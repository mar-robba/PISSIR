package it.uni.reti2;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;

/**
 * Microservizio Stazione
 * 
 * Simula il comportamento di una stazione ferroviaria.
 * 
 * PUBBLICA (produce messaggi MQTT):
 *   - heartbeat periodico ogni 10 secondi  →  topic: railway/station/{id}/heartbeat
 *   - notifica di transito treno           →  topic: railway/station/{id}/transit
 * 
 * SOTTOSCRIVE (consuma messaggi MQTT):
 *   - alert dalla Centrale Operativa       ←  topic: railway/alerts
 */
@ApplicationScoped
public class StazioneService {

    private static final Logger LOG = Logger.getLogger(StazioneService.class);

    /**
     * L'ID della stazione viene letto da application.properties
     * (proprietà "stazione.id", default = "alessandria")
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "alessandria")
    String stazioneId;

    // ================================================================
    // PRODUTTORE: Heartbeat periodico
    // ================================================================
    /**
     * Questo metodo NON ha @Incoming → è un GENERATORE puro.
     * L'annotazione @Outgoing("heartbeat-out") collega il Multi restituito
     * al canale "heartbeat-out" definito in application.properties.
     *
     * Ogni 10 secondi, produce un messaggio JSON con lo stato della stazione.
     * Il broker Mosquitto lo riceverà sul topic: railway/station/alessandria/heartbeat
     */
    @Outgoing("heartbeat-out")
    public Multi<String> generaHeartbeat() {
        return Multi.createFrom()
                .ticks()                                         // genera un tick crescente (0, 1, 2, ...)
                .every(Duration.ofSeconds(10))                   // ogni 10 secondi
                .map(tick -> {
                    String timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    String json = String.format(
                            "{\"stazione\":\"%s\", \"stato\":\"OPERATIVA\", \"timestamp\":\"%s\", \"tick\":%d}",
                            stazioneId, timestamp, tick
                    );
                    LOG.infof("💓 [HEARTBEAT] Invio heartbeat: %s", json);
                    return json;
                });
    }

    // ================================================================
    // PRODUTTORE: Notifica Transito (invocato da REST)
    // ================================================================
    /**
     * Anche questo è un @Outgoing, ma viene alimentato programmaticamente
     * dal REST endpoint (vedi StazioneResource.java).
     *
     * Per semplicità, usiamo un Emitter nel Resource.
     * Qui mostriamo solo la struttura del canale.
     */

    // ================================================================
    // CONSUMATORE: Riceve gli alert dalla Centrale Operativa
    // ================================================================
    /**
     * L'annotazione @Incoming("alerts-in") collega questo metodo
     * al canale "alerts-in" definito in application.properties.
     *
     * Quando la Centrale pubblica su railway/alerts,
     * Mosquitto inoltra il messaggio a tutti i subscriber (inclusa questa stazione).
     * SmallRye MQTT lo deserializza e invoca questo metodo.
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        LOG.warnf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload);

        // Qui la stazione potrebbe reagire: bloccare i binari, attivare segnali, etc.
        // Per la demo, logghiamo soltanto.

        return message.ack();  // confermiamo la ricezione del messaggio
    }
}
