package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class ExampleTest {

    /*
    invio di un allert dal treno e controllo di come arriva alla coda MQTT
    Nota: il test non tocca il broker. Al posto di CodaPubblicazioni c'e' una coda finta
    che si tiene i messaggi, cosi' si controlla topic, QoS e formato senza che l'alert di
    prova arrivi alla Centrale accesa.

    Il payload deve rispettare il formato JSON atteso da IngestionService.onAlert della
    Centrale (vedi PubblicatoreAlert.inviaGuasto): una stringa non-JSON come "ALERT: test"
    verrebbe scartata con un errore di parsing lato Centrale.
     */
    @Test
    public void invioDiUnAllertAdUnTreno() {
        List<CodaPubblicazioni.MessaggioInUscita> inviati = new ArrayList<>();
        CodaPubblicazioni coda = new CodaPubblicazioni() {
            @Override
            public void metti(String topic, String payload, int qos) {
                inviati.add(new MessaggioInUscita(topic, payload, qos));
            }
        };
        TrainDB db = new TrainDB();
        db.trenoId = "test";
        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.trainDB = db;
        pubblicatoreAlert.coda = coda;

        pubblicatoreAlert.inviaGuasto("alert di test", "WARNING");

        Assertions.assertEquals(1, inviati.size());
        CodaPubblicazioni.MessaggioInUscita messaggio = inviati.get(0);
        Assertions.assertEquals("railway/alerts", messaggio.topic());
        Assertions.assertEquals(1, messaggio.qos(), "un guasto non si puo' perdere: QoS 1");
        Assertions.assertTrue(messaggio.payload().startsWith(
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"TRENO\",\"sorgenteId\":\"test\","
                        + "\"severita\":\"WARNING\",\"messaggio\":\"alert di test\""));
    }

    /*
    la descrizione e' testo libero (sensore o console): con le virgolette dentro il payload
    deve restare un JSON valido, se no la Centrale lo scarta e l'allarme non si apre mai
    mentre il treno resta in EMERGENZA
     */
    @Test
    public void descrizioneConVirgoletteRestaUnJsonValido() {
        List<CodaPubblicazioni.MessaggioInUscita> inviati = new ArrayList<>();
        CodaPubblicazioni coda = new CodaPubblicazioni() {
            @Override
            public void metti(String topic, String payload, int qos) {
                inviati.add(new MessaggioInUscita(topic, payload, qos));
            }
        };
        TrainDB db = new TrainDB();
        db.trenoId = "test";
        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.trainDB = db;
        pubblicatoreAlert.coda = coda;

        String descrizione = "Freno \"bloccato\" sul carrello \\2";
        pubblicatoreAlert.inviaGuasto(descrizione, "CRITICAL");

        Assertions.assertEquals(1, inviati.size());
        JsonObject alert = JsonParser.parseString(inviati.get(0).payload()).getAsJsonObject();
        Assertions.assertEquals(descrizione, alert.get("messaggio").getAsString());
        Assertions.assertEquals("test", alert.get("sorgenteId").getAsString());
    }

}
