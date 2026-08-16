package it.uni.reti2;

import io.quarkus.scheduler.Scheduled;
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
     * Job periodico di manutenzione del nodo, volutamente SEPARATO dal flusso di heartbeat:
     * - controlla i keepalive dei sensori di binario;
     * - se la connessione è attiva e ci sono eventi pendenti, svuota il buffer
     *   tramite il flush reale del Gateway (store and forward).
     *
     * Prima queste due operazioni giravano dentro l'.invoke() del Multi dell'heartbeat:
     * siccome un Multi che fallisce termina, al primo errore MQTT si fermava anche lo
     * store-and-forward e gli eventi bufferizzati non sarebbero mai più partiti.
     * Con un job schedulato indipendente il buffer continua a svuotarsi comunque.
     */
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void manutenzionePeriodica() {
        // Verifica dei sensori che non mandano più il keepalive
        controllaSensori();

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
                })
                // invoke() esegue solo l'effetto collaterale: il flusso resterebbe comunque
                // terminato e la stazione non batterebbe MAI più (per la Centrale sarebbe
                // OFFLINE per sempre). Il retry con backoff riaggancia il canale da solo
                // quando il broker torna disponibile.
                .onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely();
    }

   //boh

    /**
     * Controlla la mappa dei sensori monitorati: quelli il cui ultimo battito
     * è più vecchio del timeout configurato vengono segnalati alla Centrale
     * e rimossi dalla mappa, così la segnalazione avviene una sola volta.
     *
     * La severità è CRITICAL perché il PDF chiede che una stazione che non riceve
     * più i keepalive dai propri sensori "segnali lo stato di guasta alla Centrale
     * Operativa": inviaGuasto con CRITICAL porta dbLocale.stato a GUASTA, quindi i
     * treni in arrivo vengono trattenuti finché non arriva la squadra di manutenzione.
     */
    private void controllaSensori() {
        Instant limite = Instant.now().minusSeconds(timeoutSensoriSecondi);
        for (Map.Entry<String, Instant> sensore : dbLocale.sensoriUltimoBattito.entrySet()) {
            if (sensore.getValue().isBefore(limite)) {
                LOG.warnf("⚠️ Sensore %s silente da oltre %d secondi: stazione GUASTA!", sensore.getKey(), timeoutSensoriSecondi);
                // "sensore_offline" dichiarato esplicitamente: questo è davvero il guasto di
                // un sensore di binario, e va distinto dagli altri guasti di terra.
                stationGateway.inviaGuasto("Sensore " + sensore.getKey() + " non invia keepalive",
                        "CRITICAL", "sensore_offline");
                // Rimozione dalla mappa: evita di rigenerare lo stesso alert a ogni tick
                dbLocale.sensoriUltimoBattito.remove(sensore.getKey());
            }
        }
    }
}
