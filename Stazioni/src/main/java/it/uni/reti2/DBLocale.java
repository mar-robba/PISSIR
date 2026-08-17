package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
// da spostare in un sqlite per maggiore resilienza nel caso di un fault energetico da parte della stazione

/**
 * DBLocale funge da archivio di stato in-memory per il nodo Stazione.
 * Essendo annotata con {@link ApplicationScoped}, l'istanza è unica e condivisa
 * a livello applicativo. Contiene le informazioni di base per identificare 
 * la stazione e tracciare il suo stato di salute e connettività.
 */
@ApplicationScoped
public class DBLocale {

    /**
     * Identificativo univoco della stazione.
     * Recuperato dalle properties di configurazione (es. application.properties).
     * Il valore di default è "alessandria" per agevolare il testing locale.
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    public String stazioneId;

    /**
     * Stato operativo attuale della stazione.
     * - "ONLINE": stazione operativa, binari liberi da guasti.
     * - "GUASTA": stazione inagibile (es. guasto ai sistemi di terra).
     */
    public String stato = "ONLINE";

    /**
     * Catena di eventi che sta rendendo la stazione non percorribile: l'identificativo del
     * guasto primario da cui la situazione discende (null se la stazione è agibile).
     * <p>Per un guasto ai propri sistemi di terra è la catena che parte dalla stazione stessa;
     * quando invece la stazione diventa impercorribile per colpa di un altro nodo (un convoglio
     * guasto fermo sui suoi binari) è la catena ereditata da quell'evento. Viaggia negli alert
     * che la stazione pubblica, così chi legge sa che si tratta della stessa avaria e non di
     * una nuova.</p>
     */
    public String catenaAttiva = null;

    /**
     * Catene di eventi a cui questa stazione ha già reagito: è la regola che fa terminare le
     * reazioni a catena (un nodo reagisce al massimo una volta per ogni catena) e che taglia i
     * cicli, per esempio stazione guasta - convoglio fermo - stazione guasta.
     */
    public final Set<String> cateneReagite = ConcurrentHashMap.newKeySet();

    /**
     * Le catene per cui la stazione <b>in questo momento</b> non è percorribile: il proprio
     * guasto ai sistemi di terra, il convoglio guasto fermo sui binari, o tutti e due insieme.
     *
     * <p>Serve perché le due cose possono capitare contemporaneamente e la risposta alla
     * domanda "torna percorribile quando si chiude una sola di esse?" è no: finché in questo
     * insieme rimane qualcosa la stazione resta GUASTA. Con il solo {@link #catenaAttiva} il
     * ripristino di un guasto qualsiasi rimetteva la stazione ONLINE anche con l'altro ancora
     * aperto, e i convogli ci entravano dentro.</p>
     *
     * <p>{@link #catenaAttiva} resta il valore che viaggia negli alert: è una di queste catene,
     * quella che la stazione sta dichiarando a chi le arriva addosso.</p>
     */
    public final Set<String> cateneImpercorribili = ConcurrentHashMap.newKeySet();

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
     * Viene aggiornata dai messaggi di passaggio ricevuti via MQTT.
     */
    public final Map<String, Instant> treniPresenti = new ConcurrentHashMap<>();

    /**
     * Mappa dei sensori di binario monitorati tramite keepalive.
     * Chiave: id del sensore; valore: istante dell'ultimo battito ricevuto.
     * Se un sensore non invia battiti oltre il timeout configurato viene
     * segnalato un guasto alla Centrale e rimosso dalla mappa.
     */
    public final Map<String, Instant> sensoriUltimoBattito = new ConcurrentHashMap<>();
}
