package it.uni.reti2;

import java.time.Instant;
import java.util.Locale;
import java.util.logging.Logger;

// più propriamente telemetria out
/**
 * TrainElab invia periodicamente alla Centrale, con il protocollo MQTT e attraverso il
 * topic railway/train/{trenoId}/telemetry, la fotografia del convoglio (telemetria). La posizione NON è
 * simulata qui: le coordinate le scrive l'automa del viaggio (AutomaViaggio), che
 * interpola il percorso reale tra le stazioni dell'itinerario; qui viene solo simulata la
 * velocità istantanea.
 */
public class TrainElab {

    private static final Logger LOG = Logger.getLogger(TrainElab.class.getName());

    /**
     * Database locale del treno per recuperare coordinate, stato e dati del viaggio.
     */
    TrainDB trainDB;

    /**
     * Coda dei messaggi MQTT in uscita: il frame ci entra e lo pubblica il suo thread.
     */
    CodaPubblicazioni coda;

    /**
     * Invio periodico dei dati di telemetria. Uguale a all'heath beath della stazione
     * Lo chiama l'Orologio una volta al secondo, in background, e manda un pacchetto di dati
     * (posizioni, velocità, stato, condizione e fase del viaggio, progresso della tratta,
     * passeggeri) sul topic
     * railway/train/{trenoId}/telemetry verso la Centrale Operativa.
     *
     * Come l'heartbeat della stazione, l'invio è filtrato su trenoRiconosciuto: finché
     * la Centrale non conferma che l'ID esiste nel database centrale non parte nemmeno un
     * frame. Senza questo filtro un treno lanciato con un ID inventato avrebbe potuto
     * farsi creare a DB dalla telemetria e poi auto-validarsi, rendendo inutile la verifica.
     */
    public void inviaTelemetria() {
        // ID non ancora validato dalla Centrale: nessuna telemetria
        if (!trainDB.trenoRiconosciuto) {
            LOG.fine("⏳ Treno non ancora validato dalla Centrale: telemetria sospesa.");
            return;
        }

        // La velocità è l'unico dato ancora simulato dai sensori:
        // 0 se il treno è fermo, altrimenti un valore realistico tra 80 e 160 km/h
        if ("IN_VIAGGIO".equals(trainDB.stato())) {
            trainDB.velocita = 80 + Math.random() * 80;
        } else {
            trainDB.velocita = 0;
        }

        // Costruisce il payload JSON contenente il frame telemetrico completo
        // (stazioneCorrente/prossimaStazione: stringa vuota se assenti).
        // Oltre a "stato", che e' un riassunto calcolato da TrainDB.stato(), il frame porta
        // lo stato vero dei due automi: condizione (T6) e faseViaggio (T5). Dal solo "stato"
        // non si torna indietro: FERMO vale sia per un treno in sosta al binario sia per uno
        // trattenuto a meta' tratta, e la web app non sapeva piu' quale dei due mostrare.
        String json = String.format(Locale.US,
                "{\"trenoId\":\"%s\",\"stato\":\"%s\",\"condizione\":\"%s\",\"faseViaggio\":\"%s\","
                        + "\"latitudine\":%.5f,\"longitudine\":%.5f,"
                        + "\"velocita\":%.1f,\"progressPercent\":%.1f,\"passeggeri\":%d,"
                        + "\"stazioneCorrente\":\"%s\",\"prossimaStazione\":\"%s\",\"direzione\":\"%s\",\"timestamp\":\"%s\"}",
                trainDB.trenoId, trainDB.stato(), trainDB.condizione, trainDB.faseViaggio,
                trainDB.latitudine, trainDB.longitudine,
                trainDB.velocita, trainDB.progressPercent,
                trainDB.passeggeri,
                trainDB.stazioneCorrente != null ? trainDB.stazioneCorrente : "",
                trainDB.prossimaStazione != null ? trainDB.prossimaStazione : "",
                trainDB.direzione,
                Instant.now().toString()
        );

        LOG.fine(String.format("🚂 [TELEMETRIA] Invio: %s", json));

        // QoS 0: se un frame si perde, fra un secondo ne arriva uno più fresco
        coda.metti("railway/train/" + trainDB.trenoId + "/telemetry", json, 0);
    }
}
