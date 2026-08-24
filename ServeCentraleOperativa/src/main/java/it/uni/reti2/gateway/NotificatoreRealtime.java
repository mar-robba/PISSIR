package it.uni.reti2.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.TransitoMsg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * L'unico posto della Centrale che conosce il vocabolario del frontend.
 *
 * <p>Gli eventi che viaggiano sulla WebSocket hanno nomi di campo loro
 * ({@code trainId}, {@code stationId}, {@code delayMinutes}, {@code status}) che non
 * sono quelli del database ne' quelli dei topic MQTT. Prima quelle traduzioni erano
 * sparse fra l'ingestione, il watchdog e le API REST, e ogni pezzo di Centrale che
 * voleva far sapere qualcosa al browser doveva rifarsi da capo il proprio ObjectNode.
 * Adesso chi ha una notizia da dare chiama un metodo con nome parlante e non sa
 * nemmeno come e' fatto il JSON che parte.</p>
 *
 * <p><b>Perche' certi metodi ricevono ancora il JsonNode grezzo.</b> Per telemetria,
 * heartbeat e transiti l'evento si costruisce copiando il payload arrivato dal campo e
 * aggiungendoci i campi canonici. Non e' pigrizia: quel payload porta anche campi che
 * la Centrale non usa ma il browser si', e ricostruirlo da un record vorrebbe dire
 * decidere qui, una volta per tutte, che cosa il frontend ha il diritto di vedere.</p>
 */
@ApplicationScoped
public class NotificatoreRealtime {

    @Inject
    ObjectMapper mapper;

    @Inject
    RealtimeWebSocket webSocket;

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
    public void telemetria(JsonNode payloadDalCampo, Treno treno) {
        ObjectNode evento = payloadDalCampo.deepCopy();
        evento.put("eventType", "TELEMETRY");
        evento.put("trainId", treno.id);
        evento.put("stato", treno.stato);
        evento.put("progressPercent", treno.progresso);
        evento.put("delayMinutes", treno.ritardo);
        evento.put("velocita", treno.velocita);
        evento.put("latitudine", treno.latitudine);
        evento.put("longitudine", treno.longitudine);
        scriviPrevisione(evento, treno);
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
        ObjectNode evento = mapper.createObjectNode();
        evento.put("eventType", "TELEMETRY");
        evento.put("trainId", treno.id);
        evento.put("stato", treno.stato);
        evento.put("velocita", treno.velocita);
        evento.put("progressPercent", treno.progresso);
        evento.put("delayMinutes", treno.ritardo);
        evento.put("latitudine", treno.latitudine);
        evento.put("longitudine", treno.longitudine);
        scriviPrevisione(evento, treno);
        webSocket.broadcast(evento.toString());
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
    private void scriviPrevisione(ObjectNode evento, Treno treno) {
        if (treno.arrivoPrevisto != null) {
            evento.put("arrivoPrevisto", treno.arrivoPrevisto.toString());
        } else {
            evento.putNull("arrivoPrevisto");
        }
        if (treno.minutiAllArrivo != null) {
            evento.put("minutiAllArrivo", treno.minutiAllArrivo);
        } else {
            evento.putNull("minutiAllArrivo");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    /**
     * Rilancia il battito di una stazione con lo stato che la Centrale ha deciso di
     * darle, che non sempre e' quello dichiarato dal nodo (RF01.4.1).
     */
    public void heartbeat(JsonNode payloadDalCampo, Stazione stazione) {
        ObjectNode evento = payloadDalCampo.deepCopy();
        evento.put("eventType", "HEARTBEAT");
        evento.put("stationId", stazione.id);
        evento.put("status", statoPerFrontend(stazione.stato));
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
        ObjectNode evento = mapper.createObjectNode();
        evento.put("eventType", "STATION_STATUS");
        evento.put("stationId", stazione.id);
        evento.put("status", statoPerFrontend(stazione.stato));
        evento.put("timestamp", Instant.now().toString());
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
    public void transito(JsonNode payloadDalCampo, TransitoMsg msg, Treno treno) {
        ObjectNode evento = payloadDalCampo.deepCopy();
        evento.put("eventType", "TRANSIT");
        evento.put("trainId", msg.trenoId());
        evento.put("stationId", msg.stazioneId());
        evento.put("type", msg.entrata() ? "ingresso" : "uscita");
        evento.put("delayMinutes", treno != null ? treno.ritardo : 0);
        if (treno != null && treno.posizioneAttualeTratta != null) {
            evento.put("trattaId", treno.posizioneAttualeTratta.id);
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
        ObjectNode evento = mapper.createObjectNode();
        evento.put("eventType", "ALERT");
        evento.put("id", guasto.id);
        evento.put("type", guasto.tipo);
        evento.put("severity", guasto.severita != null ? guasto.severita.toLowerCase() : "warning");
        evento.put("message", guasto.messaggio);
        evento.put("sorgenteId", guasto.sorgenteId);
        evento.put("origine", Guasto.originePerOperatore(guasto));
        evento.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        evento.put("risolto", guasto.risolto);
        if (guasto.timestampRisoluzione != null) {
            evento.put("timestampRisoluzione", guasto.timestampRisoluzione.toString());
        }
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            evento.put("trainId", guasto.sorgenteId);
        } else if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            evento.put("stationId", guasto.sorgenteId);
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
        ObjectNode evento = mapper.createObjectNode();
        evento.put("eventType", "SNAPSHOT");
        evento.set("dashboard", mapper.valueToTree(kpi));
        evento.set("treni", mapper.valueToTree(treni));
        evento.set("stazioni", mapper.valueToTree(stazioni));
        webSocket.broadcast(evento.toString());
    }
}
