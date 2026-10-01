package it.uni.reti2.elaboration;

import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;

import java.time.Instant;

/**
 * L'automa dello stato di una stazione, come lo decide la Centrale: ONLINE, GUASTA,
 * MANUTENZIONE, OFFLINE (i valori di {@link StatiCanonici}).
 *
 * <p>Le transizioni sono queste:</p>
 * <ul>
 *   <li>un evento del campo (il battito, un guasto CRITICAL segnalato) porta la stazione nello
 *       stato che dichiara, <b>tranne</b> quando è in MANUTENZIONE: la squadra sul posto la
 *       tiene chiusa finché l'intervento non viene chiuso (RF01.4.1). È la guardia
 *       {@link #accettaDalCampo};</li>
 *   <li>il watchdog la porta OFFLINE quando smette di battere ({@link #silenziosa});</li>
 *   <li>l'invio della squadra la porta in MANUTENZIONE, e la chiusura dell'ultimo guasto aperto
 *       la riporta ONLINE: sono i due comandi della Centrale, gli unici che fanno uscire una
 *       stazione dalla manutenzione;</li>
 *   <li>l'amministratore dal pannello la mette dove vuole.</li>
 * </ul>
 *
 * <p>Come gli automi del treno non decide niente da solo e non chiama nessuno: offre le guardie
 * (metodi che leggono e basta) e le mosse (metodi che scrivono e basta), e le chiama
 * GestoreEventi nell'ordine giusto. È l'unica classe che scrive {@code stazione.stato}; prima
 * lo scrivevano in cinque classi diverse e la regola della manutenzione era ripetuta a
 * memoria in due di loro.</p>
 *
 * <p>Tiene anche i dati di contorno che cambiano con gli stessi eventi: l'istante
 * dell'ultimo battito, lo stato che il nodo ci ha dichiarato e il conteggio dei treni
 * presenti.</p>
 */
public class AutomaStazione {

    /** La cache in cui vivono le stazioni. */
    public TrafficLogicEngine statoRete;

    // ===============================================================
    // LETTURE
    // ===============================================================

    /** @return true se sulla stazione c'è una squadra al lavoro. */
    public boolean inManutenzione(Stazione stazione) {
        return StatiCanonici.interventoInCorso(stazione.stato);
    }

    /** @return true se la stazione trattiene i convogli (GUASTA o MANUTENZIONE). */
    public boolean trattieneITreni(Stazione stazione) {
        return StatiCanonici.stazioneTrattieneITreni(stazione.stato);
    }

    // ===============================================================
    // GUARDIE
    // ===============================================================

    /**
     * Un evento del campo può cambiare lo stato solo se sulla stazione non c'è un intervento
     * in corso. Senza questa guardia bastava un alert ripetuto dal campo (e una stazione
     * guasta ne manda uno per ogni treno che entra) per riportare a GUASTA una stazione in
     * manutenzione; da lì il primo RESOLVED del campo la rimetteva ONLINE e i convogli
     * ripartivano con i tecnici ancora sui binari.
     *
     * @param nuovoStato Lo stato che il campo vorrebbe darle.
     */
    public boolean accettaDalCampo(Stazione stazione, String nuovoStato) {
        return !inManutenzione(stazione) || StatiCanonici.interventoInCorso(nuovoStato);
    }

    /**
     * Il watchdog dichiara OFFLINE una stazione che non batte da prima del limite. Se ne
     * salta una che è già OFFLINE (l'episodio è già stato aperto), una in MANUTENZIONE (il
     * nodo si spegne perché ci lavorano i tecnici) e una che non ha mai battuto da quando la
     * Centrale è accesa (non se ne sa niente, e marcarla caduta aprirebbe un falso guasto a
     * ogni riavvio).
     *
     * @param limite L'istante prima del quale l'ultimo battito è troppo vecchio.
     */
    public boolean silenziosa(Stazione stazione, Instant limite) {
        if (StatiCanonici.STAZIONE_OFFLINE.equalsIgnoreCase(stazione.stato) || inManutenzione(stazione)) {
            return false;
        }
        return stazione.ultimoHeartbeat != null && stazione.ultimoHeartbeat.isBefore(limite);
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /** Lo stato dichiarato dal campo (da chiamare solo se {@link #accettaDalCampo} ha detto sì). */
    public void dalCampo(Stazione stazione, String nuovoStato) {
        scrivi(stazione, nuovoStato);
    }

    /**
     * Il nodo ha battuto: si annota quando, è l'orologio del watchdog, e lo stato che ha
     * dichiarato di sé. Quello si annota sempre, anche quando {@link #accettaDalCampo} ha
     * detto di no: in MANUTENZIONE non diventa lo stato della stazione, ma resta vero che il
     * nodo si dichiara così, e la web app lo mostra accanto.
     *
     * @param dichiarato Lo stato scritto nel battito (ONLINE o GUASTA).
     */
    public void battito(Stazione stazione, Instant quando, String dichiarato) {
        stazione.ultimoHeartbeat = quando;
        stazione.statoDichiarato = dichiarato;
        statoRete.aggiornaStazione(stazione);
    }

    /** -> OFFLINE: la stazione tace da troppo (la decisione è del watchdog). */
    public void vaiOffline(Stazione stazione) {
        scrivi(stazione, StatiCanonici.STAZIONE_OFFLINE);
    }

    /** -> GUASTA: un guasto CRITICAL del nodo, o un convoglio rotto sui suoi binari. */
    public void guasta(Stazione stazione) {
        scrivi(stazione, StatiCanonici.STAZIONE_GUASTA);
    }

    /**
     * -> MANUTENZIONE: la squadra è partita. Da qui in avanti è {@link #accettaDalCampo} a
     * difendere lo stato dagli eventi del campo, finché l'intervento non viene chiuso.
     */
    public void inviaSquadra(Stazione stazione) {
        scrivi(stazione, StatiCanonici.STAZIONE_MANUTENZIONE);
    }

    /**
     * -> ONLINE: si è chiuso l'ultimo guasto aperto. L'istante del battito si rinnova, così il
     * watchdog non la ridichiara OFFLINE al primo giro per un silenzio che era del guasto.
     */
    public void tornaOnline(Stazione stazione, Instant quando) {
        stazione.ultimoHeartbeat = quando;
        scrivi(stazione, StatiCanonici.STAZIONE_ONLINE);
    }

    /**
     * Lo stato scelto a mano dall'amministratore: è un comando esplicito e passa sopra a tutto,
     * manutenzione compresa. Se toglie una stazione dall'intervento lo sta facendo apposta.
     */
    public void imponiDallOperatore(Stazione stazione, String stato) {
        scrivi(stazione, stato);
    }

    /** Un convoglio è entrato in stazione. */
    public void entraTreno(Stazione stazione) {
        stazione.treniInStazione++;
        statoRete.aggiornaStazione(stazione);
    }

    /** Un convoglio è uscito dalla stazione (il conteggio non scende sotto zero). */
    public void esceTreno(Stazione stazione) {
        if (stazione.treniInStazione > 0) {
            stazione.treniInStazione--;
        }
        statoRete.aggiornaStazione(stazione);
    }

    private void scrivi(Stazione stazione, String stato) {
        stazione.stato = stato;
        statoRete.aggiornaStazione(stazione);
    }
}
