package it.uni.reti2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uni.reti2.DBLocale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.time.Instant;
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
     * Parser JSON per interpretare i payload dei messaggi MQTT in ingresso.
     */
    private final ObjectMapper mapper = new ObjectMapper();

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
     * Intercetta gli eventi in ingresso dal canale condiviso railway/alerts.
     * Il discriminante è il campo "tipoEvento" del payload:
     * - RESOLVED riferito a questa stazione: la Centrale ha risolto il guasto, si torna ONLINE.
     * - MAINTENANCE_DISPATCHED riferito a questa stazione: gli operatori sono in viaggio.
     * Gli altri tipi (GUASTO, STOP, ITINERARIO_AGGIORNATO) non richiedono azioni lato stazione.
     *
     * @param message Il messaggio in arrivo dal broker.
     * @return CompletionStage che indica il processamento completato.
     */
    @Incoming("alerts-in")
    public CompletionStage<Void> riceviAlert(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());
        LOG.warnf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload);

        try {
            JsonNode json = mapper.readTree(payload);
            String tipoEvento = json.path("tipoEvento").asText();
            String sorgenteId = json.path("sorgenteId").asText();

            switch (tipoEvento) {
                case "RESOLVED":
                    // La Centrale ha risolto un guasto: se riguarda questa stazione si riparte
                    if ("STAZIONE".equals(json.path("sorgenteTipo").asText())
                            && dbLocale.stazioneId.equals(sorgenteId)) {
                        dbLocale.stato = "ONLINE";
                        LOG.info("🔧 Stazione ripristinata dalla Centrale! Stato di nuovo ONLINE.");
                    }
                    break;
                case "MAINTENANCE_DISPATCHED":
                    // Notifica informativa: la Centrale ha inviato gli operatori
                    if (dbLocale.stazioneId.equals(sorgenteId)) {
                        LOG.info("🛠️ Operatori di manutenzione in arrivo verso la stazione!");
                    }
                    break;
                case "GUASTO":
                    // RF02.1.2.2.1: la Centrale ha dedotto che questa stazione è non
                    // percorribile (es. convoglio deragliato). Si allinea lo stato locale.
                    if ("STAZIONE".equals(json.path("sorgenteTipo").asText())
                            && dbLocale.stazioneId.equals(sorgenteId)
                            && "CRITICAL".equalsIgnoreCase(json.path("severita").asText(""))) {
                        dbLocale.stato = "GUASTA";
                        LOG.warnf("🚨 Stazione marcata GUASTA dalla Centrale: %s",
                                json.path("messaggio").asText(""));
                    }
                    break;
                default:
                    // Nessuna azione per gli altri eventi del canale condiviso
                    break;
            }
        } catch (Exception e) {
            LOG.errorf("Payload alert non interpretabile: %s", payload);
        }
        return message.ack();
    }

    /**
     * Intercetta i passaggi fisici dei treni pubblicati sul topic wildcard
     * railway/train/+/passaggio. Ogni stazione riceve i passaggi di TUTTI i treni,
     * quindi filtra i soli messaggi indirizzati al proprio stazioneId.
     * Aggiorna la mappa dei treni presenti e notifica il transito ufficiale alla Centrale.
     * Se la stazione è GUASTA e un treno entra, viene pubblicato un alert che lo trattiene.
     *
     * @param message Il messaggio di passaggio in arrivo dal broker.
     * @return CompletionStage che indica il processamento completato.
     */
    @Incoming("passaggio-in")
    public CompletionStage<Void> riceviPassaggio(org.eclipse.microprofile.reactive.messaging.Message<byte[]> message) {
        String payload = new String(message.getPayload());

        try {
            JsonNode json = mapper.readTree(payload);
            String stazioneId = json.path("stazioneId").asText();

            // Il passaggio riguarda un'altra stazione: si ignora
            if (!dbLocale.stazioneId.equals(stazioneId)) {
                return message.ack();
            }

            String trenoId = json.path("trenoId").asText();
            String tipo = json.path("tipo").asText();
            LOG.infof("🚂 [PASSAGGIO] Treno %s in %s dalla stazione %s", trenoId, tipo, stazioneId);

            // Aggiorna la fotografia locale dei binari
            if ("ENTRATA".equals(tipo)) {
                dbLocale.treniPresenti.put(trenoId, Instant.now());
            } else if ("USCITA".equals(tipo)) {
                dbLocale.treniPresenti.remove(trenoId);
            }

            // Notifica ufficiale del transito verso la Centrale (fonte per la storicizzazione)
            inviaTransito(trenoId, tipo);

            // Stazione guasta: il treno appena entrato viene trattenuto con un alert dedicato
            if ("GUASTA".equals(dbLocale.stato) && "ENTRATA".equals(tipo)) {
                inviaGuasto(String.format("Stazione %s guasta: treno %s trattenuto", dbLocale.stazioneId, trenoId), "CRITICAL");
            }
        } catch (Exception e) {
            LOG.errorf("Payload passaggio non interpretabile: %s", payload);
        }
        return message.ack();
    }

    // ---------------- END  listener per canali in imput

    // ================= inizion degli inviatori dei messaggi in out dalla stazione

    /**
     * Segnala un guasto infrastrutturale locale con severità CRITICAL (default).
     *
     * @param descrizione La descrizione del problema rilevato.
     */
    public void inviaGuasto(String descrizione) {
        inviaGuasto(descrizione, "CRITICAL");
    }

    /**
     * Segnala un guasto dell'infrastruttura di terra: è la stazione a non essere più
     * agibile, quindi il tipo lasciato implicito è {@code stazione_guasta}.
     *
     * @param descrizione La descrizione del problema rilevato.
     * @param severita "CRITICAL" oppure "WARNING".
     */
    public void inviaGuasto(String descrizione, String severita) {
        inviaGuasto(descrizione, severita, null);
    }

    /**
     * Segnala un guasto locale inviando un alert alla Centrale nel formato condiviso
     * del canale railway/alerts (campo discriminante "tipoEvento").
     * Con severità CRITICAL la stazione passa in stato GUASTA; con WARNING
     * (es. sensore che non risponde) la stazione resta operativa.
     * Se la rete è giù l'evento viene persistito nel buffer locale.
     *
     * <p>Il campo {@code tipoGuasto} è la classificazione dichiarata dalla sorgente. Prima
     * non c'era e la Centrale la indovinava cercando la parola "sensore" nel testo del
     * messaggio: siccome i guasti di terra parlano quasi sempre di sensori, finivano tutti
     * classificati come {@code sensore_offline} e l'operatore si ritrovava senza il comando
     * per mandare la squadra di manutenzione. Chi segnala il guasto sa che tipo è: lo dice.</p>
     *
     * @param descrizione La descrizione del problema rilevato.
     * @param severita "CRITICAL" oppure "WARNING".
     * @param tipoGuasto Classificazione ("sensore_offline"), oppure null per lasciar
     *                   decidere alla Centrale in base al tipo di sorgente.
     */
    public void inviaGuasto(String descrizione, String severita, String tipoGuasto) {
        if ("CRITICAL".equals(severita)) {
            dbLocale.stato = "GUASTA";
        }
        String campoTipo = tipoGuasto != null ? String.format("\"tipoGuasto\":\"%s\",", tipoGuasto) : "";
        String alertJson = String.format(
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\",%s\"severita\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, campoTipo, severita, descrizione, Instant.now().toString());

        inviaOppureBufferizza("alert", alertJson, alertsEmitter);
    }

    /**
     * Invia un evento telemetrico relativo al passaggio fisico di un treno.
     * se l'invio del messaggio fallisce allora viene salvato l'evento nel buffer
     *
     * @param trenoId Identificativo del convoglio.
     * @param tipo "ENTRATA" oppure "USCITA".
     */
    public void inviaTransito(String trenoId, String tipo) {
        String payload = String.format(
                "{\"stazioneId\":\"%s\",\"trenoId\":\"%s\",\"tipo\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, trenoId, tipo, Instant.now().toString());

        inviaOppureBufferizza("transit", payload, transitEmitter);
    }

    /**
     * Logica comune di invio con Store and Forward:
     * - se la connessione verso la Centrale risulta giù NON si tenta nemmeno l'invio,
     *   l'evento finisce direttamente nel buffer locale;
     * - altrimenti si invia e, in caso di eccezione, si bufferizza e si marca la
     *   connessione come persa.
     *
     * @param canale Canale logico dell'evento ("transit" o "alert").
     * @param payload Il payload JSON da recapitare.
     * @param emitter L'emitter MQTT su cui pubblicare.
     */
    private void inviaOppureBufferizza(String canale, String payload, Emitter<String> emitter) {
        if (!dbLocale.connessioneCentrale) {
            LOG.warnf("🔌 Offline: evento '%s' salvato nel buffer locale.", canale);
            localBuffer.add(canale, payload);
            return;
        }
        try {
            emitter.send(payload);
        } catch (Exception e) {
            LOG.errorf("Impossibile inviare '%s', salvo nel buffer locale.", canale);
            // Meccanismo Store and Forward: se il broker è irraggiungibile si accoda
            localBuffer.add(canale, payload);
            dbLocale.connessioneCentrale = false;
        }
    }

    /**
     * Svuota il buffer locale reinviando ogni evento pendente sull'emitter corretto
     * (transit o alert) in ordine FIFO. Da chiamare quando la connessione verso
     * la Centrale torna disponibile. Se un invio fallisce l'evento viene rimesso
     * IN TESTA al buffer e il flush si interrompe (si riproverà al prossimo giro):
     * rimetterlo in fondo lo farebbe diventare il più recente e la Centrale
     * potrebbe ricevere l'USCITA di un treno prima della sua ENTRATA.
     */
    public void flush() {
        while (!localBuffer.isEmpty()) {
            LocalBuffer.EventoBufferizzato evento = localBuffer.poll();
            if (evento == null) {
                break;
            }
            try {
                if ("alert".equals(evento.canale())) {
                    alertsEmitter.send(evento.payload());
                } else {
                    transitEmitter.send(evento.payload());
                }
                LOG.infof("📤 Re-invio dal buffer [%s]: %s", evento.canale(), evento.payload());
            } catch (Exception e) {
                LOG.error("🔌 Flush interrotto: connessione ancora assente, evento rimesso in testa.");
                // L'evento non è partito: torna davanti a tutti (mantiene l'ordine cronologico)
                localBuffer.addFirst(evento.canale(), evento.payload());
                dbLocale.connessioneCentrale = false;
                break;
            }
        }
    }
}
