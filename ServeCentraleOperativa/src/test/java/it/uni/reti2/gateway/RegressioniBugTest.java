package it.uni.reti2.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.elaboration.EventoCentrale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RegressioniBugTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "REG_A_" + SUFFISSO;
    private static final String STAZ_B = "REG_B_" + SUFFISSO;
    private static final String TRATTA_AB = "REG_T_AB_" + SUFFISSO;
    private static final String TRATTA_BA = "REG_T_BA_" + SUFFISSO;
    private static final String ITINERARIO = "REG_IT_" + SUFFISSO;
    private static final String TRENO = "REG_TRN_" + SUFFISSO;

    private CentraleDiProva centrale;

    @BeforeAll
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
    }

    @AfterAll
    void spegni() throws Exception {
        centrale.close();
    }

    private void creaStazione(String id, String nome) throws Exception {
        assertEquals(201, centrale.post("/api/stazioni", """
                { "id": "%s", "nome": "%s", "stato": "ONLINE",
                  "latitudine": 45.0, "longitudine": 9.0, "binari": 2 }
                """.formatted(id, nome), centrale.admin()).stato());
    }

    private void creaTrattaElementare(String id, String partenza, String arrivo) throws Exception {
        assertEquals(201, centrale.post("/api/tratte-elementari", """
                { "id": "%s", "stazionePartenzaId": "%s", "stazioneArrivoId": "%s",
                  "tempoPercorrenzaMinuti": 20 }
                """.formatted(id, partenza, arrivo), centrale.admin()).stato());
    }

    /** I treni assegnati all'itinerario secondo GET /api/tratte. */
    private JsonArray treniAssegnati() throws Exception {
        for (JsonElement elemento : centrale.get("/api/tratte", centrale.admin()).json().getAsJsonArray()) {
            if (ITINERARIO.equals(elemento.getAsJsonObject().get("id").getAsString())) {
                return elemento.getAsJsonObject().getAsJsonArray("treniIds");
            }
        }
        throw new AssertionError("itinerario " + ITINERARIO + " non trovato");
    }

    @Test
    @Order(1)
    public void preparaLaRete() throws Exception {
        creaStazione(STAZ_A, "Regressione Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Regressione Bravo " + SUFFISSO);
        creaTrattaElementare(TRATTA_AB, STAZ_A, STAZ_B);
        creaTrattaElementare(TRATTA_BA, STAZ_B, STAZ_A);

        assertEquals(201, centrale.post("/api/tratte", """
                { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B), centrale.admin()).stato());

        // Il convoglio viene assegnato dalla pagina Amministrazione (form Treni), cioe'
        // da una schermata diversa dall'editor degli itinerari: e' il punto di partenza
        // dello scenario di A06.
        assertEquals(201, centrale.post("/api/treni", """
                { "id": "%s", "stato": "fermo", "itinerario": { "id": "%s" } }
                """.formatted(TRENO, ITINERARIO), centrale.admin()).stato());
    }

    @Test
    @Order(2)
    public void modificareLeStazioniNonSganciaITreniAssegnati() throws Exception {
        // A06: l'editor degli itinerari cambia solo le stazioni e NON manda "treniIds",
        // perche' quelle assegnazioni non le gestisce. Prima rimandava indietro la lista
        // scaricata all'avvio della pagina e il convoglio assegnato poco prima veniva
        // sganciato senza che nessuno lo avesse chiesto.
        assertEquals(200, centrale.put("/api/tratte/" + ITINERARIO, """
                { "stazioni": ["%s", "%s"], "travelTimes": [25] }
                """.formatted(STAZ_A, STAZ_B), centrale.admin()).stato());

        assertTrue(treniAssegnati().toString().contains("\"" + TRENO + "\""));
    }

    @Test
    @Order(3)
    public void elencoDiTreniVuotoSganciaDavvero() throws Exception {
        // Rovescio della medaglia: se "treniIds" c'e' ed e' vuoto, allora l'intenzione e'
        // proprio quella di togliere tutte le assegnazioni, e va rispettata.
        assertEquals(200, centrale.put("/api/tratte/" + ITINERARIO, "{ \"treniIds\": [] }", centrale.admin()).stato());

        assertEquals(0, treniAssegnati().size());
        // Il convoglio sganciato riceve ITINERARIO_AGGIORNATO, altrimenti continuerebbe
        // a girare sulla tratta vecchia
        assertTrue(centrale.alertPubblicati().stream().anyMatch(alert ->
                alert.contains("ITINERARIO_AGGIORNATO") && alert.contains("\"target\":\"" + TRENO + "\"")));
    }

    @Test
    @Order(4)
    public void itinerarioCheRipassaSullaStessaTrattaVieneRifiutato() throws Exception {
        // A20: la chiave di Itinerario_Tratta e' (id_itinerario, id_Tratta) e l'ordine non
        // ne fa parte, quindi A -> B -> A -> B chiederebbe due righe con la stessa chiave.
        // Entrambi gli archi esistono in rete: l'unico problema e' la ripetizione.
        CentraleDiProva.Risposta risposta = centrale.post("/api/tratte", """
                { "id": "%s_BIS", "stazioni": ["%s", "%s", "%s", "%s"],
                  "travelTimes": [20, 20, 20] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B, STAZ_A, STAZ_B), centrale.admin());
        assertEquals(400, risposta.stato());
        assertTrue(risposta.json().getAsJsonObject().get("errore").getAsString().contains("due volte la stessa tratta"));

        // E soprattutto non deve essere nato niente a meta'
        assertTrue(centrale.repository.trovaItinerario(ITINERARIO + "_BIS") == null);
    }

    @Test
    @Order(5)
    public void trattaUsataComePosizioneDiUnTrenoNonSiElimina() throws Exception {
        // A12: la posizione del convoglio la scrive il gestore degli eventi al passaggio,
        // qui la si imposta a mano perche' non esiste una REST che lo faccia.
        centrale.db.inTransazione(() -> centrale.repository.aggiornaPosizioneTreno(TRENO, TRATTA_BA));

        CentraleDiProva.Risposta rifiuto = centrale.delete("/api/tratte-elementari/" + TRATTA_BA, centrale.admin());
        assertEquals(409, rifiuto.stato());
        assertTrue(rifiuto.json().getAsJsonObject().get("errore").getAsString().contains("posizione corrente"));

        // Liberata la tratta, l'eliminazione deve passare: il rifiuto era sul dato, non sul comando.
        centrale.db.inTransazione(() -> centrale.repository.aggiornaPosizioneTreno(TRENO, null));

        assertEquals(204, centrale.delete("/api/tratte-elementari/" + TRATTA_BA, centrale.admin()).stato());
    }

    @Test
    @Order(6)
    public void loSnapshotPortaKpiTreniEStazioni() {
        // A03: lo snapshot periodico conteneva i soli KPI, quindi anche leggendolo il
        // browser non avrebbe potuto riallineare niente (RF02.6.3 parla di "fotografia
        // completa dello stato corrente"). Qui si fa scattare l'evento vero e si controlla
        // che quello che esce sulla WebSocket porti i campi che il mappatore del frontend
        // si aspetta: sono oggetti con relazioni, ed e' il punto dove una serializzazione
        // puo' rompersi.
        centrale.dimenticaUscite();
        centrale.tick(EventoCentrale.Tipo.FOTOGRAFIA);
        Assertions.assertEquals(1, centrale.eventiWeb.size());
        JsonObject evento = JsonParser.parseString(centrale.eventiWeb.get(0)).getAsJsonObject();
        assertEquals("SNAPSHOT", evento.get("eventType").getAsString());

        JsonObject kpi = evento.getAsJsonObject("dashboard");
        assertTrue(kpi.has("trainsInMotion"), "i KPI devono contenere trainsInMotion");
        assertTrue(kpi.has("stationsFaulty"), "i KPI devono contenere stationsFaulty");
        assertTrue(kpi.has("avgDelay"), "i KPI devono contenere avgDelay");

        JsonObject trenoDiProva = null;
        for (JsonElement t : evento.getAsJsonArray("treni")) {
            if (TRENO.equals(t.getAsJsonObject().get("id").getAsString())) trenoDiProva = t.getAsJsonObject();
        }
        assertNotNull(trenoDiProva, "il convoglio di prova deve comparire nello snapshot");
        assertTrue(trenoDiProva.has("stato"), "serve lo stato per la mappa");
        assertTrue(trenoDiProva.has("progresso"), "serve il progresso per la barra di avanzamento");

        JsonObject stazioneDiProva = null;
        for (JsonElement s : evento.getAsJsonArray("stazioni")) {
            if (STAZ_A.equals(s.getAsJsonObject().get("id").getAsString())) stazioneDiProva = s.getAsJsonObject();
        }
        assertNotNull(stazioneDiProva, "la stazione di prova deve comparire nello snapshot");
        assertTrue(stazioneDiProva.has("stato"), "serve lo stato operativo della stazione");
    }

    @Test
    @Order(7)
    public void pulisci() throws Exception {
        assertEquals(204, centrale.delete("/api/treni/" + TRENO, centrale.admin()).stato());
        assertEquals(204, centrale.delete("/api/tratte/" + ITINERARIO, centrale.admin()).stato());
        assertEquals(204, centrale.delete("/api/tratte-elementari/" + TRATTA_AB, centrale.admin()).stato());
        for (String id : new String[]{STAZ_A, STAZ_B}) {
            assertEquals(204, centrale.delete("/api/stazioni/" + id, centrale.admin()).stato());
        }
    }
}
