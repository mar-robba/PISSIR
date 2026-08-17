package it.uni.reti2.eventi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StoricoStatoTratta;
import it.uni.reti2.entity.StoricoStatoTreno;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica dell'infrastruttura per gli <b>eventi domino</b>: un nodo cambia stato per colpa di
 * un evento di un altro nodo, lo dichiara, e la Centrale lo registra con dentro la causa.
 *
 * <p>Le due proprieta' che questi test fissano sono quelle da cui dipende tutto il resto:</p>
 * <ul>
 *   <li><b>la causa finisce a database</b>: la riga di Storico_Stato_Treni non dice solo che il
 *       convoglio si e' fermato, ma per colpa di chi e per quale catena;</li>
 *   <li><b>un nodo reagisce una volta sola per catena</b>: e' cio' che fa terminare le catene e
 *       che regge le ripetizioni del broker (MQTT consegna at-least-once, e una stazione guasta
 *       manda un alert per ogni treno che entra).</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EventiDominoTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "DOM_A_" + SUFFISSO;
    private static final String STAZ_B = "DOM_B_" + SUFFISSO;
    private static final String TRATTA = "DOM_T_" + SUFFISSO;
    private static final String ITINERARIO = "DOM_IT_" + SUFFISSO;
    private static final String TRENO = "DOM_TRN_" + SUFFISSO;
    private static final String CATENA = "DOM_CAT_" + SUFFISSO;
    /** Catena dell'avaria che occupa un arco della rete (RF02.1.2.2.2). */
    private static final String CATENA_TRATTA = "DOM_CATT_" + SUFFISSO;
    /** Catena usata per la chiusura delle conseguenze insieme alla causa. */
    private static final String CATENA_CONSEGUENZE = "DOM_CATC_" + SUFFISSO;
    /** Catena usata per verificare che il verso della chiusura sia uno solo. */
    private static final String CATENA_VERSO = "DOM_CATV_" + SUFFISSO;

    @Inject
    GestoreReazioni gestoreReazioni;

    @Inject
    RegistroCatene registroCatene;

    @Inject
    ObjectMapper mapper;

    /**
     * Costruisce il payload di una reazione come lo pubblica un convoglio bloccato.
     *
     * @param nuovoStato      Stato dichiarato dal nodo.
     * @param statoPrecedente Stato da cui proviene.
     * @param attiva          true se entra nella catena, false se ne esce.
     * @return Il JSON dell'evento derivato.
     */
    private String reazioneDelTreno(String nuovoStato, String statoPrecedente, boolean attiva) {
        return """
                { "tipoEvento": "REAZIONE", "sorgenteTipo": "TRENO", "sorgenteId": "%s",
                  "nuovoStato": "%s", "statoPrecedente": "%s",
                  "causaTipo": "STAZIONE", "causaId": "%s", "catenaId": "%s",
                  "attiva": %b, "motivo": "prova" }
                """.formatted(TRENO, nuovoStato, statoPrecedente, STAZ_B, CATENA, attiva);
    }

    private void applica(String payload) throws Exception {
        gestoreReazioni.applica(EventoDerivato.daJson(mapper.readTree(payload)));
    }

    /** Le righe di storico del convoglio, lette in una transazione come fanno gli altri test. */
    private List<StoricoStatoTreno> storicoDelTreno() {
        return QuarkusTransaction.requiringNew()
                .call(() -> StoricoStatoTreno.<StoricoStatoTreno>find("trenoId", TRENO).list());
    }

    /** Le righe di storico della percorribilita' dell'arco di prova. */
    private List<StoricoStatoTratta> storicoDellaTratta() {
        return QuarkusTransaction.requiringNew()
                .call(() -> StoricoStatoTratta.<StoricoStatoTratta>find("trattaId", TRATTA).list());
    }

    /**
     * Un guasto gia' aperto appartenente a una catena, scritto direttamente a database come
     * farebbe l'ingestione: qui interessa solo il comportamento della chiusura.
     *
     * @param id           Identificativo del guasto.
     * @param sorgenteTipo TRENO o TRATTA.
     * @param sorgenteId   Sorgente del guasto.
     * @param apertura     Istante di apertura: e' quello che dice chi e' causa e chi conseguenza.
     * @param catena       Catena a cui il guasto appartiene.
     */
    private Guasto guastoDellaCatena(String id, String sorgenteTipo, String sorgenteId,
                                     Instant apertura, String catena) {
        Guasto guasto = new Guasto();
        guasto.id = id;
        guasto.tipo = "TRENO".equals(sorgenteTipo) ? "treno_fermo" : "tratta_impercorribile";
        guasto.severita = "CRITICAL";
        guasto.sorgenteTipo = sorgenteTipo;
        guasto.sorgenteId = sorgenteId;
        guasto.messaggio = "prova catena";
        guasto.timestamp = apertura;
        guasto.risolto = false;
        guasto.catenaId = catena;
        return guasto;
    }

    @Test
    @Order(1)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void preparaLaRete() {
        for (String[] stazione : new String[][]{{STAZ_A, "Domino Alfa"}, {STAZ_B, "Domino Bravo"}}) {
            given()
                .contentType(ContentType.JSON)
                .body("""
                        { "id": "%s", "nome": "%s %s", "stato": "ONLINE",
                          "latitudine": 45.0, "longitudine": 9.0, "binari": 2 }
                        """.formatted(stazione[0], stazione[1], SUFFISSO))
            .when()
                .post("/api/stazioni")
            .then()
                .statusCode(201);
        }

        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "id": "%s", "stazionePartenzaId": "%s", "stazioneArrivoId": "%s",
                      "tempoPercorrenzaMinuti": 20 }
                    """.formatted(TRATTA, STAZ_A, STAZ_B))
        .when()
            .post("/api/tratte-elementari")
        .then()
            .statusCode(201);

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
                    { "id": "%s", "stato": "attivo", "itinerario": { "id": "%s" } }
                    """.formatted(TRENO, ITINERARIO))
        .when()
            .post("/api/treni")
        .then()
            .statusCode(201);
    }

    /**
     * La reazione del convoglio lascia una riga di storico che porta con se' la causa: senza
     * queste tre colonne un convoglio trattenuto perche' la stazione davanti a lui non e'
     * percorribile sarebbe indistinguibile da uno fermo in sosta regolare.
     */
    @Test
    @Order(2)
    public void laReazioneRegistraAncheLaCausa() throws Exception {
        applica(reazioneDelTreno("BLOCCATO_GUASTO_STAZIONE", "IN_VIAGGIO", true));

        List<StoricoStatoTreno> righe = storicoDelTreno();
        assertEquals(1, righe.size(), "la reazione deve lasciare esattamente una riga di storico");

        StoricoStatoTreno riga = righe.get(0);
        assertEquals("fermo", riga.stato, "BLOCCATO_GUASTO_STAZIONE si traduce in uno stato ammesso dal CHECK");
        assertEquals("attivo", riga.statoPrecedente);
        assertEquals("STAZIONE", riga.causaTipo);
        assertEquals(STAZ_B, riga.causaId);
        assertEquals(CATENA, riga.catenaId);
        assertNotNull(riga.tsStoricizzazione);
    }

    /**
     * La stessa reazione ripetuta non lascia una seconda riga. E' la regola che fa terminare le
     * catene (un nodo reagisce al massimo una volta per catena) e che regge la consegna
     * at-least-once del broker.
     */
    @Test
    @Order(3)
    public void loStessoNodoNonReagisceDueVolteAllaStessaCatena() throws Exception {
        applica(reazioneDelTreno("BLOCCATO_GUASTO_STAZIONE", "IN_VIAGGIO", true));
        applica(reazioneDelTreno("BLOCCATO_GUASTO_STAZIONE", "IN_VIAGGIO", true));

        assertEquals(1, storicoDelTreno().size(), "i doppioni della stessa catena non devono lasciare righe");
        assertTrue(registroCatene.nodiDi(CATENA).contains("TRENO:" + TRENO));
    }

    /**
     * L'uscita dalla catena (il guasto e' stato risolto) viene invece sempre applicata: se
     * valesse anche per lei la regola dei doppioni il nodo resterebbe registrato per sempre su
     * una catena ormai chiusa e non potrebbe piu' reagire a un nuovo guasto della stessa
     * sorgente.
     */
    @Test
    @Order(4)
    public void lUscitaDallaCatenaVieneSempreApplicata() throws Exception {
        applica(reazioneDelTreno("IN_VIAGGIO", "BLOCCATO_GUASTO_STAZIONE", false));

        List<StoricoStatoTreno> righe = storicoDelTreno();
        assertEquals(2, righe.size(), "il rientro e' un cambiamento e va storicizzato");
        assertFalse(registroCatene.nodiDi(CATENA).contains("TRENO:" + TRENO),
                "chi esce dalla catena non deve restarci registrato");

        // Uscito dalla catena, il convoglio puo' reagire di nuovo se il guasto si riapre.
        applica(reazioneDelTreno("BLOCCATO_GUASTO_STAZIONE", "IN_VIAGGIO", true));
        assertEquals(3, storicoDelTreno().size());
    }

    /**
     * La chiusura della catena, che la Centrale fa quando risolve il guasto primario, libera
     * tutti i nodi che le stavano reagendo: e' la rete di sicurezza per quelli che nel frattempo
     * si sono spenti senza dichiarare l'uscita.
     */
    @Test
    @Order(5)
    public void laChiusuraDellaCatenaLiberaTuttiINodi() {
        assertTrue(registroCatene.nodiDi(CATENA).contains("TRENO:" + TRENO));

        assertEquals(1, registroCatene.chiudi(CATENA).size());
        assertTrue(registroCatene.nodiDi(CATENA).isEmpty());
        assertTrue(registroCatene.chiudi(CATENA).isEmpty(), "chiudere due volte non deve dare errore");
    }

    /**
     * RF02.1.2.2.2: un convoglio si guasta fra due stazioni e l'arco che occupa diventa non
     * percorribile. La tratta non e' un processo e non parla: a dichiarare per lei e' il
     * convoglio che ci si e' fermato sopra, quindi la reazione arriva con sorgente TRATTA e
     * causa TRENO. Quello che si verifica qui e' che la Centrale sappia applicarla: prima le
     * tratte non avevano nessuno stato di percorribilita' e non c'era niente da cambiare.
     */
    @Test
    @Order(6)
    public void laTrattaOccupataDaUnAvariaVieneRegistrataConLaSuaCausa() throws Exception {
        applica("""
                { "tipoEvento": "REAZIONE", "sorgenteTipo": "TRATTA", "sorgenteId": "%s",
                  "nuovoStato": "IMPERCORRIBILE", "statoPrecedente": "PERCORRIBILE",
                  "causaTipo": "TRENO", "causaId": "%s", "catenaId": "%s",
                  "attiva": true, "motivo": "convoglio guasto in linea" }
                """.formatted(TRATTA, TRENO, CATENA_TRATTA));

        List<StoricoStatoTratta> righe = storicoDellaTratta();
        assertEquals(1, righe.size(), "il cambio di percorribilita' va storicizzato");
        StoricoStatoTratta riga = righe.get(0);
        assertEquals("IMPERCORRIBILE", riga.stato);
        assertEquals("PERCORRIBILE", riga.statoPrecedente);
        assertEquals("TRENO", riga.causaTipo, "la causa e' il convoglio, non la tratta");
        assertEquals(TRENO, riga.causaId);
        assertEquals(CATENA_TRATTA, riga.catenaId);
        assertNotNull(riga.descrizioneTratta, "la riga deve restare leggibile senza join");

        // Vale anche qui la regola dei doppioni: stesso nodo, stessa catena, una volta sola.
        applica("""
                { "tipoEvento": "REAZIONE", "sorgenteTipo": "TRATTA", "sorgenteId": "%s",
                  "nuovoStato": "IMPERCORRIBILE", "statoPrecedente": "PERCORRIBILE",
                  "causaTipo": "TRENO", "causaId": "%s", "catenaId": "%s",
                  "attiva": true, "motivo": "ripetizione del broker" }
                """.formatted(TRATTA, TRENO, CATENA_TRATTA));
        assertEquals(1, storicoDellaTratta().size(), "il doppione non lascia una seconda riga");

        // E l'uscita rimette l'arco in servizio, come per gli altri nodi.
        applica("""
                { "tipoEvento": "REAZIONE", "sorgenteTipo": "TRATTA", "sorgenteId": "%s",
                  "nuovoStato": "PERCORRIBILE", "statoPrecedente": "IMPERCORRIBILE",
                  "causaTipo": "TRENO", "causaId": "%s", "catenaId": "%s",
                  "attiva": false, "motivo": "avaria riparata" }
                """.formatted(TRATTA, TRENO, CATENA_TRATTA));
        assertEquals(2, storicoDellaTratta().size(), "anche il rientro e' un cambiamento");
        assertFalse(registroCatene.nodiDi(CATENA_TRATTA).contains("TRATTA:" + TRATTA));
    }

    /**
     * Risolvere la causa chiude le sue conseguenze. Un evento derivato non apre guasti, ma
     * quando la conseguenza e' un pezzo di infrastruttura inagibile il fatto viene ripubblicato
     * come guasto perche' l'operatore lo deve vedere: e' un allarme vero con la catena della
     * causa, e riparata la causa deve sparire da solo. Se restasse aperto, una sola avaria
     * lascerebbe in elenco N allarmi da chiudere a mano, che e' esattamente cio' che lo schema
     * degli eventi a catena serve a evitare.
     */
    @Test
    @Order(7)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void chiudendoLaCausaSiChiudonoLeConseguenze() {
        Instant quando = Instant.now();
        String causa = "DOM_CAUSA_" + SUFFISSO;
        String conseguenza = "DOM_CONSEG_" + SUFFISSO;
        QuarkusTransaction.requiringNew().run(() -> {
            guastoDellaCatena(causa, "TRENO", TRENO, quando.minusSeconds(60), CATENA_CONSEGUENZE).persist();
            guastoDellaCatena(conseguenza, "TRATTA", TRATTA, quando, CATENA_CONSEGUENZE).persist();
        });

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + causa + "/risolvi")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(Guasto.<Guasto>findById(causa).risolto, "la causa e' risolta");
            assertTrue(Guasto.<Guasto>findById(conseguenza).risolto,
                    "la conseguenza si chiude con lei: e' lo stesso fatto, non un'avaria in piu'");
        });
    }

    /**
     * Il verso conta: chiudere l'allarme sulla conseguenza non ripara la causa. L'arco torna
     * percorribile perche' l'operatore lo ha dichiarato, ma il convoglio guasto resta guasto.
     */
    @Test
    @Order(8)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void chiudendoLaConseguenzaLaCausaResta() {
        Instant quando = Instant.now();
        String causa = "DOM_CAUSA2_" + SUFFISSO;
        String conseguenza = "DOM_CONSEG2_" + SUFFISSO;
        QuarkusTransaction.requiringNew().run(() -> {
            guastoDellaCatena(causa, "TRENO", TRENO, quando.minusSeconds(60), CATENA_VERSO).persist();
            guastoDellaCatena(conseguenza, "TRATTA", TRATTA, quando, CATENA_VERSO).persist();
        });

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + conseguenza + "/risolvi")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(Guasto.<Guasto>findById(conseguenza).risolto);
            assertFalse(Guasto.<Guasto>findById(causa).risolto,
                    "chiudere l'allarme sulla tratta non ripara il convoglio che la occupa");
        });
    }

    /**
     * Un evento derivato senza catena non e' applicabile: la catena e' cio' che lo rende
     * idempotente, e senza di essa il messaggio verrebbe riapplicato a ogni ripetizione.
     */
    @Test
    @Order(9)
    public void senzaCatenaLaReazioneNonSiApplica() throws Exception {
        int righePrima = storicoDelTreno().size();
        applica("""
                { "tipoEvento": "REAZIONE", "sorgenteTipo": "TRENO", "sorgenteId": "%s",
                  "nuovoStato": "BLOCCATO_GUASTO_STAZIONE", "causaTipo": "STAZIONE",
                  "causaId": "%s", "attiva": true }
                """.formatted(TRENO, STAZ_B));

        assertEquals(righePrima, storicoDelTreno().size());
    }
}
