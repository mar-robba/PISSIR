package it.uni.reti2.persistence;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * All'avvio mette la memoria storica al passo con lo stato corrente.
 *
 * <p><b>Il problema che risolve.</b> La memoria storica registra i <em>cambiamenti</em>
 * (RF02.7): una riga si apre quando un convoglio riceve un itinerario e si chiude quando lo
 * lascia. I convogli che erano già assegnati prima che questa registrazione esistesse però
 * un cambiamento non lo faranno mai — ce l'hanno già alle spalle — e resterebbero senza
 * storia per sempre, con il risultato paradossale di uno storico che dice "nessuno sta
 * percorrendo niente" mentre i convogli viaggiano.</p>
 *
 * <p><b>Perché una classe a sé.</b> Il {@code TrafficLogicEngine} ha già un suo
 * {@code @Observes StartupEvent}, ma quello popola la cache in RAM e per dichiarazione non è
 * il posto dove si scrive sul database. Questa è una scrittura, e per giunta una tantum:
 * tenerla separata evita di infilare un INSERT dentro il caricamento della cache. Le due
 * cose sono indipendenti, quindi non importa in che ordine CDI le esegua.</p>
 *
 * <p>Gira a ogni avvio ed è innocua: al secondo giro trova le righe già aperte e non fa
 * niente.</p>
 *
 * @see it.uni.reti2.persistence.RailwayRepository#allineaItinerariPercorsi()
 */
@ApplicationScoped
public class AllineamentoStorico {

    private static final Logger LOG = Logger.getLogger(AllineamentoStorico.class);

    @Inject
    RailwayRepository repository;

    /**
     * La transazione la apre questo metodo: il repository, per scelta, non ne apre nessuna.
     *
     * @param ev L'evento di avvio di Quarkus.
     */
    @Transactional
    void onStart(@Observes StartupEvent ev) {
        int aperte = repository.allineaItinerariPercorsi();
        if (aperte > 0) {
            LOG.infof("📖 Memoria storica allineata: aperte %d righe di itinerario per convogli "
                    + "che erano già assegnati", aperte);
        }
    }
}
