package it.uni.reti2.entity;

import java.util.List;

/**
 * Il vocabolario degli stati ammessi dal database, in un posto solo.
 *
 * <p>Le colonne {@code Treni.stato} e {@code Stazione.stato} hanno un vincolo CHECK:
 * uno stato inventato non viene rifiutato quando lo si scrive in memoria, ma molto
 * piu' tardi, al commit, e li' l'eccezione risale fino al connettore reattivo e
 * spegne il canale MQTT. Per questo la conversione verso il vocabolario canonico non
 * e' un abbellimento ma una difesa, e deve essere la stessa ovunque si scriva uno
 * stato.</p>
 *
 * <p><b>Due vocabolari in ingresso, non uno.</b> Il campo e il pannello
 * dell'amministratore parlano lingue diverse: dal campo arrivano gli stati del
 * protocollo MQTT (IN_VIAGGIO, EMERGENZA...), dalla web app arrivano gia' i valori
 * canonici digitati da un form. I due metodi sono quindi separati, ognuno con la
 * sua regola di ripiego, nello stesso file.</p>
 */
public final class StatiCanonici {

    /** Valori ammessi dal CHECK sulla colonna Treni.stato. */
    public static final List<String> TRENO =
            List.of("attivo", "fermo", "rotto", "in manutenzione", "capolinea");

    /**
     * Il convoglio ha finito la corsa al capolinea dell'itinerario e non riparte da solo.
     *
     * <p>E' uno stato terminale come "in manutenzione", ma per un motivo opposto: non c'e'
     * niente di rotto, il servizio e' semplicemente finito. Se ne esce solo assegnando al
     * treno un itinerario diverso.</p>
     */
    public static final String TRENO_CAPOLINEA = "capolinea";

    /** Valori con cui la Centrale descrive una stazione. */
    public static final String STAZIONE_ONLINE = "ONLINE";
    public static final String STAZIONE_GUASTA = "GUASTA";
    public static final String STAZIONE_MANUTENZIONE = "MANUTENZIONE";
    public static final String STAZIONE_OFFLINE = "OFFLINE";

    private StatiCanonici() {
    }

    /**
     * Converte lo stato dichiarato da un convoglio sul topic della telemetria.
     * IN_VIAGGIO&rarr;attivo, FERMO&rarr;fermo, EMERGENZA&rarr;rotto, SOPPRESSO&rarr;in manutenzione,
     * CAPOLINEA&rarr;capolinea (corsa finita all'estremo marcato dell'itinerario).
     *
     * <p>Qualunque valore non riconosciuto (campo assente compreso) diventa "fermo":
     * un convoglio che dice qualcosa di incomprensibile e' un convoglio di cui non si
     * sa niente, e la scelta prudente e' considerarlo non in marcia.</p>
     */
    public static String trenoDalCampo(String dichiarato) {
        if ("IN_VIAGGIO".equalsIgnoreCase(dichiarato)) return "attivo";
        if ("FERMO".equalsIgnoreCase(dichiarato)) return "fermo";
        if ("EMERGENZA".equalsIgnoreCase(dichiarato)) return "rotto";
        if ("SOPPRESSO".equalsIgnoreCase(dichiarato)) return "in manutenzione";
        if ("CAPOLINEA".equalsIgnoreCase(dichiarato)) return TRENO_CAPOLINEA;
        String normalizzato = dichiarato == null ? "" : dichiarato.trim().toLowerCase();
        return TRENO.contains(normalizzato) ? normalizzato : "fermo";
    }

    /**
     * Converte lo stato scelto dall'amministratore nel pannello di gestione: il form
     * manda gia' i valori canonici, qui si tolgono solo spazi e maiuscole.
     *
     * @return null se il campo non era nel corpo della richiesta, che nelle PUT
     *         parziali significa "questo campo non lo sto toccando".
     */
    public static String trenoDallOperatore(String scelto) {
        return scelto == null ? null : scelto.trim().toLowerCase();
    }

    /**
     * Converte lo stato di una stazione scelto dall'amministratore, accettando il
     * sinonimo "OPERATIVA" con cui la web app chiama ONLINE.
     *
     * @return null se il campo non era nel corpo della richiesta.
     */
    public static String stazioneDallOperatore(String scelto) {
        if (scelto == null) return null;
        String normalizzato = scelto.trim().toUpperCase();
        return "OPERATIVA".equals(normalizzato) ? STAZIONE_ONLINE : normalizzato;
    }

    /**
     * Dice se in questo stato la stazione trattiene i convogli.
     *
     * <p>GUASTA e MANUTENZIONE sono due situazioni diverse per l'operatore (una e' un
     * guasto da lavorare, l'altra e' la squadra gia' sul posto) ma per chi viaggia sono
     * la stessa identica cosa: quei binari non si possono attraversare, quindi il treno
     * che sta per arrivarci si ferma e quello che e' gia' dentro resta fermo. OFFLINE
     * invece non basta: una stazione che non batte e' un nodo di cui non si sa niente,
     * e se e' davvero caduta ci pensa il watchdog ad aprirle un guasto dedotto.</p>
     */
    public static boolean stazioneTrattieneITreni(String stato) {
        return STAZIONE_GUASTA.equalsIgnoreCase(stato) || STAZIONE_MANUTENZIONE.equalsIgnoreCase(stato);
    }

    /**
     * Dice se sulla stazione c'e' una squadra al lavoro.
     *
     * <p>MANUTENZIONE non e' uno stato alternativo a GUASTA: vale "guasta piu' squadra
     * inviata" (RF01.4.1), quindi riscriverla GUASTA non e' una transizione ma la perdita
     * di un'informazione. Finche' l'intervento dura lo stato non lo tocca nessun evento
     * del campo: un guasto nuovo su quella stazione e' un allarme in piu' su un nodo che
     * e' gia' chiuso e per di piu' preso in carico, non un cambio di stato.</p>
     *
     * <p>Serve a {@code TrafficLogicEngine.cambiaStatoStazione}, che e' il punto in cui la
     * regola viene applicata una volta sola per tutti.</p>
     */
    public static boolean interventoInCorso(String stato) {
        return STAZIONE_MANUTENZIONE.equalsIgnoreCase(stato);
    }
}
