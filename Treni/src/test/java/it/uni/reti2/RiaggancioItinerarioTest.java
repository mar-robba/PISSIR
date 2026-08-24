package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Test del riaggancio all'itinerario modificato a viaggio iniziato (RF02.5.2):
 * il convoglio ancora assegnato deve restare dove si trova e proseguire sulle
 * fermate nuove, non ricominciare dal capolinea.
 *
 * Non è un @QuarkusTest: il motore viene costruito a mano e gli si passa un TrainDB
 * finto, così il test non tocca né il broker MQTT né la Centrale (i metodi provati
 * lavorano solo sullo stato in memoria, non pubblicano nessun passaggio).
 */
public class RiaggancioItinerarioTest {

    private TrainJourneyEngine motore;
    private TrainDB db;

    /** Itinerario di partenza: S1 -> S2 -> S3 -> S4, treno fermo a S3 in andata. */
    @BeforeEach
    public void preparaTrenoInMarcia() {
        db = new TrainDB();
        motore = new TrainJourneyEngine();
        motore.trainDB = db;
        motore.sostaSecondi = 20;

        List<TrainDB.TappaItinerario> vecchio = itinerario("S1", "S2", "S3", "S4");
        db.itinerario = vecchio;
        db.stazioniTratta = idDi(vecchio);
        db.itinerarioId = "IT_VECCHIO";
        db.indiceStazione = 2;          // fermo a S3
        db.stazioneCorrente = "S3";
        db.prossimaStazione = "S4";
        db.direzione = "andata";
        db.faseViaggio = "IN_STAZIONE";
        db.stato = "FERMO";
        db.passeggeri = 123;
        motore.viaggioAvviato = true;
    }

    /**
     * Caso principale: l'amministratore toglie S1 e aggiunge S5 in fondo. Il treno è
     * fermo a S3, che nel percorso nuovo è la seconda fermata: deve ritrovarsi lì e non
     * all'indice 0, conservando i passeggeri già a bordo.
     */
    @Test
    public void ilTrenoRestaNellaStazioneInCuiSiTrova() {
        List<TrainDB.TappaItinerario> nuovo = itinerario("S2", "S3", "S4", "S5");
        motore.applicaItinerario("IT_NUOVO", nuovo, idDi(nuovo));

        Assertions.assertTrue(motore.viaggioAvviato, "il viaggio non doveva essere azzerato");
        Assertions.assertEquals("IT_NUOVO", db.itinerarioId);
        Assertions.assertEquals(1, db.indiceStazione, "S3 è la seconda fermata del percorso nuovo");
        Assertions.assertEquals("S3", db.stazioneCorrente);
        Assertions.assertEquals("S4", db.prossimaStazione);
        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio);
        Assertions.assertEquals("andata", db.direzione);
        // Il ritardo non si controlla più qui: non è più un dato del convoglio, lo tiene
        // la Centrale (PrevisioneArrivi) confrontando i passaggi con l'orario previsto.
        Assertions.assertEquals(123, db.passeggeri, "i passeggeri a bordo sono del convoglio, non dell'itinerario");
    }

    /**
     * Se la stazione in cui il treno si trova non fa più parte del percorso non c'è nessun
     * punto a cui agganciarsi: si torna al comportamento di prima, cioè partenza dal capolinea.
     */
    @Test
    public void senzaLaStazioneCorrenteSiRicominciaDalCapolinea() {
        List<TrainDB.TappaItinerario> nuovo = itinerario("S7", "S8", "S9");
        motore.applicaItinerario("IT_ALTROVE", nuovo, idDi(nuovo));

        Assertions.assertFalse(motore.viaggioAvviato, "il tick successivo deve rifare avviaViaggio()");
        Assertions.assertEquals("IT_ALTROVE", db.itinerarioId);
        Assertions.assertEquals(3, db.itinerario.size());
    }

    /**
     * Se la stazione in cui il treno si trova è diventata il capolinea del percorso nuovo
     * la direzione va invertita, se no la prossima fermata sarebbe null e il viaggio finirebbe.
     */
    @Test
    public void alCapolineaNuovoLaDirezioneSiInverte() {
        List<TrainDB.TappaItinerario> nuovo = itinerario("S1", "S2", "S3");
        motore.applicaItinerario("IT_ACCORCIATO", nuovo, idDi(nuovo));

        Assertions.assertEquals(2, db.indiceStazione);
        Assertions.assertEquals("ritorno", db.direzione);
        Assertions.assertEquals("S2", db.prossimaStazione);
    }

    /**
     * Treno in mezzo alla tratta S3 -> S4: non può teletrasportarsi, quindi finisce la tratta
     * sul percorso vecchio e il nuovo resta in attesa (lo installa arrivoInStazione).
     */
    @Test
    public void inMezzoAUnaTrattaIlPercorsoNuovoAspetta() {
        db.faseViaggio = "IN_VIAGGIO";
        db.stato = "IN_VIAGGIO";

        List<TrainDB.TappaItinerario> nuovo = itinerario("S3", "S4", "S5");
        motore.applicaItinerario("IT_NUOVO", nuovo, idDi(nuovo));

        Assertions.assertEquals("IT_VECCHIO", db.itinerarioId, "l'itinerario in uso non cambia a metà tratta");
        Assertions.assertEquals(4, db.itinerario.size());
        Assertions.assertEquals("IN_VIAGGIO", db.faseViaggio);
        Assertions.assertEquals("S4", db.prossimaStazione);
    }

    /**
     * L'evento ITINERARIO_AGGIORNATO arriva a tutti i treni della tratta anche quando
     * l'amministratore ne ha solo agganciato un altro: se le fermate sono le stesse il
     * convoglio non deve essere rimesso in sosta.
     */
    @Test
    public void conLeStesseFermateIlViaggioProsegue() {
        List<TrainDB.TappaItinerario> stesse = itinerario("S1", "S2", "S3", "S4");
        motore.applicaItinerario("IT_VECCHIO", stesse, idDi(stesse));

        Assertions.assertEquals(2, db.indiceStazione, "nessun riaggancio: il treno era già a posto");
        Assertions.assertNull(db.tArrivoInStazione, "la sosta non doveva ripartire");
    }

    // ---------------------------------------------------------------- utilità

    /** Costruisce un itinerario finto con coordinate e tempi qualsiasi ma validi. */
    private List<TrainDB.TappaItinerario> itinerario(String... stazioni) {
        List<TrainDB.TappaItinerario> tappe = new ArrayList<>();
        for (int i = 0; i < stazioni.length; i++) {
            tappe.add(new TrainDB.TappaItinerario(stazioni[i], "Stazione " + stazioni[i],
                    45.0 + i, 9.0 + i, 10));
        }
        return tappe;
    }

    /** Gli id delle tappe, come li vuole TrainDB.stazioniTratta. */
    private List<String> idDi(List<TrainDB.TappaItinerario> tappe) {
        List<String> ids = new ArrayList<>();
        for (TrainDB.TappaItinerario tappa : tappe) {
            ids.add(tappa.id);
        }
        return ids;
    }
}
