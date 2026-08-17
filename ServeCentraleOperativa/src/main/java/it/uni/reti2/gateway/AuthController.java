package it.uni.reti2.gateway;

import it.uni.reti2.entity.Utente;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    /**
     * Chi ha presentato il token: matricola, ruolo di realm e riga di anagrafica.
     * La lettura del JWT sta tutta li' dentro, perche' serve anche al
     * {@link RestApiGateway} per firmare le righe di storico delle assegnazioni.
     */
    @Inject
    OperatoreCorrente operatore;

    /**
     * Restituisce il profilo dell'utente che ha presentato il token.
     *
     * @return 200 con il profilo, 401 se la chiamata arriva senza token valido.
     */
    @GET
    @Path("/me")
    public Response profilo() {
        if (operatore.anonimo()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("errore", "Autenticazione richiesta: effettuare il login"))
                    .build();
        }

        String matricola = operatore.matricola();

        // Riga di anagrafica corrispondente: serve per restituire lo stesso id_utente
        // (U1, U2, ...) usato dalle chiavi esterne dei guasti.
        Utente utente = operatore.inAnagrafica();

        String nome = utente != null ? utente.nome : operatore.claim("given_name");
        String cognome = utente != null ? utente.cognome : operatore.claim("family_name");

        Map<String, Object> profilo = new HashMap<>();
        profilo.put("id", utente != null ? utente.id : matricola);
        profilo.put("username", matricola);
        // Il ruolo applicativo non si deduce piu' dalla colonna "tipo" del database:
        // e' il ruolo di realm che Keycloak ha messo dentro il token.
        profilo.put("role", operatore.ruolo());
        profilo.put("displayName", componiNomeCompleto(nome, cognome, matricola));
        profilo.put("avatarInitials", iniziali(nome, cognome, matricola));

        return Response.ok(profilo).build();
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
