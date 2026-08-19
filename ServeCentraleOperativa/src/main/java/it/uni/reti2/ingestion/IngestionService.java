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

    /** Valori esposti all'operatore per distinguere un guasto dichiarato da uno dedotto. */
    public static final String ORIGINE_SEGNALATO_CAMPO = "SEGNALATO_CAMPO";
    public static final String ORIGINE_DEDOTTO_CENTRALE = "DEDOTTO_CENTRALE";

    /**
     * Prefisso dei messaggi dei guasti aperti automaticamente dal watchdog quando una
     * stazione smette di battere: permette alla Centrale di riconoscerli e chiuderli da
     * sola al ritorno dell'heartbeat, senza toccare i guasti segnalati dalla stazione.
     */
    public static final String MSG_HEARTBEAT_PERSO = "Heartbeat assente:";

    /**
     * Prefisso dei messaggi dei guasti che il watchdog apre da solo quando un convoglio
     * resta fermo fuori da una stazione. Ha lo stesso scopo di {@link #MSG_HEARTBEAT_PERSO}
     * per le stazioni: permette alla Centrale di riconoscere i propri guasti dedotti e di
     * chiuderli appena il treno riprende a muoversi, senza toccare i guasti che il
     * convoglio ha segnalato per conto suo (avaria di bordo).
     */
    public static final String MSG_TRENO_FERMO = "Convoglio fermo:";

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

            // Il convoglio dà di nuovo segni di vita: se il watchdog gli aveva aperto un
            // "treno fermo" va chiuso, esattamente come si fa con le stazioni al ritorno
            // dell'heartbeat (RF02.1.4). Senza questo l'allarme dedotto restava aperto per
            // sempre e la deduplica impediva di aprirne altri per lo stesso convoglio.
            chiudiGuastoTrenoFermoSeRiparte(treno);

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
        // Lo stato di partenza va letto PRIMA di sovrascriverlo: è quello che finisce
        // nella colonna stato_precedente e rende la riga di storico un cambiamento
        // ("da attivo a rotto") invece di una fotografia isolata.
        String statoPrecedente = dbTreno.stato;
        boolean statoCambiato = !stato.equals(dbTreno.stato);
        dbTreno.stato = stato;

        if (statoCambiato) {
            StoricoStatoTreno storico = StoricoStatoTreno.fotografiaDi(dbTreno, statoPrecedente);
            storico.persist();
        }
    }

    /**
     * Chiude il guasto "treno fermo" aperto dal watchdog quando il convoglio torna a
     * muoversi (velocità diversa da zero oppure ingresso in una stazione).
     *
     * <p>Il primo controllo è sulla cache in RAM e costa niente: senza un guasto aperto
     * del tipo giusto non si tocca il database, altrimenti sarebbe una query ogni cinque
     * secondi per ogni convoglio della rete.</p>
     *
     * @param treno Il treno appena aggiornato dalla telemetria.
     */
    private void chiudiGuastoTrenoFermoSeRiparte(Treno treno) {
        boolean inMovimento = treno.velocita > 0
                || (treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty());
        if (!inMovimento) {
            return;
        }
        Guasto inCache = statoRete.getGuastoApertoPerSorgente(treno.id, "treno_fermo");
        if (inCache == null || inCache.messaggio == null
                || !inCache.messaggio.startsWith(MSG_TRENO_FERMO)) {
            return; // nessun guasto dedotto da chiudere (o è un'avaria dichiarata dal treno)
        }

        List<Guasto> chiusi = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> chiusi.addAll(chiudiGuastiTrenoFermo(treno.id)));
        for (Guasto guasto : chiusi) {
            LOG.infof("✅ [WATCHDOG] Treno %s di nuovo in movimento: chiuso il guasto %s", treno.id, guasto.id);
            pubblicaRisoluzioneSuMqtt(guasto);
            broadcastAlert(guasto);
        }
    }

    /**
     * Gemello di {@link #chiudiGuastiHeartbeatPerso(String)} per i convogli: chiude i
     * guasti che la Centrale ha dedotto da sola (riconoscibili dal prefisso del messaggio)
     * lasciando aperti quelli segnalati dal treno, che li chiude un operatore.
     *
     * @return I guasti chiusi, da notificare poi su MQTT e WebSocket.
     */
    private List<Guasto> chiudiGuastiTrenoFermo(String trenoId) {
        List<Guasto> chiusi = new ArrayList<>();
        List<Guasto> aperti = Guasto.list("sorgenteId = ?1 and sorgenteTipo = 'TRENO' and risolto = false", trenoId);
        for (Guasto guasto : aperti) {
            if (guasto.messaggio == null || !guasto.messaggio.startsWith(MSG_TRENO_FERMO)) {
                continue; // avaria dichiarata dal convoglio: la chiude un operatore
            }
            guasto.risolto = true;
            guasto.timestampRisoluzione = Instant.now();

            StoricoGuasto storico = StoricoGuasto.find("guastoId", guasto.id).firstResult();
            if (storico != null) {
                storico.risolto = true;
                storico.tsChiusura = guasto.timestampRisoluzione;
            }
            statoRete.risolviGuasto(guasto.id);
            chiusi.add(guasto);
        }
        return chiusi;
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
                    storicizzaStatoStazione(stazioneId, stato, statoPrecedente);
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

    /**
     * Scrive una riga di Storico_Stato_Stazioni per la stazione indicata.
     *
     * @param stato            Stato nuovo della stazione.
     * @param statoPrecedente  Stato da cui proviene, che la riga registra per raccontare
     *                         il cambiamento e non solo il punto di arrivo.
     */
    private void storicizzaStatoStazione(String stazioneId, String stato, String statoPrecedente) {
        Stazione dbStazione = Stazione.findById(stazioneId);
        if (dbStazione == null) {
            return;
        }
        StoricoStatoStazione storico = StoricoStatoStazione.fotografiaDi(dbStazione, stato, statoPrecedente);
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

            StoricoGuasto storico = StoricoGuasto.find("guastoId", guasto.id).firstResult();
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
            // L'ora del passaggio è quella scritta dalla stazione quando il sensore ha
            // rilevato il treno, NON quella di arrivo del messaggio: con lo store and
            // forward (SV02) i transiti accodati durante un'interruzione tornano tutti
            // insieme, e datarli all'istante del rientro schiaccerebbe dieci minuti di
            // passaggi sullo stesso minuto.
            Instant quandoEPassato = parseTimestamp(root);
            // Il ritardo va congelato adesso: è quello che il convoglio ha nel momento
            // del passaggio, ed è il dato che lo storico dovrà mostrare (vedi getTransiti).
            Treno cacheTreno = statoRete.getTreno(trenoId);
            int ritardoAlPassaggio = cacheTreno != null ? cacheTreno.ritardo : 0;

            QuarkusTransaction.requiringNew()
                    .run(() -> registraTransito(trenoId, stazioneId, tipo, quandoEPassato, ritardoAlPassaggio));

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
     *
     * @param quandoEPassato Istante in cui la stazione ha rilevato il passaggio (dal payload).
     * @param ritardoMinuti  Ritardo del convoglio in quel momento.
     */
    private void registraTransito(String trenoId, String stazioneId, String tipo,
                                  Instant quandoEPassato, int ritardoMinuti) {
        Instant adesso = quandoEPassato != null ? quandoEPassato : Instant.now();
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
            transito.ritardoMinuti = ritardoMinuti;
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
                aperto.ritardoMinuti = ritardoMinuti;
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
                transito.ritardoMinuti = ritardoMinuti;
                transito.persist();
                storicizzaTransito(transito);
            }
        }
    }

    /**
     * Inserisce nello storico dei transiti un record fotografia dell'evento corrente
     * (semplificazione: un record per ogni evento di apertura/chiusura).
     * La riga si porta dietro anche il nome della stazione e la descrizione della tratta:
     * lo storico non ha più chiavi esterne verso l'anagrafica (RF02.7).
     */
    private void storicizzaTransito(Transito transito) {
        StoricoTransito storico = StoricoTransito.fotografiaDi(transito);
        storico.persist();
    }

    /**
     * Invia sul WebSocket un evento TRANSIT arricchito con i campi che il frontend
     * si aspetta (trainId, stationId, type minuscolo, delayMinutes, tratta percorsa).
     * Il campo "timestamp" arriva dal payload della stazione e viene lasciato intatto:
     * è l'ora vera del passaggio, quella che la pagina Transiti deve mostrare.
     */
    private void broadcastTransit(JsonNode root, String trenoId, String stazioneId, String tipo) {
        Treno cacheTreno = statoRete.getTreno(trenoId);
        ObjectNode wsEvent = root.deepCopy();
        wsEvent.put("eventType", "TRANSIT");
        wsEvent.put("trainId", trenoId);
        wsEvent.put("stationId", stazioneId);
        wsEvent.put("type", "USCITA".equalsIgnoreCase(tipo) ? "uscita" : "ingresso");
        wsEvent.put("delayMinutes", cacheTreno != null ? cacheTreno.ritardo : 0);
        // RF01.5 chiede la tratta fra i dati del transito: la stazione non la conosce,
        // la sa solo la Centrale dalla posizione corrente del convoglio.
        if (cacheTreno != null && cacheTreno.posizioneAttualeTratta != null) {
            wsEvent.put("trattaId", cacheTreno.posizioneAttualeTratta.id);
        }
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
            // Il tipo lo dichiara la sorgente (campo opzionale "tipoGuasto"): non si
            // indovina più leggendo il testo del messaggio.
            String tipo = tipoGuastoPerFrontend(sorgenteTipo, root.path("tipoGuasto").asText(""));
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
                // Anche se il guasto non è nuovo la sorgente va rimarcata: nel frattempo
                // la telemetria (o un heartbeat) può aver riscritto lo stato in cache.
                marcaSorgenteGuasta(sorgenteTipo, sorgenteId, severita);
                broadcastAlert(giaAperto);
                return message.ack();
            }

            Guasto guasto = new Guasto();
            guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            guasto.tipo = tipo;
            guasto.severita = severita;
            guasto.sorgenteTipo = sorgenteTipo;
            guasto.sorgenteId = sorgenteId;
            guasto.origine = ORIGINE_SEGNALATO_CAMPO;
            guasto.messaggio = messaggio;
            guasto.timestamp = quando;
            guasto.risolto = false;

            QuarkusTransaction.requiringNew().run(() -> {
                guasto.persist();

                StoricoGuasto storico = StoricoGuasto.fotografiaDi(guasto);
                storico.persist();
            });
            statoRete.aggiungiGuasto(guasto);
            marcaSorgenteGuasta(sorgenteTipo, sorgenteId, severita);

            //
            broadcastAlert(guasto);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing alert: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Riporta il guasto appena ricevuto sulla cache della sorgente che lo ha segnalato.
     * Senza questo passaggio l'allarme finiva solo nella tabella dei guasti: il nodo
     * continuava a comparire operativo nelle API REST (che leggono la cache) e la mappa
     * lo mostrava normale finché non arrivava un altro frame dal campo.
     * <p>Si marca solo sui CRITICAL: i WARNING restano allarmi informativi e non devono
     * contraddire gli heartbeat ONLINE della stazione né la telemetria del treno.</p>
     *
     * @param sorgenteTipo STAZIONE o TRENO.
     * @param sorgenteId   Identificativo del nodo che ha segnalato il guasto.
     * @param severita     Severità dichiarata nell'alert.
     */
    private void marcaSorgenteGuasta(String sorgenteTipo, String sorgenteId, String severita) {
        if (!"CRITICAL".equalsIgnoreCase(severita)) {
            return;
        }

        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            Stazione stazione = statoRete.getStazione(sorgenteId);
            if (stazione != null) {
                stazione.stato = "GUASTA";
                statoRete.aggiornaStazione(stazione);
                broadcastStatoStazione(stazione);
            }
            return;
        }

        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            Treno treno = statoRete.getTreno(sorgenteId);
            if (treno == null) {
                LOG.warnf("🚫 Guasto da un treno sconosciuto '%s': cache non aggiornata", sorgenteId);
                return;
            }
            // "rotto" è uno degli stati ammessi dal CHECK su Treni.stato; il frontend
            // lo traduce in "guasto" (mapBackendStatus in apiClient.ts).
            treno.stato = "rotto";
            treno.velocita = 0;
            treno.ultimoAggiornamento = Instant.now();
            statoRete.aggiornaTreno(treno);

            // Allinea anche il DB (e lo storico) come fa la telemetria: la cache viene
            // ricostruita da lì al riavvio della Centrale.
            QuarkusTransaction.requiringNew().run(() -> salvaStatoTreno(sorgenteId, "rotto"));

            broadcastStatoTreno(treno);

            // RF02.1.2.2.1: se il convoglio è fermo in una stazione, la stazione
            // diventa non percorribile (treno deragliato o avaria grave).
            marcaStazionePerConvoglioGuasto(treno);
        }
    }

    /**
     * RF02.1.2.2.1 — Se il convoglio guasto si trova fisicamente in una stazione,
     * la stazione diventa non percorribile. Un treno deragliato o gravemente avariato
     * blocca i binari e impedisce il transito: la Centrale crea un guasto dedicato
     * per la stazione e notifica campo e dashboard.
     *
     * @param treno Il treno appena marcato come «rotto» in cache.
     */
    private void marcaStazionePerConvoglioGuasto(Treno treno) {
        String stazioneId = treno.stazioneCorrente;
        if (stazioneId == null || stazioneId.isEmpty()) {
            return; // il convoglio è in tratta, non in stazione
        }

        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            LOG.warnf("🚫 Stazione '%s' non trovata in cache: impossibile marcarla GUASTA per convoglio %s",
                    stazioneId, treno.id);
            return;
        }

        // Se la stazione è già guasta non serve un secondo guasto: basta il log.
        if ("GUASTA".equalsIgnoreCase(stazione.stato)) {
            LOG.infof("♻️ Stazione %s già GUASTA: convoglio %s non genera un guasto duplicato",
                    stazioneId, treno.id);
            return;
        }

        String statoPrecedente = stazione.stato;

        // 1. Cache: la stazione diventa non percorribile
        stazione.stato = "GUASTA";
        statoRete.aggiornaStazione(stazione);
        broadcastStatoStazione(stazione);

        // 2. Deduplica: un solo guasto aperto per episodio
        String tipo = "convoglio_guasto_in_stazione";
        if (statoRete.getGuastoApertoPerSorgente(stazioneId, tipo) != null) {
            return;
        }

        // 3. Nuovo guasto per la stazione
        Guasto guastoStazione = new Guasto();
        guastoStazione.id = "alert-" + Instant.now().toEpochMilli() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        guastoStazione.tipo = tipo;
        guastoStazione.severita = "CRITICAL";
        guastoStazione.sorgenteTipo = "STAZIONE";
        guastoStazione.sorgenteId = stazioneId;
        guastoStazione.origine = ORIGINE_DEDOTTO_CENTRALE;
        guastoStazione.messaggio = "Convoglio " + treno.id + " guasto in stazione "
                + stazioneId + ": stazione non percorribile";
        guastoStazione.timestamp = Instant.now();
        guastoStazione.risolto = false;

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                guastoStazione.persist();

                StoricoGuasto storicoGuasto = StoricoGuasto.fotografiaDi(guastoStazione);
                storicoGuasto.persist();

                // Audit log sull'evento stazione
                EventoStazione evento = new EventoStazione();
                evento.stazioneId = stazioneId;
                evento.nomeStazione = stazione.nome != null ? stazione.nome : stazioneId;
                evento.stato = "GUASTA";
                evento.tipoEvento = "CONVOGLIO_GUASTO";
                evento.descrizione = "Convoglio " + treno.id
                        + " guasto: stazione non percorribile";
                evento.persist();

                // Storico del cambio di stato della stazione
                Stazione dbStazione = Stazione.findById(stazioneId);
                if (dbStazione != null) {
                    StoricoStatoStazione storicoStato = StoricoStatoStazione.fotografiaDi(
                            dbStazione, "GUASTA", statoPrecedente);
                    storicoStato.persist();
                }
            });
        } catch (Exception e) {
            LOG.errorf("❌ Guasto stazione %s per convoglio %s non scritto a DB (%s): nessuna notifica",
                    stazioneId, treno.id, e.getMessage());
            return;
        }

        // 4. Notifica: cache guasti + dashboard + campo
        statoRete.aggiungiGuasto(guastoStazione);
        broadcastAlert(guastoStazione);
        pubblicaGuastoSuMqtt(guastoStazione);

        LOG.warnf("🚨 Stazione %s marcata GUASTA per convoglio %s guasto in stazione",
                stazioneId, treno.id);
    }

    /**
     * Manda al frontend un evento TELEMETRY con il nuovo stato del convoglio.
     * Serve perché un treno che si guasta può smettere di trasmettere: senza questo
     * l'interfaccia continuerebbe a disegnarlo com'era all'ultimo frame ricevuto.
     *
     * @param treno Il treno appena marcato in cache.
     */
    private void broadcastStatoTreno(Treno treno) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "TELEMETRY");
        wsEvent.put("trainId", treno.id);
        wsEvent.put("stato", treno.stato);
        wsEvent.put("velocita", treno.velocita);
        wsEvent.put("progressPercent", treno.progresso);
        wsEvent.put("delayMinutes", treno.ritardo);
        wsEvent.put("latitudine", treno.latitudine);
        wsEvent.put("longitudine", treno.longitudine);
        webSocket.broadcast(wsEvent.toString());
    }

    /** Aggiorna il testo di un guasto già aperto (deduplica degli alert ripetuti). */
    private void aggiornaMessaggioGuasto(String guastoId, String messaggio) {
        Guasto dbGuasto = Guasto.findById(guastoId);
        if (dbGuasto != null) {
            dbGuasto.messaggio = messaggio;
        }
    }

    /**
     * Determina il tipo di guasto nel vocabolario del frontend.
     *
     * <p>Prima la prima riga del metodo classificava come {@code sensore_offline}
     * qualunque allarme che contenesse la parola "sensore" nel messaggio: siccome tutti i
     * guasti di terra parlano di sensori, il tipo {@code stazione_guasta} non veniva quasi
     * mai prodotto, e con lui spariva il pulsante "Invia Operatori" (RF01.4.1). Bastava poi
     * la parola "sensore" nella descrizione di un'avaria di bordo per far passare per
     * guasto di terra il guasto di un treno.</p>
     *
     * <p>Adesso il tipo lo dichiara chi segnala, con il campo {@code tipoGuasto} del
     * payload (lo scrivono le stazioni sui guasti ai sensori di binario); se manca, decide
     * il tipo di sorgente, che è un dato strutturato e non una parola in una frase.</p>
     *
     * @param tipoDichiarato Valore del campo "tipoGuasto" del payload, se presente.
     */
    private String tipoGuastoPerFrontend(String sorgenteTipo, String tipoDichiarato) {
        if (tipoDichiarato != null && !tipoDichiarato.isBlank()) {
            String tipo = tipoDichiarato.trim().toLowerCase();
            if ("sensore_offline".equals(tipo) || "stazione_guasta".equals(tipo) || "treno_fermo".equals(tipo)) {
                return tipo;
            }
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
     *
     * <p>L'evento porta anche {@code risolto} e {@code timestampRisoluzione}: lo stesso
     * metodo serve infatti sia per annunciare un guasto nuovo sia per dire che uno vecchio
     * è rientrato (chiusura automatica del fail-stop, deduplica di un allarme ripetuto).
     * Senza quel campo il browser non poteva distinguere i due casi e metteva in elenco un
     * secondo allarme rosso proprio quando la condizione era appena rientrata.</p>
     */
    public void broadcastAlert(Guasto guasto) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "ALERT");
        wsEvent.put("id", guasto.id);
        wsEvent.put("type", guasto.tipo);
        wsEvent.put("severity", guasto.severita != null ? guasto.severita.toLowerCase() : "warning");
        wsEvent.put("message", guasto.messaggio);
        wsEvent.put("sorgenteId", guasto.sorgenteId);
        wsEvent.put("origine", originePerOperatore(guasto));
        wsEvent.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        wsEvent.put("risolto", guasto.risolto);
        if (guasto.timestampRisoluzione != null) {
            wsEvent.put("timestampRisoluzione", guasto.timestampRisoluzione.toString());
        }
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("trainId", guasto.sorgenteId);
        } else if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("stationId", guasto.sorgenteId);
        }
        webSocket.broadcast(wsEvent.toString());
    }

    /** Mantiene leggibili anche gli allarmi creati prima dell'introduzione del campo origine. */
    public static String originePerOperatore(Guasto guasto) {
        return ORIGINE_DEDOTTO_CENTRALE.equals(guasto.origine)
                || (guasto.origine == null && guasto.messaggio != null
                && (guasto.messaggio.startsWith(MSG_HEARTBEAT_PERSO)
                || guasto.messaggio.startsWith(MSG_TRENO_FERMO)))
                ? ORIGINE_DEDOTTO_CENTRALE
                : ORIGINE_SEGNALATO_CAMPO;
    }

    /**
     * Annuncia al frontend che una stazione ha cambiato stato operativo.
     *
     * <p>Prima l'unico evento che poteva muovere il pallino di una stazione era
     * l'HEARTBEAT, cioè un messaggio che arriva dalla stazione stessa: per definizione
     * non arriva più proprio nel caso che conta, quando la stazione tace ed è la Centrale
     * a dedurne la caduta. Il risultato era una mappa tutta verde con la stazione OFFLINE
     * a database, che si allineava solo ricaricando la pagina (RF01.1.4 chiede il
     * contrario). Va quindi chiamato ovunque la Centrale scriva {@code stazione.stato}:
     * watchdog, risoluzione di un allarme, invio della squadra, guasto segnalato.</p>
     *
     * <p>Evento separato dall'HEARTBEAT apposta: l'HEARTBEAT porta con sé anche l'ora
     * dell'ultimo battito, e una stazione dichiarata OFFLINE dal watchdog non ha battuto
     * affatto — spacciare l'istante del broadcast per un battito sarebbe una bugia.</p>
     *
     * @param stazione La stazione appena aggiornata in cache.
     */
    public void broadcastStatoStazione(Stazione stazione) {
        if (stazione == null) {
            return;
        }
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "STATION_STATUS");
        wsEvent.put("stationId", stazione.id);
        wsEvent.put("status", statoStazionePerFrontend(stazione.stato));
        wsEvent.put("timestamp", Instant.now().toString());
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
    //per i guasti dedotti
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
                boolean entrata = "ENTRATA".equalsIgnoreCase(tipo);
                cacheTreno.ritardo = ritardo;
                if (entrata) {
                    cacheTreno.stazioneCorrente = stazioneId;
                    cacheTreno.prossimaStazione = esito.prossimaStazione();
                } else {
                    cacheTreno.stazioneCorrente = null;
                }
                if (esito.posizione() != null) {
                    cacheTreno.posizioneAttualeTratta = esito.posizione();
                }
                // Stato e progresso vanno allineati QUI, non alla telemetria successiva.
                // Il passaggio arriva subito, la telemetria fino a cinque secondi dopo:
                // nel mezzo lo SNAPSHOT metteva insieme campi di due età diverse e la
                // fotografia non stava in piedi. Un treno appena entrato risultava ancora
                // "attivo" al 95% ma con la prossima stazione già avanzata, e la mappa lo
                // scagliava sulla stazione successiva; uno appena uscito risultava "fermo"
                // senza più una stazione corrente, e la mappa non sapeva più dove disegnarlo
                // (spariva, finendo nel conteggio dei "treni senza posizione nota").
                // Il convoglio rotto o soppresso non si tocca: quello stato non lo decide
                // un passaggio in stazione.
                if ("attivo".equals(cacheTreno.stato) || "fermo".equals(cacheTreno.stato)) {
                    cacheTreno.stato = entrata ? "fermo" : "attivo";
                    cacheTreno.progresso = entrata ? 100.0 : 0.0;
                    if (entrata) {
                        cacheTreno.velocita = 0.0;
                    }
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
