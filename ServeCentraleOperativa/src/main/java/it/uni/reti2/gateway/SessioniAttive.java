package it.uni.reti2.gateway;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro in memoria delle sessioni aperte con il login.
 *
 * <p>Prima il token generato da {@link AuthController} veniva buttato via appena creato:
 * nessuno lo conservava e nessuno lo verificava, quindi la distinzione fra tecnico e
 * amministratore viveva solo nello store React del frontend e un semplice
 * {@code curl -X DELETE .../api/treni/TRN001} passava senza credenziali.</p>
 *
 * <p>Qui il token viene invece memorizzato insieme al ruolo dell'utente, e
 * {@link FiltroAutorizzazione} lo ricontrolla a ogni chiamata REST. È la variante 2
 * ammessa dal prof (autenticazione tradizionale al posto di OAUTH2/Keycloak):
 * le sessioni stanno in RAM, quindi si perdono al riavvio della Centrale.</p>
 */
@ApplicationScoped
public class SessioniAttive {

    /** Ruolo di chi può fare tutto, comprese le CRUD di stazioni/treni/tratte. */
    public static final String RUOLO_AMMINISTRATORE = "amministratore";

    /**
     * Dati associati a un token di sessione.
     *
     * @param username Matricola dell'operatore che ha fatto il login.
     * @param ruolo    "amministratore" oppure "tecnico".
     */
    public record Sessione(String username, String ruolo) {}

    /** Mappa thread-safe token -> sessione (le richieste REST arrivano in parallelo). */
    private final Map<String, Sessione> sessioni = new ConcurrentHashMap<>();

    /**
     * Apre una nuova sessione e ne restituisce il token da mandare al frontend.
     *
     * @param username Matricola dell'operatore.
     * @param ruolo    Ruolo applicativo (amministratore/tecnico).
     * @return Il token di sessione appena generato.
     */
    public String apri(String username, String ruolo) {
        String token = UUID.randomUUID().toString();
        sessioni.put(token, new Sessione(username, ruolo));
        return token;
    }

    /**
     * Cerca la sessione associata a un token.
     *
     * @param token Token estratto dall'header Authorization.
     * @return La sessione, oppure null se il token non è valido o è scaduto col riavvio.
     */
    public Sessione trova(String token) {
        return token == null ? null : sessioni.get(token);
    }

    /**
     * Invalida un token (logout esplicito dell'operatore).
     *
     * @param token Token da rimuovere.
     */
    public void chiudi(String token) {
        if (token != null) {
            sessioni.remove(token);
        }
    }
}
