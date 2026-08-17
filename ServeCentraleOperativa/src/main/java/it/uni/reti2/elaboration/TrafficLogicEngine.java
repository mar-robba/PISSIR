package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**tesi

 Ruolo TrafficLogicEngine: è una cache in-memory aggiornata dal consumer MQTT (IngestionService) e usata dalle API REST; non è il componente che normalmente scrive sul DB. Vedi TrafficLogicEngine.java:37-66.
 Quando il DB viene aggiornato (e dove)

 Telemetria (treni): scrive/crea Treno e inserisce righe di storico quando cambia stato — vedi treno.persist() e StoricoStatoTreno.persist() in IngestionService.java:120-160.
 Heartbeat (stazioni): crea/aggiorna Stazione e registra uno StoricoStatoStazione — vedi stazione.persist() e storico.persist() in IngestionService.java:180-210.
 Transiti / Passaggi: crea/chiude Transito e inserisce StoricoTransito — vedi transito.persist() e storicizzaTransito(...) in IngestionService.java:236-308.
 Allarmi (guasti): crea Guasto e relativo storico — vedi guasto.persist() e StoricoGuasto.persist() in IngestionService.java:340-364.
 Aggiornamento posizione treno: l'endpoint/consumer aggiorna dbTreno.posizioneAttualeTratta (persistente) in IngestionService.java:447-453.
 Operazioni REST amministrative: anche gli endpoint HTTP del RestApiGateway possono persistere entità (es. stazione.persist(), treno.persist()), vedi RestApiGateway.java:120-140 e [..#L220-L240].
 Nota sul TrafficLogicEngine e persistenza

 TrafficLogicEngine.onStart() è annotato @Transactional e carica la cache dal DB all'avvio; nel ciclo di inizializzazione imposta alcuni campi volatili (es. s.stato, t.ultimoAggiornamento) che sono marcati @Transient e quindi NON vengono persistiti (vedi Stazione.java:68-76 e Treno.java:1-30).
 Eccezione: durante l’inizializzazione onStart() il codice imposta default per campi persistenti di Guasto (es. timestamp, severita) — questi campi sono persistiti nel DB perché non sono @Transient (vedi Guasto.java:57-74 e TrafficLogicEngine.java:62-64). Quindi un aggiornamento puntuale al DB può avvenire all'avvio per quei campi.
 Conclusione breve
**/

/**
 * Cache in-memory dello stato corrente della rete ferroviaria.
 * Viene aggiornato dal consumer MQTT in tempo reale (IngestionService) e letto 
 * in modo veloce dalle API REST (RestApiGateway), fungendo da strato intermedio
 * per ridurre il carico sul database relazionale.
 * e allora quando il database in back aggiorna il proprio stato e viceversa
 */
@ApplicationScoped
public class TrafficLogicEngine {

    /** Mappa thread-safe per indicizzare i treni in base al loro ID univoco */
    private final Map<String, Treno> treni = new ConcurrentHashMap<>();
    
    /** Mappa thread-safe per indicizzare le stazioni */
    private final Map<String, Stazione> stazioni = new ConcurrentHashMap<>();
    
    /** Mappa thread-safe per tenere traccia dei guasti attualmente segnalati e non ancora risolti */
    private final Map<String, Guasto> guastiAttivi = new ConcurrentHashMap<>();

    /**
     * Mappa thread-safe degli archi della rete con la loro percorribilità corrente
     * (RF02.1.2.2.2). Ci sta per lo stesso motivo delle altre due: la percorribilità è stato
     * corrente, e lo stato corrente in questo sistema vive in RAM.
     */
    private final Map<String, Tratta> tratte = new ConcurrentHashMap<>();

    /**
     * La cache si riempie dal database una volta sola, all'avvio: è l'unico punto in cui
     * questa classe ha bisogno del repository. Da lì in poi lavora solo sulle tre mappe.
     */
    @Inject
    RailwayRepository repository;
/*Prevenire errori di "Lazy Loading" (Caricamento Pigro): Se la tua entità Stazioni ha dei campi collegati (ad esempio una lista di Sensori o di Storici) che non vengono caricati immediatamente dalla query principale, l'ORM (come Hibernate) proverà a leggerli nel momento in cui accedi a quei campi. Senza una transazione aperta, riceveresti un blocco totale (il famoso errore LazyInitializationException).***/
    @Transactional
    void onStart(@Observes StartupEvent ev) {
        System.out.println("TrafficLogicEngine: Inizializzazione cache dal DB...");

        // Popola Stazioni: i campi persistiti (nome, coordinate, binari, tipo)
        // arrivano direttamente dal DB. Lo stato runtime NON viene inventato: resta
        // il default OFFLINE dell'entità con ultimoHeartbeat a null, perché all'avvio
        // nessuna stazione ha ancora battuto (marcarle ONLINE faceva vedere per 30
        // secondi una rete tutta operativa anche a nodi spenti). Il FaultMonitor
        // salta le stazioni con heartbeat nullo, quindi non apre falsi guasti.
        for (Stazione s : repository.tutteLeStazioni()) {
            stazioni.put(s.id, s);
        }
        System.out.println("Caricate " + stazioni.size() + " stazioni.");

        // Popola Treni: l'id del convoglio è il nome scelto dall'amministratore,
        // non c'è nessun altro campo anagrafico da ricostruire.
        // ultimoAggiornamento resta NULL, come ultimoHeartbeat per le stazioni: all'avvio
        // nessun convoglio ha ancora trasmesso, e datare la telemetria all'accensione della
        // Centrale faceva sembrare "appena visti" treni il cui processo non è nemmeno acceso.
        // Il FaultMonitor salta i treni con ultimoAggiornamento nullo, quindi non apre più i
        // guasti "treno fermo" falsi che comparivano dieci secondi dopo ogni riavvio.
        for (Treno t : repository.tuttiITreni()) {
            treni.put(t.id, t);
        }
        System.out.println("Caricati " + treni.size() + " treni.");

        // Popola le Tratte: dal DB arrivano gli estremi e il tempo di percorrenza, la
        // percorribilità no. Riparte PERCORRIBILE per tutte, come le stazioni ripartono
        // OFFLINE: è stato corrente, e lo ridichiara chi lo sa (il convoglio fermo sull'arco
        // ripubblica la propria reazione).
        for (Tratta t : repository.tutteLeTratte()) {
            tratte.put(t.id, t);
        }
        System.out.println("Caricate " + tratte.size() + " tratte.");

        // Popola Guasti: tipo/severita/timestamp sono ora persistiti,
        // quindi non vanno sovrascritti; si applicano solo default per righe legacy.
        for (Guasto g : repository.guastiNonRisolti()) {
            if (g.timestamp == null) g.timestamp = Instant.now();
            if (g.severita == null) g.severita = "warning";
            guastiAttivi.put(g.id, g);
        }
        System.out.println("Caricati " + guastiAttivi.size() + " guasti attivi.");
    }

    /**
     * Aggiorna i dati di un treno nella cache.
     * @param treno L'entità Treno aggiornata.
     */
    // sostituisce la vecchia occorreza dell'oggetto treno mappato per il proprio id con quella nuova
    public void aggiornaTreno(Treno treno) {
        treni.put(treno.id, treno);
    }

    /**
     * Recupera un treno dalla cache tramite ID.
     * @param id L'identificativo del convoglio.
     * @return Il treno richiesto, o null se non presente.
     */
    public Treno getTreno(String id) {
        return treni.get(id);
    }

    /**
     * Estrae la lista completa di tutti i treni noti.
     * @return Una nuova lista contenente i valori memorizzati.
     */
    public List<Treno> getTuttiTreni() {
        return new ArrayList<>(treni.values());
    }

    /**
     * Aggiorna i dati di una stazione nella cache.
     * @param stazione L'entità Stazione aggiornata.
     */
    public void aggiornaStazione(Stazione stazione) {
        stazioni.put(stazione.id, stazione);
    }

    /**
     * Recupera una stazione dalla cache tramite ID.
     * @param id L'identificativo della stazione.
     * @return La stazione richiesta, o null se non presente.
     */
    public Stazione getStazione(String id) {
        return stazioni.get(id);
    }

    /**
     * Estrae la lista completa di tutte le stazioni monitorate.
     * @return Una nuova lista delle stazioni.
     */
    public List<Stazione> getTutteStazioni() {
        return new ArrayList<>(stazioni.values());
    }

    /**
     * Aggiorna (o inserisce) un arco della rete nella cache.
     * @param tratta L'arco con la percorribilità aggiornata.
     */
    public void aggiornaTratta(Tratta tratta) {
        tratte.put(tratta.id, tratta);
    }

    /**
     * Recupera un arco dalla cache tramite ID.
     * @param id L'identificativo della tratta.
     * @return La tratta richiesta, o null se non presente.
     */
    public Tratta getTratta(String id) {
        return tratte.get(id);
    }

    /**
     * Estrae l'elenco completo degli archi della rete con la loro percorribilità.
     * @return Una nuova lista delle tratte.
     */
    public List<Tratta> getTutteTratte() {
        return new ArrayList<>(tratte.values());
    }

    /**
     * Rimuove un arco dalla cache (usato dalla DELETE REST).
     * @param id ID della tratta da rimuovere.
     */
    public void rimuoviTratta(String id) {
        tratte.remove(id);
    }

    /**
     * Inserisce un nuovo allarme/guasto nella mappa dei guasti attivi.
     * @param guasto Il guasto appena rilevato.
     */
    public void aggiungiGuasto(Guasto guasto) {
        guastiAttivi.put(guasto.id, guasto);
    }

    /**
     * Contrassegna un guasto come risolto all'interno della cache.
     * @param id ID dell'allarme da chiudere.
     */
    public void risolviGuasto(String id) {
        if (guastiAttivi.containsKey(id)) {
            Guasto g = guastiAttivi.get(id);
            g.risolto = true;
            guastiAttivi.put(id, g);
            // Non lo rimuoviamo subito dalla cache così il frontend ha tempo di vedere che è stato risolto
            // Eventualmente si potrebbe pulire dopo un timeout o spostarlo in uno storico dedicato.
        }
    }

    /**
     * Ritorna l'elenco dei guasti (sia attivi che recentemente risolti ma non ancora rimossi).
     * @return Lista di guasti.
     */
    public List<Guasto> getGuastiAttivi() {
        return new ArrayList<>(guastiAttivi.values());
    }

    /**
     * Rimuove un treno dalla cache (usato dalla DELETE REST).
     * @param id ID del convoglio da rimuovere.
     */
    public void rimuoviTreno(String id) {
        treni.remove(id);
    }

    /**
     * Rimuove una stazione dalla cache (usato dalla DELETE REST).
     * @param id ID della stazione da rimuovere.
     */
    public void rimuoviStazione(String id) {
        stazioni.remove(id);
    }

    /**
     * Cerca un guasto ancora aperto generato da una determinata sorgente e con un
     * determinato tipo. Usato dal FaultMonitor per evitare di creare guasti duplicati
     * per lo stesso episodio (es. heartbeat mancante segnalato una sola volta).
     *
     * @param sorgenteId ID della sorgente (treno o stazione).
     * @param tipo Tipologia del guasto (es. "sensore_offline", "treno_fermo").
     * @return Il guasto aperto corrispondente, o null se non esiste.
     */
    public Guasto getGuastoApertoPerSorgente(String sorgenteId, String tipo) {
        for (Guasto g : guastiAttivi.values()) {
            if (!g.risolto
                    && sorgenteId != null && sorgenteId.equals(g.sorgenteId)
                    && tipo != null && tipo.equals(g.tipo)) {
                return g;
            }
        }
        return null;
    }

    /**
     * Calcola i KPI della dashboard nel formato atteso dal frontend.
     * Usato sia da GET /api/dashboard che dal broadcast SNAPSHOT del FaultMonitor.
     * @return Mappa con i contatori aggregati della rete.
     */
    public Map<String, Object> kpiDashboard() {
        List<Treno> tuttiTreni = getTuttiTreni();
        List<Stazione> tutteStazioni = getTutteStazioni();

        long inMovimento = tuttiTreni.stream().filter(t -> "attivo".equalsIgnoreCase(t.stato)).count();
        long inRitardo = tuttiTreni.stream().filter(t -> t.ritardo > 0).count();
        long operative = tutteStazioni.stream().filter(s -> "ONLINE".equalsIgnoreCase(s.stato)).count();
        long guaste = tutteStazioni.stream()
                .filter(s -> "GUASTA".equalsIgnoreCase(s.stato) || "OFFLINE".equalsIgnoreCase(s.stato))
                .count();
        long allarmi = guastiAttivi.values().stream().filter(g -> !g.risolto).count();
        double mediaRitardo = tuttiTreni.stream().mapToInt(t -> t.ritardo).average().orElse(0.0);

        Map<String, Object> kpi = new java.util.HashMap<>();
        kpi.put("totalTrains", tuttiTreni.size());
        kpi.put("trainsInMotion", inMovimento);
        kpi.put("trainsDelayed", inRitardo);
        kpi.put("stationsOperative", operative);
        kpi.put("stationsFaulty", guaste);
        kpi.put("activeAlerts", allarmi);
        kpi.put("avgDelay", mediaRitardo);
        return kpi;
    }
}
