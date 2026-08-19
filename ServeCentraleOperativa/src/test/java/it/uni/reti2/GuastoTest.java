package it.uni.reti2;

import it.uni.reti2.entity.Guasto;
import it.uni.reti2.ingestion.IngestionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GuastoTest {

    @Test
    public void testGuastoFields() {
        Guasto g = new Guasto();
        g.id = "G-TEST-1";
        g.risolto = false;
        g.tipo = "TRAIN";
        g.messaggio = "Test message";

        Assertions.assertEquals("G-TEST-1", g.id);
        Assertions.assertFalse(g.risolto);
        Assertions.assertEquals("TRAIN", g.tipo);
        Assertions.assertEquals("Test message", g.messaggio);
    }

    @Test
    public void testOrigineAllarmePerOperatore() {
        Guasto dedotto = new Guasto();
        dedotto.origine = IngestionService.ORIGINE_DEDOTTO_CENTRALE;

        Guasto segnalato = new Guasto();
        segnalato.origine = IngestionService.ORIGINE_SEGNALATO_CAMPO;

        Assertions.assertEquals(IngestionService.ORIGINE_DEDOTTO_CENTRALE,
                IngestionService.originePerOperatore(dedotto));
        Assertions.assertEquals(IngestionService.ORIGINE_SEGNALATO_CAMPO,
                IngestionService.originePerOperatore(segnalato));
    }
}
