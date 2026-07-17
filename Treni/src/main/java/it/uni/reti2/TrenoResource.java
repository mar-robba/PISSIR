package it.uni.reti2;

import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * REST Endpoint del Treno
 *
 * Espone un'API HTTP per simulare azioni del treno che generano
 * messaggi MQTT.
 *
 * Esempio di chiamata:
 *   curl http://localhost:8082/treno/info
 *   curl -X POST http://localhost:8082/treno/emergenza
 */
@Path("/treno")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TrenoResource {

    private static final Logger LOG = Logger.getLogger(TrenoResource.class);

    @ConfigProperty(name = "treno.id", defaultValue = "REG-1234")
    String trenoId;

    @Inject
    @Channel("telemetry-out")
    MutinyEmitter<String> emitterTelemetria;

    /**
     * GET /treno/info
     * Restituisce le informazioni correnti del treno.
     */
    @GET
    @Path("/info")
    public Response getInfo() {
        return Response.ok(String.format(
                "{\"trenoId\":\"%s\", \"stato\":\"IN_VIAGGIO\", \"descrizione\":\"Microservizio Treno Quarkus + MQTT\"}",
                trenoId
        )).build();
    }

    /**
     * POST /treno/emergenza
     *
     * Simula un'emergenza a bordo del treno.
     * Pubblica un messaggio di telemetria con stato EMERGENZA su MQTT.
     */
    @POST
    @Path("/emergenza")
    public Response segnalaEmergenza() {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String json = String.format(
                "{\"trenoId\":\"%s\", \"lat\":44.9131, \"lon\":8.6154, \"velocita\":0.0, \"stato\":\"EMERGENZA\", \"timestamp\":\"%s\"}",
                trenoId, timestamp
        );

        LOG.errorf("🆘 [EMERGENZA] Pubblicazione emergenza: %s", json);

        emitterTelemetria.send(json).subscribe().with(
                success -> LOG.info("✅ Emergenza pubblicata su MQTT"),
                failure -> LOG.errorf("❌ Errore pubblicazione emergenza: %s", failure.getMessage())
        );

        return Response.ok("{\"status\":\"EMERGENZA_SEGNALATA\",\"dettagli\":" + json + "}")
                .build();
    }
}
