package it.uni.reti2;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.json.JavalinGson;

import java.util.logging.Logger;

/**
 * Il server REST del treno, fatto con Javalin: lo accende sulla porta giusta, in chiaro o
 * in HTTPS, e collega ogni indirizzo /treno/... al metodo di TrainIngestion che lo gestisce.
 *
 * <p>Che cosa rispondono gli endpoint lo decide TrainIngestion; qui c'è solo il meccanismo:
 * il JSON (Gson), il certificato, la tabella delle rotte.</p>
 *
 * <p>Sotto profilo tls il server risponde solo in HTTPS, sulla stessa porta: il certificato
 * (server-treno.crt) e la sua chiave li genera BrokerMosquitto/tls/gen-certs.sh e li firma
 * la CA del progetto, come quelli del broker e della Centrale. A caricarli è il plugin SSL
 * di Javalin, che legge direttamente i file PEM.</p>
 */
public class ServerRest {

    private static final Logger LOG = Logger.getLogger(ServerRest.class.getName());

    /** Porta su cui ascoltare (8082 di default, 9080+ quando i treni li lancia avvioTreni.sh). */
    int porta;

    /** true sotto profilo tls: solo HTTPS, niente HTTP in chiaro. */
    boolean tls;

    /** Certificato e chiave privata del treno in formato PEM (servono solo con tls). */
    String certificatoPath;
    String chiavePath;

    /** Chi risponde alle richieste. */
    TrainIngestion ingestion;

    private Javalin app;

    /** Accende il server e registra le rotte. */
    public void avvia() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // disableHtmlEscaping: senza, Gson scriverebbe l'apostrofo di "all'impianto"
            // come \u0027. Sarebbe JSON valido lo stesso, ma illeggibile con curl.
            config.jsonMapper(new JavalinGson(new GsonBuilder().disableHtmlEscaping().create(), false));
            if (tls) {
                config.registerPlugin(new SslPlugin(ssl -> {
                    ssl.pemFromPath(certificatoPath, chiavePath);
                    ssl.insecure = false;      // niente HTTP in chiaro
                    ssl.securePort = porta;    // l'HTTPS prende la porta del treno
                }));
            }
        });

        app.get("/treno/info", ingestion::info);
        app.get("/treno/stato", ingestion::getStato);
        app.get("/treno/viaggio", ingestion::getViaggio);
        app.get("/treno/tratta", ingestion::getTratta);
        app.post("/treno/tratta", ingestion::setTratta);
        app.post("/treno/sensore/guasto", ingestion::rilevaGuasto);
        app.post("/treno/sensore/passaggio", ingestion::rilevaPassaggio);

        // Un corpo che non è JSON è un errore di chi chiama (400), non del treno (500)
        app.exception(JsonParseException.class, (e, ctx) ->
                ctx.status(400).result("Il corpo della richiesta non e' un oggetto JSON valido"));

        if (tls) {
            app.start(); // la porta l'ha già data il plugin SSL
        } else {
            app.start(porta);
        }
        LOG.info(String.format("🌐 Server REST disponibile su %s://localhost:%d/treno/info",
                tls ? "https" : "http", porta));
    }
}
