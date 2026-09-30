package it.uni.reti2;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import it.uni.reti2.DbValidator.ExistIdForEdge;
import it.uni.reti2.DbValidator.ExistIdForEdgeStazione;
import it.uni.reti2.elaboration.AutomaGuasto;
import it.uni.reti2.elaboration.AutomaStazione;
import it.uni.reti2.elaboration.AutomaTreno;
import it.uni.reti2.elaboration.EventoCentrale;
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
import it.uni.reti2.ingestion.CodaPubblicazioni;
import it.uni.reti2.ingestion.IngestionService;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.persistence.Database;
import it.uni.reti2.persistence.RailwayRepository;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Una Centrale vera, accesa per i test: gli stessi oggetti collegati come nel main, con tre
 * differenze che servono a non toccare niente fuori dal test.
 * <ul>
 *   <li>il database è un H2 in memoria (modalità PostgreSQL), nuovo per ogni Centrale e
 *       creato dallo stesso schema.sql della Centrale vera;</li>
 *   <li>niente broker: la coda delle pubblicazioni e la WebSocket sono sostituite da due
 *       versioni che si limitano a registrare quello che uscirebbe ({@link #pubblicati},
 *       {@link #eventiWeb}); i messaggi del campo si consegnano direttamente a
 *       IngestionService, come farebbe il client MQTT;</li>
 *   <li>niente Keycloak: le chiavi del realm sono una coppia RSA generata qui, e
 *       {@link #token} firma i token con quella. pac4j li verifica come quelli veri.</li>
 * </ul>
 * <p>Il server Javalin è vero e ascolta su una porta libera: le richieste REST passano da
 * HTTP, dal filtro e dal convertitore JSON come in esercizio. Il watchdog non parte da solo:
 * i test fanno scattare i suoi eventi quando vogliono, con {@link #tick}.</p>
 */
public class CentraleDiProva implements AutoCloseable {

    /** L'emittente dei token di prova (il realm finto). */
    public static final String EMITTENTE = "https://keycloak.di.prova/realms/railway";

    public final Database db = new Database();
    public final RailwayRepository repository = new RailwayRepository();
    public final TrafficLogicEngine statoRete = new TrafficLogicEngine();
    public final NavigazioneItinerario navigazione = new NavigazioneItinerario();
    public final PrevisioneArrivi previsioni = new PrevisioneArrivi();
    public final AutomaTreno treni = new AutomaTreno();
    public final AutomaStazione stazioni = new AutomaStazione();
    public final AutomaGuasto guasti = new AutomaGuasto();
    public final GestoreEventi gestore = new GestoreEventi();
    public final IngestionService ingestion = new IngestionService();
    public final PubblicatoreAlert pubblicatore = new PubblicatoreAlert();
    public final NotificatoreRealtime notificatore = new NotificatoreRealtime();
    public final RestApiGateway api = new RestApiGateway();
    public final ExistIdForEdge validatoreTreni = new ExistIdForEdge();
    public final ExistIdForEdgeStazione validatoreStazioni = new ExistIdForEdgeStazione();
    public final SicurezzaKeycloak sicurezza = new SicurezzaKeycloak();
    public final ServerRest server = new ServerRest();

    /** I messaggi che la Centrale avrebbe pubblicato sul broker: {topic, payload}. */
    public final List<String[]> pubblicati = new CopyOnWriteArrayList<>();

    /** Gli eventi che la Centrale avrebbe mandato alla web app sulla WebSocket. */
    public final List<String> eventiWeb = new CopyOnWriteArrayList<>();

    private final String urlDatabase = "jdbc:h2:mem:centrale-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    private final HttpClient http = HttpClient.newHttpClient();

    private RSAKey chiave;

    /** Una risposta HTTP: il codice, il corpo e le intestazioni. */
    public record Risposta(int stato, String corpo, HttpHeaders intestazioni) {
        /** @return Il corpo letto come JSON. */
        public JsonElement json() {
            return JsonParser.parseString(corpo);
        }
    }

    /** Accende una Centrale con i tempi del watchdog di default (30 e 90 secondi). */
    public static CentraleDiProva avvia() throws Exception {
        CentraleDiProva centrale = new CentraleDiProva();
        centrale.collega();
        return centrale;
    }

    private void collega() throws Exception {
        db.url = urlDatabase;
        db.utente = "sa";
        db.password = "";
        db.creaTabelleMancanti();
        repository.db = db;

        statoRete.repository = repository;
        navigazione.repository = repository;
        previsioni.repository = repository;
        previsioni.statoRete = statoRete;

        treni.statoRete = statoRete;
        treni.previsioni = previsioni;
        stazioni.statoRete = statoRete;
        guasti.statoRete = statoRete;

        pubblicatore.coda = new CodaPubblicazioni() {
            @Override
            public void metti(String topic, String payload, int qos) {
                pubblicati.add(new String[]{topic, payload});
            }
        };
        notificatore.webSocket = new RealtimeWebSocket() {
            @Override
            public void broadcast(String message) {
                eventiWeb.add(message);
            }
        };

        gestore.statoRete = statoRete;
        gestore.repository = repository;
        gestore.db = db;
        gestore.navigazione = navigazione;
        gestore.treni = treni;
        gestore.stazioni = stazioni;
        gestore.guasti = guasti;
        gestore.notificatore = notificatore;
        gestore.pubblicatore = pubblicatore;

        ingestion.gestore = gestore;

        api.statoRete = statoRete;
        api.repository = repository;
        api.db = db;
        api.previsioni = previsioni;
        api.pubblicatore = pubblicatore;
        api.gestore = gestore;
        api.navigazione = navigazione;

        validatoreTreni.repository = repository;
        validatoreTreni.coda = pubblicatore.coda;
        validatoreStazioni.repository = repository;
        validatoreStazioni.coda = pubblicatore.coda;

        chiave = new RSAKeyGenerator(2048).keyID("chiave-di-prova").keyUse(KeyUse.SIGNATURE).generate();
        sicurezza.usaChiavi(new JWKSet(chiave.toPublicJWK()).toString(), EMITTENTE);
        FiltroAutorizzazione filtro = new FiltroAutorizzazione();
        filtro.sicurezza = sicurezza;
        AuthController auth = new AuthController();
        auth.repository = repository;

        server.porta = 0; // una porta libera qualsiasi
        server.api = api;
        server.auth = auth;
        server.filtro = filtro;
        server.webSocket = new RealtimeWebSocket();

        statoRete.carica();
        server.avvia();
    }

    // ==================================================================== token

    /** Un token valido per la Centrale, dell'utente dato e con i ruoli dati. */
    public String token(String utente, String... ruoli) throws Exception {
        return token(utente, EMITTENTE, "railway-centrale", 300, chiave, ruoli);
    }

    /** Un token con emittente, audience, durata (secondi, anche negativa) e chiave scelti. */
    public String token(String utente, String emittente, String audience, long durataSecondi,
                        RSAKey chiaveDiFirma, String... ruoli) throws Exception {
        return firma(claims(utente, emittente, audience, durataSecondi, ruoli), chiaveDiFirma);
    }

    /** I claim di un token come li scriverebbe Keycloak (utente, realm, audience, ruoli di realm). */
    public static JWTClaimsSet claims(String utente, String emittente, String audience, long durataSecondi,
                                      String... ruoli) {
        return new JWTClaimsSet.Builder()
                .subject("id-" + utente)
                .issuer(emittente)
                .audience(List.of(audience, "account"))
                .expirationTime(new Date(System.currentTimeMillis() + durataSecondi * 1000))
                .issueTime(new Date())
                .claim("preferred_username", utente)
                .claim("realm_access", Map.of("roles", List.of(ruoli)))
                .build();
    }

    /** Firma i claim con la chiave data (RS256, come Keycloak). */
    public static String firma(JWTClaimsSet claims, RSAKey chiaveDiFirma) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chiaveDiFirma.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(chiaveDiFirma));
        return jwt.serialize();
    }

    /** La chiave con cui questa Centrale firma i suoi token: e' quella "del realm". */
    public RSAKey chiaveDelRealm() {
        return chiave;
    }

    /** Il token di un amministratore (mat001). */
    public String admin() throws Exception {
        return token("mat001", FiltroAutorizzazione.RUOLO_AMMINISTRATORE);
    }

    /** Il token di un tecnico (mat003). */
    public String tecnico() throws Exception {
        return token("mat003", FiltroAutorizzazione.RUOLO_TECNICO);
    }

    // ==================================================================== REST

    public Risposta get(String percorso, String token) throws Exception {
        return invia("GET", percorso, null, token);
    }

    public Risposta post(String percorso, String corpo, String token) throws Exception {
        return invia("POST", percorso, corpo, token);
    }

    public Risposta put(String percorso, String corpo, String token) throws Exception {
        return invia("PUT", percorso, corpo, token);
    }

    public Risposta delete(String percorso, String token) throws Exception {
        return invia("DELETE", percorso, null, token);
    }

    /**
     * Una richiesta qualsiasi; corpo e token possono essere null.
     *
     * @param intestazioni Altre intestazioni, a coppie nome e valore.
     */
    public Risposta invia(String metodo, String percorso, String corpo, String token, String... intestazioni) throws Exception {
        HttpRequest.Builder richiesta = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.portaEffettiva() + percorso))
                .method(metodo, corpo != null ? HttpRequest.BodyPublishers.ofString(corpo) : HttpRequest.BodyPublishers.noBody());
        if (corpo != null) {
            richiesta.header("Content-Type", "application/json");
        }
        if (token != null) {
            richiesta.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < intestazioni.length; i += 2) {
            richiesta.header(intestazioni[i], intestazioni[i + 1]);
        }
        HttpResponse<String> risposta = http.send(richiesta.build(), HttpResponse.BodyHandlers.ofString());
        return new Risposta(risposta.statusCode(), risposta.body(), risposta.headers());
    }

    /** Un pezzo di percorso con i caratteri speciali codificati (il nome "IC 351" ha uno spazio). */
    public static String codifica(String pezzo) {
        return URLEncoder.encode(pezzo, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ==================================================================== campo e tempo

    public void telemetria(String payload) {
        ingestion.onTelemetry(payload);
    }

    public void heartbeat(String payload) {
        ingestion.onHeartbeat(payload);
    }

    public void transito(String payload) {
        ingestion.onTransit(payload);
    }

    public void alert(String payload) {
        ingestion.onAlert(payload);
    }

    /** Fa scattare uno degli eventi del watchdog. */
    public GestoreEventi.Esito tick(EventoCentrale.Tipo tipo) {
        return gestore.gestisci(EventoCentrale.di(tipo));
    }

    /** I payload pubblicati su railway/alerts, in ordine. */
    public List<String> alertPubblicati() {
        List<String> alert = new ArrayList<>();
        for (String[] messaggio : pubblicati) {
            if (PubblicatoreAlert.TOPIC_ALERTS.equals(messaggio[0])) {
                alert.add(messaggio[1]);
            }
        }
        return alert;
    }

    // ==================================================================== database

    /** Le righe di una SELECT sul database della Centrale, ogni riga un array di colonne. */
    public List<Object[]> righe(String sql, Object... parametri) throws Exception {
        List<Object[]> righe = new ArrayList<>();
        try (Connection connessione = DriverManager.getConnection(urlDatabase, "sa", "");
             PreparedStatement ps = connessione.prepareStatement(sql)) {
            for (int i = 0; i < parametri.length; i++) {
                ps.setObject(i + 1, parametri[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int colonne = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] riga = new Object[colonne];
                    for (int c = 0; c < colonne; c++) {
                        riga[c] = rs.getObject(c + 1);
                    }
                    righe.add(riga);
                }
            }
        }
        return righe;
    }

    /** Il numero che restituisce una SELECT COUNT(*). */
    public long conta(String sql, Object... parametri) throws Exception {
        return ((Number) righe(sql, parametri).get(0)[0]).longValue();
    }

    /** Svuota le registrazioni di MQTT e WebSocket. */
    public void dimenticaUscite() {
        pubblicati.clear();
        eventiWeb.clear();
    }

    @Override
    public void close() throws Exception {
        server.ferma();
        try (Connection connessione = DriverManager.getConnection(urlDatabase, "sa", "");
             Statement statement = connessione.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
