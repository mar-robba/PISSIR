package it.uni.reti2;

import it.uni.reti2.DbValidator.ExistIdForEdge;
import it.uni.reti2.DbValidator.ExistIdForEdgeStazione;
import it.uni.reti2.elaboration.AutomaGuasto;
import it.uni.reti2.elaboration.AutomaStazione;
import it.uni.reti2.elaboration.AutomaTreno;
import it.uni.reti2.elaboration.GeneratoreEventiTempo;
import it.uni.reti2.elaboration.GestoreEventi;
import it.uni.reti2.elaboration.NavigazioneItinerario;
import it.uni.reti2.elaboration.PrevisioneArrivi;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.gateway.AuthController;
import it.uni.reti2.gateway.FiltroAutorizzazione;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.gateway.RealtimeWebSocket;
import it.uni.reti2.gateway.RestApiGateway;
import it.uni.reti2.gateway.ServerRest;
import it.uni.reti2.gateway.SicurezzaKeycloak;
import it.uni.reti2.ingestion.ClientMqtt;
import it.uni.reti2.ingestion.CodaPubblicazioni;
import it.uni.reti2.ingestion.IngestionService;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.persistence.Database;
import it.uni.reti2.persistence.RailwayRepository;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto di ingresso del servizio Centrale Operativa.
 *
 * <p>Crea gli oggetti e li collega a mano, assegnando i campi, come fa il main del treno.
 * L'ordine conta, ed è quello dei commenti qui
 * sotto: database, stato, automi e gestore, generatori; poi si accende il server e si apre
 * MQTT, perché il primo messaggio che arriva deve trovare la cache già piena.</p>
 */
public class main {

    private static final Logger LOG = Logger.getLogger(main.class.getName());

    public static void main(String[] args) {
        LOG.info("🚦 Avvio processo Centrale Operativa...");
        Configurazione conf = Configurazione.carica();
        if (conf.profilo() != null) {
            LOG.info("⚙️ Profilo attivo: " + conf.profilo());
        }

        // ---- database
        Database db = new Database();
        db.url = conf.get("db.url", "jdbc:postgresql://localhost:5432/railway");
        db.utente = conf.get("db.utente", "postgres");
        db.password = conf.get("db.password", "postgres");
        db.creaTabelleMancanti();
        RailwayRepository repository = new RailwayRepository();
        repository.db = db;

        // ---- lo stato della rete e i calcoli che ci lavorano sopra
        TrafficLogicEngine statoRete = new TrafficLogicEngine();
        statoRete.repository = repository;
        statoRete.freschezzaTelemetriaSecondi = conf.getLong("posizione.telemetria.freschezza.secondi", 15);

        NavigazioneItinerario navigazione = new NavigazioneItinerario();
        navigazione.repository = repository;

        PrevisioneArrivi previsioni = new PrevisioneArrivi();
        previsioni.repository = repository;
        previsioni.statoRete = statoRete;
        previsioni.fattoreAccelerazione = conf.getDouble("viaggio.fattore.accelerazione", 100);
        previsioni.sostaSecondi = conf.getLong("viaggio.sosta.secondi", 2);

        // ---- gli automi
        AutomaTreno treni = new AutomaTreno();
        treni.statoRete = statoRete;
        treni.previsioni = previsioni;
        AutomaStazione stazioni = new AutomaStazione();
        stazioni.statoRete = statoRete;
        AutomaGuasto guasti = new AutomaGuasto();
        guasti.statoRete = statoRete;

        // ---- le azioni: MQTT verso il campo e WebSocket verso la web app
        ClientMqtt clientMqtt = new ClientMqtt();
        clientMqtt.host = conf.get("mqtt.host", "localhost");
        clientMqtt.port = conf.getInt("mqtt.port", 1883);
        clientMqtt.tls = conf.getBoolean("mqtt.tls", false);
        clientMqtt.caPath = conf.get("mqtt.ca", "../BrokerMosquitto/tls/certs/ca.crt");
        clientMqtt.clientId = conf.get("mqtt.client-id", "centrale-operativa");
        CodaPubblicazioni coda = new CodaPubblicazioni();
        coda.clientMqtt = clientMqtt;
        PubblicatoreAlert pubblicatore = new PubblicatoreAlert();
        pubblicatore.coda = coda;

        RealtimeWebSocket webSocket = new RealtimeWebSocket();
        NotificatoreRealtime notificatore = new NotificatoreRealtime();
        notificatore.webSocket = webSocket;

        // ---- il gestore degli eventi
        GestoreEventi gestore = new GestoreEventi();
        gestore.statoRete = statoRete;
        gestore.repository = repository;
        gestore.db = db;
        gestore.navigazione = navigazione;
        gestore.treni = treni;
        gestore.stazioni = stazioni;
        gestore.guasti = guasti;
        gestore.notificatore = notificatore;
        gestore.pubblicatore = pubblicatore;
        gestore.heartbeatTimeoutSecondi = conf.getLong("fault.heartbeat.timeout.secondi", 30);
        gestore.trenoFermoTimeoutSecondi = conf.getLong("fault.treno.fermo.timeout.secondi", 90);

        // ---- i generatori degli eventi: MQTT, tempo, REST
        IngestionService ingestion = new IngestionService();
        ingestion.gestore = gestore;
        GeneratoreEventiTempo tempo = new GeneratoreEventiTempo();
        tempo.gestore = gestore;

        RestApiGateway api = new RestApiGateway();
        api.statoRete = statoRete;
        api.repository = repository;
        api.db = db;
        api.previsioni = previsioni;
        api.pubblicatore = pubblicatore;
        api.gestore = gestore;
        api.navigazione = navigazione;

        // ---- i validatori degli id (domanda e risposta su MQTT, fuori dal gestore)
        ExistIdForEdge validatoreTreni = new ExistIdForEdge();
        validatoreTreni.repository = repository;
        validatoreTreni.coda = coda;
        ExistIdForEdgeStazione validatoreStazioni = new ExistIdForEdgeStazione();
        validatoreStazioni.repository = repository;
        validatoreStazioni.coda = coda;

        // ---- sicurezza e server HTTP
        SicurezzaKeycloak sicurezza = new SicurezzaKeycloak();
        sicurezza.indirizzoRealm = conf.get("keycloak.url", "https://localhost:8543/realms/railway");
        sicurezza.caPath = conf.get("keycloak.ca", "../BrokerMosquitto/tls/certs/ca.crt");
        sicurezza.audience = conf.get("keycloak.audience", "railway-centrale");
        FiltroAutorizzazione filtro = new FiltroAutorizzazione();
        filtro.sicurezza = sicurezza;
        AuthController auth = new AuthController();
        auth.repository = repository;

        ServerRest server = new ServerRest();
        server.porta = conf.getInt("http.porta", 8781);
        server.tls = "tls".equals(conf.profilo());
        server.portaSicura = conf.getInt("https.porta", 8444);
        server.certificatoPath = conf.get("https.certificato", "../BrokerMosquitto/tls/certs/server-centrale.crt");
        server.chiavePath = conf.get("https.chiave", "../BrokerMosquitto/tls/certs/server-centrale.key");
        server.api = api;
        server.auth = auth;
        server.filtro = filtro;
        server.webSocket = webSocket;

        // ---- avvio: prima la cache, poi tutto quello che produce eventi
        statoRete.carica();
        coda.avvia();
        sicurezza.avvia();
        server.avvia();
        tempo.avvia();

        // Il collegamento al broker su un thread a parte: connetti() riprova finché il broker
        // non risponde, e intanto le API REST devono funzionare lo stesso (come prima).
        // I destinatari si registrano prima di collegarsi: con la sessione persistente gli
        // alert arrivati mentre la Centrale era spenta arrivano appena ci si collega.
        ingestion.sottoscrivi(clientMqtt);
        validatoreTreni.sottoscrivi(clientMqtt);
        validatoreStazioni.sottoscrivi(clientMqtt);
        Thread collegamento = new Thread(() -> {
            try {
                clientMqtt.connetti();
                LOG.info("📡 In ascolto su telemetria, heartbeat, transiti, alert e validazioni");
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "❌ Collegamento MQTT non riuscito: la Centrale non riceve niente dal campo", e);
            }
        }, "collegamento-mqtt");
        collegamento.setDaemon(true);
        collegamento.start();

        LOG.info("✅ Centrale Operativa AVVIATA CORRETTAMENTE");
        // Il processo resta in piedi finché è acceso il server (i thread di Jetty non sono
        // demoni); Ctrl-C o SIGTERM lo spengono.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // System.out e non LOG: java.util.logging chiude i suoi handler in un suo
            // shutdown hook, che puo' girare prima di questo, e il messaggio si perderebbe
            System.out.println("🛑 Spegnimento della Centrale Operativa richiesto");
            tempo.ferma();
            server.ferma();
        }, "spegnimento"));
    }
}
