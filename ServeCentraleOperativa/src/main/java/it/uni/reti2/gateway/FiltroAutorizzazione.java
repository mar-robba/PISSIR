package it.uni.reti2.gateway;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
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
 * treno con un curl. Adesso ogni richiesta sotto /api deve portare l'access token
 * rilasciato da Keycloak nell'header {@code Authorization: Bearer <token>}.</p>
 *
 * <p>Attenzione a dove finisce la responsabilita' di ciascun pezzo, perche' con
 * l'arrivo di Keycloak il filtro fa meta' del lavoro di prima:</p>
 * <ul>
 *   <li>chi il token e' VALIDO lo stabilisce l'estensione {@code quarkus-oidc}, che
 *       ne verifica firma, scadenza, emittente e audience con le chiavi pubbliche
 *       del realm. Un token scaduto o taroccato viene bloccato prima ancora di
 *       arrivare qui;</li>
 *   <li>questo filtro decide soltanto CHI puo' fare COSA, leggendo i ruoli che
 *       Keycloak ha scritto nel token ({@code realm_access.roles}).</li>
 * </ul>
 *
 * <p>Regole applicate:</p>
 * <ul>
 *   <li>le preflight OPTIONS restano pubbliche (altrimenti salta il CORS);</li>
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

    /** Ruolo di realm di chi può fare tutto, comprese le CRUD di stazioni/treni/tratte. */
    public static final String RUOLO_AMMINISTRATORE = "amministratore";

    /** Ruolo di realm di chi legge e usa i comandi operativi, ma non tocca l'anagrafica. */
    public static final String RUOLO_TECNICO = "tecnico";

    /**
     * Identita' costruita da quarkus-oidc a partire dal token.
     * E' "anonima" quando la richiesta non porta nessun Authorization header.
     */
    @Inject
    SecurityIdentity identita;

    @Override
    public void filter(ContainerRequestContext richiesta) {
        String metodo = richiesta.getMethod();
        String percorso = normalizza(richiesta.getUriInfo().getPath());

        // La preflight del browser non porta header applicativi: va lasciata passare,
        // altrimenti il CORS fallisce prima ancora della chiamata vera.
        if ("OPTIONS".equalsIgnoreCase(metodo)) {
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

        if (identita == null || identita.isAnonymous()) {
            LOG.warnf("🔒 %s /%s rifiutata: token assente o non valido", metodo, percorso);
            richiesta.abortWith(errore(Response.Status.UNAUTHORIZED,
                    "Autenticazione richiesta: effettuare il login"));
            return;
        }

        // Nessun ruolo del progetto nel token: l'utente esiste in Keycloak ma non e'
        // stato abilitato a questa applicazione, quindi non deve vedere niente.
        if (!identita.hasRole(RUOLO_AMMINISTRATORE) && !identita.hasRole(RUOLO_TECNICO)) {
            LOG.warnf("🔒 %s /%s rifiutata: l'utente %s non ha nessun ruolo del realm railway",
                    metodo, percorso, identita.getPrincipal().getName());
            richiesta.abortWith(errore(Response.Status.FORBIDDEN,
                    "Utente senza ruoli abilitati sulla Centrale Operativa"));
            return;
        }

        if (richiedeAmministratore(metodo, percorso) && !identita.hasRole(RUOLO_AMMINISTRATORE)) {
            LOG.warnf("🔒 %s /%s rifiutata: l'utente %s è tecnico, serve l'amministratore",
                    metodo, percorso, identita.getPrincipal().getName());
            richiesta.abortWith(errore(Response.Status.FORBIDDEN,
                    "Operazione riservata all'amministratore"));
        }
    }

    /**
     * Decide se l'operazione richiesta è riservata all'amministratore.
     * Le letture no, e nemmeno i comandi operativi che il PDF assegna al tecnico
     * (invio operatori, soppressione corsa, presa in carico e chiusura di un allarme).
     */
    private boolean richiedeAmministratore(String metodo, String percorso) {
        if ("GET".equalsIgnoreCase(metodo) || "HEAD".equalsIgnoreCase(metodo)) {
            return false;
        }
        // La fine dell'intervento sta con l'invio della squadra: sono i due estremi dello
        // stesso comando operativo (RF01.4.1), e se il tecnico puo' mandarla deve anche poter
        // dire che ha finito, altrimenti la stazione resterebbe in manutenzione fino a quando
        // non passa un amministratore.
        return !percorso.endsWith("/sopprimi") && !percorso.endsWith("/risolvi")
                && !percorso.endsWith("/assegna") && !percorso.endsWith("/manutenzione")
                && !percorso.endsWith("/manutenzione/conclusa");
    }

    /**
     * Riconosce i due endpoint di sola lettura usati dai NODI DI CAMPO e non
     * dall'interfaccia utente: il download dell'itinerario che il digital twin del
     * treno fa al boot e la richiesta della prossima stazione.
     *
     * Restano aperti perché i processi Treno e Stazione non fanno login su Keycloak:
     * la loro "autenticazione" è la validazione dell'ID sul database centrale via MQTT,
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
