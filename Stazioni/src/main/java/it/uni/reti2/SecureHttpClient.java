package it.uni.reti2;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
// ???
/** Client HTTPS che considera attendibile solo la CA privata del progetto. */
@ApplicationScoped
public class SecureHttpClient {
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
            throw new IllegalStateException("Impossibile configurare la CA TLS della Centrale: " + caPath, e);
        }
    }

    public HttpClient get() { return client; }
}
