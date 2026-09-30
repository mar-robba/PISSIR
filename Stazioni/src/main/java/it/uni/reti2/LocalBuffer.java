package it.uni.reti2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * LocalBuffer è la coda store and forward della stazione: transiti e allarmi diretti alla
 * Centrale passano tutti da qui, nell'ordine in cui sono nati, e ne escono solo quando il
 * broker li ha presi davvero.
 *
 * <p>È il buffer di uno schema produttore/consumatore. I produttori sono PubblicatoreTransiti
 * e PubblicatoreAlert, che GestoreEventi chiama nella fase delle azioni: accodano con
 * {@link #add} e tornano subito, senza aspettare il broker. Il consumatore è un thread solo,
 * quello di {@link ConsumatoreBuffer}: aspetta con {@link #attendiTesta()} che ci sia qualcosa,
 * prova a pubblicare la testa e, solo se ci riesce, la toglie con {@link #confermaInvio}. I
 * metodi sono tutti synchronized sullo stesso oggetto, e l'attesa del consumatore è la coppia
 * wait()/notifyAll().</p>
 *
 * <p>Il caching locale chiesto dal testo viene da sé: quando la Centrale non è raggiungibile
 * (collegamento staccato con POST /stazione/rete/offline, o broker giù) il consumatore non
 * riesce a togliere la testa, e gli eventi nuovi si accodano dietro. Al ritorno del
 * collegamento ripartono tutti, in ordine.</p>
 *
 * <p>La coda vive in RAM ma ha una copia sul db locale, nella tabella {@code bufferChache}:
 * ogni add finisce anche su disco, e la riga si cancella solo dopo l'invio. Prima un blackout
 * del nodo si portava via proprio gli eventi che la stazione non era ancora riuscita a
 * spedire, cioè il caso peggiore, perché quei transiti e quei guasti non esistevano da
 * nessun'altra parte. Adesso al riavvio la coda si ricostruisce da sola, nello stesso ordine
 * di prima.</p>
 *
 * <p>Sull'ordine: l'id degli eventi è progressivo e la lettura da disco è ordinata per id,
 * quindi il FIFO regge anche attraverso un riavvio. Serve, se no la Centrale potrebbe
 * ricevere l'USCITA di un treno prima della sua ENTRATA.</p>
 */
public class LocalBuffer {

    private static final Logger LOG = Logger.getLogger(LocalBuffer.class.getName());

    /**
     * Il db locale su file, dove la coda viene rispecchiata. Assegnato dal main.
     */
    PersistenzaLocale persistenza;

    /**
     * Evento accodato in attesa di essere consegnato alla Centrale.
     * Oltre al payload JSON viene memorizzato anche il canale di destinazione
     * ("transit" oppure "alert"), così il consumatore sa su quale topic pubblicarlo.
     *
     * @param idEvento Numero progressivo che dà l'ordine FIFO, anche dopo un riavvio.
     * @param canale   Canale logico di destinazione: "transit" o "alert" (colonna "tipo").
     * @param payload  Il payload JSON dell'evento, già pronto per il topic.
     * @param idGuasto Riferimento al guasto locale che ha generato l'evento, null se non
     *                 c'entra nessun guasto (colonna id_Guasto del modello).
     * @param idTreno  Convoglio a cui l'evento si riferisce, null per gli eventi che non
     *                 parlano di nessun treno (colonna id_Treno del modello).
     */
    public record EventoBufferizzato(long idEvento, String canale, String payload,
                                     Long idGuasto, String idTreno) {}

    /**
     * La coda vera e propria. È un ArrayDeque, cioè una coda con due estremità: i produttori
     * aggiungono in fondo, il consumatore guarda la testa con peekFirst e la toglie con
     * pollFirst solo dopo l'invio. Così l'evento che non riesce a partire resta il più vecchio,
     * e la Centrale non riceve mai una USCITA prima della relativa ENTRATA.
     */
    private final Deque<EventoBufferizzato> bufferEventi = new ArrayDeque<>();

    /**
     * Prossimo id da assegnare. Al riavvio riparte da dopo l'ultimo trovato su disco,
     * così i vecchi eventi restano davanti ai nuovi.
     */
    private long prossimoId = 1;

    /**
     * Ricarica dal db locale gli eventi che erano rimasti da spedire. La chiama il main
     * all'avvio; a rimandarli ci pensa il ConsumatoreBuffer appena parte.
     */
    public synchronized void ripristinaDaDisco() {
        List<EventoBufferizzato> pendenti = persistenza.leggiBuffer();
        for (EventoBufferizzato evento : pendenti) {
            bufferEventi.addLast(evento);
            prossimoId = Math.max(prossimoId, evento.idEvento() + 1);
        }
        if (!pendenti.isEmpty()) {
            LOG.info(String.format("💾 Ripristinati %d eventi ancora da spedire alla Centrale.", pendenti.size()));
        }
    }

    /**
     * Lato produttore: aggiunge un evento in fondo alla coda, lo scrive sul db locale e
     * sveglia il consumatore, se stava aspettando una coda vuota.
     *
     * @param canale   Canale di destinazione dell'evento ("transit" o "alert").
     * @param payload  Il payload JSON dell'evento.
     * @param idGuasto Guasto locale che ha generato l'evento, oppure null.
     * @param idTreno  Convoglio a cui l'evento si riferisce, oppure null.
     */
    public synchronized void add(String canale, String payload, Long idGuasto, String idTreno) {
        EventoBufferizzato evento = new EventoBufferizzato(prossimoId++, canale, payload, idGuasto, idTreno);
        bufferEventi.addLast(evento);
        persistenza.salvaEventoBuffer(evento);
        notifyAll();
    }

    /**
     * Lato consumatore: aspetta che la coda non sia vuota e restituisce l'evento più vecchio,
     * SENZA toglierlo. Resta in testa finché il consumatore non conferma di averlo consegnato:
     * toglierlo prima vorrebbe dire che un invio fallito, o un blackout fra l'estrazione e la
     * spedizione, si porterebbe via l'evento, che è proprio quello che questa classe deve
     * impedire.
     *
     * @return L'evento in testa alla coda.
     * @throws InterruptedException Se il thread viene interrotto mentre aspetta.
     */
    public synchronized EventoBufferizzato attendiTesta() throws InterruptedException {
        while (bufferEventi.isEmpty()) {
            wait();
        }
        return bufferEventi.peekFirst();
    }

    /**
     * Lato consumatore: l'evento in testa è stato consegnato, quindi esce dalla coda in RAM
     * e dal db locale. Da chiamare solo dopo che il broker ha accettato il payload.
     *
     * @param evento L'evento restituito da {@link #attendiTesta()} e andato a buon fine.
     */
    public synchronized void confermaInvio(EventoBufferizzato evento) {
        if (evento.equals(bufferEventi.peekFirst())) {
            bufferEventi.pollFirst();
        }
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
     * senza, la serializzazione JSON dell'endpoint poteva girare mentre il consumatore
     * modificava la coda (ConcurrentModificationException).
     *
     * @return La lista degli eventi pendenti, dal più vecchio al più recente.
     */
    public synchronized List<EventoBufferizzato> getBuffer() {
        return new ArrayList<>(bufferEventi);
    }
}
