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
            // In dev mode Quarkus non passa argomenti: non si esce, si usa la property "stazione.id"
            LOG.warn("⚠️ ID della stazione non fornito come argomento: uso la property 'stazione.id'");
            LOG.warn("Utilizzo consigliato: java -jar stazione-app.jar <STAZIONE_ID>");
        } else {
            String stazioneId = args[0].trim();
            STAZIONE_ID_PARAM = stazioneId;
            System.setProperty("stazione.id", stazioneId);
            LOG.infof("🚉 Avvio processo Stazione con ID: %s", stazioneId);
        }

        Quarkus.run(main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        // Se l'ID è arrivato da riga di comando sovrascrive quello della property,
        // altrimenti resta il valore già iniettato in DBLocale dalla configurazione
        if (STAZIONE_ID_PARAM != null) {
            dbLocale.stazioneId = STAZIONE_ID_PARAM;
        }
        LOG.info("✅ Processo Stazione AVVIATO CORRETTAMENTE");
        LOG.info("🔄 Ingresso nel ciclo infinito di mantenimento del nodo Stazione...");
        LOG.info("🌐 Server REST disponibile su http://localhost:8081/stazione/info");

        while (true) {
            Thread.sleep(1000);
        }
    }
}
