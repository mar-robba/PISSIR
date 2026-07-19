package it.uni.reti2;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@QuarkusMain
public class main implements QuarkusApplication {
    @Inject
    DBLocale dbLocale;

    private static final Logger LOG = Logger.getLogger(main.class);
    private static String STAZIONE_ID_PARAM;

    public static void main(String[] args) {
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty() || "null".equalsIgnoreCase(args[0].trim())) {
            System.err.println("❌ ERRORE: ID della stazione non fornito o non valido!");
            System.err.println("Utilizzo: java -jar stazione-app.jar <STAZIONE_ID>");
            System.exit(1);
        }

        String stazioneId = args[0].trim();
        STAZIONE_ID_PARAM = stazioneId;
        System.setProperty("stazione.id", stazioneId);

        LOG.infof("🚉 Avvio processo Stazione con ID: %s", stazioneId);
        Quarkus.run(main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        dbLocale.stazioneId = STAZIONE_ID_PARAM;
        LOG.info("✅ Processo Stazione AVVIATO CORRETTAMENTE");
        LOG.info("🔄 Ingresso nel ciclo infinito di mantenimento del nodo Stazione...");
        LOG.info("🌐 Server REST disponibile su http://localhost:8081/stazione/info");

        while (true) {
            Thread.sleep(1000);
        }
    }
}
