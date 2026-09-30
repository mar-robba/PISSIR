package it.uni.reti2;

import java.time.Instant;

/**
 * L'automa dello stato operativo della stazione (S10): dice se il nodo è ONLINE o GUASTA,
 * cioè il valore di DBLocale.stato, insieme ai due istanti dell'ultimo fault, alla causa del
 * guasto (DBLocale.guastoDaSensori) e al registro dei guasti ancora aperti (la tabella
 * Guasto_Locale_Momentaneo del db locale).
 *
 * <p>Gli stati sono due e non quattro come nella cache della Centrale: OFFLINE e MANUTENZIONE
 * sono giudizi che si possono dare solo da fuori (RF02.3.4).</p>
 *
 * <p>Come gli automi del treno non decide niente da solo e non chiama nessuno: offre le
 * guardie (metodi che leggono e rispondono) e le mosse (metodi che scrivono e basta), e le
 * chiama GestoreEventi nell'ordine giusto. È l'unica classe che scrive queste variabili.</p>
 */
public class AutomaStatoStazione {

    /** Classificazione dei guasti nati dai sensori muti, dichiarata anche alla Centrale. */
    public static final String TIPO_GUASTO_SENSORE = "sensore_offline";

    /** Classificazione di tutti gli altri guasti della stazione, quando nessuno ne dichiara una. */
    public static final String TIPO_GUASTO_STAZIONE = "stazione_guasta";

    /** Stato del nodo: qui si leggono e si scrivono lo stato, i due istanti e la causa. */
    DBLocale dbLocale;

    /** Il db locale: il registro dei guasti aperti. */
    PersistenzaLocale persistenza;

    // ===============================================================
    // LETTURE
    // ===============================================================

    /** @return true se la stazione è operativa. */
    public boolean online() {
        return "ONLINE".equals(dbLocale.getStato());
    }

    /** @return true se la stazione è inagibile e trattiene i treni. */
    public boolean guasta() {
        return "GUASTA".equals(dbLocale.getStato());
    }

    /** @return true se nel registro c'è ancora aperto almeno un guasto dei sensori. */
    public boolean guastiDeiSensoriAperti() {
        return persistenza.contaGuastiApertiDiTipo(TIPO_GUASTO_SENSORE) > 0;
    }

    // ===============================================================
    // GUARDIE
    // ===============================================================

    /**
     * ONLINE -> GUASTA si fa solo da ONLINE. Da GUASTA un guasto nuovo si aggiunge a quello
     * che c'è senza cambiare stato: se lo si riscrivesse, un secondo allarme a stazione già
     * guasta sposterebbe in avanti l'inizio del fault, e il tempo di fuori servizio
     * risulterebbe più corto del vero.
     */
    public boolean puoGuastarsi() {
        return online();
    }

    /** GUASTA -> ONLINE con il RESOLVED della Centrale: vale qualunque sia la causa. */
    public boolean puoTornareOnline() {
        return guasta();
    }

    /**
     * GUASTA -> ONLINE senza che nessuno lo decida: vale solo se la stazione è guasta
     * soltanto per i sensori muti. Se nel frattempo si è aggiunto un altro guasto, il ritorno
     * dei keepalive non basta, e a rimetterla in servizio sarà la Centrale.
     */
    public boolean puoRientrareDaSola() {
        return guasta() && dbLocale.guastoDaSensori;
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /**
     * ONLINE -> GUASTA: comincia il fault.
     *
     * @param perSensori true se il guasto nasce dai sensori muti, false per tutti gli altri.
     * @param adesso     L'istante del guasto.
     */
    public void guasta(boolean perSensori, Instant adesso) {
        dbLocale.guastoDaSensori = perSensori;
        dbLocale.scriviStato("GUASTA", adesso, dbLocale.getFineUltimoFault());
    }

    /**
     * La stazione resta GUASTA, ma si aggiunge un guasto che non è dei sensori: da qui in
     * poi il ritorno dei loro battiti non basta più a rimetterla in servizio.
     */
    public void aggiungiAltraCausa() {
        dbLocale.guastoDaSensori = false;
    }

    /**
     * Annota un guasto nel registro locale, aperto.
     *
     * @return L'id locale del guasto, -1 se la persistenza è spenta o la scrittura fallisce.
     */
    public long annotaGuasto(String tipo, String descrizione, Instant adesso) {
        return persistenza.registraGuasto(tipo, descrizione, adesso);
    }

    /**
     * I sensori sono tornati tutti a battere, ma la stazione resta GUASTA per un altro
     * motivo: nel registro si chiudono i soli guasti dei sensori, gli altri restano aperti.
     */
    public void chiudiGuastiDeiSensori(Instant adesso) {
        persistenza.risolviGuastiApertiDiTipo(TIPO_GUASTO_SENSORE, adesso);
    }

    /**
     * GUASTA -> ONLINE: finisce il fault e si chiudono tutti i guasti locali ancora aperti.
     */
    public void tornaOnline(Instant adesso) {
        dbLocale.guastoDaSensori = false;
        persistenza.risolviGuastiAperti(adesso);
        dbLocale.scriviStato("ONLINE", dbLocale.getInizioUltimoFault(), adesso);
    }

    /**
     * Stato iniziale della causa, al riavvio del nodo. Lo stato GUASTA torna da disco, ma la
     * causa è un campo in RAM: si ricostruisce dal registro dei guasti, e la stazione risulta
     * guasta per i soli sensori se ha dei guasti dei sensori aperti e nessun altro. Lo chiama
     * il main una volta sola, dopo DBLocale.ripristinaDaDisco().
     */
    public void ripristinaCausa() {
        dbLocale.guastoDaSensori = guasta()
                && guastiDeiSensoriAperti()
                && persistenza.contaGuastiApertiTranne(TIPO_GUASTO_SENSORE) == 0;
    }
}
