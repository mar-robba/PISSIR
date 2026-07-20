package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedList;
import java.util.Queue;

/**
 * LocalBuffer implementa un meccanismo di persistenza temporanea (coda FIFO in-memory)
 * per gli eventi generati dalla Stazione (es. transiti, guasti).
 * Questo buffer è fondamentale quando la connessione verso la Centrale cade (Edge computing in offline mode),
 * permettendo di accodare gli eventi e ritrasmetterli al ripristino della rete.
 */
@ApplicationScoped
public class LocalBuffer {

    /**
     * Evento accodato in attesa di ritrasmissione.
     * Oltre al payload JSON viene memorizzato anche il canale di destinazione
     * ("transit" oppure "alert"), così al momento del flush ogni evento
     * può essere reinviato sull'emitter MQTT corretto.
     *
     * @param canale  Canale logico di destinazione: "transit" o "alert".
     * @param payload Il payload JSON dell'evento non recapitato.
     */
    public record EventoBufferizzato(String canale, String payload) {}

    /**
     * La coda vera e propria dove vengono stoccati gli eventi.
     * L'uso di LinkedList implementa nativamente l'interfaccia Queue (FIFO).
     */
    private final Queue<EventoBufferizzato> bufferEventi = new LinkedList<>();

    /**
     * Aggiunge un nuovo evento in coda al buffer.
     * Da invocare quando l'invio remoto fallisce o la stazione è offline.
     *
     * @param canale  Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload Il payload JSON dell'evento non recapitato.
     */
    public synchronized void add(String canale, String payload) {
        bufferEventi.add(new EventoBufferizzato(canale, payload));
    }

    /**
     * Estrae e rimuove il primo evento inserito nella coda (modalità FIFO).
     *
     * @return L'evento più vecchio nel buffer, oppure null se il buffer è vuoto.
     */
    public synchronized EventoBufferizzato poll() {
        return bufferEventi.poll();
    }

    /**
     * Verifica se il buffer locale è vuoto.
     *
     * @return true se non ci sono eventi pendenti, false altrimenti.
     */
    public synchronized boolean isEmpty() {
        return bufferEventi.isEmpty();
    }

    /**
     * Ritorna il numero di eventi attualmente in attesa di essere inviati.
     *
     * @return La dimensione della coda.
     */
    public synchronized int size() {
        return bufferEventi.size();
    }

    /**
     * Espone la coda sottostante.
     * Utile per scopi diagnostici (es. restituzione in un'API di stato).
     *
     * @return La struttura dati Queue.
     */
    public Queue<EventoBufferizzato> getBuffer() {
        return bufferEventi;
    }
}
