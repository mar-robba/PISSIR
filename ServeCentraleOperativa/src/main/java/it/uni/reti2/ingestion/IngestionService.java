package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import it.uni.reti2.elaboration.GuastiService;
import it.uni.reti2.elaboration.MonitoraggioStazioni;
import it.uni.reti2.elaboration.MonitoraggioTreni;
import it.uni.reti2.elaboration.TransitiService;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.AlertMsg;
import it.uni.reti2.ingestion.payload.HeartbeatMsg;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * La porta d'ingresso MQTT della Centrale: quattro topic, quattro metodi.
 *
 * <p>Questa classe fa tre cose e nessun'altra: prende i byte che arrivano dal broker, li
 * trasforma in un messaggio con un nome ({@code TelemetriaMsg}, {@code HeartbeatMsg}...)
 * e chiama chi di dovere. Non decide niente, non scrive sul database, non sa come e'
 * fatto un evento WebSocket. Prima ci stava dentro tutto: la lettura dei payload, le
 * regole sui guasti, le transazioni, il formato degli eventi per il browser e quello
 * degli alert per il campo, per milletrecento righe.</p>
 *
 * <h3>Perche' i consumer sono @Blocking e non @Transactional</h3>
 * <p>Con {@code @Transactional} sul consumer il commit avviene all'uscita del metodo,
 * cioe' <b>fuori</b> dal try/catch: una violazione di vincolo risaliva fino al connettore
 * reattivo e spegneva il canale, e da quel momento la Centrale smetteva di ricevere su
 * quel topic fino al riavvio. Le transazioni stanno percio' dentro i servizi, che le
 * chiudono prima di annunciare qualsiasi cosa; qui resta il try/catch, che e' l'ultima
 * rete: un frame malformato si perde con una riga di log, il canale resta vivo.</p>
 *
 * <p>Il messaggio si conferma sempre ({@code ack}), anche quando il contenuto e'
 * illeggibile o parla di un nodo sconosciuto. Un frame che nessuno sapra' mai
 * interpretare non va rimesso in coda: verrebbe riconsegnato all'infinito.</p>
 */
@ApplicationScoped
public class IngestionService {

    private static final Logger LOG = Logger.getLogger(IngestionService.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    MonitoraggioTreni treni;

    @Inject
    MonitoraggioStazioni stazioni;

    @Inject
    TransitiService transiti;

    @Inject
    GuastiService guasti;

    /**
     * railway/telemetry — la posizione che ogni convoglio trasmette di continuo.
     *
     * <p>Un frame di telemetria e' anche un segno di vita: se il convoglio si sta di nuovo
     * muovendo, il guasto "treno fermo" che il watchdog gli aveva aperto va chiuso.</p>
     */
    @Incoming("telemetry-in")
    @Blocking
    public CompletionStage<Void> onTelemetry(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            Treno treno = treni.registraTelemetria(TelemetriaMsg.da(root), root);
            if (treno != null) {
                guasti.chiudiDedottiSeTrenoRiparte(treno);
            }
        } catch (Exception e) {
            LOG.error("❌ Errore parsing telemetria: " + payload, e);
        }
        return message.ack();
    }

    /** railway/heartbeat — il battito con cui ogni stazione dichiara di essere viva. */
    @Incoming("heartbeat-in")
    @Blocking
    public CompletionStage<Void> onHeartbeat(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            stazioni.registraHeartbeat(HeartbeatMsg.da(root), root);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing heartbeat stazione: " + payload, e);
        }
        return message.ack();
    }

    /**
     * railway/station/+/transit — il passaggio rilevato dai sensori di terra di una
     * stazione, unica fonte del transito ora che il treno non lo dichiara più su un
     * topic proprio: da qui la Centrale misura anche il ritardo e la direzione
     * (vedi {@link TransitiService#registra}).
     */
    @Incoming("transit-in")
    @Blocking
    public CompletionStage<Void> onTransit(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            transiti.registra(TransitoMsg.da(root), root);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing transito: " + payload, e);
        }
        return message.ack();
    }

    /**
     * railway/alerts — il topic condiviso su cui viaggiano guasti, risoluzioni e comandi.
     *
     * <p>La Centrale e' sottoscritta anche in ingresso, quindi <b>si riascolta da sola</b>:
     * tutto quello che porta il marcatore {@code CENTRALE} e' roba sua e va lasciata
     * passare, altrimenti creerebbe un secondo guasto per un allarme che ha appena scritto
     * lei. Fa eccezione il RESOLVED senza marcatore, che arriva davvero dal campo: e' un
     * nodo che dichiara rientrato un guasto che aveva segnalato lui (oggi lo fa la stazione
     * quando i sensori di binario ricominciano a mandare i keepalive). Va gestito,
     * altrimenti il nodo tornerebbe operativo sul posto ma la Centrale continuerebbe a
     * mostrare l'allarme rosso finche' non ci mette le mani un operatore.</p>
     */
    @Incoming("alerts-in")
    @Blocking
    public CompletionStage<Void> onAlert(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            AlertMsg alert = AlertMsg.da(mapper.readTree(payload), payload);

            if (alert.segnalaRisoluzione()) {
                if (!alert.dallaCentrale()) {
                    guasti.chiudiSuDichiarazioneDalCampo(alert);
                }
                return message.ack();
            }
            // Gli altri eventi del canale (STOP, MAINTENANCE_DISPATCHED, RITARDO,
            // ITINERARIO_AGGIORNATO) sono comandi verso il campo: qui non si guardano.
            if (alert.segnalaGuasto() && !alert.dallaCentrale()) {
                guasti.registraSegnalazione(alert);
            }
        } catch (Exception e) {
            LOG.error("❌ Errore parsing alert: " + payload, e);
        }
        return message.ack();
    }
}
