package it.uni.reti2.DbValidator;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uni.reti2.entity.Stazione;
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

/**
 * Analogo di {@link ExistIdForEdge} ma per il nodo Stazione: ascolta sul topic
 * railway/station/{id}/validation la richiesta di verifica dell'ID inviata dal
 * processo Stazione all'avvio, interroga la tabella Stazione del database
 * centrale e risponde su railway/station/{id}/validation-response con l'esito.
 */
@ApplicationScoped
public class ExistIdForEdgeStazione {

    @Inject
    ObjectMapper mapper;

    /** Unico punto da cui questa classe interroga il database. */
    @Inject
    RailwayRepository repository;

    // Emitter per l'invio del messaggio di risposta
    @Inject
    @Channel("validation-station-response") // Nome del canale di uscita configurato in application.properties
    Emitter<String> validationEmitter;

    /**
     * Come per il treno: try/catch su tutto il corpo e ack in ogni caso, così un
     * payload malformato non fa terminare il canale (che altrimenti smetterebbe di
     * validare qualsiasi stazione fino al riavvio della Centrale). La lettura sul DB
     * gira in una transazione aperta DENTRO il try.
     */
    @Incoming("validation-station-in")
    @Blocking
    public CompletionStage<Void> interrogaIlDbPerValidazione(Message<byte[]> messaggio) {
        String payload = new String(messaggio.getPayload());
        try {
            JsonNode root = mapper.readTree(payload);

            String stazioneId = root.has("stazioneId") ? root.get("stazioneId").asText() : null;

            if (stazioneId != null) {
                // Check sul DB: la query sta nel repository, qui resta solo la transazione
                boolean esiste = QuarkusTransaction.requiringNew().call(() -> repository.esisteStazione(stazioneId));

                // Creiamo la risposta aggiungendo la conferma dell'esistenza a DB
                ObjectNode responseNode = root.deepCopy();
                responseNode.put("esisteNelDb", esiste);
                responseNode.put("ESISTE", esiste);

                // Pubblichiamo il messaggio sul canale di risposta con il topic dinamico
                String targetTopic = "railway/station/" + stazioneId + "/validation-response";
                SendingMqttMessageMetadata metadata = new SendingMqttMessageMetadata(targetTopic, MqttQoS.AT_MOST_ONCE, false);
                Message<String> mqttMsg = Message.of(responseNode.toString(), Metadata.of(metadata));
                validationEmitter.send(mqttMsg);

                System.out.println("Elaborata stazione: " + stazioneId + " | Esiste a DB: " + esiste);
            } else {
                System.err.println("Campo 'stazioneId' non presente nel payload: " + payload);
            }
        } catch (Exception e) {
            System.err.println("Richiesta di validazione stazione non elaborabile (" + e.getMessage() + "): " + payload);
        }

        // Confermiamo (ACK) la ricezione del messaggio in modo reattivo
        return messaggio.ack();
    }
}
