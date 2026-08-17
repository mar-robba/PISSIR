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
            // Catena di eventi a cui l'alert appartiene: è l'id del guasto primario da cui
            // discende. Se il campo non c'è (alert vecchio stile) si usa l'id del guasto, e
            // in mancanza anche di quello la sorgente: serve comunque una chiave stabile per
            // non reagire due volte allo stesso fatto.
            String catenaId = primoNonVuoto(
                    alert.path("catenaId").asText(""),
                    alert.path("guastoId").asText(""),
                    sorgenteId);

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
                        // Memorizza la stazione guasta (con la sua catena) per i controlli di partenza
                        trainDB.stazioniGuaste.put(sorgenteId, catenaId);
                        // Il treno si blocca solo se la stazione guasta lo riguarda direttamente
                        if (sorgenteId.equals(trainDB.prossimaStazione) || sorgenteId.equals(trainDB.stazioneCorrente)) {
                            LOG.warnf("🚨 [ALERT] Stazione %s guasta sulla mia rotta: mi fermo", sorgenteId);
                            journeyEngine.bloccaPerGuastoStazione(sorgenteId, catenaId);
                        }
                    }
                    // Un arco della rete e' occupato da un'avaria (RF02.1.2.2.2): vale come una
                    // stazione chiusa, e lo si tratta allo stesso modo.
                    if ("TRATTA".equals(sorgenteTipo) && !sorgenteId.isEmpty()) {
                        trainDB.tratteGuaste.put(sorgenteId, catenaId);
                        // Se è la mia stessa dichiarazione che torna indietro non c'è niente da
                        // fare: sono già fermo perché sono guasto io, e non ha senso bloccarmi
                        // per un arco che ho occupato io.
                        if (!sorgenteId.equals(trainDB.trattaDichiarataImpercorribile)) {
                            LOG.warnf("🚨 [ALERT] Tratta %s non percorribile: la considero chiusa", sorgenteId);
                            journeyEngine.bloccaPerGuastoTratta(sorgenteId, catenaId);
                        }
                    }
                    // Il guasto riguarda ME e sono in linea, cioe' fra due stazioni: la
                    // conseguenza e' che l'arco che sto occupando non e' piu' percorribile
                    // (RF02.1.2.2.2). Vale sia per l'avaria che ho dichiarato io sia per quella
                    // che la Centrale ha dedotto vedendomi immobile (RF02.6.2): in tutti e due i
                    // casi il fatto e' che sono fermo li' sopra, e la severita' non c'entra.
                    if ("TRENO".equals(sorgenteTipo) && trainDB.trenoId.equals(sorgenteId)) {
                        journeyEngine.dichiaraTrattaNonPercorribile(catenaId);
                    }
                }

                case "RESOLVED" -> {
                    // La catena si chiude: il convoglio può tornare a reagire se in futuro
                    // quello stesso guasto dovesse riaprirsi.
                    trainDB.cateneReagite.remove(catenaId);
                    // Srotolamento per catena: tutte le stazioni diventate non percorribili per
                    // colpa di QUESTO guasto tornano agibili. Serve quando il RESOLVED riguarda
                    // un nodo diverso da quello che teneva fermo il convoglio: è il caso di una
                    // stazione resa impercorribile da un altro convoglio guasto sui suoi binari,
                    // dove il guasto che si chiude è quello del convoglio, non quello della
                    // stazione.
                    trainDB.stazioniGuaste.values().removeIf(catenaId::equals);
                    // Stessa cosa per gli archi: quelli resi non percorribili da QUESTA avaria
                    // tornano liberi. E se l'arco l'avevo dichiarato io, sono io a dover
                    // dichiarare che è di nuovo percorribile: la tratta non parla.
                    trainDB.tratteGuaste.values().removeIf(catenaId::equals);
                    journeyEngine.dichiaraTrattaPercorribile(catenaId);

                    if (catenaId.equals(trainDB.catenaBloccante)) {
                        LOG.infof("✅ [ALERT] Catena %s chiusa: riprendo il viaggio", catenaId);
                        journeyEngine.sbloccaDaGuastoStazione();
                    } else if ("STAZIONE".equals(sorgenteTipo)) {
                        // La stazione torna agibile
                        trainDB.stazioniGuaste.remove(sorgenteId);
                        if (sorgenteId.equals(trainDB.stazioneBloccante)) {
                            LOG.infof("✅ [ALERT] Guasto risolto alla stazione %s: riprendo il viaggio", sorgenteId);
                            journeyEngine.sbloccaDaGuastoStazione();
                        }
                    } else if ("TRENO".equals(sorgenteTipo) && trainDB.trenoId.equals(sorgenteId)) {
                        // Rientro dall'emergenza di bordo: il treno è pronto a ripartire e la
                        // catena aperta dalla sua avaria si chiude.
                        trainDB.catenaGuastoDiBordo = null;
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

    /**
     * Primo valore non vuoto fra quelli passati.
     * Serve a ricavare la catena di un alert quando il campo dedicato non c'è: gli alert
     * pubblicati prima di questa versione non lo hanno, e senza una chiave stabile il
     * convoglio reagirebbe di nuovo a ogni ripetizione dello stesso messaggio.
     *
     * @param valori I candidati, in ordine di preferenza.
     * @return Il primo non vuoto, stringa vuota se non ce n'è nessuno.
     */
    private String primoNonVuoto(String... valori) {
        for (String valore : valori) {
            if (valore != null && !valore.isBlank()) {
                return valore;
            }
        }
        return "";
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

        // Catena delle conseguenze: il guasto di bordo è un evento primario, quindi la catena
        // parte da qui. La conia il treno e la riusa finché l'avaria non è risolta, così tutto
        // quello che ne discende (la stazione che diventa impercorribile perché il convoglio è
        // fermo sui suoi binari, gli altri convogli che si fermano) resta riconducibile a
        // questa avaria e non a tante avarie diverse.
        if (trainDB.catenaGuastoDiBordo == null) {
            trainDB.catenaGuastoDiBordo = trainDB.trenoId + "-" + Instant.now().toEpochMilli();
        }

        // Composizione del payload JSON per l'alert (formato condiviso su railway/alerts)
                            // formattazione stringhe java classico
        String alertJson = String.format(Locale.US,
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"TRENO\",\"sorgenteId\":\"%s\",\"severita\":\"%s\",\"messaggio\":\"%s\",\"catenaId\":\"%s\",\"timestamp\":\"%s\"}",
                trainDB.trenoId, severita, descrizione, trainDB.catenaGuastoDiBordo, Instant.now().toString());

        try {
            // Invio del messaggio in modo reattivo
            alertsEmitter.send(alertJson);
        } catch (Exception e) {
            LOG.error("Errore invio guasto: " + e.getMessage());
        }
    }

    /**
     * Ripubblica come GUASTO il fatto che un arco della rete non è percorribile, ereditando la
     * catena dell'avaria che lo ha causato (RF02.1.2.2.2).
     *
     * <p>Perché un GUASTO e non solo la reazione: gli altri convogli reagiscono ai guasti, non
     * alle reazioni altrui — per loro "la tratta T1 è chiusa" è un fatto primario come la
     * stazione guasta, e si fermano senza chiedersi chi l'abbia causato. La catena ereditata
     * dice alla Centrale che è sempre la stessa avaria e non una seconda. L'allarme che ne nasce
     * è giusto che l'operatore lo veda: un pezzo di rete è fuori uso.</p>
     *
     * <p>Nota sulla sorgente: qui {@code sorgenteTipo} è TRATTA, cioè un tipo di sorgente che
     * prima non esisteva. È il requisito stesso a chiederlo, visto che l'elenco degli allarmi
     * deve dire "quale stazione, quale convoglio, quale tratta".</p>
     *
     * @param trattaId    Arco diventato non percorribile.
     * @param descrizione Descrizione leggibile per l'operatore.
     * @param catenaId    Catena ereditata dall'avaria che lo ha occupato.
     */
    public void inviaGuastoTratta(String trattaId, String descrizione, String catenaId) {
        String alertJson = String.format(Locale.US,
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"TRATTA\",\"sorgenteId\":\"%s\","
                        + "\"tipoGuasto\":\"tratta_impercorribile\",\"severita\":\"CRITICAL\","
                        + "\"messaggio\":\"%s\",\"catenaId\":\"%s\",\"timestamp\":\"%s\"}",
                trattaId, descrizione, catenaId, Instant.now().toString());

        try {
            alertsEmitter.send(alertJson);
        } catch (Exception e) {
            LOG.error("Errore invio guasto tratta: " + e.getMessage());
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
