package it.uni.reti2.ingestion;

import com.google.gson.JsonObject;
import it.uni.reti2.elaboration.EventoCentrale;
import it.uni.reti2.elaboration.GestoreEventi;
import it.uni.reti2.gateway.Json;
import it.uni.reti2.ingestion.payload.AlertMsg;
import it.uni.reti2.ingestion.payload.HeartbeatMsg;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * La porta d'ingresso MQTT della Centrale: quattro topic, quattro metodi. È il generatore
 * degli eventi che arrivano dal campo.
 *
 * <p>Questa classe fa tre cose e nessun'altra: prende il testo che arriva dal broker, lo
 * trasforma in un messaggio con un nome ({@code TelemetriaMsg}, {@code HeartbeatMsg}...) e lo
 * consegna al GestoreEventi dentro un EventoCentrale. Non decide niente, non scrive sul
 * database, non sa come è fatto un evento WebSocket: come TrainGateway nel treno.</p>
 *
 * <p>Il try/catch di ogni metodo è l'ultima rete: un frame malformato (o un errore del
 * database mentre lo si gestisce) si perde con una riga di log, e la Centrale continua a
 * ricevere su quel topic. Un frame che nessuno saprà mai interpretare non va rimesso in
 * coda: verrebbe riconsegnato all'infinito.</p>
 */
public class IngestionService {

    private static final Logger LOG = Logger.getLogger(IngestionService.class.getName());

    /** La posizione che ogni convoglio trasmette di continuo. */
    public static final String TOPIC_TELEMETRIA = "railway/train/+/telemetry";

    /** Il battito con cui ogni stazione dichiara di essere viva. */
    public static final String TOPIC_HEARTBEAT = "railway/station/+/heartbeat";

    /** I passaggi rilevati dai sensori di terra delle stazioni. */
    public static final String TOPIC_TRANSITI = "railway/station/+/transit";

    /** Chi riceve gli eventi. */
    public GestoreEventi gestore;

    /**
     * Si mette in ascolto sui quattro topic. Gli alert sono a QoS 1 (nessuno li ripete, e con
     * la sessione persistente il broker li conserva se la Centrale è staccata); gli altri a
     * QoS 0, perché fra un secondo ne arriva uno nuovo. Va chiamato prima di
     * {@link ClientMqtt#connetti()}: gli alert conservati arrivano appena ci si collega.
     */
    public void sottoscrivi(ClientMqtt client) {
        client.ascolta(TOPIC_TELEMETRIA, 0, this::onTelemetry);
        client.ascolta(TOPIC_HEARTBEAT, 0, this::onHeartbeat);
        client.ascolta(TOPIC_TRANSITI, 0, this::onTransit);
        client.ascolta(PubblicatoreAlert.TOPIC_ALERTS, 1, this::onAlert);
    }

    /** railway/train/+/telemetry — la posizione che ogni convoglio trasmette di continuo. */
    public void onTelemetry(String payload) {
        try {
            JsonObject root = Json.oggetto(payload);
            gestore.gestisci(EventoCentrale.telemetria(TelemetriaMsg.da(root), root));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "❌ Errore parsing telemetria: " + payload, e);
        }
    }

    /** railway/station/+/heartbeat — il battito con cui ogni stazione dichiara di essere viva. */
    public void onHeartbeat(String payload) {
        try {
            JsonObject root = Json.oggetto(payload);
            gestore.gestisci(EventoCentrale.heartbeat(HeartbeatMsg.da(root), root));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "❌ Errore parsing heartbeat stazione: " + payload, e);
        }
    }

    /**
     * railway/station/+/transit — il passaggio rilevato dai sensori di terra di una stazione,
     * unica fonte del transito: da qui la Centrale misura anche il ritardo.
     */
    public void onTransit(String payload) {
        try {
            JsonObject root = Json.oggetto(payload);
            gestore.gestisci(EventoCentrale.passaggio(TransitoMsg.da(root), root));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "❌ Errore parsing transito: " + payload, e);
        }
    }

    /**
     * railway/alerts — il topic condiviso su cui viaggiano guasti, risoluzioni e comandi.
     *
     * <p>La Centrale è sottoscritta anche in ingresso, quindi <b>si riascolta da sola</b>:
     * tutto quello che porta il marcatore {@code CENTRALE} è roba sua e va lasciata passare,
     * altrimenti creerebbe un secondo guasto per un allarme che ha appena scritto lei. Del
     * campo interessano due eventi: il GUASTO che un nodo segnala e il RESOLVED con cui un
     * nodo dichiara rientrato un guasto che aveva segnalato lui. Gli altri eventi del canale
     * (STOP, MAINTENANCE_DISPATCHED, RITARDO, ITINERARIO_AGGIORNATO) sono comandi verso il
     * campo: qui non si guardano.</p>
     */
    public void onAlert(String payload) {
        try {
            AlertMsg alert = AlertMsg.da(Json.oggetto(payload), payload);
            if (alert.dallaCentrale()) {
                return;
            }
            if (alert.segnalaRisoluzione()) {
                gestore.gestisci(EventoCentrale.alert(EventoCentrale.Tipo.GUASTO_RIENTRATO, alert));
            } else if (alert.segnalaGuasto()) {
                gestore.gestisci(EventoCentrale.alert(EventoCentrale.Tipo.GUASTO_SEGNALATO, alert));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "❌ Errore parsing alert: " + payload, e);
        }
    }
}
