package it.uni.reti2.gateway;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.TransitoMsg;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * L'unico posto della Centrale che conosce il vocabolario del frontend.
 *
 * <p>Gli eventi che viaggiano sulla WebSocket hanno nomi di campo loro
 * ({@code trainId}, {@code stationId}, {@code delayMinutes}, {@code status}) che non
 * sono quelli del database ne' quelli dei topic MQTT. Le traduzioni stanno tutte qui:
 * chi ha una notizia da dare chiama un metodo con nome parlante e non sa nemmeno come
 * e' fatto il JSON che parte.</p>
 *
 * <p><b>Perche' certi metodi ricevono il JsonObject grezzo.</b> Per telemetria,
 * heartbeat e transiti l'evento si costruisce copiando il payload arrivato dal campo e
 * aggiungendoci i campi canonici. Non e' pigrizia: quel payload porta anche campi che
 * la Centrale non usa ma il browser si', e ricostruirlo da un record vorrebbe dire
 * decidere qui, una volta per tutte, che cosa il frontend ha il diritto di vedere.</p>
 */
public class NotificatoreRealtime {

    /** Le connessioni aperte dalla web app. */
    public RealtimeWebSocket webSocket;

    // ──────────────────────────────────────────────────────────────
    // TRENI
    // ──────────────────────────────────────────────────────────────

    /**
     * Rilancia al frontend il frame di telemetria appena arrivato, arricchito con i
     * dati che il convoglio non conosce: lo stato tradotto per il database, il ritardo
     * e la previsione di arrivo, che li calcola la Centrale.
     *
     * @param payloadDalCampo Il messaggio originale del convoglio.
     * @param treno           Il treno gia' aggiornato in cache.
     */
    public void telemetria(JsonObject payloadDalCampo, Treno treno) {
        JsonObject evento = payloadDalCampo.deepCopy();
        evento.addProperty("eventType", "TELEMETRY");
        evento.addProperty("trainId", treno.id);
        evento.addProperty("stato", treno.stato);
        evento.addProperty("progressPercent", treno.progresso);
        evento.addProperty("delayMinutes", treno.ritardo);
        evento.addProperty("velocita", treno.velocita);
        evento.addProperty("latitudine", treno.latitudine);
        evento.addProperty("longitudine", treno.longitudine);
        scriviPrevisione(evento, treno);
        scriviPosizione(evento, treno);
        scriviStatoComposto(evento, treno);
        webSocket.broadcast(evento.toString());
    }

    /**
     * Annuncia il nuovo stato di un convoglio senza aspettare la sua telemetria.
     *
     * <p>Serve perche' un treno che si guasta puo' smettere di trasmettere: senza
     * questo l'interfaccia continuerebbe a disegnarlo com'era all'ultimo frame
     * ricevuto, cioe' sano e in corsa.</p>
     */
    public void statoTreno(Treno treno) {
        JsonObject evento = new JsonObject();
        evento.addProperty("eventType", "TELEMETRY");
        evento.addProperty("trainId", treno.id);
        evento.addProperty("stato", treno.stato);
        evento.addProperty("velocita", treno.velocita);
        evento.addProperty("progressPercent", treno.progresso);
        evento.addProperty("delayMinutes", treno.ritardo);
        evento.addProperty("latitudine", treno.latitudine);
        evento.addProperty("longitudine", treno.longitudine);
        scriviPrevisione(evento, treno);
        scriviPosizione(evento, treno);
        scriviStatoComposto(evento, treno);
        webSocket.broadcast(evento.toString());
    }

    /**
     * Attacca a un evento TELEMETRY la posizione che la Centrale è disposta a garantire.
     *
     * <p>Nel frame grezzo la stazione corrente e la prossima ci sono già, ma sono ciò che
     * il convoglio <em>dichiara</em>; questi campi sono invece il giudizio della Centrale,
     * che incrocia quella dichiarazione con l'ultimo passaggio rilevato dai sensori di
     * terra (vedi {@code PosizioneConvoglio}).</p>
     *
     * <p>Vanno scritti <b>sempre</b>, anche a null, per lo stesso motivo della previsione
     * di arrivo: sono il modo in cui il browser cancella quello che sapeva prima. Se
     * l'incertezza arrivasse come "campo assente" l'interfaccia terrebbe l'ultima
     * posizione buona e continuerebbe a disegnare il convoglio in viaggio verso una
     * fermata che nessuno gli ha più confermato — che è esattamente il difetto per cui
     * questi campi esistono.</p>
     */
    private void scriviPosizione(JsonObject evento, Treno treno) {
        scriviTestoONull(evento, "partenzaCorrente", treno.partenzaCorrente);
        scriviTestoONull(evento, "arrivoCorrente", treno.arrivoCorrente);
        scriviTestoONull(evento, "direzioneCorrente", treno.direzioneCorrente);
        scriviTestoONull(evento, "incertezzaPosizione", treno.incertezzaPosizione);
        evento.addProperty("posizioneNota", treno.posizioneNota);
    }

    /**
     * Attacca a un evento TELEMETRY lo stato degli automi del convoglio, condizione (T6),
     * fase del viaggio (T5) e verso di marcia (T7), come li ha dichiarati nell'ultimo frame.
     *
     * <p>Il campo "stato" da solo non basta: e' un riassunto, e il riassunto di un treno
     * trattenuto a meta' tratta e di uno in sosta al binario e' lo stesso "fermo". Si
     * prendono dalla cache e non dal frame grezzo perche' anche l'evento che parte senza
     * telemetria ({@link #statoTreno}) deve portarli, e sempre, anche a null: un evento senza
     * questi campi lascerebbe sullo schermo quelli di prima.</p>
     */
    private void scriviStatoComposto(JsonObject evento, Treno treno) {
        scriviTestoONull(evento, "condizione", treno.condizione);
        scriviTestoONull(evento, "faseViaggio", treno.faseViaggio);
        scriviTestoONull(evento, "direzione", treno.direzione);
    }

    /** Scrive il campo, oppure un null esplicito che vale "non lo so". */
    private void scriviTestoONull(JsonObject evento, String campo, String valore) {
        if (valore != null) {
            evento.addProperty(campo, valore);
        } else {
            evento.add(campo, JsonNull.INSTANCE);
        }
    }

    /**
     * Attacca a un evento TELEMETRY la previsione di arrivo del convoglio.
     *
     * <p>I due campi viaggiano insieme perche' dicono la stessa cosa in due unita'
     * diverse: {@code arrivoPrevisto} e' l'istante da stampare come ora del giorno,
     * {@code minutiAllArrivo} e' il "fra quanto" negli stessi minuti in cui e' espresso
     * il ritardo. Se la previsione non c'e' i campi si scrivono lo stesso, a null: e'
     * cosi' che il frontend cancella la stima precedente, e senza di loro la schermata
     * continuerebbe a mostrare l'orario di un convoglio che una stima non ce l'ha piu'
     * (corsa soppressa).</p>
     */
    private void scriviPrevisione(JsonObject evento, Treno treno) {
        if (treno.arrivoPrevisto != null) {
            evento.addProperty("arrivoPrevisto", treno.arrivoPrevisto.toString());
        } else {
            evento.add("arrivoPrevisto", JsonNull.INSTANCE);
        }
        if (treno.minutiAllArrivo != null) {
            evento.addProperty("minutiAllArrivo", treno.minutiAllArrivo);
        } else {
            evento.add("minutiAllArrivo", JsonNull.INSTANCE);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    /**
     * Rilancia il battito di una stazione con lo stato che la Centrale ha deciso di
     * darle, che non sempre e' quello dichiarato dal nodo (RF01.4.1).
     */
    public void heartbeat(JsonObject payloadDalCampo, Stazione stazione) {
        JsonObject evento = payloadDalCampo.deepCopy();
        evento.addProperty("eventType", "HEARTBEAT");
        evento.addProperty("stationId", stazione.id);
        evento.addProperty("status", statoPerFrontend(stazione.stato));
        // Lo stato che il nodo dichiara di se', che in MANUTENZIONE non e' quello che la
        // Centrale gli da' e la web app lo mostra accanto.
        scriviTestoONull(evento, "statoDichiarato", stazione.statoDichiarato);
        webSocket.broadcast(evento.toString());
    }

    /**
     * Annuncia che una stazione ha cambiato stato operativo.
     *
     * <p>Prima l'unico evento che poteva muovere il pallino di una stazione era
     * l'HEARTBEAT, cioe' un messaggio che arriva dalla stazione stessa: per definizione
     * non arriva piu' proprio nel caso che conta, quando la stazione tace ed e' la
     * Centrale a dedurne la caduta. Il risultato era una mappa tutta verde con la
     * stazione OFFLINE a database, che si allineava solo ricaricando la pagina
     * (RF01.1.4 chiede il contrario). Va quindi chiamato ovunque la Centrale scriva
     * {@code stazione.stato}: watchdog, risoluzione di un allarme, invio della squadra,
     * guasto segnalato.</p>
     *
     * <p>Evento separato dall'HEARTBEAT apposta: l'HEARTBEAT porta con se' anche l'ora
     * dell'ultimo battito, e una stazione dichiarata OFFLINE dal watchdog non ha
     * battuto affatto — spacciare l'istante del broadcast per un battito sarebbe una
     * bugia.</p>
     */
    public void statoStazione(Stazione stazione) {
        if (stazione == null) {
            return;
        }
        JsonObject evento = new JsonObject();
        evento.addProperty("eventType", "STATION_STATUS");
        evento.addProperty("stationId", stazione.id);
        evento.addProperty("status", statoPerFrontend(stazione.stato));
        evento.addProperty("timestamp", Instant.now().toString());
        webSocket.broadcast(evento.toString());
    }

    /** Traduce lo stato interno della stazione nel campo "status" atteso dal frontend. */
    private String statoPerFrontend(String stato) {
        if (StatiCanonici.STAZIONE_ONLINE.equalsIgnoreCase(stato)) return "operativa";
        if (StatiCanonici.STAZIONE_GUASTA.equalsIgnoreCase(stato)) return "guasta";
        if (StatiCanonici.STAZIONE_MANUTENZIONE.equalsIgnoreCase(stato)) return "manutenzione";
        return "offline";
    }

    // ──────────────────────────────────────────────────────────────
    // TRANSITI
    // ──────────────────────────────────────────────────────────────

    /**
     * Invia l'evento TRANSIT del passaggio rilevato dal sensore di terra.
     *
     * <p>Il campo {@code timestamp} arriva dal payload della stazione e viene lasciato
     * intatto: e' l'ora vera del passaggio, quella che la pagina Transiti deve mostrare.
     * La tratta invece la sa solo la Centrale (RF01.5), dalla posizione corrente del
     * convoglio.</p>
     *
     * @param treno Il convoglio in cache, o null se non e' noto.
     */
    public void transito(JsonObject payloadDalCampo, TransitoMsg msg, Treno treno) {
        JsonObject evento = payloadDalCampo.deepCopy();
        evento.addProperty("eventType", "TRANSIT");
        evento.addProperty("trainId", msg.trenoId());
        evento.addProperty("stationId", msg.stazioneId());
        evento.addProperty("type", msg.entrata() ? "ingresso" : "uscita");
        evento.addProperty("delayMinutes", treno != null ? treno.ritardo : 0);
        if (treno != null && treno.posizioneAttualeTratta != null) {
            evento.addProperty("trattaId", treno.posizioneAttualeTratta.id);
        }
        webSocket.broadcast(evento.toString());
    }

    // ──────────────────────────────────────────────────────────────
    // ALLARMI E FOTOGRAFIA PERIODICA
    // ──────────────────────────────────────────────────────────────

    /**
     * Invia un evento ALERT nel formato atteso dal frontend.
     *
     * <p>L'evento porta anche {@code risolto} e {@code timestampRisoluzione}: lo stesso
     * metodo serve infatti sia per annunciare un guasto nuovo sia per dire che uno
     * vecchio e' rientrato (chiusura automatica del fail-stop, deduplica di un allarme
     * ripetuto). Senza quel campo il browser non poteva distinguere i due casi e metteva
     * in elenco un secondo allarme rosso proprio quando la condizione era appena
     * rientrata.</p>
     */
    public void allarme(Guasto guasto) {
        JsonObject evento = new JsonObject();
        evento.addProperty("eventType", "ALERT");
        evento.addProperty("id", guasto.id);
        evento.addProperty("type", guasto.tipo);
        evento.addProperty("severity", guasto.severita != null ? guasto.severita.toLowerCase() : "warning");
        evento.addProperty("message", guasto.messaggio);
        evento.addProperty("sorgenteId", guasto.sorgenteId);
        evento.addProperty("origine", Guasto.originePerOperatore(guasto));
        evento.addProperty("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        evento.addProperty("risolto", guasto.risolto);
        if (guasto.timestampRisoluzione != null) {
            evento.addProperty("timestampRisoluzione", guasto.timestampRisoluzione.toString());
        }
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            evento.addProperty("trainId", guasto.sorgenteId);
        } else if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            evento.addProperty("stationId", guasto.sorgenteId);
        }
        webSocket.broadcast(evento.toString());
    }

    /**
     * Manda la fotografia completa dello stato corrente della rete (RF02.6.3): i KPI
     * della dashboard piu' l'elenco di treni e stazioni.
     *
     * <p>E' la rete di sicurezza che rimette a posto il browser quando un evento in
     * tempo reale si perde. I numeri sono quelli calcolati dalla Centrale, gli stessi
     * di GET /api/dashboard: la dashboard non deve rifarsi i conti per suo conto.</p>
     */
    public void snapshot(Map<String, Object> kpi, List<Treno> treni, List<Stazione> stazioni) {
        JsonObject evento = new JsonObject();
        evento.addProperty("eventType", "SNAPSHOT");
        // Json.GSON e' lo stesso convertitore delle API REST: gli oggetti escono identici a
        // quelli di GET /api/treni e GET /api/stazioni
        evento.add("dashboard", Json.GSON.toJsonTree(kpi));
        evento.add("treni", Json.GSON.toJsonTree(treni));
        evento.add("stazioni", Json.GSON.toJsonTree(stazioni));
        webSocket.broadcast(evento.toString());
    }
}
