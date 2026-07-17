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
 * REST Endpoint della Stazione
 *
 * Espone un'API HTTP che, quando chiamata, pubblica un messaggio MQTT
 * sul topic di transito della stazione.
 *
 * Questo mostra come un evento esterno (una chiamata HTTP) possa generare
 * un messaggio MQTT che arriva alla Centrale tramite Mosquitto.
 *
 * Esempio di chiamata:
 *   curl -X POST http://localhost:8081/stazione/transito \
 *        -H "Content-Type: application/json" \
 *        -d '{"trenoId":"REG-1234","tipo":"INGRESSO"}'
 */
@Path("/stazione")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StazioneResource {

    private static final Logger LOG = Logger.getLogger(StazioneResource.class);

    @ConfigProperty(name = "stazione.id", defaultValue = "alessandria")
    String stazioneId;

    /**
     * @Channel("transit-out") inietta un Emitter collegato al canale
     * "transit-out" definito in application.properties.
     *
     * Quando invochi emitterTransito.send(...), il messaggio viene
     * pubblicato su: railway/station/alessandria/transit
     */
    @Inject
    @Channel("transit-out")
    MutinyEmitter<String> emitterTransito;

    /**
     * POST /stazione/transito
     *
     * Simula l'arrivo o la partenza di un treno dalla stazione.
     * Il messaggio viene pubblicato su MQTT → Mosquitto → Centrale Operativa.
     */
    @POST
    @Path("/transito")
    public Response notificaTransito(TransitoRequest request) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String json = String.format(
                "{\"stazione\":\"%s\", \"trenoId\":\"%s\", \"tipo\":\"%s\", \"timestamp\":\"%s\"}",
                stazioneId, request.trenoId, request.tipo, timestamp
        );

        LOG.infof("🚂 [TRANSITO] Pubblico su MQTT: %s", json);

        // Pubblica il messaggio sul canale MQTT tramite l'Emitter
        emitterTransito.send(json).subscribe().with(
                success -> LOG.info("✅ Messaggio di transito pubblicato con successo"),
                failure -> LOG.errorf("❌ Errore pubblicazione: %s", failure.getMessage())
        );

        return Response.ok("{\"status\":\"TRANSITO_NOTIFICATO\",\"dettagli\":" + json + "}")
                .build();
    }

    /**
     * GET /stazione/info
     * 
     * Endpoint di test: restituisce info sulla stazione.
     */
    @GET
    @Path("/info")
    public Response getInfo() {
        return Response.ok(String.format(
                "{\"stazioneId\":\"%s\", \"stato\":\"OPERATIVA\", \"descrizione\":\"Microservizio Stazione Quarkus + MQTT\"}",
                stazioneId
        )).build();
    }

    /**
     * DTO per la richiesta di transito.
     */
    public static class TransitoRequest {
        public String trenoId;
        public String tipo; // "INGRESSO" o "USCITA"
    }
}
