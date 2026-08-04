package it.uni.reti2;

import io.smallrye.mutiny.Multi;
import it.uni.reti2.DBLocale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Componente responsabile della generazione e dell'invio ciclico del segnale
 * di vitalità (Heartbeat) della stazione verso la Centrale Operativa.
 * Gestisce anche il meccanismo di Store and Forward per svuotare il buffer
 * degli eventi pendenti non appena la connessione viene ristabilita, e il
 * controllo di keepalive dei sensori di binario.
 */
@ApplicationScoped
public class HeartbeatGenerator implements HeartbeatKA {
    private static final Logger LOG = Logger.getLogger(HeartbeatGenerator.class);

    @Inject
    DBLocale dbLocale;

    @Inject
    LocalBuffer localBuffer;

    /**
     * Gateway verso la Centrale: usato per il flush reale del buffer
     * e per segnalare i sensori che non rispondono più.
     */
    @Inject
    StationGateway stationGateway;

    /**
     * Secondi di silenzio oltre i quali un sensore viene considerato guasto.
     */
    @ConfigProperty(name = "sensori.timeout.secondi", defaultValue = "30")
    long timeoutSensoriSecondi;

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
     * Producer reattivo che emette l'heartbeat sul canale MQTT dedicato ("heartbeat-out").
     * Viene eseguito ogni 10 secondi in background. A ogni tick, oltre al battito:
     * - controlla i keepalive dei sensori di binario;
     * - se la connessione è attiva e ci sono eventi pendenti, svuota il buffer
     *   tramite il flush reale del Gateway.
     * Se la connessione verso la Centrale è simulata come assente, il battito
     * NON viene emesso (così la Centrale rileva la stazione come OFFLINE).
     * Allo stesso modo, finché l'ID della stazione non è stato riconosciuto dal
     * database centrale (validazione MQTT asincrona via StationDatabaseValidator)
     * nessun battito viene emesso: il tick di Mutiny continua a scattare ogni 10s
     * in background (costo trascurabile), ma resta filtrato finché
     * dbLocale.stazioneRiconosciuta non diventa true; al tick successivo alla
     * conferma l'heartbeat riparte da solo, senza alcun accoppiamento diretto
     * con StationDatabaseValidator.
     *
     * @return Il flusso dati Multi di Mutiny contenente le stringhe JSON dei battiti cardiaci.
     */
    @Outgoing("heartbeat-out")
    public Multi<String> generaHeartbeat() {
        // Genera un evento reattivo periodico
        return Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(10))
                .invoke(tick -> {
                    // Verifica dei sensori che non mandano più il keepalive
                    controllaSensori();

                    // Se la connessione è attiva e ci sono eventi che non erano stati inviati...
                    if (dbLocale.connessioneCentrale && !localBuffer.isEmpty()) {
                        LOG.infof("📡 Connessione ok, svuoto il buffer (%d eventi)...", localBuffer.size());
                        // Reinvia effettivamente gli eventi sugli emitter corretti
                        stationGateway.flush();
                    }
                })
                .filter(tick -> {
                    // ID non ancora validato dalla Centrale: nessun battito finché non arriva
                    // la conferma via MQTT (StationDatabaseValidator.gestisciRispostaValidazione).
                    if (!dbLocale.stazioneRiconosciuta) {
                        LOG.warn("⏳ Stazione non ancora validata dalla Centrale: heartbeat sospeso.");
                        return false;
                    }
                    return true;
                })
                .filter(tick -> {
                    // Rete simulata giù: si salta il battito, la Centrale ci vedrà OFFLINE
                    if (!dbLocale.connessioneCentrale) {
                        LOG.warn("🔌 Offline: heartbeat non inviato alla Centrale.");
                        return false;
                    }
                    return true;
                })
                .map(tick -> {
                    // Prepara il messaggio
                    String json = generatePayload(dbLocale.stazioneId, dbLocale.stato, localBuffer.size());
                    LOG.infof("💓 [HEARTBEAT] Invio heartbeat: %s", json);
                    return json;
                })
                // Gestione reattiva degli errori di connessione sul canale di invio
                .onFailure().invoke(e -> {
                    LOG.error("🔌 Errore connessione MQTT Centrale!");
                    // Contrassegna la connessione come persa in modo che Gateway sappia
                    // che deve usare il buffer per i prossimi eventi.
                    dbLocale.connessioneCentrale = false;
                });
    }

   //boh

    /**
     * Controlla la mappa dei sensori monitorati: quelli il cui ultimo battito
     * è più vecchio del timeout configurato vengono segnalati alla Centrale
     * con un guasto di severità WARNING (la stazione resta ONLINE) e rimossi
     * dalla mappa, così la segnalazione avviene una sola volta.
     */
    private void controllaSensori() {
        Instant limite = Instant.now().minusSeconds(timeoutSensoriSecondi);
        for (Map.Entry<String, Instant> sensore : dbLocale.sensoriUltimoBattito.entrySet()) {
            if (sensore.getValue().isBefore(limite)) {
                LOG.warnf("⚠️ Sensore %s silente da oltre %d secondi!", sensore.getKey(), timeoutSensoriSecondi);
                stationGateway.inviaGuasto("Sensore " + sensore.getKey() + " non invia keepalive", "WARNING");
                // Rimozione dalla mappa: evita di rigenerare lo stesso alert a ogni tick
                dbLocale.sensoriUltimoBattito.remove(sensore.getKey());
            }
        }
    }
}
