package it.uni.reti2.gateway;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Itinerario;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoAssegnazioneGuasto;
import it.uni.reti2.entity.StoricoInterventoManutenzione;
import it.uni.reti2.entity.StoricoItinerario;
import it.uni.reti2.entity.StoricoItinerarioTratta;
import it.uni.reti2.entity.StoricoStatoStazione;
import it.uni.reti2.entity.StoricoStatoTreno;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.entity.Utente;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica di RF02.7 (memoria storica), nelle sue due meta'.
 *
 * <p><b>Quello che e' successo resta scritto</b> anche dopo che la stazione, il convoglio o
 * la tratta che l'hanno prodotto sono stati tolti dalla rete. Finche' gli storici avevano
 * una chiave esterna verso l'anagrafica succedeva il contrario: per cancellare una stazione
 * il gateway doveva prima buttare via i suoi transiti storici, e una tratta gia'
 * storicizzata non si poteva togliere affatto.</p>
 *
 * <p><b>E si scrive tutto quello che l'enunciato elenca</b>, comprese le due cose che fino a
 * ieri non le scriveva nessuno: gli itinerari percorsi dai convogli e le assegnazioni degli
 * operatori (la presa in carico di un allarme e l'invio della squadra a una stazione).
 * Sempre ai cambiamenti e mai ai campionamenti, che e' la regola di registrazione del
 * requisito: una PUT che non cambia niente non deve lasciare una riga in piu'.</p>
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
    private static final String TRENO_PREESISTENTE = "MEM_OLD_" + SUFFISSO;
    private static final String GUASTO = "MEM_G_" + SUFFISSO;
    private static final String GUASTO_MANUTENZIONE = "MEM_GM_" + SUFFISSO;
    private static final String GUASTO_ANAGRAFICA = "MEM_GA_" + SUFFISSO;
    private static final String GUASTO_PASSAGGIO = "MEM_GP_" + SUFFISSO;

    /** La matricola che @TestSecurity mette nel principal, in maiuscolo come la usa la Centrale. */
    private static final String MATRICOLA = "MAT001";

    /**
     * L'unico operatore che in questi test esiste anche in anagrafica. La matricola e'
     * diversa da {@link #MATRICOLA} apposta: la riga di Utenti viene seminata a meta' suite
     * e non deve cambiare l'esito dei test che si aspettano l'operatore senza anagrafica.
     */
    private static final String MATRICOLA_ANAGRAFICA = "MAT099";
    private static final String UTENTE = "U99_" + SUFFISSO;

    /** Serve al test dell'allineamento, che chiama il repository invece di un endpoint. */
    @Inject
    RailwayRepository repository;

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

    /**
     * L'itinerario assegnato al convoglio lascia una riga aperta, con il percorso copiato
     * dentro tratta per tratta. E riassegnare lo stesso itinerario non ne lascia una seconda:
     * RF02.7 registra i cambiamenti, non i campionamenti.
     */
    @Test
    @Order(2)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void lItinerarioAssegnatoLasciaUnaRigaSola() {
        QuarkusTransaction.requiringNew().run(() -> {
            StoricoItinerario percorso = StoricoItinerario.find("trenoId", TRENO).firstResult();
            assertNotNull(percorso, "l'assegnazione fatta alla creazione del convoglio va storicizzata");
            assertEquals(ITINERARIO, percorso.itinerarioId);
            assertNull(percorso.tsCompletamento, "il viaggio e' in corso, non ha un istante di fine");
            assertEquals(1, percorso.numeroTratte);
            // Il percorso in chiaro e' quello che rende leggibile la riga quando fra un mese
            // l'itinerario avra' tappe diverse, o non ci sara' piu'.
            assertTrue(percorso.descrizionePercorso.startsWith("Memoria Alfa"));
            assertTrue(percorso.descrizionePercorso.contains("Memoria Bravo"));

            List<StoricoItinerarioTratta> tratte =
                    StoricoItinerarioTratta.list("storicoItinerarioId", percorso.id);
            assertEquals(1, tratte.size(), "ogni tratta del percorso ha la sua riga");
            assertEquals(TRATTA_AB, tratte.get(0).trattaId);
            assertEquals(STAZ_A, tratte.get(0).stazionePartenzaId);
            assertEquals(STAZ_B, tratte.get(0).stazioneArrivoId);
            assertEquals(20, tratte.get(0).tempoPercorrenzaMinuti,
                    "il tempo di percorrenza e' quello di allora, non quello di domani");
        });

        // Stesso itinerario di prima: non e' cambiato niente e non si scrive niente.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "stato": "attivo", "itinerario": { "id": "%s" } }
                    """.formatted(ITINERARIO))
        .when()
            .put("/api/treni/" + TRENO)
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(1, StoricoItinerario.count("trenoId", TRENO),
                        "riassegnare lo stesso itinerario non e' un cambiamento: niente riga nuova"));
    }

    /**
     * Se l'amministratore riscrive le tappe dell'itinerario, da quel momento il convoglio ne
     * percorre un altro: la riga di prima si chiude e se ne apre una con il percorso nuovo.
     * E' il caso per cui il percorso viene copiato nello storico invece di essere solo puntato.
     */
    @Test
    @Order(3)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void riscrivereIlPercorsoChiudeIlViaggioPrecedente() {
        // L'itinerario diventa A -> B -> C.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "stazioni": ["%s", "%s", "%s"], "travelTimes": [20, 20] }
                    """.formatted(STAZ_A, STAZ_B, STAZ_C))
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(2, StoricoItinerario.count("trenoId", TRENO),
                    "cambiate le tappe, il viaggio di prima finisce e ne comincia un altro");

            StoricoItinerario chiuso = StoricoItinerario
                    .find("trenoId = ?1 and tsCompletamento is not null", TRENO).firstResult();
            assertNotNull(chiuso, "il viaggio sul percorso vecchio deve risultare concluso");
            assertEquals(1, chiuso.numeroTratte);

            StoricoItinerario aperto = StoricoItinerario
                    .find("trenoId = ?1 and tsCompletamento is null", TRENO).firstResult();
            assertNotNull(aperto);
            assertEquals(2, aperto.numeroTratte);
            assertTrue(aperto.descrizionePercorso.contains("Memoria Charlie"));
        });

        // Si torna ad A -> B, cosi' la tratta B -> C resta libera per il test dopo.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    { "stazioni": ["%s", "%s"], "travelTimes": [20] }
                    """.formatted(STAZ_A, STAZ_B))
        .when()
            .put("/api/tratte/" + ITINERARIO)
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(3, StoricoItinerario.count("trenoId", TRENO),
                        "anche il ritorno al percorso di prima e' un cambiamento"));
    }

    @Test
    @Order(4)
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
    @Order(5)
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

            // Il percorso storicizzato che passava per C se lo porta ancora dentro.
            StoricoItinerarioTratta versoCharlie =
                    StoricoItinerarioTratta.find("stazioneArrivoId", STAZ_C).firstResult();
            assertNotNull(versoCharlie, "le tratte del viaggio restano anche senza la stazione");
            assertTrue(versoCharlie.nomeStazioneArrivo.startsWith("Memoria Charlie"));
        });
    }

    /**
     * Presa in carico e chiusura di un allarme: chi se n'e' occupato resta scritto. Prima il
     * token serviva solo al filtro e a guasto chiuso non si sapeva piu' chi lo aveva chiuso.
     */
    @Test
    @Order(6)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void laPresaInCaricoDiUnAllarmeLasciaIlNomeDellOperatore() {
        QuarkusTransaction.requiringNew().run(() -> guastoDiProva(GUASTO).persist());

        // Il contentType serve solo perche' RestAssured, lasciato a se', spedisce
        // text/plain e la risorsa consuma JSON: il browser questi comandi li manda senza
        // corpo e senza header, e in quel caso il controllo non scatta.
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO + "/assegna")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoAssegnazioneGuasto assegnazione =
                    StoricoAssegnazioneGuasto.find("guastoId", GUASTO).firstResult();
            assertNotNull(assegnazione, "la presa in carico deve lasciare la sua riga");
            assertEquals(MATRICOLA, assegnazione.matricolaOperatore);
            assertEquals("tecnico", assegnazione.ruoloOperatore,
                    "il ruolo e' quello con cui ha dato il comando, non quello in anagrafica");
            assertEquals("sensore_offline", assegnazione.tipoGuasto);
            assertEquals(STAZ_A, assegnazione.sorgenteId);
            assertTrue(assegnazione.nomeSorgente.startsWith("Memoria Alfa"),
                    "della sorgente si congela anche il nome, che l'id da solo non dice niente");
            assertNotNull(assegnazione.tsAssegnazione);
            assertNull(assegnazione.tsRisoluzione, "l'allarme e' preso in carico, non ancora risolto");
        });

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO + "/risolvi")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, StoricoAssegnazioneGuasto.count("guastoId", GUASTO),
                    "la risoluzione chiude la presa in carico, non ne apre un'altra");
            StoricoAssegnazioneGuasto assegnazione =
                    StoricoAssegnazioneGuasto.find("guastoId", GUASTO).firstResult();
            assertNotNull(assegnazione.tsRisoluzione, "chiuso l'allarme, si chiude l'assegnazione");
        });
    }

    /**
     * L'invio della squadra a una stazione lascia la riga dell'intervento: prima passava solo
     * per MQTT e per la WebSocket, cioe' spariva appena finito.
     *
     * <p>La riga nasce APERTA e la stazione resta in MANUTENZIONE (RF01.4.1): prima il comando
     * rimetteva ONLINE dentro la stessa chiamata, quindi lo stato non durava e fra {@code
     * ts_invio} e {@code ts_rientro} passavano millisecondi. Adesso il rientro e' un comando a
     * se', ed e' quello che si verifica nel test successivo.</p>
     */
    @Test
    @Order(7)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void lInvioDellaSquadraLasciaLaRigaDellIntervento() {
        QuarkusTransaction.requiringNew().run(() -> guastoDiProva(GUASTO_MANUTENZIONE).persist());

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/" + STAZ_A + "/manutenzione")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoInterventoManutenzione intervento =
                    StoricoInterventoManutenzione.find("stazioneId", STAZ_A).firstResult();
            assertNotNull(intervento, "la squadra mandata a una stazione va storicizzata");
            assertTrue(intervento.nomeStazione.startsWith("Memoria Alfa"));
            assertEquals(MATRICOLA, intervento.matricolaOperatore);
            assertEquals(GUASTO_MANUTENZIONE, intervento.guastoId,
                    "va scritto anche il guasto che ha motivato l'invio");
            assertEquals("MANUTENZIONE", intervento.statoStazioneDopo,
                    "finche' la squadra lavora la stazione risulta in manutenzione");
            assertNotNull(intervento.tsInvio);
            assertNull(intervento.tsRientro, "l'intervento e' in corso: il rientro non c'e' ancora");
            assertNotNull(intervento.catenaId, "l'intervento e' un episodio con la sua catena");

            // Il guasto che ha fatto partire la squadra risulta chiuso da chi l'ha mandata,
            // anche se non era passato dalla presa in carico esplicita.
            StoricoAssegnazioneGuasto assegnazione =
                    StoricoAssegnazioneGuasto.find("guastoId", GUASTO_MANUTENZIONE).firstResult();
            assertNotNull(assegnazione, "chi risolve un allarme se n'e' occupato: va scritto");
            assertEquals(MATRICOLA, assegnazione.matricolaOperatore);
            assertNotNull(assegnazione.tsRisoluzione);
        });

        // Lo stato dura: e' il punto di RF01.4.1. Un secondo invio sulla stessa stazione viene
        // rifiutato, perche' la squadra e' gia' li'.
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/" + STAZ_A + "/manutenzione")
        .then()
            .statusCode(409);

        // E non lo cancella nemmeno la chiusura di un altro allarme della stessa stazione:
        // e' la strada da cui il difetto rientrerebbe, perche' la schermata manda la squadra e
        // subito dopo mette "presa visione" sull'allarme.
        String altroGuasto = GUASTO_MANUTENZIONE + "_B";
        QuarkusTransaction.requiringNew().run(() -> guastoDiProva(altroGuasto).persist());
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + altroGuasto + "/risolvi")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/api/stazioni")
        .then()
            .statusCode(200)
            .body("find { it.id == '" + STAZ_A + "' }.stato", equalTo("MANUTENZIONE"));
    }

    /**
     * Il ritorno in servizio e' un comando a se', legato alla fine vera dell'intervento: e'
     * quello che mancava a RF01.4.1, dove MANUTENZIONE durava quanto la chiamata che la
     * impostava. Chiude anche la riga dell'intervento, quindi fra invio e rientro resta scritta
     * la durata vera del lavoro, e lascia le due righe di cambio stato con la loro causa.
     */
    @Test
    @Order(8)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void laFineDellInterventoRimetteLaStazioneInServizio() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/" + STAZ_A + "/manutenzione/conclusa")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoInterventoManutenzione intervento =
                    StoricoInterventoManutenzione.find("stazioneId", STAZ_A).firstResult();
            assertNotNull(intervento.tsRientro, "l'intervento concluso ha il suo rientro");
            assertEquals("ONLINE", intervento.statoStazioneDopo);

            // Le due righe di cambio stato dell'episodio: ingresso in manutenzione e ritorno,
            // tutte e due con la causa (chi ha deciso) e la catena dell'intervento.
            List<StoricoStatoStazione> cambi = StoricoStatoStazione
                    .<StoricoStatoStazione>find("stazioneId = ?1 and catenaId = ?2",
                            STAZ_A, intervento.catenaId).list();
            assertEquals(2, cambi.size(),
                    "l'intervento lascia due cambi di stato: l'ingresso in manutenzione e il rientro");
            for (StoricoStatoStazione cambio : cambi) {
                assertEquals("OPERATORE", cambio.causaTipo,
                        "il cambiamento non nasce da un sensore ma da una decisione");
                assertEquals(MATRICOLA, cambio.causaId);
            }
            assertTrue(cambi.stream().anyMatch(c -> "MANUTENZIONE".equals(c.stato)));
            assertTrue(cambi.stream().anyMatch(c -> "ONLINE".equals(c.stato)));
        });

        // Senza nessun intervento in corso il comando non ha senso e viene rifiutato.
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/stazioni/" + STAZ_A + "/manutenzione/conclusa")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(9)
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

            // Rottamato il convoglio nessun viaggio puo' restare in corso, ma quelli fatti
            // restano tutti, con il loro percorso.
            assertEquals(0, StoricoItinerario.count("trenoId = ?1 and tsCompletamento is null", TRENO),
                    "l'ultimo itinerario si chiude quando il convoglio viene eliminato");
            assertEquals(3, StoricoItinerario.count("trenoId", TRENO),
                    "gli itinerari percorsi restano scritti");
        });
    }

    /**
     * Con la riga di anagrafica al suo posto la presa in carico scrive l'id "U..." e il nome
     * per esteso, e valorizza anche la chiave esterna del guasto.
     *
     * <p>Questo caso i test di prima non lo toccavano: il database di prova non ha
     * l'anagrafica precaricata (=sql-load-script=no-file=), quindi
     * =trovaUtentePerMatricola= tornava sempre null e il ramo che scrive
     * =Guasto.operatore= non veniva mai eseguito. La matricola usata qui e' apposta
     * diversa da quella degli altri test, cosi' l'utente seminato non cambia il risultato
     * di nessuno di loro.</p>
     */
    @Test
    @Order(10)
    @TestSecurity(user = "mat099", roles = {"tecnico"})
    public void conLAnagraficaLaPresaInCaricoScriveNomeEChiaveEsterna() {
        QuarkusTransaction.requiringNew().run(() -> {
            utenteDiProva().persist();
            guastoDiProva(GUASTO_ANAGRAFICA).persist();
        });

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO_ANAGRAFICA + "/assegna")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoAssegnazioneGuasto assegnazione =
                    StoricoAssegnazioneGuasto.find("guastoId", GUASTO_ANAGRAFICA).firstResult();
            assertNotNull(assegnazione);
            assertEquals(UTENTE, assegnazione.operatoreId, "con l'anagrafica si scrive l'id_utente");
            assertEquals("Anna Verdi", assegnazione.nomeOperatore);
            assertEquals(MATRICOLA_ANAGRAFICA, assegnazione.matricolaOperatore);

            // La colonna OperatoreCheSeNeStaOccupandoFK, che fino a ieri restava vuota.
            Guasto guasto = Guasto.findById(GUASTO_ANAGRAFICA);
            assertNotNull(guasto.operatore, "il guasto deve sapere chi se ne sta occupando");
            assertEquals(UTENTE, guasto.operatore.id);
        });

        // E l'elenco degli allarmi lo dice al frontend: senza questo campo la presa in
        // carico si vedrebbe solo nel browser che ha premuto il pulsante e sparirebbe al
        // ricaricamento della pagina.
        given()
        .when()
            .get("/api/allarmi")
        .then()
            .statusCode(200)
            .body("find { it.id == '" + GUASTO_ANAGRAFICA + "' }.operatore", equalTo("Anna Verdi"));
    }

    /** Prima presa in carico, da un operatore che in anagrafica non c'e'. */
    @Test
    @Order(11)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void unAltroOperatorePrendeInCaricoPerPrimo() {
        QuarkusTransaction.requiringNew().run(() -> guastoDiProva(GUASTO_PASSAGGIO).persist());

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO_PASSAGGIO + "/assegna")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(1, StoricoAssegnazioneGuasto.count("guastoId", GUASTO_PASSAGGIO)));
    }

    /**
     * Passaggio di mano: se se ne prende carico un secondo operatore, la riga del primo si
     * chiude e ne comincia una nuova. Cosi' a lavorarci non risulta mai piu' di uno per
     * volta, e resta scritto che il testimone e' passato.
     */
    @Test
    @Order(12)
    @TestSecurity(user = "mat099", roles = {"tecnico"})
    public void ilPassaggioDiManoChiudeLaRigaDelPrimo() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO_PASSAGGIO + "/assegna")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(2, StoricoAssegnazioneGuasto.count("guastoId", GUASTO_PASSAGGIO),
                    "il passaggio di mano lascia due righe, non ne sovrascrive una");
            assertEquals(1, StoricoAssegnazioneGuasto.count(
                            "guastoId = ?1 and tsRisoluzione is null", GUASTO_PASSAGGIO),
                    "a lavorarci resta uno solo");

            StoricoAssegnazioneGuasto aperta = StoricoAssegnazioneGuasto
                    .find("guastoId = ?1 and tsRisoluzione is null", GUASTO_PASSAGGIO).firstResult();
            assertEquals(UTENTE, aperta.operatoreId, "quella aperta e' del secondo operatore");
        });

        // Riprendere in carico un guasto che si ha gia' non e' un cambiamento: niente riga.
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO_PASSAGGIO + "/assegna")
        .then()
            .statusCode(200);

        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(2, StoricoAssegnazioneGuasto.count("guastoId", GUASTO_PASSAGGIO),
                        "chi ha gia' l'allarme in mano non lascia una riga a ogni click"));
    }

    /**
     * L'allineamento dell'avvio: un convoglio assegnato "di nascosto", cioe' scritto sulla
     * tabella Treni senza passare dagli endpoint, e' esattamente com'erano i convogli
     * esistenti prima che la registrazione degli itinerari percorsi venisse scritta. Nessun
     * cambiamento futuro gli aprirebbe una riga, perche' il cambiamento e' gia' avvenuto:
     * ce la deve aprire l'allineamento.
     */
    @Test
    @Order(13)
    @TestSecurity(user = "mat001", roles = {"amministratore"})
    public void lAvvioApreLaRigaDeiConvogliGiaAssegnati() {
        QuarkusTransaction.requiringNew().run(() -> {
            Treno vecchio = new Treno(TRENO_PREESISTENTE);
            vecchio.stato = "fermo";
            vecchio.itinerario = Itinerario.findById(ITINERARIO);
            vecchio.persist();

            assertEquals(0, StoricoItinerario.count("trenoId", TRENO_PREESISTENTE),
                    "scritto cosi' il convoglio non ha nessuna storia: e' il caso da sanare");
        });

        // È quello che fa AllineamentoStorico quando la Centrale parte.
        QuarkusTransaction.requiringNew().run(() -> {
            // Che sia esattamente uno e' esso stesso la verifica piu' forte: vuol dire che
            // tutti gli altri convogli del database una riga aperta ce l'hanno gia', cioe'
            // che nessun percorso di assegnazione si e' dimenticato di registrarla.
            assertEquals(1, repository.allineaItinerariPercorsi(),
                    "va allineato solo il convoglio scritto a mano");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            StoricoItinerario percorso = StoricoItinerario
                    .find("trenoId = ?1 and tsCompletamento is null", TRENO_PREESISTENTE).firstResult();
            assertNotNull(percorso, "l'allineamento apre la riga che mancava");
            assertEquals(ITINERARIO, percorso.itinerarioId);
            assertTrue(percorso.descrizionePercorso.startsWith("Memoria Alfa"),
                    "e si porta dentro il percorso di adesso, come tutte le altre");
        });

        // Idempotente: al secondo avvio quella riga c'e' gia' e non se ne aggiungono altre.
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0, repository.allineaItinerariPercorsi(),
                        "un secondo avvio non deve raddoppiare le righe"));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(1, StoricoItinerario.count("trenoId", TRENO_PREESISTENTE)));
    }

    /** Un allarme gia' chiuso non si prende piu' in carico: non ci sarebbe niente da fare. */
    @Test
    @Order(14)
    @TestSecurity(user = "mat001", roles = {"tecnico"})
    public void unAllarmeGiaRisoltoNonSiPrendeInCarico() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/allarmi/" + GUASTO + "/assegna")
        .then()
            .statusCode(409);
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

    /**
     * Un allarme aperto dalla stazione A, come lo scriverebbe l'ingestione ricevendolo da
     * MQTT: serve ai due test sulle assegnazioni degli operatori.
     *
     * @param id Identificativo del guasto.
     */
    /**
     * La riga di anagrafica dell'unico operatore che in questi test esiste anche in Utenti.
     * Il tipo e' quello della colonna, che non c'entra con il ruolo di realm del token: lo
     * storico scrive il secondo, non il primo.
     */
    private static Utente utenteDiProva() {
        Utente utente = new Utente();
        utente.id = UTENTE;
        utente.tipo = "operatore";
        utente.nome = "Anna";
        utente.cognome = "Verdi";
        utente.matricola = MATRICOLA_ANAGRAFICA;
        return utente;
    }

    private static Guasto guastoDiProva(String id) {
        Guasto guasto = new Guasto();
        guasto.id = id;
        guasto.tipo = "sensore_offline";
        guasto.severita = "CRITICAL";
        guasto.sorgenteTipo = "STAZIONE";
        guasto.sorgenteId = STAZ_A;
        guasto.messaggio = "Sensore fuori servizio";
        guasto.timestamp = Instant.now();
        return guasto;
    }
}
