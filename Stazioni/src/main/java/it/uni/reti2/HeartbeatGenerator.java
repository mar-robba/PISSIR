package it.uni.reti2;

import io.quarkus.scheduler.Scheduled;
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
 *
 * <p>Il keepalive dei sensori di binario, che prima stava qui dentro, adesso è tutto in
 * {@link MonitorSensori}: sono due heartbeat diversi (questo va verso la Centrale, quello
 * arriva dai dispositivi di campo) e tenerli insieme aveva solo confuso le cose.</p>
 */
@ApplicationScoped
public class HeartbeatGenerator implements HeartbeatKA {
    private static final Logger LOG = Logger.getLogger(HeartbeatGenerator.class);

    @Inject
    DBLocale dbLocale;

    @Inject
    LocalBuffer localBuffer;

    /**
     * Gateway verso la Centrale: usato per il flush reale del buffer.
     */
    @Inject
    StationGateway stationGateway;

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
     * Job periodico di manutenzione del nodo, volutamente SEPARATO dal flusso di heartbeat:
     * se la connessione è attiva e ci sono eventi pendenti, svuota il buffer
     * tramite il flush reale del Gateway (store and forward).
     *
     * Prima queste due operazioni giravano dentro l'.invoke() del Multi dell'heartbeat:
     * siccome un Multi che fallisce termina, al primo errore MQTT si fermava anche lo
     * store-and-forward e gli eventi bufferizzati non sarebbero mai più partiti.
     * Con un job schedulato indipendente il buffer continua a svuotarsi comunque.
     */
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void manutenzionePeriodica() {
        // Se la connessione è attiva e ci sono eventi che non erano stati inviati...
        if (dbLocale.connessioneCentrale && !localBuffer.isEmpty()) {
            LOG.infof("📡 Connessione ok, svuoto il buffer (%d eventi)...", localBuffer.size());
            // Reinvia effettivamente gli eventi sugli emitter corretti
            stationGateway.flush();
        }
    }

    /**
     * Producer reattivo che emette l'heartbeat sul canale MQTT dedicato ("heartbeat-out").
     * Viene eseguito ogni 10 secondi in background.
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
                    String json = generatePayload(dbLocale.stazioneId, dbLocale.getStato(), localBuffer.size());
                    LOG.infof("💓 [HEARTBEAT] Invio heartbeat: %s", json);
                    return json;
                })
                // Gestione reattiva degli errori di connessione sul canale di invio
                .onFailure().invoke(e -> {
                    LOG.error("🔌 Errore connessione MQTT Centrale!");
                    // Contrassegna la connessione come persa in modo che Gateway sappia
                    // che deve usare il buffer per i prossimi eventi.
                    dbLocale.connessioneCentrale = false;
                })
                // invoke() esegue solo l'effetto collaterale: il flusso resterebbe comunque
                // terminato e la stazione non batterebbe MAI più (per la Centrale sarebbe
                // OFFLINE per sempre). Il retry con backoff riaggancia il canale da solo
                // quando il broker torna disponibile.
                .onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely();
    }

}
