package it.uni.reti2;

/**
 * Il generatore degli eventi del tempo della stazione: a ogni giro dell'orologio (ogni
 * sensori.controllo.secondi) manda a GestoreEventi un CONTROLLO_SENSORI, ed è lì che si vede
 * quali sensori hanno smesso di battere. Non decide niente e non scrive niente: come
 * l'omonima classe del treno, fa solo passare il tempo.
 *
 * <p>Il periodo è molto più corto del timeout dei sensori, così il ritardo con cui ci si
 * accorge del guasto è dominato dal timeout e non dalla frequenza del controllo.</p>
 */
public class GeneratoreEventiTempo {

    /** Dove vanno gli eventi. */
    GestoreEventi gestore;

    /** Un giro di controllo: lo chiama l'Orologio, su un suo thread. */
    public void controllaSensori() {
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.CONTROLLO_SENSORI));
    }
}
