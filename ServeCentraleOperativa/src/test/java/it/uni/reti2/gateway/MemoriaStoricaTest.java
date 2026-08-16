package it.uni.reti2.gateway;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoStatoStazione;
import it.uni.reti2.entity.StoricoStatoTreno;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica di RF02.7 (memoria storica): quello che e' successo resta scritto anche
 * dopo che la stazione, il convoglio o la tratta che l'hanno prodotto sono stati tolti
 * dalla rete.
 *
 * <p>Finche' gli storici avevano una chiave esterna verso l'anagrafica succedeva il
 * contrario: per cancellare una stazione il gateway doveva prima buttare via i suoi
 * transiti storici, e una tratta gia' storicizzata non si poteva togliere affatto. I tre
 * test qui sotto sono esattamente quei tre casi.</p>
 *
 * <p>La rete di prova e' A -&gt; B (usata da un itinerario), B -&gt; C (libera) e una
 * stazione C che alla fine viene dismessa.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MemoriaStoricaTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "MEM_A_" + SUFFISSO;
    private static final String STAZ_B = "MEM_B_" + SUFFISSO;
    private static final String STAZ_C = "MEM_C_" + SUFFISSO;
    private static final String TRATTA_AB = "MEM_T_AB_" + SUFFISSO;
    private static final String TRATTA_BC = "MEM_T_BC_" + SUFFISSO;
    private static final String ITINERARIO = "MEM_IT_" + SUFFISSO;
    private static final String TRENO = "MEM_TRN_" + SUFFISSO;

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
        creaStazione(STAZ_A, "Memoria Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Memoria Bravo " + SUFFISSO);
        creaStazione(STAZ_C, "Memoria Charlie " + SUFFISSO);
        creaTrattaElementare(TRATTA_AB, STAZ_A, STAZ_B);
        creaTrattaElementare(TRATTA_BC, STAZ_B, STAZ_C);

        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                    """.formatted(ITINERARIO, STAZ_A, STAZ_B))
        .when()
            .post("/api/tratte")
        .then()
            .statusCode(201);

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
    public void laTrattaPresenteSoloNelloStoricoSiPuoEliminare() {
        // Un passaggio gia' avvenuto sulla tratta B -> C, come lo scriverebbe l'ingestione.
        QuarkusTransaction.requiringNew().run(() -> {
            StoricoTransito storico = StoricoTransito.fotografiaDi(transitoDiProva(STAZ_C, TRATTA_BC));
            storico.persist();
            // Il nome della stazione e la descrizione della tratta finiscono dentro la riga:
            // sono loro a sostituire la chiave esterna.
            assertNotNull(storico.nomeStazione);
            assertTrue(storico.descrizioneTratta.contains("->"),
                    "la riga deve portarsi dentro la descrizione della tratta");
        });

        // Prima questa DELETE tornava 409 "Tratta presente nei transiti storici".
        given()
        .when()
            .delete("/api/tratte-elementari/" + TRATTA_BC)
        .then()
            .statusCode(204);

        // E il passaggio e' ancora li', con il percorso leggibile.
        QuarkusTransaction.requiringNew().run(() -> {
            StoricoTransito storico = StoricoTransito.find("trattaId", TRATTA_BC).firstResult();
            assertNotNull(storico, "il transito storico non deve sparire con la tratta");
            assertTrue(storico.descrizioneTratta.contains("->"));
        });
    }

    @Test
    @Order(3)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void eliminataLaStazioneLaSuaStoriaResta() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Un transito ancora aperto (tabella viva) e il cambio di stato della stazione.
            transitoDiProva(STAZ_C, null).persist();
            Stazione stazione = Stazione.findById(STAZ_C);
            StoricoStatoStazione.fotografiaDi(stazione, "GUASTA", "ONLINE").persist();
        });

        given()
        .when()
            .delete("/api/stazioni/" + STAZ_C)
        .then()
            .statusCode(204);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0, Transito.count("stazione.id", STAZ_C),
                    "i transiti VIVI vanno via con la stazione: sono stato corrente");
            assertTrue(StoricoTransito.count("stazioneId", STAZ_C) > 0,
                    "i transiti STORICI restano: la stazione e' stata dismessa, non e' mai esistita");

            StoricoStatoStazione statoStorico = StoricoStatoStazione.find("stazioneId", STAZ_C).firstResult();
            assertNotNull(statoStorico);
            assertEquals("GUASTA", statoStorico.stato);
            assertEquals("ONLINE", statoStorico.statoPrecedente);
            // Il nome sopravvive alla riga di anagrafica: e' il motivo per cui lo si copia.
            assertTrue(statoStorico.nome.startsWith("Memoria Charlie"));
        });
    }

    @Test
    @Order(4)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void eliminatoIlConvoglioISuoiCambiDiStatoRestano() {
        QuarkusTransaction.requiringNew().run(() -> {
            Treno treno = Treno.findById(TRENO);
            treno.posizioneAttualeTratta = Tratta.findById(TRATTA_AB);
            treno.stato = "attivo";
            StoricoStatoTreno storico = StoricoStatoTreno.fotografiaDi(treno, "fermo");
            storico.persist();
            assertTrue(storico.descrizionePosizione.contains("->"),
                    "anche la posizione va scritta in chiaro, la tratta si puo' eliminare");
        });

        given()
        .when()
            .delete("/api/treni/" + TRENO)
        .then()
            .statusCode(204);

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoStatoTreno storico = StoricoStatoTreno.find("trenoId", TRENO).firstResult();
            assertNotNull(storico, "i cambi di stato del convoglio restano anche dopo la rottamazione");
            assertEquals("attivo", storico.stato);
            assertEquals("fermo", storico.statoPrecedente);
        });
    }

    /**
     * Costruisce un transito come lo scriverebbe l'ingestione alla ricezione di un
     * ENTRATA, cosi' i test non hanno bisogno del broker MQTT.
     *
     * @param stazioneId Stazione attraversata.
     * @param trattaId   Tratta percorsa, oppure null.
     */
    private static Transito transitoDiProva(String stazioneId, String trattaId) {
        Transito transito = new Transito();
        transito.id = "TR-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        transito.treno = Treno.findById(TRENO);
        transito.stazione = Stazione.findById(stazioneId);
        transito.tratta = trattaId != null ? Tratta.findById(trattaId) : null;
        transito.tempoEntrata = Instant.now();
        transito.ritardoMinuti = 3;
        return transito;
    }
}
