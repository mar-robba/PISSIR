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
 * Verifica che l'ID del treno sia valido tramite messaggistica MQTT.
 */
@ApplicationScoped
public class TrainDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(TrainDatabaseValidator.class);
    private static final long RETRY_SECONDI = 15;

    public enum EsitoVerifica {
        VALIDATO,
        ID_NON_PRESENTE,
        RIPROVA
    }

    @Inject
    TrainDB trainDB;

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
        if (trainDB.trenoRiconosciuto || esitoCorrente == EsitoVerifica.VALIDATO) {
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

        String idTreno = trainDB.trenoId == null ? "" : trainDB.trenoId.trim();
        if (idTreno.isEmpty()) {
            LOG.error("Impossibile verificare il processo: ID del treno vuoto");
            return EsitoVerifica.RIPROVA;
        }

        // Pubblica il messaggio di richiesta sul broker MQTT
        inviaRichiestaValidazione(idTreno);

        return EsitoVerifica.RIPROVA;
    }

    private void inviaRichiestaValidazione(String idTreno) {
        try {
            ObjectNode payloadJson = mapper.createObjectNode();
            payloadJson.put("trenoId", idTreno);

            validationEmitter.send(payloadJson.toString());
            LOG.infof("📩 Richiesta di validazione inviata per il treno: %s", idTreno);
        } catch (Exception e) {
            LOG.warnf("Errore durante l'invio della richiesta MQTT per ID '%s'; riprovo tra %ds: %s",
                    idTreno, RETRY_SECONDI, e.getMessage());
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

            String trenoIdRisposta = root.has("trenoId") ? root.get("trenoId").asText() : null;
            String trenoIdCorrente = trainDB.trenoId != null ? trainDB.trenoId.trim() : "";

            // Verifica che la risposta appartenga a questo specifico treno
            if (trenoIdRisposta != null && trenoIdRisposta.equals(trenoIdCorrente)) {
                boolean esiste = root.has("esisteNelDb") && root.get("esisteNelDb").asBoolean();

                if (esiste) {
                    trainDB.trenoRiconosciuto = true;
                    this.esitoCorrente = EsitoVerifica.VALIDATO;
                    LOG.infof("🚂 Processo associato con successo alla chiave primaria del database: %s", trenoIdRisposta);
                } else {
                    this.esitoCorrente = EsitoVerifica.ID_NON_PRESENTE;
                    LOG.errorf("❌ L'ID '%s' non è presente nel database centrale: il treno non è registrato.", trenoIdRisposta);
                }
            }
        } catch (JsonProcessingException e) {
            LOG.error("Errore nel parsing del messaggio di risposta di validazione MQTT", e);
        }

        return messaggio.ack();
    }
}