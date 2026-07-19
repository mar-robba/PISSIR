package it.uni.reti2.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controller WebSocket per la comunicazione in tempo reale bidirezionale
 * tra il Server Centrale e l'interfaccia utente web (Frontend).
 * Quando arrivano eventi dal broker MQTT (es. telemetria, allarmi), 
 * vengono inoltrati ai client connessi tramite questa classe.
 */
@ServerEndpoint("/ws/realtime")
@ApplicationScoped
public class RealtimeWebSocket {

    private static final Logger LOG = Logger.getLogger(RealtimeWebSocket.class);

    /**
     * Mantiene traccia di tutte le sessioni WebSocket attive.
     * Si usa una struttura thread-safe (ConcurrentHashMap) poichè le connessioni
     * e disconnessioni possono avvenire in parallelo da diversi thread.
     */
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Callback invocata all'apertura di una nuova connessione da parte del client.
     *
     * @param session L'oggetto Session generato dall'engine WebSocket.
     */
    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        LOG.infof("🔌 [WebSocket] Nuova connessione: %s", session.getId());
    }

    /**
     * Callback invocata alla chiusura volontaria o per timeout della connessione.
     *
     * @param session La sessione terminata.
     */
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        LOG.infof("🔌 [WebSocket] Connessione chiusa: %s", session.getId());
    }

    /**
     * Callback invocata in caso di errori a livello di protocollo o di rete.
     *
     * @param session La sessione interessata.
     * @param throwable L'eccezione lanciata.
     */
    @OnError
    public void onError(Session session, Throwable throwable) {
        sessions.remove(session);
        LOG.errorf(throwable, "🔌 [WebSocket] Errore sulla sessione: %s", session.getId());
    }

    /**
     * Invia un messaggio di testo (solitamente un payload JSON) a tutti i client
     * attualmente connessi alla WebSocket.
     * 
     * @param message Il contenuto del messaggio da trasmettere in broadcast.
     */
    public void broadcast(String message) {
        sessions.forEach(s -> {
            // Invio asincrono per non bloccare il thread principale durante la trasmissione
            s.getAsyncRemote().sendObject(message, result -> {
                if (result.getException() != null) {
                    LOG.error("Errore invio messaggio WebSocket alla sessione " + s.getId(), result.getException());
                }
            });
        });
    }
}
