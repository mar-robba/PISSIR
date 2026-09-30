package it.uni.reti2.ingestion;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * La coda dei messaggi MQTT in uscita: produttore/consumatore.
 *
 * <p>I produttori sono tutti quelli che hanno qualcosa da mandare al campo: PubblicatoreAlert
 * (chiamato da GestoreEventi, che gira sul thread di Paho quando l'evento è un messaggio MQTT,
 * su quello di Javalin quando è un comando dell'operatore e su quello del watchdog) e i due
 * validatori degli id. Non pubblicano loro: mettono il messaggio in coda con {@link #metti} e
 * tornano subito a quello che stavano facendo.</p>
 *
 * <p>Il consumatore è un thread solo, "pubblicatore-mqtt", che prende i messaggi uno alla
 * volta e li consegna al broker con ClientMqtt. Serve perché una pubblicazione a QoS 1
 * aspetta la conferma del broker, e quella conferma la porta il thread delle callback di
 * Paho: se a pubblicare fosse proprio quel thread (un GUASTO arrivato dal campo che fa
 * pubblicare alla Centrale un altro alert) aspetterebbe sé stesso per sempre. Con la coda
 * nessun produttore aspetta il broker, e GestoreEventi tiene il suo lock solo per il tempo
 * delle mosse. È la stessa classe del nodo Treno.</p>
 *
 * <p>Se il broker non è raggiungibile: i messaggi a QoS 0 (le risposte alle validazioni) si
 * buttano, perché il nodo ripete la domanda; quelli a QoS 1 (gli alert su railway/alerts) si
 * riprovano finché il collegamento non torna, perché nessuno li ripeterebbe. La coda ha un tetto: oltre {@value #CAPIENZA} messaggi in attesa i nuovi si
 * scartano, invece di riempire la memoria mentre il broker è spento.</p>
 */
public class CodaPubblicazioni {

    private static final Logger LOG = Logger.getLogger(CodaPubblicazioni.class.getName());

    /** Quanti messaggi possono aspettare al massimo. */
    private static final int CAPIENZA = 1000;

    /** Pausa fra due tentativi di un messaggio a QoS 1 col broker spento (millisecondi). */
    private static final long ATTESA_RIPROVA_MS = 1000;

    /** Un messaggio in attesa di essere pubblicato. */
    public record MessaggioInUscita(String topic, String payload, int qos) {
    }

    /** La connessione con cui il consumatore pubblica: assegnata dal main. */
    public ClientMqtt clientMqtt;

    private final BlockingQueue<MessaggioInUscita> coda = new LinkedBlockingQueue<>(CAPIENZA);

    /**
     * Lato produttore: mette un messaggio in coda e torna subito, senza aspettare il broker.
     *
     * @param topic Dove pubblicarlo.
     * @param payload Il contenuto (JSON).
     * @param qos 0 per i messaggi che si ripetono da soli, 1 per quelli che non si possono perdere.
     */
    public void metti(String topic, String payload, int qos) {
        if (!coda.offer(new MessaggioInUscita(topic, payload, qos))) {
            LOG.warning("📭 Coda MQTT piena: scarto il messaggio per " + topic);
        }
    }

    /** Fa partire il thread consumatore. Lo chiama il main. */
    public void avvia() {
        Thread pubblicatore = new Thread(this::consuma, "pubblicatore-mqtt");
        pubblicatore.setDaemon(true); // non deve impedire la chiusura del processo
        pubblicatore.start();
    }

    /** Lato consumatore: prende un messaggio alla volta (take() aspetta se la coda è vuota). */
    private void consuma() {
        while (true) {
            try {
                MessaggioInUscita messaggio = coda.take();
                consegna(messaggio);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Consegna un messaggio al broker. A QoS 0 si prova una volta sola; a QoS 1 si riprova
     * finché non va, e intanto gli altri messaggi aspettano dietro, nell'ordine in cui sono
     * arrivati.
     */
    private void consegna(MessaggioInUscita messaggio) throws InterruptedException {
        while (true) {
            try {
                clientMqtt.pubblica(messaggio.topic(), messaggio.payload(), messaggio.qos());
                return;
            } catch (Exception e) {
                if (messaggio.qos() == 0) {
                    LOG.fine("Messaggio per " + messaggio.topic() + " non pubblicato (" + e.getMessage() + "): scartato");
                    return;
                }
                LOG.warning(String.format("📮 Messaggio per %s non pubblicato (%s): riprovo tra %d ms",
                        messaggio.topic(), e.getMessage(), ATTESA_RIPROVA_MS));
                Thread.sleep(ATTESA_RIPROVA_MS);
            }
        }
    }
}
