package it.uni.reti2;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * La connessione della stazione al broker Mosquitto, fatta con il client Eclipse Paho.
 *
 * <p>Tiene qui dentro tutto quello che riguarda MQTT come protocollo: l'indirizzo (tcp:// in
 * chiaro, ssl:// sotto profilo tls), il certificato della CA, la sessione persistente, la
 * riconnessione automatica e lo smistamento dei messaggi in arrivo. Le altre classi non
 * vedono Paho: chi ascolta un topic riceve il payload già convertito in stringa (un
 * {@link Destinatario}), e chi pubblica chiama {@link #pubblica}.</p>
 *
 * <p>La connessione è una sola per tutto il processo, con client-id {@code stazione-<ID>}:
 * due processi con lo stesso client-id si scollegano a vicenda, per questo dentro c'è l'id
 * della stazione. La sessione è persistente (cleanSession=false): se la stazione perde il
 * collegamento, o è spenta, gli alert a QoS 1 arrivati nel frattempo il broker li tiene da
 * parte e li consegna alla riconnessione. Sono gli unici messaggi che nessuno ripete, e
 * perdere un RESOLVED vorrebbe dire una stazione che resta GUASTA per sempre.</p>
 *
 * <p>Le callback di Paho (messageArrived, connectComplete, connectionLost) girano tutte sullo
 * stesso thread del client, e quel thread non deve mai restare bloccato: è lui che porta
 * avanti le conferme del broker. Per questo nessuna callback pubblica (i messaggi per la
 * Centrale li pubblica il thread di ConsumatoreBuffer) e la risottoscrizione dopo una caduta
 * parte su un thread a parte. È la stessa classe del nodo Treno, tranne che per
 * {@link #ascolta}, che qui si chiama prima di collegarsi (vedi il suo commento).</p>
 */
public class ClientMqtt implements MqttCallbackExtended {

    private static final Logger LOG = Logger.getLogger(ClientMqtt.class.getName());

    /** Pausa tra due tentativi della prima connessione (secondi). */
    private static final int RIPROVA_SECONDI = 5;

    /**
     * Chi riceve i messaggi di un topic. Riceve solo il payload, già in stringa: il topic e
     * i dettagli di Paho restano qui.
     */
    public interface Destinatario {
        void ricevi(String payload);
    }

    /** Una sottoscrizione: la si ricorda per farla a ogni connessione. */
    private record Sottoscrizione(int qos, Destinatario destinatario) {
    }

    /** Indirizzo e porta del broker (1883 in chiaro, 8883 sotto profilo tls). */
    String host;
    int port;

    /** true sotto profilo tls: connessione ssl:// validata contro la CA del progetto. */
    boolean tls;

    /** Percorso del certificato della CA (ca.crt), usato solo con tls. */
    String caPath;

    /** Identificativo del client verso il broker: stazione-<ID>. */
    String clientId;

    private MqttClient client;

    /**
     * I topic da ascoltare, topic -> QoS e destinatario. La legge il thread di Paho a ogni
     * messaggio mentre il main la riempie: per questo è una mappa concorrente.
     */
    private final Map<String, Sottoscrizione> sottoscrizioni = new ConcurrentHashMap<>();

    /**
     * Registra chi deve ricevere i messaggi di un topic (anche con i caratteri jolly + e #).
     * La sottoscrizione vera la fa {@link #connetti()} appena il broker risponde.
     *
     * <p>Va chiamato PRIMA di connettersi. Con la sessione persistente il broker consegna gli
     * alert rimasti in coda subito dopo la connessione, prima ancora che il main arrivi alla
     * riga successiva: se il destinatario non fosse già al suo posto, messageArrived non
     * troverebbe a chi darli e un RESOLVED arrivato mentre la stazione era spenta andrebbe
     * perso.</p>
     *
     * @param topic Il topic o il filtro da ascoltare.
     * @param qos La qualità del servizio chiesta al broker.
     * @param destinatario Chi riceve il payload dei messaggi di quel topic.
     */
    public void ascolta(String topic, int qos, Destinatario destinatario) {
        sottoscrizioni.put(topic, new Sottoscrizione(qos, destinatario));
    }

    /**
     * Apre la connessione verso il broker e si sottoscrive ai topic registrati con
     * {@link #ascolta}. Finché il broker non risponde riprova ogni {@value #RIPROVA_SECONDI}
     * secondi: la riconnessione automatica di Paho vale solo dopo che la prima connessione è
     * riuscita.
     *
     * @throws Exception Se l'indirizzo non è valido o, sotto tls, la CA non si legge: in quel
     *                   caso riprovare non serve a niente.
     */
    public void connetti() throws Exception {
        String indirizzo = (tls ? "ssl://" : "tcp://") + host + ":" + port;
        // MemoryPersistence: i messaggi in volo si tengono in memoria. Quella di default
        // scriverebbe una cartella di file nella directory di lancio.
        client = new MqttClient(indirizzo, clientId, new MemoryPersistence());
        client.setCallback(this);

        MqttConnectOptions opzioni = new MqttConnectOptions();
        opzioni.setCleanSession(false);          // sessione persistente, vedi in cima alla classe
        opzioni.setAutomaticReconnect(true);     // dopo una caduta Paho si ricollega da solo...
        opzioni.setMaxReconnectDelay(10_000);    // ...aspettando al massimo 10s tra un tentativo e l'altro
        opzioni.setConnectionTimeout(10);
        opzioni.setKeepAliveInterval(30);
        if (tls) {
            opzioni.setSocketFactory(ContestoTls.fidatoDellaCa(Path.of(caPath)).getSocketFactory());
        }

        while (true) {
            try {
                client.connect(opzioni);
                break;
            } catch (MqttException e) {
                LOG.warning(String.format("🔌 Broker MQTT %s non raggiungibile (%s): riprovo tra %d secondi",
                        indirizzo, e.getMessage(), RIPROVA_SECONDI));
                Thread.sleep(RIPROVA_SECONDI * 1000L);
            }
        }
        for (Map.Entry<String, Sottoscrizione> voce : sottoscrizioni.entrySet()) {
            client.subscribe(voce.getKey(), voce.getValue().qos());
        }
    }

    /**
     * Pubblica un messaggio. Aspetta la conferma del broker (per QoS 1), quindi non va mai
     * chiamato da dentro una callback di Paho: lo chiamano il thread di ConsumatoreBuffer
     * (transiti e allarmi), quello dell'Orologio (heartbeat) e il main (richiesta di
     * validazione).
     *
     * @throws MqttException Se il client non è collegato o il broker non conferma.
     */
    public void pubblica(String topic, String payload, int qos) throws MqttException {
        MqttMessage messaggio = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        messaggio.setQos(qos);
        messaggio.setRetained(false);
        client.publish(topic, messaggio);
    }

    /** @return true se in questo momento il client è collegato al broker. */
    public boolean collegato() {
        return client != null && client.isConnected();
    }

    /**
     * Chiude la connessione: lo chiama lo shutdown hook del main. Si aspetta al massimo un
     * secondo che finiscano le consegne in corso, poi si chiude lo stesso.
     */
    public void disconnetti() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(1000);
            }
            client.close();
        } catch (MqttException e) {
            LOG.warning("Chiusura della connessione MQTT non riuscita: " + e.getMessage());
        }
    }

    // ============================== callback di Paho

    /**
     * Collegamento riuscito: la prima volta o dopo una caduta. Nel secondo caso si rifanno le
     * sottoscrizioni, perché se il broker ha perso la sessione (riavviato senza i suoi dati)
     * la stazione resterebbe collegata ma sorda. Con la sessione ancora buona non cambia niente.
     */
    @Override
    public void connectComplete(boolean riconnessione, String indirizzo) {
        if (!riconnessione) {
            LOG.info("🔌 Collegato al broker MQTT " + indirizzo + " come " + clientId);
            return;
        }
        LOG.info("🔌 Di nuovo collegato al broker MQTT " + indirizzo);
        // subscribe() aspetta la risposta del broker: da qui dentro bloccherebbe il thread
        // che quella risposta la deve leggere
        new Thread(this::risottoscrivi, "mqtt-risottoscrizione").start();
    }

    @Override
    public void connectionLost(Throwable causa) {
        LOG.warning("🔌 Collegamento MQTT perso (" + causa.getMessage() + "): Paho riprova da solo");
    }

    /**
     * Arriva un messaggio: lo si passa a chi ascolta un filtro che corrisponde al topic.
     * Un'eccezione che uscisse da qui farebbe chiudere la connessione a Paho, per questo ogni
     * destinatario è chiamato dentro un try/catch.
     */
    @Override
    public void messageArrived(String topic, MqttMessage messaggio) {
        String payload = new String(messaggio.getPayload(), StandardCharsets.UTF_8);
        for (Map.Entry<String, Sottoscrizione> voce : sottoscrizioni.entrySet()) {
            if (MqttTopic.isMatched(voce.getKey(), topic)) {
                try {
                    voce.getValue().destinatario().ricevi(payload);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Errore nel gestire il messaggio di " + topic + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // la conferma la aspetta già pubblica(): qui non c'è niente da fare
    }

    /** Rifà tutte le sottoscrizioni: gira sul thread creato da connectComplete. */
    private void risottoscrivi() {
        for (Map.Entry<String, Sottoscrizione> voce : sottoscrizioni.entrySet()) {
            try {
                client.subscribe(voce.getKey(), voce.getValue().qos());
            } catch (MqttException e) {
                LOG.warning("Risottoscrizione a " + voce.getKey() + " non riuscita: " + e.getMessage());
            }
        }
    }
}
