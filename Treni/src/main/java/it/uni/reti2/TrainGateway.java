package it.uni.reti2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Locale;
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
     * Motore di viaggio del treno (digital twin): il gateway gli delega le
     * reazioni agli alert (blocco per stazione guasta, sblocco, ricarica itinerario).
     */
    @Inject
    TrainJourneyEngine journeyEngine;

    /**
     * Mapper JSON (fornito da Quarkus) per interpretare i payload degli alert.
     */
    @Inject
    ObjectMapper objectMapper;

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
     * Listener per la ricezione di avvisi (alert) provenienti dal canale condiviso
     * "railway/alerts". Il campo discriminante del payload è "tipoEvento":
     * - STOP con target questo treno: soppressione definitiva della corsa;
     * - GUASTO di una STAZIONE corrente/prossima: il treno si blocca (trattenuto);
     * - RESOLVED della stazione bloccante: il treno riprende il viaggio;
     * - RESOLVED di questo TRENO: rientro dall'emergenza (pronto a ripartire);
     * - ITINERARIO_AGGIORNATO con target questo treno: ricarica dell'itinerario.
     *
     * @param message Il messaggio asincrono contenente il payload dell'alert.
     * @return Una CompletionStage che indica l'avvenuta elaborazione del messaggio (ack).
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode alert = objectMapper.readTree(payload);
            String tipoEvento = alert.path("tipoEvento").asText("");
            String sorgenteTipo = alert.path("sorgenteTipo").asText("");
            String sorgenteId = alert.path("sorgenteId").asText("");
            String severita = alert.path("severita").asText("CRITICAL");
            String target = alert.path("target").asText("");

            switch (tipoEvento) {
                case "STOP" -> {
                    // Soppressione della corsa decisa dalla Centrale, solo se il target è questo treno
                    if (trainDB.trenoId.equals(target)) {
                        LOG.warnf("🛑 [ALERT] Corsa soppressa dalla Centrale: %s", payload);
                        trainDB.stato = "SOPPRESSO";
                        trainDB.velocita = 0;
                    }
                }
                case "GUASTO" -> {
                    // Solo i guasti CRITICI inibiscono la stazione: i WARNING (es. keepalive
                    // sensore mancante) sono informativi e la stazione resta agibile.
                    if ("STAZIONE".equals(sorgenteTipo) && !sorgenteId.isEmpty()
                            && "CRITICAL".equalsIgnoreCase(severita)) {
                        // Memorizza la stazione guasta per i controlli di partenza
                        trainDB.stazioniGuaste.add(sorgenteId);
                        // Il treno si blocca solo se la stazione guasta lo riguarda direttamente
                        if (sorgenteId.equals(trainDB.prossimaStazione) || sorgenteId.equals(trainDB.stazioneCorrente)) {
                            LOG.warnf("🚨 [ALERT] Stazione %s guasta sulla mia rotta: mi fermo", sorgenteId);
                            journeyEngine.bloccaPerGuastoStazione(sorgenteId);
                        }
                    }
                }

                case "RESOLVED" -> {
                    if ("STAZIONE".equals(sorgenteTipo)) {
                        // La stazione torna agibile
                        trainDB.stazioniGuaste.remove(sorgenteId);
                        if (sorgenteId.equals(trainDB.stazioneBloccante)) {
                            LOG.infof("✅ [ALERT] Guasto risolto alla stazione %s: riprendo il viaggio", sorgenteId);
                            journeyEngine.sbloccaDaGuastoStazione();
                        }
                    } else if ("TRENO".equals(sorgenteTipo) && trainDB.trenoId.equals(sorgenteId)) {
                        // Rientro dall'emergenza di bordo: il treno è pronto a ripartire
                        if ("EMERGENZA".equals(trainDB.stato)) {
                            LOG.info("✅ [ALERT] Guasto di bordo risolto: pronto a ripartire");
                            trainDB.stato = "FERMO";
                        }
                    }
                }
                case "ITINERARIO_AGGIORNATO" -> {
                    if (trainDB.trenoId.equals(target)) {
                        LOG.info("🗺️ [ALERT] La Centrale ha aggiornato il mio itinerario: lo ricarico");
                        journeyEngine.richiediRicaricaItinerario();
                    }
                }
                default -> LOG.debugf("[ALERT] Evento %s ignorato: %s", tipoEvento, payload);
            }
        } catch (Exception e) {
            LOG.warnf("Alert non interpretabile (%s): %s", e.getMessage(), payload);
        }

        // Conferma (acknowledge) l'avvenuta processazione del messaggio al broker
        return message.ack();
    }
//---- END listeners
// =========================== inizio emettitori ======================

    /**
     * Metodo per inviare alla Centrale Operativa una notifica di guasto rilevato sul treno.
     * Contemporaneamente arresta il convoglio per sicurezza (stato EMERGENZA).
     *
     * @param descrizione Il dettaglio testuale o codice del guasto rilevato.
     * @param severita La gravità del guasto (CRITICAL o WARNING).
     */
    public void inviaGuasto(String descrizione, String severita) {
        // Appena viene rilevato un guasto critico, si arresta subito il treno localmente
        trainDB.stato = "EMERGENZA";
        trainDB.velocita = 0;

        // Composizione del payload JSON per l'alert (formato condiviso su railway/alerts)
                            // formattazione stringhe java classico
        String alertJson = String.format(Locale.US,
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"TRENO\",\"sorgenteId\":\"%s\",\"severita\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}",
                trainDB.trenoId, severita, descrizione, Instant.now().toString());

        try {
            // Invio del messaggio in modo reattivo
            alertsEmitter.send(alertJson);
        } catch (Exception e) {
            LOG.error("Errore invio guasto: " + e.getMessage());
        }
    }

    /**
     * notifica di transito dentro una stazione
     * Notifica alla Centrale Operativa e agli altri sistemi il passaggio in una determinata stazione,
     * includendo il timestamp dell'evento e il ritardo accumulato dal convoglio.
     *
     * @param stazioneId L'identificativo univoco della stazione interessata.
     * @param tipo Il tipo di passaggio (es. "ENTRATA" nella stazione, o "USCITA" dalla stazione).
     */
    public void notificaPassaggioStazione(String stazioneId, String tipo) {
        // Se il treno entra in stazione, aggiorniamo il suo stato in-memory per tracciarlo.
        // Se sta uscendo, impostiamo la stazione corrente a null.
        trainDB.stazioneCorrente = "ENTRATA".equals(tipo) ? stazioneId : null;

        // Costruzione del messaggio JSON per la telemetria/segnalamento passaggi
        String json = String.format(Locale.US,
                "{\"trenoId\":\"%s\",\"stazioneId\":\"%s\",\"tipo\":\"%s\",\"timestamp\":\"%s\",\"ritardoMinuti\":%d}",
                trainDB.trenoId, stazioneId, tipo, Instant.now().toString(), trainDB.ritardoMinuti);

        try {
            // Invio del payload sul canale apposito
            passaggioEmitter.send(json);
        } catch (Exception e) {
            LOG.error("Errore notifica passaggio: " + e.getMessage());
        }
    }
}
//----------------END EMETTITORI
