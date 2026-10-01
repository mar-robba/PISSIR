package it.uni.reti2.elaboration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il generatore degli eventi del tempo: il watchdog della Centrale Operativa.
 *
 * <p>Ogni {@value #PERIODO_SECONDI} secondi manda al GestoreEventi tre eventi, che servono a
 * rilevare le anomalie che i nodi del campo non possono segnalare da soli (una stazione che
 * smette di battere perché è caduta la connessione non può dire di essere caduta):</p>
 * <ul>
 *   <li>CONTROLLO_HEARTBEAT: stazioni silenti da troppo tempo → OFFLINE + guasto automatico;</li>
 *   <li>CONTROLLO_TRENI_FERMI: treni immobili fuori dalle stazioni → guasto automatico;</li>
 *   <li>FOTOGRAFIA: lo stato completo della rete inviato via WebSocket al frontend.</li>
 * </ul>
 *
 * <p>Questa classe scandisce soltanto il tempo, come il tick del treno: che cosa succede a
 * ogni scatto lo decide il gestore, con le stesse guardie e mosse di tutti gli altri
 * eventi.</p>
 *
 * <p>I tre compiti girano su un solo thread ("watchdog"), uno dopo l'altro, e ognuno ha il
 * suo try/catch: un'eccezione che uscisse dal compito fermerebbe per sempre la sua
 * ripetizione (è così che funziona {@link ScheduledExecutorService}). Un compito che dura più
 * del periodo non si sovrappone a se stesso: il giro successivo parte quando il precedente è
 * finito, come il {@code ConcurrentExecution.SKIP} di prima.</p>
 */
public class GeneratoreEventiTempo {

    private static final Logger LOG = Logger.getLogger(GeneratoreEventiTempo.class.getName());

    /** Ogni quanto scatta il watchdog. */
    private static final long PERIODO_SECONDI = 10;

    /** Chi riceve gli eventi. */
    public GestoreEventi gestore;

    private ScheduledExecutorService orologio;

    /** Fa partire il watchdog. Lo chiama il main dopo aver caricato la cache. */
    public void avvia() {
        orologio = Executors.newSingleThreadScheduledExecutor(compito -> {
            Thread thread = new Thread(compito, "watchdog");
            thread.setDaemon(true); // non deve impedire la chiusura del processo
            return thread;
        });
        programma(EventoCentrale.Tipo.CONTROLLO_HEARTBEAT);
        programma(EventoCentrale.Tipo.CONTROLLO_TRENI_FERMI);
        programma(EventoCentrale.Tipo.FOTOGRAFIA);
    }

    /** Ferma il watchdog (lo usano i test). */
    public void ferma() {
        if (orologio != null) {
            orologio.shutdownNow();
        }
    }

    private void programma(EventoCentrale.Tipo tipo) {
        orologio.scheduleAtFixedRate(() -> scatta(tipo), PERIODO_SECONDI, PERIODO_SECONDI, TimeUnit.SECONDS);
    }

    private void scatta(EventoCentrale.Tipo tipo) {
        try {
            gestore.gestisci(EventoCentrale.di(tipo));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "❌ Errore del watchdog su " + tipo, e);
        }
    }
}
