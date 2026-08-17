package it.uni.reti2.DbValidator;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import io.smallrye.reactive.messaging.mqtt.SendingMqttMessageMetadata;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class ExistIdForEdge {

    @Inject
    ObjectMapper mapper;

    /** Tutte le letture sul database passano da qui. */
    @Inject
    RailwayRepository repository;

    // 1. Iniettiamo l'Emitter per inviare il messaggio di risposta
    @Inject
    @Channel("validation-response") // Nome del canale di uscita configurato in application.properties
            Emitter<String> validationEmitter;

    /**
     * Il corpo è tutto dentro un try/catch e il messaggio viene comunque confermato:
     * prima bastava un payload non-JSON pubblicato su railway/train/+/validation per
     * far terminare in errore il canale reattivo, e da quel momento la Centrale non
     * validava più nessun treno finché non veniva riavviata.
     * La lettura sul DB gira in una transazione aperta DENTRO il try (e non con
     * @Transactional sul metodo, il cui commit sarebbe stato fuori dal try).
     */
    @Incoming("validation-in")
    @Blocking
    public CompletionStage<Void> interrogaIlDPerValidazione(Message<byte[]> messaggio) {
        // 2. Correzione: estrazione reale dei byte del payload
        String payload = new String(messaggio.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : null;

            if (trenoId != null) {
                // Check sul DB: la query sta nel repository, qui resta solo la transazione
                boolean esiste = QuarkusTransaction.requiringNew().call(() -> repository.esisteTreno(trenoId));

                // 3. Creiamo la risposta aggiungendo la conferma dell'esistenza a DB
                ObjectNode responseNode = root.deepCopy();
                responseNode.put("esisteNelDb", esiste);
                responseNode.put("ESISTE", esiste);

                // 4. Pubblichiamo il messaggio sul canale di risposta con il topic dinamico
                String targetTopic = "railway/train/" + trenoId + "/validation-response";
                SendingMqttMessageMetadata metadata = new SendingMqttMessageMetadata(targetTopic, MqttQoS.AT_MOST_ONCE, false);
                Message<String> mqttMsg = Message.of(responseNode.toString(), Metadata.of(metadata));
                validationEmitter.send(mqttMsg);

                System.out.println("Elaborato treno: " + trenoId + " | Esiste a DB: " + esiste);
            } else {
                System.err.println("Campo 'trenoId' non presente nel payload: " + payload);
            }
        } catch (Exception e) {
            System.err.println("Richiesta di validazione treno non elaborabile (" + e.getMessage() + "): " + payload);
        }

        // 5. Confermiamo (ACK) la ricezione del messaggio in modo reattivo
        return messaggio.ack();
    }
}