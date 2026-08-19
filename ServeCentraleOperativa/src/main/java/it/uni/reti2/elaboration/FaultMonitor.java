package it.uni.reti2.elaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import it.uni.reti2.entity.EventoStazione;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoGuasto;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.RealtimeWebSocket;
import it.uni.reti2.ingestion.IngestionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watchdog della Centrale Operativa: controlla periodicamente lo stato della rete
 * per rilevare in autonomia le anomalie che i nodi edge non possono segnalare da soli
 * (es. una stazione che smette di inviare heartbeat perché è caduta la connessione).
 *
 * <p>Espone tre job schedulati ogni 10 secondi:</p>
 * <ul>
 *   <li>{@code controllaHeartbeat}: stazioni silenti da troppo tempo → OFFLINE + guasto automatico;</li>
 *   <li>{@code controllaTreniFermi}: treni "attivi" ma immobili fuori dalle stazioni → guasto automatico;</li>
 *   <li>{@code broadcastSnapshot}: fotografia KPI della rete inviata via WebSocket al frontend.</li>
 * </ul>
 */
@ApplicationScoped
public class FaultMonitor {

    private static final Logger LOG = Logger.getLogger(FaultMonitor.class);

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RealtimeWebSocket webSocket;

    @Inject
    IngestionService ingestion;

    /* È una classe fondamentale che fa parte della libreria Jackson (il motore JSON standard usato da Quarkus). Il suo compito principale è fare due cose:
    Serializzazione (Object → JSON): Prende un normale oggetto Java (POJO) e lo trasforma in una stringa di testo in formato JSON.
    Deserializzazione (JSON → Object): Prende una stringa di testo JSON (o un file) e la trasforma in un oggetto Java per poterci lavorare comodamente nel codice.
     */
    @Inject
    ObjectMapper mapper;
    /*
    Cerca nel file application.properties una chiave chiamata centrale.stazione.timeout-secondi.
    Se la trovi, prendi il suo valore, convertilo in un numero (long o int) e inseriscilo nella variabile secondiSilenzio.
    Se non la trovi (o il file non esiste), usa il valore di default, ovvero 60.
    */
    /** Secondi di silenzio oltre i quali una stazione viene considerata OFFLINE. */
    @ConfigProperty(name = "fault.heartbeat.timeout.secondi", defaultValue = "30")
    long heartbeatTimeoutSecondi;

    /** Secondi senza movimento/aggiornamenti oltre i quali un treno attivo è considerato fermo anomalo. */
    @ConfigProperty(name = "fault.treno.fermo.timeout.secondi", defaultValue = "90")
    long trenoFermoTimeoutSecondi;

    /**
     * Da quando ogni convoglio risulta fermo fuori da una stazione (id treno → istante del
     * primo rilevamento). Serve a far scattare l'anomalia solo se la condizione dura almeno
     * {@code T_fer}: senza memoria il watchdog poteva solo guardare l'istante presente e
     * apriva il guasto al primo giro in cui vedeva velocità zero.
     * Mappa concorrente perché il job gira su un thread di lavoro dello scheduler.
     */
    private final Map<String, Instant> fermiDa = new ConcurrentHashMap<>();

    /**
     * Rileva le stazioni che non inviano heartbeat da più del timeout configurato:
     * le marca OFFLINE e apre un guasto automatico (una sola volta per episodio).
     */
    /*Grazie a @Scheduled(every = "10s"), Quarkus sa che questa funzione è un Job.

    Il countdown: All'avvio dell'applicazione, il motore di pianificazione di Quarkus (basato su Vert.x) fa partire un timer interno.

    Il thread dedicato: Scaduti i 10 secondi, il framework sveglia un filo di esecuzione secondario (un worker thread in background) e gli dice: "Esegui controllaHeartbeat()". In questo modo l'applicazione principale (che risponde alle richieste REST degli utenti) rimane libera e reattiva.
    * */
    // ATTENZIOONE !!!! RACE CONDITON
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP) // <-- Evita sovrapposizioni nello stesso server
    // NIENTE @Transactional sul metodo: con quell'annotazione il commit avviene all'uscita,
    // cioè DOPO che il guasto è già stato annunciato a dashboard e treni. Le scritture
    // stanno adesso dentro QuarkusTransaction.requiringNew() e la notifica parte solo a
    // commit avvenuto (stesso ragionamento già applicato in IngestionService).
    public void controllaHeartbeat() {
        Instant limite = Instant.now().minus(Duration.ofSeconds(heartbeatTimeoutSecondi));
        for (Stazione stazione : statoRete.getTutteStazioni()) {
            if ("OFFLINE".equalsIgnoreCase(stazione.stato)
                    || "MANUTENZIONE".equalsIgnoreCase(stazione.stato)) {
                continue;
            }
            if (stazione.ultimoHeartbeat != null && stazione.ultimoHeartbeat.isBefore(limite)) {
                LOG.warnf("⚠️ [FaultMonitor] Stazione %s senza heartbeat da oltre %d secondi → OFFLINE",
                        stazione.id, heartbeatTimeoutSecondi);
                stazione.stato = "OFFLINE";
                statoRete.aggiornaStazione(stazione);
                // Il cambio di stato va spinto sulla WebSocket: è proprio il caso in cui
                // l'HEARTBEAT non arriva più, quindi senza questo la mappa continuerebbe a
                // mostrare la stazione operativa fino al prossimo F5.
                ingestion.broadcastStatoStazione(stazione);

                // Evita duplicati: un solo guasto aperto per episodio di silenzio
                if (statoRete.getGuastoApertoPerSorgente(stazione.id, "sensore_offline") == null) {
                    // Severità CRITICAL (non warning): è il fail-stop della stazione, e
                    // TrainGateway.riceviAlert blocca i treni solo sui guasti CRITICAL.
                    // Il messaggio parte con MSG_HEARTBEAT_PERSO così la Centrale
                    // riconosce il guasto come "suo" e lo richiude da sola quando il
                    // battito torna (vedi IngestionService.onHeartbeat).
                    creaGuastoAutomatico(
                            "sensore_offline", "CRITICAL", "STAZIONE", stazione.id,
                            IngestionService.MSG_HEARTBEAT_PERSO + " la stazione " + stazione.id
                                    + " non invia heartbeat da oltre " + heartbeatTimeoutSecondi + " secondi",
                            "HEARTBEAT_LOST");
                }
            }
        }
    }

    /**
     * Rileva i convogli fermi fuori da una stazione (RF02.6.2, scenario SV04: "un
     * convoglio è fermo fra due stazioni per una causa qualsiasi").
     *
     * <p>Il controllo guarda <strong>dove</strong> si trova il treno, non lo stato che
     * dichiara. Prima filtrava sui soli treni in stato "attivo", cioè esattamente quelli
     * che si stanno muovendo: un convoglio davvero fermo dichiara FERMO (trattenuto da una
     * stazione guasta) o EMERGENZA (avaria di bordo) e veniva quindi saltato, mentre
     * l'unico caso che faceva scattare l'allarme erano i treni caricati dal database
     * all'avvio e mai visti — dieci secondi dopo l'accensione della Centrale si aprivano
     * guasti falsi su tutta la flotta.</p>
     *
     * <p>Due precauzioni contro i falsi positivi, le stesse già adottate per le stazioni:
     * si saltano i convogli che non hanno mai trasmesso (telemetria mai arrivata, quindi
     * processo spento: non è un'anomalia di marcia) e si pretende che la condizione duri
     * almeno {@code T_fer} secondi prima di aprire il guasto. Prima {@code velocita == 0}
     * apriva l'allarme al primo giro utile, senza aspettare niente.</p>
     */
    @Scheduled(every = "10s",  concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void controllaTreniFermi() {
        Instant adesso = Instant.now();
        Instant limiteTelemetria = adesso.minus(Duration.ofSeconds(trenoFermoTimeoutSecondi));
        Set<String> treniEsistenti = new HashSet<>();

        for (Treno treno : statoRete.getTuttiTreni()) {
            treniEsistenti.add(treno.id);
            // Un convoglio soppresso è fermo per decisione dell'operatore: non è un'anomalia.
            if ("in manutenzione".equalsIgnoreCase(treno.stato)) {
                fermiDa.remove(treno.id);
                continue;
            }
            // Mai trasmesso: il processo del treno non è acceso, non c'è niente da dedurre.
            if (treno.ultimoAggiornamento == null) {
                fermiDa.remove(treno.id);
                continue;
            }

            boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
            boolean immobile = treno.velocita == 0 || treno.ultimoAggiornamento.isBefore(limiteTelemetria);

            if (!immobile || inStazione) {
                fermiDa.remove(treno.id); // si muove, o è fermo dove è lecito esserlo
                continue;
            }

            // Da quanto dura la condizione? Il primo giro registra solo l'istante.
            Instant fermoDa = fermiDa.putIfAbsent(treno.id, adesso);
            if (fermoDa == null) {
                continue;
            }
            long secondiFermo = Duration.between(fermoDa, adesso).getSeconds();
            if (secondiFermo < trenoFermoTimeoutSecondi) {
                continue; // T_fer non ancora scaduto
            }

            if (statoRete.getGuastoApertoPerSorgente(treno.id, "treno_fermo") == null) {
                LOG.warnf("⚠️ [FaultMonitor] Treno %s fermo fuori stazione da %d secondi → guasto automatico",
                        treno.id, secondiFermo);
                // Il prefisso MSG_TRENO_FERMO marca il guasto come dedotto dalla Centrale:
                // è quello che permette di richiuderlo da soli quando il treno riparte
                // (IngestionService.chiudiGuastoTrenoFermoSeRiparte).
                creaGuastoAutomatico(
                        "treno_fermo", "warning", "TRENO", treno.id,
                        IngestionService.MSG_TRENO_FERMO + " il convoglio " + treno.id
                                + " è fermo fuori da una stazione da oltre " + trenoFermoTimeoutSecondi + " secondi",
                        null);
            }
        }

        // Un convoglio eliminato dall'amministrazione non deve lasciarsi dietro il proprio
        // cronometro: se ne venisse creato un altro con lo stesso nome si troverebbe già
        // "fermo da un'ora" e il guasto scatterebbe subito, senza aspettare T_fer.
        fermiDa.keySet().retainAll(treniEsistenti);
    }

    /**
     * Invia periodicamente al frontend la fotografia dello stato corrente della rete
     * (RF02.6.3): i KPI della dashboard più l'elenco completo di treni e stazioni.
     *
     * <p>I treni e le stazioni non c'erano: lo snapshot portava i soli KPI, quindi non
     * poteva riallineare niente, ed era per giunta l'unico evento a cui il frontend non si
     * sottoscriveva. La dashboard si ricalcolava i numeri per conto suo con formule diverse
     * da quelle della Centrale (treni in movimento, ritardo medio, stazioni guaste: tre
     * conti su quattro davano un risultato diverso). Adesso i numeri li calcola una funzione
     * sola — {@link TrafficLogicEngine#kpiDashboard()}, la stessa che serve GET /api/dashboard
     * — e lo snapshot è anche la rete di sicurezza che rimette a posto il browser quando un
     * evento in tempo reale si perde.</p>
     *
     * <p>I due elenchi sono gli stessi oggetti in cache serializzati da GET /api/treni e
     * GET /api/stazioni, quindi il browser li rilegge con lo stesso mappatore.</p>
     */
    @Scheduled(every = "10s",  concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void broadcastSnapshot() {
        try {
            Map<String, Object> dashboard = statoRete.kpiDashboard();
            ObjectNode wsEvent = mapper.createObjectNode();
            wsEvent.put("eventType", "SNAPSHOT");
            wsEvent.set("dashboard", mapper.valueToTree(dashboard));
            wsEvent.set("treni", mapper.valueToTree(statoRete.getTuttiTreni()));
            wsEvent.set("stazioni", mapper.valueToTree(statoRete.getTutteStazioni()));
            webSocket.broadcast(wsEvent.toString());
        } catch (Exception e) {
            LOG.error("❌ Errore durante il broadcast dello snapshot", e);
        }
    }

    /**
     * Crea, persiste e notifica un guasto rilevato automaticamente dalla centrale.
     * La notifica va su DUE strade: la WebSocket per la dashboard e il topic MQTT
     * railway/alerts per il campo (senza quest'ultima i treni non saprebbero mai
     * che una stazione è caduta e continuerebbero ad andarci dentro).
     *
     * <p><b>Ordine delle operazioni.</b> Prima si scrive e si chiude la transazione, poi si
     * annuncia. Fino a poco fa il metodo girava dentro una {@code @Transactional} sul job e
     * annunciava il guasto <em>prima</em> del commit: se il commit falliva (vincolo violato,
     * database che non risponde) l'allarme era già arrivato alla dashboard e i treni si erano
     * già fermati per un guasto che a database non esisteva. Se la scrittura non riesce ora
     * non parte nessuna notifica e il giro successivo del watchdog riproverà.</p>
     *
     * @param tipoEventoStazione Se non null, viene scritta anche una riga di audit su
     *                           eventi_stazioni con questo tipo (es. HEARTBEAT_LOST).
     * @return Il guasto appena creato, oppure null se la scrittura non è andata a buon fine.
     */
    private Guasto creaGuastoAutomatico(String tipo, String severita, String sorgenteTipo,
                                        String sorgenteId, String messaggio, String tipoEventoStazione) {
        Guasto guasto = new Guasto();
        // Il suffisso casuale è indispensabile: i due job schedulati girano entrambi ogni
        // 10 secondi e ciclano su tutte le sorgenti, quindi due guasti generati nello stesso
        // millisecondo avrebbero avuto la stessa chiave primaria (violazione di PK al commit
        // e rollback dell'INTERO giro di controllo).
        guasto.id = "alert-" + Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        guasto.tipo = tipo;
        guasto.severita = severita;
        guasto.sorgenteTipo = sorgenteTipo;
        guasto.sorgenteId = sorgenteId;
        guasto.origine = IngestionService.ORIGINE_DEDOTTO_CENTRALE;
        guasto.messaggio = messaggio;
        guasto.timestamp = Instant.now();
        guasto.risolto = false;

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                guasto.persist();

                StoricoGuasto storico = StoricoGuasto.fotografiaDi(guasto);
                storico.persist();

                if (tipoEventoStazione != null) {
                    // Audit log dell'evento sulla tabella eventi_stazioni
                    Stazione stazione = statoRete.getStazione(sorgenteId);
                    EventoStazione evento = new EventoStazione();
                    evento.stazioneId = sorgenteId;
                    // Il nome va congelato qui: la tabella non ha una chiave esterna verso
                    // la stazione (RF02.7), quindi è l'unico posto dove resta scritto.
                    evento.nomeStazione = stazione != null ? stazione.nome : sorgenteId;
                    evento.stato = "OFFLINE";
                    evento.tipoEvento = tipoEventoStazione;
                    evento.descrizione = "Heartbeat mancante, guasto automatico " + guasto.id;
                    evento.persist();
                }
            });
        } catch (Exception e) {
            LOG.errorf("❌ Guasto automatico %s non scritto a database (%s): nessuna notifica inviata",
                    guasto.id, e.getMessage());
            return null;
        }

        // Da qui in poi il guasto esiste davvero: si può annunciarlo.
        statoRete.aggiungiGuasto(guasto);
        ingestion.broadcastAlert(guasto);       // dashboard (WebSocket)
        ingestion.pubblicaGuastoSuMqtt(guasto); // campo (topic railway/alerts)
        return guasto;
    }
}
