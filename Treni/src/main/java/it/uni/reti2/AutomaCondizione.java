package it.uni.reti2;

/**
 * L'automa della condizione del convoglio (T6): dice COME sta il treno, cioè il valore di
 * TrainDB.condizione. Gli stati sono tre: REGOLARE, EMERGENZA (ci si va con un guasto di
 * bordo e se ne esce quando la Centrale lo dichiara risolto) e SOPPRESSO, in cui si arriva
 * da tutti e due gli altri con lo STOP della Centrale e da cui non si esce più.
 *
 * <p>Come gli altri automi non decide niente da solo e non chiama nessuno: offre le
 * guardie (metodi che leggono e basta) e le mosse (metodi che scrivono e basta), e le
 * chiama GestoreEventi nell'ordine giusto. È l'unica classe che scrive la condizione.</p>
 */
public class AutomaCondizione {

    /** Stato del treno: qui si legge e si scrive solo la condizione (e si azzera la velocità). */
    TrainDB trainDB;

    // ===============================================================
    // LETTURE (le usano anche le guardie degli altri automi)
    // ===============================================================

    /** @return true se il convoglio è in servizio e può muoversi. */
    public boolean regolare() {
        return "REGOLARE".equals(trainDB.condizione);
    }

    /** @return true se il convoglio è fermo per un'avaria di bordo. */
    public boolean inEmergenza() {
        return "EMERGENZA".equals(trainDB.condizione);
    }

    /** @return true se la corsa è stata cancellata. */
    public boolean soppresso() {
        return "SOPPRESSO".equals(trainDB.condizione);
    }

    // ===============================================================
    // GUARDIE
    // ===============================================================

    /**
     * Un convoglio già in emergenza ci resta per l'avaria che l'ha fermato, e uno soppresso
     * resta soppresso: in emergenza ci va solo un treno regolare.
     */
    public boolean puoAndareInEmergenza() {
        return regolare();
    }

    /** Dall'emergenza si esce solo con la risoluzione del guasto di bordo. */
    public boolean puoRientrare() {
        return inEmergenza();
    }

    /** La soppressione vale da qualsiasi condizione, tranne ovviamente da soppresso. */
    public boolean puoEssereSoppresso() {
        return !soppresso();
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /** REGOLARE -> EMERGENZA: il convoglio si ferma dov'è, anche a metà tratta. */
    public void vaiInEmergenza() {
        trainDB.condizione = "EMERGENZA";
        trainDB.velocita = 0; // il tachimetro va a zero subito, senza aspettare la telemetria
    }

    /** EMERGENZA -> REGOLARE: il treno riprende dalla fase in cui era rimasto. */
    public void rientra() {
        trainDB.condizione = "REGOLARE";
    }

    /** -> SOPPRESSO: la corsa è cancellata e il convoglio non si muove più. */
    public void sopprimi() {
        trainDB.condizione = "SOPPRESSO";
        trainDB.velocita = 0;
    }
}
