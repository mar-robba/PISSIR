package it.uni.reti2.elaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
     * Rileva le stazioni che non inviano heartbeat da più del timeout configurato:
     * le marca OFFLINE e apre un guasto automatico (una sola volta per episodio).
     */
    /*Grazie a @Scheduled(every = "10s"), Quarkus sa che questa funzione è un Job.

    Il countdown: All'avvio dell'applicazione, il motore di pianificazione di Quarkus (basato su Vert.x) fa partire un timer interno.

    Il thread dedicato: Scaduti i 10 secondi, il framework sveglia un filo di esecuzione secondario (un worker thread in background) e gli dice: "Esegui controllaHeartbeat()". In questo modo l'applicazione principale (che risponde alle richieste REST degli utenti) rimane libera e reattiva.
    * */
    // ATTENZIOONE !!!! RACE CONDITON
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP) // <-- Evita sovrapposizioni nello stesso server
    // @Transactional garantisce la sicurezza dei dati nel Database (ACID) e non t
    @Transactional
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

                // Evita duplicati: un solo guasto aperto per episodio di silenzio
                if (statoRete.getGuastoApertoPerSorgente(stazione.id, "sensore_offline") == null) {
                    Guasto guasto = creaGuastoAutomatico(
                            "sensore_offline", "warning", "STAZIONE", stazione.id,
                            "Stazione " + stazione.id + " non invia heartbeat da oltre "
                                    + heartbeatTimeoutSecondi + " secondi");

                    // Audit log dell'evento sulla tabella eventi_stazioni
                    EventoStazione evento = new EventoStazione();
                    evento.stazioneId = stazione.id;
                    evento.stato = "OFFLINE";
                    evento.tipoEvento = "HEARTBEAT_LOST";
                    evento.descrizione = "Heartbeat mancante, guasto automatico " + guasto.id;
                    evento.persist();
                }
            }
        }
    }

    /**
     * Rileva i treni dichiarati "attivi" ma fermi (velocità nulla o telemetria
     * scaduta) mentre NON si trovano in una stazione: possibile anomalia in linea.
     */
    @Scheduled(every = "10s",  concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void controllaTreniFermi() {
        Instant limite = Instant.now().minus(Duration.ofSeconds(trenoFermoTimeoutSecondi));
        for (Treno treno : statoRete.getTuttiTreni()) {
            if (!"attivo".equalsIgnoreCase(treno.stato)) {
                continue;
            }
           // è vera se non è dentro una stazione
            boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
            boolean immobile = treno.velocita == 0
                    || (treno.ultimoAggiornamento != null && treno.ultimoAggiornamento.isBefore(limite));
            if (immobile && !inStazione
                    && statoRete.getGuastoApertoPerSorgente(treno.id, "treno_fermo") == null) {
                LOG.warnf("⚠️ [FaultMonitor] Treno %s attivo ma fermo fra stazioni → guasto automatico", treno.id);
                creaGuastoAutomatico(
                        "treno_fermo", "warning", "TRENO", treno.id,
                        "Treno " + treno.id + " fermo tra stazioni con stato attivo");
            }
        }
    }

    /**
     * Invia periodicamente al frontend uno snapshot dei KPI della rete
     * (requisito: interrogare lo stato del sistema ogni 10 secondi per il tempo reale).
     */
    @Scheduled(every = "10s",  concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void broadcastSnapshot() {
        try {
            Map<String, Object> dashboard = statoRete.kpiDashboard();
            ObjectNode wsEvent = mapper.createObjectNode();
            wsEvent.put("eventType", "SNAPSHOT");
            wsEvent.set("dashboard", mapper.valueToTree(dashboard));
            webSocket.broadcast(wsEvent.toString());
        } catch (Exception e) {
            LOG.error("❌ Errore durante il broadcast dello snapshot", e);
        }
    }

    /**
     * Crea, persiste e notifica un guasto rilevato automaticamente dalla centrale.
     *
     * @return Il guasto appena creato.
     */
    private Guasto creaGuastoAutomatico(String tipo, String severita, String sorgenteTipo,
                                        String sorgenteId, String messaggio) {
        Guasto guasto = new Guasto();
        guasto.id = "alert-" + Instant.now().toEpochMilli();
        guasto.tipo = tipo;
        guasto.severita = severita;
        guasto.sorgenteTipo = sorgenteTipo;
        guasto.sorgenteId = sorgenteId;
        guasto.messaggio = messaggio;
        guasto.timestamp = Instant.now();
        guasto.risolto = false;
        guasto.persist();

        StoricoGuasto storico = new StoricoGuasto();
        storico.guasto = guasto;
        storico.risolto = false;
        storico.tsApertura = guasto.timestamp;
        storico.persist();

        statoRete.aggiungiGuasto(guasto);
        ingestion.broadcastAlert(guasto);
        return guasto;
    }
}
