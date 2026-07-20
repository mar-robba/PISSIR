package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.panache.common.Sort;
// quindi Anche Treno
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.gateway.RealtimeWebSocket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.time.Instant;
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
 */
@ApplicationScoped
public class IngestionService {

    private static final Logger LOG = Logger.getLogger(IngestionService.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RealtimeWebSocket webSocket;

    /**
     * Sostituisce le virgole che appaiono fra cifre con il punto decimale.
     * Esempio: "latitudine":44,912444  -> "latitudine":44.912444
     */
    private String normalizeDecimalComma(String s) {
        if (s == null) return null;
        // Sostituisce solo le virgole che sono fra due cifre (lookbehind/lookahead)
        return s.replaceAll("(?<=\\d),(?=\\d)", ".");
    }
// Ste conversioni vanno messe a posto, bisogna fare solo un unico tipo di dato
    /**
     * Converte lo stato MQTT del treno nello stato canonico usato dal DB.
     * IN_VIAGGIO→attivo, FERMO→fermo, EMERGENZA→rotto, SOPPRESSO→in manutenzione.
     */
    private String normalizzaStatoTreno(String rawStato) {
        if ("IN_VIAGGIO".equalsIgnoreCase(rawStato)) return "attivo";
        if ("FERMO".equalsIgnoreCase(rawStato)) return "fermo";
        if ("EMERGENZA".equalsIgnoreCase(rawStato)) return "rotto";
        if ("SOPPRESSO".equalsIgnoreCase(rawStato)) return "in manutenzione";
        return rawStato == null ? "fermo" : rawStato.toLowerCase();
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
    @Transactional
    public CompletionStage<Void> onTelemetry(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        // Normalizza eventuali numeri con virgola (es. 44,912444) → 44.912444
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);
            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "N/A";
            String nome = root.has("nome") ? root.get("nome").asText() : trenoId;

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
            Treno treno = statoRete.getTreno(trenoId);
            // assoluzione del todo di emacs : cosa succede se inserisco un nuoovo treno nella rete
            if (treno == null) {
                treno = new Treno(trenoId, nome);
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
            //
                            // è una operazione a postregras? si ed è fornita dal framework come metodo statico della classe entità dunque sarebbe un metodo statico della class Treno
            Treno dbTreno = Treno.findById(trenoId);
            boolean statoCambiato;
                // stato del treno nel dib
            if (dbTreno == null) {
                // ?
                treno.persist();
                dbTreno = treno;
                statoCambiato = true;
            } else {
                statoCambiato = !stato.equals(dbTreno.stato);
                dbTreno.stato = stato;
            }

            // Storicizza SOLO quando lo stato del treno è effettivamente cambiato
            // (evita un record ad ogni frame telemetrico ricevuto ogni 5 secondi).
            if (statoCambiato) {
                StoricoStatoTreno storico = new StoricoStatoTreno();
                storico.treno = dbTreno;
                storico.stato = stato;
                storico.itinerarioId = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
                storico.posizioneId = dbTreno.posizioneAttualeTratta != null ? dbTreno.posizioneAttualeTratta.id : null;
                storico.persist();
            }

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

    @Incoming("heartbeat-in")
    @Transactional
    public CompletionStage<Void> onHeartbeat(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "N/A";
            String nome = root.has("nome") ? root.get("nome").asText() : stazioneId;
            String stato = root.has("stato") ? root.get("stato").asText() : "ONLINE";

            Stazione stazione = statoRete.getStazione(stazioneId);
            if (stazione == null) {
                stazione = new Stazione(stazioneId, nome, 0, 0, 1);
            }
            stazione.stato = stato;
            stazione.ultimoHeartbeat = Instant.now();

            statoRete.aggiornaStazione(stazione);

            Stazione dbStazione = Stazione.findById(stazioneId);
            if (dbStazione == null) {
                stazione.persist();
                dbStazione = stazione;
            }

            StoricoStatoStazione storico = new StoricoStatoStazione();
            storico.stazione = dbStazione;
            storico.nome = dbStazione.nome;
            storico.tipo = dbStazione.tipoCapolineaPartenzaoNormale;
            storico.funzionanteONo = !stato.equals("GUASTA") && !stato.equals("OFFLINE");
            storico.persist();

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

    @Incoming("transit-in")
    @Transactional
    public CompletionStage<Void> onTransit(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);

            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "";
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "";
            String tipo = root.has("tipo") ? root.get("tipo").asText() : "ENTRATA";
            Instant adesso = Instant.now();

            Treno dbTreno = Treno.findById(trenoId);
            Stazione dbStazione = Stazione.findById(stazioneId);

            if (dbTreno != null && dbStazione != null) {
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

            broadcastTransit(root, trenoId, stazioneId, tipo);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing transito: " + payload, e);
        }
        return message.ack();
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
    @Transactional
    public CompletionStage<Void> onAlert(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);

            // Sul canale condiviso viaggiano anche gli eventi emessi dalla centrale stessa
            // (RESOLVED, STOP, MAINTENANCE_DISPATCHED, ITINERARIO_AGGIORNATO):
            // per questi NON va creato alcun guasto.
            String tipoEvento = root.path("tipoEvento").asText("");
            if (!"GUASTO".equalsIgnoreCase(tipoEvento)) {
                return message.ack();
            }

            String sorgenteTipo = root.path("sorgenteTipo").asText("");
            String sorgenteId = root.path("sorgenteId").asText("");
            String severita = root.path("severita").asText("CRITICAL");
            String messaggio = root.hasNonNull("messaggio") ? root.get("messaggio").asText() : payload;

            Guasto guasto = new Guasto();
            guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            guasto.tipo = tipoGuastoPerFrontend(sorgenteTipo, messaggio);
            guasto.severita = severita;
            guasto.sorgenteTipo = sorgenteTipo;
            guasto.sorgenteId = sorgenteId;
            guasto.messaggio = messaggio;
            guasto.timestamp = parseTimestamp(root);
            guasto.risolto = false;

            guasto.persist();
            statoRete.aggiungiGuasto(guasto);

            StoricoGuasto storico = new StoricoGuasto();
            storico.guasto = guasto;
            storico.risolto = false;
            storico.tsApertura = guasto.timestamp;
            storico.persist();

            // Una stazione che segnala un guasto CRITICO viene marcata GUASTA nella cache.
            // I guasti WARNING (es. keepalive sensore mancante) restano solo allarmi:
            // la stazione continua a operare e i suoi heartbeat ONLINE non vanno contraddetti.
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

    @Incoming("passaggio-in")
    @Transactional
    public CompletionStage<Void> onPassaggio(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);
            String trenoId = root.path("trenoId").asText("");
            String stazioneId = root.path("stazioneId").asText("");
            String tipo = root.path("tipo").asText("ENTRATA");
            int ritardo = root.path("ritardoMinuti").asInt(0);

            Treno cacheTreno = statoRete.getTreno(trenoId);
            String direzione = cacheTreno != null && cacheTreno.direzione != null
                    ? cacheTreno.direzione : "andata";

            if (cacheTreno != null) {
                cacheTreno.ritardo = ritardo;
                if ("ENTRATA".equalsIgnoreCase(tipo)) {
                    cacheTreno.stazioneCorrente = stazioneId;
                    cacheTreno.prossimaStazione = calcolaProssimaStazione(cacheTreno, stazioneId, direzione);
                } else {
                    cacheTreno.stazioneCorrente = null;
                }
                cacheTreno.ultimoAggiornamento = Instant.now();
            }

            // Aggiorna sul DB la posizione del treno rispetto alla tratta percorsa,
            // rispettando la direzione di marcia (andata = tratte nell'ordine originario)
            Treno dbTreno = Treno.findById(trenoId);
            if (dbTreno != null && dbTreno.itinerario != null) {
                Tratta trattaPosizione = trovaTratta(dbTreno.itinerario.id, stazioneId, tipo, direzione);
                if (trattaPosizione != null) {
                    dbTreno.posizioneAttualeTratta = trattaPosizione;
                    if (cacheTreno != null) {
                        cacheTreno.posizioneAttualeTratta = trattaPosizione;
                    }
                }
            }
            if (cacheTreno != null) {
                statoRete.aggiornaTreno(cacheTreno);
            }

            broadcastTransit(root, trenoId, stazioneId, tipo);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing passaggio: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Calcola la prossima stazione dell'itinerario del treno rispetto alla
     * stazione appena raggiunta e alla direzione di marcia.
     *
     * @return ID della prossima stazione, o null se capolinea/itinerario non noto.
     */
    private String calcolaProssimaStazione(Treno treno, String stazioneId, String direzione) {
        Treno dbTreno = Treno.findById(treno.id);
        if (dbTreno == null || dbTreno.itinerario == null) return null;

        List<String> stazioni = stazioniOrdinateDiItinerario(dbTreno.itinerario.id);
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
        List<String> stazioni = new java.util.ArrayList<>();
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
