package it.uni.reti2.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Test del vincolo sulle tratte negli itinerari.
 *
 * <p>Un itinerario puo' usare solo collegamenti che in rete esistono davvero: prima il
 * gateway creava al volo la Tratta mancante, cosi' spostando o aggiungendo una stazione
 * si inventavano archi fisici che nessuno aveva mai registrato.</p>
 *
 * <p>Lo scenario e' la rete A -> B (una tratta sola) piu' una terza stazione C isolata:
 * tutto cio' che passa da C deve essere rifiutato con 404.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ItinerariTratteTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "IT_A_" + SUFFISSO;
    private static final String STAZ_B = "IT_B_" + SUFFISSO;
    private static final String STAZ_C = "IT_C_" + SUFFISSO;
    private static final String TRATTA_AB = "IT_T_AB_" + SUFFISSO;
    private static final String ITINERARIO = "IT_PERCORSO_" + SUFFISSO;

    private static void creaStazione(String id, String nome) {
        String corpo = """
                {
                    "id": "%s",
                    "nome": "%s",
                    "stato": "ONLINE",
                    "latitudine": 45.0,
                    "longitudine": 9.0,
                    "binari": 2
                }
                """.formatted(id, nome);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .post("/api/stazioni")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(1)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void preparaLaRete() {
        creaStazione(STAZ_A, "Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Bravo " + SUFFISSO);
        creaStazione(STAZ_C, "Charlie " + SUFFISSO);

        // In rete esiste il solo collegamento A -> B: verso C non c'e' niente.
        String corpo = """
                {
                    "id": "%s",
                    "stazionePartenzaId": "%s",
                    "stazioneArrivoId": "%s",
                    "tempoPercorrenzaMinuti": 20
                }
                """.formatted(TRATTA_AB, STAZ_A, STAZ_B);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .post("/api/tratte-elementari")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void itinerarioSuTrattaEsistenteVieneCreato() {
        String corpo = """
                { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .post("/api/tratte")
        .then()
            .statusCode(201)
            .body("stazioni", hasSize(2));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void creazioneRifiutataSeLaTrattaNonEsiste() {
        String corpo = """
                { "id": "%s_KO", "stazioni": ["%s", "%s"], "travelTimes": [15] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_C);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .post("/api/tratte")
        .then()
            .statusCode(404)
            .body("errore", containsString("Manca la tratta"))
            .body("coppieMancanti[0].partenzaId", equalTo(STAZ_A))
            .body("coppieMancanti[0].arrivoId", equalTo(STAZ_C));

        // L'itinerario non deve essere nato a meta'
        given()
        .when()
            .get("/api/tratte")
        .then()
            .statusCode(200)
            .body("findAll { it.id == '%s_KO' }".formatted(ITINERARIO), hasSize(0));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void aggiuntaDiStazioneSenzaTrattaVieneRifiutata() {
        // A -> B esiste, B -> C no: la coda dell'itinerario e' quella che manca.
        String corpo = """
                { "stazioni": ["%s", "%s", "%s"], "travelTimes": [20, 15] }
                """.formatted(STAZ_A, STAZ_B, STAZ_C);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(404)
            .body("coppieMancanti", hasSize(1))
            .body("coppieMancanti[0].partenzaId", equalTo(STAZ_B))
            .body("coppieMancanti[0].arrivoId", equalTo(STAZ_C));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void cambioDiOrdineSenzaTrattaVieneRifiutato() {
        // Scambiare A e B chiede il verso opposto, B -> A, che in rete non c'e':
        // la tratta e' un arco orientato e i due versi sono due righe distinte.
        String corpo = """
                { "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(STAZ_B, STAZ_A);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(404)
            .body("coppieMancanti[0].partenzaId", equalTo(STAZ_B))
            .body("coppieMancanti[0].arrivoId", equalTo(STAZ_A));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void elencoTroppoCortoVieneRifiutato() {
        // Un itinerario con una stazione sola non e' un aggiornamento da ignorare:
        // prima la PUT rispondeva 200 lasciando le stazioni com'erano, e il form
        // dava per salvata una modifica che non era mai stata applicata.
        String corpo = """
                { "stazioni": ["%s"], "travelTimes": [] }
                """.formatted(STAZ_A);
        given()
            .contentType(ContentType.JSON)
            .body(corpo)
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(400)
            .body("errore", containsString("almeno due stazioni"));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void ilRifiutoNonSmontaLItinerario() {
        // E' il punto delicato: la PUT cancella le righe di Itinerario_Tratta prima di
        // ricomporle, e la @Transactional NON fa rollback quando si torna una Response
        // di errore. Se la verifica non stesse prima della delete, dopo i rifiuti
        // qui sopra l'itinerario sarebbe rimasto senza nessuna stazione.
        given()
        .when()
            .get("/api/tratte")
        .then()
            .statusCode(200)
            .body("find { it.id == '%s' }.stazioni".formatted(ITINERARIO), hasSize(2))
            .body("find { it.id == '%s' }.stazioni[0]".formatted(ITINERARIO), equalTo(STAZ_A))
            .body("find { it.id == '%s' }.stazioni[1]".formatted(ITINERARIO), equalTo(STAZ_B));
    }

    @Test
    @Order(8)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void pulisci() {
        given().when().delete("/api/tratte/" + ITINERARIO).then().statusCode(204);
        given().when().delete("/api/tratte-elementari/" + TRATTA_AB).then().statusCode(204);
        for (String id : new String[]{STAZ_A, STAZ_B, STAZ_C}) {
            given().when().delete("/api/stazioni/" + id).then().statusCode(204);
        }
    }
}
