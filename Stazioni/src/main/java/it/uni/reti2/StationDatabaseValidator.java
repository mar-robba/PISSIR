package it.uni.reti2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifica che l'ID della stazione sia valido tramite messaggistica MQTT:
 * stesso pattern richiesta/risposta asincrona usato da TrainDatabaseValidator
 * per il treno (topic railway/station/{id}/validation e .../validation-response,
 * gestiti lato Centrale da ExistIdForEdgeStazione).
 *
 * <p>La risposta la riceve {@link #gestisciRispostaValidazione}, che il main registra presso
 * ClientMqtt come destinatario di railway/station/+/validation-response.</p>
 */
public class StationDatabaseValidator {

    private static final Logger LOG = Logger.getLogger(StationDatabaseValidator.class.getName());
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

    /** Stato del nodo: l'id da verificare e il flag stazioneRiconosciuta. Assegnato dal main. */
    DBLocale dbLocale;

    /** Con che cosa si pubblica la richiesta di verifica. */
    ClientMqtt clientMqtt;

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
            LOG.severe("Impossibile verificare il processo: ID della stazione vuoto");
            return EsitoVerifica.RIPROVA;
        }

        // Pubblica il messaggio di richiesta sul broker MQTT
        inviaRichiestaValidazione(idStazione);

        return EsitoVerifica.RIPROVA;
    }

    /**
     * Pubblica la domanda. Va direttamente al broker, a QoS 0 e senza passare dal
     * LocalBuffer: non è un evento da consegnare per forza, se si perde il main la ripete
     * fra {@value #RETRY_SECONDI} secondi.
     */
    private void inviaRichiestaValidazione(String idStazione) {
        try {
            JsonObject payloadJson = new JsonObject();
            payloadJson.addProperty("stazioneId", idStazione);

            clientMqtt.pubblica("railway/station/" + idStazione + "/validation", payloadJson.toString(), 0);
            LOG.info(String.format("📩 Richiesta di validazione inviata per la stazione: %s", idStazione));
        } catch (Exception e) {
            LOG.warning(String.format("Errore durante l'invio della richiesta MQTT per ID '%s'; riprovo tra %ds: %s",
                    idStazione, RETRY_SECONDI, e.getMessage()));
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

            String stazioneIdRisposta = CampiJson.testo(root, "stazioneId", null);
            String stazioneIdCorrente = dbLocale.stazioneId != null ? dbLocale.stazioneId.trim() : "";

            // Verifica che la risposta appartenga a questa specifica stazione: il topic ha la
            // wildcard, quindi arrivano anche le risposte destinate alle altre
            if (stazioneIdRisposta != null && stazioneIdRisposta.equals(stazioneIdCorrente)) {
                boolean esiste = CampiJson.booleano(root, "esisteNelDb", false);

                if (esiste) {
                    dbLocale.stazioneRiconosciuta = true;
                    this.esitoCorrente = EsitoVerifica.VALIDATO;
                    LOG.info(String.format("🚉 Processo associato con successo alla chiave primaria del database: %s", stazioneIdRisposta));
                } else {
                    this.esitoCorrente = EsitoVerifica.ID_NON_PRESENTE;
                    LOG.severe(String.format("❌ L'ID '%s' non è presente nel database centrale: la stazione non è registrata.", stazioneIdRisposta));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Errore nel parsing del messaggio di risposta di validazione MQTT", e);
        }
    }
}
