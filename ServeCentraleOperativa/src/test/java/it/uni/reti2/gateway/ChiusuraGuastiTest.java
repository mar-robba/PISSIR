package it.uni.reti2.gateway;

import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.elaboration.EventoCentrale;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.UUID;

/**
 * Prova i due comandi con cui il tecnico lavora un guasto: manda la squadra e chiude
 * l'allarme.
 *
 * <p>La logica sta nel GestoreEventi e negli automi, e il confine della transazione si
 * chiude prima che parta qualsiasi notifica. Il comportamento visto da fuori pero' deve
 * essere lo stesso di prima, ed e' quello che si controlla qui: chi risponde cosa, e in
 * che stato resta la stazione.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChiusuraGuastiTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZIONE = "GUA_S_" + SUFFISSO;
    private static final String STAZIONE_SANA = "GUA_SANA_" + SUFFISSO;

    private CentraleDiProva centrale;

    private String idGuastoAperto;

    @BeforeAll
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
    }

    @AfterAll
    void spegni() throws Exception {
        centrale.close();
    }

    @Test
    @Order(1)
    public void preparaLaStazioneGuasta() throws Exception {
        Assertions.assertEquals(201, centrale.post("/api/stazioni", """
                { "id": "%s", "nome": "Guasti Alfa %s", "stato": "ONLINE",
                  "latitudine": 45.0, "longitudine": 9.0, "binari": 2 }
                """.formatted(STAZIONE, SUFFISSO), centrale.admin()).stato());
        Assertions.assertEquals(201, centrale.post("/api/stazioni",
                "{ \"id\": \"%s\", \"nome\": \"Guasti Beta\" }".formatted(STAZIONE_SANA), centrale.admin()).stato());

        // Il guasto lo apre il watchdog, per la strada vera: la stazione ha battuto, poi
        // tace da un'ora, e al tick si prende il guasto "heartbeat assente". Severita'
        // WARNING, perche' una stazione che tace non e' una stazione impercorribile.
        centrale.heartbeat("{\"stazioneId\":\"%s\",\"stato\":\"ONLINE\"}".formatted(STAZIONE));
        centrale.statoRete.getStazione(STAZIONE).ultimoHeartbeat = Instant.now().minusSeconds(3600);
        centrale.tick(EventoCentrale.Tipo.CONTROLLO_HEARTBEAT);

        Guasto guasto = centrale.statoRete.getGuastoApertoPerSorgente(STAZIONE, "sensore_offline");
        Assertions.assertNotNull(guasto, "il guasto doveva essere aperto");
        Assertions.assertNotNull(centrale.repository.trovaGuasto(guasto.id), "e scritto a database");
        Assertions.assertEquals("WARNING", guasto.severita);
        idGuastoAperto = guasto.id;
    }

    /** Una stazione che non esiste e' un 404; una senza guasti aperti e' un 409, non un 200 a vuoto. */
    @Test
    @Order(2)
    public void laSquadraNonSiMandaSuUnaStazioneSana() throws Exception {
        Assertions.assertEquals(404, centrale.post("/api/stazioni/GUA_INESISTENTE_" + SUFFISSO + "/manutenzione",
                null, centrale.tecnico()).stato());
        Assertions.assertEquals(409, centrale.post("/api/stazioni/" + STAZIONE_SANA + "/manutenzione",
                null, centrale.tecnico()).stato());
    }

    /** L'invio della squadra apre l'intervento e ci lascia la stazione (RF01.4.1). */
    @Test
    @Order(3)
    public void inviareLaSquadraMetteLaStazioneInManutenzione() throws Exception {
        Assertions.assertEquals(200, centrale.post("/api/stazioni/" + STAZIONE + "/manutenzione",
                null, centrale.tecnico()).stato());

        Stazione stazione = centrale.statoRete.getStazione(STAZIONE);
        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, stazione.stato,
                "l'intervento dura finché il guasto non viene chiuso");
    }

    /**
     * Il caso che rompeva l'intervento: la stazione e' in manutenzione ma il nodo di se'
     * continua a dichiararsi guasto, e rimanda l'alert a ogni treno che entra. Quella
     * ripetizione non deve riportare la stazione a GUASTA, altrimenti l'intervento
     * sparisce e con lui il motivo per cui i convogli sono fermi.
     */
    @Test
    @Order(4)
    public void unAlertRipetutoDalCampoNonCancellaLIntervento() {
        centrale.alert("""
                {"tipoEvento":"GUASTO","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"%s",
                 "severita":"CRITICAL","messaggio":"Sensore di binario B2 non invia keepalive","tipoGuasto":"sensore_offline"}
                """.formatted(STAZIONE));

        Stazione stazione = centrale.statoRete.getStazione(STAZIONE);
        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, stazione.stato,
                "un guasto nuovo e' un allarme in piu' su una stazione gia' chiusa, non un cambio di stato");
    }

    /**
     * Il seguito del test precedente: il campo dichiara rientrato il proprio guasto (i
     * sensori ricominciano a battere) mentre la squadra e' ancora sul posto. La stazione
     * non torna in servizio da sola: a dire che l'intervento e' finito e' il tecnico.
     */
    @Test
    @Order(5)
    public void ilRientroDichiaratoDalCampoNonChiudeLIntervento() {
        centrale.alert("""
                {"tipoEvento":"RESOLVED","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"%s",
                 "severita":"CRITICAL","messaggio":"Keepalive dei sensori di binario ripristinato","tipoGuasto":"sensore_offline"}
                """.formatted(STAZIONE));

        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, centrale.statoRete.getStazione(STAZIONE).stato,
                "i tecnici sono ancora sui binari");
        Assertions.assertNotNull(centrale.statoRete.getGuastoApertoPerSorgente(STAZIONE, "sensore_offline"),
                "l'allarme resta aperto: e' quello che tiene fermi i convogli fino a fine intervento");
    }

    /**
     * La chiusura dell'allarme e' l'unico punto in cui la stazione torna operativa
     * (RF01.4.2): il guasto risulta risolto e lo stato torna ONLINE senza aspettare un
     * heartbeat.
     */
    @Test
    @Order(6)
    public void chiudereLAllarmeRimetteLaStazioneInServizio() throws Exception {
        centrale.dimenticaUscite();
        CentraleDiProva.Risposta risposta = centrale.post("/api/allarmi/" + idGuastoAperto + "/risolvi", null, centrale.tecnico());
        Assertions.assertEquals(200, risposta.stato());
        Assertions.assertTrue(risposta.json().getAsJsonObject().get("risolto").getAsBoolean());

        Stazione stazione = centrale.statoRete.getStazione(STAZIONE);
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, stazione.stato,
                "chiuso l'ultimo guasto la stazione torna in servizio");
        Assertions.assertNotNull(stazione.ultimoHeartbeat,
                "il cronometro del battito riparte, altrimenti una stazione ancora spenta "
                        + "resterebbe operativa per sempre");
        // Al campo partono lo stato nuovo (i treni trattenuti ripartono) e il RESOLVED
        Assertions.assertTrue(centrale.alertPubblicati().get(0).contains("\"tipoEvento\":\"STATO_STAZIONE\""));
        Assertions.assertTrue(centrale.alertPubblicati().get(0).contains("\"stato\":\"ONLINE\""));
        Assertions.assertTrue(centrale.alertPubblicati().get(1).contains("\"tipoEvento\":\"RESOLVED\""));
        Assertions.assertTrue(centrale.repository.trovaGuasto(idGuastoAperto).risolto);
    }

    /** Un identificativo che non esiste e' un 404, non un 500. */
    @Test
    @Order(7)
    public void chiudereUnAllarmeInesistenteE404() throws Exception {
        Assertions.assertEquals(404, centrale.post("/api/allarmi/alert-non-esiste-" + SUFFISSO + "/risolvi",
                null, centrale.tecnico()).stato());
    }
}
