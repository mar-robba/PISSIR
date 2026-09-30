package it.uni.reti2;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * DBLocale è l'archivio di stato del nodo Stazione: un oggetto solo, creato dal main e
 * condiviso da tutte le classi che ne hanno bisogno. Contiene le informazioni di base per
 * identificare la stazione e tracciare il suo stato di salute e connettività.
 *
 * <p>Qui lo stato si tiene e basta: a cambiarlo sono gli automi (AutomaStatoStazione,
 * AutomaSensori, AutomaTreniPresenti, AutomaConnessione), che fa muovere GestoreEventi. Ogni
 * variabile ha un solo automa che la scrive, ed è scritto nel suo commento. Fa eccezione
 * stazioneRiconosciuta, che la scrive StationDatabaseValidator, come nel treno.</p>
 *
 * <p>Le variabili stanno in RAM, ma quelle che devono sopravvivere al riavvio del nodo vengono
 * anche scritte su un file SQLite tramite {@link PersistenzaLocale}: la RAM resta la copia di
 * lavoro (le letture sono tantissime e devono costare zero), il disco è la copia buona.
 * All'avvio il main chiama {@link #ripristinaDaDisco()}, che rilegge il file e rimette il nodo
 * esattamente dov'era, invece di ripartire ONLINE e a binari vuoti.</p>
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
public class DBLocale {

    private static final Logger LOG = Logger.getLogger(DBLocale.class.getName());

    /**
     * Il db locale su file: ci finiscono lo stato operativo e i treni presenti.
     * Assegnato dal main.
     */
    PersistenzaLocale persistenza;

    /**
     * Identificativo univoco della stazione. Lo assegna il main prima di creare qualunque
     * altra cosa: arriva dal primo argomento della riga di comando o, se manca, dalla
     * property stazione.id. Non finisce fra le cose da ripristinare: è l'identità del
     * processo, non il suo stato.
     */
    public String stazioneId;

    /**
     * Stato operativo attuale della stazione (S10, lo scrive AutomaStatoStazione).
     * - "ONLINE": stazione operativa, binari liberi da guasti.
     * - "GUASTA": stazione inagibile (es. guasto ai sistemi di terra).
     * Ci si arriva solo da {@link #getStato()} e {@link #scriviStato} perché ogni
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

    /**
     * true quando la stazione è GUASTA soltanto perché dei sensori di binario hanno smesso di
     * battere (S10, lo scrive AutomaStatoStazione). È quello che le permette di tornare ONLINE
     * da sola quando i battiti riprendono. Se è GUASTA anche per un altro motivo (un'avaria
     * segnalata da un sottosistema locale, o un convoglio deragliato sui binari, che è la
     * Centrale a comunicare) vale false: il sensore che ricomincia a battere non ha nessun
     * titolo per cancellare quel guasto.
     */
    public volatile boolean guastoDaSensori = false;

    /**
     * La Centrale ha confermato che l'id della stazione esiste nel database centrale (S2, lo
     * scrive StationDatabaseValidator). Finché è false l'heartbeat non parte.
     */
    public volatile boolean stazioneRiconosciuta = false;

    /**
     * Il collegamento verso la Centrale Operativa è attivo (S4, lo scrive AutomaConnessione).
     * Lo spengono e lo riaccendono soltanto le POST /stazione/rete/offline e /rete/online,
     * cioè la caduta simulata della demo: finché è false transiti e allarmi restano nel buffer
     * locale e l'heartbeat non parte. Una caduta vera del broker non lo tocca: la assorbe la
     * coda del LocalBuffer, perché l'evento che non riesce a partire resta in testa.
     */
    public volatile boolean connessioneCentrale = true;

    /**
     * Mappa dei treni fisicamente presenti in stazione in questo momento (S11, la scrive
     * AutomaTreniPresenti). Chiave: id del treno; valore: istante in cui il treno è entrato
     * (evento ENTRATA). È rispecchiata sulla tabella Treno del db locale, così un convoglio
     * fermo sui binari durante un blackout risulta ancora fermo lì quando il nodo torna su.
     */
    private final Map<String, Instant> treniPresenti = new ConcurrentHashMap<>();

    /**
     * Mappa dei sensori di binario monitorati tramite keepalive (S7, la scrive AutomaSensori).
     * Chiave: id del sensore; valore: istante dell'ultimo battito ricevuto.
     * All'avvio ci finiscono i sensori che la stazione si aspetta di sentire, poi la aggiorna
     * ogni battito che arriva. Un sensore che sfora il timeout NON viene tolto da qui: resta
     * in osservazione proprio perché è dal suo battito successivo che la stazione capisce che
     * il guasto è rientrato.
     */
    public final Map<String, Instant> sensoriUltimoBattito = new ConcurrentHashMap<>();

    /**
     * I sensori attualmente considerati fermi (fail-stop), cioè quelli che hanno sforato il
     * timeout dei keepalive (S7, lo scrive AutomaSensori). Serve a due cose: mandare l'allarme
     * alla Centrale una volta sola invece che a ogni giro di controllo, e sapere quando l'ultimo
     * sensore muto è tornato a battere. Anche questa è roba volatile: al riavvio i battiti si
     * riascoltano da capo.
     */
    public final Set<String> sensoriGuasti = ConcurrentHashMap.newKeySet();

    /**
     * Ricostruisce lo stato del nodo leggendo il db locale. La chiama il main all'avvio,
     * dopo aver assegnato l'id e aperto la persistenza.
     * Se il file non esiste (primo avvio della stazione) o la persistenza è spenta non
     * trova niente e restano i valori di default, cioè ONLINE e binari vuoti.
     */
    public void ripristinaDaDisco() {
        PersistenzaLocale.StatoPersistito salvato = persistenza.leggiStato(stazioneId);
        if (salvato != null) {
            stato = salvato.stato();
            inizioUltimoFault = salvato.inizioUltimoFault();
            fineUltimoFault = salvato.fineUltimoFault();
            LOG.info(String.format("💾 Stato ripristinato dal db locale: la stazione riparte %s.", stato));
        } else {
            // Prima accensione di questo nodo: si scrive subito la riga, così il file
            // contiene lo stato corrente anche se poi non succede più niente.
            persistenza.salvaStato(stazioneId, stato, null, null);
        }

        treniPresenti.putAll(persistenza.leggiTreniPresenti());
        if (!treniPresenti.isEmpty()) {
            LOG.info(String.format("💾 Ripristinati %d treni ancora presenti in stazione: %s",
                    treniPresenti.size(), treniPresenti.keySet()));
        }
    }

    /**
     * @return lo stato operativo corrente ("ONLINE" oppure "GUASTA").
     */
    public String getStato() {
        return stato;
    }

    /**
     * Scrive lo stato operativo e i due istanti del fault, in RAM e sul db locale.
     * Non controlla niente: la chiama soltanto AutomaStatoStazione, quando le guardie del
     * gestore degli eventi hanno già deciso che la mossa si fa.
     *
     * @param nuovoStato  "ONLINE" oppure "GUASTA".
     * @param inizioFault istante di inizio dell'ultimo guasto, può essere null.
     * @param fineFault   istante di fine dell'ultimo guasto, può essere null.
     */
    public synchronized void scriviStato(String nuovoStato, Instant inizioFault, Instant fineFault) {
        stato = nuovoStato;
        inizioUltimoFault = inizioFault;
        fineUltimoFault = fineFault;
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
     * La chiama soltanto AutomaTreniPresenti.
     *
     * @param trenoId identificativo del convoglio entrato.
     * @param quando  istante dell'ingresso.
     */
    public void registraEntrataTreno(String trenoId, Instant quando) {
        treniPresenti.put(trenoId, quando);
        persistenza.registraEntrataTreno(trenoId, quando);
    }

    /**
     * Registra l'uscita di un convoglio dalla stazione, in RAM e su disco.
     * La chiama soltanto AutomaTreniPresenti.
     *
     * @param trenoId identificativo del convoglio uscito.
     * @param quando  istante dell'uscita.
     */
    public void registraUscitaTreno(String trenoId, Instant quando) {
        treniPresenti.remove(trenoId);
        persistenza.registraUscitaTreno(trenoId, quando);
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
