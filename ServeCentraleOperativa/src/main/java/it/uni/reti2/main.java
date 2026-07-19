package it.uni.reti2;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;

/**
 * Punto di ingresso del servizio Centrale Operativa.
 * Avvia l'applicazione Quarkus e mantiene il processo in esecuzione.
 */
@QuarkusMain
public class main implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(main.class);

    public static void main(String[] args) {
        LOG.info("🚦 Avvio processo Centrale Operativa...");
        Quarkus.run(main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        LOG.info("✅ Centrale Operativa AVVIATA CORRETTAMENTE");
        LOG.info("🌐 Server REST disponibile su http://localhost:8080/api");
        LOG.info("🔄 Ingresso nel ciclo infinito di mantenimento del servizio Centrale Operativa...");

        while (true) {
            Thread.sleep(1000);
        }
    }
}
