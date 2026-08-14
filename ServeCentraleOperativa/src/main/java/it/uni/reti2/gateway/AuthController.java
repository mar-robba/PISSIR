package it.uni.reti2.gateway;

import io.quarkus.security.identity.SecurityIdentity;
import it.uni.reti2.entity.Utente;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * Profilo dell'operatore collegato.
 *
 * <p>Qui prima c'erano il {@code POST /api/auth/login} che confrontava la password
 * con la colonna della tabella Utenti e il {@code /logout} che buttava via il token:
 * con il passaggio a Keycloak sono spariti tutti e due. Le credenziali le verifica
 * Keycloak, il logout si fa sul suo endpoint di fine sessione, e la Centrale riceve
 * soltanto un JWT firmato di cui controlla la firma (vedi {@code quarkus.oidc.*}).</p>
 *
 * <p>Resta un solo endpoint, {@code GET /api/auth/me}: la web app lo chiama appena
 * ottenuto il token per sapere chi e' l'utente e cosa puo' fare. Il payload e' lo
 * stesso di prima (id, username, role, displayName, avatarInitials) perche' il
 * frontend non doveva cambiare struttura dati, ma i valori adesso vengono per meta'
 * dal token e per meta' dalla tabella Utenti, che e' rimasta come anagrafica degli
 * operatori (i guasti hanno una chiave esterna verso di lei).</p>
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthController {

    /** Identita' ricavata dal token: contiene username e ruoli di realm. */
    @Inject
    SecurityIdentity identita;

    /**
     * Restituisce il profilo dell'utente che ha presentato il token.
     *
     * @return 200 con il profilo, 401 se la chiamata arriva senza token valido.
     */
    @GET
    @Path("/me")
    public Response profilo() {
        if (identita == null || identita.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("errore", "Autenticazione richiesta: effettuare il login"))
                    .build();
        }

        // Keycloak salva gli username in minuscolo (mat001), mentre in tutto il resto
        // del sistema l'operatore e' identificato dalla matricola (MAT001). Il claim
        // "matricola" arriva dal protocol mapper del realm; se qualcuno crea un utente
        // a mano dalla console senza quell'attributo si ripiega sullo username.
        String matricola = claimTestuale("matricola");
        if (matricola == null || matricola.isBlank()) {
            matricola = identita.getPrincipal().getName().toUpperCase();
        }

        // Riga di anagrafica corrispondente: serve per restituire lo stesso id_utente
        // (U1, U2, ...) usato dalle chiavi esterne dei guasti.
        Utente utente = Utente.find("upper(matricola) = ?1", matricola.toUpperCase()).firstResult();

        String nome = utente != null ? utente.nome : claimTestuale("given_name");
        String cognome = utente != null ? utente.cognome : claimTestuale("family_name");

        // Il ruolo applicativo non si deduce piu' dalla colonna "tipo" del database:
        // e' il ruolo di realm che Keycloak ha messo dentro il token.
        String ruolo = identita.hasRole(FiltroAutorizzazione.RUOLO_AMMINISTRATORE)
                ? FiltroAutorizzazione.RUOLO_AMMINISTRATORE
                : FiltroAutorizzazione.RUOLO_TECNICO;

        Map<String, Object> profilo = new HashMap<>();
        profilo.put("id", utente != null ? utente.id : matricola);
        profilo.put("username", matricola);
        profilo.put("role", ruolo);
        profilo.put("displayName", componiNomeCompleto(nome, cognome, matricola));
        profilo.put("avatarInitials", iniziali(nome, cognome, matricola));

        return Response.ok(profilo).build();
    }

    /**
     * Legge un claim di testo dal token.
     *
     * <p>Il principal e' un {@link JsonWebToken} solo quando l'identita' arriva
     * davvero da Keycloak: nei test viene simulata con {@code @TestSecurity} e il
     * principal e' un oggetto qualsiasi, quindi il controllo di tipo serve per non
     * far esplodere i test.</p>
     *
     * @param nomeClaim Nome del claim dentro il JWT.
     * @return Il valore del claim, oppure null se assente.
     */
    private String claimTestuale(String nomeClaim) {
        Principal principal = identita.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object valore = jwt.getClaim(nomeClaim);
            return valore != null ? valore.toString() : null;
        }
        return null;
    }

    /** "Mario Rossi", oppure la matricola se l'anagrafica non ha nome e cognome. */
    private String componiNomeCompleto(String nome, String cognome, String matricola) {
        String completo = ((nome != null ? nome : "") + " " + (cognome != null ? cognome : "")).trim();
        return completo.isEmpty() ? matricola : completo;
    }

    /** Iniziali per l'avatar della sidebar: "MR" per Mario Rossi. */
    private String iniziali(String nome, String cognome, String matricola) {
        String risultato = "";
        if (nome != null && !nome.isEmpty()) {
            risultato += nome.substring(0, 1).toUpperCase();
        }
        if (cognome != null && !cognome.isEmpty()) {
            risultato += cognome.substring(0, 1).toUpperCase();
        }
        // Utente senza nome in anagrafica: si usano le prime due lettere della matricola.
        if (risultato.isEmpty() && matricola != null && !matricola.isEmpty()) {
            risultato = matricola.substring(0, Math.min(2, matricola.length())).toUpperCase();
        }
        return risultato;
    }
}
