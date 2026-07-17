package it.uni.reti2.centrale.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uni.reti2.centrale.entity.EventoStazione;
import it.uni.reti2.centrale.entity.TelemetriaTreno;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class CentraleMqttConsumer {

    private static final Logger LOG = Logger.getLogger(CentraleMqttConsumer.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    @Incoming("telemetry-in")
    @Transactional
    public CompletionStage<Void> onTelemetry(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "N/A";
            String stato = root.has("stato") ? root.get("stato").asText() : "UNKNOWN";
            String desc = root.has("descrizione") ? root.get("descrizione").asText() : "";

            LOG.infof("🚂 [TELEMETRIA] Treno: %s | Stato: %s", trenoId, stato);

            TelemetriaTreno entity = new TelemetriaTreno();
            entity.trenoId = trenoId;
            entity.stato = stato;
            entity.descrizione = desc;
            entity.persist();

            if ("EMERGENZA".equalsIgnoreCase(stato)) {
                LOG.warnf("⚠️ RILEVATA EMERGENZA SUL TRENO %s! Invio alert globale.", trenoId);
                String alertJson = String.format("{\"target\":\"%s\",\"type\":\"STOP_ALL\",\"motivo\":\"Emergenza treno %s\"}", trenoId, trenoId);
                alertsEmitter.send(alertJson);
            }
        } catch (Exception e) {
            LOG.error("❌ Errore parsing telemetria: " + payload, e);
        }
        return message.ack();
    }

    @Incoming("transit-in")
    @Transactional
    public CompletionStage<Void> onTransit(Message<byte[]> message) {
        return processStazioneEvent(new String(message.getPayload()), "TRANSIT", message);
    }

    @Incoming("heartbeat-in")
    @Transactional
    public CompletionStage<Void> onHeartbeat(Message<byte[]> message) {
        return processStazioneEvent(new String(message.getPayload()), "HEARTBEAT", message);
    }

    private CompletionStage<Void> processStazioneEvent(String payload, String tipoEvento, Message<byte[]> message) {
        try {
            JsonNode root = mapper.readTree(payload);
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "N/A";
            String stato = root.has("stato") ? root.get("stato").asText() : "UNKNOWN";
            String desc = root.has("descrizione") ? root.get("descrizione").asText() : "";

            LOG.infof("🚉 [STAZIONE - %s] Stazione: %s | Stato: %s", tipoEvento, stazioneId, stato);

            EventoStazione entity = new EventoStazione();
            entity.stazioneId = stazioneId;
            entity.stato = stato;
            entity.descrizione = desc;
            entity.tipoEvento = tipoEvento;
            entity.persist();

        } catch (Exception e) {
            LOG.error("❌ Errore parsing evento stazione: " + payload, e);
        }
        return message.ack();
    }
}
