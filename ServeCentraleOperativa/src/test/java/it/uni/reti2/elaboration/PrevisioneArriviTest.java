package it.uni.reti2.elaboration;

import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Treno;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prova il "Processing" della Centrale: previsioni di arrivo e calcolo dei ritardi.
 *
 * <p>La rete di prova e' una linea di tre stazioni, A -&gt; B (10 minuti) e B -&gt; C
 * (20 minuti), con un convoglio assegnato all'itinerario che le tiene insieme.</p>
 *
 * <p>I numeri attesi dipendono dai due parametri dell'orario di servizio
 * ({@code viaggio.fattore.accelerazione}=100, {@code viaggio.sosta.secondi}=2): con
 * quella scala un minuto dell'orario dura 0,6 secondi reali e la sosta in fermata vale
 * 2 &times; 100 / 60 = 3,33 minuti di servizio. Sono scritti espliciti nei commenti
 * perche' un'asserzione che non si sa da dove esce non serve a niente.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PrevisioneArriviTest {

    private static final String SUFFISSO = UUID.randomUUID().toString().substring(0, 4);
    private static final String STAZ_A = "PREV_A_" + SUFFISSO;
    private static final String STAZ_B = "PREV_B_" + SUFFISSO;
    private static final String STAZ_C = "PREV_C_" + SUFFISSO;
    private static final String TRATTA_AB = "PREV_T_AB_" + SUFFISSO;
    private static final String TRATTA_BC = "PREV_T_BC_" + SUFFISSO;
    private static final String ITINERARIO = "PREV_IT_" + SUFFISSO;
    private static final String TRENO = "PREV_TRN_" + SUFFISSO;

    /** Quanti secondi reali dura un minuto di orario con il fattore 100 configurato. */
    private static final double SECONDI_PER_MINUTO = 0.6;

    /** L'istante da cui partono gli orari dei test sul ritardo in corso, che li ricevono tutti come parametro. */
    private static final Instant ORA = Instant.parse("2030-01-01T10:00:00Z");

    private CentraleDiProva centrale;
    private PrevisioneArrivi previsioni;
    private TrafficLogicEngine statoRete;

    @BeforeAll
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
        previsioni = centrale.previsioni;
        statoRete = centrale.statoRete;
    }

    @AfterAll
    void spegni() throws Exception {
        centrale.close();
    }

    // ──────────────────────────────────────────────────────────────
    // PREPARAZIONE
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    public void preparaLaLinea() throws Exception {
        creaStazione(STAZ_A, "Prev Alfa " + SUFFISSO);
        creaStazione(STAZ_B, "Prev Bravo " + SUFFISSO);
        creaStazione(STAZ_C, "Prev Charlie " + SUFFISSO);
        creaTratta(TRATTA_AB, STAZ_A, STAZ_B, 10);
        creaTratta(TRATTA_BC, STAZ_B, STAZ_C, 20);

        String itinerario = """
                {
                    "id": "%s",
                    "stazioni": ["%s", "%s", "%s"],
                    "travelTimes": [10, 20]
                }
                """.formatted(ITINERARIO, STAZ_A, STAZ_B, STAZ_C);
        Assertions.assertEquals(201, centrale.post("/api/tratte", itinerario, centrale.admin()).stato());

        String treno = """
                {"id": "%s", "itinerario": {"id": "%s"}}
                """.formatted(TRENO, ITINERARIO);
        Assertions.assertEquals(201, centrale.post("/api/treni", treno, centrale.admin()).stato());
    }

    // ──────────────────────────────────────────────────────────────
    // PREVISIONI DI ARRIVO
    // ──────────────────────────────────────────────────────────────

    /**
     * Convoglio fermo in A: verso B manca il tempo della tratta, verso C ci si aggiunge
     * la seconda tratta e la sosta della fermata intermedia.
     */
    @Test
    @Order(2)
    public void laPrevisioneSommaTratteESoste() {
        Treno treno = inStazione(STAZ_A, STAZ_B);

        Assertions.assertEquals(10, previsioni.previsioneVerso(treno, STAZ_B).minuti(),
                "verso la fermata successiva vale il solo tempo di percorrenza");
        Assertions.assertEquals(0, previsioni.previsioneVerso(treno, STAZ_B).fermateIntermedie());

        // 10 + 20 di percorrenza piu' la sosta in B: 33,33 minuti arrotondati.
        Assertions.assertEquals(33, previsioni.previsioneVerso(treno, STAZ_C).minuti(),
                "verso la seconda fermata si conta anche la sosta intermedia");
        Assertions.assertEquals(1, previsioni.previsioneVerso(treno, STAZ_C).fermateIntermedie(),
                "prima di C il convoglio si ferma una volta");
    }

    /** A meta' della tratta A-B manca meta' del tempo di percorrenza. */
    @Test
    @Order(3)
    public void aMetaTrattaRestaMetaTempo() {
        Treno treno = inTratta(STAZ_B, 40.0);

        Assertions.assertEquals(6, previsioni.previsioneVerso(treno, STAZ_B).minuti(),
                "al 40% di una tratta da 10 minuti ne restano 6");
    }

    /** Una stazione gia' superata non e' piu' un arrivo previsto. */
    @Test
    @Order(4)
    public void laStazioneAlleSpalleNonSiPrevede() {
        Treno treno = inTratta(STAZ_C, 10.0); // ha lasciato B, va verso C

        Assertions.assertNull(previsioni.previsioneVerso(treno, STAZ_A),
                "A e' dietro al convoglio: nessun arrivo da prevedere in questo verso");
    }

    // ──────────────────────────────────────────────────────────────
    // CALCOLO DEL RITARDO
    // ──────────────────────────────────────────────────────────────

    /**
     * Il ritardo e' la differenza fra il passaggio reale e l'orario che la Centrale ha
     * fissato alla partenza. Il convoglio non lo dichiara piu': nel payload del passaggio
     * il campo non esiste nemmeno.
     */
    @Test
    @Order(5)
    public void ilRitardoNasceDalConfrontoConLOrarioPrevisto() {
        Treno treno = inStazione(STAZ_A, STAZ_B);
        treno.ritardo = 0;

        Instant uscita = Instant.now();
        previsioni.programmaArrivo(treno, STAZ_A, uscita, "andata");

        // La tratta dura 10 minuti di orario, cioe' 6 secondi reali; il convoglio si
        // presenta 6 secondi dopo il previsto, che sono altri 10 minuti di orario.
        Instant arrivoInRitardo = uscita.plusMillis(millisecondiPer(10) + millisecondiPer(10));
        Assertions.assertTrue(previsioni.registraArrivo(treno, STAZ_B, arrivoInRitardo),
                "un arrivo fuori orario deve aggiornare il ritardo");
        Assertions.assertEquals(10, treno.ritardo, "10 minuti di orario persi per strada");
    }

    /** Chi arriva quando deve non accumula niente: lo scarto di misura non e' ritardo. */
    @Test
    @Order(6)
    public void inOrarioIlRitardoNonCresce() {
        Treno treno = inStazione(STAZ_A, STAZ_B);
        treno.ritardo = 4;

        Instant uscita = Instant.now();
        previsioni.programmaArrivo(treno, STAZ_A, uscita, "andata");

        // Mezzo secondo oltre l'orario: sono 0,83 minuti, sotto la tolleranza di 2.
        Instant arrivoPuntuale = uscita.plusMillis(millisecondiPer(10) + 500);
        Assertions.assertFalse(previsioni.registraArrivo(treno, STAZ_B, arrivoPuntuale),
                "il quanto di campionamento del twin non deve diventare ritardo");
        Assertions.assertEquals(4, treno.ritardo, "il ritardo precedente resta com'era");
    }

    /**
     * Il tempo perso fermi in banchina e' ritardo quanto quello perso in linea: e' il caso
     * del convoglio trattenuto da una stazione guasta, che riparte tardi e poi percorre la
     * tratta in orario. Senza questa misura risulterebbe puntuale.
     */
    @Test
    @Order(7)
    public void anchLaSostaTroppoLungaEUnRitardo() {
        Treno treno = inStazione(STAZ_B, STAZ_C);
        treno.ritardo = 0;

        Instant entrata = Instant.now();
        previsioni.registraArrivo(treno, STAZ_B, entrata);

        // B non e' un capolinea, quindi la sosta prevista vale 3,33 minuti di orario:
        // ripartendo dopo 23,33 minuti se ne sono persi 20.
        Instant uscitaTardiva = entrata.plusMillis(millisecondiPer(20) + sostaInMillisecondi());
        previsioni.programmaArrivo(treno, STAZ_B, uscitaTardiva, "andata");

        Assertions.assertEquals(20, treno.ritardo,
                "i minuti passati in stazione oltre la sosta prevista sono ritardo");
    }

    /** Una sosta regolare non deve inventare ritardo. */
    @Test
    @Order(8)
    public void laSostaNormaleNonProduceRitardo() {
        Treno treno = inStazione(STAZ_B, STAZ_C);
        treno.ritardo = 3;

        Instant entrata = Instant.now();
        previsioni.registraArrivo(treno, STAZ_B, entrata);
        previsioni.programmaArrivo(treno, STAZ_B, entrata.plusMillis(sostaInMillisecondi()), "andata");

        Assertions.assertEquals(3, treno.ritardo, "la sosta prevista non e' tempo perso");
    }

    // ──────────────────────────────────────────────────────────────
    // RITARDO IN CORSO
    // ──────────────────────────────────────────────────────────────

    /**
     * Il convoglio trattenuto in banchina accumula ritardo mentre aspetta, non solo quando
     * riparte; e quando riparte la misura dell'uscita prende il posto della stima, senza
     * contare due volte lo stesso tempo.
     */
    @Test
    @Order(9)
    public void ilTrenoTrattenutoInStazioneAccumulaRitardoMentreAspetta() {
        Treno treno = inStazione(STAZ_B, STAZ_C);
        ricomincia(treno);
        previsioni.registraArrivo(treno, STAZ_B, ORA);

        // La sosta in B vale 2 secondi: 10 minuti di orario dopo la sua fine il ritardo e'
        // gia' di 10, dopo 20 minuti e' di 20.
        previsioni.aggiornaRitardo(treno, ORA.plusMillis(sostaInMillisecondi() + millisecondiPer(10)));
        Assertions.assertEquals(10, treno.ritardo, "fermo oltre la sosta, il ritardo cresce");
        Instant uscita = ORA.plusMillis(sostaInMillisecondi() + millisecondiPer(20));
        previsioni.aggiornaRitardo(treno, uscita);
        Assertions.assertEquals(20, treno.ritardo, "e continua a crescere finche' resta fermo");

        previsioni.programmaArrivo(treno, STAZ_B, uscita, "andata");
        inTratta(STAZ_C, 0.0);
        previsioni.aggiornaRitardo(treno, uscita.plusMillis(500));
        Assertions.assertEquals(20, treno.ritardo, "all'uscita la misura prende il posto della stima: 20, non 40");
    }

    /**
     * In tratta conta la strada fatta: a meta' tratta dopo meta' del tempo il convoglio e'
     * in orario, fermo a meta' tratta accumula ritardo. All'arrivo vale la misura.
     */
    @Test
    @Order(10)
    public void inTrattaSiConfrontaIlTempoConLaStradaFatta() {
        Treno treno = inStazione(STAZ_B, STAZ_C);
        ricomincia(treno);
        previsioni.programmaArrivo(treno, STAZ_B, ORA, "andata");

        // B-C dura 20 minuti di orario: dopo 10 il treno a meta' tratta e' puntuale.
        inTratta(STAZ_C, 50.0);
        previsioni.aggiornaRitardo(treno, ORA.plusMillis(millisecondiPer(10)));
        Assertions.assertEquals(0, treno.ritardo, "a meta' tratta dopo meta' del tempo e' in orario");

        // Resta fermo li' altri 15 minuti: la strada fatta vale sempre 10 minuti.
        previsioni.aggiornaRitardo(treno, ORA.plusMillis(millisecondiPer(25)));
        Assertions.assertEquals(15, treno.ritardo, "fermo in linea, il ritardo cresce");

        // Ripartito, arriva in C dopo 35 minuti invece di 20.
        Assertions.assertTrue(previsioni.registraArrivo(treno, STAZ_C, ORA.plusMillis(millisecondiPer(35))));
        Assertions.assertEquals(15, treno.ritardo, "all'arrivo la misura prende il posto della stima: 15, non 30");
    }

    /**
     * Nella prima fermata il convoglio non entra, ci compare: la sosta comincia dal primo
     * frame di telemetria che lo vede li'. Senza, un treno trattenuto alla partenza
     * risultava puntuale.
     */
    @Test
    @Order(11)
    public void allaPartenzaLaSostaSiContaDalPrimoFrame() {
        Treno treno = inStazione(STAZ_A, STAZ_B);
        ricomincia(treno);

        previsioni.aggiornaRitardo(treno, ORA);
        Instant uscita = ORA.plusMillis(sostaInMillisecondi() + millisecondiPer(10));
        previsioni.aggiornaRitardo(treno, uscita);
        Assertions.assertEquals(10, treno.ritardo, "trattenuto alla partenza, il ritardo cresce");

        previsioni.programmaArrivo(treno, STAZ_A, uscita, "andata");
        Assertions.assertEquals(10, treno.ritardo, "e all'uscita resta, misurato");
    }

    /** A corsa finita il ritardo resta quello dell'arrivo, per quanto il treno resti fermo. */
    @Test
    @Order(12)
    public void alCapolineaIlRitardoNonCrescePiu() {
        Treno treno = inStazione(STAZ_B, STAZ_C);
        ricomincia(treno);
        previsioni.programmaArrivo(treno, STAZ_B, ORA, "andata");

        // Il frame del capolinea arriva prima dell'ENTRATA: l'ultima tratta si misura lo stesso.
        inStazione(STAZ_C, null);
        treno.stato = StatiCanonici.TRENO_CAPOLINEA;
        previsioni.aggiornaRitardo(treno, ORA.plusMillis(millisecondiPer(25)));
        previsioni.registraArrivo(treno, STAZ_C, ORA.plusMillis(millisecondiPer(25)));
        Assertions.assertEquals(5, treno.ritardo, "5 minuti persi sull'ultima tratta");

        previsioni.aggiornaRitardo(treno, ORA.plusSeconds(3600));
        Assertions.assertEquals(5, treno.ritardo, "fermo al capolinea non e' in ritardo: la corsa e' finita");
    }

    // ──────────────────────────────────────────────────────────────
    // TABELLONE DELLA STAZIONE
    // ──────────────────────────────────────────────────────────────

    /**
     * Il convoglio fermo in A compare fra i treni attesi di C, che nel suo itinerario
     * viene due fermate dopo: e' esattamente il caso che il filtro sulla posizione
     * istantanea del vecchio frontend non vedeva.
     */
    @Test
    @Order(13)
    public void ilTabelloneGuardaLIntinerarioNonLaPosizione() {
        inStazione(STAZ_A, STAZ_B);

        Map<String, Object> versoC = rigaDelTreno(previsioni.treniAttesi(STAZ_C));
        Assertions.assertNotNull(versoC, "il convoglio deve comparire fra gli attesi di C");
        Assertions.assertEquals("in_arrivo", versoC.get("tipo"));
        Assertions.assertEquals(33, versoC.get("minutiAllArrivo"));

        Map<String, Object> inA = rigaDelTreno(previsioni.treniAttesi(STAZ_A));
        Assertions.assertNotNull(inA, "in A il convoglio c'e' gia': e' quello in partenza");
        Assertions.assertEquals("in_partenza", inA.get("tipo"));
        Assertions.assertEquals(0, inA.get("minutiAllArrivo"));
    }

    /** Lo stesso elenco passando dall'API REST che usa la pagina Stazioni. */
    @Test
    @Order(14)
    public void lEndpointDeiTreniAttesiRisponde() throws Exception {
        inStazione(STAZ_A, STAZ_B);

        CentraleDiProva.Risposta attesi = centrale.get("/api/stazioni/" + STAZ_C + "/treni-attesi", centrale.tecnico());
        Assertions.assertEquals(200, attesi.stato());
        Assertions.assertTrue(attesi.corpo().contains("\"trenoId\":\"" + TRENO + "\""));

        Assertions.assertEquals(404, centrale.get("/api/stazioni/NON_ESISTE_" + SUFFISSO + "/treni-attesi",
                centrale.tecnico()).stato());
    }

    // ──────────────────────────────────────────────────────────────
    // PULIZIA
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(15)
    public void smontaLaLinea() throws Exception {
        for (String percorso : new String[]{"/api/treni/" + TRENO, "/api/tratte/" + ITINERARIO,
                "/api/tratte-elementari/" + TRATTA_BC, "/api/tratte-elementari/" + TRATTA_AB,
                "/api/stazioni/" + STAZ_C, "/api/stazioni/" + STAZ_B, "/api/stazioni/" + STAZ_A}) {
            Assertions.assertEquals(204, centrale.delete(percorso, centrale.admin()).stato(), percorso);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // AIUTI
    // ──────────────────────────────────────────────────────────────

    /** Mette il convoglio fermo in una stazione, diretto verso quella indicata. */
    private Treno inStazione(String stazione, String prossima) {
        Treno treno = statoRete.getTreno(TRENO);
        Assertions.assertNotNull(treno, "il convoglio di prova deve essere in cache");
        treno.direzione = "andata";
        treno.stato = "fermo";
        treno.stazioneCorrente = stazione;
        treno.prossimaStazione = prossima;
        treno.progresso = 100.0;
        return treno;
    }

    /** Mette il convoglio in marcia verso una stazione, a una certa quota di tratta. */
    private Treno inTratta(String prossima, double progresso) {
        Treno treno = statoRete.getTreno(TRENO);
        Assertions.assertNotNull(treno, "il convoglio di prova deve essere in cache");
        treno.direzione = "andata";
        treno.stato = "attivo";
        treno.stazioneCorrente = null;
        treno.prossimaStazione = prossima;
        treno.progresso = progresso;
        return treno;
    }

    /** Riparte da un convoglio senza ritardo e senza orari o soste in sospeso. */
    private void ricomincia(Treno treno) {
        previsioni.dimenticaTreno(treno.id);
        treno.ritardo = 0;
    }

    /** La riga del convoglio di prova dentro un tabellone (gli altri treni non contano). */
    private Map<String, Object> rigaDelTreno(List<Map<String, Object>> tabellone) {
        return tabellone.stream()
                .filter(riga -> TRENO.equals(riga.get("trenoId")))
                .findFirst()
                .orElse(null);
    }

    /** La sosta prevista in una fermata intermedia, in millisecondi reali. */
    private long sostaInMillisecondi() {
        return 2000; // viaggio.sosta.secondi = 2
    }

    /** Quanti millisecondi reali durano i minuti di orario indicati. */
    private long millisecondiPer(int minutiDiServizio) {
        return Math.round(minutiDiServizio * SECONDI_PER_MINUTO * 1000);
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

    private void creaTratta(String id, String partenza, String arrivo, int minuti) throws Exception {
        String corpo = """
                {
                    "id": "%s",
                    "stazionePartenzaId": "%s",
                    "stazioneArrivoId": "%s",
                    "tempoPercorrenzaMinuti": %d
                }
                """.formatted(id, partenza, arrivo, minuti);
        Assertions.assertEquals(201, centrale.post("/api/tratte-elementari", corpo, centrale.admin()).stato());
    }
}
