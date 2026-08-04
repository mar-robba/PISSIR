package it.uni.reti2.DbValidator;

import io.smallrye.common.annotation.Blocking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uni.reti2.entity.Treno;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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

    // 1. Iniettiamo l'Emitter per inviare il messaggio di risposta
    @Inject
    @Channel("validation-response") // Nome del canale di uscita configurato in application.properties
            Emitter<String> validationEmitter;

    @Incoming("validation-in")
    @Transactional
    @Blocking
    public CompletionStage<Void> interrogaIlDPerValidazione(Message<byte[]> messaggio) throws JsonProcessingException {
        // 2. Correzione: estrazione reale dei byte del payload
        String payload = new String(messaggio.getPayload());
        JsonNode root = mapper.readTree(payload);

        String trenoId = root.has("trenoId") ? root.get("trenoId").asText() : null;

        if (trenoId != null) {
            // Check sul DB tramite il pattern Active Record di Panache
            boolean esiste = Treno.findById(trenoId) != null;

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

        // 5. Confermiamo (ACK) la ricezione del messaggio in modo reattivo
        return messaggio.ack();
    }
}