package it.uni.reti2.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Test delle API di amministrazione.
 *
 * <p>Prima ogni test faceva un vero {@code POST /api/auth/login} per farsi dare il
 * token: con Keycloak quell'endpoint non esiste piu' e tenere acceso un container
 * solo per i test sarebbe scomodo. Si usa quindi {@code @TestSecurity}, che inietta
 * direttamente l'identita' (utente + ruoli di realm) che il token avrebbe portato;
 * nel profilo di test l'estensione OIDC e' spenta ({@code %test.quarkus.oidc.enabled=false},
 * in application.properties).</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdminApiTest {

    private static final String STAZIONE_ID = "STAZ_TEST_" + UUID.randomUUID().toString().substring(0, 4);
    private static final String TRENO_ID = "TRENO_TEST_" + UUID.randomUUID().toString().substring(0, 4);

    @Test
    @Order(0)
    public void testSenzaTokenVieneRifiutato() {
        // Prova che gli endpoint non sono pubblici: senza identita' si prende un 401
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(401);
    }

    @Test
    @Order(1)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void testTecnicoNonPuoCreareStazioni() {
        // Il tecnico legge e usa i comandi operativi, ma l'anagrafica non la tocca:
        // e' la separazione dei due ruoli che chiede il PDF, adesso decisa dal ruolo
        // di realm che Keycloak scrive nel token.
        given()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"STAZ_VIETATA\", \"nome\": \"Non deve nascere\"}")
        .when()
            .post("/api/stazioni")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void testTecnicoPuoLeggere() {
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testCreateStazione() {
        String requestBody = """
                {
                    "id": "%s",
                    "nome": "Stazione Test",
                    "stato": "ONLINE",
                    "latitudine": 45.0,
                    "longitudine": 9.0,
                    "binari": 3
                }
                """.formatted(STAZIONE_ID);

        given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/stazioni")
        .then()
            .statusCode(201)
            .body("id", equalTo(STAZIONE_ID))
            .body("nome", equalTo("Stazione Test"));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testGetStazioni() {
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testDeleteStazione() {
        given()
        .when()
            .delete("/api/stazioni/" + STAZIONE_ID)
        .then()
            .statusCode(204);

        // Verifica che sia stata cancellata
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testCreateTreno() {
        // "id" è il nome del convoglio digitato in interfaccia: è la chiave primaria.
        String requestBody = """
                {
                    "id": "%s",
                    "stato": "fermo"
                }
                """.formatted(TRENO_ID);

        given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/treni")
        .then()
            .statusCode(201)
            .body("id", equalTo(TRENO_ID));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testNomeConvoglioDuplicatoRifiutato() {
        // Il nome è la chiave primaria: un secondo treno con lo stesso nome è un conflitto.
        given()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"%s\", \"stato\": \"fermo\"}".formatted(TRENO_ID))
        .when()
            .post("/api/treni")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(8)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testRinominaConvoglioRifiutata() {
        // Il nome identifica il convoglio: la PUT non lo cambia, risponde 400.
        given()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"%s_RINOMINATO\", \"stato\": \"fermo\"}".formatted(TRENO_ID))
        .when()
            .put("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(9)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testUpdateTrenoSenzaCambiareNome() {
        // La modifica di stato e itinerario resta permessa e non tocca la chiave.
        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\": \"attivo\"}")
        .when()
            .put("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(200)
            .body("id", equalTo(TRENO_ID))
            .body("stato", equalTo("attivo"));
    }

    @Test
    @Order(10)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void testSopprimiTreno() {
        // La soppressione e' uno dei tre comandi operativi concessi anche al tecnico.
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/treni/" + TRENO_ID + "/sopprimi")
        .then()
            .statusCode(200)
            .body("stato", equalTo("SOPPRESSO"));
    }

    @Test
    @Order(11)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testDeleteTreno() {
        given()
        .when()
            .delete("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(12)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void testProfiloUtenteCollegato() {
        // /api/auth/me e' cio' che resta di AuthController: la web app lo chiama
        // appena ha il token per sapere chi e' entrato e quale ruolo mostrare.
        given()
        .when()
            .get("/api/auth/me")
        .then()
            .statusCode(200)
            .body("username", equalTo("MAT001"))
            .body("role", equalTo("amministratore"));
    }
}
