package it.uni.reti2.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import it.uni.reti2.elaboration.GuastiService;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Prova i due comandi con cui il tecnico lavora un guasto: manda la squadra e chiude
 * l'allarme.
 *
 * <p>Sono i due endpoint che lo scorporo dell'ingestione ha spostato di piu': la logica
 * non sta piu' dentro il metodo REST ma in {@code GuastiService} e
 * {@code MonitoraggioStazioni}, e con lei si e' spostato anche il confine della
 * transazione, che adesso si chiude prima che parta qualsiasi notifica. Il comportamento
 * visto da fuori pero' deve essere lo stesso di prima, ed e' quello che si controlla
 * qui: chi risponde cosa, e in che stato resta la stazione.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChiusuraGuastiTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZIONE = "GUA_S_" + SUFFISSO;

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    GuastiService guasti;

    private static String idGuastoAperto;

    @Test
    @Order(1)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void preparaLaStazioneGuasta() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "nome": "Guasti Alfa %s", "stato": "ONLINE",
                      "latitudine": 45.0, "longitudine": 9.0, "binari": 2 }
                    """.formatted(STAZIONE, SUFFISSO))
        .when()
            .post("/api/stazioni")
        .then()
            .statusCode(201);

        // Il guasto lo apre il servizio, esattamente come farebbe il watchdog.
        Guasto guasto = guasti.apri(guasti.nuovoDedotto(
                "sensore_offline", "CRITICAL", "STAZIONE", STAZIONE,
                Guasto.MSG_HEARTBEAT_PERSO + " la stazione " + STAZIONE + " non invia heartbeat"), null);
        Assertions.assertNotNull(guasto, "il guasto doveva essere scritto a database");
        idGuastoAperto = guasto.id;
    }

    /** Senza guasti aperti la squadra non si manda: è un 409, non un 200 a vuoto. */
    @Test
    @Order(2)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void laSquadraNonSiMandaSuUnaStazioneSana() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/GUA_INESISTENTE_" + SUFFISSO + "/manutenzione")
        .then()
            .statusCode(404);
    }

    /** L'invio della squadra apre l'intervento e ci lascia la stazione (RF01.4.1). */
    @Test
    @Order(3)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void inviareLaSquadraMetteLaStazioneInManutenzione() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/" + STAZIONE + "/manutenzione")
        .then()
            .statusCode(200);

        Stazione stazione = statoRete.getStazione(STAZIONE);
        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, stazione.stato,
                "l'intervento dura finché il guasto non viene chiuso");
    }

    /**
     * La chiusura dell'allarme e' l'unico punto in cui la stazione torna operativa
     * (RF01.4.2): il guasto risulta risolto e lo stato torna ONLINE senza aspettare un
     * heartbeat.
     */
    @Test
    @Order(4)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void chiudereLAllarmeRimetteLaStazioneInServizio() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + idGuastoAperto + "/risolvi")
        .then()
            .statusCode(200)
            .body("risolto", org.hamcrest.CoreMatchers.is(true));

        Stazione stazione = statoRete.getStazione(STAZIONE);
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, stazione.stato,
                "chiuso l'ultimo guasto la stazione torna in servizio");
        Assertions.assertNotNull(stazione.ultimoHeartbeat,
                "il cronometro del battito riparte, altrimenti una stazione ancora spenta "
                        + "resterebbe operativa per sempre");
    }

    /** Un identificativo che non esiste e' un 404, non un 500. */
    @Test
    @Order(5)
    @TestSecurity(user = "mat003", roles = {"tecnico"})
    public void chiudereUnAllarmeInesistenteE404() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/alert-non-esiste-" + SUFFISSO + "/risolvi")
        .then()
            .statusCode(404);
    }
}
