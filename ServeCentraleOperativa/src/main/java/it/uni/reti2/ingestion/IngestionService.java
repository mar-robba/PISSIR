package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
// quindi Anche Treno
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.eventi.CausaEvento;
import it.uni.reti2.eventi.EventoDerivato;
import it.uni.reti2.eventi.GestoreReazioni;
import it.uni.reti2.eventi.RegistroCatene;
import it.uni.reti2.eventi.VocabolarioEventi;
import it.uni.reti2.gateway.RealtimeWebSocket;
import it.uni.reti2.persistence.RailwayRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
//è un bean CDI (@ApplicationScoped) che riceve i messaggi MQTT e poi aggiorna sia la cache interna sia il database.
/*
Un bean CDI è, in parole povere, una normale classe Java il cui ciclo di vita (creazione, distruzione)
e le cui dipendenze sono gestite interamente dal framework (il container), anziché da te manualmente tramite la parola chiave new.

CDI sta per Contexts and Dependency Injection, ed è lo standard ufficiale di Jakarta EE (e utilizzato da framework moderni come Quarkus o MicroProfile) per gestire i componenti dell'applicazione.
 */

/**
 * IngestionService è il cuore reattivo per la consumazione e memorizzazione (sink)
 * dei messaggi MQTT provenienti dai nodi sul campo (Edge), ovvero Treni e Stazioni.
 *
 * <p><b>Nota sulle transazioni.</b> I consumer NON sono più annotati {@code @Transactional}:
 * con quell'annotazione il commit avviene all'uscita del metodo, cioè FUORI dal try/catch,
 * quindi una violazione di vincolo risaliva fino al connettore reattivo e spegneva il canale
 * (da quel momento la Centrale smetteva di ricevere su quel topic fino al riavvio).
 * Adesso ogni scrittura gira dentro {@code QuarkusTransaction.requiringNew()} chiamato
 * DENTRO il try: il commit avviene lì e l'eventuale eccezione resta catturabile.
 * I metodi restano {@code @Blocking} perché usano JDBC (prima lo erano implicitamente,
 * essendo annotati {@code @Transactional}).</p>
 */
@ApplicationScoped
public class IngestionService {

    private static final Logger LOG = Logger.getLogger(IngestionService.class);

    /**
     * Marcatore inserito negli alert che la Centrale pubblica su railway/alerts.
     * Il topic è condiviso e la Centrale è sottoscritta anche in ingresso: senza
     * questo campo si riascolterebbe da sola e creerebbe un secondo Guasto per un
     * allarme che ha appena scritto lei.
     */
    public static final String ORIGINE_CENTRALE = "CENTRALE";

    /**
     * Prefisso dei messaggi dei guasti aperti automaticamente dal watchdog quando una
     * stazione smette di battere: permette alla Centrale di riconoscerli e chiuderli da
     * sola al ritorno dell'heartbeat, senza toccare i guasti segnalati dalla stazione.
     */
    public static final String MSG_HEARTBEAT_PERSO = "Heartbeat assente:";

    /**
     * Prefisso dei messaggi dei guasti che il watchdog apre da solo quando un convoglio
     * resta fermo fuori da una stazione. Ha lo stesso scopo di {@link #MSG_HEARTBEAT_PERSO}
     * per le stazioni: permette alla Centrale di riconoscere i propri guasti dedotti e di
     * chiuderli appena il treno riprende a muoversi, senza toccare i guasti che il
     * convoglio ha segnalato per conto suo (avaria di bordo).
     */
    public static final String MSG_TRENO_FERMO = "Convoglio fermo:";

    /**
     * Tipo dei guasti che dichiarano un arco della rete non percorribile (RF02.1.2.2.2).
     * È la classificazione che li distingue nell'elenco degli allarmi: la sorgente non è un
     * nodo che si è rotto ma un pezzo di infrastruttura occupato da un'avaria altrui.
     */
    public static final String TIPO_TRATTA_IMPERCORRIBILE = "tratta_impercorribile";

    @Inject
    ObjectMapper mapper;

    /**
     * Canale verso railway/alerts: serve alla Centrale per propagare sul campo
     * i guasti che rileva da sola (vedi pubblicaGuastoSuMqtt, usato dal FaultMonitor).
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RealtimeWebSocket webSocket;

    /**
     * Tutto quello che finisce sul database passa da qui: questa classe decide COSA
     * scrivere e dentro quale transazione, il repository sa COME farlo.
     */
    @Inject
    RailwayRepository repository;

    /**
     * Applica gli eventi derivati (tipoEvento REAZIONE), cioè i cambiamenti di stato che un nodo
     * dichiara di aver subito per colpa di un evento altrui. Sono l'altra metà del canale
     * rispetto ai guasti e vanno trattati in modo opposto: non aprono niente in
     * Guasti_Pervenuti_da_treni_o_Staz, aggiornano stato e storico portandosi dietro la causa.
     */
    @Inject
    GestoreReazioni gestoreReazioni;

    /**
     * Chi sta reagendo a quale catena di eventi: serve qui per chiudere la catena quando il
     * guasto che l'ha originata viene risolto.
     */
    @Inject
    RegistroCatene registroCatene;

// Ste conversioni vanno messe a posto, bisogna fare solo un unico tipo di dato
    /**
     * Converte lo stato MQTT del treno nello stato canonico usato dal DB.
     * IN_VIAGGIO→attivo, FERMO→fermo, EMERGENZA→rotto, SOPPRESSO→in manutenzione.
     * Qualunque valore non riconosciuto (compreso il campo "stato" assente) diventa
     * "fermo": la colonna ha un CHECK e uno stato inventato farebbe saltare il commit.
     */
    private String normalizzaStatoTreno(String rawStato) {
        // La traduzione sta nel vocabolario condiviso del canale: serve identica anche al
        // gestore delle reazioni, e due copie sarebbero divergute alla prima modifica.
        return VocabolarioEventi.normalizzaStatoTreno(rawStato);
    }

    /**
     * Converte lo stato interno della stazione nel valore atteso dal frontend
     * per il campo "status" degli eventi WebSocket HEARTBEAT.
     */
    private String statoStazionePerFrontend(String stato) {
        return VocabolarioEventi.statoStazionePerFrontend(stato);
    }

// ma che cazz di controllo è ?? è davvero necessario
    /**
     * Prova a interpretare il timestamp ISO-8601 del payload; in caso di errore
     * o assenza ritorna l'istante corrente.
     */
    private Instant parseTimestamp(JsonNode root) {
        try {
            if (root.hasNonNull("timestamp")) {
                return Instant.parse(root.get("timestamp").asText());
            }
        } catch (Exception ignored) {
            // formato non valido: si ripiega sull'orario di ricezione
        }
        return Instant.now();
    }
// prende i dati in arrivo dal topic e nutre la chash si sta trattando dei dati telemetrici del treno,
    // lo fa per ogni treno ?

    @Incoming("telemetry-in")
    @Blocking
    public CompletionStage<Void> onTelemetry(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {

            JsonNode root = mapper.readTree(payload);
            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "N/A";

            String rawStato = root.has("stato") ? root.get("stato").asText() : "UNKNOWN";
            String stato = normalizzaStatoTreno(rawStato);

            double lat = root.has("latitudine") ? root.get("latitudine").asDouble() : 0.0;
            double lng = root.has("longitudine") ? root.get("longitudine").asDouble() : 0.0;
            double velocita = root.has("velocita") ? root.get("velocita").asDouble() : 0.0;
            double progresso = root.has("progressPercent") ? root.get("progressPercent").asDouble() : 0.0;
            int ritardo = root.has("ritardoMinuti") ? root.get("ritardoMinuti").asInt() : 0;
            int passeggeri = root.has("passeggeri") ? root.get("passeggeri").asInt() : 0;
            String stazioneCorrente = root.has("stazioneCorrente") ? root.get("stazioneCorrente").asText("") : "";
            String prossimaStazione = root.has("prossimaStazione") ? root.get("prossimaStazione").asText("") : "";
            String direzione = root.has("direzione") ? root.get("direzione").asText("andata") : "andata";

            // in base a quali dati sono arrivati in input si pesca il treno giusto e gli si aggiornano i valori
            // assoluzione del todo di emacs : cosa succede se inserisco un nuoovo treno nella rete?
            // Risposta: la telemetria NON crea più il treno. Un convoglio che non è in cache
            // (la cache è la fotografia della tabella Treni) semplicemente non esiste, quindi
            // il frame viene scartato. Altrimenti un processo lanciato con un ID inventato si
            // faceva creare la riga dalla telemetria e la verifica dell'ID si auto-validava.
            Treno treno = statoRete.getTreno(trenoId);
            if (treno == null) {
                LOG.warnf("🚫 Telemetria dal treno sconosciuto '%s': frame scartato (i treni si creano dall'amministrazione)", trenoId);
                return message.ack();
            }
            treno.stato = stato;
            treno.latitudine = lat;
            treno.longitudine = lng;
            treno.velocita = velocita;
            treno.progresso = progresso;
            treno.ritardo = ritardo;
            treno.passeggeri = passeggeri;
            treno.stazioneCorrente = stazioneCorrente.isEmpty() ? null : stazioneCorrente;
            treno.prossimaStazione = prossimaStazione.isEmpty() ? null : prossimaStazione;
            treno.direzione = direzione;
            treno.ultimoAggiornamento = Instant.now();

            statoRete.aggiornaTreno(treno);

            // Scrittura su DB in una transazione aperta qui dentro (vedi nota di classe)
            QuarkusTransaction.requiringNew().run(() -> salvaStatoTreno(trenoId, stato));

            // Il convoglio dà di nuovo segni di vita: se il watchdog gli aveva aperto un
            // "treno fermo" va chiuso, esattamente come si fa con le stazioni al ritorno
            // dell'heartbeat (RF02.1.4). Senza questo l'allarme dedotto restava aperto per
            // sempre e la deduplica impediva di aprirne altri per lo stesso convoglio.
            chiudiGuastoTrenoFermoSeRiparte(treno);

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "TELEMETRY");
            // Campi aggiuntivi richiesti dal frontend
            wsEvent.put("trainId", trenoId);
            wsEvent.put("stato", stato); // Trasmette al frontend lo stato normalizzato per il DB
            wsEvent.put("progressPercent", progresso);
            wsEvent.put("delayMinutes", ritardo);
            wsEvent.put("velocita", velocita);
            wsEvent.put("latitudine", lat);
            wsEvent.put("longitudine", lng);
            webSocket.broadcast(wsEvent.toString());

        } catch (Exception e) {
            LOG.error("❌ Errore parsing telemetria: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Allinea sul database lo stato del treno e lo storicizza SOLO quando cambia
     * davvero (altrimenti sarebbe un record ogni 5 secondi per ogni convoglio).
     * Gira dentro la transazione aperta dal chiamante.
     */
    private void salvaStatoTreno(String trenoId, String stato) {
        salvaStatoTreno(trenoId, stato, null);
    }

    /**
     * Come sopra, registrando anche perché lo stato è cambiato.
     *
     * @param trenoId Convoglio da aggiornare.
     * @param stato   Stato nuovo, già normalizzato.
     * @param causa   Chi ha provocato il cambiamento e a quale catena appartiene (può essere null).
     */
    private void salvaStatoTreno(String trenoId, String stato, CausaEvento causa) {
        Treno dbTreno = repository.trovaTreno(trenoId);
        if (dbTreno == null) {
            LOG.warnf("🚫 Il treno '%s' non è nella tabella Treni: nessuna scrittura", trenoId);
            return;
        }
        // Lo stato di partenza va letto PRIMA di sovrascriverlo: è quello che finisce
        // nella colonna stato_precedente e rende la riga di storico un cambiamento
        // ("da attivo a rotto") invece di una fotografia isolata.
        String statoPrecedente = dbTreno.stato;
        boolean statoCambiato = !stato.equals(dbTreno.stato);
        dbTreno.stato = stato;

        if (statoCambiato) {
            repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente, causa);
        }
    }

    /**
     * Chiude il guasto "treno fermo" aperto dal watchdog quando il convoglio torna a
     * muoversi (velocità diversa da zero oppure ingresso in una stazione).
     *
     * <p>Il primo controllo è sulla cache in RAM e costa niente: senza un guasto aperto
     * del tipo giusto non si tocca il database, altrimenti sarebbe una query ogni cinque
     * secondi per ogni convoglio della rete.</p>
     *
     * @param treno Il treno appena aggiornato dalla telemetria.
     */
    private void chiudiGuastoTrenoFermoSeRiparte(Treno treno) {
        boolean inMovimento = treno.velocita > 0
                || (treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty());
        if (!inMovimento) {
            return;
        }
        Guasto inCache = statoRete.getGuastoApertoPerSorgente(treno.id, "treno_fermo");
        if (inCache == null || inCache.messaggio == null
                || !inCache.messaggio.startsWith(MSG_TRENO_FERMO)) {
            return; // nessun guasto dedotto da chiudere (o è un'avaria dichiarata dal treno)
        }

        List<Guasto> chiusi = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> chiusi.addAll(chiudiGuastiTrenoFermo(treno.id)));
        for (Guasto guasto : chiusi) {
            LOG.infof("✅ [WATCHDOG] Treno %s di nuovo in movimento: chiuso il guasto %s", treno.id, guasto.id);
            pubblicaRisoluzioneSuMqtt(guasto);
            broadcastAlert(guasto);
        }
    }

    /**
     * Gemello di {@link #chiudiGuastiHeartbeatPerso(String)} per i convogli: chiude i
     * guasti che la Centrale ha dedotto da sola (riconoscibili dal prefisso del messaggio)
     * lasciando aperti quelli segnalati dal treno, che li chiude un operatore.
     *
     * @return I guasti chiusi, da notificare poi su MQTT e WebSocket.
     */
    private List<Guasto> chiudiGuastiTrenoFermo(String trenoId) {
        List<Guasto> chiusi = new ArrayList<>();
        List<Guasto> aperti = repository.guastiApertiDi(trenoId, "TRENO");
        for (Guasto guasto : aperti) {
            if (guasto.messaggio == null || !guasto.messaggio.startsWith(MSG_TRENO_FERMO)) {
                continue; // avaria dichiarata dal convoglio: la chiude un operatore
            }
            guasto.risolto = true;
            guasto.timestampRisoluzione = Instant.now();

            StoricoGuasto storico = repository.trovaStoricoGuasto(guasto.id);
            if (storico != null) {
                storico.risolto = true;
                storico.tsChiusura = guasto.timestampRisoluzione;
            }
            // Nessun operatore: il guasto si è richiuso da solo. Se però qualcuno lo aveva
            // preso in carico, la sua riga va chiusa lo stesso, altrimenti risulterebbe al
            // lavoro per sempre su un allarme già finito (RF02.7).
            repository.chiudiAssegnazioneGuasto(guasto, null);
            statoRete.risolviGuasto(guasto.id);
            chiusi.add(guasto);
        }
        return chiusi;
    }

    @Incoming("heartbeat-in")
    @Blocking
    public CompletionStage<Void> onHeartbeat(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "N/A";
            String stato = root.has("stato") ? root.get("stato").asText() : "ONLINE";

            // Come per i treni: una stazione che non è in cache non è nella tabella
            // Stazione, quindi non avrebbe nemmeno superato la validazione dell'ID.
            Stazione stazione = statoRete.getStazione(stazioneId);
            if (stazione == null) {
                LOG.warnf("🚫 Heartbeat dalla stazione sconosciuta '%s': scartato", stazioneId);
                return message.ack();
            }

            String statoPrecedente = stazione.stato;
            // La squadra è sul posto: il battito dice che il nodo è vivo, non che l'intervento
            // è finito. Il nodo dichiara di sé solo ONLINE o GUASTA (RF02.3.4) e MANUTENZIONE
            // non è nel suo vocabolario: se lo si lasciasse sovrascrivere, il primo battito
            // utile cancellerebbe uno stato deciso dall'operatore, e MANUTENZIONE tornerebbe a
            // durare pochi secondi come quando invio e rientro stavano nella stessa chiamata.
            // Finisce quando l'operatore dichiara concluso l'intervento, e non prima.
            boolean inManutenzione = "MANUTENZIONE".equalsIgnoreCase(statoPrecedente);
            boolean statoCambiato = !inManutenzione
                    && (statoPrecedente == null || !statoPrecedente.equalsIgnoreCase(stato));
            // Se la Centrale l'aveva marcata OFFLINE (fail-stop) e il battito è tornato,
            // il guasto automatico va chiuso e i treni vanno sbloccati.
            boolean tornataDalFailStop = "OFFLINE".equalsIgnoreCase(statoPrecedente);

            if (!inManutenzione) {
                stazione.stato = stato;
            }
            stazione.ultimoHeartbeat = Instant.now();
            statoRete.aggiornaStazione(stazione);

            // I guasti chiusi qui dentro vengono notificati sul campo dopo il commit
            List<Guasto> guastiChiusi = new ArrayList<>();
            QuarkusTransaction.requiringNew().run(() -> {
                // Storicizza solo al cambio di stato, come già si fa per i treni:
                // a ogni battito sarebbero ~8.600 righe identiche al giorno per stazione.
                if (statoCambiato) {
                    storicizzaStatoStazione(stazioneId, stato, statoPrecedente);
                }
                if (tornataDalFailStop) {
                    guastiChiusi.addAll(chiudiGuastiHeartbeatPerso(stazioneId));
                }
            });
            for (Guasto guasto : guastiChiusi) {
                LOG.infof("✅ [FAIL-STOP] Stazione %s di nuovo raggiungibile: chiuso il guasto %s", stazioneId, guasto.id);
                pubblicaRisoluzioneSuMqtt(guasto);
                broadcastAlert(guasto);
            }

            ObjectNode wsEvent = root.deepCopy();
            wsEvent.put("eventType", "HEARTBEAT");
            // Campi aggiuntivi richiesti dal frontend
            wsEvent.put("stationId", stazioneId);
            // Lo stato che si annuncia è quello che la Centrale tiene, non quello dichiarato nel
            // battito: sono diversi proprio nel caso della manutenzione, dove il nodo continua a
            // dirsi ONLINE perché quello stato non lo conosce. Mandando il suo, la schermata
            // avrebbe rimesso "operativa" una stazione con la squadra ancora sul posto.
            wsEvent.put("status", statoStazionePerFrontend(stazione.stato));
            webSocket.broadcast(wsEvent.toString());

        } catch (Exception e) {
            LOG.error("❌ Errore parsing heartbeat stazione: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Scrive una riga di Storico_Stato_Stazioni per la stazione indicata.
     *
     * @param stato            Stato nuovo della stazione.
     * @param statoPrecedente  Stato da cui proviene, che la riga registra per raccontare
     *                         il cambiamento e non solo il punto di arrivo.
     */
    private void storicizzaStatoStazione(String stazioneId, String stato, String statoPrecedente) {
        Stazione dbStazione = repository.trovaStazione(stazioneId);
        if (dbStazione == null) {
            return;
        }
        repository.salvaStoricoStatoStazione(dbStazione, stato, statoPrecedente);
    }

    /**
     * Chiude i guasti automatici aperti dal FaultMonitor per heartbeat mancante
     * relativi alla stazione indicata (li riconosce dal prefisso del messaggio,
     * così non tocca i guasti veri segnalati dalla stazione stessa).
     *
     * @return I guasti chiusi, da notificare poi su MQTT e WebSocket.
     */
    private List<Guasto> chiudiGuastiHeartbeatPerso(String stazioneId) {
        List<Guasto> chiusi = new ArrayList<>();
        List<Guasto> aperti = repository.guastiApertiDi(stazioneId, "STAZIONE");
        for (Guasto guasto : aperti) {
            if (guasto.messaggio == null || !guasto.messaggio.startsWith(MSG_HEARTBEAT_PERSO)) {
                continue; // guasto dichiarato dalla stazione: lo chiude un operatore
            }
            guasto.risolto = true;
            guasto.timestampRisoluzione = Instant.now();

            StoricoGuasto storico = repository.trovaStoricoGuasto(guasto.id);
            if (storico != null) {
                storico.risolto = true;
                storico.tsChiusura = guasto.timestampRisoluzione;
            }
            // Come sopra: il battito tornato chiude il guasto, e con lui l'eventuale presa
            // in carico rimasta aperta.
            repository.chiudiAssegnazioneGuasto(guasto, null);
            statoRete.risolviGuasto(guasto.id);
            chiusi.add(guasto);
        }
        return chiusi;
    }

    @Incoming("transit-in")
    @Blocking
    public CompletionStage<Void> onTransit(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : "";
            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : "";
            String tipo = root.has("tipo") ? root.get("tipo").asText() : "ENTRATA";
            // L'ora del passaggio è quella scritta dalla stazione quando il sensore ha
            // rilevato il treno, NON quella di arrivo del messaggio: con lo store and
            // forward (SV02) i transiti accodati durante un'interruzione tornano tutti
            // insieme, e datarli all'istante del rientro schiaccerebbe dieci minuti di
            // passaggi sullo stesso minuto.
            Instant quandoEPassato = parseTimestamp(root);
            // Il ritardo va congelato adesso: è quello che il convoglio ha nel momento
            // del passaggio, ed è il dato che lo storico dovrà mostrare (vedi getTransiti).
            Treno cacheTreno = statoRete.getTreno(trenoId);
            int ritardoAlPassaggio = cacheTreno != null ? cacheTreno.ritardo : 0;

            QuarkusTransaction.requiringNew()
                    .run(() -> registraTransito(trenoId, stazioneId, tipo, quandoEPassato, ritardoAlPassaggio));

            // Aggiorna il contatore dei treni presenti nella stazione (cache RAM)
            Stazione cacheStazione = statoRete.getStazione(stazioneId);
            if (cacheStazione != null) {
                if ("ENTRATA".equalsIgnoreCase(tipo)) {
                    cacheStazione.treniInStazione++;
                } else if (cacheStazione.treniInStazione > 0) {
                    cacheStazione.treniInStazione--;
                }
                statoRete.aggiornaStazione(cacheStazione);
            }

            // La stazione è la SOLA sorgente degli eventi TRANSIT per la UI: è il sensore
            // di terra a rilevare il passaggio. onPassaggio() non fa più il broadcast,
            // altrimenti il frontend riceveva due volte lo stesso ingresso/uscita.
            broadcastTransit(root, trenoId, stazioneId, tipo);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing transito: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Apre o chiude il transito sul database, storicizzandolo.
     * Gira dentro la transazione aperta dal chiamante.
     *
     * @param quandoEPassato Istante in cui la stazione ha rilevato il passaggio (dal payload).
     * @param ritardoMinuti  Ritardo del convoglio in quel momento.
     */
    private void registraTransito(String trenoId, String stazioneId, String tipo,
                                  Instant quandoEPassato, int ritardoMinuti) {
        Instant adesso = quandoEPassato != null ? quandoEPassato : Instant.now();
        Treno dbTreno = repository.trovaTreno(trenoId);
        Stazione dbStazione = repository.trovaStazione(stazioneId);
        if (dbTreno == null || dbStazione == null) {
            LOG.warnf("🚫 Transito ignorato: treno '%s' o stazione '%s' non presenti a DB", trenoId, stazioneId);
            return;
        }

        if ("ENTRATA".equalsIgnoreCase(tipo)) {
            // Apre un nuovo transito: il treno è entrato in stazione
            Transito transito = new Transito();
            transito.id = "TR-" + adesso.toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            transito.treno = dbTreno;
            transito.stazione = dbStazione;
            transito.tratta = dbTreno.posizioneAttualeTratta;
            transito.tempoEntrata = adesso;
            transito.ritardoMinuti = ritardoMinuti;
            repository.salvaTransito(transito);

            // Storicizza subito l'apertura (record con tempoUscita null)
            storicizzaTransito(transito);
        } else {
            // USCITA: chiude il transito aperto per stesso treno+stazione
            Transito aperto = repository.trovaTransitoAperto(trenoId, stazioneId);
            if (aperto != null) {
                aperto.tempoUscita = adesso;
                aperto.ritardoMinuti = ritardoMinuti;
                storicizzaTransito(aperto);
            } else {
                // Caso "esce senza entrare": tipico della stazione di partenza,
                // si registra un transito puntuale con entrata = uscita
                Transito transito = new Transito();
                transito.id = "TR-" + adesso.toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                transito.treno = dbTreno;
                transito.stazione = dbStazione;
                transito.tratta = dbTreno.posizioneAttualeTratta;
                transito.tempoEntrata = adesso;
                transito.tempoUscita = adesso;
                transito.ritardoMinuti = ritardoMinuti;
                repository.salvaTransito(transito);
                storicizzaTransito(transito);
            }
        }
    }

    /**
     * Inserisce nello storico dei transiti un record fotografia dell'evento corrente
     * (semplificazione: un record per ogni evento di apertura/chiusura).
     * La riga si porta dietro anche il nome della stazione e la descrizione della tratta:
     * lo storico non ha più chiavi esterne verso l'anagrafica (RF02.7).
     */
    private void storicizzaTransito(Transito transito) {
        repository.salvaStoricoTransito(transito);
    }

    /**
     * Invia sul WebSocket un evento TRANSIT arricchito con i campi che il frontend
     * si aspetta (trainId, stationId, type minuscolo, delayMinutes, tratta percorsa).
     * Il campo "timestamp" arriva dal payload della stazione e viene lasciato intatto:
     * è l'ora vera del passaggio, quella che la pagina Transiti deve mostrare.
     */
    private void broadcastTransit(JsonNode root, String trenoId, String stazioneId, String tipo) {
        Treno cacheTreno = statoRete.getTreno(trenoId);
        ObjectNode wsEvent = root.deepCopy();
        wsEvent.put("eventType", "TRANSIT");
        wsEvent.put("trainId", trenoId);
        wsEvent.put("stationId", stazioneId);
        wsEvent.put("type", "USCITA".equalsIgnoreCase(tipo) ? "uscita" : "ingresso");
        wsEvent.put("delayMinutes", cacheTreno != null ? cacheTreno.ritardo : 0);
        // RF01.5 chiede la tratta fra i dati del transito: la stazione non la conosce,
        // la sa solo la Centrale dalla posizione corrente del convoglio.
        if (cacheTreno != null && cacheTreno.posizioneAttualeTratta != null) {
            wsEvent.put("trattaId", cacheTreno.posizioneAttualeTratta.id);
        }
        webSocket.broadcast(wsEvent.toString());
    }

    @Incoming("alerts-in")
    @Blocking
    public CompletionStage<Void> onAlert(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            // Sul canale condiviso viaggiano anche gli eventi emessi dalla centrale stessa
            // (RESOLVED, STOP, MAINTENANCE_DISPATCHED, ITINERARIO_AGGIORNATO):
            // per questi NON va creato alcun guasto.
            String tipoEvento = root.path("tipoEvento").asText("");
            boolean dallaCentrale = ORIGINE_CENTRALE.equalsIgnoreCase(root.path("origine").asText(""));

            // Eventi derivati: un nodo dichiara di aver cambiato stato per causa altrui. Non
            // sono guasti e non ne aprono (dieci convogli fermi per una sola avaria non devono
            // diventare dieci allarmi da risolvere a mano): li applica il gestore delle reazioni,
            // che aggiorna stato e storico portandosi dietro la causa e la catena.
            if (VocabolarioEventi.TIPO_REAZIONE.equalsIgnoreCase(tipoEvento)) {
                if (!dallaCentrale) {
                    gestoreReazioni.applica(EventoDerivato.daJson(root));
                }
                return message.ack();
            }

            if (!"GUASTO".equalsIgnoreCase(tipoEvento)) {
                return message.ack();
            }
            // Nemmeno per i GUASTO che ha pubblicato la Centrale stessa (fail-stop di una
            // stazione): sono già stati scritti a DB dal FaultMonitor, qui tornano solo
            // perché siamo sottoscritti al nostro stesso topic.
            if (dallaCentrale) {
                return message.ack();
            }

            String sorgenteTipo = root.path("sorgenteTipo").asText("");
            String sorgenteId = root.path("sorgenteId").asText("");
            String severita = root.path("severita").asText("CRITICAL");
            String messaggio = root.hasNonNull("messaggio") ? root.get("messaggio").asText() : payload;
            // Il tipo lo dichiara la sorgente (campo opzionale "tipoGuasto"): non si
            // indovina più leggendo il testo del messaggio.
            String tipo = tipoGuastoPerFrontend(sorgenteTipo, root.path("tipoGuasto").asText(""));
            Instant quando = parseTimestamp(root);

            // Deduplica: la stazione guasta manda un alert per OGNI treno che entra.
            // Se un guasto dello stesso tipo per la stessa sorgente è già aperto si
            // aggiorna il messaggio invece di riempire la tabella di righe identiche
            // (con N righe aperte, "risolvi" ne chiuderebbe una sola).
            // Catena ereditata: un nodo che si dichiara guasto PER COLPA di un altro evento
            // (la stazione resa non percorribile da un convoglio guasto sui suoi binari)
            // ripubblica un GUASTO normale, perché per gli altri nodi di campo quel fatto è un
            // primario come un altro, ma si porta dietro la catena di partenza. Se il campo non
            // c'è il guasto è primario e la catena parte da lui.
            String catenaEreditata = root.path(VocabolarioEventi.CAMPO_CATENA).asText("");

            Guasto giaAperto = statoRete.getGuastoApertoPerSorgente(sorgenteId, tipo);
            if (giaAperto != null) {
                final Guasto daAggiornare = giaAperto;
                QuarkusTransaction.requiringNew().run(() -> aggiornaMessaggioGuasto(daAggiornare.id, messaggio));
                giaAperto.messaggio = messaggio;
                LOG.infof("♻️ Guasto già aperto per %s (%s): aggiornato il messaggio invece di crearne un altro", sorgenteId, tipo);
                // Anche se il guasto non è nuovo la sorgente va rimarcata: nel frattempo
                // la telemetria (o un heartbeat) può aver riscritto lo stato in cache.
                marcaSorgenteGuasta(giaAperto);
                broadcastAlert(giaAperto);
                return message.ack();
            }

            Guasto guasto = new Guasto();
            guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            guasto.tipo = tipo;
            guasto.severita = severita;
            guasto.sorgenteTipo = sorgenteTipo;
            guasto.sorgenteId = sorgenteId;
            guasto.messaggio = messaggio;
            guasto.timestamp = quando;
            guasto.risolto = false;
            guasto.catenaId = catenaEreditata.isBlank() ? guasto.id : catenaEreditata;

            QuarkusTransaction.requiringNew().run(() -> {
                repository.salvaGuasto(guasto);
                repository.salvaStoricoGuasto(guasto);
            });
            statoRete.aggiungiGuasto(guasto);
            // Qui c'era un todo: marcare GUASTA la stazione quando il guasto arriva da un treno
            // fermo sui suoi binari (RF02.1.2.2.1). Non va fatto qui, ed è il motivo per cui il
            // todo è rimasto lì tanto: quella decisione non appartiene alla Centrale. Chi sa
            // quali convogli ha sui binari è la stazione, che è sottoscritta allo stesso canale
            // e reagisce da sé (StationGateway.riceviAlert), così la conseguenza vale anche
            // mentre la rete è giù. La Centrale la registra, non la comanda.
            marcaSorgenteGuasta(guasto);

            //
            broadcastAlert(guasto);
        } catch (Exception e) {
            LOG.error("❌ Errore parsing alert: " + payload, e);
        }
        return message.ack();
    }

    /**
     * Riporta il guasto appena ricevuto sulla cache della sorgente che lo ha segnalato.
     * Senza questo passaggio l'allarme finiva solo nella tabella dei guasti: il nodo
     * continuava a comparire operativo nelle API REST (che leggono la cache) e la mappa
     * lo mostrava normale finché non arrivava un altro frame dal campo.
     * <p>Si marca solo sui CRITICAL: i WARNING restano allarmi informativi e non devono
     * contraddire gli heartbeat ONLINE della stazione né la telemetria del treno.</p>
     *
     * <p>Il cambio di stato passa anche dal database e dallo storico, con dentro la causa
     * (il guasto che l'ha provocato e la sua catena). Prima il ramo della STAZIONE toccava
     * solo la cache: il passaggio ONLINE → GUASTA non finiva in Storico_Stato_Stazioni,
     * e siccome la cache era già GUASTA al battito successivo "lo stato è cambiato"
     * risultava falso, quindi quella riga non la scriveva più nessuno.</p>
     *
     * @param guasto Il guasto appena aperto o già aperto per quella sorgente.
     */
    private void marcaSorgenteGuasta(Guasto guasto) {
        if (guasto == null || !"CRITICAL".equalsIgnoreCase(guasto.severita)) {
            return;
        }
        String sorgenteTipo = guasto.sorgenteTipo;
        String sorgenteId = guasto.sorgenteId;
        String catenaId = guasto.catenaId != null ? guasto.catenaId : guasto.id;
        CausaEvento causa = CausaEvento.propria(sorgenteTipo, sorgenteId, catenaId);

        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            Stazione stazione = statoRete.getStazione(sorgenteId);
            if (stazione == null) {
                LOG.warnf("🚫 Guasto da una stazione sconosciuta '%s': cache non aggiornata", sorgenteId);
                return;
            }
            String statoPrecedente = stazione.stato;
            stazione.stato = "GUASTA";
            statoRete.aggiornaStazione(stazione);
            if (!"GUASTA".equalsIgnoreCase(statoPrecedente)) {
                QuarkusTransaction.requiringNew().run(() -> salvaStatoStazione(sorgenteId, "GUASTA", statoPrecedente, causa));
            }
            broadcastStatoStazione(stazione);
            return;
        }

        if (VocabolarioEventi.NODO_TRATTA.equalsIgnoreCase(sorgenteTipo)) {
            // La tratta resa impercorribile viene ripubblicata come GUASTO dal convoglio che ci
            // è rimasto sopra, perché gli altri convogli reagiscono ai guasti e non alle
            // reazioni altrui. Di norma la reazione è già arrivata (stesso client, stesso topic,
            // quindi nell'ordine) e la cache è già a posto: questo ramo è la rete di sicurezza
            // per il caso in cui si sia persa solo lei.
            Tratta tratta = statoRete.getTratta(sorgenteId);
            if (tratta == null) {
                LOG.warnf("🚫 Guasto su una tratta sconosciuta '%s': cache non aggiornata", sorgenteId);
                return;
            }
            String statoPrecedente = tratta.stato;
            if (VocabolarioEventi.TRATTA_IMPERCORRIBILE.equalsIgnoreCase(statoPrecedente)) {
                return; // già impercorribile: niente da cambiare e niente da storicizzare
            }
            tratta.stato = VocabolarioEventi.TRATTA_IMPERCORRIBILE;
            statoRete.aggiornaTratta(tratta);
            QuarkusTransaction.requiringNew().run(() -> {
                Tratta dbTratta = repository.trovaTratta(sorgenteId);
                if (dbTratta != null) {
                    repository.salvaStoricoStatoTratta(dbTratta,
                            VocabolarioEventi.TRATTA_IMPERCORRIBILE, statoPrecedente, causa);
                }
            });
            return;
        }

        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            Treno treno = statoRete.getTreno(sorgenteId);
            if (treno == null) {
                LOG.warnf("🚫 Guasto da un treno sconosciuto '%s': cache non aggiornata", sorgenteId);
                return;
            }
            // "rotto" è uno degli stati ammessi dal CHECK su Treni.stato; il frontend
            // lo traduce in "guasto" (mapBackendStatus in apiClient.ts).
            treno.stato = "rotto";
            treno.velocita = 0;
            treno.ultimoAggiornamento = Instant.now();
            statoRete.aggiornaTreno(treno);

            // Allinea anche il DB (e lo storico) come fa la telemetria: la cache viene
            // ricostruita da lì al riavvio della Centrale.
            QuarkusTransaction.requiringNew().run(() -> salvaStatoTreno(sorgenteId, "rotto", causa));

            broadcastStatoTreno(treno);
        }
    }

    /**
     * Scrive la riga di storico del cambio di stato di una stazione, con dentro la causa.
     * Gira dentro la transazione aperta dal chiamante.
     *
     * <p>Lo stato corrente della stazione non si scrive: {@code Stazione.stato} è
     * {@code @Transient}, cioè vive solo nella cache e si ricostruisce dagli heartbeat. Quello
     * che deve restare a database è il <b>cambiamento</b>, ed è questa riga.</p>
     *
     * @param stazioneId      Stazione interessata.
     * @param stato           Stato nuovo.
     * @param statoPrecedente Stato da cui proviene.
     * @param causa           Perché è cambiato (può essere null).
     */
    private void salvaStatoStazione(String stazioneId, String stato, String statoPrecedente, CausaEvento causa) {
        Stazione dbStazione = repository.trovaStazione(stazioneId);
        if (dbStazione == null) {
            LOG.warnf("🚫 La stazione '%s' non è nella tabella Stazione: nessuna scrittura", stazioneId);
            return;
        }
        repository.salvaStoricoStatoStazione(dbStazione, stato, statoPrecedente, causa);
    }

    /**
     * Manda al frontend un evento TELEMETRY con il nuovo stato del convoglio.
     * Serve perché un treno che si guasta può smettere di trasmettere: senza questo
     * l'interfaccia continuerebbe a disegnarlo com'era all'ultimo frame ricevuto.
     *
     * @param treno Il treno appena marcato in cache.
     */
    private void broadcastStatoTreno(Treno treno) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "TELEMETRY");
        wsEvent.put("trainId", treno.id);
        wsEvent.put("stato", treno.stato);
        wsEvent.put("velocita", treno.velocita);
        wsEvent.put("progressPercent", treno.progresso);
        wsEvent.put("delayMinutes", treno.ritardo);
        wsEvent.put("latitudine", treno.latitudine);
        wsEvent.put("longitudine", treno.longitudine);
        webSocket.broadcast(wsEvent.toString());
    }

    /** Aggiorna il testo di un guasto già aperto (deduplica degli alert ripetuti). */
    private void aggiornaMessaggioGuasto(String guastoId, String messaggio) {
        Guasto dbGuasto = repository.trovaGuasto(guastoId);
        if (dbGuasto != null) {
            dbGuasto.messaggio = messaggio;
        }
    }

    /**
     * Determina il tipo di guasto nel vocabolario del frontend.
     *
     * <p>Prima la prima riga del metodo classificava come {@code sensore_offline}
     * qualunque allarme che contenesse la parola "sensore" nel messaggio: siccome tutti i
     * guasti di terra parlano di sensori, il tipo {@code stazione_guasta} non veniva quasi
     * mai prodotto, e con lui spariva il pulsante "Invia Operatori" (RF01.4.1). Bastava poi
     * la parola "sensore" nella descrizione di un'avaria di bordo per far passare per
     * guasto di terra il guasto di un treno.</p>
     *
     * <p>Adesso il tipo lo dichiara chi segnala, con il campo {@code tipoGuasto} del
     * payload (lo scrivono le stazioni sui guasti ai sensori di binario); se manca, decide
     * il tipo di sorgente, che è un dato strutturato e non una parola in una frase.</p>
     *
     * @param tipoDichiarato Valore del campo "tipoGuasto" del payload, se presente.
     */
    private String tipoGuastoPerFrontend(String sorgenteTipo, String tipoDichiarato) {
        if (tipoDichiarato != null && !tipoDichiarato.isBlank()) {
            String tipo = tipoDichiarato.trim().toLowerCase();
            if ("sensore_offline".equals(tipo) || "stazione_guasta".equals(tipo)
                    || "treno_fermo".equals(tipo) || TIPO_TRATTA_IMPERCORRIBILE.equals(tipo)) {
                return tipo;
            }
        }
        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            return "treno_fermo";
        }
        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            return "stazione_guasta";
        }
        // Un arco della rete occupato da un'avaria: è un allarme a sé, con la sua sorgente, e
        // l'operatore deve vederlo perché è un pezzo di rete che non si può percorrere.
        if (VocabolarioEventi.NODO_TRATTA.equalsIgnoreCase(sorgenteTipo)) {
            return TIPO_TRATTA_IMPERCORRIBILE;
        }
        return "sensore_offline";
    }


   // compito che avrebbe dovuto fare restAPIGatewy

    /**
     * Invia sul WebSocket un evento ALERT nel formato atteso dal frontend.
     * Metodo pubblico perché riusato anche dal FaultMonitor per i guasti automatici.
     *
     * <p>L'evento porta anche {@code risolto} e {@code timestampRisoluzione}: lo stesso
     * metodo serve infatti sia per annunciare un guasto nuovo sia per dire che uno vecchio
     * è rientrato (chiusura automatica del fail-stop, deduplica di un allarme ripetuto).
     * Senza quel campo il browser non poteva distinguere i due casi e metteva in elenco un
     * secondo allarme rosso proprio quando la condizione era appena rientrata.</p>
     */
    public void broadcastAlert(Guasto guasto) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "ALERT");
        wsEvent.put("id", guasto.id);
        wsEvent.put("type", guasto.tipo);
        wsEvent.put("severity", guasto.severita != null ? guasto.severita.toLowerCase() : "warning");
        wsEvent.put("message", guasto.messaggio);
        wsEvent.put("sorgenteId", guasto.sorgenteId);
        wsEvent.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        wsEvent.put("risolto", guasto.risolto);
        if (guasto.timestampRisoluzione != null) {
            wsEvent.put("timestampRisoluzione", guasto.timestampRisoluzione.toString());
        }
        // Chi se ne sta occupando (RF01.4.2): senza questo campo la presa in carico si
        // vedeva solo nel browser di chi l'aveva premuta, e un secondo operatore poteva
        // mettersi a lavorare sullo stesso allarme senza saperlo.
        if (guasto.operatore != null) {
            wsEvent.put("operatore", (guasto.operatore.nome + " " + guasto.operatore.cognome).trim());
        }
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("trainId", guasto.sorgenteId);
        } else if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            wsEvent.put("stationId", guasto.sorgenteId);
        } else if (VocabolarioEventi.NODO_TRATTA.equalsIgnoreCase(guasto.sorgenteTipo)) {
            // La terza sorgente possibile secondo RF01.2.1: un arco della rete.
            wsEvent.put("trattaId", guasto.sorgenteId);
        }
        webSocket.broadcast(wsEvent.toString());
    }

    /**
     * Annuncia al frontend che una stazione ha cambiato stato operativo.
     *
     * <p>Prima l'unico evento che poteva muovere il pallino di una stazione era
     * l'HEARTBEAT, cioè un messaggio che arriva dalla stazione stessa: per definizione
     * non arriva più proprio nel caso che conta, quando la stazione tace ed è la Centrale
     * a dedurne la caduta. Il risultato era una mappa tutta verde con la stazione OFFLINE
     * a database, che si allineava solo ricaricando la pagina (RF01.1.4 chiede il
     * contrario). Va quindi chiamato ovunque la Centrale scriva {@code stazione.stato}:
     * watchdog, risoluzione di un allarme, invio della squadra, guasto segnalato.</p>
     *
     * <p>Evento separato dall'HEARTBEAT apposta: l'HEARTBEAT porta con sé anche l'ora
     * dell'ultimo battito, e una stazione dichiarata OFFLINE dal watchdog non ha battuto
     * affatto — spacciare l'istante del broadcast per un battito sarebbe una bugia.</p>
     *
     * @param stazione La stazione appena aggiornata in cache.
     */
    public void broadcastStatoStazione(Stazione stazione) {
        if (stazione == null) {
            return;
        }
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", "STATION_STATUS");
        wsEvent.put("stationId", stazione.id);
        wsEvent.put("status", statoStazionePerFrontend(stazione.stato));
        wsEvent.put("timestamp", Instant.now().toString());
        webSocket.broadcast(wsEvent.toString());
    }

    /**
     * Pubblica su railway/alerts un guasto rilevato dalla Centrale, nello stesso formato
     * usato dai nodi edge. È il pezzo che mancava al requisito del fail-stop: prima il
     * FaultMonitor si limitava alla WebSocket, quindi i treni non sapevano nulla della
     * stazione caduta e ci andavano dentro lo stesso. Con severità CRITICAL entra in
     * funzione la logica di blocco già scritta in TrainGateway/TrainJourneyEngine.
     *
     * @param guasto Il guasto appena aperto dalla Centrale.
     */
    //per i guasti dedotti
    public void pubblicaGuastoSuMqtt(Guasto guasto) {
        ObjectNode alert = mapper.createObjectNode();
        alert.put("tipoEvento", "GUASTO");
        alert.put("origine", ORIGINE_CENTRALE); // evita che la Centrale si riascolti da sola
        alert.put("sorgenteTipo", guasto.sorgenteTipo);
        alert.put("sorgenteId", guasto.sorgenteId);
        alert.put("severita", guasto.severita);
        alert.put("messaggio", guasto.messaggio);
        alert.put("guastoId", guasto.id);
        // La catena viaggia con l'alert: è quello che permette a chi reagisce di dichiarare
        // POI di che conseguenza si tratta, e di reagirci una volta sola.
        alert.put(VocabolarioEventi.CAMPO_CATENA, guasto.catenaId != null ? guasto.catenaId : guasto.id);
        alert.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        inviaSuMqtt(alert.toString());
    }

    /**
     * Pubblica su railway/alerts una reazione decisa dalla Centrale, cioè il cambiamento di stato
     * che segue a un comando dell'operatore (la squadra mandata a una stazione, il ritorno in
     * servizio a intervento finito).
     *
     * <p>Va sul canale come le reazioni del campo perché è lo stesso tipo di fatto e chiunque
     * ascolti deve poterlo leggere allo stesso modo. Porta il marcatore {@code origine}: il topic
     * è condiviso e la Centrale è sottoscritta anche a sé stessa, quindi senza quel campo si
     * riascolterebbe e riapplicherebbe la propria reazione — che è poi la regola anti-eco che
     * vale già per i guasti.</p>
     *
     * @param sorgenteTipo    Tipo del nodo che cambia stato.
     * @param sorgenteId      Identificativo di quel nodo.
     * @param nuovoStato      Stato nuovo.
     * @param statoPrecedente Stato da cui proviene.
     * @param causa           Chi lo ha deciso e a quale catena appartiene.
     * @param attiva          true se il nodo entra nella catena, false se ne esce.
     * @param motivo          Descrizione leggibile.
     */
    public void pubblicaReazioneSuMqtt(String sorgenteTipo, String sorgenteId, String nuovoStato,
                                       String statoPrecedente, CausaEvento causa, boolean attiva,
                                       String motivo) {
        ObjectNode reazione = mapper.createObjectNode();
        reazione.put(VocabolarioEventi.CAMPO_TIPO_EVENTO, VocabolarioEventi.TIPO_REAZIONE);
        reazione.put("origine", ORIGINE_CENTRALE);
        reazione.put(VocabolarioEventi.CAMPO_SORGENTE_TIPO, sorgenteTipo);
        reazione.put(VocabolarioEventi.CAMPO_SORGENTE_ID, sorgenteId);
        reazione.put(VocabolarioEventi.CAMPO_NUOVO_STATO, nuovoStato);
        if (statoPrecedente != null) {
            reazione.put(VocabolarioEventi.CAMPO_STATO_PRECEDENTE, statoPrecedente);
        }
        if (causa != null) {
            reazione.put(VocabolarioEventi.CAMPO_CAUSA_TIPO, causa.tipo());
            reazione.put(VocabolarioEventi.CAMPO_CAUSA_ID, causa.id());
            reazione.put(VocabolarioEventi.CAMPO_CATENA, causa.catenaId());
        }
        reazione.put(VocabolarioEventi.CAMPO_ATTIVA, attiva);
        reazione.put(VocabolarioEventi.CAMPO_MOTIVO, motivo);
        reazione.put(VocabolarioEventi.CAMPO_TIMESTAMP, Instant.now().toString());
        inviaSuMqtt(reazione.toString());
    }

    /** Pubblica su railway/alerts la chiusura di un guasto aperto dalla Centrale. */
    public void pubblicaRisoluzioneSuMqtt(Guasto guasto) {
        String catenaId = guasto.catenaId != null ? guasto.catenaId : guasto.id;
        ObjectNode alert = mapper.createObjectNode();
        alert.put("tipoEvento", "RESOLVED");
        alert.put("origine", ORIGINE_CENTRALE);
        alert.put("sorgenteTipo", guasto.sorgenteTipo != null ? guasto.sorgenteTipo : "STAZIONE");
        alert.put("sorgenteId", guasto.sorgenteId != null ? guasto.sorgenteId : "");
        alert.put("guastoId", guasto.id);
        alert.put(VocabolarioEventi.CAMPO_CATENA, catenaId);
        alert.put("timestamp", Instant.now().toString());
        inviaSuMqtt(alert.toString());

        // La catena si srotola: chi si era fermato per questo guasto torna a muoversi e lo
        // dichiara. Qui la si chiude anche nel registro, altrimenti resterebbero registrati
        // per sempre i nodi che nel frattempo si sono spenti senza dichiarare l'uscita.
        java.util.Set<String> nodi = registroCatene.chiudi(catenaId);
        if (!nodi.isEmpty()) {
            LOG.infof("🔗 Catena %s chiusa: %d nodi tornano liberi %s", catenaId, nodi.size(), nodi);
        }
    }

    /** Invio difensivo sul canale MQTT: un broker giù non deve far cadere il chiamante. */
    private void inviaSuMqtt(String payload) {
        try {
            alertsEmitter.send(payload);
        } catch (Exception e) {
            LOG.errorf("Impossibile pubblicare l'alert su railway/alerts: %s", e.getMessage());
        }
    }

    @Incoming("passaggio-in")
    @Blocking
    public CompletionStage<Void> onPassaggio(Message<byte[]> message) {
        String payload = new String(message.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);
            String trenoId = root.path("trenoId").asText("");
            String stazioneId = root.path("stazioneId").asText("");
            String tipo = root.path("tipo").asText("ENTRATA");
            int ritardo = root.path("ritardoMinuti").asInt(0);

            Treno cacheTreno = statoRete.getTreno(trenoId);
            String direzione = cacheTreno != null && cacheTreno.direzione != null
                    ? cacheTreno.direzione : "andata";

            // Letture sull'itinerario e aggiornamento della posizione: tutto in una
            // transazione, perché senza non ci sarebbe nessuna sessione JPA attiva.
            EsitoPassaggio esito = QuarkusTransaction.requiringNew()
                    .call(() -> aggiornaPosizioneSuTratta(trenoId, stazioneId, tipo, direzione));

            if (cacheTreno != null) {
                boolean entrata = "ENTRATA".equalsIgnoreCase(tipo);
                cacheTreno.ritardo = ritardo;
                if (entrata) {
                    cacheTreno.stazioneCorrente = stazioneId;
                    cacheTreno.prossimaStazione = esito.prossimaStazione();
                } else {
                    cacheTreno.stazioneCorrente = null;
                }
                if (esito.posizione() != null) {
                    cacheTreno.posizioneAttualeTratta = esito.posizione();
                }
                // Stato e progresso vanno allineati QUI, non alla telemetria successiva.
                // Il passaggio arriva subito, la telemetria fino a cinque secondi dopo:
                // nel mezzo lo SNAPSHOT metteva insieme campi di due età diverse e la
                // fotografia non stava in piedi. Un treno appena entrato risultava ancora
                // "attivo" al 95% ma con la prossima stazione già avanzata, e la mappa lo
                // scagliava sulla stazione successiva; uno appena uscito risultava "fermo"
                // senza più una stazione corrente, e la mappa non sapeva più dove disegnarlo
                // (spariva, finendo nel conteggio dei "treni senza posizione nota").
                // Il convoglio rotto o soppresso non si tocca: quello stato non lo decide
                // un passaggio in stazione.
                if ("attivo".equals(cacheTreno.stato) || "fermo".equals(cacheTreno.stato)) {
                    cacheTreno.stato = entrata ? "fermo" : "attivo";
                    cacheTreno.progresso = entrata ? 100.0 : 0.0;
                    if (entrata) {
                        cacheTreno.velocita = 0.0;
                    }
                }
                cacheTreno.ultimoAggiornamento = Instant.now();
                statoRete.aggiornaTreno(cacheTreno);
            }

            // NIENTE broadcastTransit qui: l'evento TRANSIT per la UI lo genera la
            // stazione (onTransit). Lo stesso passaggio fisico arriva alla Centrale
            // per due strade (dal treno e dalla stazione) e la sorgente ufficiale
            // per il frontend è il sensore di terra, altrimenti ogni ingresso/uscita
            // compariva due volte nella pagina transiti.
        } catch (Exception e) {
            LOG.error("❌ Errore parsing passaggio: " + payload, e);
        }
        return message.ack();
    }

    /** Dati letti dal DB durante un passaggio, che poi servono alla cache in RAM. */
    private record EsitoPassaggio(String prossimaStazione, Tratta posizione) {}

    /**
     * Aggiorna sul DB la posizione del treno rispetto alla tratta percorsa,
     * rispettando la direzione di marcia (andata = tratte nell'ordine originario),
     * e calcola quale sarà la prossima stazione.
     */
    private EsitoPassaggio aggiornaPosizioneSuTratta(String trenoId, String stazioneId, String tipo, String direzione) {
        Treno dbTreno = repository.trovaTreno(trenoId);
        if (dbTreno == null || dbTreno.itinerario == null) {
            return new EsitoPassaggio(null, null);
        }
        Tratta trattaPosizione = trovaTratta(dbTreno.itinerario.id, stazioneId, tipo, direzione);
        if (trattaPosizione != null) {
            dbTreno.posizioneAttualeTratta = trattaPosizione;
        }
        String prossima = "ENTRATA".equalsIgnoreCase(tipo)
                ? calcolaProssimaStazione(dbTreno.itinerario.id, stazioneId, direzione)
                : null;
        return new EsitoPassaggio(prossima, trattaPosizione);
    }

    /**
     * Calcola la prossima stazione dell'itinerario del treno rispetto alla
     * stazione appena raggiunta e alla direzione di marcia.
     *
     * @return ID della prossima stazione, o null se capolinea/itinerario non noto.
     */
    private String calcolaProssimaStazione(String itinerarioId, String stazioneId, String direzione) {
        List<String> stazioni = repository.stazioniOrdinateDi(itinerarioId);
        int idx = stazioni.indexOf(stazioneId);
        if (idx < 0) return null;

        boolean andata = !"ritorno".equalsIgnoreCase(direzione);
        int next = andata ? idx + 1 : idx - 1;
        if (next < 0 || next >= stazioni.size()) return null; // capolinea
        return stazioni.get(next);
    }

    /**
     * Trova la tratta dell'itinerario coerente con l'evento di passaggio:
     * per una ENTRATA la tratta appena percorsa (arrivo = stazione), per una
     * USCITA la tratta che il treno sta imboccando (partenza = stazione).
     * In direzione "ritorno" i ruoli di partenza/arrivo si invertono.
     */
    private Tratta trovaTratta(String itinerarioId, String stazioneId, String tipo, String direzione) {
        List<ItinerarioTratta> tratte = repository.tratteOrdinateDi(itinerarioId);
        boolean andata = !"ritorno".equalsIgnoreCase(direzione);
        boolean entrata = "ENTRATA".equalsIgnoreCase(tipo);
        for (ItinerarioTratta it : tratte) {
            Tratta t = it.tratta;
            String riferimento;
            if (entrata) {
                riferimento = andata ? t.stazioneArrivo.id : t.stazionePartenza.id;
            } else {
                riferimento = andata ? t.stazionePartenza.id : t.stazioneArrivo.id;
            }
            if (riferimento.equals(stazioneId)) {
                return t;
            }
        }
        return null;
    }
}
