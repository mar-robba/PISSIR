package it.uni.reti2.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test di non regressione per tre dei bug corretti il 16/08/2026
 * (elenco in doc/AnalisiDellaBaseDiCodice/bug_trovati.md):
 *
 * <ul>
 *   <li><b>A06</b>: una PUT su un itinerario che non parla di assegnazioni non deve
 *       sganciare i convogli assegnati da un'altra schermata;</li>
 *   <li><b>A20</b>: un itinerario che ripassa sulla stessa tratta va rifiutato con un 400
 *       che si capisce, non con il 500 della chiave primaria violata;</li>
 *   <li><b>A12</b>: una tratta usata come posizione corrente di un convoglio va rifiutata
 *       con un 409, non con il 500 della chiave esterna violata.</li>
 * </ul>
 *
 * <p>La rete di prova e' A -&gt; B e B -&gt; A (i due versi sono archi distinti), un
 * itinerario che percorre A -&gt; B e un convoglio assegnato a quell'itinerario.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RegressioniBugTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "REG_A_" + SUFFISSO;
    private static final String STAZ_B = "REG_B_" + SUFFISSO;
    private static final String TRATTA_AB = "REG_T_AB_" + SUFFISSO;
    private static final String TRATTA_BA = "REG_T_BA_" + SUFFISSO;
    private static final String ITINERARIO = "REG_IT_" + SUFFISSO;
    private static final String TRENO = "REG_TRN_" + SUFFISSO;

    /** Cache della rete: e' la sorgente dei dati dello snapshot periodico. */
    @Inject
    TrafficLogicEngine statoRete;

    /** Lo stesso mapper che usa il FaultMonitor per costruire il payload dello snapshot. */
    @Inject
    ObjectMapper mapper;

    private static void creaStazione(String id, String nome) {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "nome": "%s", "stato": "ONLINE",
                      "latitudine": 45.0, "longitudine": 9.0, "binari": 2 }
                    """.formatted(id, nome))
        .when()
            .post("/api/stazioni")
        .then()
            .statusCode(201);
    }

    private static void creaTrattaElementare(String id, String partenza, String arrivo) {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "stazionePartenzaId": "%s", "stazioneArrivoId": "%s",
                      "tempoPercorrenzaMinuti": 20 }
                    """.formatted(id, partenza, arrivo))
        .when()
            .post("/api/tratte-elementari")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(1)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void preparaLaRete() {
        creaStazione(STAZ_A, "Regressione Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Regressione Bravo " + SUFFISSO);
        creaTrattaElementare(TRATTA_AB, STAZ_A, STAZ_B);
        creaTrattaElementare(TRATTA_BA, STAZ_B, STAZ_A);

        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                    """.formatted(ITINERARIO, STAZ_A, STAZ_B))
        .when()
            .post("/api/tratte")
        .then()
            .statusCode(201);

        // Il convoglio viene assegnato dalla pagina Amministrazione (form Treni), cioe'
        // da una schermata diversa dall'editor degli itinerari: e' il punto di partenza
        // dello scenario di A06.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "stato": "fermo", "itinerario": { "id": "%s" } }
                    """.formatted(TRENO, ITINERARIO))
        .when()
            .post("/api/treni")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void modificareLeStazioniNonSganciaITreniAssegnati() {
        // A06: l'editor degli itinerari cambia solo le stazioni e NON manda "treniIds",
        // perche' quelle assegnazioni non le gestisce. Prima rimandava indietro la lista
        // scaricata all'avvio della pagina e il convoglio assegnato poco prima veniva
        // sganciato senza che nessuno lo avesse chiesto.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "stazioni": ["%s", "%s"], "travelTimes": [25] }
                    """.formatted(STAZ_A, STAZ_B))
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/api/tratte")
        .then()
            .statusCode(200)
            .body("find { it.id == '%s' }.treniIds".formatted(ITINERARIO), hasItem(TRENO));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void elencoDiTreniVuotoSganciaDavvero() {
        // Rovescio della medaglia: se "treniIds" c'e' ed e' vuoto, allora l'intenzione e'
        // proprio quella di togliere tutte le assegnazioni, e va rispettata.
        given()
            .contentType(ContentType.JSON)
            .body("{ \"treniIds\": [] }")
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/api/tratte")
        .then()
            .statusCode(200)
            .body("find { it.id == '%s' }.treniIds".formatted(ITINERARIO), hasSize(0));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void itinerarioCheRipassaSullaStessaTrattaVieneRifiutato() {
        // A20: la chiave di Itinerario_Tratta e' (id_itinerario, id_Tratta) e l'ordine non
        // ne fa parte, quindi A -> B -> A -> B chiederebbe due righe con la stessa chiave.
        // Entrambi gli archi esistono in rete: l'unico problema e' la ripetizione.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s_BIS", "stazioni": ["%s", "%s", "%s", "%s"],
                      "travelTimes": [20, 20, 20] }
                    """.formatted(ITINERARIO, STAZ_A, STAZ_B, STAZ_A, STAZ_B))
        .when()
            .post("/api/tratte")
        .then()
            .statusCode(400)
            .body("errore", containsString("due volte la stessa tratta"));

        // E soprattutto non deve essere nato niente a meta'
        given()
        .when()
            .get("/api/tratte")
        .then()
            .statusCode(200)
            .body("findAll { it.id == '%s_BIS' }".formatted(ITINERARIO), hasSize(0));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void trattaUsataComePosizioneDiUnTrenoNonSiElimina() {
        // A12: la posizione del convoglio la scrive il consumer dei passaggi MQTT, qui la
        // si imposta a mano perche' non esiste una REST che lo faccia. La transazione va
        // chiusa PRIMA della chiamata HTTP, altrimenti il gateway non vedrebbe la modifica.
        QuarkusTransaction.requiringNew().run(() -> {
            Treno treno = Treno.findById(TRENO);
            treno.posizioneAttualeTratta = Tratta.findById(TRATTA_BA);
        });

        given()
        .when()
            .delete("/api/tratte-elementari/" + TRATTA_BA)
        .then()
            .statusCode(409)
            .body("errore", containsString("posizione corrente"));

        // Liberata la tratta, l'eliminazione deve passare: il rifiuto era sul dato, non sul comando.
        QuarkusTransaction.requiringNew().run(() -> {
            Treno treno = Treno.findById(TRENO);
            treno.posizioneAttualeTratta = null;
        });

        given()
        .when()
            .delete("/api/tratte-elementari/" + TRATTA_BA)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(6)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void loSnapshotPortaKpiTreniEStazioni() {
        // A03: lo snapshot periodico conteneva i soli KPI, quindi anche leggendolo il
        // browser non avrebbe potuto riallineare niente (RF02.6.3 parla di "fotografia
        // completa dello stato corrente"). Qui si controlla che il payload si costruisca
        // davvero e che porti i campi che il mappatore del frontend si aspetta: sono
        // entita' JPA con relazioni, ed e' il punto dove una serializzazione puo' rompersi.
        JsonNode kpi = mapper.valueToTree(statoRete.kpiDashboard());
        JsonNode treni = mapper.valueToTree(statoRete.getTuttiTreni());
        JsonNode stazioni = mapper.valueToTree(statoRete.getTutteStazioni());

        assertTrue(kpi.hasNonNull("trainsInMotion"), "i KPI devono contenere trainsInMotion");
        assertTrue(kpi.hasNonNull("stationsFaulty"), "i KPI devono contenere stationsFaulty");
        assertTrue(kpi.hasNonNull("avgDelay"), "i KPI devono contenere avgDelay");

        JsonNode trenoDiProva = null;
        for (JsonNode t : treni) {
            if (TRENO.equals(t.path("id").asText())) trenoDiProva = t;
        }
        assertNotNull(trenoDiProva, "il convoglio di prova deve comparire nello snapshot");
        assertTrue(trenoDiProva.has("stato"), "serve lo stato per la mappa");
        assertTrue(trenoDiProva.has("progresso"), "serve il progresso per la barra di avanzamento");

        JsonNode stazioneDiProva = null;
        for (JsonNode s : stazioni) {
            if (STAZ_A.equals(s.path("id").asText())) stazioneDiProva = s;
        }
        assertNotNull(stazioneDiProva, "la stazione di prova deve comparire nello snapshot");
        assertTrue(stazioneDiProva.has("stato"), "serve lo stato operativo della stazione");
    }

    @Test
    @Order(7)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void pulisci() {
        given().when().delete("/api/treni/" + TRENO).then().statusCode(204);
        given().when().delete("/api/tratte/" + ITINERARIO).then().statusCode(204);
        given().when().delete("/api/tratte-elementari/" + TRATTA_AB).then().statusCode(204);
        for (String id : new String[]{STAZ_A, STAZ_B}) {
            given().when().delete("/api/stazioni/" + id).then().statusCode(204);
        }
    }
}
