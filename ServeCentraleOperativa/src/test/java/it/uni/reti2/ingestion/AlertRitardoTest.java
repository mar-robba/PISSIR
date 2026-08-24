package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.AlertMsg;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
 * <p>Non e' un @QuarkusTest: il pubblicatore viene costruito a mano con un emitter finto,
 * cosi' il test non pubblica niente sul broker vero. Prima per arrivare a questi tre
 * metodi bisognava istanziare l'intero servizio di ingestione.</p>
 */
public class AlertRitardoTest {

    private PubblicatoreAlert pubblicatore;
    private List<String> pubblicati;

    @BeforeEach
    public void preparaLaCentrale() {
        pubblicati = new ArrayList<>();
        pubblicatore = new PubblicatoreAlert();
        pubblicatore.mapper = new ObjectMapper();
        pubblicatore.alertsEmitter = new EmitterFinto(pubblicati);
    }

    /** L'alert dell'ingresso in ritardo porta target, minuti, variazione e causa "in linea". */
    @Test
    public void ilRitardoMaturatoInLineaViaggiaVersoIlTreno() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 7;

        pubblicatore.pubblicaRitardo(treno, "S2", 4, true);

        Assertions.assertEquals(1, pubblicati.size(), "doveva partire un alert solo");
        JsonNode alert = new ObjectMapper().readTree(pubblicati.get(0));
        Assertions.assertEquals("RITARDO", alert.path("tipoEvento").asText());
        Assertions.assertEquals("TRN001", alert.path("target").asText(), "il destinatario e' il convoglio");
        Assertions.assertEquals("S2", alert.path("sorgenteId").asText(), "la misura e' del passaggio a S2");
        Assertions.assertEquals(7, alert.path("ritardoMinuti").asInt());
        Assertions.assertEquals(4, alert.path("variazioneMinuti").asInt());
        Assertions.assertEquals(PubblicatoreAlert.CAUSA_RITARDO_IN_LINEA, alert.path("causa").asText());
        Assertions.assertEquals("WARNING", alert.path("severita").asText());
        // Senza il marcatore la Centrale si riascolterebbe da sola sul topic condiviso.
        Assertions.assertEquals(AlertMsg.ORIGINE_CENTRALE, alert.path("origine").asText());
    }

    /** L'uscita tardiva dichiara l'altra causa: il tempo perso fermi in banchina. */
    @Test
    public void laSostaProlungataSiDichiaraComeTale() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 12;

        pubblicatore.pubblicaRitardo(treno, "S2", 12, false);

        JsonNode alert = new ObjectMapper().readTree(pubblicati.get(0));
        Assertions.assertEquals(PubblicatoreAlert.CAUSA_SOSTA_PROLUNGATA, alert.path("causa").asText());
    }

    /** Un convoglio tornato in orario non e' un allarme: la severita' scende a INFO. */
    @Test
    public void ilRientroInOrarioNonEUnAllarme() throws Exception {
        Treno treno = new Treno();
        treno.id = "TRN001";
        treno.ritardo = 0;

        pubblicatore.pubblicaRitardo(treno, "S3", -5, true);

        JsonNode alert = new ObjectMapper().readTree(pubblicati.get(0));
        Assertions.assertEquals("INFO", alert.path("severita").asText());
        Assertions.assertEquals(0, alert.path("ritardoMinuti").asInt());
        Assertions.assertEquals(-5, alert.path("variazioneMinuti").asInt());
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

        JsonNode alert = new ObjectMapper().readTree(pubblicati.get(0));
        Assertions.assertEquals("RESOLVED", alert.path("tipoEvento").asText());
        Assertions.assertEquals(AlertMsg.ORIGINE_CENTRALE, alert.path("origine").asText(),
                "senza il marcatore la Centrale si riascolta da sola");
        Assertions.assertEquals("S2", alert.path("sorgenteId").asText());
        Assertions.assertEquals("alert-1", alert.path("guastoId").asText());
    }

    /** Emitter che invece di pubblicare sul broker tiene da parte i payload. */
    private static final class EmitterFinto implements Emitter<String> {
        private final List<String> raccolti;

        private EmitterFinto(List<String> raccolti) {
            this.raccolti = raccolti;
        }

        @Override
        public CompletionStage<Void> send(String msg) {
            raccolti.add(msg);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends String>> void send(M msg) {
            raccolti.add(msg.getPayload());
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Exception e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean hasRequests() {
            return true;
        }
    }
}
