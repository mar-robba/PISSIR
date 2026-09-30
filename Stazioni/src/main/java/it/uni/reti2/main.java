package it.uni.reti2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Punto di ingresso del processo Stazione: un processo per ogni stazione, con l'ID come
 * primo argomento della riga di comando.
 *
 * <p>Qui si fa quello che prima faceva il framework: si creano gli oggetti, si assegnano i
 * campi con cui si parlano (gli stessi che i test assegnano a mano) e si avviano, in
 * quest'ordine, la persistenza locale (che rimette il nodo dov'era), il server REST dei
 * sensori, la connessione MQTT con il consumatore del buffer, l'orologio (controllo dei
 * sensori, sensori simulati, heartbeat) e infine la verifica dell'ID presso la Centrale.</p>
 *
 * <p>Uso: {@code java -jar target/stazione.jar <STAZIONE_ID>}, con -Dhttp.port per la porta
 * e -Dprofilo=tls per cifrare MQTT e le API REST della stazione.</p>
 */
public class main {

    private static final Logger LOG = Logger.getLogger(main.class.getName());

    public static void main(String[] args) throws Exception {
        Configurazione conf = Configurazione.carica();
        LogSuFile.preparaTerminale(conf.getBoolean("log.debug", false));

        // L'ID arriva come primo argomento. Se manca (o e' "null") non si esce: si usa la
        // property stazione.id
        String stazioneId;
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty() || "null".equalsIgnoreCase(args[0].trim())) {
            LOG.warning("⚠️ ID della stazione non fornito come argomento: uso la property 'stazione.id'");
            LOG.warning("Utilizzo consigliato: java -jar target/stazione.jar <STAZIONE_ID>");
            stazioneId = conf.get("stazione.id", "S1");
        } else {
            stazioneId = args[0].trim();
        }
        if (stazioneId == null || stazioneId.isEmpty()) {
            LOG.severe("ID della stazione non impostato: impossibile avviare il processo.");
            System.exit(1);
            return;
        }
        LOG.info(String.format("🚉 Avvio processo Stazione con ID: %s", stazioneId));

        boolean tls = "tls".equals(conf.profilo());
        int porta = conf.getInt("http.port", 8081);
        String caPath = conf.get("tls.ca.path", "../BrokerMosquitto/tls/certs/ca.crt");
        // dove i sensori simulati e la console chiamano le API della stazione
        String indirizzoApi = (tls ? "https" : "http") + "://localhost:" + porta;

        // ------------------------------------------------------------ stato del nodo
        // Prima la persistenza, perché DBLocale e LocalBuffer si ripristinano leggendo da lì
        PersistenzaLocale persistenza = new PersistenzaLocale();
        persistenza.percorsoDb = conf.get("stazione.db.path", "data/stazione-" + stazioneId + ".db");
        persistenza.abilitata = conf.getBoolean("stazione.db.abilitata", true);
        persistenza.apri();

        DBLocale dbLocale = new DBLocale();
        dbLocale.persistenza = persistenza;
        dbLocale.stazioneId = stazioneId;
        dbLocale.ripristinaDaDisco();

        LocalBuffer localBuffer = new LocalBuffer();
        localBuffer.persistenza = persistenza;
        localBuffer.ripristinaDaDisco();

        // ------------------------------------------------------------ gli automi
        AutomaStatoStazione statoStazione = new AutomaStatoStazione();
        statoStazione.dbLocale = dbLocale;
        statoStazione.persistenza = persistenza;
        AutomaSensori sensori = new AutomaSensori();
        sensori.dbLocale = dbLocale;
        sensori.timeoutSecondi = conf.getLong("sensori.timeout.secondi", 30);
        AutomaTreniPresenti treni = new AutomaTreniPresenti();
        treni.dbLocale = dbLocale;
        AutomaConnessione connessione = new AutomaConnessione();
        connessione.dbLocale = dbLocale;

        // Lo stato iniziale dei sensori: quelli che la stazione si aspetta di sentire, con il
        // timeout che parte da adesso.
        int binario = conf.getInt("stazione.binario", 1);
        List<String> sensoriAttesi = new ArrayList<>();
        for (SensoreBinario.Tipo tipo : SensoreBinario.Tipo.values()) {
            sensoriAttesi.add(SensoreBinario.idPer(stazioneId, binario, tipo));
        }
        sensori.sorveglia(sensoriAttesi, Instant.now());
        // Se il nodo si era spento guasto (anche) per dei sensori muti va ricordato, se no il
        // ritorno dei loro battiti non riuscirebbe più a chiudere il loro allarme e, se erano
        // l'unico guasto, a rimettere la stazione operativa. Quali sensori tacevano però non si
        // sa più (stava in RAM), quindi si considerano muti tutti: il primo battito di ognuno lo
        // rimette a posto, e quando battono tutti si fa come al solito.
        statoStazione.ripristinaCausa();
        if (statoStazione.guasta() && statoStazione.guastiDeiSensoriAperti()) {
            sensori.segnaMuti(sensoriAttesi);
            LOG.warning("💾 La stazione riparte GUASTA per un sensore muto: aspetto il ritorno dei keepalive.");
        }
        LOG.info(String.format("👁️ Monitor sensori attivo su %d dispositivi (timeout %d s): %s",
                sensoriAttesi.size(), sensori.timeoutSecondi(), sensoriAttesi));

        // ------------------------------------------------------------ chi prepara i messaggi per la Centrale
        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.dbLocale = dbLocale;
        pubblicatoreAlert.localBuffer = localBuffer;
        pubblicatoreAlert.connessione = connessione;
        PubblicatoreTransiti pubblicatoreTransiti = new PubblicatoreTransiti();
        pubblicatoreTransiti.dbLocale = dbLocale;
        pubblicatoreTransiti.localBuffer = localBuffer;
        pubblicatoreTransiti.connessione = connessione;

        // ------------------------------------------------------------ il gestore degli eventi, l'unico che muove gli automi
        GestoreEventi gestore = new GestoreEventi();
        gestore.dbLocale = dbLocale;
        gestore.statoStazione = statoStazione;
        gestore.sensori = sensori;
        gestore.treni = treni;
        gestore.connessione = connessione;
        gestore.pubblicatoreAlert = pubblicatoreAlert;
        gestore.pubblicatoreTransiti = pubblicatoreTransiti;

        // ------------------------------------------------------------ MQTT e chi pubblica
        ClientMqtt clientMqtt = new ClientMqtt();
        clientMqtt.host = conf.get("mqtt.host", "localhost");
        clientMqtt.port = conf.getInt("mqtt.port", 1883);
        clientMqtt.tls = tls;
        clientMqtt.caPath = caPath;
        clientMqtt.clientId = "stazione-" + stazioneId;

        ConsumatoreBuffer consumatore = new ConsumatoreBuffer();
        consumatore.localBuffer = localBuffer;
        consumatore.connessione = connessione;
        consumatore.clientMqtt = clientMqtt;
        consumatore.stazioneId = stazioneId;

        HeartbeatGenerator heartbeat = new HeartbeatGenerator();
        heartbeat.dbLocale = dbLocale;
        heartbeat.localBuffer = localBuffer;
        heartbeat.connessione = connessione;
        heartbeat.clientMqtt = clientMqtt;

        StationDatabaseValidator validatore = new StationDatabaseValidator();
        validatore.dbLocale = dbLocale;
        validatore.clientMqtt = clientMqtt;

        // ------------------------------------------------------------ i sensori simulati e i generatori degli eventi
        SecureHttpClient secureHttpClient = new SecureHttpClient();
        secureHttpClient.caPath = caPath;
        secureHttpClient.init();

        SimulatoreSensori simulatore = new SimulatoreSensori();
        simulatore.stazioneId = stazioneId;
        simulatore.binario = binario;
        simulatore.indirizzoApi = indirizzoApi;
        simulatore.httpClient = secureHttpClient.get();
        simulatore.creaSensori();

        GeneratoreEventiTempo generatoreTempo = new GeneratoreEventiTempo();
        generatoreTempo.gestore = gestore;
        StationIngestion ingestion = new StationIngestion();
        ingestion.dbLocale = dbLocale;
        ingestion.localBuffer = localBuffer;
        ingestion.persistenza = persistenza;
        ingestion.gestore = gestore;
        ingestion.simulatoreSensori = simulatore;
        StationGateway gateway = new StationGateway();
        gateway.dbLocale = dbLocale;
        gateway.gestore = gestore;
        gateway.simulatoreSensori = simulatore;

        // ------------------------------------------------------------ avvio
        // 1. le API REST dei sensori
        ServerRest serverRest = new ServerRest();
        serverRest.porta = porta;
        serverRest.tls = tls;
        serverRest.certificatoPath = conf.get("http.tls.cert.path", "../BrokerMosquitto/tls/certs/server-stazione.crt");
        serverRest.chiavePath = conf.get("http.tls.key.path", "../BrokerMosquitto/tls/certs/server-stazione.key");
        serverRest.ingestion = ingestion;
        serverRest.avvia();

        // 2. il broker. I destinatari si registrano prima di collegarsi: con la sessione
        //    persistente gli alert rimasti in coda arrivano appena ci si collega (vedi ClientMqtt).
        //    Gli alert a QoS 1, perché sono quelli che non si possono perdere.
        clientMqtt.ascolta("railway/alerts", 1, gateway::riceviAlert);
        clientMqtt.ascolta("railway/station/+/validation-response", 0, validatore::gestisciRispostaValidazione);
        clientMqtt.ascolta("railway/treno-simulatoriSensori/+", 0, gateway::riceviPassaggio);
        clientMqtt.connetti();
        consumatore.avvia();
        LOG.info("🔴 Ascolto alert su topic MQTT: railway/alerts");

        // 3. l'orologio: il controllo dei keepalive, i sensori simulati che battono e
        //    l'heartbeat verso la Centrale (filtrato finché l'ID non è validato)
        Orologio orologio = new Orologio();
        orologio.ogni("controllo dei sensori", conf.getLong("sensori.controllo.secondi", 5), generatoreTempo::controllaSensori);
        orologio.ogni("keepalive dei sensori simulati", conf.getLong("sensori.heartbeat.secondi", 5), simulatore::battito);
        orologio.ogni("heartbeat verso la Centrale", 10, heartbeat::generaHeartbeat);

        // Allo spegnimento (Ctrl+C, kill, oppure l'ID rifiutato qui sotto) si chiude tutto in
        // ordine: il server REST, la connessione al broker e il file SQLite. Le righe di log
        // scritte da qui dentro possono non comparire, perché java.util.logging chiude i suoi
        // handler con un hook suo che gira nello stesso momento: il lavoro però si fa lo stesso.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            serverRest.ferma();
            clientMqtt.disconnetti();
            persistenza.chiudi();
        }, "arresto"));

        // 4. la verifica dell'ID nel database centrale
        LOG.info(String.format("🔎 Verifica ID stazione '%s' nel database centrale...", stazioneId));
        while (true) {
            StationDatabaseValidator.EsitoVerifica esito = validatore.verificaSeNecessario();
            switch (esito) {
                case VALIDATO:
                    break;
                case ID_NON_PRESENTE:
                    LOG.severe("❌ ERRORE FATALE: ID della stazione non presente nel database centrale. " +
                            "Il processo verrà terminato.");
                    System.exit(1);
                    return;
                case RIPROVA:
                    LOG.warning("ID stazione non ancora valido o il database centrale non è raggiungibile; riprovo tra 5 secondi...");
                    Thread.sleep(5000);
                    continue;
            }
            break;
        }

        LOG.info("✅ Processo Stazione AVVIATO CORRETTAMENTE");

        // Console dei sensori di binario (heartbeat, fail-stop, guasti): gira su un thread
        // separato. La si avvia solo se c'è davvero un terminale interattivo: con
        // avvioStazioni.sh l'output finisce su file e lo Scanner leggerebbe a vuoto
        // consumando CPU per niente. Stesso criterio del processo Treno.
        if (System.console() != null) {
            ConsoleSensori consoleSensori = new ConsoleSensori();
            consoleSensori.stazioneId = stazioneId;
            consoleSensori.indirizzoApi = indirizzoApi;
            consoleSensori.httpClient = secureHttpClient.get();
            Thread consoleThread = new Thread(consoleSensori::avviaMenu, "console-sensori-stazione");
            consoleThread.setDaemon(true); // non deve impedire la chiusura del processo
            consoleThread.start();
            LOG.info("🎛️ Console dei sensori di binario avviata su questo terminale");
        } else {
            LOG.info("🎛️ Nessun terminale interattivo: sensori pilotabili via POST /stazione/sensori/{id}/stop|start");
        }

        // Il main finisce qui, ma il processo resta vivo: il server REST (Jetty) e l'orologio
        // hanno i loro thread, che non sono daemon, e la JVM non si chiude finché ci sono.
    }
}
