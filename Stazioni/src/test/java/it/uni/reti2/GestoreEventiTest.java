package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Prova le cose che dipendono da come sono fatti gli automi della stazione: lo stato e la
 * causa del guasto (S10), i sensori muti (S7), i treni sui binari (S11), e che cosa finisce
 * nella coda verso la Centrale. In particolare le due correzioni: il ritorno dei sensori non
 * cancella un guasto che non è loro, e la stazione non reagisce all'eco dei propri allarmi.
 *
 * <p>Non gira niente: gestore, automi e pubblicatori sono costruiti a mano come nel main, il
 * db locale è un file SQLite in una cartella temporanea e il consumatore del buffer non
 * parte, quindi i messaggi per la Centrale restano nel LocalBuffer e si possono guardare.</p>
 */
public class GestoreEventiTest {

    private static final String STAZIONE = "STEST";
    private static final String SENSORE_IN = SensoreBinario.idPer(STAZIONE, 1, SensoreBinario.Tipo.INGRESSO);
    private static final String SENSORE_OUT = SensoreBinario.idPer(STAZIONE, 1, SensoreBinario.Tipo.USCITA);

    @TempDir
    Path cartella;

    private PersistenzaLocale persistenza;
    private DBLocale db;
    private LocalBuffer buffer;
    private GestoreEventi gestore;
    private StationGateway gateway;

    @BeforeEach
    public void accendiLaStazione() {
        accendi();
    }

    @AfterEach
    public void spegniLaStazione() {
        persistenza.chiudi();
    }

    /**
     * Il caso base del fail-stop: il sensore muto guasta la stazione con un allarme dei
     * sensori, e il suo ritorno la rimette ONLINE da sola, con il RESOLVED per la Centrale.
     */
    @Test
    public void ilSensoreMutoGuastaLaStazioneEIlSuoRitornoLaRimetteOnline() {
        faiTacere(SENSORE_IN);
        Assertions.assertEquals("GUASTA", db.getStato());
        Assertions.assertTrue(db.guastoDaSensori);
        JsonObject guasto = ultimoAlert();
        Assertions.assertEquals("GUASTO", testo(guasto, "tipoEvento"));
        Assertions.assertEquals("sensore_offline", testo(guasto, "tipoGuasto"));
        Assertions.assertEquals(1, persistenza.contaGuastiAperti());

        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        Assertions.assertEquals("ONLINE", db.getStato(), "tornato il battito la stazione rientra da sola");
        JsonObject ripristino = ultimoAlert();
        Assertions.assertEquals("RESOLVED", testo(ripristino, "tipoEvento"));
        Assertions.assertEquals("sensore_offline", testo(ripristino, "tipoGuasto"));
        Assertions.assertEquals(0, persistenza.contaGuastiAperti());
    }

    /** L'allarme del sensore muto parte una volta sola, non a ogni giro di controllo. */
    @Test
    public void ilSensoreMutoVieneSegnalatoUnaVoltaSola() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.CONTROLLO_SENSORI));
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.CONTROLLO_SENSORI));
        Assertions.assertEquals(1, alertInCoda().size());
    }

    /**
     * La prima correzione. La stazione è guasta per un sensore muto, e intanto si rompe uno
     * scambio: quando il sensore torna a battere si chiude l'allarme del sensore, ma la
     * stazione resta GUASTA, perché lo scambio è ancora rotto. Prima tornava ONLINE e chiudeva
     * anche il guasto dello scambio.
     */
    @Test
    public void unGuastoSegnalatoNonVieneCancellatoDalRitornoDeiSensori() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.guastoRilevato("scambio bloccato", "binario_guasto"));
        Assertions.assertFalse(db.guastoDaSensori, "adesso la stazione non e' guasta per i soli sensori");

        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        Assertions.assertEquals("GUASTA", db.getStato(), "lo scambio e' ancora rotto");
        JsonObject ripristino = ultimoAlert();
        Assertions.assertEquals("RESOLVED", testo(ripristino, "tipoEvento"), "l'allarme del sensore va chiuso lo stesso");
        Assertions.assertEquals("sensore_offline", testo(ripristino, "tipoGuasto"));
        Assertions.assertEquals(1, persistenza.contaGuastiAperti(), "resta aperto solo il guasto dello scambio");

        // a rimetterla in servizio e' la Centrale
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.RIPRISTINO_DALLA_CENTRALE));
        Assertions.assertEquals("ONLINE", db.getStato());
        Assertions.assertEquals(0, persistenza.contaGuastiAperti());
    }

    /** La stessa correzione al contrario: guasta per altro, poi un sensore tace e torna. */
    @Test
    public void ilSensoreCheTaceETornaNonRimetteOnlineUnaStazioneGuastaPerAltro() {
        gestore.gestisci(EventoStazione.guastoRilevato("alimentazione assente", null));
        faiTacere(SENSORE_IN);
        Assertions.assertFalse(db.guastoDaSensori, "la stazione era gia' guasta per un altro motivo");

        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        Assertions.assertEquals("GUASTA", db.getStato());
    }

    /** Il guasto dichiarato dalla Centrale (RF02.1.2.2.1) non lo cancella il ritorno dei sensori. */
    @Test
    public void ilGuastoDellaCentraleNonVieneCancellatoDalRitornoDeiSensori() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.guastoDallaCentrale("Convoglio TRN1 guasto sui binari"));

        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        Assertions.assertEquals("GUASTA", db.getStato());
    }

    /**
     * La causa del guasto sopravvive al riavvio. La stazione è guasta per un sensore muto e
     * trattiene un treno; si spegne e si riaccende: riparte GUASTA per i soli sensori (il
     * treno trattenuto non è un guasto nuovo), con il treno ancora sui binari, e quando i
     * sensori tornano a battere rientra da sola.
     */
    @Test
    public void alRiavvioLaStazioneGuastaPerISensoriRientraDaSola() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.transito("TRN1", "ENTRATA"));

        riavvia();
        Assertions.assertEquals("GUASTA", db.getStato());
        Assertions.assertTrue(db.guastoDaSensori, "i guasti aperti sono solo dei sensori");
        Assertions.assertTrue(db.getTreniPresenti().containsKey("TRN1"), "il treno e' ancora sui binari");

        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        Assertions.assertEquals("GUASTA", db.getStato(), "manca ancora il battito dell'altro sensore");
        gestore.gestisci(EventoStazione.battito(SENSORE_OUT));
        Assertions.assertEquals("ONLINE", db.getStato());
    }

    /**
     * Se al riavvio c'è aperto anche un guasto che non è dei sensori, la stazione aspetta la
     * Centrale; il ritorno dei sensori chiude comunque il loro allarme.
     */
    @Test
    public void alRiavvioUnGuastoNonDeiSensoriResta() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.guastoRilevato("scambio bloccato", "binario_guasto"));

        riavvia();
        Assertions.assertFalse(db.guastoDaSensori);
        gestore.gestisci(EventoStazione.battito(SENSORE_IN));
        gestore.gestisci(EventoStazione.battito(SENSORE_OUT));
        Assertions.assertEquals("GUASTA", db.getStato());
        Assertions.assertEquals("RESOLVED", testo(ultimoAlert(), "tipoEvento"), "l'allarme dei sensori si chiude lo stesso");
        Assertions.assertEquals(1, persistenza.contaGuastiAperti(), "resta aperto solo lo scambio");
    }

    /**
     * Un treno che entra in una stazione guasta viene trattenuto: in coda finiscono il transito
     * e l'allarme, ma il registro dei guasti non si allunga.
     */
    @Test
    public void ilTrenoCheEntraInUnaStazioneGuastaVieneTrattenuto() {
        gestore.gestisci(EventoStazione.guastoRilevato("alimentazione assente", null));
        int guastiPrima = persistenza.contaGuastiAperti();
        int eventiPrima = buffer.size();

        gestore.gestisci(EventoStazione.transito("TRN1", "ENTRATA"));
        Assertions.assertTrue(db.getTreniPresenti().containsKey("TRN1"));
        List<LocalBuffer.EventoBufferizzato> nuovi = buffer.getBuffer().subList(eventiPrima, buffer.size());
        Assertions.assertEquals(2, nuovi.size());
        Assertions.assertEquals("transit", nuovi.get(0).canale());
        Assertions.assertEquals("TRN1", nuovi.get(0).idTreno());
        Assertions.assertEquals("ENTRATA", testo(json(nuovi.get(0)), "tipo"));
        Assertions.assertEquals("alert", nuovi.get(1).canale());
        Assertions.assertEquals("Stazione STEST guasta: treno TRN1 trattenuto", testo(json(nuovi.get(1)), "messaggio"));
        Assertions.assertEquals(guastiPrima, persistenza.contaGuastiAperti(), "il treno trattenuto non e' un guasto nuovo");

        gestore.gestisci(EventoStazione.transito("TRN1", "USCITA"));
        Assertions.assertFalse(db.getTreniPresenti().containsKey("TRN1"));
    }

    /**
     * Il RESOLVED della Centrale rimette in servizio la stazione e azzera i sensori muti: se
     * uno tace ancora davvero, al giro dopo la stazione si ridichiara GUASTA.
     */
    @Test
    public void ilRipristinoDellaCentraleRicominciaASorvegliareISensori() {
        faiTacere(SENSORE_IN);
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.RIPRISTINO_DALLA_CENTRALE));
        Assertions.assertEquals("ONLINE", db.getStato());
        Assertions.assertTrue(db.sensoriGuasti.isEmpty());

        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.CONTROLLO_SENSORI));
        Assertions.assertEquals("GUASTA", db.getStato(), "il sensore tace ancora");
    }

    /**
     * La seconda correzione: la stazione si risente i propri GUASTO e RESOLVED sul topic
     * condiviso, e non deve reagire. Reagisce solo a quelli firmati dalla Centrale.
     */
    @Test
    public void laStazioneNonReagisceAllEcoDeiPropriAllarmi() {
        gestore.gestisci(EventoStazione.guastoRilevato("alimentazione assente", null));

        gateway.riceviAlert("{\"tipoEvento\":\"RESOLVED\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"STEST\","
                + "\"tipoGuasto\":\"sensore_offline\",\"messaggio\":\"Keepalive dei sensori di binario ripristinato\"}");
        Assertions.assertEquals("GUASTA", db.getStato(), "l'eco di un proprio RESOLVED non conta");

        gateway.riceviAlert("{\"tipoEvento\":\"RESOLVED\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"ALTRA\"}");
        Assertions.assertEquals("GUASTA", db.getStato(), "il RESOLVED di un'altra stazione non conta");

        gateway.riceviAlert("{\"tipoEvento\":\"RESOLVED\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"STEST\"}");
        Assertions.assertEquals("ONLINE", db.getStato(), "quello della Centrale si'");

        gateway.riceviAlert("{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"STEST\",\"severita\":\"CRITICAL\"}");
        Assertions.assertEquals("ONLINE", db.getStato(), "l'eco di un proprio GUASTO non conta");

        gateway.riceviAlert("{\"tipoEvento\":\"GUASTO\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"STEST\","
                + "\"severita\":\"CRITICAL\",\"messaggio\":\"Convoglio TRN1 guasto sui binari\"}");
        Assertions.assertEquals("GUASTA", db.getStato(), "il domino della Centrale si'");
    }

    /** Le virgolette in una descrizione non rompono il JSON che parte verso la Centrale. */
    @Test
    public void unaDescrizioneConLeVirgoletteNonRompeLAllarme() {
        gestore.gestisci(EventoStazione.guastoRilevato("scambio \"3\" bloccato all'ingresso", null));
        Assertions.assertEquals("scambio \"3\" bloccato all'ingresso", testo(ultimoAlert(), "messaggio"));
    }

    // ============================== appoggi

    /** Monta la stazione come il main, sul db locale della cartella di prova. */
    private void accendi() {
        persistenza = new PersistenzaLocale();
        persistenza.percorsoDb = cartella.resolve("stazione.db").toString();
        persistenza.abilitata = true;
        persistenza.apri();

        db = new DBLocale();
        db.persistenza = persistenza;
        db.stazioneId = STAZIONE;
        db.ripristinaDaDisco();
        buffer = new LocalBuffer();
        buffer.persistenza = persistenza;
        buffer.ripristinaDaDisco();

        AutomaStatoStazione statoStazione = new AutomaStatoStazione();
        statoStazione.dbLocale = db;
        statoStazione.persistenza = persistenza;
        AutomaSensori sensori = new AutomaSensori();
        sensori.dbLocale = db;
        sensori.timeoutSecondi = 30;
        AutomaTreniPresenti treni = new AutomaTreniPresenti();
        treni.dbLocale = db;
        AutomaConnessione connessione = new AutomaConnessione();
        connessione.dbLocale = db;

        List<String> attesi = List.of(SENSORE_IN, SENSORE_OUT);
        sensori.sorveglia(attesi, Instant.now());
        statoStazione.ripristinaCausa();
        if (statoStazione.guasta() && statoStazione.guastiDeiSensoriAperti()) {
            sensori.segnaMuti(attesi);
        }

        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.dbLocale = db;
        pubblicatoreAlert.localBuffer = buffer;
        pubblicatoreAlert.connessione = connessione;
        PubblicatoreTransiti pubblicatoreTransiti = new PubblicatoreTransiti();
        pubblicatoreTransiti.dbLocale = db;
        pubblicatoreTransiti.localBuffer = buffer;
        pubblicatoreTransiti.connessione = connessione;

        gestore = new GestoreEventi();
        gestore.dbLocale = db;
        gestore.statoStazione = statoStazione;
        gestore.sensori = sensori;
        gestore.treni = treni;
        gestore.connessione = connessione;
        gestore.pubblicatoreAlert = pubblicatoreAlert;
        gestore.pubblicatoreTransiti = pubblicatoreTransiti;

        gateway = new StationGateway();
        gateway.dbLocale = db;
        gateway.gestore = gestore;
    }

    /** Spegne e riaccende la stazione: resta solo quello che c'è sul db locale. */
    private void riavvia() {
        persistenza.chiudi();
        accendi();
    }

    /** Il sensore non batte da un minuto (il timeout è di 30 secondi) e passa un giro di controllo. */
    private void faiTacere(String sensoreId) {
        db.sensoriUltimoBattito.put(sensoreId, Instant.now().minusSeconds(60));
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.CONTROLLO_SENSORI));
    }

    private List<JsonObject> alertInCoda() {
        List<JsonObject> alert = new ArrayList<>();
        for (LocalBuffer.EventoBufferizzato evento : buffer.getBuffer()) {
            if ("alert".equals(evento.canale())) {
                alert.add(json(evento));
            }
        }
        return alert;
    }

    private JsonObject ultimoAlert() {
        List<JsonObject> alert = alertInCoda();
        Assertions.assertFalse(alert.isEmpty(), "nessun allarme in coda");
        return alert.get(alert.size() - 1);
    }

    private static JsonObject json(LocalBuffer.EventoBufferizzato evento) {
        return JsonParser.parseString(evento.payload()).getAsJsonObject();
    }

    private static String testo(JsonObject json, String campo) {
        return CampiJson.testo(json, campo, null);
    }
}
