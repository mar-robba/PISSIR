package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prova le cose che dipendono da come sono fatti gli automi del treno: la condizione del
 * convoglio (EMERGENZA, SOPPRESSO) e la fase del viaggio sono due variabili diverse, e
 * nessuna mossa di una può cancellare l'altra; e le azioni partono dopo le mosse, quindi
 * vedono lo stato già scritto.
 *
 * <p>Gestore, automi e pubblicatori sono costruiti a mano (come fa il main), e al
 * posto della coda MQTT ce n'e' una finta che si tiene i messaggi.</p>
 */
public class GestoreEventiTest {

    private TrainDB db;
    private AutomaViaggio viaggio;
    private GestoreEventi gestore;
    private final List<String> passaggi = new ArrayList<>();
    private final List<String> alert = new ArrayList<>();

    /** Itinerario S1 -> S5 e treno in marcia da S3 verso S4, in andata. */
    @BeforeEach
    public void preparaIlConvoglioInTratta() {
        db = new TrainDB();
        db.trenoId = "TRN001";
        db.itinerario = itinerario("S1", "S2", "S3", "S4", "S5");
        db.stazioniTratta = List.of("S1", "S2", "S3", "S4", "S5");
        db.itinerarioId = "IT1";
        db.indiceStazione = 2;
        db.direzione = "andata";
        db.faseViaggio = "IN_VIAGGIO";
        db.prossimaStazione = "S4";
        db.tInizioTratta = Instant.now();

        viaggio = new AutomaViaggio();
        viaggio.trainDB = db;
        viaggio.fattoreAccelerazione = 60; // un minuto simulato = un secondo reale
        viaggio.autostart = true;
        AutomaCondizione condizione = new AutomaCondizione();
        condizione.trainDB = db;
        AutomaStazioniNote stazioni = new AutomaStazioniNote();
        stazioni.trainDB = db;

        PubblicatorePassaggi pubblicatorePassaggi = new PubblicatorePassaggi();
        pubblicatorePassaggi.trainDB = db;
        pubblicatorePassaggi.coda = codaFinta(passaggi);
        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.trainDB = db;
        pubblicatoreAlert.coda = codaFinta(alert);

        gestore = new GestoreEventi();
        gestore.trainDB = db;
        gestore.viaggio = viaggio;
        gestore.condizione = condizione;
        gestore.stazioni = stazioni;
        gestore.pubblicatorePassaggi = pubblicatorePassaggi;
        gestore.pubblicatoreAlert = pubblicatoreAlert;
    }

    /**
     * Il treno in avaria a meta' tratta viene raggiunto dal guasto della stazione verso cui
     * sta andando (e' quello che fa il domino della Centrale). Il blocco cambia la fase, ma
     * l'EMERGENZA resta: quando la stazione riapre il convoglio non deve ripartire con il
     * suo allarme ancora aperto.
     */
    @Test
    public void ilBloccoDellaStazioneNonCancellaLEmergenza() {
        gestore.gestisci(EventoTreno.guastoDiBordo("freni"));
        Assertions.assertEquals("EMERGENZA", db.stato());
        Assertions.assertEquals(1, alert.size(), "la Centrale deve ricevere il guasto");

        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.GUASTO_STAZIONE, "S4", null));
        Assertions.assertEquals("BLOCCATO_IN_TRATTA", db.faseViaggio);
        Assertions.assertEquals("EMERGENZA", db.stato(), "il blocco non tocca la condizione");

        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.STATO_STAZIONE, "S4", "ONLINE"));
        Assertions.assertEquals("IN_VIAGGIO", db.faseViaggio, "la fase si riprende");
        Assertions.assertEquals("EMERGENZA", db.stato(), "ma il treno e' ancora rotto");

        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.GUASTO_BORDO_RISOLTO));
        Assertions.assertEquals("IN_VIAGGIO", db.stato(), "risolto il guasto riparte da dov'era");
    }

    /**
     * Un treno soppresso e poi sganciato dall'itinerario non deve rianimarsi se qualcuno lo
     * riassegna: la soppressione e' definitiva, e l'avvio sposta solo la fase.
     */
    @Test
    public void ilTrenoSoppressoNonRiparteConUnItinerarioNuovo() {
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.SOPPRESSIONE));
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.ITINERARIO_REVOCATO));
        Assertions.assertEquals("SENZA_ITINERARIO", db.faseViaggio);

        gestore.gestisci(EventoTreno.itinerarioCaricato(
                new Itinerario("IT2", itinerario("S1", "S2", "S3"), null, Map.of())));
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.TICK));

        Assertions.assertEquals("SENZA_ITINERARIO", db.faseViaggio, "nessun avvio per un treno soppresso");
        Assertions.assertEquals("SOPPRESSO", db.stato());
    }

    /**
     * Un guasto di bordo segnalato da un treno soppresso arriva alla Centrale, ma non lo fa
     * tornare in EMERGENZA: dalla soppressione non si esce.
     */
    @Test
    public void unGuastoDiBordoNonCancellaLaSoppressione() {
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.SOPPRESSIONE));
        gestore.gestisci(EventoTreno.guastoDiBordo("porte"));

        Assertions.assertEquals("SOPPRESSO", db.stato());
        Assertions.assertEquals(1, alert.size(), "l'avaria va comunque segnalata");
    }

    /**
     * Il convoglio al capolinea ha finito il servizio: se la stazione in cui e' fermo va in
     * guasto non c'e' niente da bloccare, e soprattutto la corsa deve restare chiusa (prima
     * il blocco lo rimetteva FERMO e il treno restava inchiodato anche togliendo il capolinea).
     */
    @Test
    public void alCapolineaIlGuastoDellaStazioneNonRiapreLaCorsa() {
        db.faseViaggio = "FERMO_CAPOLINEA";
        db.capolineaId = "S5";
        db.indiceStazione = 4;
        db.stazioneCorrente = "S5";
        db.prossimaStazione = null;

        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.GUASTO_STAZIONE, "S5", null));

        Assertions.assertEquals("FERMO_CAPOLINEA", db.faseViaggio);
        Assertions.assertEquals("CAPOLINEA", db.stato());
    }

    /**
     * L'itinerario cambia mentre il treno va da S3 a S4, e nel percorso nuovo S4 e' l'ultima
     * fermata: all'arrivo il treno si riaggancia li' e deve girarsi. La ENTRATA parte dopo
     * il riaggancio, quindi porta gia' la direzione nuova, come all'estremo di un percorso
     * normale.
     */
    @Test
    public void laEntrataPortaLaDirezioneDopoIlRiaggancio() {
        gestore.gestisci(EventoTreno.itinerarioCaricato(
                new Itinerario("IT_CORTO", itinerario("S1", "S2", "S3", "S4"), null, Map.of())));
        Assertions.assertEquals("IT1", db.itinerarioId, "a meta' tratta il percorso nuovo aspetta");

        db.tInizioTratta = Instant.now().minusSeconds(3600); // la tratta e' finita
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.TICK));

        Assertions.assertEquals("IT_CORTO", db.itinerarioId);
        Assertions.assertEquals("IN_STAZIONE", db.faseViaggio);
        Assertions.assertEquals("S4", db.stazioneCorrente);
        Assertions.assertEquals("ritorno", db.direzione);
        Assertions.assertEquals("S3", db.prossimaStazione);
        Assertions.assertEquals(1, passaggi.size());
        Assertions.assertTrue(passaggi.get(0).contains("\"tipo\":\"ENTRATA\""));
        Assertions.assertTrue(passaggi.get(0).contains("\"direzione\":\"ritorno\""),
                "il passaggio deve dire che qui il treno si e' girato: " + passaggi.get(0));
    }

    /** All'estremo di un percorso normale succede la stessa cosa, con la sosta doppia. */
    @Test
    public void allEstremoLaEntrataPortaLaDirezioneInvertita() {
        db.indiceStazione = 3;
        db.prossimaStazione = "S5";
        db.tInizioTratta = Instant.now().minusSeconds(3600);

        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.TICK));

        Assertions.assertEquals("S5", db.stazioneCorrente);
        Assertions.assertEquals("ritorno", db.direzione);
        Assertions.assertTrue(passaggi.get(0).contains("\"direzione\":\"ritorno\""));
    }

    /**
     * Il percorso nuovo passa due volte da S4: all'arrivo il treno va agganciato
     * all'occorrenza vicina alla stazione in cui e' arrivato (indice 3), non a quella vicina
     * alla stazione da cui era partito (indice 2).
     */
    @Test
    public void allArrivoIlRiaggancioParteDallaStazioneDiArrivo() {
        gestore.gestisci(EventoTreno.itinerarioCaricato(
                new Itinerario("IT_DOPPIO", itinerario("S1", "S4", "S2", "S3", "S4", "S5"), null, Map.of())));

        db.tInizioTratta = Instant.now().minusSeconds(3600); // la tratta e' finita
        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.TICK));

        Assertions.assertEquals("IT_DOPPIO", db.itinerarioId);
        Assertions.assertEquals(4, db.indiceStazione, "la S4 dopo la S3, non quella in cima");
        Assertions.assertEquals("S5", db.prossimaStazione);
    }

    // ---------------------------------------------------------------- utilita'

    /** Una coda che invece di pubblicare sul broker si tiene i messaggi nella lista. */
    private CodaPubblicazioni codaFinta(List<String> inviati) {
        return new CodaPubblicazioni() {
            @Override
            public void metti(String topic, String payload, int qos) {
                inviati.add(payload);
            }
        };
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
}
