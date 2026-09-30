package it.uni.reti2;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il battito del treno: fa girare ogni secondo i compiti periodici del processo, cioè il
 * TICK del viaggio (GeneratoreEventiTempo) e la telemetria (TrainElab).
 *
 * <p>Sotto c'è uno ScheduledExecutorService del JDK con due thread, così un compito lento
 * (il tick può aspettare fino a 5 secondi la GET dell'itinerario) non ritarda l'altro. Con
 * scheduleAtFixedRate, se un giro dura più di un secondo i successivi partono in ritardo e
 * poi arrivano uno dietro l'altro fino a recuperare: non è un problema, perché sosta e
 * avanzamento si misurano con l'orologio e non contando i tick. Lo stesso compito non gira
 * mai due volte in parallelo.</p>
 */
public class Orologio {

    private static final Logger LOG = Logger.getLogger(Orologio.class.getName());

    private final ScheduledExecutorService esecutore =
            Executors.newScheduledThreadPool(2, compito -> new Thread(compito, "orologio"));

    /**
     * Fa girare un compito una volta al secondo, a partire da adesso.
     *
     * <p>Il compito è chiamato dentro un try/catch: con scheduleAtFixedRate la prima
     * eccezione che esce dal compito ne cancella tutte le esecuzioni successive, e il treno
     * resterebbe senza tick (o senza telemetria) per sempre senza dirlo a nessuno.</p>
     *
     * @param nome Come chiamarlo nei log se qualcosa va storto.
     * @param compito Cosa fare a ogni secondo.
     */
    public void ogniSecondo(String nome, Runnable compito) {
        esecutore.scheduleAtFixedRate(() -> {
            try {
                compito.run();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Errore nel compito periodico '" + nome + "': " + e.getMessage(), e);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
}
