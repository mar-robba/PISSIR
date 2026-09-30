package it.uni.reti2.gateway;

import it.uni.reti2.CentraleDiProva;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * La WebSocket /ws/realtime vera, quella di Javalin: un client Java si collega come farebbe
 * la web app e riceve quello che la Centrale manda in broadcast. Non chiede token, come
 * prima (il filtro difende solo /api).
 */
public class RealtimeWebSocketTest {

    @Test
    void ilBrowserRiceveIBroadcast() throws Exception {
        try (CentraleDiProva centrale = CentraleDiProva.avvia()) {
            BlockingQueue<String> ricevuti = new LinkedBlockingQueue<>();
            WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + centrale.server.portaEffettiva() + "/ws/realtime"),
                            new WebSocket.Listener() {
                                @Override
                                public CompletionStage<?> onText(WebSocket ws, CharSequence testo, boolean ultimo) {
                                    ricevuti.add(testo.toString());
                                    ws.request(1);
                                    return null;
                                }
                            })
                    .get(5, TimeUnit.SECONDS);

            // la sessione la registra Javalin appena finita la stretta di mano: si riprova
            // finche' il messaggio non arriva
            String arrivato = null;
            for (int tentativo = 0; tentativo < 20 && arrivato == null; tentativo++) {
                centrale.server.webSocket.broadcast("{\"eventType\":\"PROVA\"}");
                arrivato = ricevuti.poll(250, TimeUnit.MILLISECONDS);
            }
            Assertions.assertEquals("{\"eventType\":\"PROVA\"}", arrivato);
            client.sendClose(WebSocket.NORMAL_CLOSURE, "fine").get(5, TimeUnit.SECONDS);
        }
    }
}
