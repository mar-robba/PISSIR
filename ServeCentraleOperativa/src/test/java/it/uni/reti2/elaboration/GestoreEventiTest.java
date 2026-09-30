package it.uni.reti2.elaboration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Prova il gestore degli eventi della Centrale: per ogni evento, lo stato degli automi dopo
 * la mossa, le righe scritte sul database e i messaggi partiti verso il campo e la web app.
 *
 * <p>Ogni test si accende la sua Centrale con una piccola linea: stazioni A, B, C, tratte
 * A-B (10 minuti) e B-C (20), l'itinerario A-B-C e il convoglio T1 assegnato. I messaggi del
 * campo si consegnano a IngestionService come farebbe il client MQTT, il watchdog si fa
 * scattare a mano.</p>
 */
public class GestoreEventiTest {

    private CentraleDiProva centrale;

    @BeforeEach
    void preparaLaLinea() throws Exception {
        centrale = CentraleDiProva.avvia();
        String admin = centrale.admin();
        for (String stazione : new String[]{"A", "B", "C"}) {
            centrale.post("/api/stazioni", "{\"id\":\"" + stazione + "\",\"nome\":\"Stazione " + stazione + "\"}", admin);
        }
        centrale.post("/api/tratte-elementari", "{\"id\":\"AB\",\"stazionePartenzaId\":\"A\",\"stazioneArrivoId\":\"B\",\"tempoPercorrenzaMinuti\":10}", admin);
        centrale.post("/api/tratte-elementari", "{\"id\":\"BC\",\"stazionePartenzaId\":\"B\",\"stazioneArrivoId\":\"C\",\"tempoPercorrenzaMinuti\":20}", admin);
        centrale.post("/api/tratte", "{\"id\":\"IT\",\"stazioni\":[\"A\",\"B\",\"C\"]}", admin);
        centrale.post("/api/treni", "{\"id\":\"T1\",\"itinerario\":{\"id\":\"IT\"}}", admin);
        for (String stazione : new String[]{"A", "B", "C"}) {
            centrale.heartbeat("{\"stazioneId\":\"" + stazione + "\",\"stato\":\"ONLINE\"}");
        }
        centrale.dimenticaUscite();
    }

    @AfterEach
    void spegni() throws Exception {
        centrale.close();
    }

    private void telemetria(String stato, String stazioneCorrente, String prossima, double velocita) {
        centrale.telemetria("""
                {"trenoId":"T1","stato":"%s","latitudine":45.0,"longitudine":9.0,"velocita":%s,
                 "progressPercent":50,"passeggeri":10,"stazioneCorrente":%s,"prossimaStazione":"%s","direzione":"andata"}
                """.formatted(stato, velocita, stazioneCorrente == null ? "null" : "\"" + stazioneCorrente + "\"", prossima));
    }

    private JsonObject ultimoAlert() {
        List<String> alert = centrale.alertPubblicati();
        Assertions.assertFalse(alert.isEmpty(), "doveva partire almeno un alert");
        return JsonParser.parseString(alert.get(alert.size() - 1)).getAsJsonObject();
    }

    // ============================================================== heartbeat

    @Test
    void ilPrimoBattitoPortaLaStazioneOnlineELoStoricizza() throws Exception {
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, centrale.statoRete.getStazione("A").stato);
        // una riga per stazione: da OFFLINE (lo stato di partenza) a ONLINE
        Assertions.assertEquals(3, centrale.conta(
                "SELECT COUNT(*) FROM Storico_Stato_Stazioni WHERE stato = 'ONLINE' AND stato_precedente = 'OFFLINE'"));
        // un secondo battito uguale non e' un cambiamento: niente riga nuova
        centrale.heartbeat("{\"stazioneId\":\"A\",\"stato\":\"ONLINE\"}");
        Assertions.assertEquals(3, centrale.conta("SELECT COUNT(*) FROM Storico_Stato_Stazioni"));
    }

    @Test
    void inManutenzioneIlBattitoNonCambiaLoStato() throws Exception {
        centrale.alert("""
                {"tipoEvento":"GUASTO","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"B","severita":"CRITICAL","messaggio":"scambio"}
                """);
        Assertions.assertEquals(StatiCanonici.STAZIONE_GUASTA, centrale.statoRete.getStazione("B").stato);
        Assertions.assertEquals(200, centrale.post("/api/stazioni/B/manutenzione", null, centrale.tecnico()).stato());

        centrale.heartbeat("{\"stazioneId\":\"B\",\"stato\":\"ONLINE\"}");

        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, centrale.statoRete.getStazione("B").stato,
                "la squadra e' sul posto: il nodo non decide lo stato");
    }

    @Test
    void unaStazioneSconosciutaSiIgnora() {
        GestoreEventi.Esito esito = centrale.gestore.gestisci(EventoCentrale.heartbeat(
                it.uni.reti2.ingestion.payload.HeartbeatMsg.da(JsonParser.parseString("{\"stazioneId\":\"Z\"}").getAsJsonObject()),
                new JsonObject()));
        Assertions.assertEquals(GestoreEventi.Esito.Risposta.IGNORATO, esito.risposta());
        Assertions.assertTrue(centrale.eventiWeb.isEmpty());
    }

    // ============================================================== guasti

    @Test
    void unAlertRipetutoNonApreUnSecondoGuasto() throws Exception {
        String alert = """
                {"tipoEvento":"GUASTO","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"C","severita":"CRITICAL","messaggio":"%s"}
                """;
        centrale.alert(alert.formatted("primo"));
        centrale.alert(alert.formatted("secondo"));

        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM Guasti_Pervenuti_da_treni_o_Staz"));
        Assertions.assertEquals("secondo", centrale.righe("SELECT messaggio FROM Guasti_Pervenuti_da_treni_o_Staz").get(0)[0]);
        Assertions.assertTrue(centrale.alertPubblicati().isEmpty(),
                "il guasto l'ha gia' pubblicato il nodo: la Centrale non lo ripete");
    }

    @Test
    void lEcoDellaCentraleNonSiRiascolta() throws Exception {
        centrale.alert("""
                {"tipoEvento":"GUASTO","origine":"CENTRALE","sorgenteTipo":"STAZIONE","sorgenteId":"C","severita":"CRITICAL","messaggio":"eco"}
                """);
        Assertions.assertEquals(0, centrale.conta("SELECT COUNT(*) FROM Guasti_Pervenuti_da_treni_o_Staz"));
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, centrale.statoRete.getStazione("C").stato);
    }

    @Test
    void unConvoglioRottoInStazioneChiudeLaStazioneELaRisoluzioneLaRiapre() throws Exception {
        telemetria("FERMO", "B", "C", 0);
        centrale.alert("""
                {"tipoEvento":"GUASTO","origine":"TRENO","sorgenteTipo":"TRENO","sorgenteId":"T1","severita":"CRITICAL","messaggio":"freni"}
                """);

        // tre automi, tre mosse
        Assertions.assertEquals("rotto", centrale.statoRete.getTreno("T1").stato);
        Assertions.assertEquals("rotto", centrale.repository.trovaTreno("T1").stato);
        Assertions.assertEquals(StatiCanonici.STAZIONE_GUASTA, centrale.statoRete.getStazione("B").stato);
        Guasto derivato = centrale.statoRete.getGuastoApertoPerSorgente("B", AutomaGuasto.TIPO_CONVOGLIO_IN_STAZIONE);
        Assertions.assertNotNull(derivato, "la stazione riceve il guasto dedotto");
        Assertions.assertEquals("GUASTO", ultimoAlert().get("tipoEvento").getAsString(),
                "il guasto dedotto il campo non lo conosce: la Centrale lo pubblica");
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM eventi_stazioni WHERE tipoevento = 'CONVOGLIO_GUASTO'"));

        Guasto delTreno = centrale.statoRete.getGuastoApertoPerSorgente("T1", "treno_fermo");
        centrale.dimenticaUscite();
        Assertions.assertEquals(200, centrale.post("/api/allarmi/" + delTreno.id + "/risolvi", null, centrale.tecnico()).stato());

        Assertions.assertEquals("fermo", centrale.statoRete.getTreno("T1").stato);
        Assertions.assertEquals("fermo", centrale.repository.trovaTreno("T1").stato);
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, centrale.statoRete.getStazione("B").stato);
        Assertions.assertTrue(centrale.repository.trovaGuasto(derivato.id).risolto, "il guasto derivato si chiude con il treno");
        List<String> alert = centrale.alertPubblicati();
        Assertions.assertEquals(3, alert.size(), alert.toString());
        Assertions.assertTrue(alert.get(0).contains("RESOLVED") && alert.get(0).contains(derivato.id));
        Assertions.assertTrue(alert.get(1).contains("STATO_STAZIONE") && alert.get(1).contains("\"stato\":\"ONLINE\""));
        Assertions.assertTrue(alert.get(2).contains("RESOLVED") && alert.get(2).contains(delTreno.id));
    }

    @Test
    void ilRientroDalCampoChiudeIlGuastoERiapreLaStazione() throws Exception {
        centrale.alert("""
                {"tipoEvento":"GUASTO","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"C","severita":"CRITICAL","messaggio":"binario"}
                """);
        centrale.dimenticaUscite();
        centrale.alert("""
                {"tipoEvento":"RESOLVED","origine":"STAZIONE","sorgenteTipo":"STAZIONE","sorgenteId":"C"}
                """);
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, centrale.statoRete.getStazione("C").stato);
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM Guasti_Pervenuti_da_treni_o_Staz WHERE Stato_RisoltoONO = TRUE"));
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM Storico_Guasti WHERE Stato_RisoltoONO = TRUE AND ts_chiusura IS NOT NULL"));
        // al campo si dice solo lo stato nuovo: il RESOLVED l'ha gia' pubblicato il nodo
        Assertions.assertEquals(1, centrale.alertPubblicati().size());
        Assertions.assertEquals("STATO_STAZIONE", ultimoAlert().get("tipoEvento").getAsString());
    }

    // ============================================================== watchdog

    @Test
    void unaStazioneCheTaceVaOfflineEQuandoTornaSiChiudeIlGuasto() throws Exception {
        centrale.statoRete.getStazione("C").ultimoHeartbeat = Instant.now().minusSeconds(3600);
        centrale.tick(EventoCentrale.Tipo.CONTROLLO_HEARTBEAT);

        Assertions.assertEquals(StatiCanonici.STAZIONE_OFFLINE, centrale.statoRete.getStazione("C").stato);
        Guasto guasto = centrale.statoRete.getGuastoApertoPerSorgente("C", "sensore_offline");
        Assertions.assertNotNull(guasto);
        Assertions.assertEquals(Guasto.ORIGINE_DEDOTTO_CENTRALE, guasto.origine);
        Assertions.assertEquals("GUASTO", ultimoAlert().get("tipoEvento").getAsString(), "fail-stop: lo si dice ai treni");
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM eventi_stazioni WHERE tipoevento = 'HEARTBEAT_LOST'"));

        // un secondo giro non apre un secondo guasto
        centrale.tick(EventoCentrale.Tipo.CONTROLLO_HEARTBEAT);
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM Guasti_Pervenuti_da_treni_o_Staz"));

        centrale.dimenticaUscite();
        centrale.heartbeat("{\"stazioneId\":\"C\",\"stato\":\"ONLINE\"}");
        Assertions.assertEquals(StatiCanonici.STAZIONE_ONLINE, centrale.statoRete.getStazione("C").stato);
        Assertions.assertTrue(centrale.repository.trovaGuasto(guasto.id).risolto);
        List<String> alert = centrale.alertPubblicati();
        Assertions.assertTrue(alert.get(0).contains("RESOLVED"), alert.toString());
        Assertions.assertTrue(alert.get(1).contains("STATO_STAZIONE"), alert.toString());
    }

    @Test
    void unTrenoFermoFuoriStazioneRiceveUnGuastoCheSiChiudeQuandoRiparte() throws Exception {
        centrale.gestore.trenoFermoTimeoutSecondi = 0;
        telemetria("IN_VIAGGIO", null, "B", 0);

        centrale.tick(EventoCentrale.Tipo.CONTROLLO_TRENI_FERMI);
        Assertions.assertNull(centrale.statoRete.getGuastoApertoPerSorgente("T1", "treno_fermo"),
                "al primo giro parte solo l'orologio");
        Assertions.assertNotNull(centrale.treni.fermoDa("T1"));

        centrale.tick(EventoCentrale.Tipo.CONTROLLO_TRENI_FERMI);
        Guasto guasto = centrale.statoRete.getGuastoApertoPerSorgente("T1", "treno_fermo");
        Assertions.assertNotNull(guasto, "scaduto T_fer il guasto si apre");

        telemetria("IN_VIAGGIO", null, "B", 90);
        Assertions.assertTrue(centrale.repository.trovaGuasto(guasto.id).risolto, "ripartito, il guasto dedotto si chiude");
        Assertions.assertTrue(ultimoAlert().toString().contains("RESOLVED"));
    }

    // ============================================================== passaggi

    @Test
    void iPassaggiMisuranoIlRitardoEScrivonoITransiti() throws Exception {
        telemetria("FERMO", "A", "B", 0);
        centrale.transito("{\"trenoId\":\"T1\",\"stazioneId\":\"A\",\"tipo\":\"USCITA\",\"timestamp\":\"2030-01-01T10:00:00Z\"}");
        // la tratta dura 10 minuti di orario = 6 secondi reali; arriva 12 secondi dopo il previsto
        centrale.transito("{\"trenoId\":\"T1\",\"stazioneId\":\"B\",\"tipo\":\"ENTRATA\",\"timestamp\":\"2030-01-01T10:00:18Z\"}");

        Assertions.assertEquals(20, centrale.statoRete.getTreno("T1").ritardo);
        JsonObject ritardo = ultimoAlert();
        Assertions.assertEquals("RITARDO", ritardo.get("tipoEvento").getAsString());
        Assertions.assertEquals("T1", ritardo.get("target").getAsString());
        Assertions.assertEquals(20, ritardo.get("variazioneMinuti").getAsInt());

        // uscita da A senza entrata (partenza) e ingresso in B: due righe vive, la seconda aperta
        Assertions.assertEquals(2, centrale.conta("SELECT COUNT(*) FROM Transiti"));
        Assertions.assertEquals(1, centrale.conta("SELECT COUNT(*) FROM Transiti WHERE tempoUscita IS NULL AND ritardoMinuti = 20"));
        Assertions.assertEquals("fermo", centrale.statoRete.getTreno("T1").stato, "l'ingresso ferma il convoglio");
        Assertions.assertEquals(1, centrale.statoRete.getStazione("B").treniInStazione);
        Assertions.assertEquals("AB", centrale.repository.trovaTreno("T1").posizioneAttualeTratta.id);

        centrale.transito("{\"trenoId\":\"T1\",\"stazioneId\":\"B\",\"tipo\":\"USCITA\",\"timestamp\":\"2030-01-01T10:00:20Z\"}");
        Assertions.assertEquals(0, centrale.conta("SELECT COUNT(*) FROM Transiti WHERE tempoUscita IS NULL"), "l'uscita chiude il transito");
        Assertions.assertEquals(3, centrale.conta("SELECT COUNT(*) FROM Storico_Transiti"), "una riga di storico per ogni evento");
        Assertions.assertEquals("BC", centrale.repository.trovaTreno("T1").posizioneAttualeTratta.id);
    }

    // ============================================================== comandi

    @Test
    void laSoppressioneVoleIlTrenoFermoInStazione() throws Exception {
        telemetria("IN_VIAGGIO", null, "B", 80);
        CentraleDiProva.Risposta rifiuto = centrale.post("/api/treni/T1/sopprimi", null, centrale.tecnico());
        Assertions.assertEquals(400, rifiuto.stato());
        Assertions.assertTrue(centrale.alertPubblicati().isEmpty(), "rifiutato dalla guardia: nessuna azione");

        telemetria("FERMO", "B", "C", 0);
        Assertions.assertEquals(200, centrale.post("/api/treni/T1/sopprimi", null, centrale.tecnico()).stato());
        Assertions.assertEquals("in manutenzione", centrale.statoRete.getTreno("T1").stato);
        Assertions.assertEquals("in manutenzione", centrale.repository.trovaTreno("T1").stato);
        Assertions.assertEquals("STOP", ultimoAlert().get("tipoEvento").getAsString());
    }

    @Test
    void loStatoDellaStazioneDecisoDallOperatoreArrivaAiTreni() throws Exception {
        Assertions.assertEquals(200, centrale.put("/api/stazioni/B", "{\"stato\":\"MANUTENZIONE\"}", centrale.admin()).stato());
        Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, centrale.statoRete.getStazione("B").stato);
        JsonObject alert = ultimoAlert();
        Assertions.assertEquals("STATO_STAZIONE", alert.get("tipoEvento").getAsString());
        Assertions.assertEquals("CRITICAL", alert.get("severita").getAsString());
        Assertions.assertTrue(centrale.eventiWeb.get(0).contains("\"status\":\"manutenzione\""));
    }

    // ============================================================== validazione

    @Test
    void laValidazioneDegliIdRispondeSulTopicDelNodo() {
        centrale.validatoreTreni.interrogaIlDPerValidazione("{\"trenoId\":\"T1\"}");
        centrale.validatoreStazioni.interrogaIlDbPerValidazione("{\"stazioneId\":\"Z\"}");
        Assertions.assertEquals("railway/train/T1/validation-response", centrale.pubblicati.get(0)[0]);
        Assertions.assertTrue(centrale.pubblicati.get(0)[1].contains("\"ESISTE\":true"));
        Assertions.assertEquals("railway/station/Z/validation-response", centrale.pubblicati.get(1)[0]);
        Assertions.assertTrue(centrale.pubblicati.get(1)[1].contains("\"esisteNelDb\":false"));
    }
}
