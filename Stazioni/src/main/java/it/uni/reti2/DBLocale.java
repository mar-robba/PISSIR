package it.uni.reti2;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DBLocale è l'archivio di stato del nodo Stazione. Essendo annotata con
 * {@link ApplicationScoped}, l'istanza è unica e condivisa a livello applicativo.
 * Contiene le informazioni di base per identificare la stazione e tracciare il suo
 * stato di salute e connettività.
 *
 * <p>Le variabili stanno in RAM, come prima, ma quelle che devono sopravvivere al
 * riavvio del nodo vengono anche scritte su un file SQLite tramite {@link PersistenzaLocale}:
 * la RAM resta la copia di lavoro (le letture sono tantissime e devono costare zero),
 * il disco è la copia buona. All'avvio il @PostConstruct rilegge il file e rimette il
 * nodo esattamente dov'era, invece di ripartire ONLINE e a binari vuoti come faceva prima.</p>
 *
 * <p>Non tutto però va persistito, e la differenza è visibile dalla forma dei membri:</p>
 * <ul>
 *   <li>lo stato <b>durevole</b> (stato operativo, treni presenti) passa dai metodi, che
 *       scrivono in RAM e su disco insieme;</li>
 *   <li>lo stato <b>volatile</b> è rimasto un campo pubblico, perché al riavvio va
 *       ricalcolato e non riletto: la connessione MQTT e la validazione dell'id si
 *       rifanno da capo comunque, e i keepalive dei sensori sono per definizione un
 *       segnale "fresco" (rimetterli da disco vorrebbe dire ripartire con dei battiti
 *       vecchi di ore e dichiarare guasti dei sensori che invece stanno benissimo).</li>
 * </ul>
 */
@ApplicationScoped
public class DBLocale {

    private static final Logger LOG = Logger.getLogger(DBLocale.class);

    /**
     * Il db locale su file: ci finiscono lo stato operativo e i treni presenti.
     */
    @Inject
    PersistenzaLocale persistenza;

    /**
     * Identificativo univoco della stazione.
     * Recuperato dalle properties di configurazione (es. application.properties).
     * Il valore di default è "S1" per agevolare il testing locale.
     * Non finisce fra le cose da ripristinare: è l'identità del processo e arriva sempre
     * dalla configurazione o dal primo argomento della riga di comando, non dal disco.
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    public String stazioneId;

    /**
     * Stato operativo attuale della stazione.
     * - "ONLINE": stazione operativa, binari liberi da guasti.
     * - "GUASTA": stazione inagibile (es. guasto ai sistemi di terra).
     * Ci si arriva solo da {@link #getStato()} e {@link #setStato(String)} perché ogni
     * cambiamento deve finire anche sul db locale.
     */
    private volatile String stato = "ONLINE";

    /**
     * Istante di inizio dell'ultimo guasto (colonna timestampInizioUltimoFault).
     * Resta null finché la stazione non si è mai guastata.
     */
    private volatile Instant inizioUltimoFault = null;

    /**
     * Istante in cui l'ultimo guasto è stato risolto (colonna timestampFineUltimoFault).
     * Se la stazione è GUASTA in questo momento è più vecchio di inizioUltimoFault.
     */
    private volatile Instant fineUltimoFault = null;

    // per il riconoscimento della stazione
    public boolean stazioneRiconosciuta = false ;

    /**
     * Flag booleano che indica se la connessione verso il sistema centrale (Centrale Operativa)
     * è attualmente attiva e funzionante. Viene utilizzato per gestire le logiche di buffering
     * locale in caso di disconnessione (fallback mode).
     */
    public boolean connessioneCentrale = true;

    /**
     * Mappa dei treni fisicamente presenti in stazione in questo momento.
     * Chiave: id del treno; valore: istante in cui il treno è entrato (evento ENTRATA).
     * Viene aggiornata dai messaggi di passaggio ricevuti via MQTT e rispecchiata sulla
     * tabella Treno del db locale, così un convoglio fermo sui binari durante un blackout
     * risulta ancora fermo lì quando il nodo torna su.
     */
    private final Map<String, Instant> treniPresenti = new ConcurrentHashMap<>();

    /**
     * Mappa dei sensori di binario monitorati tramite keepalive.
     * Chiave: id del sensore; valore: istante dell'ultimo battito ricevuto.
     * La riempie {@link MonitorSensori} all'avvio con i sensori che la stazione si aspetta
     * di sentire, e la aggiorna a ogni battito che arriva. Un sensore che sfora il timeout
     * NON viene tolto da qui: resta in osservazione proprio perché è dal suo battito
     * successivo che la stazione capisce che il guasto è rientrato.
     */
    public final Map<String, Instant> sensoriUltimoBattito = new ConcurrentHashMap<>();

    /**
     * I sensori attualmente considerati fermi (fail-stop), cioè quelli che hanno sforato il
     * timeout dei keepalive. Serve a due cose: mandare l'allarme alla Centrale una volta
     * sola invece che a ogni giro di controllo, e sapere quando l'ultimo sensore muto è
     * tornato a battere, che è il momento in cui la stazione può tornare operativa.
     * Anche questa è roba volatile: al riavvio i battiti si riascoltano da capo.
     */
    public final Set<String> sensoriGuasti = ConcurrentHashMap.newKeySet();

    /**
     * true quando lo stato GUASTA è stato deciso da {@link MonitorSensori} per colpa dei
     * sensori muti. Senza questa distinzione il ritorno di un keepalive rimetterebbe
     * operativa anche una stazione dichiarata GUASTA per tutt'altro motivo (per esempio un
     * convoglio deragliato sui binari, che è la Centrale a comunicare): il sensore che
     * ricomincia a battere non ha nessun titolo per cancellare quel guasto.
     */
    public volatile boolean guastoDaSensori = false;

    /**
     * Ricostruisce lo stato del nodo leggendo il db locale.
     * Se il file non esiste (primo avvio della stazione) o la persistenza è spenta non
     * trova niente e restano i valori di default, cioè il comportamento di prima.
     */
    @PostConstruct
    void ripristinaDaDisco() {
        PersistenzaLocale.StatoPersistito salvato = persistenza.leggiStato(stazioneId);
        if (salvato != null) {
            stato = salvato.stato();
            inizioUltimoFault = salvato.inizioUltimoFault();
            fineUltimoFault = salvato.fineUltimoFault();
            LOG.infof("💾 Stato ripristinato dal db locale: la stazione riparte %s.", stato);
        } else {
            // Prima accensione di questo nodo: si scrive subito la riga, così il file
            // contiene lo stato corrente anche se poi non succede più niente.
            persistenza.salvaStato(stazioneId, stato, null, null);
        }

        treniPresenti.putAll(persistenza.leggiTreniPresenti());
        if (!treniPresenti.isEmpty()) {
            LOG.infof("💾 Ripristinati %d treni ancora presenti in stazione: %s",
                    treniPresenti.size(), treniPresenti.keySet());
        }
    }

    /**
     * @return lo stato operativo corrente ("ONLINE" oppure "GUASTA").
     */
    public String getStato() {
        return stato;
    }

    /**
     * Cambia lo stato operativo della stazione e lo scrive sul db locale.
     * Tiene aggiornati anche i due timestamp del modello: entrando in GUASTA si segna
     * l'inizio del fault, tornando ONLINE se ne segna la fine e si chiudono i guasti
     * locali rimasti aperti.
     *
     * <p>Se lo stato richiesto è già quello corrente non si fa nulla: senza questo
     * controllo un secondo alert CRITICAL a stazione già guasta sposterebbe in avanti
     * l'inizio del fault, e il tempo di fuori servizio risulterebbe più corto del vero.</p>
     *
     * @param nuovoStato "ONLINE" oppure "GUASTA".
     */
    public synchronized void setStato(String nuovoStato) {
        if (stato.equals(nuovoStato)) {
            return;
        }
        Instant adesso = Instant.now();
        if ("GUASTA".equals(nuovoStato)) {
            inizioUltimoFault = adesso;
        } else {
            fineUltimoFault = adesso;
            // Si torna operativi: nessun guasto locale è più in corso.
            persistenza.risolviGuastiAperti(adesso);
            // Si riparte a sorvegliare i sensori con la lavagna pulita. Se il ritorno in
            // servizio l'ha deciso la Centrale (RESOLVED dell'operatore) ma un sensore è
            // ancora muto davvero, al primo giro di controllo il timeout scatta di nuovo e
            // la stazione si ridichiara GUASTA: meglio un allarme ripetuto che una stazione
            // data per buona mentre nessuno guarda i binari.
            sensoriGuasti.clear();
            guastoDaSensori = false;
        }
        stato = nuovoStato;
        persistenza.salvaStato(stazioneId, stato, inizioUltimoFault, fineUltimoFault);
    }

    /**
     * @return istante di inizio dell'ultimo guasto, null se la stazione non si è mai guastata.
     */
    public Instant getInizioUltimoFault() {
        return inizioUltimoFault;
    }

    /**
     * @return istante di risoluzione dell'ultimo guasto, null se non è mai stato risolto.
     */
    public Instant getFineUltimoFault() {
        return fineUltimoFault;
    }

    /**
     * Registra l'ingresso di un convoglio sui binari della stazione, in RAM e su disco.
     *
     * @param trenoId identificativo del convoglio entrato.
     */
    public void registraEntrataTreno(String trenoId) {
        Instant adesso = Instant.now();
        treniPresenti.put(trenoId, adesso);
        persistenza.registraEntrataTreno(trenoId, adesso);
    }

    /**
     * Registra l'uscita di un convoglio dalla stazione, in RAM e su disco.
     *
     * @param trenoId identificativo del convoglio uscito.
     */
    public void registraUscitaTreno(String trenoId) {
        treniPresenti.remove(trenoId);
        persistenza.registraUscitaTreno(trenoId, Instant.now());
    }

    /**
     * Espone i treni presenti in sola lettura: si aggiunge e si toglie solo dai due
     * metodi qui sopra, se no la mappa in RAM e la tabella Treno finirebbero per dire
     * due cose diverse.
     *
     * @return mappa trenoId -&gt; istante di entrata, non modificabile.
     */
    public Map<String, Instant> getTreniPresenti() {
        return Collections.unmodifiableMap(treniPresenti);
    }
}
