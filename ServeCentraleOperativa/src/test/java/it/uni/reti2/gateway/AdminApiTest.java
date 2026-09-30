package it.uni.reti2.gateway;

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
 * Test delle API di amministrazione.
 *
 * <p>Le richieste passano davvero da HTTP, dal filtro e da pac4j: il token lo firma la
 * {@link CentraleDiProva} con la sua chiave di prova, e porta l'utente e i ruoli di realm
 * che porterebbe quello di Keycloak. Prima, con Quarkus, l'identita' la iniettava
 * {@code @TestSecurity} e il controllo del token nei test era spento.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdminApiTest {

    private static final String STAZIONE_ID = "STAZ_TEST_" + UUID.randomUUID().toString().substring(0, 4);
    private static final String TRENO_ID = "TRENO_TEST_" + UUID.randomUUID().toString().substring(0, 4);

    private CentraleDiProva centrale;

    @BeforeAll
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
    }

    @AfterAll
    void spegni() throws Exception {
        centrale.close();
    }

    @Test
    @Order(0)
    public void testSenzaTokenVieneRifiutato() throws Exception {
        // Prova che gli endpoint non sono pubblici: senza token si prende un 401
        CentraleDiProva.Risposta risposta = centrale.get("/api/stazioni", null);
        Assertions.assertEquals(401, risposta.stato());
        Assertions.assertEquals("Autenticazione richiesta: effettuare il login",
                risposta.json().getAsJsonObject().get("errore").getAsString());
    }

    @Test
    @Order(1)
    public void testTecnicoNonPuoCreareStazioni() throws Exception {
        // Il tecnico legge e usa i comandi operativi, ma l'anagrafica non la tocca:
        // e' la separazione dei due ruoli che chiede il PDF, decisa dal ruolo di realm
        // che Keycloak scrive nel token.
        CentraleDiProva.Risposta risposta = centrale.post("/api/stazioni",
                "{\"id\": \"STAZ_VIETATA\", \"nome\": \"Non deve nascere\"}", centrale.tecnico());
        Assertions.assertEquals(403, risposta.stato());
        Assertions.assertNull(centrale.statoRete.getStazione("STAZ_VIETATA"), "la stazione non doveva nascere");
    }

    @Test
    @Order(2)
    public void testTecnicoPuoLeggere() throws Exception {
        Assertions.assertEquals(200, centrale.get("/api/stazioni", centrale.tecnico()).stato());
    }

    @Test
    @Order(3)
    public void testCreateStazione() throws Exception {
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

        CentraleDiProva.Risposta risposta = centrale.post("/api/stazioni", requestBody, centrale.admin());
        Assertions.assertEquals(201, risposta.stato());
        JsonObject stazione = risposta.json().getAsJsonObject();
        Assertions.assertEquals(STAZIONE_ID, stazione.get("id").getAsString());
        Assertions.assertEquals("Stazione Test", stazione.get("nome").getAsString());
    }

    @Test
    @Order(4)
    public void testGetStazioni() throws Exception {
        CentraleDiProva.Risposta risposta = centrale.get("/api/stazioni", centrale.admin());
        Assertions.assertEquals(200, risposta.stato());
        Assertions.assertTrue(risposta.corpo().contains(STAZIONE_ID));
    }

    @Test
    @Order(5)
    public void testDeleteStazione() throws Exception {
        Assertions.assertEquals(204, centrale.delete("/api/stazioni/" + STAZIONE_ID, centrale.admin()).stato());

        // Verifica che sia stata cancellata
        CentraleDiProva.Risposta risposta = centrale.get("/api/stazioni", centrale.admin());
        Assertions.assertEquals(200, risposta.stato());
        Assertions.assertFalse(risposta.corpo().contains(STAZIONE_ID));
    }

    @Test
    @Order(6)
    public void testCreateTreno() throws Exception {
        // "id" è il nome del convoglio digitato in interfaccia: è la chiave primaria.
        String requestBody = """
                {
                    "id": "%s",
                    "stato": "fermo"
                }
                """.formatted(TRENO_ID);

        CentraleDiProva.Risposta risposta = centrale.post("/api/treni", requestBody, centrale.admin());
        Assertions.assertEquals(201, risposta.stato());
        Assertions.assertEquals(TRENO_ID, risposta.json().getAsJsonObject().get("id").getAsString());
    }

    @Test
    @Order(7)
    public void testNomeConvoglioDuplicatoRifiutato() throws Exception {
        // Il nome è la chiave primaria: un secondo treno con lo stesso nome è un conflitto.
        CentraleDiProva.Risposta risposta = centrale.post("/api/treni",
                "{\"id\": \"%s\", \"stato\": \"fermo\"}".formatted(TRENO_ID), centrale.admin());
        Assertions.assertEquals(409, risposta.stato());
    }

    @Test
    @Order(8)
    public void testRinominaConvoglioRifiutata() throws Exception {
        // Il nome identifica il convoglio: la PUT non lo cambia, risponde 400.
        CentraleDiProva.Risposta risposta = centrale.put("/api/treni/" + TRENO_ID,
                "{\"id\": \"%s_RINOMINATO\", \"stato\": \"fermo\"}".formatted(TRENO_ID), centrale.admin());
        Assertions.assertEquals(400, risposta.stato());
    }

    @Test
    @Order(9)
    public void testUpdateTrenoSenzaCambiareNome() throws Exception {
        // La modifica di stato e itinerario resta permessa e non tocca la chiave.
        CentraleDiProva.Risposta risposta = centrale.put("/api/treni/" + TRENO_ID,
                "{\"stato\": \"attivo\"}", centrale.admin());
        Assertions.assertEquals(200, risposta.stato());
        JsonObject treno = risposta.json().getAsJsonObject();
        Assertions.assertEquals(TRENO_ID, treno.get("id").getAsString());
        Assertions.assertEquals("attivo", treno.get("stato").getAsString());
        // lo stato l'ha scritto l'automa: sul database e' lo stesso
        Assertions.assertEquals("attivo", centrale.repository.trovaTreno(TRENO_ID).stato);
    }

    @Test
    @Order(10)
    public void testUnoStatoFuoriVocabolarioSiRifiuta() throws Exception {
        // Prima arrivava al CHECK della colonna e diventava un 500; adesso e' la guardia
        // dell'automa a dire di no, con il motivo.
        CentraleDiProva.Risposta risposta = centrale.put("/api/treni/" + TRENO_ID,
                "{\"stato\": \"volante\"}", centrale.admin());
        Assertions.assertEquals(400, risposta.stato());
        Assertions.assertEquals("attivo", centrale.statoRete.getTreno(TRENO_ID).stato);
    }

    @Test
    @Order(11)
    public void testSopprimiTreno() throws Exception {
        // Il treno deve essere fermo in una stazione: lo si mette li' a mano in cache
        it.uni.reti2.entity.Treno treno = centrale.statoRete.getTreno(TRENO_ID);
        treno.stato = "fermo";
        treno.stazioneCorrente = STAZIONE_ID;

        // La soppressione e' uno dei tre comandi operativi concessi anche al tecnico.
        CentraleDiProva.Risposta risposta = centrale.post("/api/treni/" + TRENO_ID + "/sopprimi", null, centrale.tecnico());
        Assertions.assertEquals(200, risposta.stato());
        // Il valore canonico, lo stesso che va sul database: prima la cache diceva
        // "SOPPRESSO" e il database "in manutenzione".
        Assertions.assertEquals("in manutenzione", risposta.json().getAsJsonObject().get("stato").getAsString());
        Assertions.assertEquals("in manutenzione", centrale.repository.trovaTreno(TRENO_ID).stato);
        Assertions.assertTrue(centrale.alertPubblicati().get(centrale.alertPubblicati().size() - 1).contains("\"tipoEvento\":\"STOP\""));
    }

    @Test
    @Order(12)
    public void testDeleteTreno() throws Exception {
        Assertions.assertEquals(204, centrale.delete("/api/treni/" + TRENO_ID, centrale.admin()).stato());
        Assertions.assertNull(centrale.statoRete.getTreno(TRENO_ID));
    }

    @Test
    @Order(13)
    public void testProfiloUtenteCollegato() throws Exception {
        // /api/auth/me e' cio' che resta di AuthController: la web app lo chiama
        // appena ha il token per sapere chi e' entrato e quale ruolo mostrare.
        CentraleDiProva.Risposta risposta = centrale.get("/api/auth/me", centrale.admin());
        Assertions.assertEquals(200, risposta.stato());
        JsonObject profilo = risposta.json().getAsJsonObject();
        Assertions.assertEquals("MAT001", profilo.get("username").getAsString());
        Assertions.assertEquals("amministratore", profilo.get("role").getAsString());
    }
}
