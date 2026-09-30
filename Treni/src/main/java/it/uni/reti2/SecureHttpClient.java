package it.uni.reti2;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;
/*

SecureHttpClient serve a creare un client HTTPS configurato in modo sicuro, che si fida solo del certificato della CA (Certificate Authority) privata del progetto. In pratica permette al processo Treno di parlare con la Centrale Operativa via HTTPS senza disabilitare i controlli TLS.
Perché serve

Quando fai una richiesta HTTPS, Java verifica che il certificato del server sia firmato da una CA presente nel truststore della JVM.

Nel vostro progetto, la Centrale usa probabilmente un certificato autofirmato o firmato da una CA privata, quindi il truststore standard di Java non lo riconosce.

 */
/** Client HTTPS che considera attendibile solo la CA privata del progetto. */
public class SecureHttpClient {
    private static final Logger LOG = Logger.getLogger(SecureHttpClient.class.getName());

    /** Percorso del certificato della CA (ca.crt), assegnato dal main. */
    String caPath;

    private HttpClient client;

    /**
     * Costruisce il client: lo chiama il main dopo aver assegnato {@link #caPath}. Il
     * contesto TLS lo prepara ContestoTls, che è lo stesso che usano MQTT e la console.
     */
    void init() {
        try {
            client = HttpClient.newBuilder()
                    .sslContext(ContestoTls.fidatoDellaCa(Path.of(caPath)))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception e) {
            // Senza certificati generati (profilo di default, tutto in chiaro sulla 8781)
            // il treno deve comunque partire: si usa il client HTTP standard.
            LOG.warning(String.format("⚠️ CA TLS della Centrale non caricata (%s): uso il client HTTP in chiaro. Dettaglio: %s",
                    caPath, e.getMessage()));
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }
    }

    public HttpClient get() { return client; }
}
