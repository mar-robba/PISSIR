package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoStatoStazione;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/*
 Dove si scrive sul database, e perché non qui.

 Questa classe è una cache in RAM: la riempie all'avvio leggendo dal database e poi la
 tengono aggiornata i servizi di monitoraggio. Le API REST la leggono per non
 ripassare dal database a ogni richiesta.

 Da quando esiste RailwayRepository ogni lettura e ogni scrittura passa da lì: nessuna
 classe chiama più findById, list o persist sulle entità. I punti in cui il database
 cambia davvero restano due:
   - i servizi di monitoraggio (MonitoraggioTreni, MonitoraggioStazioni, TransitiService,
     GuastiService), che a ogni messaggio MQTT aggiornano treni, stazioni, transiti e
     guasti e ne scrive le righe di storico (repository.salvaTreno, salvaStoricoTransito,
     salvaGuasto, ...);
   - gli endpoint amministrativi del RestApiGateway, che creano e cancellano stazioni,
     convogli, tratte e itinerari (repository.salvaStazione, eliminaTratta, ...).

 Un'eccezione c'è ed è qui sotto, in onStart(): i campi di Guasto sistemati al caricamento
 (timestamp, severita) sono persistenti e non @Transient, quindi la @Transactional del
 metodo li scrive davvero. I campi di Stazione e Treno toccati nello stesso ciclo invece
 sono @Transient e restano solo in memoria.
*/

/**
 * Cache in-memory dello stato corrente della rete ferroviaria.
 * Viene aggiornato in tempo reale dai servizi di monitoraggio e letto 
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
    /** Unico punto da cui questa classe legge il database (la cache qui sopra è RAM). */
    @Inject
    RailwayRepository repository;

    /**
     * Oltre quanti secondi di silenzio la telemetria di un convoglio è considerata
     * vecchia, e quindi la sua posizione non più garantita. Il digital twin trasmette
     * ogni secondo: qualche secondo di margine copre una riconnessione al broker senza
     * dichiarare perso un treno che sta benissimo.
     */
    @ConfigProperty(name = "posizione.telemetria.freschezza.secondi", defaultValue = "15")
    long freschezzaTelemetriaSecondi;

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

        // Popola Guasti: tipo/severita/timestamp sono ora persistiti,
        // quindi non vanno sovrascritti; si applicano solo default per righe legacy.
        for (Guasto g : repository.guastiNonRisolti()) {
            if (g.timestamp == null) g.timestamp = Instant.now();
            if (g.severita == null) g.severita = "warning";
            guastiAttivi.put(g.id, g);
        }
        System.out.println("Caricati " + guastiAttivi.size() + " guasti attivi.");

        riprendiInterventiAperti();
    }

    /**
     * Rimette in MANUTENZIONE le stazioni su cui un intervento era aperto quando la
     * Centrale si e' spenta.
     *
     * <p>Lo stato runtime non e' persistito (e' @Transient, vive in questa cache) e per
     * ONLINE/OFFLINE va benissimo cosi': il primo battito lo ricostruisce da solo. La
     * MANUTENZIONE no, perche' non e' un dato di campo — la decide la Centrale e la
     * stazione continua a dichiararsi GUASTA per conto suo. Senza questo giro un riavvio
     * fatto a intervento aperto rimetteva la stazione OFFLINE e poi ONLINE al primo
     * heartbeat, cioe' riapriva i binari con i tecnici ancora sopra.</p>
     *
     * <p>Le due condizioni vanno prese insieme: l'ultima riga di storico dice che si era
     * in manutenzione, il guasto ancora aperto dice che l'intervento non e' mai stato
     * chiuso. Con la sola riga di storico si sarebbe ripescato anche un intervento
     * concluso di cui e' arrivata dopo la chiusura.</p>
     */
    private void riprendiInterventiAperti() {
        for (Stazione stazione : stazioni.values()) {
            if (!haGuastiAperti(stazione.id, "STAZIONE")) {
                continue;
            }
            StoricoStatoStazione ultimo = repository.ultimoStatoStoricizzato(stazione.id);
            if (ultimo != null && StatiCanonici.interventoInCorso(ultimo.stato)) {
                stazione.stato = StatiCanonici.STAZIONE_MANUTENZIONE;
                System.out.println("Intervento ancora aperto su " + stazione.id
                        + ": stazione ripresa in MANUTENZIONE.");
            }
        }
    }

    /**
     * Aggiorna i dati di un treno nella cache.
     * @param treno L'entità Treno aggiornata.
     */
    // sostituisce la vecchia occorreza dell'oggetto treno mappato per il proprio id con quella nuova
    public void aggiornaTreno(Treno treno) {
        certificaPosizione(treno);
        treni.put(treno.id, treno);
    }

    /**
     * Recupera un treno dalla cache tramite ID.
     * @param id L'identificativo del convoglio.
     * @return Il treno richiesto, o null se non presente.
     */
    public Treno getTreno(String id) {
        return certificaPosizione(treni.get(id));
    }

    /**
     * Estrae la lista completa di tutti i treni noti.
     * @return Una nuova lista contenente i valori memorizzati.
     */
    public List<Treno> getTuttiTreni() {
        List<Treno> tutti = new ArrayList<>(treni.values());
        tutti.forEach(this::certificaPosizione);
        return tutti;
    }

    /**
     * Rivaluta quanto la Centrale è sicura di dove si trova il convoglio.
     *
     * <p>Serve sia in scrittura sia in lettura, e per due motivi diversi. In scrittura,
     * perché ogni frame di telemetria e ogni passaggio cambiano le premesse del giudizio,
     * e chi legge subito dopo (il broadcast dell'evento TELEMETRY) deve trovarlo già
     * aggiornato. In lettura, perché una delle condizioni è il <em>tempo</em>: un
     * convoglio il cui processo si spegne non produce più nessuna scrittura, e senza
     * questa riga resterebbe per sempre nell'ultima posizione che ha dichiarato, marcata
     * come certa.</p>
     *
     * @param treno Convoglio da valutare, anche null (i chiamanti girano su cache mancanti).
     * @return Lo stesso convoglio, per comodità di chi lo restituisce.
     */
    private Treno certificaPosizione(Treno treno) {
        PosizioneConvoglio.valuta(treno, freschezzaTelemetriaSecondi);
        return treno;
    }

    /**
     * Aggiorna i dati di una stazione nella cache.
     *
     * <p>Serve per i campi che non sono lo stato (istante dell'ultimo battito, treni
     * presenti): lo stato si scrive con {@link #cambiaStatoStazione} o
     * {@link #forzaStatoStazione}, che sono l'unico punto in cui vale la regola
     * dell'intervento in corso.</p>
     *
     * @param stazione L'entità Stazione aggiornata.
     */
    public void aggiornaStazione(Stazione stazione) {
        stazioni.put(stazione.id, stazione);
    }

    /**
     * Scrive lo stato di una stazione per un evento che <b>non</b> e' un comando
     * dell'operatore: un guasto segnalato dal campo, un battito, il watchdog.
     *
     * <p>E' qui che sta la regola dell'intervento: finche' la squadra e' sul posto lo
     * stato non si tocca (RF01.4.1). Prima ogni chiamante scriveva {@code stazione.stato}
     * per conto suo e la regola era ripetuta a memoria in due punti su cinque: bastava un
     * alert ripetuto dal campo — e una stazione guasta ne manda uno per ogni treno che
     * entra — per riportare a GUASTA una stazione in manutenzione. Da li' il primo
     * RESOLVED del campo la rimetteva ONLINE e i convogli ripartivano con i tecnici
     * ancora sui binari.</p>
     *
     * @return true se lo stato e' stato scritto davvero, false se e' stato rifiutato
     *         perche' sulla stazione c'e' un intervento in corso.
     */
    public boolean cambiaStatoStazione(Stazione stazione, String nuovoStato) {
        if (stazione == null || nuovoStato == null) {
            return false;
        }
        if (StatiCanonici.interventoInCorso(stazione.stato)
                && !StatiCanonici.interventoInCorso(nuovoStato)) {
            return false;
        }
        stazione.stato = nuovoStato;
        stazioni.put(stazione.id, stazione);
        return true;
    }

    /**
     * Scrive lo stato di una stazione per un comando esplicito della Centrale: l'invio
     * della squadra, la chiusura dell'allarme che chiude l'intervento (RF01.4.2) e il
     * pannello dell'amministratore.
     *
     * <p>E' l'unica strada che puo' far uscire una stazione dalla manutenzione, ed e'
     * giusto che sia cosi': l'intervento lo apre e lo chiude chi lo ha deciso.</p>
     */
    public void forzaStatoStazione(Stazione stazione, String nuovoStato) {
        if (stazione == null || nuovoStato == null) {
            return;
        }
        stazione.stato = nuovoStato;
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
    /**
     * Dice se una sorgente ha ancora almeno un guasto aperto, di qualunque tipo.
     *
     * <p>Diverso da {@link #getGuastoApertoPerSorgente}, che cerca l'episodio preciso per
     * non duplicarlo: qui la domanda e' "questo nodo e' pulito?", ed e' quella che serve
     * prima di dire al campo che una stazione e' di nuovo agibile. Si guarda la cache e
     * non il database perche' la si chiama anche fuori da una transazione, sul thread che
     * sta smaltendo un messaggio MQTT.</p>
     */
    public boolean haGuastiAperti(String sorgenteId, String sorgenteTipo) {
        for (Guasto g : guastiAttivi.values()) {
            if (!g.risolto
                    && sorgenteId != null && sorgenteId.equals(g.sorgenteId)
                    && sorgenteTipo != null && sorgenteTipo.equalsIgnoreCase(g.sorgenteTipo)) {
                return true;
            }
        }
        return false;
    }

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
