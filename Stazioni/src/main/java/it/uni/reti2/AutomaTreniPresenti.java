package it.uni.reti2;

import java.time.Instant;

/**
 * L'automa della presenza dei treni sui binari (S11): per ogni convoglio, Assente o Presente,
 * cioè la mappa DBLocale.treniPresenti, rispecchiata sulla tabella Treno del db locale. È la
 * fotografia dei binari che risponde alla GET /stazione/treni.
 *
 * <p>Ha solo mosse: un'ENTRATA mette il treno sui binari, un'USCITA lo toglie, e nessuna
 * delle due ha condizioni, perché la stazione registra quello che i sensori hanno visto.
 * Come AutomaStazioniNote nel treno, sta in una classe sua perché è l'unica strada da cui
 * passano le scritture di quella mappa, e a chiamarla è GestoreEventi.</p>
 */
public class AutomaTreniPresenti {

    /** Stato del nodo: la mappa dei treni presenti. */
    DBLocale dbLocale;

    /** Assente -> Presente: il sensore di ingresso ha visto entrare il convoglio. */
    public void entra(String trenoId, Instant adesso) {
        dbLocale.registraEntrataTreno(trenoId, adesso);
    }

    /** Presente -> Assente: il sensore di uscita l'ha visto uscire. */
    public void esce(String trenoId, Instant adesso) {
        dbLocale.registraUscitaTreno(trenoId, adesso);
    }
}
