package it.uni.reti2.spark;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.sql.SparkSession;

@ApplicationScoped
public class SparkProcessorService {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SparkProcessorService.class);
    
    private SparkSession sparkSession;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("🚀 Avvio inizializzazione Apache Spark in background...");

        // Disabilita log troppo verbosi di Spark
        Logger.getLogger("org").setLevel(Level.WARN);
        Logger.getLogger("akka").setLevel(Level.WARN);

        new Thread(() -> {
            try {
                sparkSession = SparkSession.builder()
                        .appName("CentraleOperativaSpark-Quarkus")
                        .master("local[2]") // Esecuzione locale con 2 thread
                        .getOrCreate();

                LOG.info("✅ Apache Spark Session inizializzata con successo in Quarkus!");
                
                // Qui potremmo aggiungere job periodici (es. aggregazione batch dai dati PostgreSQL)
                // oppure avviare Structured Streaming se non stessimo già usando Quarkus MQTT.
                
            } catch (Exception e) {
                LOG.error("❌ Errore durante l'inizializzazione di Spark: ", e);
            }
        }).start();
    }

    public SparkSession getSparkSession() {
        return sparkSession;
    }
}
