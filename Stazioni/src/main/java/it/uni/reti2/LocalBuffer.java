package it.uni.reti2;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * LocalBuffer implementa il meccanismo di store and forward per gli eventi generati
 * dalla Stazione (es. transiti, guasti). È fondamentale quando la connessione verso la
 * Centrale cade (Edge computing in offline mode), perché permette di accodare gli eventi
 * e ritrasmetterli al ripristino della rete.
 *
 * <p>La coda vive in RAM ma ha una copia sul db locale, nella tabella {@code bufferChache}:
 * prima un blackout del nodo si portava via proprio gli eventi che la stazione non era
 * ancora riuscita a spedire, cioè il caso peggiore, perché quei transiti e quei guasti non
 * esistevano da nessun'altra parte. Adesso ogni add finisce anche su disco e al riavvio la
 * coda si ricostruisce da sola, nello stesso ordine di prima.</p>
 *
 * <p>Sull'ordine: l'id degli eventi è progressivo e la lettura da disco è ordinata per id,
 * quindi il FIFO regge anche attraverso un riavvio. Serve, se no la Centrale potrebbe
 * ricevere l'USCITA di un treno prima della sua ENTRATA.</p>
 */
@ApplicationScoped
public class LocalBuffer {

    private static final Logger LOG = Logger.getLogger(LocalBuffer.class);

    /**
     * Il db locale su file, dove la coda viene rispecchiata.
     */
    @Inject
    PersistenzaLocale persistenza;

    /**
     * Evento accodato in attesa di ritrasmissione.
     * Oltre al payload JSON viene memorizzato anche il canale di destinazione
     * ("transit" oppure "alert"), così al momento del flush ogni evento
     * può essere reinviato sull'emitter MQTT corretto.
     *
     * @param idEvento Numero progressivo che dà l'ordine FIFO, anche dopo un riavvio.
     * @param canale   Canale logico di destinazione: "transit" o "alert" (colonna "tipo").
     * @param payload  Il payload JSON dell'evento non recapitato, già pronto per il topic.
     * @param idGuasto Riferimento al guasto locale che ha generato l'evento, null se non
     *                 c'entra nessun guasto (colonna id_Guasto del modello).
     * @param idTreno  Convoglio a cui l'evento si riferisce, null per gli eventi che non
     *                 parlano di nessun treno (colonna id_Treno del modello).
     */
    public record EventoBufferizzato(long idEvento, String canale, String payload,
                                     Long idGuasto, String idTreno) {}

    /**
     * La coda vera e propria dove vengono stoccati gli eventi.
     * Si usa un ArrayDeque (e non una LinkedList vista come Queue) perché serve
     * poter reinserire un evento anche IN TESTA: se il reinvio fallisce a metà
     * flush l'evento più vecchio deve restare il più vecchio, altrimenti la
     * Centrale riceverebbe una USCITA prima della relativa ENTRATA.
     */
    private final Deque<EventoBufferizzato> bufferEventi = new ArrayDeque<>();

    /**
     * Prossimo id da assegnare. Al riavvio riparte da dopo l'ultimo trovato su disco,
     * così i vecchi eventi restano davanti ai nuovi.
     */
    private long prossimoId = 1;

    /**
     * Ricarica dal db locale gli eventi che erano rimasti da spedire.
     * Non li rimanda: ci pensa il job periodico di HeartbeatGenerator appena vede che
     * la connessione verso la Centrale è tornata.
     */
    @PostConstruct
    void ripristinaDaDisco() {
        List<EventoBufferizzato> pendenti = persistenza.leggiBuffer();
        for (EventoBufferizzato evento : pendenti) {
            bufferEventi.addLast(evento);
            prossimoId = Math.max(prossimoId, evento.idEvento() + 1);
        }
        if (!pendenti.isEmpty()) {
            LOG.infof("💾 Ripristinati %d eventi ancora da spedire alla Centrale.", pendenti.size());
        }
    }

    /**
     * Aggiunge un nuovo evento in coda al buffer, senza riferimenti a guasti o treni.
     * Da invocare quando l'invio remoto fallisce o la stazione è offline.
     *
     * @param canale  Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload Il payload JSON dell'evento non recapitato.
     */
    public synchronized void add(String canale, String payload) {
        add(canale, payload, null, null);
    }

    /**
     * Aggiunge un nuovo evento in coda al buffer e lo scrive sul db locale.
     *
     * @param canale   Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload  Il payload JSON dell'evento non recapitato.
     * @param idGuasto Guasto locale che ha generato l'evento, oppure null.
     * @param idTreno  Convoglio a cui l'evento si riferisce, oppure null.
     */
    public synchronized void add(String canale, String payload, Long idGuasto, String idTreno) {
        EventoBufferizzato evento = new EventoBufferizzato(prossimoId++, canale, payload, idGuasto, idTreno);
        bufferEventi.addLast(evento);
        persistenza.salvaEventoBuffer(evento);
    }

    /**
     * Rimette un evento IN TESTA al buffer, davanti a tutti gli altri.
     * Serve al flush: l'evento che non è riuscito a partire deve essere
     * ritentato per primo, così l'ordine cronologico (FIFO) è rispettato.
     * Si passa l'evento intero e non canale e payload perché deve tornare indietro
     * con lo stesso id, se no perderebbe il proprio posto nella fila.
     *
     * @param evento L'evento uscito da {@link #poll()} e non recapitato.
     */
    public synchronized void addFirst(EventoBufferizzato evento) {
        bufferEventi.addFirst(evento);
    }

    /**
     * Estrae e rimuove il primo evento inserito nella coda (modalità FIFO).
     * Toglie l'evento SOLO dalla coda in RAM: la riga sul db resta finché non arriva
     * la conferma dell'invio con {@link #confermaInvio(EventoBufferizzato)}. Cancellarla
     * qui vorrebbe dire che un blackout fra l'estrazione e la spedizione si porterebbe via
     * l'evento, che è proprio quello che questa classe deve impedire.
     *
     * @return L'evento più vecchio nel buffer, oppure null se il buffer è vuoto.
     */
    public synchronized EventoBufferizzato poll() {
        return bufferEventi.pollFirst();
    }

    /**
     * Cancella dal db locale un evento che è stato consegnato davvero.
     * Da chiamare dopo che l'emitter MQTT ha accettato il payload.
     *
     * @param evento L'evento andato a buon fine.
     */
    public synchronized void confermaInvio(EventoBufferizzato evento) {
        persistenza.rimuoviEventoBuffer(evento.idEvento());
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
