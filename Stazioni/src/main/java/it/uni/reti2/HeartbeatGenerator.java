package it.uni.reti2;

import io.smallrye.mutiny.Multi;
import it.uni.reti2.DBLocale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * Componente responsabile della generazione e dell'invio ciclico del segnale
 * di vitalità (Heartbeat) della stazione verso la Centrale Operativa.
 * Gestisce anche il meccanismo di Store and Forward per svuotare il buffer
 * degli eventi pendenti non appena la connessione viene ristabilita.
 */
@ApplicationScoped
public class HeartbeatGenerator implements HeartbeatKA {
    private static final Logger LOG = Logger.getLogger(HeartbeatGenerator.class);

    @Inject
    DBLocale dbLocale;

    @Inject
    LocalBuffer localBuffer;

    /**
     * Costruisce il messaggio JSON di heartbeat implementando l'interfaccia.
     */
    @Override
    public String generatePayload(String stazioneId, String stato) {
        return String.format(
                "{\"stazioneId\":\"%s\", \"stato\":\"%s\", \"timestamp\":\"%s\", \"tipoEvento\":\"HEARTBEAT\"}",
                stazioneId, stato, Instant.now().toString()
        );
    }

    /**
     * Producer reattivo che emette l'heartbeat sul canale MQTT dedicato ("heartbeat-out").
     * Viene eseguito ogni 10 secondi in background.
     *
     * @return Il flusso dati Multi di Mutiny contenente le stringhe JSON dei battiti cardiaci.
     */
    @Outgoing("heartbeat-out")
    public Multi<String> generaHeartbeat() {
        // Genera un evento reattivo periodico
        return Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(10))
                .map(tick -> {
                    // Prepara il messaggio
                    String json = generatePayload(dbLocale.stazioneId, dbLocale.stato);
                    LOG.infof("💓 [HEARTBEAT] Invio heartbeat: %s", json);
                    
                    // Se la connessione è attiva e ci sono eventi che non erano stati inviati...
                    if (dbLocale.connessioneCentrale && !localBuffer.isEmpty()) {
                        LOG.info("📡 Connessione ok, svuoto bufsasfer...");
                        // Svuota progressivamente il buffer 
                        // NOTA: l'implementazione attuale stampa solo a log l'evento,
                        // in produzione andrebbe richiamato un emitter per rinviare effettivamente i messaggi!
                        while (!localBuffer.isEmpty()) {
                            String eventoBuffered = localBuffer.poll();
                            LOG.infof("📤 Re-invio dal buffer: %s", eventoBuffered);
                        }
                    }
                    
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
}
