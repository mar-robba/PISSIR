package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * TrainGateway è il componente responsabile della comunicazione asincrona in ingresso
 * (tramite broker di messaggistica come Kafka, MQTT o simili) dalla Centrale Operativa al Treno.
 *
 * <p>È un generatore di eventi: legge gli alert di railway/alerts, scarta quelli che non
 * riguardano questo treno e traduce gli altri in un EventoTreno per GestoreEventi. Non
 * decide come reagire e non scrive lo stato del convoglio (fa eccezione il ritardo
 * comunicato, che non è lo stato di nessun automa: il treno lo annota e basta). Quello che
 * esce verso la Centrale lo pubblicano PubblicatoreAlert e PubblicatorePassaggi.</p>
 */
public class TrainGateway {

    private static final Logger LOG = Logger.getLogger(TrainGateway.class.getName());

    /**
     * Riferimento allo stato in-memory del treno: serve l'id per riconoscere gli alert che
     * parlano di questo treno, e ci si scrive il ritardo comunicato.
     */
    TrainDB trainDB;

    /** Dove vanno gli eventi tradotti dagli alert. */
    GestoreEventi gestore;

    /**
     * Il generatore del tick: la ricarica dell'itinerario la fa lui, perché la GET deve
     * partire dal thread del tick e non da quello del broker.
     */
    GeneratoreEventiTempo generatoreTempo;

// ============================== inizio listeners
    /**
     * Listener per la ricezione di avvisi (alert) provenienti dal canale condiviso
     * "railway/alerts". Il campo discriminante del payload è "tipoEvento", e ognuno diventa
     * un evento per il gestore:
     * - STOP con target questo treno: SOPPRESSIONE;
     * - GUASTO CRITICAL di una STAZIONE: GUASTO_STAZIONE;
     * - STATO_STAZIONE di una STAZIONE: STATO_STAZIONE, con lo stato dichiarato (GUASTA e
     *   MANUTENZIONE chiudono i binari, ONLINE è il permesso di ripartire);
     * - RESOLVED di una STAZIONE: GUASTO_STAZIONE_RISOLTO (l'allarme è chiuso, i binari no);
     * - RESOLVED di questo TRENO: GUASTO_BORDO_RISOLTO, cioè il rientro dall'emergenza;
     * - ITINERARIO_AGGIORNATO con target questo treno: si chiede la ricarica al
     *   GeneratoreEventiTempo;
     * - RITARDO con target questo treno: la Centrale comunica quanti minuti di ritardo ha
     *   misurato al passaggio in stazione, e il treno lo annota.
     *
     * <p>Gira sul thread di Paho: il main la registra presso ClientMqtt come destinatario
     * del topic, e la conferma al broker (il PUBACK del QoS 1) la manda Paho quando questo
     * metodo è finito.</p>
     *
     * @param payload Il contenuto dell'alert (JSON).
     */
    public void riceviAlert(String payload) {
        try {
            JsonObject alert = JsonParser.parseString(payload).getAsJsonObject();
            String tipoEvento = CampiJson.testo(alert, "tipoEvento", "");
            String sorgenteTipo = CampiJson.testo(alert, "sorgenteTipo", "");
            String sorgenteId = CampiJson.testo(alert, "sorgenteId", "");
            String severita = CampiJson.testo(alert, "severita", "CRITICAL");
            String target = CampiJson.testo(alert, "target", "");

            switch (tipoEvento) {
                case "STOP" -> {
                    // Soppressione della corsa decisa dalla Centrale, solo se il target è questo treno
                    if (trainDB.trenoId.equals(target)) {
                        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.SOPPRESSIONE));
                    }
                }
                case "GUASTO" -> {
                    // Solo i guasti CRITICI inibiscono la stazione: i WARNING (es. keepalive
                    // sensore mancante) sono informativi e la stazione resta agibile.
                    if ("STAZIONE".equals(sorgenteTipo) && !sorgenteId.isEmpty()
                            && "CRITICAL".equalsIgnoreCase(severita)) {
                        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.GUASTO_STAZIONE, sorgenteId, null));
                    }
                }

                case "STATO_STAZIONE" -> {
                    // La Centrale dichiara in che stato ha messo una stazione: che cosa
                    // significhi per il convoglio lo decide il gestore.
                    if ("STAZIONE".equals(sorgenteTipo) && !sorgenteId.isEmpty()) {
                        String statoDichiarato = CampiJson.testo(alert, "stato", "");
                        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.STATO_STAZIONE, sorgenteId, statoDichiarato));
                    }
                }

                case "RESOLVED" -> {
                    if ("STAZIONE".equals(sorgenteTipo)) {
                        gestore.gestisci(EventoTreno.suStazione(EventoTreno.Tipo.GUASTO_STAZIONE_RISOLTO, sorgenteId, null));
                    } else if ("TRENO".equals(sorgenteTipo) && trainDB.trenoId.equals(sorgenteId)) {
                        // Rientro dall'emergenza di bordo
                        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.GUASTO_BORDO_RISOLTO));
                    }
                }
                case "RITARDO" -> {
                    // L'altra meta' del sistema di comunicazione in ingresso chiesto dal
                    // testo del progetto: il convoglio riceve "guasti o ritardi". Il numero
                    // non lo calcola lui, glielo dice la Centrale, che e' l'unico nodo a
                    // conoscere l'orario di servizio; qui lo si prende e basta. Non passa dal
                    // gestore perche' non fa muovere nessun automa e nessuna guardia lo legge.
                    if (trainDB.trenoId.equals(target)) {
                        int ritardo = CampiJson.intero(alert, "ritardoMinuti", 0);
                        int variazione = CampiJson.intero(alert, "variazioneMinuti", 0);
                        String causa = CampiJson.testo(alert, "causa", "");
                        trainDB.ritardoMinuti = Math.max(0, ritardo);
                        trainDB.ritardoRilevatoA = sorgenteId;
                        trainDB.ritardoAggiornatoIl = Instant.now();
                        String dove = sorgenteId.isEmpty() ? "?" : sorgenteId;
                        if (trainDB.ritardoMinuti > 0) {
                            LOG.warning(String.format("⏱️ [ALERT] La Centrale mi comunica %d minuti di ritardo (%+d a %s, causa %s)",
                                    trainDB.ritardoMinuti, variazione, dove,
                                    causa.isEmpty() ? "non dichiarata" : causa));
                        } else {
                            // Tornare in orario non e' un allarme: e' la notizia buona.
                            LOG.info(String.format("⏱️ [ALERT] La Centrale mi da' di nuovo in orario (%+d a %s)",
                                    variazione, dove));
                        }
                    }
                }
                case "ITINERARIO_AGGIORNATO" -> {
                    if (trainDB.trenoId.equals(target)) {
                        LOG.info("🗺️ [ALERT] La Centrale ha aggiornato il mio itinerario: lo ricarico");
                        generatoreTempo.richiediRicaricaItinerario();
                    }
                }
                default -> LOG.fine(String.format("[ALERT] Evento %s ignorato: %s", tipoEvento, payload));
            }
        } catch (Exception e) {
            LOG.warning(String.format("Alert non interpretabile (%s): %s", e.getMessage(), payload));
        }
    }
//---- END listeners
}
