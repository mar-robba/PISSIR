package it.uni.reti2.gateway;

import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.json.JavalinGson;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Il server HTTP della Centrale, fatto con Javalin: lo accende sulle porte giuste, in chiaro
 * e (sotto profilo tls) anche in HTTPS, e collega ogni indirizzo al metodo che lo gestisce.
 *
 * <p>Che cosa rispondono gli endpoint lo decidono RestApiGateway e AuthController; qui c'è
 * solo il meccanismo:</p>
 * <ul>
 *   <li>il JSON, con lo stesso Gson della fotografia periodica ({@link Json#GSON});</li>
 *   <li>il CORS aperto a qualsiasi origine, come prima: la web app gira su un'altra porta
 *       (Vite) e il browser senza queste intestazioni bloccherebbe le sue chiamate;</li>
 *   <li>il filtro di FiltroAutorizzazione davanti a ogni richiesta sotto /api;</li>
 *   <li>la WebSocket /ws/realtime, che non passa dal filtro (come prima);</li>
 *   <li>il certificato: sotto profilo tls il plugin SSL di Javalin aggiunge l'HTTPS sulla
 *       8444 (server-centrale.crt, firmato dalla CA del progetto) e l'HTTP in chiaro resta
 *       sulla 8781, come con {@code quarkus.http.insecure-requests=enabled}. L'HTTPS parla
 *       solo HTTP/1.1: con l'HTTP/2 la WebSocket non arrivava al browser (vedi sotto).</li>
 * </ul>
 */
public class ServerRest {

    private static final Logger LOG = Logger.getLogger(ServerRest.class.getName());

    /** Porta HTTP in chiaro (8781). */
    public int porta;

    /** true sotto profilo tls: si aggiunge l'HTTPS sulla porta sicura. */
    public boolean tls;

    /** Porta HTTPS (8444), usata solo con tls. */
    public int portaSicura;

    /** Certificato e chiave privata della Centrale in formato PEM (servono solo con tls). */
    public String certificatoPath;
    public String chiavePath;

    /** Chi risponde alle richieste. */
    public RestApiGateway api;
    public AuthController auth;
    public FiltroAutorizzazione filtro;
    public RealtimeWebSocket webSocket;

    private Javalin app;

    /** Accende il server e registra le rotte. */
    public void avvia() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new JavalinGson(Json.GSON, false));
            config.bundledPlugins.enableCors(cors -> cors.addRule(regola -> regola.anyHost()));
            if (tls) {
                config.registerPlugin(new SslPlugin(ssl -> {
                    ssl.pemFromPath(certificatoPath, chiavePath);
                    ssl.insecurePort = porta;      // l'HTTP in chiaro resta dov'era
                    ssl.securePort = portaSicura;  // e l'HTTPS si aggiunge accanto
                    ssl.sniHostCheck = false;      // si entra anche come 127.0.0.1, non solo localhost
                    // Solo HTTP/1.1 sulla porta sicura. Il plugin accende da solo anche
                    // l'HTTP/2, e con quello acceso Firefox (e Zen) prova a far passare la
                    // WebSocket dentro la connessione HTTP/2: Jetty 11 non la accetta, la
                    // wss://.../ws/realtime si chiude subito (codice 1006) e la mappa resta
                    // ferma ai dati caricati all'apertura della pagina. Le REST andavano lo
                    // stesso, quindi dall'esterno sembrava tutto a posto.
                    ssl.http2 = false;
                }));
            }
        });

        // Prima di ogni endpoint sotto /api: token e ruoli
        app.before("/api/*", filtro::filtra);

        app.get("/api/auth/me", auth::profilo);
        app.get("/api/dashboard", api::getDashboard);

        app.get("/api/stazioni", api::getStazioni);
        app.post("/api/stazioni", api::createStazione);
        app.put("/api/stazioni/{id}", api::updateStazione);
        app.delete("/api/stazioni/{id}", api::deleteStazione);
        app.get("/api/stazioni/{id}/treni-attesi", api::getTreniAttesi);
        app.post("/api/stazioni/{id}/manutenzione", api::dispacciaManutenzione);

        app.get("/api/treni", api::getTreni);
        app.post("/api/treni", api::createTreno);
        app.put("/api/treni/{id}", api::updateTreno);
        app.delete("/api/treni/{id}", api::deleteTreno);
        app.post("/api/treni/{id}/sopprimi", api::sopprimiTreno);
        app.get("/api/treni/{id}/itinerario", api::getItinerarioTreno);
        app.get("/api/prossima-stazione", api::getProssimaStazione);

        app.get("/api/tratte-elementari", api::getTratteElementari);
        app.post("/api/tratte-elementari", api::createTrattaElemento);
        app.put("/api/tratte-elementari/{id}", api::updateTrattaElemento);
        app.delete("/api/tratte-elementari/{id}", api::deleteTrattaElemento);

        app.get("/api/tratte", api::getTratte);
        app.post("/api/tratte", api::createTratta);
        app.put("/api/tratte/{id}", api::updateTratta);
        app.delete("/api/tratte/{id}", api::deleteTratta);

        app.get("/api/transiti", api::getTransiti);
        app.get("/api/allarmi", api::getAllarmi);
        app.post("/api/allarmi/{id}/risolvi", api::risolviAllarme);

        app.ws("/ws/realtime", ws -> {
            ws.onConnect(webSocket::onOpen);
            ws.onClose(webSocket::onClose);
            ws.onError(webSocket::onError);
        });

        // Un corpo che non è JSON è un errore di chi chiama (400), non della Centrale (500)
        app.exception(JsonParseException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("errore", "Il corpo della richiesta non e' un JSON valido")));

        if (tls) {
            app.start(); // le porte le ha già date il plugin SSL
        } else {
            app.start(porta);
        }
        LOG.info(String.format("🌐 Server REST disponibile su http://localhost:%d/api", porta));
        if (tls) {
            LOG.info(String.format("🔒 e in HTTPS su https://localhost:%d/api", portaSicura));
        }
    }

    /** @return La porta su cui ascolta davvero (serve ai test, che chiedono una porta libera con 0). */
    public int portaEffettiva() {
        return app.port();
    }

    /** Spegne il server (lo usano i test). */
    public void ferma() {
        if (app != null) {
            app.stop();
        }
    }
}
