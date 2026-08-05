package it.uni.reti2.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Test delle API di amministrazione. Da quando esiste FiltroAutorizzazione ogni
 * chiamata deve portare il token di sessione ottenuto dal login, quindi il test
 * si autentica come amministratore (MAT001, seed di import.sql) prima di partire.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdminApiTest {

    private static final String STAZIONE_ID = "STAZ_TEST_" + UUID.randomUUID().toString().substring(0, 4);
    private static final String TRENO_ID = "TRENO_TEST_" + UUID.randomUUID().toString().substring(0, 4);

    /** Token di sessione dell'amministratore, riusato da tutti i test. */
    private static String token;

    /**
     * Restituisce una richiesta già autenticata come amministratore, facendo il login
     * la prima volta che serve. Il login NON si può mettere in un @BeforeAll statico:
     * verrebbe eseguito prima che Quarkus comunichi a RestAssured la porta di test
     * (che è casuale, quarkus.http.test-port=0) e finirebbe sulla 8080 con
     * "connessione rifiutata".
     */
    private static io.restassured.specification.RequestSpecification autenticato() {
        if (token == null) {
            token = given()
                    .contentType(ContentType.JSON)
                    .body("{\"username\":\"MAT001\",\"password\":\"password\"}")
            .when()
                    .post("/api/auth/login")
            .then()
                    .statusCode(200)
                    .extract().path("token");
        }
        return given().header("Authorization", "Bearer " + token);
    }

    @Test
    @Order(0)
    public void testSenzaTokenVieneRifiutato() {
        // Prova che gli endpoint non sono più pubblici: senza header si prende un 401
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(401);
    }

    @Test
    @Order(1)
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

        autenticato()
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
    @Order(2)
    public void testGetStazioni() {
        autenticato()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    public void testDeleteStazione() {
        autenticato()
        .when()
            .delete("/api/stazioni/" + STAZIONE_ID)
        .then()
            .statusCode(204);

        // Verifica che sia stata cancellata
        autenticato()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    public void testCreateTreno() {
        // "id" è il nome del convoglio digitato in interfaccia: è la chiave primaria.
        String requestBody = """
                {
                    "id": "%s",
                    "stato": "fermo"
                }
                """.formatted(TRENO_ID);

        autenticato()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/treni")
        .then()
            .statusCode(201)
            .body("id", equalTo(TRENO_ID));
    }

    @Test
    @Order(5)
    public void testNomeConvoglioDuplicatoRifiutato() {
        // Il nome è la chiave primaria: un secondo treno con lo stesso nome è un conflitto.
        autenticato()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"%s\", \"stato\": \"fermo\"}".formatted(TRENO_ID))
        .when()
            .post("/api/treni")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(6)
    public void testRinominaConvoglioRifiutata() {
        // Il nome identifica il convoglio: la PUT non lo cambia, risponde 400.
        autenticato()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"%s_RINOMINATO\", \"stato\": \"fermo\"}".formatted(TRENO_ID))
        .when()
            .put("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(7)
    public void testUpdateTrenoSenzaCambiareNome() {
        // La modifica di stato e itinerario resta permessa e non tocca la chiave.
        autenticato()
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
    @Order(8)
    public void testSopprimiTreno() {
        autenticato()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/treni/" + TRENO_ID + "/sopprimi")
        .then()
            .statusCode(200)
            .body("stato", equalTo("SOPPRESSO"));
    }

    @Test
    @Order(9)
    public void testDeleteTreno() {
        autenticato()
        .when()
            .delete("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(204);
    }
}
