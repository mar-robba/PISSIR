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
     */
    @Test
    public void invioDiUnAllertAdUnTreno() {
        Assertions.assertNotNull(alertsEmitter, "alertsEmitter should be injected");
        // invia un alert e attende il completamento
        alertsEmitter.send("ALERT: test").toCompletableFuture().join();
        Assertions.assertTrue(true);
    }

}
