package it.uni.reti2;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
/**
 * Client HTTPS che considera attendibile solo la CA privata del progetto.
 * Gemello di quello del Treno: oggi la Stazione parla con la Centrale solo via MQTT,
 * ma la classe è pronta per le chiamate REST verso la Centrale
 * (per esempio GET /api/prossima-stazione) sotto profilo tls.
 */
@ApplicationScoped
public class SecureHttpClient {
    private static final Logger LOG = Logger.getLogger(SecureHttpClient.class);

    @ConfigProperty(name = "centrale.tls.ca.path")
    String caPath;

    private HttpClient client;

    @PostConstruct
    void init() {
        try (InputStream input = Files.newInputStream(Path.of(caPath))) {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("railway-ca", ca);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, trustManagers.getTrustManagers(), null);
            client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
        } catch (Exception e) {
            // Senza certificati generati (profilo di default, tutto in chiaro sulla 8781)
            // la stazione deve comunque partire: si usa il client HTTP standard.
            // Prima qui volava una IllegalStateException e il bean non si sarebbe
            // nemmeno costruito, bloccando l'avvio del nodo.
            LOG.warnf("⚠️ CA TLS della Centrale non caricata (%s): uso il client HTTP in chiaro. Dettaglio: %s",
                    caPath, e.getMessage());
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }
    }

    public HttpClient get() { return client; }
}
