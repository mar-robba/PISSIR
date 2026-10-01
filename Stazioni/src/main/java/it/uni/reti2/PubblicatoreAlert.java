package it.uni.reti2;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Prepara gli allarmi della stazione per railway/alerts (GUASTO e RESOLVED, nel formato
 * condiviso del canale, con il discriminante "tipoEvento") e li mette nel LocalBuffer: a
 * pubblicarli è il ConsumatoreBuffer, nell'ordine in cui sono nati.
 *
 * <p>Lo chiama GestoreEventi (è l'azione delle mosse che guastano o ripristinano la
 * stazione). Non sta nello StationGateway perché il gateway passa a sua volta gli alert al
 * gestore: con la pubblicazione dentro il gateway i due si chiamerebbero a vicenda. Nel treno
 * PubblicatoreAlert esiste per lo stesso motivo.</p>
 *
 * <p>Il JSON si costruisce con un JsonObject di Gson e non concatenando stringhe: la
 * descrizione di un guasto la scrive chi chiama la POST /stazione/sensore/guasto, e delle
 * virgolette al suo interno basterebbero a produrre un messaggio illeggibile, che la Centrale
 * scarterebbe in silenzio. I campi escono nello stesso ordine di prima.</p>
 */
public class PubblicatoreAlert {

    private static final Logger LOG = Logger.getLogger(PubblicatoreAlert.class.getName());

    /** Stato del nodo: serve l'id per la sorgente dell'alert. Assegnato dal main. */
    DBLocale dbLocale;

    /** La coda verso la Centrale. */
    LocalBuffer localBuffer;

    /** Il collegamento: solo per dire nel log che l'allarme resta in coda. */
    AutomaConnessione connessione;

    /**
     * Segnala un guasto della stazione con un alert GUASTO.
     *
     * <p>Il campo {@code tipoGuasto} è la classificazione dichiarata dalla sorgente: chi
     * segnala il guasto sa che tipo è, e la Centrale non deve ricavarlo dal testo. Da lui
     * dipende anche se all'operatore compare il comando per mandare la squadra.</p>
     *
     * @param descrizione La descrizione del problema rilevato.
     * @param severita    "CRITICAL" oppure "WARNING".
     * @param tipoGuasto  Classificazione ("sensore_offline", "binario_guasto", ...), oppure null
     *                    per lasciar decidere alla Centrale in base al tipo di sorgente.
     * @param idGuasto    Il guasto del registro locale che ha fatto partire l'allarme, oppure
     *                    null (o -1, se la persistenza è spenta) quando non ce n'è uno.
     */
    public void inviaGuasto(String descrizione, String severita, String tipoGuasto, Long idGuasto) {
        JsonObject alert = new JsonObject();
        alert.addProperty("tipoEvento", "GUASTO");
        alert.addProperty("sorgenteTipo", "STAZIONE");
        alert.addProperty("sorgenteId", dbLocale.stazioneId);
        if (tipoGuasto != null) {
            alert.addProperty("tipoGuasto", tipoGuasto);
        }
        alert.addProperty("severita", severita);
        alert.addProperty("messaggio", descrizione);
        alert.addProperty("timestamp", Instant.now().toString());

        accoda(alert.toString(), idGuasto != null && idGuasto >= 0 ? idGuasto : null);
    }

    /**
     * Comunica alla Centrale che un guasto segnalato da questa stazione è rientrato da solo:
     * oggi succede quando i keepalive dei sensori ricominciano ad arrivare. Se non lo dicesse
     * a nessuno l'allarme resterebbe rosso sulla dashboard. Il RESOLVED sul topic condiviso lo
     * ascoltano anche i convogli, che se lo segnano ma ripartono solo quando la Centrale
     * dichiara la stazione di nuovo agibile.
     *
     * <p>Non viene messo il campo "origine": quello è il marcatore che la Centrale usa per non
     * riascoltare i propri messaggi, e questo messaggio arriva invece dal campo. È anche il
     * motivo per cui lo StationGateway, quando se lo risente arrivare, lo scarta.</p>
     *
     * @param tipoGuasto  Classificazione del guasto che è rientrato ("sensore_offline").
     * @param descrizione Testo leggibile del rientro.
     */
    public void inviaRipristino(String tipoGuasto, String descrizione) {
        JsonObject ripristino = new JsonObject();
        ripristino.addProperty("tipoEvento", "RESOLVED");
        ripristino.addProperty("sorgenteTipo", "STAZIONE");
        ripristino.addProperty("sorgenteId", dbLocale.stazioneId);
        ripristino.addProperty("tipoGuasto", tipoGuasto);
        ripristino.addProperty("messaggio", descrizione);
        ripristino.addProperty("timestamp", Instant.now().toString());

        accoda(ripristino.toString(), null);
    }

    /** Mette l'allarme in coda: parte subito se la Centrale è raggiungibile, se no aspetta. */
    private void accoda(String payload, Long idGuasto) {
        if (!connessione.collegata()) {
            LOG.warning("🔌 Offline: evento 'alert' salvato nel buffer locale.");
        }
        localBuffer.add("alert", payload, idGuasto, null);
    }
}
