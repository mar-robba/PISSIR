package it.uni.reti2.ingestion.payload;

import com.google.gson.JsonObject;
import it.uni.reti2.entity.StatiCanonici;

/**
 * Il frame di telemetria che un convoglio spedisce ogni pochi secondi su railway/telemetry.
 *
 * <p>Lo stato arriva gia' tradotto nel vocabolario del database ({@link StatiCanonici}):
 * il resto della Centrale non deve nemmeno sapere che sul topic viaggiano IN_VIAGGIO e
 * EMERGENZA.</p>
 *
 * <p>Il ritardo NON e' un campo di questo messaggio, e non e' una dimenticanza: e' il
 * risultato del confronto fra l'orario previsto e il passaggio reale, un conto che sa
 * fare solo la Centrale perche' solo lei conosce l'orario di servizio.</p>
 */
public record TelemetriaMsg(
        String trenoId,
        String stato,
        double latitudine,
        double longitudine,
        double velocita,
        double progresso,
        int passeggeri,
        String stazioneCorrente,
        String prossimaStazione,
        String direzione) {

    public static TelemetriaMsg da(JsonObject root) {
        return new TelemetriaMsg(
                Payload.testo(root, "trenoId", "N/A"),
                StatiCanonici.trenoDalCampo(Payload.testo(root, "stato", "UNKNOWN")),
                Payload.numero(root, "latitudine", 0.0),
                Payload.numero(root, "longitudine", 0.0),
                Payload.numero(root, "velocita", 0.0),
                Payload.numero(root, "progressPercent", 0.0),
                Payload.intero(root, "passeggeri", 0),
                Payload.testoOpzionale(root, "stazioneCorrente"),
                Payload.testoOpzionale(root, "prossimaStazione"),
                Payload.testo(root, "direzione", "andata"));
    }
}
