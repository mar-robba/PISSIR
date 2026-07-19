package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * TrainGateway è il componente responsabile della comunicazione asincrona 
 * (tramite broker di messaggistica come Kafka, MQTT o simili) tra il Treno e la Centrale Operativa.
 * Gestisce sia la ricezione di alert in ingresso che l'invio di notifiche di passaggio e guasti.
 */
@ApplicationScoped
public class TrainGateway {

    private static final Logger LOG = Logger.getLogger(TrainGateway.class);

    /**
     * Riferimento allo stato in-memory del treno. 
     * Viene iniettato per permettere di leggere lo stato attuale o aggiornarlo in base ai messaggi.
     */
    @Inject
    TrainDB trainDB;

    /**
     * Emitter reattivo per l'invio di messaggi di allarme (alert) sul canale "alerts-out".
     * Usato per segnalare guasti a bordo del treno verso la Centrale.
     * paradigma della programmazione reattiva
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    /**
     * Emitter reattivo per l'invio di notifiche sul passaggio del treno nelle stazioni.
     * I messaggi vengono inviati sul canale "passaggio-out".
     */
    @Inject
    @Channel("passaggio-out")
    Emitter<String> passaggioEmitter;
// ============================== inizio listeners
    /**
     * Listener per la ricezione di avvisi (alert) provenienti dalla Centrale Operativa 
     * sul canale "alerts-in".
     *
     * @param message Il messaggio asincrono contenente il payload dell'alert.
     * @return Una CompletionStage che indica l'avvenuta elaborazione del messaggio (ack).
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        LOG.warnf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload); // dove lo loggerebbe sta cosa?

       ///----------- condiizoni per identificare il tipo di alert  
        if (payload.contains("\"type\":\"STOP_ALL\"")) {
            // Comando di blocco totale, solitamente per gravi emergenze a livello di tratta o sistema
            // Se l'alert è rivolto in modo specifico a questo treno o è un broadcast ("ALL")
            if (payload.contains(trainDB.trenoId) || payload.contains("ALL")) {
                LOG.warn("🛑 Ricevuto comando di STOP_ALL! Freno di emergenza.");
                // Aziona il freno di emergenza fermando fisicamente il treno e aggiornando lo stato
                trainDB.stato = "EMERGENZA";
                trainDB.velocita = 0;
            }
        } else if (payload.contains("\"type\":\"RESOLVED\"") && payload.contains(trainDB.trenoId)) {
            // Notifica di risoluzione guasto o emergenza rientrata per questo specifico convoglio
            LOG.info("✅ Guasto risolto, riparto.");
            trainDB.stato = "IN_VIAGGIO"; // Ripristino dello stato di marcia normale
        } else if (payload.contains("stazione fuori uso")) {
            // Avviso informativo circa l'inagibilità di una stazione, che potrebbe richiedere ricalcolo
            LOG.info("⚠️ Avviso stazione fuori uso, ricalcolo percorso.");
            // TODO: Implementare l'effettivo ricalcolo del percorso interagendo con la logica di navigazione
        }

        // Conferma (acknowledge) l'avvenuta processazione del messaggio al broker
        return message.ack();
    }
//---- END listeners
// =========================== inizio emettitori ======================

    /**
     * Metodo per inviare alla Centrale Operativa una notifica di guasto rilevato sul treno.
     * Contemporaneamente arresta il convoglio per sicurezza.
     *
     * @param descrizione Il dettaglio testuale o codice del guasto rilevato.
     */
    public void inviaGuasto(String descrizione, String grado) {
        // Appena viene rilevato un guasto critico, si arresta subito il treno localmente
        trainDB.stato = grado;
        trainDB.velocita = 0;
        
        // Composizione del payload JSON per l'alert
                            // formattazione stringhe java classico
        String alertJson = String.format("{\"sorgenteId\":\"%s\", \"tipo\":\"TRAIN\", \"severita\":\"%s\", \"messaggio\":\"%s\"}", trainDB.trenoId,grado, descrizione);

        try {
            // Invio del messaggio in modo reattivo
            alertsEmitter.send(alertJson);
        } catch (Exception e) {
            LOG.error("Errore invio guasto: " + e.getMessage());
        }
    }

    /**
     * notifica di transito dentro una stazione
     * Notifica alla Centrale Operativa e agli altri sistemi il passaggio in una determinata stazione.
     *
     * @param stazioneId L'identificativo univoco della stazione interessata.
     * @param tipo Il tipo di passaggio (es. "ENTRATA" nella stazione, o "USCITA" dalla stazione).
     */
    public void notificaPassaggioStazione(String stazioneId, String tipo) {
        // Se il treno entra in stazione, aggiorniamo il suo stato in-memory per tracciarlo.
        // Se sta uscendo, impostiamo la stazione corrente a null.
        trainDB.stazioneCorrente = "ENTRATA".equals(tipo) ? stazioneId : null;
        
        // Costruzione del messaggio JSON per la telemetria/segnalamento passaggi
        String json = String.format("{\"trenoId\":\"%s\", \"stazioneId\":\"%s\", \"tipo\":\"%s\"}", trainDB.trenoId, stazioneId, tipo);
        
        try {
            // Invio del payload sul canale apposito
            passaggioEmitter.send(json);
        } catch (Exception e) {
            LOG.error("Errore notifica passaggio: " + e.getMessage());
        }
    }
}
//----------------END EMETTITORI