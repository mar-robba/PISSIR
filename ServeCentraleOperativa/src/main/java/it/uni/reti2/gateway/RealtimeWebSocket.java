package it.uni.reti2.gateway;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import org.eclipse.jetty.websocket.api.WriteCallback;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * La WebSocket /ws/realtime per la comunicazione in tempo reale tra il Server Centrale e
 * l'interfaccia utente web (Frontend).
 * Quando arrivano eventi dal broker MQTT (es. telemetria, allarmi),
 * vengono inoltrati ai client connessi tramite questa classe.
 *
 * <p>L'indirizzo lo registra ServerRest con {@code app.ws(...)}, e Javalin chiama i tre
 * metodi {@link #onOpen}, {@link #onClose} e {@link #onError} quando una pagina si collega,
 * se ne va o perde il collegamento.</p>
 */
public class RealtimeWebSocket {

    private static final Logger LOG = Logger.getLogger(RealtimeWebSocket.class.getName());

    /**
     * Mantiene traccia di tutte le sessioni WebSocket attive.
     * Si usa una struttura thread-safe (ConcurrentHashMap) poichè le connessioni
     * e disconnessioni possono avvenire in parallelo da diversi thread.
     */
    private final Set<WsContext> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Callback invocata all'apertura di una nuova connessione da parte del client.
     *
     * @param session Il contesto della connessione creato da Javalin.
     */
    public void onOpen(WsConnectContext session) {
        sessions.add(session);
        LOG.info(String.format("🔌 [WebSocket] Nuova connessione: %s", session.sessionId()));
    }

    /**
     * Callback invocata alla chiusura volontaria o per timeout della connessione.
     *
     * @param session La sessione terminata.
     */
    public void onClose(WsCloseContext session) {
        sessions.remove(session);
        LOG.info(String.format("🔌 [WebSocket] Connessione chiusa: %s", session.sessionId()));
    }

    /**
     * Callback invocata in caso di errori a livello di protocollo o di rete.
     *
     * @param session La sessione interessata.
     */
    public void onError(WsErrorContext session) {
        sessions.remove(session);
        LOG.log(Level.SEVERE, String.format("🔌 [WebSocket] Errore sulla sessione: %s", session.sessionId()),
                session.error());
    }

    /**
     * Invia un messaggio di testo (solitamente un payload JSON) a tutti i client
     * attualmente connessi alla WebSocket.
     *
     * @param message Il contenuto del messaggio da trasmettere in broadcast.
     */
    public void broadcast(String message) {
        sessions.forEach(s -> {
            if (!s.session.isOpen()) {
                return;
            }
            // Invio asincrono per non bloccare il thread principale durante la trasmissione:
            // Jetty accoda il messaggio e chiama la callback quando l'ha spedito
            s.session.getRemote().sendString(message, new WriteCallback() {
                @Override
                public void writeFailed(Throwable errore) {
                    LOG.log(Level.SEVERE, "Errore invio messaggio WebSocket alla sessione " + s.sessionId(), errore);
                }
            });
        });
    }
}
