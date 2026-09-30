package it.uni.reti2.ingestion.payload;

import com.google.gson.JsonObject;

/**
 * Il battito con cui una stazione dichiara di essere viva, e in che stato si trova.
 *
 * <p>Lo stato resta quello grezzo dichiarato dal nodo: la Centrale non lo accetta per
 * buono in ogni caso (una stazione in MANUTENZIONE continua a battere e a dichiararsi
 * GUASTA, ma lo stato lo decide la Centrale, RF01.4.1). La regola sta in
 * {@code AutomaStazione.accettaDalCampo}, non qui: questo e' solo cio' che il nodo ha detto.</p>
 */
public record HeartbeatMsg(String stazioneId, String stato) {

    public static HeartbeatMsg da(JsonObject root) {
        return new HeartbeatMsg(
                Payload.testo(root, "stazioneId", "N/A"),
                Payload.testo(root, "stato", "ONLINE"));
    }
}
