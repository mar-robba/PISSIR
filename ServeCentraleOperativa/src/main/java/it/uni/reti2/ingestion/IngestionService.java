package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.Blocking;
// quindi Anche Treno
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.gateway.RealtimeWebSocket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
//è un bean CDI (@ApplicationScoped) che riceve i messaggi MQTT e poi aggiorna sia la cache interna sia il database.
/*
Un bean CDI è, in parole povere, una normale classe Java il cui ciclo di vita (creazione, distruzione)
e le cui dipendenze sono gestite interamente dal framework (il container), anziché da te manualmente tramite la parola chiave new.

CDI sta per Contexts and Dependency Injection, ed è lo standard ufficiale di Jakarta EE (e utilizzato da framework moderni come Quarkus o MicroProfile) per gestire i componenti dell'applicazione.
 */

/**
 * IngestionService è il cuore reattivo per la consumazione e memorizzazione (sink)
 * dei messaggi MQTT provenienti dai nodi sul campo (Edge), ovvero Treni e Stazioni.
 *
 * <p><b>Nota sulle transazioni.</b> I consumer NON sono più annotati {@code @Transactional}:
 * con quell'annotazione il commit avviene all'uscita del metodo, cioè FUORI dal try/catch,
 * quindi una violazione di vincolo risaliva fino al connettore reattivo e spegneva il canale
 * (da quel momento la Centrale smetteva di ricevere su quel topic fino al riavvio).
 * Adesso ogni scrittura gira dentro {@code QuarkusTransaction.requiringNew()} chiamato
 * DENTRO il try: il commit avviene lì e l'eventuale eccezione resta catturabile.
 * I metodi restano {@code @Blocking} perché usano JDBC (prima lo erano implicitamente,
 * essendo annotati {@code @Transactional}).</p>
 */
@ApplicationScoped
public class IngestionService {

    private static final Logger LOG = Logger.getLogger(IngestionService.class);

    /**
     * Valori ammessi dal CHECK sulla colonna Treni.stato: qualunque altra cosa
     * farebbe fallire il commit (e per il motivo spiegato sopra è meglio evitarlo).
     */
    private static final List<String> STATI_TRENO_VALIDI =
            List.of("attivo", "fermo", "rotto", "in manutenzione");

    /**
     * Marcatore inserito negli alert che la Centrale pubblica su railway/alerts.
     * Il topic è condiviso e la Centrale è sottoscritta anche in ingresso: senza
     * questo campo si riascolterebbe da sola e creerebbe un secondo Guasto per un
     * allarme che ha appena scritto lei.
     */
    public static final String ORIGINE_CENTRALE = "CENTRALE";

    /**
     * Prefisso dei messaggi dei guasti aperti automaticamente dal watchdog quando una
     * stazione smette di battere: permette alla Centrale di riconoscerli e chiuderli da
     * sola al ritorno dell'heartbeat, senza toccare i guasti segnalati dalla stazione.
     */
    public static final String MSG_HEARTBEAT_PERSO = "Heartbeat assente:";

    @Inject
    ObjectMapper mapper;

    /**
     * Canale verso railway/alerts: serve alla Centrale per propagare sul campo
     * i guasti che rileva da sola (vedi pubblicaGuastoSuMqtt, usato dal FaultMonitor).
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RealtimeWebSocket webSocket;

// Ste conversioni vanno messe a posto, bisogna fare solo un unico tipo di dato
    /**
     * Converte lo stato MQTT del treno nello stato canonico usato dal DB.
     * IN_VIAGGIO→attivo, FERMO→fermo, EMERGENZA→rotto, SOPPRESSO→in manutenzione.
     * Qualunque valore non riconosciuto (compreso il campo "stato" assente) diventa
     * "fermo": la colonna ha un CHECK e uno stato inventato farebbe saltare il commit.
     */
    private String normalizzaStatoTreno(String rawStato) {
        if ("IN_VIAGGIO".equalsIgnoreCase(rawStato)) return "attivo";
        if ("FERMO".equalsIgnoreCase(rawStato)) return "fermo";
        if ("EMERGENZA".equalsIgnoreCase(rawStato)) return "rotto";
        if ("SOPPRESSO".equalsIgnoreCase(rawStato)) return "in manutenzione";
        String normalizzato = rawStato == null ? "" : rawStato.trim().toLowerCase();
        return STATI_TRENO_VALIDI.contains(normalizzato) ? normalizzato : "fermo";
    }

    /**
     * Converte lo stato interno della stazione nel valore atteso dal frontend
     * per il campo "status" degli eventi WebSocket HEARTBEAT.
     */
    private String statoStazionePerFrontend(String stato) {
        if ("ONLINE".equalsIgnoreCase(stato)) return "operativa";
        if ("GUASTA".equalsIgnoreCase(stato)) return "guasta";
        if ("MANUTENZIONE".equalsIgnoreCase(stato)) return "manutenzione";
        return "offline";
    }

// ma che cazz di controllo è ?? è davvero necessario
    /**
     * Prova a interpretare il timestamp ISO-8601 del payload; in caso di errore
     * o assenza ritorna l'istante corrente.
     */
    private Instant parseTimestamp(JsonNode root) {
        try {
            if (root.hasNonNull("timestamp")) {
                return Instant.parse(root.get("timestamp").asText());
            }
        } catch (Exception ignored) {
            // formato non valido: si ripiega sull'orario di ricezione
        }
        return Instant.now();
    }
// prende i dati in arrivo dal topic e nutre la chash si sta trattando dei dati telemetrici del treno,
    // lo fa per ogni treno ?

    @Incoming("telemetry-in")
    @Blocking
    public CompletionStage<Void> onTelemetry(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {

            JsonNode root = mapper.readTree(payload);
            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "N/A";

            String rawStato = root.has("stato") ? root.get("stato").asText() : "UNKNOWN";
            String stato = normalizzaStatoTreno(rawStato);

            double lat = root.has("latitudine") ? root.get("latitudine").asDouble() : 0.0;
            double lng = root.has("longitudine") ? root.get("longitudine").asDouble() : 0.0;
            double velocita = root.has("velocita") ? root.get("velocita").asDouble() : 0.0;
            double progresso = root.has("progressPercent") ? root.get("progressPercent").asDouble() : 0.0;
            int ritardo = root.has("ritardoMinuti") ? root.get("ritardoMinuti").asInt() : 0;
            int passeggeri = root.has("passeggeri") ? root.get("passeggeri").asInt() : 0;
            String stazioneCorrente = root.has("stazioneCorrente") ? root.get("stazioneCorrente").asText("") : "";
            String prossimaStazione = root.has("prossimaStazione") ? root.get("prossimaStazione").asText("") : "";
            String direzione = root.has("direzione") ? root.get("direzione").asText("andata") : "andata";

            // in base a quali dati sono arrivati in input si pesca il treno giusto e gli si aggiornano i valori
            // assoluzione del todo di emacs : cosa succede se inserisco un nuoovo treno nella rete?
            // Risposta: la telemetria NON crea più il treno. Un convoglio che non è in cache
            // (la cache è la fotografia della tabella Treni) semplicemente non esiste, quindi
            // il frame viene scartato. Altrimenti un processo lanciato con un ID inventato si
            // faceva creare la riga dalla telemetria e la verifica dell'ID si auto-validava.
            Treno treno = statoRete.getTreno(trenoId);
            if (treno == null) {
                LOG.warnf("🚫 Telemetria dal treno sconosciuto '%s': frame scartato (i treni si creano dall'amministrazione)", trenoId);
                return message.ack();
            }
            treno.stato = stato;
            treno.latitudine = lat;
            treno.longitudine = lng;
            treno.velocita = velocita;
            treno.progresso = progresso;
            treno.ritardo = ritardo;
            treno.passeggeri = passeggeri;
            treno.stazioneCorrente = stazioneCorrente.isEmpty() ? null : stazioneCorrente;
            treno.prossimaStazione = prossimaStazione.isEmpty() ? null : prossimaStazione;
            treno.direzione = direzione;
            treno.ultimoAggiornamento = Instant.now();

            statoRete.aggiornaTreno(treno);

            // Scrittura su DB in una transazione aperta qui dentro (vedi nota di classe)
            QuarkusTransaction.requiringNew().run(() -> salvaStatoTreno(trenoId, stato));

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "TELEMETRY");
            // Campi aggiuntivi richiesti dal frontend
            wsEvent.put("trainId", trenoId);
            wsEvent.put("stato", stato); // Trasmette al frontend lo stato normalizzato per il DB
            wsEvent.put("progressPercent", progresso);
            wsEvent.put("delayMinutes", ritardo);
            wsEvent.put("velocita", velocita);
            wsEvent.put("latitudine", lat);
            wsEvent.put("longitudine", lng);
            webSocket.broadcast(wsEvent.toString());

        } catch (Exception e) {
            LOG.error("❌ Errore parsing telemetria: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Allinea sul database lo stato del treno e lo storicizza SOLO quando cambia
     * davvero (altrimenti sarebbe un record ogni 5 secondi per ogni convoglio).
     * Gira dentro la transazione aperta dal chiamante.
     */
    private void salvaStatoTreno(String trenoId, String stato) {
        // è una operazione a postgres? sì ed è fornita dal framework come metodo statico
        // della classe entità, dunque sarebbe un metodo statico della classe Treno
        Treno dbTreno = Treno.findById(trenoId);
        if (dbTreno == null) {
            LOG.warnf("🚫 Il treno '%s' non è nella tabella Treni: nessuna scrittura", trenoId);
            return;
        }
        boolean statoCambiato = !stato.equals(dbTreno.stato);
        dbTreno.stato = stato;

        if (statoCambiato) {
            StoricoStatoTreno storico = new StoricoStatoTreno();
            storico.treno = dbTreno;
            storico.stato = stato;
            storico.itinerarioId = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
            storico.posizioneId = dbTreno.posizioneAttualeTratta != null ? dbTreno.posizioneAttualeTratta.id : null;
            storico.persist();
        }
    }

    @Incoming("heartbeat-in")
    @Blocking
    public CompletionStage<Void> onHeartbeat(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "N/A";
            String stato = root.has("stato") ? root.get("stato").asText() : "ONLINE";

            // Come per i treni: una stazione che non è in cache non è nella tabella
            // Stazione, quindi non avrebbe nemmeno superato la validazione dell'ID.
            Stazione stazione = statoRete.getStazione(stazioneId);
            if (stazione == null) {
                LOG.warnf("🚫 Heartbeat dalla stazione sconosciuta '%s': scartato", stazioneId);
                return message.ack();
            }

            String statoPrecedente = stazione.stato;
            boolean statoCambiato = statoPrecedente == null || !statoPrecedente.equalsIgnoreCase(stato);
            // Se la Centrale l'aveva marcata OFFLINE (fail-stop) e il battito è tornato,
            // il guasto automatico va chiuso e i treni vanno sbloccati.
            boolean tornataDalFailStop = "OFFLINE".equalsIgnoreCase(statoPrecedente);

            stazione.stato = stato;
            stazione.ultimoHeartbeat = Instant.now();
            statoRete.aggiornaStazione(stazione);

            // I guasti chiusi qui dentro vengono notificati sul campo dopo il commit
            List<Guasto> guastiChiusi = new ArrayList<>();
            QuarkusTransaction.requiringNew().run(() -> {
                // Storicizza solo al cambio di stato, come già si fa per i treni:
                // a ogni battito sarebbero ~8.600 righe identiche al giorno per stazione.
                if (statoCambiato) {
                    storicizzaStatoStazione(stazioneId, stato);
                }
                if (tornataDalFailStop) {
                    guastiChiusi.addAll(chiudiGuastiHeartbeatPerso(stazioneId));
                }
            });
            for (Guasto guasto : guastiChiusi) {
                LOG.infof("✅ [FAIL-STOP] Stazione %s di nuovo raggiungibile: chiuso il guasto %s", stazioneId, guasto.id);
                pubblicaRisoluzioneSuMqtt(guasto);
                broadcastAlert(guasto);
            }

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "HEARTBEAT");
            // Campi aggiuntivi richiesti dal frontend
            wsEvent.put("stationId", stazioneId);
            wsEvent.put("status", statoStazionePerFrontend(stato));
            webSocket.broadcast(wsEvent.toString());

        } catch (Exception e) {
            LOG.error("❌ Errore parsing heartbeat stazione: " + payload, e);
        }
        return message.ack();
    }

    /** Scrive una riga di Storico_Stato_Stazioni per la stazione indicata. */
    private void storicizzaStatoStazione(String stazioneId, String stato) {
        Stazione dbStazione = Stazione.findById(stazioneId);
        if (dbStazione == null) {
            return;
        }
        StoricoStatoStazione storico = new StoricoStatoStazione();
        storico.stazione = dbStazione;
        storico.nome = dbStazione.nome;
        storico.tipo = dbStazione.tipoCapolineaPartenzaoNormale;
        storico.funzionanteONo = !"GUASTA".equalsIgnoreCase(stato) && !"OFFLINE".equalsIgnoreCase(stato);
        storico.persist();
    }

    /**
     * Chiude i guasti automatici aperti dal FaultMonitor per heartbeat mancante
     * relativi alla stazione indicata (li riconosce dal prefisso del messaggio,
     * così non tocca i guasti veri segnalati dalla stazione stessa).
     *
     * @return I guasti chiusi, da notificare poi su MQTT e WebSocket.
     */
    private List<Guasto> chiudiGuastiHeartbeatPerso(String stazioneId) {
        List<Guasto> chiusi = new ArrayList<>();
        List<Guasto> aperti = Guasto.list("sorgenteId = ?1 and sorgenteTipo = 'STAZIONE' and risolto = false", stazioneId);
        for (Guasto guasto : aperti) {
            if (guasto.messaggio == null || !guasto.messaggio.startsWith(MSG_HEARTBEAT_PERSO)) {
                continue; // guasto dichiarato dalla stazione: lo chiude un operatore
            }
            guasto.risolto = true;
            guasto.timestampRisoluzione = Instant.now();

            StoricoGuasto storico = StoricoGuasto.find("guasto", guasto).firstResult();
            if (storico != null) {
                storico.risolto = true;
                storico.tsChiusura = guasto.timestampRisoluzione;
            }
            statoRete.risolviGuasto(guasto.id);
            chiusi.add(guasto);
        }
        return chiusi;
    }

    @Incoming("transit-in")
    @Blocking
    public CompletionStage<Void> onTransit(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "";
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "";
            String tipo = root.has("tipo") ? root.get("tipo").asText() : "ENTRATA";

            QuarkusTransaction.requiringNew().run(() -> registraTransito(trenoId, stazioneId, tipo));

            // Aggiorna il contatore dei treni presenti nella stazione (cache RAM)
            Stazione cacheStazione = statoRete.getStazione(stazioneId);
            if (cacheStazione != null) {
                if ("ENTRATA".equalsIgnoreCase(tipo)) {
                    cacheStazione.treniInStazione++;
                } else if (cacheStazione.treniInStazione > 0) {
                    cacheStazione.treniInStazione--;
                }
                statoRete.aggiornaStazione(cacheStazione);
            }

            // La stazione è la SOLA sorgente degli eventi TRANSIT per la UI: è il sensore
            // di terra a rilevare il passaggio. onPassaggio() non fa più il broadcast,
            // altrimenti il frontend riceveva due volte lo stesso ingresso/uscita.
            broadcastTransit(root, trenoId, stazioneId, tipo);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing transito: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Apre o chiude il transito sul database, storicizzandolo.
     * Gira dentro la transazione aperta dal chiamante.
     */
    private void registraTransito(String trenoId, String stazioneId, String tipo) {
        Instant adesso = Instant.now();
        Treno dbTreno = Treno.findById(trenoId);
        Stazione dbStazione = Stazione.findById(stazioneId);
        if (dbTreno == null || dbStazione == null) {
            LOG.warnf("🚫 Transito ignorato: treno '%s' o stazione '%s' non presenti a DB", trenoId, stazioneId);
            return;
        }

        if ("ENTRATA".equalsIgnoreCase(tipo)) {
            // Apre un nuovo transito: il treno è entrato in stazione
            Transito transito = new Transito();
            transito.id = "TR-" + adesso.toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            transito.treno = dbTreno;
            transito.stazione = dbStazione;
            transito.tratta = dbTreno.posizioneAttualeTratta;
            transito.tempoEntrata = adesso;
            transito.persist();

            // Storicizza subito l'apertura (record con tempoUscita null)
            storicizzaTransito(transito);
        } else {
            // USCITA: chiude il transito aperto per stesso treno+stazione
            Transito aperto = Transito.find(
                    "treno.id = ?1 and stazione.id = ?2 and tempoUscita is null",
                    trenoId, stazioneId).firstResult();
            if (aperto != null) {
                aperto.tempoUscita = adesso;
                storicizzaTransito(aperto);
            } else {
                // Caso "esce senza entrare": tipico della stazione di partenza,
                // si registra un transito puntuale con entrata = uscita
                Transito transito = new Transito();
                transito.id = "TR-" + adesso.toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                transito.treno = dbTreno;
                transito.stazione = dbStazione;
                transito.tratta = dbTreno.posizioneAttualeTratta;
                transito.tempoEntrata = adesso;
                transito.tempoUscita = adesso;
                transito.persist();
                storicizzaTransito(transito);
            }
        }
    }

    /**
     * Inserisce nello storico dei transiti un record fotografia dell'evento corrente
     * (semplificazione: un record per ogni evento di apertura/chiusura).
     */
    private void storicizzaTransito(Transito transito) {
        StoricoTransito storico = new StoricoTransito();
        storico.idTransito = transito.id;
        storico.treno = transito.treno;
        storico.stazione = transito.stazione;
        storico.tratta = transito.tratta;
        storico.tempoEntrata = transito.tempoEntrata;
        storico.tempoUscita = transito.tempoUscita;
        storico.persist();
    }

    /**
     * Invia sul WebSocket un evento TRANSIT arricchito con i campi che il frontend
     * si aspetta (trainId, stationId, type minuscolo, delayMinutes).
     */
    private void broadcastTransit(JsonNode root, String trenoId, String stazioneId, String tipo) {
        Treno cacheTreno = statoRete.getTreno(trenoId);
        ObjectNode wsEvent = root.deepCopy();
        wsEvent.put("eventType", "TRANSIT");
        wsEvent.put("trainId", trenoId);
        wsEvent.put("stationId", stazioneId);
        wsEvent.put("type", "USCITA".equalsIgnoreCase(tipo) ? "uscita" : "ingresso");
        wsEvent.put("delayMinutes", cacheTreno != null ? cacheTreno.ritardo : 0);
        webSocket.broadcast(wsEvent.toString());
    }

    @Incoming("alerts-in")
    @Blocking
    public CompletionStage<Void> onAlert(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            // Sul canale condiviso viaggiano anche gli eventi emessi dalla centrale stessa
            // (RESOLVED, STOP, MAINTENANCE_DISPATCHED, ITINERARIO_AGGIORNATO):
            // per questi NON va creato alcun guasto.
            String tipoEvento = root.path("tipoEvento").asText("");
            if (!"GUASTO".equalsIgnoreCase(tipoEvento)) {
                return message.ack();
            }
            // Nemmeno per i GUASTO che ha pubblicato la Centrale stessa (fail-stop di una
            // stazione): sono già stati scritti a DB dal FaultMonitor, qui tornano solo
            // perché siamo sottoscritti al nostro stesso topic.
            if (ORIGINE_CENTRALE.equalsIgnoreCase(root.path("origine").asText(""))) {
                return message.ack();
            }

            String sorgenteTipo = root.path("sorgenteTipo").asText("");
            String sorgenteId = root.path("sorgenteId").asText("");
            String severita = root.path("severita").asText("CRITICAL");
            String messaggio = root.hasNonNull("messaggio") ? root.get("messaggio").asText() : payload;
            String tipo = tipoGuastoPerFrontend(sorgenteTipo, messaggio);
            Instant quando = parseTimestamp(root);

            // Deduplica: la stazione guasta manda un alert per OGNI treno che entra.
            // Se un guasto dello stesso tipo per la stessa sorgente è già aperto si
            // aggiorna il messaggio invece di riempire la tabella di righe identiche
            // (con N righe aperte, "risolvi" ne chiuderebbe una sola).
            Guasto giaAperto = statoRete.getGuastoApertoPerSorgente(sorgenteId, tipo);
            if (giaAperto != null) {
                final Guasto daAggiornare = giaAperto;
                QuarkusTransaction.requiringNew().run(() -> aggiornaMessaggioGuasto(daAggiornare.id, messaggio));
                giaAperto.messaggio = messaggio;
                LOG.infof("♻️ Guasto già aperto per %s (%s): aggiornato il messaggio invece di crearne un altro", sorgenteId, tipo);
                broadcastAlert(giaAperto);
                return message.ack();
            }

            Guasto guasto = new Guasto();
            guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            guasto.tipo = tipo;
            guasto.severita = severita;
            guasto.sorgenteTipo = sorgenteTipo;
            guasto.sorgenteId = sorgenteId;
            guasto.messaggio = messaggio;
            guasto.timestamp = quando;
            guasto.risolto = false;

            QuarkusTransaction.requiringNew().run(() -> {
                guasto.persist();

                StoricoGuasto storico = new StoricoGuasto();
                storico.guasto = guasto;
                storico.risolto = false;
                storico.tsApertura = guasto.timestamp;
                storico.persist();
            });
            statoRete.aggiungiGuasto(guasto);

            // Una stazione che segnala un guasto CRITICO viene marcata GUASTA nella cache.
            // I guasti WARNING restano solo allarmi: la stazione continua a operare
            // e i suoi heartbeat ONLINE non vanno contraddetti.
            if ("STAZIONE".equalsIgnoreCase(sorgenteTipo) && "CRITICAL".equalsIgnoreCase(severita)) {
                Stazione stazione = statoRete.getStazione(sorgenteId);
                if (stazione != null) {
                    stazione.stato = "GUASTA";
                    statoRete.aggiornaStazione(stazione);
                }
            }

            broadcastAlert(guasto);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing alert: " + payload, e);
        }
        return message.ack();
    }

    /** Aggiorna il testo di un guasto già aperto (deduplica degli alert ripetuti). */
    private void aggiornaMessaggioGuasto(String guastoId, String messaggio) {
        Guasto dbGuasto = Guasto.findById(guastoId);
        if (dbGuasto != null) {
            dbGuasto.messaggio = messaggio;
        }
    }

    /**
     * Determina il tipo di guasto nel vocabolario del frontend a partire
     * dal tipo di sorgente e dal messaggio ricevuto.
     */
    private String tipoGuastoPerFrontend(String sorgenteTipo, String messaggio) {
        if (messaggio != null && messaggio.toLowerCase().contains("sensore")) {
            return "sensore_offline";
        }
        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            return "treno_fermo";
        }
        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            return "stazione_guasta";
        }
        return "sensore_offline";
    }


   // compito che avrebbe dovuto fare restAPIGatewy

    /**
     * Invia sul WebSocket un evento ALERT nel formato atteso dal frontend.
     * Metodo pubblico perché riusato anche dal FaultMonitor per i guasti automatici.
     */
    public void broadcastAlert(Guasto guasto) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "ALERT");
        wsEvent.put("id", guasto.id);
        wsEvent.put("type", guasto.tipo);
        wsEvent.put("severity", guasto.severita != null ? guasto.severita.toLowerCase() : "warning");
        wsEvent.put("message", guasto.messaggio);
        wsEvent.put("sorgenteId", guasto.sorgenteId);
        wsEvent.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("trainId", guasto.sorgenteId);
        } else if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("stationId", guasto.sorgenteId);
        }
        webSocket.broadcast(wsEvent.toString());
    }

    /**
     * Pubblica su railway/alerts un guasto rilevato dalla Centrale, nello stesso formato
     * usato dai nodi edge. È il pezzo che mancava al requisito del fail-stop: prima il
     * FaultMonitor si limitava alla WebSocket, quindi i treni non sapevano nulla della
     * stazione caduta e ci andavano dentro lo stesso. Con severità CRITICAL entra in
     * funzione la logica di blocco già scritta in TrainGateway/TrainJourneyEngine.
     *
     * @param guasto Il guasto appena aperto dalla Centrale.
     */
    public void pubblicaGuastoSuMqtt(Guasto guasto) {
        ObjectNode alert = mapper.createObjectNode();
        alert.put("tipoEvento", "GUASTO");
        alert.put("origine", ORIGINE_CENTRALE); // evita che la Centrale si riascolti da sola
        alert.put("sorgenteTipo", guasto.sorgenteTipo);
        alert.put("sorgenteId", guasto.sorgenteId);
        alert.put("severita", guasto.severita);
        alert.put("messaggio", guasto.messaggio);
        alert.put("guastoId", guasto.id);
        alert.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        inviaSuMqtt(alert.toString());
    }

    /** Pubblica su railway/alerts la chiusura di un guasto aperto dalla Centrale. */
    public void pubblicaRisoluzioneSuMqtt(Guasto guasto) {
        ObjectNode alert = mapper.createObjectNode();
        alert.put("tipoEvento", "RESOLVED");
        alert.put("origine", ORIGINE_CENTRALE);
        alert.put("sorgenteTipo", guasto.sorgenteTipo != null ? guasto.sorgenteTipo : "STAZIONE");
        alert.put("sorgenteId", guasto.sorgenteId != null ? guasto.sorgenteId : "");
        alert.put("guastoId", guasto.id);
        alert.put("timestamp", Instant.now().toString());
        inviaSuMqtt(alert.toString());
    }

    /** Invio difensivo sul canale MQTT: un broker giù non deve far cadere il chiamante. */
    private void inviaSuMqtt(String payload) {
        try {
            alertsEmitter.send(payload);
        } catch (Exception e) {
            LOG.errorf("Impossibile pubblicare l'alert su railway/alerts: %s", e.getMessage());
        }
    }

    @Incoming("passaggio-in")
    @Blocking
    public CompletionStage<Void> onPassaggio(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            String trenoId = root.path("trenoId").asText("");
            String stazioneId = root.path("stazioneId").asText("");
            String tipo = root.path("tipo").asText("ENTRATA");
            int ritardo = root.path("ritardoMinuti").asInt(0);

            Treno cacheTreno = statoRete.getTreno(trenoId);
            String direzione = cacheTreno != null && cacheTreno.direzione != null
                    ? cacheTreno.direzione : "andata";

            // Letture sull'itinerario e aggiornamento della posizione: tutto in una
            // transazione, perché senza non ci sarebbe nessuna sessione JPA attiva.
            EsitoPassaggio esito = QuarkusTransaction.requiringNew()
                    .call(() -> aggiornaPosizioneSuTratta(trenoId, stazioneId, tipo, direzione));

            if (cacheTreno != null) {
                cacheTreno.ritardo = ritardo;
                if ("ENTRATA".equalsIgnoreCase(tipo)) {
                    cacheTreno.stazioneCorrente = stazioneId;
                    cacheTreno.prossimaStazione = esito.prossimaStazione();
                } else {
                    cacheTreno.stazioneCorrente = null;
                }
                if (esito.posizione() != null) {
                    cacheTreno.posizioneAttualeTratta = esito.posizione();
                }
                cacheTreno.ultimoAggiornamento = Instant.now();
                statoRete.aggiornaTreno(cacheTreno);
            }

            // NIENTE broadcastTransit qui: l'evento TRANSIT per la UI lo genera la
            // stazione (onTransit). Lo stesso passaggio fisico arriva alla Centrale
            // per due strade (dal treno e dalla stazione) e la sorgente ufficiale
            // per il frontend è il sensore di terra, altrimenti ogni ingresso/uscita
            // compariva due volte nella pagina transiti.
        } catch (Exception e) {
            LOG.error("❌ Errore parsing passaggio: " + payload, e);
        }
        return message.ack();
    }

    /** Dati letti dal DB durante un passaggio, che poi servono alla cache in RAM. */
    private record EsitoPassaggio(String prossimaStazione, Tratta posizione) {}

    /**
     * Aggiorna sul DB la posizione del treno rispetto alla tratta percorsa,
     * rispettando la direzione di marcia (andata = tratte nell'ordine originario),
     * e calcola quale sarà la prossima stazione.
     */
    private EsitoPassaggio aggiornaPosizioneSuTratta(String trenoId, String stazioneId, String tipo, String direzione) {
        Treno dbTreno = Treno.findById(trenoId);
        if (dbTreno == null || dbTreno.itinerario == null) {
            return new EsitoPassaggio(null, null);
        }
        Tratta trattaPosizione = trovaTratta(dbTreno.itinerario.id, stazioneId, tipo, direzione);
        if (trattaPosizione != null) {
            dbTreno.posizioneAttualeTratta = trattaPosizione;
        }
        String prossima = "ENTRATA".equalsIgnoreCase(tipo)
                ? calcolaProssimaStazione(dbTreno.itinerario.id, stazioneId, direzione)
                : null;
        return new EsitoPassaggio(prossima, trattaPosizione);
    }

    /**
     * Calcola la prossima stazione dell'itinerario del treno rispetto alla
     * stazione appena raggiunta e alla direzione di marcia.
     *
     * @return ID della prossima stazione, o null se capolinea/itinerario non noto.
     */
    private String calcolaProssimaStazione(String itinerarioId, String stazioneId, String direzione) {
        List<String> stazioni = stazioniOrdinateDiItinerario(itinerarioId);
        int idx = stazioni.indexOf(stazioneId);
        if (idx < 0) return null;

        boolean andata = !"ritorno".equalsIgnoreCase(direzione);
        int next = andata ? idx + 1 : idx - 1;
        if (next < 0 || next >= stazioni.size()) return null; // capolinea
        return stazioni.get(next);
    }

    /**
     * Ricostruisce la sequenza ordinata degli ID stazione di un itinerario
     * a partire dalle righe di Itinerario_Tratta.
     */
    private List<String> stazioniOrdinateDiItinerario(String itinerarioId) {
        List<ItinerarioTratta> tratte = ItinerarioTratta
                .find("itinerario.id", Sort.by("ordine"), itinerarioId).list();
        List<String> stazioni = new ArrayList<>();
        if (!tratte.isEmpty()) {
            stazioni.add(tratte.get(0).tratta.stazionePartenza.id);
            for (ItinerarioTratta it : tratte) {
                stazioni.add(it.tratta.stazioneArrivo.id);
            }
        }
        return stazioni;
    }

    /**
     * Trova la tratta dell'itinerario coerente con l'evento di passaggio:
     * per una ENTRATA la tratta appena percorsa (arrivo = stazione), per una
     * USCITA la tratta che il treno sta imboccando (partenza = stazione).
     * In direzione "ritorno" i ruoli di partenza/arrivo si invertono.
     */
    private Tratta trovaTratta(String itinerarioId, String stazioneId, String tipo, String direzione) {
        List<ItinerarioTratta> tratte = ItinerarioTratta
                .find("itinerario.id", Sort.by("ordine"), itinerarioId).list();
        boolean andata = !"ritorno".equalsIgnoreCase(direzione);
        boolean entrata = "ENTRATA".equalsIgnoreCase(tipo);
        for (ItinerarioTratta it : tratte) {
            Tratta t = it.tratta;
            String riferimento;
            if (entrata) {
                riferimento = andata ? t.stazioneArrivo.id : t.stazionePartenza.id;
            } else {
                riferimento = andata ? t.stazionePartenza.id : t.stazioneArrivo.id;
            }
            if (riferimento.equals(stazioneId)) {
                return t;
            }
        }
        return null;
    }
}
