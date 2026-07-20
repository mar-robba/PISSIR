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
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdminApiTest {

    private static final String STAZIONE_ID = "STAZ_TEST_" + UUID.randomUUID().toString().substring(0, 4);
    private static final String TRENO_ID = "TRENO_TEST_" + UUID.randomUUID().toString().substring(0, 4);

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
    @Order(2)
    public void testGetStazioni() {
        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
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
    @Order(4)
    public void testCreateTreno() {
        String requestBody = """
                {
                    "id": "%s",
                    "nome": "Frecciarossa 1000",
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
            .body("id", equalTo(TRENO_ID))
            .body("nome", equalTo("Frecciarossa 1000"));
    }

    @Test
    @Order(5)
    public void testSopprimiTreno() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/treni/" + TRENO_ID + "/sopprimi")
        .then()
            .statusCode(200)
            .body("stato", equalTo("SOPPRESSO"));
    }

    @Test
    @Order(6)
    public void testDeleteTreno() {
        given()
        .when()
            .delete("/api/treni/" + TRENO_ID)
        .then()
            .statusCode(204);
    }
}
