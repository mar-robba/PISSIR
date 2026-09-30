package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/**
 * Prova il ritardo comunicato dall'esterno: il testo del progetto vuole il convoglio
 * "dotato di un sistema di comunicazione in ingresso, in grado di ricevere informazioni
 * (guasti o ritardi) dalle stazioni o dalla centrale", e i ritardi sono la meta' che
 * mancava sul canale railway/alerts.
 *
 * <p>Il gateway viene costruito a mano (come fa il main) e l'alert gli viene
 * consegnato direttamente, cosi' il test non tocca il broker (che e' condiviso con il
 * sistema acceso, e pubblicarci sopra un ritardo finto si vedrebbe sui treni veri).</p>
 */
public class RitardoComunicatoTest {

    private TrainGateway gateway;
    private TrainDB db;

    @BeforeEach
    public void preparaIlConvoglio() {
        db = new TrainDB();
        db.trenoId = "TRN001";
        gateway = new TrainGateway();
        gateway.trainDB = db;
    }

    /** L'alert con il target giusto lascia a bordo i minuti, la stazione e l'ora della misura. */
    @Test
    public void ilRitardoComunicatoDallaCentraleFinisceABordo() {
        consegna(alertRitardo("TRN001", 7, 4, "ARRIVO_IN_RITARDO", "S2"));

        Assertions.assertEquals(7, db.ritardoMinuti, "il ritardo e' quello dichiarato dalla Centrale");
        Assertions.assertEquals("S2", db.ritardoRilevatoA, "va tenuta anche la stazione della misura");
        Assertions.assertNotNull(db.ritardoAggiornatoIl, "senza l'ora non si distingue un ritardo vecchio");
    }

    /**
     * Il convoglio che recupera il tempo perso torna in orario: la Centrale manda uno
     * zero, e a bordo il ritardo deve sparire invece di restare quello di prima.
     */
    @Test
    public void ilRientroInOrarioAzzeraIlRitardo() {
        consegna(alertRitardo("TRN001", 7, 7, "ARRIVO_IN_RITARDO", "S2"));
        consegna(alertRitardo("TRN001", 0, -7, "ARRIVO_IN_RITARDO", "S3"));

        Assertions.assertEquals(0, db.ritardoMinuti);
        Assertions.assertEquals("S3", db.ritardoRilevatoA);
    }

    /**
     * railway/alerts e' un topic condiviso: l'alert di un altro convoglio arriva anche
     * qui e va scartato, se no ogni treno mostrerebbe il ritardo di tutti gli altri.
     */
    @Test
    public void ilRitardoDiUnAltroConvoglioSiIgnora() {
        consegna(alertRitardo("TRN999", 12, 12, "SOSTA_PROLUNGATA", "S2"));

        Assertions.assertEquals(0, db.ritardoMinuti, "quel ritardo non e' mio");
        Assertions.assertNull(db.ritardoRilevatoA);
    }

    // ---------------------------------------------------------------- utilita'

    /** Consegna l'alert al listener come farebbe il connettore MQTT. */
    private void consegna(String payload) {
        gateway.riceviAlert(payload);
    }

    /** Un alert RITARDO nel formato che pubblica IngestionService.pubblicaRitardoSuMqtt. */
    private String alertRitardo(String target, int ritardo, int variazione, String causa, String stazione) {
        return ("{\"tipoEvento\":\"RITARDO\",\"origine\":\"CENTRALE\",\"sorgenteTipo\":\"STAZIONE\","
                + "\"sorgenteId\":\"%s\",\"target\":\"%s\",\"ritardoMinuti\":%d,\"variazioneMinuti\":%d,"
                + "\"causa\":\"%s\",\"severita\":\"WARNING\",\"messaggio\":\"prova\",\"timestamp\":\"%s\"}")
                .formatted(stazione, target, ritardo, variazione, causa, java.time.Instant.now());
    }
}
