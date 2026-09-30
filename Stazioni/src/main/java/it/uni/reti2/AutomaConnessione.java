package it.uni.reti2;

/**
 * L'automa del collegamento con la Centrale (S4): COLLEGATA o ISOLATA, cioè il valore di
 * DBLocale.connessioneCentrale.
 *
 * <p>È la leva della demo del caching locale: ci si passa solo con le POST
 * /stazione/rete/offline e /rete/online, per staccare e riattaccare il nodo dalla Centrale
 * senza dover spegnere davvero il broker. Da isolata la stazione continua a lavorare:
 * transiti e allarmi si accodano nel LocalBuffer, che il consumatore non svuota, e l'heartbeat
 * non parte, quindi la Centrale smette di sentirla e la dichiara OFFLINE.</p>
 *
 * <p>Una caduta vera del broker non passa da qui: la assorbe la coda, perché l'evento che
 * non riesce a partire resta in testa e il consumatore riprova quando Paho si è ricollegato.
 * Prima un invio fallito metteva il collegamento a false, e siccome a rimetterlo a true era
 * solo la POST /rete/online, dopo un riavvio del broker la stazione restava isolata finché
 * qualcuno non la chiamava a mano.</p>
 */
public class AutomaConnessione {

    /** Stato del nodo: il flag del collegamento. */
    DBLocale dbLocale;

    /** @return true se transiti, allarmi e heartbeat possono partire verso la Centrale. */
    public boolean collegata() {
        return dbLocale.connessioneCentrale;
    }

    /** COLLEGATA -> ISOLATA. */
    public void isola() {
        dbLocale.connessioneCentrale = false;
    }

    /** ISOLATA -> COLLEGATA: il consumatore del buffer se ne accorge da solo e fa ripartire la coda. */
    public void ricollega() {
        dbLocale.connessioneCentrale = true;
    }
}
