package it.uni.reti2;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.CompletionStage;

/**
 * Microservizio Treno
 *
 * Simula il comportamento di un treno in circolazione.
 *
 * PUBBLICA (produce messaggi MQTT):
 *   - telemetria periodica ogni 5 secondi  →  topic: railway/train/{id}/telemetry
 *     (posizione GPS simulata, velocità, stato)
 *
 * SOTTOSCRIVE (consuma messaggi MQTT):
 *   - alert dalla Centrale Operativa       ←  topic: railway/alerts
 */
@ApplicationScoped
public class TrenoService {

    private static final Logger LOG = Logger.getLogger(TrenoService.class);
    private final Random random = new Random();

    /**
     * L'ID del treno viene letto da application.properties
     * (proprietà "treno.id", default = "REG-1234")
     */
    @ConfigProperty(name = "treno.id", defaultValue = "REG-1234")
    String trenoId;

    // Coordinate simulate (partenza da Alessandria)
    private double latitudine = 44.9131;
    private double longitudine = 8.6154;
    private double velocita = 0.0;

    // ================================================================
    // PRODUTTORE: Telemetria periodica
    // ================================================================
    /**
     * Genera un messaggio di telemetria ogni 5 secondi.
     *
     * @Outgoing("telemetry-out") → collegato al canale "telemetry-out"
     * in application.properties, che pubblica su:
     *   railway/train/REG-1234/telemetry
     *
     * Il broker Mosquitto riceve questo messaggio e lo inoltra a tutti
     * i subscriber (es. la Centrale Operativa che è in ascolto su railway/#)
     */
    @Outgoing("telemetry-out")
    public Multi<String> generaTelemetria() {
        return Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(5))      // ogni 5 secondi
                .map(tick -> {
                    // Simula il movimento del treno
                    velocita = 60 + random.nextInt(120);  // velocità tra 60 e 180 km/h
                    latitudine += (random.nextDouble() - 0.5) * 0.01;
                    longitudine += (random.nextDouble() - 0.5) * 0.01;

                    String timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                    String json = String.format(
                            "{\"trenoId\":\"%s\", \"lat\":%.4f, \"lon\":%.4f, \"velocita\":%.1f, \"stato\":\"IN_VIAGGIO\", \"timestamp\":\"%s\"}",
                            trenoId, latitudine, longitudine, velocita, timestamp
                    );

                    LOG.infof("📡 [TELEMETRIA] Invio posizione: %s", json);
                    return json;
                });
    }

    // ================================================================
    // CONSUMATORE: Riceve gli alert dalla Centrale Operativa
    // ================================================================
    /**
     * Quando la Centrale pubblica un alert su railway/alerts,
     * il broker Mosquitto lo inoltra a TUTTI i client sottoscritti,
     * incluso questo treno.
     *
     * @Incoming("alerts-in") → collegato al canale "alerts-in"
     * che si sottoscrive a railway/alerts
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        LOG.warnf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload);

        // Qui il treno potrebbe reagire: frenata d'emergenza, cambio percorso, etc.
        // Per la demo, logghiamo soltanto.

        return message.ack();
    }
}
