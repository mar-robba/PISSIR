package it.uni.reti2.ingestion.payload;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Lo stesso passaggio fisico del {@link TransitoMsg}, ma dichiarato dal convoglio
 * invece che dal sensore di terra.
 *
 * <p>I due messaggi non sono ridondanti: dal treno arriva anche la direzione di marcia,
 * che al capolinea e' appena stata invertita e che la Centrale non puo' dedurre da
 * nessun'altra parte finche' non arriva il frame di telemetria successivo. Fra
 * l'inversione e quel frame la prossima stazione verrebbe cercata dalla parte
 * sbagliata dell'itinerario.</p>
 */
public record PassaggioMsg(String trenoId, String stazioneId, String tipo,
                           Instant quando, String direzioneDichiarata) {

    public static PassaggioMsg da(JsonNode root) {
        return new PassaggioMsg(
                Payload.testo(root, "trenoId", ""),
                Payload.testo(root, "stazioneId", ""),
                Payload.testo(root, "tipo", "ENTRATA"),
                Payload.istante(root),
                Payload.testo(root, "direzione", ""));
    }

    public boolean entrata() {
        return "ENTRATA".equalsIgnoreCase(tipo);
    }

    /**
     * La direzione da usare per leggere l'itinerario: quella dichiarata dal convoglio
     * se c'e', altrimenti quella che risulta in cache, altrimenti "andata".
     */
    public String direzione(String direzioneInCache) {
        if (direzioneDichiarata != null && !direzioneDichiarata.isEmpty()) {
            return direzioneDichiarata;
        }
        return direzioneInCache != null ? direzioneInCache : "andata";
    }
}
