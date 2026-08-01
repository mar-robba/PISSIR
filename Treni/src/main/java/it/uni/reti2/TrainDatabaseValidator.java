package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Verifica che l'argomento di avvio sia una chiave primaria di Treni.id_convoglio. */
@ApplicationScoped
public class TrainDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(TrainDatabaseValidator.class);
    private static final long RETRY_SECONDI = 15;

    @Inject
    TrainDB trainDB;

    @ConfigProperty(name = "centrale.url", defaultValue = "https://localhost:8444")
    String centraleUrl;

    @Inject
    SecureHttpClient secureHttpClient;

    private Instant ultimoTentativo = Instant.EPOCH;
    private boolean erroreIdGiaSegnalato = false;

    /** @return true solo se l'ID è presente come chiave primaria nel database. */
    public synchronized boolean verificaSeNecessario() {
        if (trainDB.trenoRiconosciuto) return true;

        Instant adesso = Instant.now();
        if (Duration.between(ultimoTentativo, adesso).getSeconds() < RETRY_SECONDI) return false;
        ultimoTentativo = adesso;

        String idTreno = trainDB.trenoId == null ? "" : trainDB.trenoId.trim();
        if (idTreno.isEmpty()) {
            LOG.error("Impossibile verificare il processo: ID del treno vuoto");
            return false;
        }

        String idCodificato = URLEncoder.encode(idTreno, StandardCharsets.UTF_8).replace("+", "%20");
        String url = centraleUrl + "/api/treni/" + idCodificato + "/verifica";
        try {
            HttpRequest richiesta = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> risposta = secureHttpClient.get().send(richiesta, HttpResponse.BodyHandlers.discarding());
            if (risposta.statusCode() == 404) {
                if (!erroreIdGiaSegnalato) {
                    LOG.errorf("L'ID '%s' non è una chiave primaria presente nel database: il processo non invierà telemetria.", idTreno);
                    erroreIdGiaSegnalato = true;
                }
                return false;
            }
            if (risposta.statusCode() != 200) {
                LOG.warnf("Verifica dell'ID '%s' non riuscita (HTTP %d); riprovo tra %ds",
                        idTreno, risposta.statusCode(), RETRY_SECONDI);
                return false;
            }

            trainDB.trenoRiconosciuto = true;
            LOG.infof("🚂 Processo associato alla chiave primaria del database: %s", trainDB.trenoId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warnf("Centrale non raggiungibile durante la verifica dell'ID '%s'; riprovo tra %ds: %s",
                    idTreno, RETRY_SECONDI, e.getMessage());
            return false;
        }
    }
}
