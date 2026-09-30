package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/**
 * Prova che una stazione in MANUTENZIONE faccia sul convoglio lo stesso effetto di una
 * stazione GUASTA: chi ci sta arrivando si ferma prima, e chi e' gia' fermo li' dentro
 * non riparte finche' i tecnici non hanno finito.
 *
 * <p>La manutenzione non nasce da nessun guasto segnalato dal campo — la decide la
 * Centrale, anche dal pannello dell'amministratore — quindi l'unico modo che il treno ha
 * di saperlo e' l'alert STATO_STAZIONE.</p>
 *
 * <p>Come in RitardoComunicatoTest gateway, gestore e automi sono costruiti a mano (come
 * fa il main) e l'alert viene consegnato direttamente al listener, cosi' il broker
 * (condiviso con il sistema acceso) non viene toccato.</p>
 */
public class StazioneInManutenzioneTest {

    private TrainGateway gateway;
    private TrainDB db;

    /** Convoglio TRN001 in marcia: e' fermo a S3 e la prossima fermata e' S4. */
    @BeforeEach
    public void preparaIlConvoglioInMarcia() {
        db = new TrainDB();
        db.trenoId = "TRN001";
        db.stazioneCorrente = "S3";
        db.prossimaStazione = "S4";
        db.faseViaggio = "IN_STAZIONE";

        AutomaViaggio viaggio = new AutomaViaggio();
        viaggio.trainDB = db;
        AutomaCondizione condizione = new AutomaCondizione();
        condizione.trainDB = db;
        AutomaStazioniNote stazioni = new AutomaStazioniNote();
        stazioni.trainDB = db;
        GestoreEventi gestore = new GestoreEventi();
        gestore.trainDB = db;
        gestore.viaggio = viaggio;
        gestore.condizione = condizione;
        gestore.stazioni = stazioni;

        gateway = new TrainGateway();
        gateway.trainDB = db;
        gateway.gestore = gestore;
    }

    /**
     * Il treno sta viaggiando verso S4 e la Centrale ci manda la squadra: deve fermarsi
     * come davanti a un guasto, senza aspettare di esserci arrivato.
     */
    @Test
    public void laStazioneInManutenzioneFermaIlTrenoInArrivo() {
        db.faseViaggio = "IN_VIAGGIO";
        db.stazioneCorrente = null;

        consegna(alertStato("S4", "MANUTENZIONE"));

        Assertions.assertEquals("BLOCCATO_IN_TRATTA", db.faseViaggio, "S4 e' la mia prossima fermata");
        Assertions.assertEquals("S4", db.stazioneBloccante);
        Assertions.assertEquals("FERMO", db.stato());
        Assertions.assertEquals(0.0, db.velocita, "un treno bloccato non ha velocita'");
    }

    /**
     * Il convoglio e' in sosta proprio nella stazione che passa in manutenzione: resta
     * dov'e', perche' ripartire vorrebbe dire muoversi su binari su cui stanno lavorando.
     */
    @Test
    public void ilTrenoInTransitoNellaStazioneRestaFermo() {
        consegna(alertStato("S3", "MANUTENZIONE"));

        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio);
        Assertions.assertEquals("S3", db.stazioneBloccante, "mi blocca la stazione in cui mi trovo");
    }

    /**
     * Il caso che prima non funzionava: la stazione e' guasta, l'operatore ci manda la
     * squadra e poi chiude l'allarme. Il RESOLVED da solo non basta, perche' i tecnici sono
     * ancora sui binari: il treno riparte solo quando la Centrale la dichiara agibile.
     */
    @Test
    public void ilGuastoRisoltoNonBastaSeLaSquadraELiAncora() {
        consegna(alertGuasto("S4"));
        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio, "il guasto mi ha fermato");

        consegna(alertStato("S4", "MANUTENZIONE"));
        consegna(alertResolved("S4"));

        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio, "la squadra e' ancora sul posto");
        Assertions.assertEquals("S4", db.stazioneBloccante);

        consegna(alertStato("S4", "ONLINE"));

        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio, "adesso posso ripartire");
        Assertions.assertNull(db.stazioneBloccante);
    }

    /**
     * Il RESOLVED da solo non e' un permesso di ripartire, nemmeno quando il treno non ha
     * mai sentito dichiarare la manutenzione: quel messaggio lo pubblica anche il nodo di
     * terra quando i suoi sensori tornano a battere, e il nodo non sa se sui binari ci sia
     * ancora una squadra. Chi lo sa e' la Centrale, e lo dice con STATO_STAZIONE.
     */
    @Test
    public void ilResolvedDaSoloNonFaRipartireIlConvoglio() {
        consegna(alertGuasto("S4"));
        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio, "il guasto mi ha fermato");

        consegna(alertResolved("S4"));

        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio,
                "l'allarme e' chiuso, ma dei binari non me l'ha detto nessuno");
        Assertions.assertEquals("S4", db.stazioneBloccante);
    }

    /**
     * I due messaggi della riapertura viaggiano separati e arrivano nell'ordine che
     * capita: la Centrale annuncia lo stato della stazione prima di pubblicare la
     * chiusura dell'allarme. Il convoglio deve ripartire lo stesso, anche se in quel
     * momento ha ancora in memoria il guasto di quella stazione.
     */
    @Test
    public void laStazioneDichiarataAgibileFaRipartireAnchePrimaDelResolved() {
        consegna(alertGuasto("S4"));
        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio);

        consegna(alertStato("S4", "ONLINE"));

        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio, "la Centrale ha riaperto i binari");
        Assertions.assertNull(db.stazioneBloccante);
        Assertions.assertFalse(db.stazioneImpercorribile("S4"),
                "il guasto che avevo in memoria non vale piu': l'ha smentito la Centrale");

        // La chiusura dell'allarme arriva dopo e non deve disfare niente.
        consegna(alertResolved("S4"));
        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio);
    }

    /**
     * railway/alerts e' un topic condiviso: la manutenzione di una stazione che non e' sulla
     * mia rotta la sento anche io, ma non deve fermarmi. Lo stato me lo segno lo stesso, che
     * serve se piu' avanti quella stazione diventa la mia prossima fermata.
     */
    @Test
    public void laManutenzioneDiUnAltraStazioneNonMiFerma() {
        consegna(alertStato("S9", "MANUTENZIONE"));

        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio, "S9 non e' ne' la corrente ne' la prossima");
        Assertions.assertNull(db.stazioneBloccante);
        Assertions.assertTrue(db.stazioneImpercorribile("S9"), "lo stato resta comunque scritto a bordo");
    }

    // ---------------------------------------------------------------- utilita'

    /** Consegna l'alert al listener come farebbe il connettore MQTT. */
    private void consegna(String payload) {
        gateway.riceviAlert(payload);
    }

    /** Un alert STATO_STAZIONE nel formato che pubblica PubblicatoreAlert. */
    private String alertStato(String stazione, String stato) {
        return ("{\"tipoEvento\":\"STATO_STAZIONE\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\","
                + "\"sorgenteId\":\"%s\",\"stato\":\"%s\",\"severita\":\"CRITICAL\",\"timestamp\":\"%s\"}")
                .formatted(stazione, stato, java.time.Instant.now());
    }

    /** Il guasto critico di una stazione, quello che il treno gia' sapeva gestire. */
    private String alertGuasto(String stazione) {
        return ("{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\","
                + "\"severita\":\"CRITICAL\",\"messaggio\":\"binario rotto\",\"timestamp\":\"%s\"}")
                .formatted(stazione, java.time.Instant.now());
    }

    /** La chiusura dell'allarme annunciata dalla Centrale. */
    private String alertResolved(String stazione) {
        return ("{\"tipoEvento\":\"RESOLVED\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\","
                + "\"sorgenteId\":\"%s\",\"guastoId\":\"alert-1\",\"timestamp\":\"%s\"}")
                .formatted(stazione, java.time.Instant.now());
    }
}
