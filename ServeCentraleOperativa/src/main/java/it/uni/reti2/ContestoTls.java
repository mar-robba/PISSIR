package it.uni.reti2;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Costruisce il contesto TLS che si fida solo della CA privata del progetto
 * (BrokerMosquitto/tls/certs/ca.crt).
 *
 * <p>Java verifica il certificato di un server contro il truststore della JVM, dove la
 * nostra CA non c'è: i certificati del broker, della Centrale e dei treni li firma lei, e
 * senza questo contesto ogni connessione cifrata fallirebbe all'handshake. Invece di
 * disabilitare i controlli si crea un truststore con dentro la sola CA del progetto.</p>
 *
 * <p>Nella Centrale lo usano in due: ClientMqtt (il broker sulla 8883) e SicurezzaKeycloak (le
 * chiavi pubbliche del realm, che Keycloak serve solo in HTTPS). È la stessa classe del nodo
 * Treno: una classe di sole funzioni statiche.</p>
 */
public final class ContestoTls {

    private ContestoTls() {
        // classe di sole utilita': non si istanzia
    }

    /**
     * @param caPem Il certificato della CA in formato PEM (ca.crt).
     * @return Un SSLContext che accetta solo i certificati firmati da quella CA.
     * @throws Exception Se il file non c'è o non è un certificato valido.
     */
    public static SSLContext fidatoDellaCa(Path caPem) throws Exception {
        try (InputStream input = Files.newInputStream(caPem)) {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("railway-ca", ca);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, trustManagers.getTrustManagers(), null);
            return tls;
        }
    }
}
