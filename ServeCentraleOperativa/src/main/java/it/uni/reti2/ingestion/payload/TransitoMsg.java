package it.uni.reti2.ingestion.payload;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Il passaggio rilevato dal sensore di terra di una stazione.
 *
 * <p>{@code quando} e' l'ora scritta dalla stazione al momento del rilevamento, non
 * quella di arrivo del messaggio: con lo store and forward (SV02) i transiti accodati
 * durante un'interruzione tornano tutti insieme, e datarli all'istante del rientro
 * schiaccerebbe dieci minuti di passaggi sullo stesso minuto.</p>
 */
public record TransitoMsg(String trenoId, String stazioneId, String tipo, Instant quando) {

    public static TransitoMsg da(JsonNode root) {
        return new TransitoMsg(
                Payload.testo(root, "trenoId", ""),
                Payload.testo(root, "stazioneId", ""),
                Payload.testo(root, "tipo", "ENTRATA"),
                Payload.istante(root));
    }

    /** true se il convoglio sta entrando in stazione, false se ne sta uscendo. */
    public boolean entrata() {
        return "ENTRATA".equalsIgnoreCase(tipo);
    }
}
