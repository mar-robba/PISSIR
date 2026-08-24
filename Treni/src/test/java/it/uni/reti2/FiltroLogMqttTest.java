package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Test dello sfoltimento delle righe di connessione MQTT.
 *
 * Non è un @QuarkusTest: il filtro è una classe normale e gli si passano a mano le
 * righe di log che stamperebbero Vert.x e SmallRye, senza tirare su né il broker né
 * il resto del servizio. La finestra viene accorciata a mezzo secondo con il costruttore
 * apposta, se no per provare il riepilogo ci vorrebbero trenta secondi di attesa.
 */
public class FiltroLogMqttTest {

    /** Le due righe vere delle librerie, copiate da un log del progetto. */
    private static final String CONNESSA = "Connection with localhost:8883 established successfully";
    private static final String CADUTA = "Connection closed: Connection closed";

    private FiltroLogMqtt filtro;

    @BeforeEach
    public void preparaFiltro() {
        filtro = new FiltroLogMqtt(500);
    }

    /** La prima connessione si vede; quelle degli altri canali sono la stessa notizia. */
    @Test
    public void laPrimaConnessioneSiVedeLeRipetizioniNo() {
        Assertions.assertTrue(filtro.isLoggable(riga(CONNESSA)));
        Assertions.assertFalse(filtro.isLoggable(riga(CONNESSA)));
        Assertions.assertFalse(filtro.isLoggable(riga(CONNESSA)));
    }

    /** La caduta è la riga che interessa: esce subito, e come avviso. */
    @Test
    public void laCadutaSiVedeSubito() {
        filtro.isLoggable(riga(CONNESSA));

        LogRecord caduta = riga(CADUTA);
        Assertions.assertTrue(filtro.isLoggable(caduta));
        Assertions.assertEquals(Level.WARNING, caduta.getLevel());
        Assertions.assertTrue(caduta.getMessage().contains("caduta"), caduta.getMessage());
    }

    /**
     * Connessione che balla: dopo le due righe concesse nella finestra le altre spariscono,
     * e la prima riga della finestra dopo racconta quante ne sono state nascoste.
     */
    @Test
    public void ilBalloContinuoNonIntasaIlLog() throws InterruptedException {
        Assertions.assertTrue(filtro.isLoggable(riga(CONNESSA)));
        Assertions.assertTrue(filtro.isLoggable(riga(CADUTA)));

        // Stessa finestra: da qui in poi non deve passare più niente.
        for (int i = 0; i < 10; i++) {
            Assertions.assertFalse(filtro.isLoggable(riga(CONNESSA)));
            Assertions.assertFalse(filtro.isLoggable(riga(CADUTA)));
        }

        Thread.sleep(600);
        LogRecord riepilogo = riga(CONNESSA);
        Assertions.assertTrue(filtro.isLoggable(riepilogo));
        Assertions.assertTrue(riepilogo.getMessage().contains("nascosti altri 20"), riepilogo.getMessage());
    }

    /** Tutto quello che non parla di connessione non è affare del filtro. */
    @Test
    public void leAltreRigheDelleLibreriePassano() {
        Assertions.assertTrue(filtro.isLoggable(riga("Failed to send unsubscribe request: railway/alerts")));
        Assertions.assertTrue(filtro.isLoggable(new LogRecord(Level.FINE, CONNESSA)));
    }

    /** Una riga di log come la creerebbe la libreria. */
    private LogRecord riga(String messaggio) {
        return new LogRecord(Level.INFO, messaggio);
    }
}
