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
 * TrainElab gestisce la logica di elaborazione periodica (background tasks) e simulazione
 * dei sensori a bordo del treno. Nello specifico, simula lo spostamento fisico 
 * del convoglio e l'invio costante di dati telemetrici.
 */
@ApplicationScoped
public class TrainElab {
    // boh
    private static final Logger LOG = Logger.getLogger(TrainElab.class);

    /**
     * Database locale del treno per recuperare e aggiornare coordinate e stato.
     */
    @Inject
    TrainDB trainDB;

    /**
     * Generatore reattivo continuo per i dati di telemetria. Uguale a all'heath beath della stazione
     * Viene eseguito in background e invia pacchetti di dati (posizioni, velocità, stato)
     * sul canale d'uscita "telemetry-out" verso la Centrale Operativa.
     *
     * @return Multi (flusso reattivo di SmallRye Mutiny) che emette una stringa JSON periodicamente.
     */
    @Outgoing("telemetry-out")
    public Multi<String> generaTelemetria() {
        // Crea un flusso che "scatta" regolarmente ogni 5 secondi
        return Multi.createFrom()
                .ticks()                 // qui avviene ogni 5 sec 
                .every(Duration.ofSeconds(5))
                .map(tick -> {
                    // Simula il movimento fisico del treno solo se è in marcia
                    if ("IN_VIAGGIO".equals(trainDB.stato)) {
                        // Variazione randomica di latitudine e longitudine per simulare il tragitto
                        // (Math.random() - 0.5) produce valori tra -0.5 e 0.5, moltiplicati per il passo (0.005)
                        trainDB.latitudine += (Math.random() - 0.5) * 0.005;
                        trainDB.longitudine += (Math.random() - 0.5) * 0.005;
                        // Simula leggere variazioni di velocità tra 60 e 180 km/h
                        trainDB.velocita = 60 + Math.random() * 120;
                    } 
                    // Se il treno non è in marcia, assicura che la velocità rilevata dai sensori sia 0
                    else if ("FERMO".equals(trainDB.stato) || "EMERGENZA".equals(trainDB.stato) || "SOPPRESSO".equals(trainDB.stato)) {
                        trainDB.velocita = 0;
                    }

                    // Costruisce il payload JSON contenente il frame telemetrico completo
                    String json = String.format(Locale.US,
                            "{\"trenoId\":\"%s\", \"stato\":\"%s\", \"latitudine\":%f, \"longitudine\":%f, \"velocita\":%f, \"timestamp\":\"%s\"}",
                            trainDB.trenoId, trainDB.stato, trainDB.latitudine, trainDB.longitudine, trainDB.velocita, Instant.now().toString()
                    );
                    
                    LOG.debugf("🚂 [TELEMETRIA] Invio: %s", json);
                    
                    // Ritorna il payload che verrà poi processato dal connettore (es. inviato ad un broker)
                    return json;
                });
    }
}
