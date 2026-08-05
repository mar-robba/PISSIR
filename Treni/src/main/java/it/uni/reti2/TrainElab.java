package it.uni.reti2;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * usando il protocollo mqtt e attraverso il canale "telemetry-out", TrainElab invia periodicamente
 * TrainElab gestisce la logica di elaborazione periodica (background tasks) e la lettura
 * dei sensori a bordo del treno. La posizione NON è più simulata a caso: le coordinate
 * arrivano dal digital twin (TrainJourneyEngine) che interpola il percorso reale
 * tra le stazioni dell'itinerario; qui viene solo simulata la velocità istantanea.
 */
@ApplicationScoped
public class TrainElab {

    private static final Logger LOG = Logger.getLogger(TrainElab.class);

    /**
     * Database locale del treno per recuperare coordinate, stato e dati del viaggio.
     */
    @Inject
    TrainDB trainDB;

    /**
     * Generatore reattivo continuo per i dati di telemetria. Uguale a all'heath beath della stazione
     * Viene eseguito in background e invia pacchetti di dati (posizioni, velocità, stato,
     * progresso della tratta, ritardo, passeggeri) sul canale d'uscita "telemetry-out"
     * verso la Centrale Operativa.
     *
     * Come l'heartbeat della stazione, il flusso è filtrato su trenoRiconosciuto: finché
     * la Centrale non conferma che l'ID esiste nel database centrale non parte nemmeno un
     * frame. Senza questo filtro un treno lanciato con un ID inventato avrebbe potuto
     * farsi creare a DB dalla telemetria e poi auto-validarsi, rendendo inutile la verifica.
     *
     * @return Multi (flusso reattivo di SmallRye Mutiny) che emette una stringa JSON periodicamente.
     */
    @Outgoing("telemetry-out")
    public Multi<String> generaTelemetria() {
        // Crea un flusso che "scatta" regolarmente ogni 5 secondi
        return Multi.createFrom()
                .ticks()                 // qui avviene ogni 5 sec
                .every(Duration.ofSeconds(5))
                .filter(tick -> {
                    // ID non ancora validato dalla Centrale: nessuna telemetria
                    if (!trainDB.trenoRiconosciuto) {
                        LOG.debug("⏳ Treno non ancora validato dalla Centrale: telemetria sospesa.");
                        return false;
                    }
                    return true;
                })
                .map(tick -> {
                    // La velocità è l'unico dato ancora simulato dai sensori:
                    // 0 se il treno è fermo, altrimenti un valore realistico tra 80 e 160 km/h
                    if ("IN_VIAGGIO".equals(trainDB.stato)) {
                        trainDB.velocita = 80 + Math.random() * 80;
                    } else {
                        trainDB.velocita = 0;
                    }

                    // Costruisce il payload JSON contenente il frame telemetrico completo
                    // (stazioneCorrente/prossimaStazione: stringa vuota se assenti)
                    String json = String.format(Locale.US,
                            "{\"trenoId\":\"%s\",\"stato\":\"%s\",\"latitudine\":%.5f,\"longitudine\":%.5f,"
                                    + "\"velocita\":%.1f,\"progressPercent\":%.1f,\"ritardoMinuti\":%d,\"passeggeri\":%d,"
                                    + "\"stazioneCorrente\":\"%s\",\"prossimaStazione\":\"%s\",\"direzione\":\"%s\",\"timestamp\":\"%s\"}",
                            trainDB.trenoId, trainDB.stato,
                            trainDB.latitudine, trainDB.longitudine,
                            trainDB.velocita, trainDB.progressPercent,
                            trainDB.ritardoMinuti, trainDB.passeggeri,
                            trainDB.stazioneCorrente != null ? trainDB.stazioneCorrente : "",
                            trainDB.prossimaStazione != null ? trainDB.prossimaStazione : "",
                            trainDB.direzione,
                            Instant.now().toString()
                    );

                    LOG.debugf("🚂 [TELEMETRIA] Invio: %s", json);

                    // Ritorna il payload che verrà poi processato dal connettore (es. inviato ad un broker)
                    return json;
                })
                // Un Multi che fallisce TERMINA: senza retry, al primo errore sul canale
                // (broker riavviato, rete che sfarfalla) il treno non manderebbe mai più
                // telemetria. Con il backoff il flusso si riaggancia da solo.
                .onFailure().invoke(e -> LOG.errorf("🔌 Errore sul canale telemetria: %s", e.getMessage()))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely();
    }
}
