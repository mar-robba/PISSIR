package it.uni.reti2;

import it.uni.reti2.DBLocale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * StationGateway si occupa della comunicazione asincrona tra il nodo Edge (Stazione)
 * e il sistema Cloud (Centrale Operativa). Riceve messaggi e inoltra eventi
 * garantendo la resilienza tramite l'uso del {@link LocalBuffer} in caso di disconnessione.
 */
@ApplicationScoped
public class StationGateway {
    private static final Logger LOG = Logger.getLogger(StationGateway.class);

    /**
     * Database locale per accedere e aggiornare lo stato e la connettività della stazione.
     */
    @Inject
    DBLocale dbLocale;

    /**
     * Coda di backup per salvare gli eventi quando la connessione (es. MQTT) non è disponibile.
     */
    @Inject
    LocalBuffer localBuffer;

    //==== Alias per l'uso dei topic in output(quelli in imput naturalmente devono possedere listener reattivi siccome si
    // tratta di un flusso di dati)
    /**
     * Canale di uscita per l'invio di notifiche relative ai guasti della stazione.
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    /**
     * Canale di uscita per notificare l'entrata o l'uscita dei treni dai binari della stazione.
     */
    @Inject
    @Channel("transit-out")
    Emitter<String> transitEmitter;

    // ------------------ END Alias per l'uso dei topic in output


    // ==== listener per canali in imput
    /**
     * Intercetta gli eventi in ingresso dalla Centrale Operativa.
     * Attualmente gestisce la ricezione di conferme di risoluzione dei guasti.
     *
     * @param message Il messaggio in arrivo dal broker.
     * @return CompletionStage che indica il processamento completato.
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        LOG.warnf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload);

        // Se riceviamo un messaggio dalla centrale, la connessione è sicuramente attiva
        dbLocale.connessioneCentrale = true;

        // Se l'alert è un avviso di "RISOLTO" e riguarda questa stazione specifica
        if (payload.contains("\"type\":\"RESOLVED\"") && payload.contains(dbLocale.stazioneId)) {
            LOG.info("🔧 Stazione ripristinata dalla Centrale!");
            // Ripristina lo stato operativo normale
            dbLocale.stato = "ONLINE";
        }
        // ?
        return message.ack();
    }

    // ---------------- END  listener per canali in imput

    // ================= inizion degli inviatori dei messaggi in out dalla stazione

    /**
     * Segnala un guasto infrastrutturale locale inviando un alert alla Centrale.
     * Se l'invio fallisce (rete down), l'evento viene persistito nel buffer locale.
     *
     * @param descrizione La descrizione del problema rilevato.
     */
    public void inviaGuasto(String descrizione) {
        dbLocale.stato = "GUASTA";
        String alertJson = String.format("{\"sorgenteId\":\"%s\", \"tipo\":\"STATION\", \"severita\":\"CRITICAL\", \"messaggio\":\"%s\"}", dbLocale.stazioneId, descrizione);
        
        try {
            alertsEmitter.send(alertJson);
            // L'invio è andato a buon fine, la connessione è attiva
            dbLocale.connessioneCentrale = true;
        } catch (Exception e) {
            LOG.error("Impossibile inviare guasto, salvo nel buffer locale.");
            // Memorizza localmente per ritrasmettere quando torna online
            localBuffer.add(alertJson);
            dbLocale.connessioneCentrale = false;
        }
    }

    /**
     * Invia un evento telemetrico relativo al passaggio fisico di un treno.
     * se l'invio del messaggio fallisce allora viene salvato l'evento nel buffer
     *
     * @param trenoId Identificativo del convoglio.
     * @param tipo "ENTRATA" oppure "USCITA".
     */
    public void inviaTransito(String trenoId, String tipo) {
        String payload = String.format("{\"stazioneId\":\"%s\", \"trenoId\":\"%s\", \"tipo\":\"%s\"}",
                dbLocale.stazioneId, trenoId, tipo);
        
        try {
            transitEmitter.send(payload);
            dbLocale.connessioneCentrale = true;
        } catch (Exception e) {
            LOG.error("Impossibile inviare transito, salvo nel buffer locale.");
            // Meccanismo Store and Forward: se il broker è irraggiungibile si accoda
            localBuffer.add(payload);
            dbLocale.connessioneCentrale = false;
        }
    }
}
