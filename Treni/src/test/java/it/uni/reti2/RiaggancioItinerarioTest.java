package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test del riaggancio all'itinerario modificato a viaggio iniziato (RF02.5.2):
 * il convoglio ancora assegnato deve restare dove si trova e proseguire sulle
 * fermate nuove, non ricominciare dal capolinea.
 *
 * Gestore e automi vengono costruiti a mano (come fa il main) e gli si passa un
 * TrainDB finto, così il test non tocca né il broker MQTT né la Centrale. L'itinerario
 * arriva come arriverebbe dal GeneratoreEventiTempo, cioè come evento ITINERARIO_CARICATO
 * (un riaggancio in stazione non pubblica nessun passaggio, quindi i pubblicatori non servono).
 */
public class RiaggancioItinerarioTest {

    private GestoreEventi gestore;
    private AutomaViaggio viaggio;
    private TrainDB db;

    /** Itinerario di partenza: S1 -> S2 -> S3 -> S4, treno fermo a S3 in andata. */
    @BeforeEach
    public void preparaTrenoInMarcia() {
        db = new TrainDB();
        viaggio = new AutomaViaggio();
        viaggio.trainDB = db;
        viaggio.sostaSecondi = 20;
        AutomaCondizione condizione = new AutomaCondizione();
        condizione.trainDB = db;
        AutomaStazioniNote stazioni = new AutomaStazioniNote();
        stazioni.trainDB = db;
        gestore = new GestoreEventi();
        gestore.trainDB = db;
        gestore.viaggio = viaggio;
        gestore.condizione = condizione;
        gestore.stazioni = stazioni;

        List<TrainDB.TappaItinerario> vecchio = itinerario("S1", "S2", "S3", "S4");
        db.itinerario = vecchio;
        db.stazioniTratta = idDi(vecchio);
        db.itinerarioId = "IT_VECCHIO";
        db.indiceStazione = 2;          // fermo a S3
        db.stazioneCorrente = "S3";
        db.prossimaStazione = "S4";
        db.direzione = "andata";
        db.faseViaggio = "IN_STAZIONE";
        db.passeggeri = 123;
    }

    /**
     * Caso principale: l'amministratore toglie S1 e aggiunge S5 in fondo. Il treno è
     * fermo a S3, che nel percorso nuovo è la seconda fermata: deve ritrovarsi lì e non
     * all'indice 0, conservando i passeggeri già a bordo.
     */
    @Test
    public void ilTrenoRestaNellaStazioneInCuiSiTrova() {
        carica(new Itinerario("IT_NUOVO", itinerario("S2", "S3", "S4", "S5"), null, Map.of()));

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
     * punto a cui agganciarsi: si riparte dalla prima tappa del percorso nuovo, subito, senza
     * passare un giro con l'itinerario nuovo e l'indice di quello vecchio.
     */
    @Test
    public void senzaLaStazioneCorrenteSiRiparteDallaPrimaTappa() {
        carica(new Itinerario("IT_ALTROVE", itinerario("S7", "S8", "S9"), null, Map.of()));

        Assertions.assertEquals("IT_ALTROVE", db.itinerarioId);
        Assertions.assertEquals(3, db.itinerario.size());
        Assertions.assertEquals(0, db.indiceStazione);
        Assertions.assertEquals("S7", db.stazioneCorrente);
        Assertions.assertEquals("S8", db.prossimaStazione);
        Assertions.assertEquals("andata", db.direzione);
        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio);
    }

    /**
     * Se la stazione in cui il treno si trova è diventata il capolinea del percorso nuovo
     * la direzione va invertita, se no la prossima fermata sarebbe null e il treno non
     * saprebbe più dove andare.
     */
    @Test
    public void alCapolineaNuovoLaDirezioneSiInverte() {
        carica(new Itinerario("IT_ACCORCIATO", itinerario("S1", "S2", "S3"), null, Map.of()));

        Assertions.assertEquals(2, db.indiceStazione);
        Assertions.assertEquals("ritorno", db.direzione);
        Assertions.assertEquals("S2", db.prossimaStazione);
    }

    /**
     * Treno in mezzo alla tratta S3 -> S4: non può teletrasportarsi, quindi finisce la tratta
     * sul percorso vecchio e il nuovo resta in attesa (lo installa l'arrivo in stazione).
     */
    @Test
    public void inMezzoAUnaTrattaIlPercorsoNuovoAspetta() {
        db.faseViaggio = "IN_VIAGGIO";
        db.stazioneCorrente = null;

        Itinerario nuovo = new Itinerario("IT_NUOVO", itinerario("S3", "S4", "S5"), null, Map.of());
        carica(nuovo);

        Assertions.assertEquals("IT_VECCHIO", db.itinerarioId, "l'itinerario in uso non cambia a metà tratta");
        Assertions.assertEquals(4, db.itinerario.size());
        Assertions.assertEquals("IN_VIAGGIO", db.faseViaggio);
        Assertions.assertEquals("S4", db.prossimaStazione);
        Assertions.assertSame(nuovo, viaggio.itinerarioPendente(), "il percorso nuovo aspetta l'arrivo");
    }

    /**
     * L'evento ITINERARIO_AGGIORNATO arriva a tutti i treni della tratta anche quando
     * l'amministratore ne ha solo agganciato un altro: se le fermate sono le stesse il
     * convoglio non deve essere rimesso in sosta.
     */
    @Test
    public void conLeStesseFermateIlViaggioProsegue() {
        carica(new Itinerario("IT_VECCHIO", itinerario("S1", "S2", "S3", "S4"), null, Map.of()));

        Assertions.assertEquals(2, db.indiceStazione, "nessun riaggancio: il treno era già a posto");
        Assertions.assertNull(db.tArrivoInStazione, "la sosta non doveva ripartire");
    }

    /**
     * Il treno ha chiuso la corsa al capolinea S3. L'amministratore toglie la marcatura e
     * risalva lo stesso identico itinerario: è l'unico modo che il convoglio ha per
     * ripartire, quindi qui il riaggancio deve avvenire anche se le fermate non sono
     * cambiate (il ramo "stesse fermate" da solo lo lascerebbe fermo per sempre).
     */
    @Test
    public void toglierIlCapolineaRimetteInServizioIlTreno() {
        db.capolineaId = "S3";
        db.faseViaggio = "FERMO_CAPOLINEA";
        db.prossimaStazione = null;

        carica(new Itinerario("IT_VECCHIO", itinerario("S1", "S2", "S3", "S4"), null, Map.of()));

        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio, "la corsa è ricominciata");
        Assertions.assertEquals("FERMO", db.stato());
        Assertions.assertEquals("S3", db.stazioneCorrente, "riparte da dove si era fermato");
        Assertions.assertEquals("S4", db.prossimaStazione);
        Assertions.assertNull(db.capolineaId);
    }

    /**
     * Stessa situazione, ma il capolinea resta dov'era: succede tutte le volte che
     * l'itinerario viene risalvato per altri motivi (o che ci si aggancia un altro
     * convoglio, visto che ITINERARIO_AGGIORNATO arriva a tutti). Un treno che aveva
     * finito il servizio non deve ripartire per una modifica che non lo riguarda.
     */
    @Test
    public void ilCapolineaConfermatoTieneFermoIlTreno() {
        db.capolineaId = "S3";
        db.faseViaggio = "FERMO_CAPOLINEA";
        db.prossimaStazione = null;

        carica(new Itinerario("IT_VECCHIO", itinerario("S1", "S2", "S3", "S4"), "S3", Map.of()));

        Assertions.assertEquals("FERMO_CAPOLINEA", db.faseViaggio, "la corsa era ed è finita");
        Assertions.assertEquals("CAPOLINEA", db.stato());
        Assertions.assertEquals("S3", db.stazioneCorrente);
        Assertions.assertNull(db.prossimaStazione, "al capolinea non c'è una prossima fermata");
    }

    /**
     * Il treno è trattenuto a S3 perché S4 è guasta. Arriva un percorso nuovo e nella stessa
     * risposta la Centrale dichiara S4 di nuovo ONLINE: il blocco va deciso con quello stato,
     * anche se nel TrainDB viene scritto solo dopo (è la guardia che guarda avanti).
     */
    @Test
    public void ilRiaggancioUsaGliStatiArrivatiInsiemeAlPercorso() {
        db.faseViaggio = "BLOCCATO_IN_STAZIONE";
        db.stazioneBloccante = "S4";
        db.statoStazioni.put("S4", "GUASTA");

        carica(new Itinerario("IT_NUOVO", itinerario("S2", "S3", "S4", "S5"), null, Map.of("S4", "ONLINE")));

        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio, "S4 è di nuovo percorribile");
        Assertions.assertNull(db.stazioneBloccante);
        Assertions.assertEquals("ONLINE", db.statoStazioni.get("S4"), "lo stato nuovo resta scritto a bordo");
    }

    /** Stesso caso, ma la Centrale dice che S4 è ancora chiusa: il treno resta trattenuto. */
    @Test
    public void ilRiaggancioTieneFermoIlTrenoSeLaStazioneEAncoraChiusa() {
        db.faseViaggio = "BLOCCATO_IN_STAZIONE";
        db.stazioneBloccante = "S4";
        db.statoStazioni.put("S4", "GUASTA");

        carica(new Itinerario("IT_NUOVO", itinerario("S2", "S3", "S4", "S5"), null, Map.of("S4", "MANUTENZIONE")));

        Assertions.assertEquals("BLOCCATO_IN_STAZIONE", db.faseViaggio);
        Assertions.assertEquals("S4", db.stazioneBloccante);
        Assertions.assertEquals(1, db.indiceStazione, "il riaggancio c'è stato comunque");
    }

    // ---------------------------------------------------------------- utilità

    /** Consegna l'itinerario al gestore come farebbe il GeneratoreEventiTempo. */
    private void carica(Itinerario itinerario) {
        gestore.gestisci(EventoTreno.itinerarioCaricato(itinerario));
    }

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
