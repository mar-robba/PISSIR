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
     * La coda vera e propria dove vengono stoccati i payload JSON degli eventi.
     * L'uso di LinkedList implementa nativamente l'interfaccia Queue (FIFO).
     */
    private final Queue<String> bufferEventi = new LinkedList<>();

    /**
     * Aggiunge un nuovo evento in coda al buffer.
     * Da invocare quando l'invio remoto fallisce.
     * 
     * @param evento Il payload JSON dell'evento non recapitato.
     */
    public void add(String evento) {
        bufferEventi.add(evento);
    }

    /**
     * Estrae e rimuove il primo evento inserito nella coda (modalità FIFO).
     * 
     * @return L'evento più vecchio nel buffer, oppure null se il buffer è vuoto.
     */
    public String poll() {
        return bufferEventi.poll();
    }

    /**
     * Verifica se il buffer locale è vuoto.
     * 
     * @return true se non ci sono eventi pendenti, false altrimenti.
     */
    public boolean isEmpty() {
        return bufferEventi.isEmpty();
    }

    /**
     * Ritorna il numero di eventi attualmente in attesa di essere inviati.
     * 
     * @return La dimensione della coda.
     */
    public int size() {
        return bufferEventi.size();
    }
    
    /**
     * Espone la coda sottostante. 
     * Utile per scopi diagnostici (es. restituzione in un'API di stato).
     * 
     * @return La struttura dati Queue.
     */
    public Queue<String> getBuffer() {
        return bufferEventi;
    }
}
