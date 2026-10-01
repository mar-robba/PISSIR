package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class TelemetriaTest {

    /*
    il frame di telemetria porta, oltre allo stato riassunto, lo stato vero dei due automi:
    condizione (T6) e fase del viaggio (T5). Il caso e' quello del treno trattenuto a meta'
    tratta da una stazione guasta: lo stato e' FERMO come per un treno in sosta al binario,
    e senza gli altri due campi la web app lo mostrava "in stazione".
    Come in ExampleTest al posto della coda vera c'e' una coda finta, il broker non si tocca.
     */
    @Test
    public void ilFrameDichiaraCondizioneEFaseOltreAlloStato() {
        List<CodaPubblicazioni.MessaggioInUscita> inviati = new ArrayList<>();
        CodaPubblicazioni coda = new CodaPubblicazioni() {
            @Override
            public void metti(String topic, String payload, int qos) {
                inviati.add(new MessaggioInUscita(topic, payload, qos));
            }
        };
        TrainDB db = new TrainDB();
        db.trenoId = "test";
        db.trenoRiconosciuto = true;
        db.faseViaggio = "BLOCCATO_IN_TRATTA";
        db.prossimaStazione = "FI";
        TrainElab elab = new TrainElab();
        elab.trainDB = db;
        elab.coda = coda;

        elab.inviaTelemetria();

        Assertions.assertEquals(1, inviati.size());
        Assertions.assertEquals("railway/train/test/telemetry", inviati.get(0).topic());
        JsonObject frame = JsonParser.parseString(inviati.get(0).payload()).getAsJsonObject();
        Assertions.assertEquals("FERMO", frame.get("stato").getAsString());
        Assertions.assertEquals("REGOLARE", frame.get("condizione").getAsString());
        Assertions.assertEquals("BLOCCATO_IN_TRATTA", frame.get("faseViaggio").getAsString());
        Assertions.assertEquals("", frame.get("stazioneCorrente").getAsString());
    }
}
