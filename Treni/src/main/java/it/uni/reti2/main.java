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

    @Inject
    TrainDatabaseValidator trainDatabaseValidator;

    public static void main(String[] args) {
        // Validazione dei parametri di input: se l'ID non arriva dagli args
        // NON usciamo (in dev mode gli args non vengono passati) ma facciamo
        // fallback sulla property di configurazione treno.id
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty() || "null".equalsIgnoreCase(args[0].trim())) {
            System.err.println("⚠️ ATTENZIONE: ID del treno non fornito negli args, uso la property 'treno.id'");
            System.err.println("Utilizzo consigliato: java -jar treno-app.jar <TRENO_ID>");
            LOG.info("🚂 Avvio processo Treno con ID dalla configurazione (treno.id)");
        } else {
            // Estrae l'ID del treno dal primo parametro
            String trenoId = args[0].trim();
            TRENO_ID_PARAM = trenoId;

            // Imposta la proprietà di sistema per Quarkus
            // Questa proprietà verrà utilizzata dalla classe TrainDB per impostare trenoId
            System.setProperty("treno.id", trenoId);

            LOG.infof("🚂 Avvio processo Treno con ID: %s", trenoId);
        }

        // Avvia l'applicazione Quarkus passando il controllo a questa classe
        Quarkus.run(main.class, args);
    }

    /**
     * Metodo eseguito quando Quarkus è completamente avviato.
     * Mantiene il processo in esecuzione con un ciclo infinito.
     * @return Codice di uscita (0 = successo, 1 = errore)
     */
    @Override
    public int run(String... args) throws Exception {
        // Se l'ID è arrivato dagli args lo forziamo, altrimenti resta quello
        // iniettato dalla configurazione (property treno.id)
        if (TRENO_ID_PARAM != null) {
            trainDB.trenoId = TRENO_ID_PARAM;
        }

        if (trainDB.trenoId == null || trainDB.trenoId.trim().isEmpty()) {
            LOG.error("ID del treno non impostato: impossibile avviare il processo.");
            return 1;
        }

        LOG.infof("🔎 Verifica ID treno '%s' nel database centrale...", trainDB.trenoId);
        while (true) {
            TrainDatabaseValidator.EsitoVerifica esito = trainDatabaseValidator.verificaSeNecessario();
            switch (esito) {
                case VALIDATO:
                    break;
                case ID_NON_PRESENTE:
                    LOG.error("❌ ERRORE FATALE: ID del treno non presente nel database centrale. " +
                            "Il processo verrà terminato.");
                    return 1;
                case RIPROVA:
                    LOG.warn("ID treno non ancora valido o il database centrale non è raggiungibile; riprovo tra 5 secondi...");
                    Thread.sleep(5000);
                    continue;
            }
            break;
        }

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
