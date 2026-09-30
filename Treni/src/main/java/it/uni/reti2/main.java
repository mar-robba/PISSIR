package it.uni.reti2;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto di ingresso del processo Treno.
 * Questo file contiene l'avvio del processo treno e simula un treno reale.
 * L'ID del treno è fornito come parametro di input agli args della riga di comando.
 *
 * <p>Qui si fa quello che prima faceva il framework: si creano gli oggetti, si assegnano i
 * campi con cui si parlano (gli stessi che i test assegnano a mano) e si avviano, in
 * quest'ordine, il server REST, la connessione MQTT, la verifica dell'ID presso la
 * Centrale e l'orologio che fa girare il viaggio e la telemetria.</p>
 *
 * <p>Uso: {@code java -jar target/treno.jar <TRENO_ID>}, con -Dhttp.port per la porta e
 * -Dprofilo=tls per cifrare MQTT, la GET verso la Centrale e le API REST del treno.</p>
 */
public class main {

    private static final Logger LOG = Logger.getLogger(main.class.getName());

    public static void main(String[] args) throws Exception {
        Configurazione conf = Configurazione.carica();
        LogSuFile.preparaTerminale(conf.getBoolean("log.debug", false));

        // Validazione dei parametri di input: se l'ID non arriva dagli args
        // NON usciamo ma facciamo fallback sulla property di configurazione treno.id
        String trenoId;
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty() || "null".equalsIgnoreCase(args[0].trim())) {
            System.err.println("⚠️ ATTENZIONE: ID del treno non fornito negli args, uso la property 'treno.id'");
            System.err.println("Utilizzo consigliato: java -jar target/treno.jar <TRENO_ID>");
            trenoId = conf.get("treno.id", "TRN001");
            LOG.info("🚂 Avvio processo Treno con ID dalla configurazione (treno.id): " + trenoId);
        } else {
            // Estrae l'ID del treno dal primo parametro
            trenoId = args[0].trim();
            LOG.info(String.format("🚂 Avvio processo Treno con ID: %s", trenoId));
        }

        boolean tls = "tls".equals(conf.profilo());
        int porta = conf.getInt("http.port", 8082);
        String caPath = conf.get("tls.ca.path", "../BrokerMosquitto/tls/certs/ca.crt");

        // ------------------------------------------------------------ creazione degli oggetti
        TrainDB trainDB = new TrainDB();
        trainDB.trenoId = trenoId;

        SecureHttpClient secureHttpClient = new SecureHttpClient();
        secureHttpClient.caPath = caPath;
        secureHttpClient.init();

        ClientMqtt clientMqtt = new ClientMqtt();
        clientMqtt.host = conf.get("mqtt.host", "localhost");
        clientMqtt.port = conf.getInt("mqtt.port", 1883);
        clientMqtt.tls = tls;
        clientMqtt.caPath = caPath;
        clientMqtt.clientId = "treno-" + trenoId;

        CodaPubblicazioni coda = new CodaPubblicazioni();
        coda.clientMqtt = clientMqtt;

        // gli automi
        AutomaViaggio viaggio = new AutomaViaggio();
        viaggio.trainDB = trainDB;
        viaggio.sostaSecondi = conf.getLong("viaggio.sosta.secondi", 20);
        viaggio.fattoreAccelerazione = conf.getDouble("viaggio.fattore.accelerazione", 10);
        viaggio.autostart = conf.getBoolean("viaggio.autostart", true);
        AutomaCondizione condizione = new AutomaCondizione();
        condizione.trainDB = trainDB;
        AutomaStazioniNote stazioni = new AutomaStazioniNote();
        stazioni.trainDB = trainDB;

        // chi pubblica
        PubblicatorePassaggi pubblicatorePassaggi = new PubblicatorePassaggi();
        pubblicatorePassaggi.trainDB = trainDB;
        pubblicatorePassaggi.coda = coda;
        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.trainDB = trainDB;
        pubblicatoreAlert.coda = coda;
        TrainElab telemetria = new TrainElab();
        telemetria.trainDB = trainDB;
        telemetria.coda = coda;

        // il gestore degli eventi, l'unico che muove gli automi
        GestoreEventi gestore = new GestoreEventi();
        gestore.trainDB = trainDB;
        gestore.viaggio = viaggio;
        gestore.condizione = condizione;
        gestore.stazioni = stazioni;
        gestore.pubblicatorePassaggi = pubblicatorePassaggi;
        gestore.pubblicatoreAlert = pubblicatoreAlert;

        // i generatori degli eventi
        CaricatoreItinerario caricatoreItinerario = new CaricatoreItinerario();
        caricatoreItinerario.trainDB = trainDB;
        caricatoreItinerario.secureHttpClient = secureHttpClient;
        caricatoreItinerario.centraleUrl = conf.get("centrale.url", "http://localhost:8781");
        GeneratoreEventiTempo generatoreTempo = new GeneratoreEventiTempo();
        generatoreTempo.trainDB = trainDB;
        generatoreTempo.caricatoreItinerario = caricatoreItinerario;
        generatoreTempo.gestore = gestore;
        TrainGateway gateway = new TrainGateway();
        gateway.trainDB = trainDB;
        gateway.gestore = gestore;
        gateway.generatoreTempo = generatoreTempo;
        TrainIngestion ingestion = new TrainIngestion();
        ingestion.trainDB = trainDB;
        ingestion.gestore = gestore;
        ingestion.pubblicatorePassaggi = pubblicatorePassaggi;

        TrainDatabaseValidator trainDatabaseValidator = new TrainDatabaseValidator();
        trainDatabaseValidator.trainDB = trainDB;
        trainDatabaseValidator.coda = coda;

        // ------------------------------------------------------------ avvio
        // 1. le API REST (i sensori di bordo)
        ServerRest serverRest = new ServerRest();
        serverRest.porta = porta;
        serverRest.tls = tls;
        serverRest.certificatoPath = conf.get("http.tls.cert.path", "../BrokerMosquitto/tls/certs/server-treno.crt");
        serverRest.chiavePath = conf.get("http.tls.key.path", "../BrokerMosquitto/tls/certs/server-treno.key");
        serverRest.ingestion = ingestion;
        serverRest.avvia();

        // 2. il broker: prima si registrano i destinatari, poi ci si collega, poi parte il
        //    thread che pubblica. I destinatari vanno prima perché con la sessione persistente
        //    gli alert rimasti in coda arrivano appena ci si collega (vedi ClientMqtt).
        //    Gli alert a QoS 1: sono quelli che non si possono perdere.
        clientMqtt.ascolta("railway/alerts", 1, gateway::riceviAlert);
        clientMqtt.ascolta("railway/train/+/validation-response", 0,
                trainDatabaseValidator::gestisciRispostaValidazione);
        clientMqtt.connetti();
        coda.avvia();
        LOG.info("🔴 Ascolto alert su topic MQTT: railway/alerts");

        // 3. la verifica dell'ID nel database centrale
        LOG.info(String.format("🔎 Verifica ID treno '%s' nel database centrale...", trainDB.trenoId));
        while (true) {
            TrainDatabaseValidator.EsitoVerifica esito = trainDatabaseValidator.verificaSeNecessario();
            switch (esito) {
                case VALIDATO:
                    break;
                case ID_NON_PRESENTE:
                    LOG.severe("❌ ERRORE FATALE: ID del treno non presente nel database centrale. " +
                            "Il processo verrà terminato.");
                    System.exit(1);
                    return;
                case RIPROVA:
                    LOG.warning("ID treno non ancora valido o il database centrale non è raggiungibile; riprovo tra 5 secondi...");
                    Thread.sleep(5000);
                    continue;
            }
            break;
        }

        // 4. l'orologio: il TICK del viaggio e la telemetria, una volta al secondo
        Orologio orologio = new Orologio();
        orologio.ogniSecondo("tick del viaggio", generatoreTempo::tick);
        orologio.ogniSecondo("telemetria", telemetria::inviaTelemetria);
        LOG.info("🧭 [TWIN] Generatore degli eventi del tempo avviato: un TICK al secondo");
        LOG.info("📡 Trasmissione telemetria su topic MQTT: railway/train/" + trainDB.trenoId + "/telemetry");

        LOG.info("✅ Processo Treno AVVIATO CORRETTAMENTE");

        // Console dei sensori di bordo (guasto / passaggio in stazione): gira su un thread
        // separato. La si avvia solo se esiste davvero un terminale interattivo, altrimenti
        // (container, redirezione dell'output) lo Scanner leggerebbe a vuoto consumando CPU
        // inutilmente.
        if (System.console() != null) {
            Sensori sensori = new Sensori();
            sensori.trenoId = trainDB.trenoId;
            sensori.httpPort = porta;
            sensori.https = tls;
            sensori.httpClient = secureHttpClient.get();
            Thread consoleSensori = new Thread(sensori::avviaMenu, "console-sensori-treno");
            consoleSensori.setDaemon(true); // non deve impedire la chiusura del processo
            consoleSensori.start();
            LOG.info("🎛️ Console dei sensori di bordo avviata su questo terminale");
        } else {
            LOG.info("🎛️ Nessun terminale interattivo: sensori attivabili via POST /treno/sensore/*");
        }

        // Il main finisce qui, ma il processo resta vivo: il server REST (Jetty) e l'orologio
        // hanno i loro thread, che non sono daemon, e la JVM non si chiude finché ci sono.
    }
}
