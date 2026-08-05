package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
     * Si usa un ArrayDeque (e non una LinkedList vista come Queue) perché serve
     * poter reinserire un evento anche IN TESTA: se il reinvio fallisce a metà
     * flush l'evento più vecchio deve restare il più vecchio, altrimenti la
     * Centrale riceverebbe una USCITA prima della relativa ENTRATA.
     */
    private final Deque<EventoBufferizzato> bufferEventi = new ArrayDeque<>();

    /**
     * Aggiunge un nuovo evento in coda al buffer.
     * Da invocare quando l'invio remoto fallisce o la stazione è offline.
     *
     * @param canale  Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload Il payload JSON dell'evento non recapitato.
     */
    public synchronized void add(String canale, String payload) {
        bufferEventi.addLast(new EventoBufferizzato(canale, payload));
    }

    /**
     * Rimette un evento IN TESTA al buffer, davanti a tutti gli altri.
     * Serve al flush: l'evento che non è riuscito a partire deve essere
     * ritentato per primo, così l'ordine cronologico (FIFO) è rispettato.
     *
     * @param canale  Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload Il payload JSON dell'evento non recapitato.
     */
    public synchronized void addFirst(String canale, String payload) {
        bufferEventi.addFirst(new EventoBufferizzato(canale, payload));
    }

    /**
     * Estrae e rimuove il primo evento inserito nella coda (modalità FIFO).
     *
     * @return L'evento più vecchio nel buffer, oppure null se il buffer è vuoto.
     */
    public synchronized EventoBufferizzato poll() {
        return bufferEventi.pollFirst();
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
     * Espone una COPIA della coda (non la struttura interna) per scopi diagnostici,
     * es. la GET /stazione/buffer. Il metodo è synchronized come tutti gli altri:
     * senza, la serializzazione JSON dell'endpoint poteva girare mentre il job di
     * manutenzione modificava la coda (ConcurrentModificationException).
     *
     * @return La lista degli eventi pendenti, dal più vecchio al più recente.
     */
    public synchronized List<EventoBufferizzato> getBuffer() {
        return new ArrayList<>(bufferEventi);
    }
}
