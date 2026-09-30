package it.uni.reti2.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import it.uni.reti2.ContestoTls;
import org.pac4j.core.adapter.FrameworkAdapter;
import org.pac4j.core.authorization.authorizer.DefaultAuthorizers;
import org.pac4j.core.authorization.generator.AuthorizationGenerator;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.context.FrameworkParameters;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.matching.matcher.DefaultMatchers;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.http.client.direct.DirectBearerAuthClient;
import org.pac4j.javalin.JavalinFrameworkParameters;
import org.pac4j.javalin.SecurityHandler;
import org.pac4j.jwt.config.signature.RSASignatureConfiguration;
import org.pac4j.jwt.config.signature.SignatureConfiguration;
import org.pac4j.jwt.credentials.authenticator.JwtAuthenticator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * La verifica dei token di Keycloak (OAuth 2.0), fatta con pac4j dentro Javalin.
 *
 * <p>La web app fa il login su Keycloak e chiama le API con l'access token nell'header
 * {@code Authorization: Bearer <token>}. Il token è un JWT firmato dal realm "railway": per
 * fidarsi basta verificarne la firma con la chiave pubblica del realm, senza chiedere niente a
 * Keycloak a ogni richiesta. Prima lo faceva l'estensione quarkus-oidc; adesso i pezzi sono
 * quelli di pac4j, la libreria del materiale del corso:</p>
 * <ul>
 *   <li>{@link DirectBearerAuthClient} prende il token dall'header;</li>
 *   <li>{@link VerificaToken}, un {@link JwtAuthenticator}, ne controlla la firma con le
 *       chiavi del realm e la scadenza, e in più l'emittente (il realm giusto) e l'audience
 *       (il token dev'essere stato rilasciato per la Centrale, {@code railway-centrale});</li>
 *   <li>{@link RuoliDelRealm} copia fra i ruoli del profilo quelli che Keycloak mette nel
 *       claim {@code realm_access.roles} (amministratore, tecnico);</li>
 *   <li>il {@link SecurityHandler} di javalin-pac4j mette insieme i tre pezzi e risponde 401
 *       se il token manca o non è buono.</li>
 * </ul>
 *
 * <p>Le chiavi pubbliche il realm le pubblica in HTTPS (all'indirizzo che dice il suo
 * documento {@code .well-known/openid-configuration}), con un certificato firmato dalla CA del
 * progetto: per scaricarle serve {@link ContestoTls}. Si scaricano in sottofondo all'avvio e
 * si riprova finché Keycloak non risponde: nel frattempo la Centrale funziona, ma le API
 * protette rispondono 401. <b>Finché le chiavi non ci sono non si chiede niente a pac4j</b>:
 * un JwtAuthenticator senza chiavi accetterebbe un token non firmato.</p>
 */
public class SicurezzaKeycloak {

    private static final Logger LOG = Logger.getLogger(SicurezzaKeycloak.class.getName());

    /** Pausa fra due tentativi di scaricare le chiavi. */
    private static final int RIPROVA_SECONDI = 5;

    /** Indirizzo del realm (https://localhost:8543/realms/railway). */
    public String indirizzoRealm;

    /** Il certificato della CA del progetto, per l'HTTPS di Keycloak. */
    public String caPath;

    /** Il client per cui il token deve essere stato rilasciato (claim "aud"). */
    public String audience = "railway-centrale";

    private final VerificaToken verificatore = new VerificaToken();

    private Config config;

    private SecurityHandler controllo;

    /** Diventa true quando le chiavi del realm sono caricate: prima si rifiuta tutto. */
    private volatile boolean pronta;

    /**
     * Prepara pac4j e fa partire lo scaricamento delle chiavi del realm su un thread a parte:
     * il main non aspetta Keycloak.
     */
    public void avvia() {
        prepara();
        Thread scaricamento = new Thread(this::scaricaChiavi, "chiavi-keycloak");
        scaricamento.setDaemon(true);
        scaricamento.start();
    }

    /**
     * Prepara pac4j con chiavi e emittente dati a mano, senza Keycloak: la usano i test, che
     * firmano i loro token con una chiave generata sul momento.
     *
     * @param jwks      Le chiavi pubbliche nel formato del realm ({"keys": [...]}).
     * @param emittente Il valore atteso del claim "iss".
     */
    public void usaChiavi(String jwks, String emittente) {
        prepara();
        carica(jwks, emittente);
    }

    /**
     * Controlla il token della richiesta.
     *
     * @return true se il token c'è, è firmato dal realm, non è scaduto ed è per la Centrale.
     */
    public boolean autentica(Context ctx) {
        if (!pronta) {
            return false;
        }
        try {
            controllo.handle(ctx);
            return true;
        } catch (UnauthorizedResponse tokenNonValido) {
            // Il SecurityHandler segnala il 401 lanciando l'eccezione di Javalin: qui la si
            // trasforma in un "no", e la risposta la scrive FiltroAutorizzazione nel suo formato.
            return false;
        }
    }

    /** @return Il profilo costruito dal token di questa richiesta (dopo {@link #autentica}). */
    public Optional<UserProfile> profilo(Context ctx) {
        FrameworkParameters parametri = new JavalinFrameworkParameters(ctx);
        return new ProfileManager(config.getWebContextFactory().newContext(parametri),
                config.getSessionStoreFactory().newSessionStore(parametri)).getProfile();
    }

    /** Costruisce il client pac4j e il SecurityHandler che lo usa. */
    private void prepara() {
        DirectBearerAuthClient client = new DirectBearerAuthClient(verificatore);
        client.addAuthorizationGenerator(new RuoliDelRealm());
        config = new Config(client);
        // Le fabbriche di Javalin (contesto, sessione, risposte): le mette javalin-pac4j.
        FrameworkAdapter.INSTANCE.applyDefaultSettingsIfUndefined(config);
        // Nessun authorizer e nessun matcher di pac4j: chi può fare cosa lo decide
        // FiltroAutorizzazione, che guarda anche il metodo e il percorso della richiesta.
        controllo = new SecurityHandler(config, "DirectBearerAuthClient", DefaultAuthorizers.NONE, DefaultMatchers.NONE);
    }

    /**
     * Scarica il documento di configurazione del realm, e da lì le chiavi pubbliche. Riprova
     * ogni {@value #RIPROVA_SECONDI} secondi finché Keycloak non risponde.
     */
    private void scaricaChiavi() {
        HttpClient http;
        try {
            http = HttpClient.newBuilder()
                    .sslContext(ContestoTls.fidatoDellaCa(Path.of(caPath)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            LOG.severe("🔑 Certificato della CA illeggibile (" + caPath + "): nessun token verrà accettato. " + e.getMessage());
            return;
        }
        while (true) {
            try {
                JsonObject configurazione = Json.oggetto(scarica(http, indirizzoRealm + "/.well-known/openid-configuration"));
                String jwks = scarica(http, configurazione.get("jwks_uri").getAsString());
                carica(jwks, configurazione.get("issuer").getAsString());
                LOG.info("🔑 Chiavi del realm caricate da " + indirizzoRealm);
                return;
            } catch (Exception e) {
                LOG.warning(String.format("🔑 Keycloak non raggiungibile su %s (%s): riprovo tra %d secondi",
                        indirizzoRealm, e.getMessage(), RIPROVA_SECONDI));
            }
            try {
                Thread.sleep(RIPROVA_SECONDI * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String scarica(HttpClient http, String indirizzo) throws Exception {
        HttpResponse<String> risposta = http.send(
                HttpRequest.newBuilder(URI.create(indirizzo)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (risposta.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + risposta.statusCode() + " da " + indirizzo);
        }
        return risposta.body();
    }

    /**
     * Passa a pac4j le chiavi di firma del realm. Il realm ne pubblica anche una per cifrare
     * (use "enc"), che per verificare una firma non serve.
     */
    private void carica(String jwks, String emittente) {
        List<SignatureConfiguration> firme = new ArrayList<>();
        for (JsonElement chiave : Json.oggetto(jwks).getAsJsonArray("keys")) {
            JsonObject jwk = chiave.getAsJsonObject();
            boolean rsa = "RSA".equals(jwk.has("kty") ? jwk.get("kty").getAsString() : null);
            boolean perFirmare = !jwk.has("use") || "sig".equals(jwk.get("use").getAsString());
            if (rsa && perFirmare) {
                RSASignatureConfiguration firma = new RSASignatureConfiguration();
                firma.setKeysFromJwk(jwk.toString());
                firme.add(firma);
            }
        }
        if (firme.isEmpty()) {
            throw new IllegalStateException("il realm non ha nessuna chiave RSA di firma");
        }
        verificatore.setSignatureConfigurations(firme);
        verificatore.emittente = emittente;
        verificatore.audience = audience;
        pronta = true;
    }

    /**
     * Il JwtAuthenticator di pac4j controlla firma e scadenza; questa sottoclasse aggiunge i
     * due controlli che faceva quarkus-oidc: l'emittente ("iss", cioè il realm railway) e
     * l'audience ("aud" deve contenere railway-centrale). Senza il secondo la Centrale
     * accetterebbe anche un token rilasciato per un'altra applicazione dello stesso realm.
     */
    static final class VerificaToken extends JwtAuthenticator {

        volatile String emittente;
        volatile String audience;

        @Override
        public Optional<Credentials> validate(CallContext ctx, Credentials credenziali) {
            Optional<Credentials> valide = super.validate(ctx, credenziali);
            UserProfile profilo = credenziali.getUserProfile();
            if (profilo == null) {
                return valide; // firma o scadenza sbagliate: pac4j ha gia' detto di no
            }
            if (!emittente.equals(profilo.getAttribute("iss")) || !contiene(profilo.getAttribute("aud"), audience)) {
                credenziali.setUserProfile(null);
                return Optional.empty();
            }
            return valide;
        }

        /** "aud" può essere un testo solo o una lista di testi. */
        private static boolean contiene(Object aud, String atteso) {
            if (aud instanceof Collection<?> lista) {
                return lista.contains(atteso);
            }
            return atteso.equals(aud);
        }
    }

    /**
     * Copia fra i ruoli del profilo i ruoli di realm che Keycloak scrive nel token, nel claim
     * {@code realm_access: {"roles": ["amministratore", ...]}}.
     */
    static final class RuoliDelRealm implements AuthorizationGenerator {

        @Override
        public Optional<UserProfile> generate(CallContext ctx, UserProfile profilo) {
            if (profilo.getAttribute("realm_access") instanceof Map<?, ?> realmAccess
                    && realmAccess.get("roles") instanceof Collection<?> ruoli) {
                for (Object ruolo : ruoli) {
                    profilo.addRole(String.valueOf(ruolo));
                }
            }
            return Optional.of(profilo);
        }
    }
}
