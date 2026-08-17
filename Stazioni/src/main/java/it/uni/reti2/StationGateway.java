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
     * - RESOLVED riferito a questa stazione o a una catena che la teneva ferma: si torna ONLINE.
     * - GUASTO di un TRENO che è fermo sui binari di questa stazione: la stazione diventa non
     *   percorribile (RF02.1.2.2.1). È una reazione, non un guasto della stazione.
     * - MAINTENANCE_DISPATCHED riferito a questa stazione: gli operatori sono in viaggio.
     * Gli altri tipi (STOP, ITINERARIO_AGGIORNATO) non richiedono azioni lato stazione.
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
            String catenaId = json.path("catenaId").asText("");

            switch (tipoEvento) {
                case "RESOLVED":
                    // La Centrale ha risolto un guasto: se riguarda questa stazione si riparte.
                    // Il secondo ramo è lo srotolamento per catena: la stazione era diventata
                    // impercorribile per colpa di un altro nodo (tipicamente un convoglio guasto
                    // sui suoi binari). Il guasto che si chiude è quello di quel nodo, non il
                    // suo, ma è lo stesso fatto: la catena è la stessa.
                    if (("STAZIONE".equals(json.path("sorgenteTipo").asText())
                            && dbLocale.stazioneId.equals(sorgenteId))
                            || (!catenaId.isBlank() && dbLocale.cateneImpercorribili.contains(catenaId))) {
                        tornaPercorribile(catenaId, "Chiuso il guasto della catena "
                                + (catenaId.isBlank() ? "(non dichiarata)" : catenaId));
                    }
                    if (!catenaId.isBlank()) {
                        dbLocale.cateneReagite.remove(catenaId);
                    }
                    break;
                case "GUASTO":
                    // Un convoglio si guasta mentre è fermo sui miei binari: la stazione non è
                    // più percorribile (RF02.1.2.2.1). La decisione è mia e non della Centrale,
                    // così vale anche mentre la rete è giù, ed è l'unico nodo che sa quali
                    // convogli ha davvero sui binari in questo momento.
                    if ("TRENO".equals(json.path("sorgenteTipo").asText())
                            && "CRITICAL".equalsIgnoreCase(json.path("severita").asText("CRITICAL"))
                            && dbLocale.treniPresenti.containsKey(sorgenteId)) {
                        dichiaraNonPercorribile("TRENO", sorgenteId, catenaId,
                                String.format("Stazione %s non percorribile: convoglio %s guasto in stazione",
                                        dbLocale.stazioneId, sorgenteId));
                    }
                    break;
                case "MAINTENANCE_DISPATCHED":
                    // Notifica informativa: la Centrale ha inviato gli operatori. Lo stato
                    // MANUTENZIONE non esiste su questo nodo ed è voluto (RF02.3.4: la stazione
                    // dichiara di sé solo ONLINE o GUASTA), lo tiene la Centrale.
                    if (dbLocale.stazioneId.equals(sorgenteId)) {
                        LOG.info("🛠️ Operatori di manutenzione in arrivo verso la stazione!");
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

            // Stazione guasta: il treno appena entrato viene trattenuto con un alert dedicato.
            // L'alert eredita la catena in corso: è la stessa avaria, non una nuova, e la
            // Centrale deve poterlo riconoscere invece di contarla due volte.
            if ("GUASTA".equals(dbLocale.stato) && "ENTRATA".equals(tipo)) {
                inviaGuasto(String.format("Stazione %s guasta: treno %s trattenuto", dbLocale.stazioneId, trenoId),
                        "CRITICAL", null, dbLocale.catenaAttiva);
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
        inviaGuasto(descrizione, severita, tipoGuasto, null);
    }

    /**
     * Come sopra, dichiarando anche a quale catena di eventi il guasto appartiene.
     *
     * <p>La catena è l'identificativo del guasto primario da cui la situazione discende. Un
     * guasto ai sistemi di terra non ne ha una: è lui il primo anello e la catena parte da lui
     * (il campo resta vuoto e la assegna la Centrale). Ce l'ha invece l'alert che la stazione
     * pubblica quando è diventata impercorribile per colpa di qualcun altro: per i convogli che
     * lo leggono quello è un fatto primario come un altro — si fermano senza chiedersi chi
     * l'abbia causato — ma la catena che si porta dietro dice alla Centrale che è sempre la
     * stessa avaria e a chi va addebitata.</p>
     *
     * @param descrizione La descrizione del problema rilevato.
     * @param severita    "CRITICAL" oppure "WARNING".
     * @param tipoGuasto  Classificazione, oppure null.
     * @param catenaId    Catena ereditata, oppure null se il guasto è primario.
     */
    public void inviaGuasto(String descrizione, String severita, String tipoGuasto, String catenaId) {
        String catena = catenaId;
        if ("CRITICAL".equals(severita)) {
            dbLocale.stato = "GUASTA";
            if (catena == null || catena.isBlank()) {
                // Guasto primario: la catena parte da qui e la conia la stazione, una volta
                // sola per episodio di indisponibilità. Serve che sia STABILE, perché finché la
                // stazione resta guasta manda un alert per ogni treno che entra: se ogni alert
                // portasse una catena diversa, chi reagisce non riconoscerebbe che è sempre la
                // stessa avaria e si rifermerebbe da capo a ogni messaggio.
                if (dbLocale.catenaAttiva == null) {
                    dbLocale.catenaAttiva = dbLocale.stazioneId + "-" + Instant.now().toEpochMilli();
                }
                catena = dbLocale.catenaAttiva;
            }
            // Da qui la stazione non è percorribile, e lo resta finché QUESTA catena non si
            // chiude: le altre eventualmente aperte non c'entrano niente con lei.
            dbLocale.cateneImpercorribili.add(catena);
        }
        String campoTipo = tipoGuasto != null ? String.format("\"tipoGuasto\":\"%s\",", tipoGuasto) : "";
        String campoCatena = catena != null && !catena.isBlank()
                ? String.format("\"catenaId\":\"%s\",", catena) : "";
        String alertJson = String.format(
                "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\",%s%s\"severita\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, campoTipo, campoCatena, severita, descrizione, Instant.now().toString());

        inviaOppureBufferizza("alert", alertJson, alertsEmitter);
    }

    // ================= reazioni a catena (eventi derivati)

    /**
     * La stazione diventa non percorribile <b>per colpa di un evento di un altro nodo</b>.
     *
     * <p>È il punto di innesto delle conseguenze a catena lato stazione: chi rileva la
     * condizione (per esempio un convoglio che si guasta mentre è fermo sui binari, RF02.1.2.2.1)
     * chiama questo metodo e non {@code inviaGuasto}, perché qui non c'è nessun guasto della
     * stazione da aprire.</p>
     *
     * <p>Fa tre cose, in quest'ordine: cambia lo stato locale, lo <b>dichiara</b> alla Centrale
     * come evento derivato (così stato e storico registrano anche il perché, senza aprire un
     * secondo allarme per la stessa avaria) e ripubblica il fatto come GUASTO ereditando la
     * catena, perché i convogli in avvicinamento reagiscono agli stati delle stazioni, non alle
     * reazioni altrui. La regola "una reazione per catena" impedisce che il giro si ripeta.</p>
     *
     * @param causaTipo   Tipo del nodo che ha causato la condizione (di norma TRENO).
     * @param causaId     Identificativo di quel nodo.
     * @param catenaId    Catena di eventi da ereditare.
     * @param descrizione Descrizione leggibile per l'operatore.
     */
    public void dichiaraNonPercorribile(String causaTipo, String causaId, String catenaId, String descrizione) {
        if (catenaId == null || catenaId.isBlank()) {
            LOG.warn("Reazione senza catena: non si può dichiarare la stazione impercorribile");
            return;
        }
        if (!dbLocale.cateneReagite.add(catenaId)) {
            LOG.debugf("🔁 Ho già reagito alla catena %s: nessuna nuova reazione", catenaId);
            return;
        }

        String statoPrecedente = dbLocale.stato;
        dbLocale.stato = "GUASTA";
        dbLocale.catenaAttiva = catenaId;
        dbLocale.cateneImpercorribili.add(catenaId);
        LOG.warnf("⛔ Stazione non percorribile per %s %s (catena %s)", causaTipo, causaId, catenaId);

        inviaReazione("GUASTA", statoPrecedente, causaTipo, causaId, catenaId, true, descrizione);
        // Ripubblicato come GUASTO: per i convogli è un fatto primario come un altro, ma
        // porta con sé la catena di partenza.
        inviaGuasto(descrizione, "CRITICAL", "stazione_impercorribile", catenaId);
    }

    /**
     * Una delle catene che tenevano la stazione impercorribile si è chiusa.
     *
     * <p>La stazione torna ONLINE <b>solo se non ne restano altre aperte</b>: è la risposta alla
     * domanda che il caso RF02.1.2.2.1 si porta dietro, cioè se una stazione con un guasto
     * proprio ancora aperto debba tornare percorribile quando si ripara il convoglio che le stava
     * sui binari. No: sarebbe percorribile sulla carta e impraticabile nei fatti, e i convogli ci
     * entrerebbero dentro. Il ritorno in servizio lo decide l'ultima catena che si chiude.</p>
     *
     * @param catenaChiusa La catena del guasto appena risolto (può essere vuota per gli alert
     *                     vecchio stile che non la dichiarano).
     * @param motivo       Perché è tornata agibile (finisce nei log e nella dichiarazione).
     */
    public void tornaPercorribile(String catenaChiusa, String motivo) {
        boolean catenaNota = catenaChiusa != null && !catenaChiusa.isBlank();
        if (catenaNota) {
            dbLocale.cateneImpercorribili.remove(catenaChiusa);
            dbLocale.cateneReagite.remove(catenaChiusa);
        }

        if (!dbLocale.cateneImpercorribili.isEmpty()) {
            // Resta impercorribile: la catena che si è chiusa non era l'unica. Non si dichiara
            // niente perché niente è cambiato (RF02.7: si registrano i cambiamenti), si sposta
            // solo la catena da citare negli alert su una di quelle ancora aperte.
            dbLocale.catenaAttiva = dbLocale.cateneImpercorribili.iterator().next();
            LOG.warnf("⛔ %s, ma la stazione resta GUASTA: catene ancora aperte %s",
                    motivo, dbLocale.cateneImpercorribili);
            return;
        }

        String statoPrecedente = dbLocale.stato;
        dbLocale.stato = "ONLINE";
        dbLocale.catenaAttiva = null;
        LOG.infof("🔧 %s: stato di nuovo ONLINE.", motivo);

        // La dichiarazione parte solo se c'è stato davvero un cambiamento da raccontare e una
        // catena da chiudere in Centrale.
        if (catenaNota && !"ONLINE".equalsIgnoreCase(statoPrecedente)) {
            inviaReazione("ONLINE", statoPrecedente, null, null, catenaChiusa, false, motivo);
        }
    }

    /**
     * Dichiara alla Centrale un cambiamento di stato subito per causa altrui (tipoEvento
     * REAZIONE). Passa dallo stesso store-and-forward degli altri eventi: se la rete è giù la
     * dichiarazione aspetta nel buffer e rientra nell'ordine giusto, altrimenti la Centrale
     * potrebbe leggere il rientro prima del blocco.
     *
     * @param nuovoStato      Stato nuovo della stazione.
     * @param statoPrecedente Stato da cui proviene.
     * @param causaTipo       Tipo del nodo che ha causato il cambiamento (può essere null).
     * @param causaId         Identificativo di quel nodo (può essere null).
     * @param catenaId        Catena di appartenenza.
     * @param attiva          true se la stazione entra nella catena, false se ne esce.
     * @param motivo          Descrizione leggibile.
     */
    private void inviaReazione(String nuovoStato, String statoPrecedente, String causaTipo, String causaId,
                               String catenaId, boolean attiva, String motivo) {
        String json = String.format(
                "{\"tipoEvento\":\"REAZIONE\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\","
                        + "\"nuovoStato\":\"%s\",\"statoPrecedente\":\"%s\","
                        + "\"causaTipo\":\"%s\",\"causaId\":\"%s\",\"catenaId\":\"%s\","
                        + "\"attiva\":%b,\"motivo\":\"%s\",\"timestamp\":\"%s\"}",
                dbLocale.stazioneId, nuovoStato, statoPrecedente == null ? "" : statoPrecedente,
                causaTipo == null ? "" : causaTipo, causaId == null ? "" : causaId, catenaId,
                attiva, motivo == null ? "" : motivo, Instant.now().toString());

        inviaOppureBufferizza("alert", json, alertsEmitter);
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
