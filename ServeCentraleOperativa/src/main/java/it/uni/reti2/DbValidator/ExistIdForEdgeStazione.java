package it.uni.reti2.DbValidator;

import com.google.gson.JsonObject;
import it.uni.reti2.gateway.Json;
import it.uni.reti2.ingestion.ClientMqtt;
import it.uni.reti2.ingestion.CodaPubblicazioni;
import it.uni.reti2.persistence.RailwayRepository;

/**
 * Analogo di {@link ExistIdForEdge} ma per il nodo Stazione: ascolta sul topic
 * railway/station/{id}/validation la richiesta di verifica dell'ID inviata dal
 * processo Stazione all'avvio, interroga la tabella Stazione del database
 * centrale e risponde su railway/station/{id}/validation-response con l'esito.
 */
public class ExistIdForEdgeStazione {

    /** Le richieste di tutte le stazioni: il + è l'id della stazione. */
    public static final String TOPIC_RICHIESTE = "railway/station/+/validation";

    /** Unico punto da cui questa classe interroga il database. */
    public RailwayRepository repository;

    /** Dove si mette la risposta (la pubblica il thread della coda, non quello di Paho). */
    public CodaPubblicazioni coda;

    /** Si mette in ascolto sulle richieste di validazione delle stazioni. */
    public void sottoscrivi(ClientMqtt client) {
        client.ascolta(TOPIC_RICHIESTE, 0, this::interrogaIlDbPerValidazione);
    }

    public void interrogaIlDbPerValidazione(String payload) {
        try {
            JsonObject root = Json.oggetto(payload);

            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").getAsString() : null;

            if (stazioneId != null) {
                // Check sul DB: la query sta nel repository
                boolean esiste = repository.esisteStazione(stazioneId);

                // Creiamo la risposta aggiungendo la conferma dell'esistenza a DB
                JsonObject responseNode = root.deepCopy();
                responseNode.addProperty("esisteNelDb", esiste);
                responseNode.addProperty("ESISTE", esiste);

                // Pubblichiamo il messaggio sul topic di risposta della stazione
                String targetTopic = "railway/station/" + stazioneId + "/validation-response";
                coda.metti(targetTopic, responseNode.toString(), 0);

                System.out.println("Elaborata stazione: " + stazioneId + " | Esiste a DB: " + esiste);
            } else {
                System.err.println("Campo 'stazioneId' non presente nel payload: " + payload);
            }
        } catch (Exception e) {
            System.err.println("Richiesta di validazione stazione non elaborabile (" + e.getMessage() + "): " + payload);
        }
    }
}
