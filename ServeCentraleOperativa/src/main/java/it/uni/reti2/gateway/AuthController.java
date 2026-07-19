package it.uni.reti2.gateway;

import it.uni.reti2.entity.Utente;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {

    public static class LoginRequest {
        public String username;
        public String password;
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        if (request == null || request.username == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Missing username").build();
        }

        String usernameToSearch = request.username.trim();
        Utente utente = Utente.find("matricola", usernameToSearch).firstResult();
        if (utente == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid credentials").build();
        }

        // For this demo, we assume any password works as long as the matricola exists.
        // We will just accept it if the matricola exists.

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("id", utente.id);
        userPayload.put("username", utente.matricola);
        userPayload.put("role", utente.tipo);
        userPayload.put("displayName", utente.nome + " " + utente.cognome);
        
        String initials = "";
        if (utente.nome != null && !utente.nome.isEmpty()) {
            initials += utente.nome.substring(0, 1).toUpperCase();
        }
        if (utente.cognome != null && !utente.cognome.isEmpty()) {
            initials += utente.cognome.substring(0, 1).toUpperCase();
        }
        userPayload.put("avatarInitials", initials);

        return Response.ok(userPayload).build();
    }
}
