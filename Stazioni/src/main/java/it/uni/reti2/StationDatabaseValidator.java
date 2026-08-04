package it.uni.reti2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * Verifica che l'ID della stazione sia valido tramite messaggistica MQTT:
 * stesso pattern richiesta/risposta asincrona usato da TrainDatabaseValidator
 * per il treno (topic railway/station/{id}/validation e .../validation-response,
 * gestiti lato Centrale da ExistIdForEdgeStazione).
 */
@ApplicationScoped
public class StationDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(StationDatabaseValidator.class);
    private static final long RETRY_SECONDI = 15;

    /** Risultato della verifica dell'ID nel database centrale. */
    public enum EsitoVerifica {
        /** L'ID è presente nel database: il processo può proseguire. */
        VALIDATO,
        /** La Centrale ha risposto che l'ID non esiste (errore fatale). */
        ID_NON_PRESENTE,
        /** Nessuna risposta ancora arrivata o richiesta non ancora inviata: riprovare. */
        RIPROVA
    }

    @Inject
    DBLocale dbLocale;

    @Inject
    ObjectMapper mapper;

    // Emitter per l'invio della richiesta di verifica via MQTT
    @Inject
    @Channel("validation-request-out")
    Emitter<String> validationEmitter;

    private Instant ultimoTentativo = Instant.EPOCH;
    private volatile EsitoVerifica esitoCorrente = EsitoVerifica.RIPROVA;

    /**
     * Verifica lo stato dell'ID. Se la risposta non è ancora arrivata,
     * invia una richiesta di validazione via MQTT rispettando l'intervallo di retry.
     */
    public synchronized EsitoVerifica verificaSeNecessario() {
        if (dbLocale.stazioneRiconosciuta || esitoCorrente == EsitoVerifica.VALIDATO) {
            return EsitoVerifica.VALIDATO;
        }

        if (esitoCorrente == EsitoVerifica.ID_NON_PRESENTE) {
            return EsitoVerifica.ID_NON_PRESENTE;
        }

        Instant adesso = Instant.now();
        if (Duration.between(ultimoTentativo, adesso).getSeconds() < RETRY_SECONDI) {
            return EsitoVerifica.RIPROVA;
        }
        ultimoTentativo = adesso;

        String idStazione = dbLocale.stazioneId == null ? "" : dbLocale.stazioneId.trim();
        if (idStazione.isEmpty()) {
            LOG.error("Impossibile verificare il processo: ID della stazione vuoto");
            return EsitoVerifica.RIPROVA;
        }

        // Pubblica il messaggio di richiesta sul broker MQTT
        inviaRichiestaValidazione(idStazione);

        return EsitoVerifica.RIPROVA;
    }

    private void inviaRichiestaValidazione(String idStazione) {
        try {
            ObjectNode payloadJson = mapper.createObjectNode();
            payloadJson.put("stazioneId", idStazione);

            validationEmitter.send(payloadJson.toString());
            LOG.infof("📩 Richiesta di validazione inviata per la stazione: %s", idStazione);
        } catch (Exception e) {
            LOG.warnf("Errore durante l'invio della richiesta MQTT per ID '%s'; riprovo tra %ds: %s",
                    idStazione, RETRY_SECONDI, e.getMessage());
        }
    }

    /**
     * Ascolta in modo asincrono la risposta dal server centrale sul canale di validation response.
     */
    @Incoming("validation-response-in")
    public CompletionStage<Void> gestisciRispostaValidazione(Message<byte[]> messaggio) {
        try {
            String payload = new String(messaggio.getPayload());
            JsonNode root = mapper.readTree(payload);

            String stazioneIdRisposta = root.has("stazioneId") ? root.get("stazioneId").asText() : null;
            String stazioneIdCorrente = dbLocale.stazioneId != null ? dbLocale.stazioneId.trim() : "";

            // Verifica che la risposta appartenga a questa specifica stazione
            if (stazioneIdRisposta != null && stazioneIdRisposta.equals(stazioneIdCorrente)) {
                boolean esiste = root.has("esisteNelDb") && root.get("esisteNelDb").asBoolean();

                if (esiste) {
                    dbLocale.stazioneRiconosciuta = true;
                    this.esitoCorrente = EsitoVerifica.VALIDATO;
                    LOG.infof("🚉 Processo associato con successo alla chiave primaria del database: %s", stazioneIdRisposta);
                } else {
                    this.esitoCorrente = EsitoVerifica.ID_NON_PRESENTE;
                    LOG.errorf("❌ L'ID '%s' non è presente nel database centrale: la stazione non è registrata.", stazioneIdRisposta);
                }
            }
        } catch (JsonProcessingException e) {
            LOG.error("Errore nel parsing del messaggio di risposta di validazione MQTT", e);
        }

        return messaggio.ack();
    }
}
