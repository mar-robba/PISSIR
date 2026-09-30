package it.uni.reti2.ingestion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.AlertMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Prova il formato dei messaggi che la Centrale pubblica sul topic condiviso
 * railway/alerts, a partire dall'alert RITARDO: quello con cui dice al convoglio quanti
 * minuti di ritardo gli ha misurato.
 *
 * <p>Il formato non e' un dettaglio interno: dall'altra parte del topic c'e' il listener
 * del treno ({@code TrainGateway.riceviAlert}), che cerca esattamente questi campi. Se
 * qui cambia un nome, a bordo il ritardo resta a zero senza che nessuno se ne accorga,
 * perche' un alert non riconosciuto viene semplicemente ignorato.</p>
 *
 * <p>Non serve accendere la Centrale: il pubblicatore viene costruito a mano con una coda
 * finta, cosi' il test non pubblica niente sul broker vero. Prima per arrivare a questi tre
 * metodi bisognava istanziare l'intero servizio di ingestione.</p>
 */
public class AlertRitardoTest {

    private PubblicatoreAlert pubblicatore;
    private List<String> pubblicati;

    @BeforeEach
    public void preparaLaCentrale() {
        pubblicati = new ArrayList<>();
        pubblicatore = new PubblicatoreAlert();
        pubblicatore.coda = new CodaFinta(pubblicati);
    }

    /** L'alert dell'ingresso in ritardo porta target, minuti, variazione e causa "in linea". */
    @Test
    public void ilRitardoMaturatoInLineaViaggiaVersoIlTreno() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 7;

        pubblicatore.pubblicaRitardo(treno, "S2", 4, true);

        Assertions.assertEquals(1, pubblicati.size(), "doveva partire un alert solo");
        JsonObject alert = JsonParser.parseString(pubblicati.get(0)).getAsJsonObject();
        Assertions.assertEquals("RITARDO", alert.get("tipoEvento").getAsString());
        Assertions.assertEquals("TRN001", alert.get("target").getAsString(), "il destinatario e' il convoglio");
        Assertions.assertEquals("S2", alert.get("sorgenteId").getAsString(), "la misura e' del passaggio a S2");
        Assertions.assertEquals(7, alert.get("ritardoMinuti").getAsInt());
        Assertions.assertEquals(4, alert.get("variazioneMinuti").getAsInt());
        Assertions.assertEquals(PubblicatoreAlert.CAUSA_RITARDO_IN_LINEA, alert.get("causa").getAsString());
        Assertions.assertEquals("WARNING", alert.get("severita").getAsString());
        // Senza il marcatore la Centrale si riascolterebbe da sola sul topic condiviso.
        Assertions.assertEquals(AlertMsg.ORIGINE_CENTRALE, alert.get("origine").getAsString());
    }

    /** L'uscita tardiva dichiara l'altra causa: il tempo perso fermi in banchina. */
    @Test
    public void laSostaProlungataSiDichiaraComeTale() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 12;

        pubblicatore.pubblicaRitardo(treno, "S2", 12, false);

        JsonObject alert = JsonParser.parseString(pubblicati.get(0)).getAsJsonObject();
        Assertions.assertEquals(PubblicatoreAlert.CAUSA_SOSTA_PROLUNGATA, alert.get("causa").getAsString());
    }

    /** Un convoglio tornato in orario non e' un allarme: la severita' scende a INFO. */
    @Test
    public void ilRientroInOrarioNonEUnAllarme() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 0;

        pubblicatore.pubblicaRitardo(treno, "S3", -5, true);

        JsonObject alert = JsonParser.parseString(pubblicati.get(0)).getAsJsonObject();
        Assertions.assertEquals("INFO", alert.get("severita").getAsString());
        Assertions.assertEquals(0, alert.get("ritardoMinuti").getAsInt());
        Assertions.assertEquals(-5, alert.get("variazioneMinuti").getAsInt());
    }

    /**
     * Anche il RESOLVED porta il marcatore della Centrale.
     *
     * <p>E' il difetto che ha fatto nascere questa classe: la versione che pubblicavano le
     * API REST componeva il JSON a mano e il campo {@code origine} non c'era. Siccome la
     * Centrale e' sottoscritta al proprio stesso topic, quel messaggio le rientrava in
     * casa e veniva scambiato per una dichiarazione del campo, con il rischio di chiudere
     * un secondo guasto che nessuno aveva chiesto di chiudere.</p>
     */
    @Test
    public void laRisoluzioneEFirmataDallaCentrale() throws Exception {
        Guasto guasto = new Guasto();
        guasto.id = "alert-1";
        guasto.sorgenteTipo = "STAZIONE";
        guasto.sorgenteId = "S2";

        pubblicatore.pubblicaRisoluzione(guasto);

        JsonObject alert = JsonParser.parseString(pubblicati.get(0)).getAsJsonObject();
        Assertions.assertEquals("RESOLVED", alert.get("tipoEvento").getAsString());
        Assertions.assertEquals(AlertMsg.ORIGINE_CENTRALE, alert.get("origine").getAsString(),
                "senza il marcatore la Centrale si riascolta da sola");
        Assertions.assertEquals("S2", alert.get("sorgenteId").getAsString());
        Assertions.assertEquals("alert-1", alert.get("guastoId").getAsString());
    }

    /** Coda che invece di pubblicare sul broker tiene da parte i payload. */
    private static final class CodaFinta extends CodaPubblicazioni {
        private final List<String> raccolti;

        private CodaFinta(List<String> raccolti) {
            this.raccolti = raccolti;
        }

        @Override
        public void metti(String topic, String payload, int qos) {
            raccolti.add(payload);
        }
    }
}
