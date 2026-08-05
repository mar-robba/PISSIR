package it.uni.reti2;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Punto di ingresso del servizio Centrale Operativa.
 * Avvia l'applicazione Quarkus e mantiene il processo in esecuzione.
 */
@QuarkusMain
public class main implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(main.class);

    /** Porta HTTP davvero configurata (8781): prima il log ne stampava un'altra. */
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8781")
    int portaHttp;

    public static void main(String[] args) {
        LOG.info("🚦 Avvio processo Centrale Operativa...");
        Quarkus.run(main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        LOG.info("✅ Centrale Operativa AVVIATA CORRETTAMENTE");
        LOG.infof("🌐 Server REST disponibile su http://localhost:%d/api", portaHttp);
        LOG.info("🔄 Ingresso nel ciclo infinito di mantenimento del servizio Centrale Operativa...");

        while (true) {
            Thread.sleep(1000);
        }
    }
}
