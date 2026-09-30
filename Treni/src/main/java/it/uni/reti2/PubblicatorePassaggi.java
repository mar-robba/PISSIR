package it.uni.reti2;

import java.time.Instant;
import java.util.Locale;

/**
 * Pubblica i passaggi del treno in stazione (ENTRATA/USCITA) sul topic
 * "railway/treno-simulatoriSensori/{trenoId}", quello che ascoltano i sensori di binario.
 *
 * <p>Sta in una classe sua perché a chiamarla è GestoreEventi, nella fase delle azioni,
 * mentre il TrainGateway passa gli alert al gestore: con la pubblicazione dentro il gateway
 * i due si chiamerebbero a vicenda. PubblicatoreAlert fa la stessa cosa per railway/alerts.</p>
 *
 * <p>Pubblica e basta: lo stato del treno (la stazione in cui si trova, la direzione) lo
 * scrive AutomaViaggio prima, e qui lo si legge soltanto.</p>
 */
public class PubblicatorePassaggi {

    /** Stato del treno: id e direzione di marcia. */
    TrainDB trainDB;

    /**
     * Coda dei messaggi MQTT in uscita per le notifiche sul passaggio del treno ai sensori
     * di binario. I messaggi vanno sul topic railway/treno-simulatoriSensori/{trenoId}.
     */
    CodaPubblicazioni coda;

    /**
     * notifica di transito dentro una stazione
     * Annuncia ai sensori di binario della stazione il passaggio del treno, con l'ora
     * dell'evento e il verso di marcia.
     *
     * <p>Il treno non parla più direttamente con la Centrale su questo evento: pubblica
     * sul topic "railway/treno-simulatoriSensori/{trenoId}", che i sensori di binario
     * della stazione interessata ascoltano per rilevare fisicamente il passaggio (vedi
     * {@code SimulatoreSensori.rilevaPassaggio} lato Stazione). E' la stazione, non il
     * treno, a informare poi la Centrale con il transito ufficiale su
     * "railway/station/{stazioneId}/transit".</p>
     *
     * <p>La direzione viaggia comunque in questo messaggio perché al capolinea il treno
     * l'ha appena invertita, e nessun altro nodo lo saprebbe prima del prossimo frame di
     * telemetria. Il campo "ritardoMinuti" che stava qui è sparito insieme al contatore
     * di bordo: il ritardo lo calcola solo la Centrale.</p>
     *
     * @param stazioneId L'identificativo univoco della stazione interessata.
     * @param tipo Il tipo di passaggio (es. "ENTRATA" nella stazione, o "USCITA" dalla stazione).
     */
    public void notificaPassaggioStazione(String stazioneId, String tipo) {
        // Costruzione del messaggio JSON per la telemetria/segnalamento passaggi
        String json = String.format(Locale.US,
                "{\"trenoId\":\"%s\",\"stazioneId\":\"%s\",\"tipo\":\"%s\",\"timestamp\":\"%s\",\"direzione\":\"%s\"}",
                trainDB.trenoId, stazioneId, tipo, Instant.now().toString(), trainDB.direzione);

        // Invio del payload sul topic apposito (QoS 0 come la telemetria)
        coda.metti("railway/treno-simulatoriSensori/" + trainDB.trenoId, json, 0);
    }
}
