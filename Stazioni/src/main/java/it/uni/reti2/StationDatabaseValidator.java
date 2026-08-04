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

/** Verifica che l'argomento di avvio sia una chiave primaria di Stazione.id_stazione. */
@ApplicationScoped
public class StationDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(StationDatabaseValidator.class);
    private static final long RETRY_SECONDI = 15;

    /** Risultato della verifica dell'ID nel database centrale. */
    public enum EsitoVerifica {
        /** L'ID è presente nel database: il processo può proseguire. */
        VALIDATO,
        /** Il server ha risposto 404: l'ID non esiste nel database (errore fatale). */
        ID_NON_PRESENTE,
        /** Il server non è raggiungibile o ha risposto con un errore transitorio: riprovare. */
        RIPROVA
    }

    @Inject
    DBLocale dbLocale;

    @ConfigProperty(name = "centrale.url", defaultValue = "https://localhost:8444")
    String centraleUrl;

    @Inject
    SecureHttpClient secureHttpClient;

    private Instant ultimoTentativo = Instant.EPOCH;
    private boolean erroreIdGiaSegnalato = false;

    /** @return esito della verifica: VALIDATO, ID_NON_PRESENTE, o RIPROVA. */
    public synchronized EsitoVerifica verificaSeNecessario() {
        if (dbLocale.stazioneRiconosciuta) return EsitoVerifica.VALIDATO;

        Instant adesso = Instant.now();
        if (Duration.between(ultimoTentativo, adesso).getSeconds() < RETRY_SECONDI) return EsitoVerifica.RIPROVA;
        ultimoTentativo = adesso;

        String idStazione = dbLocale.stazioneId == null ? "" : dbLocale.stazioneId.trim();
        if (idStazione.isEmpty()) {
            LOG.error("Impossibile verificare il processo: ID della stazione vuoto");
            return EsitoVerifica.RIPROVA;
        }

        String idCodificato = URLEncoder.encode(idStazione, StandardCharsets.UTF_8).replace("+", "%20");
        String url = centraleUrl + "/api/stazioni/" + idCodificato + "/verifica";
        try {
            HttpRequest richiesta = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> risposta = secureHttpClient.get().send(richiesta, HttpResponse.BodyHandlers.discarding());
            if (risposta.statusCode() == 404) {
                LOG.errorf("❌ L'ID '%s' non è una chiave primaria presente nel database centrale: " +
                        "la stazione non è registrata. Il processo verrà terminato.", idStazione);
                return EsitoVerifica.ID_NON_PRESENTE;
            }
            if (risposta.statusCode() != 200) {
                LOG.warnf("Verifica dell'ID '%s' non riuscita (HTTP %d); riprovo tra %ds",
                        idStazione, risposta.statusCode(), RETRY_SECONDI);
                return EsitoVerifica.RIPROVA;
            }

            dbLocale.stazioneRiconosciuta = true;
            LOG.infof("🚉 Processo associato alla chiave primaria del database: %s", dbLocale.stazioneId);
            return EsitoVerifica.VALIDATO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EsitoVerifica.RIPROVA;
        } catch (Exception e) {
            LOG.warnf("Centrale non raggiungibile durante la verifica dell'ID '%s'; riprovo tra %ds: %s",
                    idStazione, RETRY_SECONDI, e.getMessage());
            return EsitoVerifica.RIPROVA;
        }
    }
}
