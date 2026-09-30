package it.uni.reti2.gateway;

import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Treno;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    @Test
    @Order(1)
    public void preparaLaRete() throws Exception {
        creaStazione(STAZ_A, "Memoria Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Memoria Bravo " + SUFFISSO);
        creaStazione(STAZ_C, "Memoria Charlie " + SUFFISSO);
        creaTrattaElementare(TRATTA_AB, STAZ_A, STAZ_B);
        creaTrattaElementare(TRATTA_BC, STAZ_B, STAZ_C);

        assertEquals(201, centrale.post("/api/tratte", """
                { "id": "%s", "stazioni": ["%s", "%s"], "travelTimes": [20] }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B), centrale.admin()).stato());

        assertEquals(201, centrale.post("/api/treni", """
                { "id": "%s", "stato": "fermo", "itinerario": { "id": "%s" } }
                """.formatted(TRENO, ITINERARIO), centrale.admin()).stato());
    }

    @Test
    @Order(2)
    public void laTrattaPresenteSoloNelloStoricoSiPuoEliminare() throws Exception {
        // Un passaggio gia' avvenuto sulla tratta B -> C, come lo scriverebbe il gestore.
        Transito transito = transitoDiProva(STAZ_C, TRATTA_BC);
        StoricoTransito fotografia = StoricoTransito.fotografiaDi(transito);
        // Il nome della stazione e la descrizione della tratta finiscono dentro la riga:
        // sono loro a sostituire la chiave esterna.
        assertNotNull(fotografia.nomeStazione);
        assertTrue(fotografia.descrizioneTratta.contains("->"),
                "la riga deve portarsi dentro la descrizione della tratta");
        centrale.db.inTransazione(() -> centrale.repository.salvaStoricoTransito(transito));

        // Prima questa DELETE tornava 409 "Tratta presente nei transiti storici".
        assertEquals(204, centrale.delete("/api/tratte-elementari/" + TRATTA_BC, centrale.admin()).stato());

        // E il passaggio e' ancora li', con il percorso leggibile.
        List<Object[]> storici = centrale.righe("SELECT descrizione_tratta FROM Storico_Transiti WHERE id_Tratta = ?", TRATTA_BC);
        assertFalse(storici.isEmpty(), "il transito storico non deve sparire con la tratta");
        assertTrue(storici.get(0)[0].toString().contains("->"));
    }

    @Test
    @Order(3)
    public void eliminataLaStazioneLaSuaStoriaResta() throws Exception {
        // Un transito ancora aperto (tabella viva) e il cambio di stato della stazione.
        centrale.db.inTransazione(() -> {
            centrale.repository.salvaTransito(transitoDiProva(STAZ_C, null));
            Stazione stazione = centrale.repository.trovaStazione(STAZ_C);
            centrale.repository.salvaStoricoStatoStazione(stazione, "GUASTA", "ONLINE");
        });

        assertEquals(204, centrale.delete("/api/stazioni/" + STAZ_C, centrale.admin()).stato());

        assertEquals(0, centrale.conta("SELECT COUNT(*) FROM Transiti WHERE id_stazione = ?", STAZ_C),
                "i transiti VIVI vanno via con la stazione: sono stato corrente");
        assertTrue(centrale.conta("SELECT COUNT(*) FROM Storico_Transiti WHERE id_stazione = ?", STAZ_C) > 0,
                "i transiti STORICI restano: la stazione e' stata dismessa, non e' mai esistita");

        List<Object[]> statoStorico = centrale.righe(
                "SELECT stato, stato_precedente, nome FROM Storico_Stato_Stazioni WHERE id_stazione = ?", STAZ_C);
        assertFalse(statoStorico.isEmpty());
        assertEquals("GUASTA", statoStorico.get(0)[0]);
        assertEquals("ONLINE", statoStorico.get(0)[1]);
        // Il nome sopravvive alla riga di anagrafica: e' il motivo per cui lo si copia.
        assertTrue(statoStorico.get(0)[2].toString().startsWith("Memoria Charlie"));
    }

    @Test
    @Order(4)
    public void eliminatoIlConvoglioISuoiCambiDiStatoRestano() throws Exception {
        Treno treno = centrale.repository.trovaTreno(TRENO);
        treno.posizioneAttualeTratta = centrale.repository.trovaTratta(TRATTA_AB);
        treno.stato = "attivo";
        centrale.db.inTransazione(() -> centrale.repository.salvaStoricoStatoTreno(treno, "fermo"));
        List<Object[]> scritto = centrale.righe(
                "SELECT descrizione_posizione FROM Storico_Stato_Treni WHERE id_convoglio = ?", TRENO);
        assertTrue(scritto.get(0)[0].toString().contains("->"),
                "anche la posizione va scritta in chiaro, la tratta si puo' eliminare");

        assertEquals(204, centrale.delete("/api/treni/" + TRENO, centrale.admin()).stato());

        List<Object[]> storico = centrale.righe(
                "SELECT stato, stato_precedente FROM Storico_Stato_Treni WHERE id_convoglio = ?", TRENO);
        assertFalse(storico.isEmpty(), "i cambi di stato del convoglio restano anche dopo la rottamazione");
        assertEquals("attivo", storico.get(0)[0]);
        assertEquals("fermo", storico.get(0)[1]);
    }

    /**
     * Costruisce un transito come lo scriverebbe il gestore degli eventi alla ricezione di
     * un ENTRATA, cosi' i test non hanno bisogno del broker MQTT.
     *
     * @param stazioneId Stazione attraversata.
     * @param trattaId   Tratta percorsa, oppure null.
     */
    private Transito transitoDiProva(String stazioneId, String trattaId) {
        Transito transito = new Transito();
        transito.id = "TR-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        transito.treno = centrale.repository.trovaTreno(TRENO);
        transito.stazione = centrale.repository.trovaStazione(stazioneId);
        transito.tratta = trattaId != null ? centrale.repository.trovaTratta(trattaId) : null;
        transito.tempoEntrata = Instant.now();
        transito.ritardoMinuti = 3;
        return transito;
    }
}
