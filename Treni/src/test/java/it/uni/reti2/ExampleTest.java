package it.uni.reti2;

import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ExampleTest {

    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    /*
    invio di un allert ad un treno e osservare il suo cambio di stato
    Nota: questo test verifica l'iniezione e che l'invio non sollevi eccezioni.
    Per un controllo dello stato del treno è necessario un componente di ricezione
    o un mock del sistema che aggiorna lo stato in risposta al messaggio.

    Il canale alerts-out è collegato al topic MQTT reale "railway/alerts" (stesso
    broker usato dalla Centrale), quindi il payload deve rispettare il formato
    JSON atteso da IngestionService.onAlert (vedi TrainGateway.inviaGuasto):
    una stringa non-JSON come "ALERT: test" veniva scartata con un
    JsonParseException lato Centrale (errore visibile nei suoi log).
     */
    @Test
    public void invioDiUnAllertAdUnTreno() {
        Assertions.assertNotNull(alertsEmitter, "alertsEmitter should be injected");
        // invia un alert in formato valido e attende il completamento
        String alertJson = "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"TRENO\",\"sorgenteId\":\"test\","
                + "\"severita\":\"WARNING\",\"messaggio\":\"alert di test\",\"timestamp\":\""
                + java.time.Instant.now() + "\"}";
        alertsEmitter.send(alertJson).toCompletableFuture().join();
        Assertions.assertTrue(true);
    }

}
