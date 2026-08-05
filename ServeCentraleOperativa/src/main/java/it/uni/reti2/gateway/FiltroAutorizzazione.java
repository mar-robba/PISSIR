package it.uni.reti2.gateway;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Filtro JAX-RS che protegge le API della Centrale.
 *
 * <p>Il PDF chiede due tipologie di utenti con permessi diversi. Prima la distinzione
 * era solo grafica: nessun endpoint controllava nulla e chiunque poteva cancellare un
 * treno con un curl. Adesso ogni richiesta sotto /api deve portare il token di sessione
 * ricevuto dal login nell'header {@code Authorization: Bearer <token>}.</p>
 *
 * <p>Regole applicate:</p>
 * <ul>
 *   <li>{@code POST /api/auth/login} e le preflight OPTIONS restano pubbliche;</li>
 *   <li>le letture (GET) sono permesse a entrambi i ruoli (tecnico e amministratore);</li>
 *   <li>i comandi operativi del tecnico (sopprimi un treno, risolvi un allarme, invia
 *       la squadra di manutenzione) sono permessi a entrambi;</li>
 *   <li>tutte le altre scritture (CRUD di stazioni, treni, tratte e itinerari) sono
 *       riservate all'amministratore.</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class FiltroAutorizzazione implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(FiltroAutorizzazione.class);

    private static final String PREFISSO_BEARER = "Bearer ";

    @Inject
    SessioniAttive sessioni;

    @Override
    public void filter(ContainerRequestContext richiesta) {
        String metodo = richiesta.getMethod();
        String percorso = normalizza(richiesta.getUriInfo().getPath());

        // La preflight del browser non porta header applicativi: va lasciata passare,
        // altrimenti il CORS fallisce prima ancora della chiamata vera.
        if ("OPTIONS".equalsIgnoreCase(metodo)) {
            return;
        }
        // Il login è per forza pubblico: è lì che si ottiene il token.
        if (percorso.equals("api/auth/login")) {
            return;
        }
        // Il filtro difende solo le API REST applicative.
        if (!percorso.startsWith("api/")) {
            return;
        }
        // Le due letture usate dai nodi di campo restano aperte (vedi metodo).
        if (endpointDiCampo(metodo, percorso)) {
            return;
        }

        String token = estraiToken(richiesta);
        SessioniAttive.Sessione sessione = sessioni.trova(token);
        if (sessione == null) {
            LOG.warnf("🔒 %s /%s rifiutata: token assente o non valido", metodo, percorso);
            richiesta.abortWith(errore(Response.Status.UNAUTHORIZED,
                    "Autenticazione richiesta: effettuare il login"));
            return;
        }

        if (richiedeAmministratore(metodo, percorso)
                && !SessioniAttive.RUOLO_AMMINISTRATORE.equalsIgnoreCase(sessione.ruolo())) {
            LOG.warnf("🔒 %s /%s rifiutata: l'utente %s è %s, serve l'amministratore",
                    metodo, percorso, sessione.username(), sessione.ruolo());
            richiesta.abortWith(errore(Response.Status.FORBIDDEN,
                    "Operazione riservata all'amministratore"));
        }
    }

    /**
     * Decide se l'operazione richiesta è riservata all'amministratore.
     * Le letture no, e nemmeno i tre comandi operativi che il PDF assegna al tecnico
     * (invio operatori, soppressione corsa, presa in carico di un allarme).
     */
    private boolean richiedeAmministratore(String metodo, String percorso) {
        if ("GET".equalsIgnoreCase(metodo) || "HEAD".equalsIgnoreCase(metodo)) {
            return false;
        }
        if (percorso.endsWith("/sopprimi") || percorso.endsWith("/risolvi")
                || percorso.endsWith("/manutenzione") || percorso.equals("api/auth/logout")) {
            return false;
        }
        return true;
    }

    /**
     * Riconosce i due endpoint di sola lettura usati dai NODI DI CAMPO e non
     * dall'interfaccia utente: il download dell'itinerario che il digital twin del
     * treno fa al boot e la richiesta della prossima stazione.
     *
     * Restano aperti perché i processi Treno e Stazione non fanno login: la loro
     * "autenticazione" è la validazione dell'ID sul database centrale via MQTT,
     * fatta all'avvio. Sono due GET che espongono solo l'itinerario di un convoglio
     * e nessuna operazione di scrittura; tutto il resto delle API resta protetto.
     */
    private boolean endpointDiCampo(String metodo, String percorso) {
        if (!"GET".equalsIgnoreCase(metodo)) {
            return false;
        }
        return percorso.equals("api/prossima-stazione")
                || (percorso.startsWith("api/treni/") && percorso.endsWith("/itinerario"));
    }

    /** Estrae il token dall'header Authorization ("Bearer <token>"). */
    private String estraiToken(ContainerRequestContext richiesta) {
        String header = richiesta.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith(PREFISSO_BEARER) ? header.substring(PREFISSO_BEARER.length()).trim() : header.trim();
    }

    /** Toglie l'eventuale slash iniziale, così i confronti sul percorso sono uniformi. */
    private String normalizza(String percorso) {
        if (percorso == null) return "";
        return percorso.startsWith("/") ? percorso.substring(1) : percorso;
    }

    /** Costruisce la risposta di rifiuto nello stesso formato usato dagli altri endpoint. */
    private Response errore(Response.Status stato, String messaggio) {
        return Response.status(stato)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("errore", messaggio))
                .build();
    }
}
