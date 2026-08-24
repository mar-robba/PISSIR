package it.uni.reti2.ingestion.payload;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Un messaggio del topic condiviso railway/alerts.
 *
 * <p>Su quel topic viaggia di tutto e in tutte e due le direzioni: i guasti segnalati
 * dal campo, i guasti che la Centrale deduce da sola, le risoluzioni, e i comandi
 * (STOP, MAINTENANCE_DISPATCHED, ITINERARIO_AGGIORNATO, RITARDO). La Centrale e'
 * sottoscritta anche in ingresso, quindi <b>si riascolta da sola</b>: e' per questo che
 * esiste {@link #dallaCentrale()}, che riconosce l'eco dei propri messaggi dal campo
 * {@code origine}. Senza quel controllo la Centrale aprirebbe un secondo guasto per un
 * allarme che ha appena scritto lei.</p>
 */
public record AlertMsg(
        String tipoEvento,
        String origine,
        String sorgenteTipo,
        String sorgenteId,
        String severita,
        String messaggio,
        String tipoGuastoDichiarato,
        Instant quando) {

    /** Marcatore con cui la Centrale firma i messaggi che pubblica lei. */
    public static final String ORIGINE_CENTRALE = "CENTRALE";

    /**
     * @param payloadGrezzo Testo originale del messaggio, usato come descrizione di
     *                      ripiego quando l'alert non porta un campo "messaggio".
     */
    public static AlertMsg da(JsonNode root, String payloadGrezzo) {
        return new AlertMsg(
                Payload.testo(root, "tipoEvento", ""),
                Payload.testo(root, "origine", ""),
                Payload.testo(root, "sorgenteTipo", ""),
                Payload.testo(root, "sorgenteId", ""),
                Payload.testo(root, "severita", "CRITICAL"),
                Payload.testo(root, "messaggio", payloadGrezzo),
                Payload.testo(root, "tipoGuasto", ""),
                Payload.istante(root));
    }

    /** Segnalazione di un guasto nuovo. */
    public boolean segnalaGuasto() {
        return "GUASTO".equalsIgnoreCase(tipoEvento);
    }

    /** Dichiarazione che una condizione di guasto e' rientrata. */
    public boolean segnalaRisoluzione() {
        return "RESOLVED".equalsIgnoreCase(tipoEvento);
    }

    /** true se e' l'eco di un messaggio pubblicato dalla Centrale stessa. */
    public boolean dallaCentrale() {
        return ORIGINE_CENTRALE.equalsIgnoreCase(origine);
    }

    /** true se la sorgente e' un convoglio (e non una stazione). */
    public boolean sorgenteTreno() {
        return "TRENO".equalsIgnoreCase(sorgenteTipo);
    }

    /** true se la sorgente e' una stazione. */
    public boolean sorgenteStazione() {
        return "STAZIONE".equalsIgnoreCase(sorgenteTipo);
    }

    /** Solo i guasti critici cambiano lo stato operativo del nodo che li segnala. */
    public boolean critico() {
        return "CRITICAL".equalsIgnoreCase(severita);
    }
}
