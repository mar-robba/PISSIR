package it.uni.reti2;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Componente responsabile della generazione e dell'invio ciclico del segnale
 * di vitalità (Heartbeat) della stazione verso la Centrale Operativa, sul topic
 * railway/station/{id}/heartbeat.
 *
 * <p>Il keepalive dei sensori di binario è un'altra cosa e sta in AutomaSensori: questo
 * battito va verso la Centrale, quello arriva dai dispositivi di campo. Nemmeno lo
 * svuotamento del buffer passa da qui: lo fa il ConsumatoreBuffer, che pubblica appena
 * può.</p>
 *
 * <p>Il battito non passa dal LocalBuffer ma si pubblica direttamente, a QoS 0: vale solo
 * adesso. Rimandarne uno vecchio di minuti dopo una caduta direbbe alla Centrale una cosa
 * falsa (una stazione viva in un momento in cui non lo era), quindi se non parte si perde e
 * basta: dieci secondi dopo ne parte un altro. Come la telemetria del treno, legge lo stato e
 * non muove nessun automa, quindi non passa nemmeno dal gestore degli eventi.</p>
 */
public class HeartbeatGenerator implements HeartbeatKA {
    private static final Logger LOG = Logger.getLogger(HeartbeatGenerator.class.getName());

    /** Stato del nodo: id, stato operativo, riconoscimento. Assegnato dal main. */
    DBLocale dbLocale;

    /** La coda verso la Centrale: nel battito viaggia quanti eventi aspettano. */
    LocalBuffer localBuffer;

    /** Il collegamento con la Centrale: da staccato il battito non parte. */
    AutomaConnessione connessione;

    /** Con che cosa si pubblica. */
    ClientMqtt clientMqtt;

    /**
     * Il battito è periodico: stamparlo tutte le volte riempirebbe il log di righe uguali.
     * Se ne stampa una quando parte (e una ogni volta che riprende dopo una pausa), il
     * payload completo resta in DEBUG. Questi due campi ricordano cosa è già stato detto,
     * così anche il motivo per cui il battito è sospeso si scrive una volta sola e non a
     * ogni giro. Li usa solo il thread dell'orologio.
     */
    private boolean battitoAnnunciato;
    private String motivoSospensione;

    /**
     * Costruisce il messaggio JSON di heartbeat implementando l'interfaccia.
     * Include la dimensione del buffer locale come informazione diagnostica.
     */
    @Override
    public String generatePayload(String stazioneId, String stato, int bufferSize) {
        return String.format(
                "{\"stazioneId\":\"%s\",\"stato\":\"%s\",\"timestamp\":\"%s\",\"tipoEvento\":\"HEARTBEAT\",\"bufferSize\":%d}",
                stazioneId, stato, Instant.now().toString(), bufferSize
        );
    }

    /**
     * Scrive il motivo per cui il battito non parte, ma una volta sola: finché il motivo
     * resta quello l'orologio continua a scattare ogni 10 secondi in silenzio invece di
     * ripetere la stessa riga. Alla ripresa {@link #generaHeartbeat()} azzera il campo,
     * così la volta dopo la sospensione si rivede.
     *
     * @param motivo Il messaggio da scrivere nel log.
     */
    private void sospendi(String motivo) {
        if (!motivo.equals(motivoSospensione)) {
            LOG.warning(motivo);
            motivoSospensione = motivo;
            // Quando il battito riprende va detto: è la notizia opposta a questa.
            battitoAnnunciato = false;
        }
    }

    /**
     * Un battito: lo chiama l'Orologio ogni 10 secondi, in background.
     * Se la connessione verso la Centrale è simulata come assente, il battito
     * NON viene emesso (così la Centrale rileva la stazione come OFFLINE).
     * Allo stesso modo, finché l'ID della stazione non è stato riconosciuto dal
     * database centrale (validazione MQTT asincrona via StationDatabaseValidator)
     * nessun battito viene emesso: l'orologio continua a scattare ogni 10s
     * (costo trascurabile), ma il battito resta filtrato finché
     * dbLocale.stazioneRiconosciuta non diventa true; al giro successivo alla
     * conferma l'heartbeat riparte da solo, senza alcun accoppiamento diretto
     * con StationDatabaseValidator.
     */
    public void generaHeartbeat() {
        // ID non ancora validato dalla Centrale: nessun battito finché non arriva
        // la conferma via MQTT (StationDatabaseValidator.gestisciRispostaValidazione).
        if (!dbLocale.stazioneRiconosciuta) {
            sospendi("⏳ Stazione non ancora validata dalla Centrale: heartbeat sospeso.");
            return;
        }
        // Rete simulata giù: si salta il battito, la Centrale ci vedrà OFFLINE
        if (!connessione.collegata()) {
            sospendi("🔌 Offline: heartbeat non inviato alla Centrale.");
            return;
        }

        String json = generatePayload(dbLocale.stazioneId, dbLocale.getStato(), localBuffer.size());
        try {
            clientMqtt.pubblica("railway/station/" + dbLocale.stazioneId + "/heartbeat", json, 0);
        } catch (Exception e) {
            // Broker giù davvero: Paho si sta già ricollegando da solo, il battito si perde
            sospendi("🔌 Broker MQTT non raggiungibile: heartbeat non inviato alla Centrale.");
            return;
        }

        if (!battitoAnnunciato) {
            // Una riga sola per dire che il battito c'è: il dettaglio è in DEBUG.
            LOG.info("💓 [HEARTBEAT] Battito verso la Centrale ogni 10 secondi.");
            battitoAnnunciato = true;
        }
        motivoSospensione = null;
        LOG.fine(String.format("💓 [HEARTBEAT] Invio heartbeat: %s", json));
    }
}
