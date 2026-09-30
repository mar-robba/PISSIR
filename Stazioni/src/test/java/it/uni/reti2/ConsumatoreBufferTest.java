package it.uni.reti2;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Prova il produttore/consumatore dello store and forward: gli eventi escono dal LocalBuffer
 * solo quando il broker li ha presi, nell'ordine in cui sono entrati, anche quando il
 * collegamento è staccato o il broker è giù per un po', e non si perdono con un riavvio.
 *
 * <p>Al posto di Paho c'è un ClientMqtt finto che si segna i messaggi, e che si può "spegnere"
 * per far fallire le pubblicazioni come farebbe il client vero col broker giù.</p>
 */
public class ConsumatoreBufferTest {

    /** Un ClientMqtt che non si collega a niente: pubblicare vuol dire segnarsi il messaggio. */
    static class ClientFinto extends ClientMqtt {
        final List<String> pubblicati = new CopyOnWriteArrayList<>();
        volatile boolean brokerGiu = false;

        @Override
        public void pubblica(String topic, String payload, int qos) throws MqttException {
            if (brokerGiu) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }
            pubblicati.add(topic + " qos" + qos + " " + payload);
        }
    }

    @TempDir
    Path cartella;

    private PersistenzaLocale persistenza;
    private LocalBuffer buffer;
    private AutomaConnessione connessione;
    private ClientFinto client;
    private ConsumatoreBuffer consumatore;

    @AfterEach
    public void spegni() {
        if (consumatore != null) {
            consumatore.ferma();
        }
        if (persistenza != null) {
            persistenza.chiudi();
        }
    }

    @Test
    public void gliEventiPartonoInOrdineSulTopicDelLoroCanale() {
        accendi(false);
        buffer.add("transit", "{\"n\":1}", null, "TRN1");
        buffer.add("alert", "{\"n\":2}", null, null);
        buffer.add("transit", "{\"n\":3}", null, "TRN1");

        consumatore.avvia();
        Assertions.assertTrue(attendi(buffer::isEmpty), "la coda doveva svuotarsi");
        Assertions.assertEquals(List.of(
                "railway/station/STEST/transit qos0 {\"n\":1}",
                "railway/alerts qos1 {\"n\":2}",
                "railway/station/STEST/transit qos0 {\"n\":3}"), client.pubblicati);
    }

    /** POST /rete/offline: non si tenta nemmeno, e al ritorno partono tutti, in ordine. */
    @Test
    public void aReteStaccataGliEventiRestanoInCodaEPartonoAlRitorno() throws InterruptedException {
        accendi(false);
        connessione.isola();
        consumatore.avvia();
        buffer.add("transit", "{\"tipo\":\"ENTRATA\"}", null, "TRN1");
        buffer.add("transit", "{\"tipo\":\"USCITA\"}", null, "TRN1");

        Thread.sleep(1500); // il consumatore ha tutto il tempo di provarci
        Assertions.assertEquals(2, buffer.size());
        Assertions.assertTrue(client.pubblicati.isEmpty());

        connessione.ricollega();
        Assertions.assertTrue(attendi(buffer::isEmpty), "tornato il collegamento la coda doveva svuotarsi");
        Assertions.assertEquals(List.of(
                "railway/station/STEST/transit qos0 {\"tipo\":\"ENTRATA\"}",
                "railway/station/STEST/transit qos0 {\"tipo\":\"USCITA\"}"), client.pubblicati);
    }

    /** Broker giù: la testa non si perde e nessuno le passa davanti. */
    @Test
    public void colBrokerGiuLaTestaNonSiPerdeENonVieneSorpassata() throws InterruptedException {
        accendi(false);
        client.brokerGiu = true;
        consumatore.avvia();
        buffer.add("transit", "{\"tipo\":\"ENTRATA\"}", null, "TRN1");
        buffer.add("transit", "{\"tipo\":\"USCITA\"}", null, "TRN1");

        Thread.sleep(1500);
        Assertions.assertEquals(2, buffer.size(), "finche' il broker e' giu' non esce niente");

        client.brokerGiu = false;
        Assertions.assertTrue(attendi(buffer::isEmpty));
        Assertions.assertEquals(List.of(
                "railway/station/STEST/transit qos0 {\"tipo\":\"ENTRATA\"}",
                "railway/station/STEST/transit qos0 {\"tipo\":\"USCITA\"}"), client.pubblicati);
    }

    /**
     * Gli eventi non ancora spediti sopravvivono a un riavvio del nodo (sono sul db locale),
     * ripartono nello stesso ordine, e dal db escono solo dopo l'invio.
     */
    @Test
    public void gliEventiNonSpeditiSopravvivonoAlRiavvio() {
        accendi(true);
        connessione.isola();
        buffer.add("alert", "{\"n\":1}", null, null);
        buffer.add("transit", "{\"n\":2}", null, "TRN1");
        persistenza.chiudi();

        accendi(true); // riavvio: collegamento di nuovo attivo, la coda si rilegge da disco
        Assertions.assertEquals(2, buffer.size());
        consumatore.avvia();
        Assertions.assertTrue(attendi(buffer::isEmpty));
        Assertions.assertEquals(List.of(
                "railway/alerts qos1 {\"n\":1}",
                "railway/station/STEST/transit qos0 {\"n\":2}"), client.pubblicati);
        Assertions.assertTrue(persistenza.leggiBuffer().isEmpty(), "consegnati, quindi tolti anche dal db");
    }

    // ============================== appoggi

    /**
     * Monta la coda e il suo consumatore, senza farlo partire.
     *
     * @param suDisco true per tenere la coda anche su un SQLite nella cartella di prova.
     */
    private void accendi(boolean suDisco) {
        persistenza = new PersistenzaLocale();
        persistenza.percorsoDb = cartella.resolve("stazione.db").toString();
        persistenza.abilitata = suDisco;
        persistenza.apri();

        buffer = new LocalBuffer();
        buffer.persistenza = persistenza;
        buffer.ripristinaDaDisco();

        DBLocale db = new DBLocale();
        db.persistenza = persistenza;
        db.stazioneId = "STEST";
        connessione = new AutomaConnessione();
        connessione.dbLocale = db;

        client = new ClientFinto();
        consumatore = new ConsumatoreBuffer();
        consumatore.localBuffer = buffer;
        consumatore.connessione = connessione;
        consumatore.clientMqtt = client;
        consumatore.stazioneId = "STEST";
    }

    /** Aspetta che una condizione diventi vera, fino a 10 secondi. */
    private static boolean attendi(BooleanSupplier condizione) {
        Instant scadenza = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(scadenza)) {
            if (condizione.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condizione.getAsBoolean();
    }
}
