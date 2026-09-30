package it.uni.reti2.DbValidator;

import com.google.gson.JsonObject;
import it.uni.reti2.gateway.Json;
import it.uni.reti2.ingestion.ClientMqtt;
import it.uni.reti2.ingestion.CodaPubblicazioni;
import it.uni.reti2.persistence.RailwayRepository;

/**
 * Risponde alla domanda che il nodo Treno fa all'avvio: "il mio ID esiste nel database
 * centrale?". Ascolta sul topic railway/train/{id}/validation, interroga la tabella Treni e
 * risponde su railway/train/{id}/validation-response con l'esito.
 *
 * <p>È una domanda e una risposta, non un evento: non cambia lo stato di niente, quindi non
 * passa dal GestoreEventi.</p>
 */
public class ExistIdForEdge {

    /** Le richieste di tutti i treni: il + è l'id del convoglio. */
    public static final String TOPIC_RICHIESTE = "railway/train/+/validation";

    /** Unico punto da cui questa classe interroga il database. */
    public RailwayRepository repository;

    /** Dove si mette la risposta (la pubblica il thread della coda, non quello di Paho). */
    public CodaPubblicazioni coda;

    /** Si mette in ascolto sulle richieste di validazione dei treni. */
    public void sottoscrivi(ClientMqtt client) {
        client.ascolta(TOPIC_RICHIESTE, 0, this::interrogaIlDPerValidazione);
    }

    public void interrogaIlDPerValidazione(String payload) {
        try {
            JsonObject root = Json.oggetto(payload);

            String trenoId = root.has("trenoId") ? root.get("trenoId").getAsString() : null;

            if (trenoId != null) {
                // Check sul DB: la query sta nel repository
                boolean esiste = repository.esisteTreno(trenoId);

                // Creiamo la risposta aggiungendo la conferma dell'esistenza a DB
                JsonObject responseNode = root.deepCopy();
                responseNode.addProperty("esisteNelDb", esiste);
                responseNode.addProperty("ESISTE", esiste);

                // Pubblichiamo il messaggio sul topic di risposta del treno (QoS 0: se si
                // perde, il treno ripete la domanda)
                String targetTopic = "railway/train/" + trenoId + "/validation-response";
                coda.metti(targetTopic, responseNode.toString(), 0);

                System.out.println("Elaborato treno: " + trenoId + " | Esiste a DB: " + esiste);
            } else {
                System.err.println("Campo 'trenoId' non presente nel payload: " + payload);
            }
        } catch (Exception e) {
            System.err.println("Richiesta di validazione treno non elaborabile (" + e.getMessage() + "): " + payload);
        }
    }
}
