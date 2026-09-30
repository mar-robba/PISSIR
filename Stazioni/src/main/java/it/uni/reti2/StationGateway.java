package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.logging.Logger;

/**
 * StationGateway riceve i messaggi MQTT che interessano la stazione: gli alert della Centrale
 * su railway/alerts e i passaggi dei treni su railway/treno-simulatoriSensori/+.
 *
 * <p>È un generatore di eventi: scarta i messaggi che non riguardano questa stazione e
 * traduce gli altri in un EventoStazione per GestoreEventi. Non decide come reagire e non
 * scrive lo stato del nodo. Quello che esce verso la Centrale lo preparano PubblicatoreAlert e
 * PubblicatoreTransiti e lo pubblica il ConsumatoreBuffer.</p>
 *
 * <p>I due metodi girano sul thread di Paho: il main li registra presso ClientMqtt come
 * destinatari dei due topic, e la conferma al broker (il PUBACK del QoS 1) la manda Paho
 * quando il metodo è finito.</p>
 */
public class StationGateway {

    private static final Logger LOG = Logger.getLogger(StationGateway.class.getName());

    /** Il marcatore con cui la Centrale firma tutti i suoi alert (campo "origine"). */
    private static final String ORIGINE_CENTRALE = "CENTRALE";

    /** Stato del nodo: serve l'id per riconoscere i messaggi di questa stazione. Assegnato dal main. */
    DBLocale dbLocale;

    /** Dove vanno gli eventi tradotti dagli alert. */
    GestoreEventi gestore;

    /**
     * I sensori di binario: sono loro a vedere passare i convogli.
     * Il gateway non registra i transiti di testa sua, li fa rilevare al sensore.
     */
    SimulatoreSensori simulatoreSensori;

    // ==== listener per canali in input

    /**
     * Intercetta gli eventi in ingresso dal canale condiviso railway/alerts. Riguardano la
     * stazione solo quelli con sorgenteTipo STAZIONE e sorgenteId uguale al suo, e fra questi
     * solo quelli firmati dalla Centrale ("origine": "CENTRALE"). Il discriminante è il campo
     * "tipoEvento" del payload:
     * - RESOLVED: la Centrale ha chiuso il guasto, si torna ONLINE (RIPRISTINO_DALLA_CENTRALE);
     * - GUASTO CRITICAL: RF02.1.2.2.1, la Centrale ha dedotto che questa stazione non è
     *   percorribile, per esempio per un convoglio deragliato (GUASTO_DALLA_CENTRALE);
     * - MAINTENANCE_DISPATCHED: gli operatori sono in viaggio. Non cambia niente nel nodo, che
     *   resta GUASTA e continua a trattenere i treni, quindi non passa dal gestore: lo si scrive
     *   nel log e basta.
     * Gli altri tipi (STOP, ITINERARIO_AGGIORNATO, RITARDO) non richiedono azioni lato stazione,
     * e nemmeno STATO_STAZIONE: quello e' l'annuncio con cui la Centrale dice ai treni che la
     * stazione e' guasta o in manutenzione, ma il nodo di se' stesso conosce solo ONLINE e
     * GUASTA (RF02.3.4) e in manutenzione non deve passarci, se no smetterebbe di trattenere
     * i convogli proprio mentre i tecnici lavorano.
     *
     * <p>Perché serve la firma: railway/alerts è un topic condiviso, e la stazione ci pubblica
     * e ci è anche sottoscritta, quindi si risente i propri GUASTO e RESOLVED, uguali a quelli
     * della Centrale tranne che per il marcatore. Prima li trattava come se arrivassero dalla
     * Centrale, e con lo store and forward un RESOLVED spedito in ritardo (rimasto nel buffer
     * mentre la rete era giù) poteva rimettere ONLINE una stazione tornata GUASTA nel
     * frattempo. La Centrale fa lo stesso controllo, al contrario, per non riascoltare i suoi.</p>
     *
     * @param payload Il contenuto dell'alert (JSON).
     */
    public void riceviAlert(String payload) {
        // railway/alerts è un canale condiviso: qui dentro passano anche i ritardi dei treni
        // e i guasti delle altre stazioni, che a questo nodo non dicono niente. Stamparli
        // tutti per intero riempiva il log, quindi il payload grezzo resta in DEBUG e le
        // righe visibili sono solo quelle degli alert che riguardano davvero la stazione.
        LOG.fine("🚨 [ALERT RICEVUTO] " + payload);

        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String tipoEvento = CampiJson.testo(json, "tipoEvento", "");
            boolean diQuestaStazione = "STAZIONE".equals(CampiJson.testo(json, "sorgenteTipo", ""))
                    && dbLocale.stazioneId.equals(CampiJson.testo(json, "sorgenteId", ""));
            boolean dallaCentrale = ORIGINE_CENTRALE.equalsIgnoreCase(CampiJson.testo(json, "origine", ""));

            // un altro nodo, oppure l'eco di un allarme partito da questa stazione
            if (!diQuestaStazione || !dallaCentrale) {
                return;
            }

            switch (tipoEvento) {
                case "RESOLVED" -> gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.RIPRISTINO_DALLA_CENTRALE));
                case "GUASTO" -> {
                    if ("CRITICAL".equalsIgnoreCase(CampiJson.testo(json, "severita", ""))) {
                        gestore.gestisci(EventoStazione.guastoDallaCentrale(CampiJson.testo(json, "messaggio", "")));
                    }
                }
                case "MAINTENANCE_DISPATCHED" -> LOG.info("🛠️ Operatori di manutenzione in arrivo verso la stazione!");
                default -> {
                    // Nessuna azione per gli altri eventi del canale condiviso
                }
            }
        } catch (Exception e) {
            LOG.severe("Payload alert non interpretabile: " + payload);
        }
    }

    /**
     * Intercetta i passaggi fisici dei treni pubblicati sul topic wildcard
     * railway/treno-simulatoriSensori/+. Ogni stazione riceve i passaggi di TUTTI i
     * treni, quindi filtra i soli messaggi indirizzati al proprio stazioneId.
     *
     * <p>Il passaggio non è un evento della stazione: è il convoglio che passa davanti al
     * binario, e a vederlo fisicamente è il sensore piazzato lì, che poi lo comunica alla
     * stazione via REST (POST /stazione/sensore/treno, che diventa l'evento TRANSITO). Se quel
     * sensore è fermo (fail-stop) il transito non viene rilevato da nessuno: è esattamente il
     * motivo per cui una stazione senza sensori è una stazione guasta, e non un dettaglio da
     * aggirare registrando il transito lo stesso.</p>
     *
     * @param payload Il contenuto del messaggio di passaggio (JSON).
     */
    public void riceviPassaggio(String payload) {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String stazioneId = CampiJson.testo(json, "stazioneId", "");

            // Il passaggio riguarda un'altra stazione: si ignora
            if (!dbLocale.stazioneId.equals(stazioneId)) {
                return;
            }

            String trenoId = CampiJson.testo(json, "trenoId", "");
            String tipo = CampiJson.testo(json, "tipo", "");
            LOG.info(String.format("🚂 [PASSAGGIO] Treno %s in %s dalla stazione %s", trenoId, tipo, stazioneId));

            simulatoreSensori.rilevaPassaggio(trenoId, tipo);
        } catch (Exception e) {
            LOG.severe("Payload passaggio non interpretabile: " + payload);
        }
    }
}
