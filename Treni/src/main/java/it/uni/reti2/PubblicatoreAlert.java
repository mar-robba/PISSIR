package it.uni.reti2;

import com.google.gson.JsonObject;

import java.time.Instant;

/**
 * Pubblica su railway/alerts i guasti rilevati a bordo del treno, perché la Centrale apra
 * l'allarme.
 *
 * <p>Lo chiama GestoreEventi (è l'azione della mossa che manda il convoglio in
 * EMERGENZA). Non sta nel TrainGateway perché il gateway passa a sua volta gli alert al
 * gestore: con la pubblicazione dentro il gateway i due si chiamerebbero a vicenda. È lo
 * stesso motivo per cui esiste PubblicatorePassaggi, e nella Centrale PubblicatoreAlert
 * fa la stessa cosa per i suoi alert.</p>
 */
public class PubblicatoreAlert {

    /** Il topic condiviso degli alert, lo stesso che ascolta la Centrale. */
    private static final String TOPIC = "railway/alerts";

    /** Stato del treno: serve l'id per la sorgente dell'alert. */
    TrainDB trainDB;

    /**
     * Coda dei messaggi MQTT in uscita: il guasto ci entra e lo pubblica il suo thread.
     * Usato per segnalare guasti a bordo del treno verso la Centrale.
     */
    CodaPubblicazioni coda;

    /**
     * Invia alla Centrale Operativa una notifica di guasto rilevato sul treno, nel formato
     * condiviso su railway/alerts.
     *
     * @param descrizione Il dettaglio testuale o codice del guasto rilevato.
     * @param severita La gravità del guasto (CRITICAL o WARNING).
     */
    public void inviaGuasto(String descrizione, String severita) {
        // Il JSON lo scrive Gson e non uno String.format: la descrizione è testo libero (arriva
        // dal sensore o dalla console) e una virgoletta dentro renderebbe il messaggio
        // illeggibile per la Centrale, che lo scarterebbe senza aprire nessun allarme.
        JsonObject alert = new JsonObject();
        alert.addProperty("tipoEvento", "GUASTO");
        alert.addProperty("sorgenteTipo", "TRENO");
        alert.addProperty("sorgenteId", trainDB.trenoId);
        alert.addProperty("severita", severita);
        alert.addProperty("messaggio", descrizione);
        alert.addProperty("timestamp", Instant.now().toString());

        // QoS 1: nessuno ripeterebbe questo messaggio, quindi il broker deve confermarlo
        coda.metti(TOPIC, alert.toString(), 1);
    }
}
