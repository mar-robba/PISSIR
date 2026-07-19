package it.uni.reti2;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Punto di ingresso del processo Treno.
 * Questo file contiene l'avvio del processo treno e simula un treno reale.
 * L'ID del treno è fornito come parametro di input agli args della riga di comando.
 * Il processo mantiene un ciclo infinito per simulare il funzionamento continuo del treno.
 */
@QuarkusMain
public class main implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(main.class);
    private static String TRENO_ID_PARAM;

    @Inject
    TrainDB trainDB;

    public static void main(String[] args) {
        // Validazione dei parametri di input
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty() || "null".equalsIgnoreCase(args[0].trim())) {
            System.err.println("❌ ERRORE: ID del treno non fornito o non valido!");
            System.err.println("Utilizzo: java -jar treno-app.jar <TRENO_ID>");
            System.err.println("Esempio: java -jar treno-app.jar ALFA-100");
            System.exit(1);
        }

        // Estrae l'ID del treno dal primo parametro
        String trenoId = args[0].trim();
        TRENO_ID_PARAM = trenoId;
        
        // Imposta la proprietà di sistema per Quarkus
        // Questa proprietà verrà utilizzata dalla classe TrainDB per impostare trenoId
        System.setProperty("treno.id", trenoId);

        LOG.infof("🚂 Avvio processo Treno con ID: %s", trenoId);

        // Avvia l'applicazione Quarkus passando il controllo a questa classe
        Quarkus.run(main.class, args);
    }

    /**
     * Metodo eseguito quando Quarkus è completamente avviato.
     * Mantiene il processo in esecuzione con un ciclo infinito.
     * @return Codice di uscita (0 = successo)
     */
    @Override
    public int run(String... args) throws Exception {
        trainDB.trenoId = TRENO_ID_PARAM;
        LOG.info("✅ Processo Treno AVVIATO CORRETTAMENTE");
        LOG.info("🔄 Ingresso nel ciclo infinito di simulazione del treno...");
        LOG.info("🌐 Server REST disponibile su http://localhost:8082/treno/info");
        LOG.info("📡 Trasmissione telemetria su topic MQTT: railway/telemetry");
        LOG.info("🔴 Ascolto alert su topic MQTT: railway/alerts");

        // Ciclo infinito per mantenere il processo in esecuzione
        // Le altre classi (TrainElab, TrainGateway, TrainIngestion) gestiscono
        // la logica in background grazie a Quarkus e ai canali reactive messaging
        while (true) {
            Thread.sleep(1000); // Sleep per evitare busy-wait
        }
    }
}
