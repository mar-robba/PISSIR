package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.CompletionStage;

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
            String stato;
            if ("IN_VIAGGIO".equalsIgnoreCase(rawStato)) stato = "attivo";
            else if ("FERMO".equalsIgnoreCase(rawStato)) stato = "fermo";
            else if ("EMERGENZA".equalsIgnoreCase(rawStato)) stato = "rotto";
            else if ("SOPPRESSO".equalsIgnoreCase(rawStato)) stato = "in manutenzione";
            else stato = rawStato.toLowerCase();

            double lat = root.has("latitudine") ? root.get("latitudine").asDouble() : 0.0;
            double lng = root.has("longitudine") ? root.get("longitudine").asDouble() : 0.0;
            double velocita = root.has("velocita") ? root.get("velocita").asDouble() : 0.0;

            Treno treno = statoRete.getTreno(trenoId);
            if (treno == null) {
                treno = new Treno(trenoId, nome);
            }
            treno.stato = stato;
            treno.latitudine = lat;
            treno.longitudine = lng;
            treno.velocita = velocita;
            treno.ultimoAggiornamento = Instant.now();
            
            statoRete.aggiornaTreno(treno);
            
            Treno dbTreno = Treno.findById(trenoId);
            if (dbTreno == null) {
                treno.persist();
                dbTreno = treno;
            } else {
                dbTreno.stato = stato;
            }

            StoricoStatoTreno storico = new StoricoStatoTreno();
            storico.treno = dbTreno;
            storico.stato = stato;
            storico.persist();

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "TELEMETRY");
            wsEvent.put("stato", stato); // Trasmette al frontend lo stato normalizzato per il DB
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
            } else {
                dbStazione.nome = nome;
            }

            StoricoStatoStazione storico = new StoricoStatoStazione();
            storico.stazione = dbStazione;
            storico.nome = nome;
            storico.funzionanteONo = !stato.equals("GUASTA") && !stato.equals("OFFLINE");
            storico.persist();

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "HEARTBEAT");
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
            
            Transito transito = new Transito();
            String idTransito = "TR-" + Instant.now().toEpochMilli();
            transito.id = idTransito;
            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "";
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "";
            transito.trenoId = trenoId;
            transito.stazioneId = stazioneId;
            transito.tipo = root.has("tipo") ? root.get("tipo").asText() : "ENTRATA";
            transito.tempoEntrata = Instant.now();
            
            Treno dbTreno = Treno.findById(trenoId);
            Stazione dbStazione = Stazione.findById(stazioneId);
            
            if (dbTreno != null && dbStazione != null) {
                transito.treno = dbTreno;
                transito.stazione = dbStazione;
                transito.persist();

                StoricoTransito storico = new StoricoTransito();
                storico.idTransito = idTransito;
                storico.treno = dbTreno;
                storico.stazione = dbStazione;
                storico.tempoEntrata = transito.tempoEntrata;
                storico.persist();
            }

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "TRANSIT");
            webSocket.broadcast(wsEvent.toString());
        } catch (Exception e) {
            LOG.error("❌ Errore parsing transito: " + payload, e);
        }
        return message.ack();
    }

    @Incoming("alerts-in")
    @Transactional
    public CompletionStage<Void> onAlert(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        payload = normalizeDecimalComma(payload);
        try {
            JsonNode root = mapper.readTree(payload);
            
            Guasto guasto = new Guasto();
            guasto.id = "alert-" + Instant.now().toEpochMilli();
            guasto.tipo = root.has("tipo") ? root.get("tipo").asText() : "SYSTEM";
            guasto.severita = root.has("severita") ? root.get("severita").asText() : "CRITICAL";
            guasto.sorgenteId = root.has("sorgenteId") ? root.get("sorgenteId").asText() : "";
            guasto.messaggio = root.has("messaggio") ? root.get("messaggio").asText() : payload;
            guasto.timestamp = Instant.now();
            guasto.risolto = false;
            
            guasto.persist();
            statoRete.aggiungiGuasto(guasto);

            StoricoGuasto storico = new StoricoGuasto();
            storico.guasto = guasto;
            storico.risolto = false;
            storico.tsApertura = guasto.timestamp;
            storico.persist();

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "ALERT");
            wsEvent.put("id", guasto.id);
            webSocket.broadcast(wsEvent.toString());
        } catch (Exception e) {
            LOG.error("❌ Errore parsing alert: " + payload, e);
        }
        return message.ack();
    }

    @Incoming("passaggio-in")
    @Transactional
    public CompletionStage<Void> onPassaggio(Message<byte[]> message) {
        return message.ack();
    }
}
