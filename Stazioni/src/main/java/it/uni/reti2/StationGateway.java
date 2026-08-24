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

    /**
     * Db locale su file: qui serve per annotare i guasti segnalati da questa stazione
     * nella tabella Guasto_Locale_Momentaneo.
     */
    @Inject
    PersistenzaLocale persistenza;

    /**
     * I sensori di binario: sono loro a vedere passare i convogli.
     * Il gateway non registra più i transiti di testa sua, li fa rilevare al sensore.
     */
    @Inject
    SimulatoreSensori simulatoreSensori;

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
        // railway/alerts è un canale condiviso: qui dentro passano anche i ritardi dei treni
        // e i guasti delle altre stazioni, che a questo nodo non dicono niente. Stamparli
        // tutti per intero riempiva il log, quindi il payload grezzo resta in DEBUG e le
        // righe visibili sono solo quelle degli alert che riguardano davvero la stazione
        // (le stampa lo switch qui sotto).
        LOG.debugf("🚨 [ALERT RICEVUTO] La Centrale dice: %s", payload);

        try {
            JsonNode json = mapper.readTree(payload);
            String tipoEvento = json.path("tipoEvento").asText();
            String sorgenteId = json.path("sorgenteId").asText();

            switch (tipoEvento) {
                case "RESOLVED":
                    // La Centrale ha risolto un guasto: se riguarda questa stazione si riparte
                    if ("STAZIONE".equals(json.path("sorgenteTipo").asText())
                            && dbLocale.stazioneId.equals(sorgenteId)) {
                        dbLocale.setStato("ONLINE");
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
                        dbLocale.setStato("GUASTA");
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
     * railway/treno-simulatoriSensori/+. Ogni stazione riceve i passaggi di TUTTI i
     * treni, quindi filtra i soli messaggi indirizzati al proprio stazioneId.
     * Il passaggio viene girato ai sensori di binario, che sono i soli a poterlo rilevare:
     * la registrazione vera e propria avviene poi in {@link #registraTransitoRilevato},
     * che è anche il punto da cui parte verso la Centrale il transito ufficiale su
     * railway/station/{stazioneId}/transit.
     *
     * @param message Il messaggio di passaggio in arrivo dal broker.
     * @return CompletionStage che indica il processamento completato.
     */
    @Incoming("sensori-in")
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

            // Il convoglio annuncia il proprio passaggio, ma a vederlo fisicamente è il
            // sensore piazzato sul binario, che poi lo comunica alla stazione via REST.
            // Se quel sensore è fermo (fail-stop) il transito non viene rilevato da nessuno:
            // è esattamente il motivo per cui una stazione senza sensori è una stazione
            // guasta, e non un dettaglio da aggirare registrando il transito lo stesso.
            simulatoreSensori.rilevaPassaggio(trenoId, tipo);
        } catch (Exception e) {
            LOG.errorf("Payload passaggio non interpretabile: %s", payload);
        }
        return message.ack();
    }

    // ---------------- END  listener per canali in imput

    // ================= inizion degli inviatori dei messaggi in out dalla stazione

    /**
     * Registra un transito che un sensore di binario ha appena rilevato.
     *
     * <p>È il punto unico in cui la stazione prende atto che un convoglio è entrato o
     * uscito: aggiorna la fotografia locale dei binari (RAM + db locale), manda alla
     * Centrale la notifica ufficiale del transito e, se la stazione è guasta, pubblica
     * l'alert che trattiene il treno appena arrivato. Ci si arriva solo dall'endpoint
     * {@code POST /stazione/sensore/treno}, cioè da un sensore: prima questa roba stava
     * dentro il listener MQTT e la stazione si registrava i passaggi da sola, senza che
     * nessun sensore avesse visto niente.</p>
     *
     * @param trenoId Identificativo del convoglio rilevato.
     * @param tipo    "ENTRATA" oppure "USCITA".
     */
    public void registraTransitoRilevato(String trenoId, String tipo) {
        // Aggiorna la fotografia locale dei binari
        if ("ENTRATA".equals(tipo)) {
            dbLocale.registraEntrataTreno(trenoId);
        } else if ("USCITA".equals(tipo)) {
            dbLocale.registraUscitaTreno(trenoId);
        }

        // Notifica ufficiale del transito verso la Centrale (fonte per la storicizzazione)
        inviaTransito(trenoId, tipo);

        // Stazione guasta: il treno appena entrato viene trattenuto con un alert dedicato
        if ("GUASTA".equals(dbLocale.getStato()) && "ENTRATA".equals(tipo)) {
            inviaGuasto(String.format("Stazione %s guasta: treno %s trattenuto", dbLocale.stazioneId, trenoId), "CRITICAL");
        }
    }

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
            dbLocale.setStato("GUASTA");
        }

        // Il guasto viene annotato anche sul db locale (Guasto_Locale_Momentaneo): resta
        // aperto finche' non arriva il RESOLVED della Centrale, e sopravvive a un riavvio
        // del nodo insieme allo stato GUASTA. La numerazione e' locale e non ha niente a
        // che vedere con l'id dell'allarme lato Centrale.
        Instant adesso = Instant.now();
        long idGuasto = persistenza.registraGuasto(
                tipoGuasto != null ? tipoGuasto : "stazione_guasta", descrizione, adesso);

        String campoTipo = tipoGuasto != null ? String.format("\"tipoGuasto\":\"%s\",", tipoGuasto) : "";
        String alertJson = String.format(
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\",%s\"severita\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, campoTipo, severita, descrizione, adesso.toString());

        inviaOppureBufferizza("alert", alertJson, alertsEmitter, idGuasto >= 0 ? idGuasto : null, null);
    }

    /**
     * Comunica alla Centrale che un guasto segnalato da questa stazione è rientrato da solo.
     *
     * <p>Serve al fail-stop dei sensori: quando i keepalive ricominciano ad arrivare la
     * stazione torna operativa per conto suo, e se non lo dicesse a nessuno l'allarme
     * resterebbe rosso sulla dashboard e i treni continuerebbero a considerare la stazione
     * bloccata (il RESOLVED sul topic condiviso lo ascoltano anche i convogli, che è quello
     * che li fa ripartire). Il formato è quello del canale railway/alerts, con lo stesso
     * discriminante "tipoEvento" usato dalla Centrale quando è lei a chiudere un guasto.</p>
     *
     * <p>Non viene messo il campo "origine": quello è il marcatore che la Centrale usa per
     * non riascoltare i propri messaggi, e questo messaggio arriva invece dal campo.</p>
     *
     * @param tipoGuasto  Classificazione del guasto che è rientrato ("sensore_offline").
     * @param descrizione Testo leggibile del rientro.
     */
    public void inviaRipristino(String tipoGuasto, String descrizione) {
        String payload = String.format(
                "{\"tipoEvento\":\"RESOLVED\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\",\"tipoGuasto\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, tipoGuasto, descrizione, Instant.now().toString());

        inviaOppureBufferizza("alert", payload, alertsEmitter, null, null);
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

        inviaOppureBufferizza("transit", payload, transitEmitter, null, trenoId);
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
     * @param idGuasto Guasto locale che ha generato l'evento, null se non c'entra.
     * @param idTreno Convoglio a cui l'evento si riferisce, null se non c'entra.
     */
    private void inviaOppureBufferizza(String canale, String payload, Emitter<String> emitter,
                                       Long idGuasto, String idTreno) {
        if (!dbLocale.connessioneCentrale) {
            LOG.warnf("🔌 Offline: evento '%s' salvato nel buffer locale.", canale);
            localBuffer.add(canale, payload, idGuasto, idTreno);
            return;
        }
        try {
            emitter.send(payload);
        } catch (Exception e) {
            LOG.errorf("Impossibile inviare '%s', salvo nel buffer locale.", canale);
            // Meccanismo Store and Forward: se il broker è irraggiungibile si accoda
            localBuffer.add(canale, payload, idGuasto, idTreno);
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
     *
     * <p>La riga sul db locale viene cancellata solo dopo che l'emitter ha accettato il
     * payload (confermaInvio): fino a quel momento l'evento resta scritto su disco, così
     * nemmeno un blackout nel mezzo del flush riesce a farlo sparire.</p>
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
                // Consegnato: adesso si può togliere anche dal db locale
                localBuffer.confermaInvio(evento);
                LOG.infof("📤 Re-invio dal buffer [%s]: %s", evento.canale(), evento.payload());
            } catch (Exception e) {
                LOG.error("🔌 Flush interrotto: connessione ancora assente, evento rimesso in testa.");
                // L'evento non è partito: torna davanti a tutti (mantiene l'ordine cronologico)
                localBuffer.addFirst(evento);
                dbLocale.connessioneCentrale = false;
                break;
            }
        }
    }
}
