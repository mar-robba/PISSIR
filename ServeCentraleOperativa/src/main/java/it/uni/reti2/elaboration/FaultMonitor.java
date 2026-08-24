package it.uni.reti2.elaboration;

import io.quarkus.scheduler.Scheduled;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.NotificatoreRealtime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

    /** La fotografia in RAM della rete: è l'unica cosa che questo watchdog guarda. */
    @Inject
    TrafficLogicEngine statoRete;

    /**
     * Il ciclo di vita dei guasti. Il watchdog si limita a dire "questa cosa è
     * un'anomalia": aprire il guasto, scriverlo, metterlo in cache e annunciarlo alla
     * dashboard e al campo è mestiere del servizio, ed è lo stesso mestiere che serve
     * quando il guasto lo dichiara un nodo invece del watchdog.
     */
    @Inject
    GuastiService guasti;

    /** L'unico punto da cui parte qualcosa verso il browser. */
    @Inject
    NotificatoreRealtime notificatore;

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
    // cioè DOPO che il guasto è già stato annunciato a dashboard e treni. La transazione
    // la apre e la chiude GuastiService.apri(), che annuncia solo a commit avvenuto.
    public void controllaHeartbeat() {
        Instant limite = Instant.now().minus(Duration.ofSeconds(heartbeatTimeoutSecondi));
        for (Stazione stazione : statoRete.getTutteStazioni()) {
            if (StatiCanonici.STAZIONE_OFFLINE.equalsIgnoreCase(stazione.stato)
                    || StatiCanonici.STAZIONE_MANUTENZIONE.equalsIgnoreCase(stazione.stato)) {
                continue;
            }
            if (stazione.ultimoHeartbeat != null && stazione.ultimoHeartbeat.isBefore(limite)) {
                LOG.warnf("⚠️ [FaultMonitor] Stazione %s senza heartbeat da oltre %d secondi → OFFLINE",
                        stazione.id, heartbeatTimeoutSecondi);
                stazione.stato = StatiCanonici.STAZIONE_OFFLINE;
                statoRete.aggiornaStazione(stazione);
                // Il cambio di stato va spinto sulla WebSocket: è proprio il caso in cui
                // l'HEARTBEAT non arriva più, quindi senza questo la mappa continuerebbe a
                // mostrare la stazione operativa fino al prossimo F5.
                notificatore.statoStazione(stazione);

                // Evita duplicati: un solo guasto aperto per episodio di silenzio
                if (statoRete.getGuastoApertoPerSorgente(stazione.id, "sensore_offline") == null) {
                    // Severità CRITICAL (non warning): è il fail-stop della stazione, e
                    // TrainGateway.riceviAlert blocca i treni solo sui guasti CRITICAL.
                    // Il messaggio parte con MSG_HEARTBEAT_PERSO così la Centrale
                    // riconosce il guasto come "suo" e lo richiude da sola quando il
                    // battito torna (vedi MonitoraggioStazioni.registraHeartbeat).
                    Guasto guasto = guasti.nuovoDedotto(
                            "sensore_offline", "CRITICAL", "STAZIONE", stazione.id,
                            Guasto.MSG_HEARTBEAT_PERSO + " la stazione " + stazione.id
                                    + " non invia heartbeat da oltre " + heartbeatTimeoutSecondi + " secondi");
                    guasti.apri(guasto, new GuastiService.EventoAudit(
                            StatiCanonici.STAZIONE_OFFLINE, "HEARTBEAT_LOST",
                            "Heartbeat mancante, guasto automatico " + guasto.id, null));
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
                // (GuastiService.chiudiDedottiSeTrenoRiparte).
                guasti.apri(guasti.nuovoDedotto(
                        "treno_fermo", "warning", "TRENO", treno.id,
                        Guasto.MSG_TRENO_FERMO + " il convoglio " + treno.id
                                + " è fermo fuori da una stazione da oltre " + trenoFermoTimeoutSecondi
                                + " secondi"), null);
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
     *
     * <p>Il formato dell'evento non si scrive piu' qui: comporlo e' mestiere del
     * {@link NotificatoreRealtime}, come per ogni altro evento diretto al browser.</p>
     */
    @Scheduled(every = "10s",  concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void broadcastSnapshot() {
        try {
            notificatore.snapshot(statoRete.kpiDashboard(),
                    statoRete.getTuttiTreni(),
                    statoRete.getTutteStazioni());
        } catch (Exception e) {
            LOG.error("❌ Errore durante il broadcast dello snapshot", e);
        }
    }
}
