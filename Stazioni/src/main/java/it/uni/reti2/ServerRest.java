package it.uni.reti2;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.json.JavalinGson;

import java.util.logging.Logger;

/**
 * Il server REST della stazione, fatto con Javalin: lo accende sulla porta giusta, in chiaro
 * o in HTTPS, e collega ogni indirizzo /stazione/... al metodo di StationIngestion che lo
 * gestisce.
 *
 * <p>Che cosa rispondono gli endpoint lo decide StationIngestion; qui c'è solo il
 * meccanismo: il JSON (Gson), il certificato, la tabella delle rotte.</p>
 *
 * <p>Sotto profilo tls il server risponde solo in HTTPS, sulla stessa porta: il certificato
 * (server-stazione.crt) e la sua chiave li genera BrokerMosquitto/tls/gen-certs.sh e li firma
 * la CA del progetto, come quelli del broker e della Centrale. A caricarli è il plugin SSL
 * di Javalin, che legge direttamente i file PEM. È la stessa classe del nodo Treno.</p>
 */
public class ServerRest {

    private static final Logger LOG = Logger.getLogger(ServerRest.class.getName());

    /**
     * Porta su cui ascoltare (8081 di default, 8080+ quando le stazioni le lancia
     * avvioStazioni.sh). Con 0 Javalin ne sceglie una libera: lo fanno i test.
     */
    int porta;

    /** true sotto profilo tls: solo HTTPS, niente HTTP in chiaro. */
    boolean tls;

    /** Certificato e chiave privata della stazione in formato PEM (servono solo con tls). */
    String certificatoPath;
    String chiavePath;

    /** Chi risponde alle richieste. */
    StationIngestion ingestion;

    private Javalin app;

    /** Accende il server e registra le rotte. */
    public void avvia() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // disableHtmlEscaping: senza, Gson scriverebbe l'apostrofo come \u0027 (JSON valido
            // lo stesso, ma illeggibile con curl). serializeNulls: GET /stazione/buffer mostra
            // anche idGuasto e idTreno quando sono vuoti, invece di far sparire il campo.
            config.jsonMapper(new JavalinGson(new GsonBuilder().disableHtmlEscaping().serializeNulls().create(), false));
            if (tls) {
                config.registerPlugin(new SslPlugin(ssl -> {
                    ssl.pemFromPath(certificatoPath, chiavePath);
                    ssl.insecure = false;      // niente HTTP in chiaro
                    ssl.securePort = porta;    // l'HTTPS prende la porta della stazione
                }));
            }
        });

        // sola lettura: stato del nodo, coda, sensori, treni sui binari, persistenza
        app.get("/stazione/info", ingestion::info);
        app.get("/stazione/stato", ingestion::getStato);
        app.get("/stazione/buffer", ingestion::getBuffer);
        app.get("/stazione/sensori", ingestion::getSensori);
        app.get("/stazione/treni", ingestion::getTreniPresenti);
        app.get("/stazione/persistenza", ingestion::getPersistenza);
        // i sensori di binario: transiti, guasti, keepalive
        app.post("/stazione/sensore/treno", ingestion::rilevaTransitoTreno);
        app.post("/stazione/sensore/guasto", ingestion::rilevaGuasto);
        app.post("/stazione/sensore/heartbeat", ingestion::heartbeatSensore);
        // le leve delle prove: i sensori simulati e il collegamento con la Centrale
        app.post("/stazione/sensori/{sensoreId}/stop", ingestion::fermaSensore);
        app.post("/stazione/sensori/{sensoreId}/start", ingestion::riavviaSensore);
        app.post("/stazione/rete/offline", ingestion::reteOffline);
        app.post("/stazione/rete/online", ingestion::reteOnline);

        // Un corpo che non è JSON è un errore di chi chiama (400), non della stazione (500)
        app.exception(JsonParseException.class, (e, ctx) ->
                ctx.status(400).result("Il corpo della richiesta non e' un oggetto JSON valido"));

        if (tls) {
            app.start(); // la porta l'ha già data il plugin SSL
        } else {
            app.start(porta);
        }
        LOG.info(String.format("🌐 Server REST disponibile su %s://localhost:%d/stazione/info",
                tls ? "https" : "http", porta()));
    }

    /** @return La porta su cui il server ascolta davvero (con porta 0 è quella scelta da Javalin). */
    public int porta() {
        return app.port();
    }

    /** Spegne il server: lo chiamano lo shutdown hook del main e i test. */
    public void ferma() {
        if (app != null) {
            app.stop();
        }
    }
}
