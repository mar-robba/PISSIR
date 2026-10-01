package it.uni.reti2.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.uni.reti2.CentraleDiProva;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

/**
 * Test del vincolo sulle tratte negli itinerari.
 *
 * <p>Un itinerario puo' usare solo collegamenti che in rete esistono davvero: il gateway
 * non crea al volo la Tratta mancante, se no spostando o aggiungendo una stazione si
 * inventerebbero archi fisici che nessuno ha mai registrato.</p>
 *
 * <p>Lo scenario e' la rete A -> B (una tratta sola) piu' una terza stazione C isolata:
 * tutto cio' che passa da C deve essere rifiutato con 404.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ItinerariTratteTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "IT_A_" + SUFFISSO;
    private static final String STAZ_B = "IT_B_" + SUFFISSO;
    private static final String STAZ_C = "IT_C_" + SUFFISSO;
    private static final String TRATTA_AB = "IT_T_AB_" + SUFFISSO;
    private static final String ITINERARIO = "IT_PERCORSO_" + SUFFISSO;
    private static final String ITINERARIO_CON_TRENO = "IT_PERCORSO_TRENO_" + SUFFISSO;
    private static final String TRENO = "IT_TRN_" + SUFFISSO;

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
        Assertions.assertEquals(201, centrale.post("/api/stazioni", corpo, centrale.admin()).stato());
    }

    /** L'itinerario con quell'id nell'elenco di GET /api/tratte, o null. */
    private JsonObject itinerario(String id) throws Exception {
        CentraleDiProva.Risposta risposta = centrale.get("/api/tratte", centrale.admin());
        Assertions.assertEquals(200, risposta.stato());
        for (JsonElement elemento : risposta.json().getAsJsonArray()) {
            if (id.equals(elemento.getAsJsonObject().get("id").getAsString())) {
                return elemento.getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    @Order(1)
    public void preparaLaRete() throws Exception {
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
        Assertions.assertEquals(201, centrale.post("/api/tratte-elementari", corpo, centrale.admin()).stato());
    }

    @Test
    @Order(2)
    public void itinerarioSuTrattaEsistenteVieneCreato() throws Exception {
        String corpo = """
                { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B);
        CentraleDiProva.Risposta risposta = centrale.post("/api/tratte", corpo, centrale.admin());
        Assertions.assertEquals(201, risposta.stato());
        Assertions.assertEquals(2, risposta.json().getAsJsonObject().getAsJsonArray("stazioni").size());
    }

    @Test
    @Order(3)
    public void creazioneRifiutataSeLaTrattaNonEsiste() throws Exception {
        String corpo = """
                { "id": "%s_KO", "stazioni": ["%s", "%s"], "travelTimes": [15] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_C);
        CentraleDiProva.Risposta risposta = centrale.post("/api/tratte", corpo, centrale.admin());
        Assertions.assertEquals(404, risposta.stato());
        JsonObject errore = risposta.json().getAsJsonObject();
        Assertions.assertTrue(errore.get("errore").getAsString().contains("Manca la tratta"));
        JsonObject coppia = errore.getAsJsonArray("coppieMancanti").get(0).getAsJsonObject();
        Assertions.assertEquals(STAZ_A, coppia.get("partenzaId").getAsString());
        Assertions.assertEquals(STAZ_C, coppia.get("arrivoId").getAsString());

        // L'itinerario non deve essere nato a meta'
        Assertions.assertNull(itinerario(ITINERARIO + "_KO"));
    }

    @Test
    @Order(4)
    public void aggiuntaDiStazioneSenzaTrattaVieneRifiutata() throws Exception {
        // A -> B esiste, B -> C no: la coda dell'itinerario e' quella che manca.
        String corpo = """
                { "stazioni": ["%s", "%s", "%s"], "travelTimes": [20, 15] }
                """.formatted(STAZ_A, STAZ_B, STAZ_C);
        CentraleDiProva.Risposta risposta = centrale.put("/api/tratte/" + ITINERARIO, corpo, centrale.admin());
        Assertions.assertEquals(404, risposta.stato());
        JsonArray coppie = risposta.json().getAsJsonObject().getAsJsonArray("coppieMancanti");
        Assertions.assertEquals(1, coppie.size());
        Assertions.assertEquals(STAZ_B, coppie.get(0).getAsJsonObject().get("partenzaId").getAsString());
        Assertions.assertEquals(STAZ_C, coppie.get(0).getAsJsonObject().get("arrivoId").getAsString());
    }

    @Test
    @Order(5)
    public void cambioDiOrdineSenzaTrattaVieneRifiutato() throws Exception {
        // Scambiare A e B chiede il verso opposto, B -> A, che in rete non c'e':
        // la tratta e' un arco orientato e i due versi sono due righe distinte.
        String corpo = """
                { "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(STAZ_B, STAZ_A);
        CentraleDiProva.Risposta risposta = centrale.put("/api/tratte/" + ITINERARIO, corpo, centrale.admin());
        Assertions.assertEquals(404, risposta.stato());
        JsonObject coppia = risposta.json().getAsJsonObject().getAsJsonArray("coppieMancanti").get(0).getAsJsonObject();
        Assertions.assertEquals(STAZ_B, coppia.get("partenzaId").getAsString());
        Assertions.assertEquals(STAZ_A, coppia.get("arrivoId").getAsString());
    }

    @Test
    @Order(6)
    public void elencoTroppoCortoVieneRifiutato() throws Exception {
        // Un itinerario con una stazione sola non e' un aggiornamento da ignorare:
        // con un 200 il form darebbe per salvata una modifica mai applicata.
        String corpo = """
                { "stazioni": ["%s"], "travelTimes": [] }
                """.formatted(STAZ_A);
        CentraleDiProva.Risposta risposta = centrale.put("/api/tratte/" + ITINERARIO, corpo, centrale.admin());
        Assertions.assertEquals(400, risposta.stato());
        Assertions.assertTrue(risposta.json().getAsJsonObject().get("errore").getAsString().contains("almeno due stazioni"));
    }

    @Test
    @Order(7)
    public void ilRifiutoNonSmontaLItinerario() throws Exception {
        // E' il punto delicato: la PUT cancella le righe di Itinerario_Tratta prima di
        // ricomporle, e la transazione NON fa rollback quando si torna una risposta di
        // errore. Se la verifica non stesse prima della delete, dopo i rifiuti qui sopra
        // l'itinerario sarebbe rimasto senza nessuna stazione.
        JsonArray stazioni = itinerario(ITINERARIO).getAsJsonArray("stazioni");
        Assertions.assertEquals(2, stazioni.size());
        Assertions.assertEquals(STAZ_A, stazioni.get(0).getAsString());
        Assertions.assertEquals(STAZ_B, stazioni.get(1).getAsString());
    }

    @Test
    @Order(8)
    public void iTreniAssegnatiAllaCreazioneVengonoAvvisati() throws Exception {
        // Un treno agganciato a un itinerario appena creato deve ricaricarlo: senza
        // ITINERARIO_AGGIORNATO il suo digital twin continuava sul percorso vecchio mentre
        // la Centrale lo dava gia' su quello nuovo (la PUT dell'itinerario e quella del
        // treno l'avviso lo mandavano gia').
        Assertions.assertEquals(201, centrale.post("/api/treni", """
                { "id": "%s", "stato": "fermo" }
                """.formatted(TRENO), centrale.admin()).stato());
        centrale.dimenticaUscite();

        String corpo = """
                { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20], "treniIds": ["%s"] }
                """.formatted(ITINERARIO_CON_TRENO, STAZ_A, STAZ_B, TRENO);
        Assertions.assertEquals(201, centrale.post("/api/tratte", corpo, centrale.admin()).stato());

        Assertions.assertTrue(centrale.alertPubblicati().stream().anyMatch(alert ->
                alert.contains("ITINERARIO_AGGIORNATO") && alert.contains("\"target\":\"" + TRENO + "\"")));

        Assertions.assertEquals(204, centrale.delete("/api/tratte/" + ITINERARIO_CON_TRENO, centrale.admin()).stato());
        Assertions.assertEquals(204, centrale.delete("/api/treni/" + TRENO, centrale.admin()).stato());
    }

    @Test
    @Order(9)
    public void pulisci() throws Exception {
        Assertions.assertEquals(204, centrale.delete("/api/tratte/" + ITINERARIO, centrale.admin()).stato());
        Assertions.assertEquals(204, centrale.delete("/api/tratte-elementari/" + TRATTA_AB, centrale.admin()).stato());
        for (String id : new String[]{STAZ_A, STAZ_B, STAZ_C}) {
            Assertions.assertEquals(204, centrale.delete("/api/stazioni/" + id, centrale.admin()).stato());
        }
    }
}
