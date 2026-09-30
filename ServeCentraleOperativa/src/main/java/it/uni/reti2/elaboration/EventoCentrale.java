package it.uni.reti2.elaboration;

import com.google.gson.JsonObject;
import it.uni.reti2.ingestion.payload.AlertMsg;
import it.uni.reti2.ingestion.payload.HeartbeatMsg;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;

/**
 * Un evento per gli automi della Centrale: che cosa è successo e i dati che servono per reagire.
 *
 * <p>Lo costruiscono i generatori di eventi, che traducono in questa forma quello che arriva
 * da fuori: IngestionService (i messaggi MQTT del campo), RestApiGateway (i comandi
 * dell'operatore) e GeneratoreEventiTempo (il tick del watchdog). Lo consuma GestoreEventi,
 * che è l'unico a far muovere gli automi. I campi che a un tipo di evento non servono restano
 * null.</p>
 *
 * @param tipo            Che cosa è successo.
 * @param messaggio       Il messaggio del campo già letto (TelemetriaMsg, TransitoMsg,
 *                        HeartbeatMsg o AlertMsg), per gli eventi che arrivano da MQTT.
 * @param payloadDalCampo Il JSON originale del messaggio: telemetria, heartbeat e transiti lo
 *                        rilanciano al browser così com'è, con i campi della Centrale in più.
 * @param id              Il treno, la stazione o il guasto a cui si riferisce un comando
 *                        dell'operatore.
 * @param stato           Lo stato scelto dall'operatore (STATO_STAZIONE_OPERATORE,
 *                        STATO_TRENO_OPERATORE).
 */
public record EventoCentrale(Tipo tipo, Record messaggio, JsonObject payloadDalCampo, String id, String stato) {

    /** L'alfabeto degli automi della Centrale: tutto quello che li può far muovere. */
    public enum Tipo {
        // ---- dal campo, via MQTT (IngestionService)
        /** Frame di telemetria di un convoglio (railway/train/+/telemetry). */
        TELEMETRIA,
        /** Passaggio rilevato dal sensore di terra di una stazione (railway/station/+/transit). */
        PASSAGGIO,
        /** Battito di una stazione (railway/station/+/heartbeat). */
        HEARTBEAT,
        /** Alert GUASTO di un nodo del campo su railway/alerts. */
        GUASTO_SEGNALATO,
        /** Alert RESOLVED di un nodo del campo: dichiara rientrato un guasto che aveva segnalato. */
        GUASTO_RIENTRATO,

        // ---- dall'operatore, via REST (RestApiGateway)
        /** POST /api/allarmi/{id}/risolvi. */
        RISOLVI_GUASTO,
        /** POST /api/treni/{id}/sopprimi. */
        SOPPRIMI_TRENO,
        /** POST /api/stazioni/{id}/manutenzione. */
        INVIA_SQUADRA,
        /** PUT /api/stazioni/{id} con il campo "stato". */
        STATO_STAZIONE_OPERATORE,
        /** PUT /api/treni/{id} con il campo "stato". */
        STATO_TRENO_OPERATORE,

        // ---- dal tempo (GeneratoreEventiTempo)
        /** Tick del watchdog: cerca le stazioni che non battono più. */
        CONTROLLO_HEARTBEAT,
        /** Tick del watchdog: cerca i convogli fermi fuori da una stazione. */
        CONTROLLO_TRENI_FERMI,
        /** Tick della fotografia: manda al browser lo stato completo della rete. */
        FOTOGRAFIA
    }

    /** @return Un evento del tempo, che non porta dati. */
    public static EventoCentrale di(Tipo tipo) {
        return new EventoCentrale(tipo, null, null, null, null);
    }

    /** @return L'evento TELEMETRIA con il frame letto e il JSON originale. */
    public static EventoCentrale telemetria(TelemetriaMsg msg, JsonObject payload) {
        return new EventoCentrale(Tipo.TELEMETRIA, msg, payload, null, null);
    }

    /** @return L'evento PASSAGGIO con il transito letto e il JSON originale. */
    public static EventoCentrale passaggio(TransitoMsg msg, JsonObject payload) {
        return new EventoCentrale(Tipo.PASSAGGIO, msg, payload, null, null);
    }

    /** @return L'evento HEARTBEAT con il battito letto e il JSON originale. */
    public static EventoCentrale heartbeat(HeartbeatMsg msg, JsonObject payload) {
        return new EventoCentrale(Tipo.HEARTBEAT, msg, payload, null, null);
    }

    /** @return GUASTO_SEGNALATO o GUASTO_RIENTRATO con l'alert letto. */
    public static EventoCentrale alert(Tipo tipo, AlertMsg msg) {
        return new EventoCentrale(tipo, msg, null, null, null);
    }

    /** @return Un comando dell'operatore su un treno, una stazione o un guasto. */
    public static EventoCentrale comando(Tipo tipo, String id) {
        return new EventoCentrale(tipo, null, null, id, null);
    }

    /** @return Un cambio di stato deciso dall'operatore dal pannello di amministrazione. */
    public static EventoCentrale statoDallOperatore(Tipo tipo, String id, String stato) {
        return new EventoCentrale(tipo, null, null, id, stato);
    }
}
