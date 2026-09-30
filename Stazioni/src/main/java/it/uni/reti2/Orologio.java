package it.uni.reti2;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il battito della stazione: fa girare i compiti periodici del processo, ognuno col suo
 * periodo. Sono tre: il giro di controllo dei keepalive dei sensori (GeneratoreEventiTempo,
 * ogni sensori.controllo.secondi), il keepalive dei sensori simulati (SimulatoreSensori, ogni
 * sensori.heartbeat.secondi) e l'heartbeat verso la Centrale (HeartbeatGenerator, ogni 10
 * secondi).
 *
 * <p>Sotto c'è uno ScheduledExecutorService del JDK con un thread per compito, così un
 * compito lento non ritarda gli altri. Con scheduleAtFixedRate lo stesso compito non gira mai
 * due volte in parallelo: se un giro dura più del periodo, i successivi partono in ritardo.
 * È la stessa classe del nodo Treno, dove però i compiti girano tutti ogni secondo.</p>
 */
public class Orologio {

    private static final Logger LOG = Logger.getLogger(Orologio.class.getName());

    private final ScheduledExecutorService esecutore =
            Executors.newScheduledThreadPool(3, compito -> new Thread(compito, "orologio"));

    /**
     * Fa girare un compito a intervalli regolari, a partire da adesso.
     *
     * <p>Il compito è chiamato dentro un try/catch: con scheduleAtFixedRate la prima
     * eccezione che esce dal compito ne cancella tutte le esecuzioni successive, e la stazione
     * resterebbe senza quel compito per sempre senza dirlo a nessuno (senza heartbeat, per
     * esempio, che per la Centrale vuol dire una stazione OFFLINE).</p>
     *
     * @param nome Come chiamarlo nei log se qualcosa va storto.
     * @param periodoSecondi Ogni quanti secondi.
     * @param compito Cosa fare.
     */
    public void ogni(String nome, long periodoSecondi, Runnable compito) {
        esecutore.scheduleAtFixedRate(() -> {
            try {
                compito.run();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Errore nel compito periodico '" + nome + "': " + e.getMessage(), e);
            }
        }, 0, periodoSecondi, TimeUnit.SECONDS);
    }

    /** Ferma tutti i compiti. Serve ai test, che accendono e spengono una stazione per prova. */
    public void ferma() {
        esecutore.shutdownNow();
    }
}
