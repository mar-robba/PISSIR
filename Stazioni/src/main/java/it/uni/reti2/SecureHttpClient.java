package it.uni.reti2;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Client HTTP che considera attendibile solo la CA privata del progetto. Con questo i
 * sensori di binario simulati ({@link SimulatoreSensori}) e la console dei sensori
 * ({@link ConsoleSensori}) chiamano le API REST della stazione.
 *
 * <p>Sotto profilo tls quelle API rispondono solo in HTTPS, con un certificato firmato dalla
 * CA del progetto (server-stazione.crt) che il truststore della JVM non conosce: con il client
 * standard i keepalive dei sensori fallirebbero all'handshake, e dopo il timeout la stazione
 * si dichiarerebbe GUASTA da sola. Il contesto TLS lo prepara ContestoTls, lo stesso che usa
 * ClientMqtt per il broker. In chiaro (profilo di default) lo stesso client va bene uguale: le
 * chiamate http:// il contesto TLS non lo usano.</p>
 */
public class SecureHttpClient {
    private static final Logger LOG = Logger.getLogger(SecureHttpClient.class.getName());

    /** Percorso del certificato della CA (ca.crt), assegnato dal main. */
    String caPath;

    private HttpClient client;

    /**
     * Costruisce il client: lo chiama il main dopo aver assegnato {@link #caPath}. Il connect
     * timeout è corto perché si parla con localhost: se la stazione non risponde entro due
     * secondi non risponderà nemmeno aspettando di più.
     */
    void init() {
        try {
            client = HttpClient.newBuilder()
                    .sslContext(ContestoTls.fidatoDellaCa(Path.of(caPath)))
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
        } catch (Exception e) {
            // Senza certificati generati (profilo di default, API in chiaro) la stazione deve
            // comunque partire: si usa il client HTTP standard.
            LOG.warning(String.format("⚠️ CA del progetto non caricata (%s): uso il client HTTP senza la CA. Dettaglio: %s",
                    caPath, e.getMessage()));
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        }
    }

    public HttpClient get() { return client; }
}
