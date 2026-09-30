package it.uni.reti2.gateway;

import io.javalin.http.Context;
import org.pac4j.core.profile.UserProfile;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Il filtro che protegge le API della Centrale: un before-handler di Javalin su /api/*.
 *
 * <p>Il PDF chiede due tipologie di utenti con permessi diversi. Prima la distinzione
 * era solo grafica: nessun endpoint controllava nulla e chiunque poteva cancellare un
 * treno con un curl. Adesso ogni richiesta sotto /api deve portare l'access token
 * rilasciato da Keycloak nell'header {@code Authorization: Bearer <token>}.</p>
 *
 * <p>Attenzione a dove finisce la responsabilita' di ciascun pezzo, perche' con
 * l'arrivo di Keycloak il filtro fa meta' del lavoro di prima:</p>
 * <ul>
 *   <li>chi il token e' VALIDO lo stabilisce {@link SicurezzaKeycloak} (pac4j), che ne
 *       verifica firma, scadenza, emittente e audience con le chiavi pubbliche del
 *       realm. Un token scaduto o taroccato qui arriva come un "no";</li>
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
public class FiltroAutorizzazione {

    private static final Logger LOG = Logger.getLogger(FiltroAutorizzazione.class.getName());

    /** Ruolo di realm di chi può fare tutto, comprese le CRUD di stazioni/treni/tratte. */
    public static final String RUOLO_AMMINISTRATORE = "amministratore";

    /** Ruolo di realm di chi legge e usa i comandi operativi, ma non tocca l'anagrafica. */
    public static final String RUOLO_TECNICO = "tecnico";

    /**
     * Nome dell'attributo della richiesta in cui il filtro lascia il profilo dell'operatore:
     * lo legge AuthController per GET /api/auth/me.
     */
    public static final String ATTRIBUTO_PROFILO = "profiloOperatore";

    /** La verifica del token (pac4j). */
    public SicurezzaKeycloak sicurezza;

    /**
     * Il controllo, fatto prima di ogni richiesta sotto /api. Se la richiesta va rifiutata
     * scrive la risposta (401 o 403, con il motivo) e ferma gli handler successivi, cosi'
     * l'endpoint non viene nemmeno chiamato.
     */
    public void filtra(Context ctx) {
        String metodo = ctx.method().name();
        String percorso = normalizza(ctx.path());

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

        UserProfile profilo = sicurezza.autentica(ctx) ? sicurezza.profilo(ctx).orElse(null) : null;
        if (profilo == null) {
            LOG.warning(String.format("🔒 %s /%s rifiutata: token assente o non valido", metodo, percorso));
            rifiuta(ctx, 401, "Autenticazione richiesta: effettuare il login");
            return;
        }

        // Nessun ruolo del progetto nel token: l'utente esiste in Keycloak ma non e'
        // stato abilitato a questa applicazione, quindi non deve vedere niente.
        if (!profilo.getRoles().contains(RUOLO_AMMINISTRATORE) && !profilo.getRoles().contains(RUOLO_TECNICO)) {
            LOG.warning(String.format("🔒 %s /%s rifiutata: l'utente %s non ha nessun ruolo del realm railway",
                    metodo, percorso, nomeUtente(profilo)));
            rifiuta(ctx, 403, "Utente senza ruoli abilitati sulla Centrale Operativa");
            return;
        }

        if (richiedeAmministratore(metodo, percorso) && !profilo.getRoles().contains(RUOLO_AMMINISTRATORE)) {
            LOG.warning(String.format("🔒 %s /%s rifiutata: l'utente %s è tecnico, serve l'amministratore",
                    metodo, percorso, nomeUtente(profilo)));
            rifiuta(ctx, 403, "Operazione riservata all'amministratore");
            return;
        }
        ctx.attribute(ATTRIBUTO_PROFILO, profilo);
    }

    /**
     * Il nome con cui l'utente ha fatto il login (mat001). E' il claim preferred_username di
     * Keycloak, lo stesso che quarkus-oidc usava come nome del principal; se manca, l'id del
     * profilo (il "sub" del token).
     */
    public static String nomeUtente(UserProfile profilo) {
        Object nome = profilo.getAttribute("preferred_username");
        return nome != null ? nome.toString() : profilo.getId();
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
        return !percorso.endsWith("/sopprimi") && !percorso.endsWith("/risolvi")
                && !percorso.endsWith("/manutenzione");
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

    /**
     * Scrive la risposta di rifiuto nello stesso formato usato dagli altri endpoint e salta
     * gli handler che restano, cioe' l'endpoint vero e proprio.
     */
    private void rifiuta(Context ctx, int stato, String messaggio) {
        ctx.status(stato).json(Map.of("errore", messaggio));
        ctx.skipRemainingHandlers();
    }
}
