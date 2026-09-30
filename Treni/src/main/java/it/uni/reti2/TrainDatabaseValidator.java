package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifica che l'ID del treno sia valido tramite messaggistica MQTT.
 *
 * <p>La domanda parte su railway/train/{id}/validation e la Centrale risponde su
 * railway/train/{id}/validation-response: la risposta la riceve
 * {@link #gestisciRispostaValidazione}, che il main registra presso ClientMqtt.</p>
 */
public class TrainDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(TrainDatabaseValidator.class.getName());
    private static final long RETRY_SECONDI = 15;

    public enum EsitoVerifica {
        VALIDATO,
        ID_NON_PRESENTE,
        RIPROVA
    }

    TrainDB trainDB;

    // Coda dei messaggi MQTT in uscita, per l'invio della richiesta di verifica
    CodaPubblicazioni coda;

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
            LOG.severe("Impossibile verificare il processo: ID del treno vuoto");
            return EsitoVerifica.RIPROVA;
        }

        // Pubblica il messaggio di richiesta sul broker MQTT
        inviaRichiestaValidazione(idTreno);

        return EsitoVerifica.RIPROVA;
    }

    private void inviaRichiestaValidazione(String idTreno) {
        try {
            JsonObject payloadJson = new JsonObject();
            payloadJson.addProperty("trenoId", idTreno);

            coda.metti("railway/train/" + idTreno + "/validation", payloadJson.toString(), 0);
            LOG.info(String.format("📩 Richiesta di validazione inviata per il treno: %s", idTreno));
        } catch (Exception e) {
            LOG.warning(String.format("Errore durante l'invio della richiesta MQTT per ID '%s'; riprovo tra %ds: %s",
                    idTreno, RETRY_SECONDI, e.getMessage()));
        }
    }

    /**
     * Ascolta in modo asincrono la risposta dal server centrale sul canale di validation
     * response (gira sul thread di Paho).
     *
     * @param payload Il contenuto della risposta (JSON).
     */
    public void gestisciRispostaValidazione(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();

            String trenoIdRisposta = CampiJson.testo(root, "trenoId", null);
            String trenoIdCorrente = trainDB.trenoId != null ? trainDB.trenoId.trim() : "";

            // Verifica che la risposta appartenga a questo specifico treno
            if (trenoIdRisposta != null && trenoIdRisposta.equals(trenoIdCorrente)) {
                boolean esiste = CampiJson.booleano(root, "esisteNelDb", false);

                if (esiste) {
                    trainDB.trenoRiconosciuto = true;
                    this.esitoCorrente = EsitoVerifica.VALIDATO;
                    LOG.info(String.format("🚂 Processo associato con successo alla chiave primaria del database: %s", trenoIdRisposta));
                } else {
                    this.esitoCorrente = EsitoVerifica.ID_NON_PRESENTE;
                    LOG.severe(String.format("❌ L'ID '%s' non è presente nel database centrale: il treno non è registrato.", trenoIdRisposta));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Errore nel parsing del messaggio di risposta di validazione MQTT", e);
        }
    }
}