package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova la regola con cui la Centrale decide se sa davvero dove sta un convoglio.
 *
 * <p>La rete di riferimento e' quella che ha fatto nascere il problema: l'itinerario
 * BE -&gt; TO -&gt; MI -&gt; FI con le stazioni di Berlino e Torino spente. I loro
 * passaggi non li rileva nessuno, quindi {@code posizioneAttualeTratta} resta ferma su
 * TO_MI mentre il convoglio percorre altre tratte; la telemetria invece continua ad
 * arrivare ed e' aggiornata. Comporre le due cose alla lettera produceva corse fra
 * stazioni che nessun binario collega (Milano - Berlino).</p>
 *
 * <p>Nessun {@code @QuarkusTest}: la classe sotto esame e' fatta di sole funzioni pure
 * sui campi gia' presenti in cache, quindi non serve avviare il contesto.</p>
 */
class PosizioneConvoglioTest {

    private static final long FRESCHEZZA = 15;

    /** Tratta TO -&gt; MI: e' l'ultima che i sensori hanno confermato per Smeraldo. */
    private static Tratta trattaToMi() {
        return tratta("TO", "MI");
    }

    private static Tratta tratta(String partenza, String arrivo) {
        Stazione da = new Stazione();
        da.id = partenza;
        Stazione a = new Stazione();
        a.id = arrivo;
        Tratta tratta = new Tratta();
        tratta.id = partenza + "_" + arrivo;
        tratta.stazionePartenza = da;
        tratta.stazioneArrivo = a;
        return tratta;
    }

    /** Convoglio che ha appena trasmesso, con la tratta TO_MI come ultimo passaggio noto. */
    private static Treno smeraldo() {
        Treno treno = new Treno("Smeraldo");
        treno.ultimoAggiornamento = Instant.now();
        treno.posizioneAttualeTratta = trattaToMi();
        return treno;
    }

    @Test
    void fermoInStazioneLaPosizioneEsempreNota() {
        Treno treno = smeraldo();
        treno.stazioneCorrente = "MI";
        treno.prossimaStazione = "FI";
        treno.direzione = "andata";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertTrue(treno.posizioneNota);
        assertEquals("MI", treno.partenzaCorrente);
        assertEquals("FI", treno.arrivoCorrente);
        assertNull(treno.incertezzaPosizione);
    }

    @Test
    void inLineaConLaTrattaConfermataDaiSensoriLaPosizioneEnota() {
        // Sta percorrendo proprio TO -> MI: la tratta confermata e la telemetria dicono
        // la stessa cosa, quindi la partenza e' garantita.
        Treno treno = smeraldo();
        treno.stazioneCorrente = "";
        treno.prossimaStazione = "MI";
        treno.direzione = "andata";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertTrue(treno.posizioneNota);
        assertEquals("TO", treno.partenzaCorrente);
        assertEquals("MI", treno.arrivoCorrente);
    }

    @Test
    void inRitornoLaTrattaSileggeAlContrario() {
        // Stessa tratta TO_MI percorsa nel verso opposto: sta andando da MI verso TO.
        Treno treno = smeraldo();
        treno.stazioneCorrente = "";
        treno.prossimaStazione = "TO";
        treno.direzione = "ritorno";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertTrue(treno.posizioneNota);
        assertEquals("MI", treno.partenzaCorrente);
        assertEquals("TO", treno.arrivoCorrente);
    }

    @Test
    void conLaTrattaVecchiaLaPartenzaNonSiInventa() {
        // Il caso Milano - Berlino: il convoglio e' fra Torino e Berlino (corsa di
        // ritorno), ma l'ultimo passaggio rilevato e' ancora TO_MI perche' i nodi di
        // Torino e Berlino sono spenti. Letta al contrario quella tratta finirebbe a
        // Torino, non a Berlino: non conferma niente, e la partenza resta sconosciuta.
        Treno treno = smeraldo();
        treno.stazioneCorrente = "";
        treno.prossimaStazione = "BE";
        treno.direzione = "ritorno";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertFalse(treno.posizioneNota);
        assertNull(treno.partenzaCorrente, "la partenza non va dedotta da una tratta superata");
        assertEquals("BE", treno.arrivoCorrente, "l'arrivo lo dice la telemetria e resta valido");
        assertEquals(PosizioneConvoglio.PARTENZA_SCONOSCIUTA, treno.incertezzaPosizione);
    }

    @Test
    void appenaIlPassaggioArrivaLaPosizioneTornaNota() {
        // Stessa situazione di sopra, ma il nodo di Torino e' tornato su e il suo
        // passaggio ha aggiornato la tratta: l'incertezza si scioglie da sola.
        Treno treno = smeraldo();
        treno.stazioneCorrente = "";
        treno.prossimaStazione = "BE";
        treno.direzione = "ritorno";
        PosizioneConvoglio.valuta(treno, FRESCHEZZA);
        assertFalse(treno.posizioneNota);

        treno.posizioneAttualeTratta = tratta("BE", "TO"); // la tratta BE_TO, percorsa al ritorno
        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertTrue(treno.posizioneNota);
        assertEquals("TO", treno.partenzaCorrente);
        assertEquals("BE", treno.arrivoCorrente);
        assertNull(treno.incertezzaPosizione);
    }

    @Test
    void senzaTelemetriaRecenteNonSiGarantisceNemmenoLaDirezione() {
        Treno treno = smeraldo();
        treno.ultimoAggiornamento = Instant.now().minus(Duration.ofMinutes(5));
        treno.stazioneCorrente = "MI";
        treno.prossimaStazione = "FI";
        treno.direzione = "andata";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertFalse(treno.posizioneNota);
        assertNull(treno.partenzaCorrente);
        assertNull(treno.arrivoCorrente);
        assertNull(treno.direzioneCorrente);
        assertEquals(PosizioneConvoglio.NESSUNA_TELEMETRIA, treno.incertezzaPosizione);
    }

    @Test
    void convoglioMaiVistoNonHaPosizione() {
        // ultimoAggiornamento nullo: processo spento, in cache c'e' solo la riga del
        // database. E' il caso in cui la Centrale ha appena riavviato.
        Treno treno = new Treno("Smeraldo");
        treno.posizioneAttualeTratta = trattaToMi();

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertFalse(treno.posizioneNota);
        assertEquals(PosizioneConvoglio.NESSUNA_TELEMETRIA, treno.incertezzaPosizione);
    }

    @Test
    void inLineaSenzaProssimaFermataLaPosizioneEincerta() {
        Treno treno = smeraldo();
        treno.stazioneCorrente = "";
        treno.prossimaStazione = "";
        treno.direzione = "andata";

        PosizioneConvoglio.valuta(treno, FRESCHEZZA);

        assertFalse(treno.posizioneNota);
        assertEquals(PosizioneConvoglio.ARRIVO_SCONOSCIUTO, treno.incertezzaPosizione);
    }
}
