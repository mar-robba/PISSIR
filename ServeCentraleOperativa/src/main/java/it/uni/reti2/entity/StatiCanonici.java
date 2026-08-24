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
 * canonici digitati da un form. I due metodi restano quindi separati, ognuno con la
 * sua regola di ripiego, ma stanno finalmente nello stesso file: prima erano due
 * metodi omonimi in due classi diverse ({@code IngestionService} e
 * {@code RestApiGateway}) che facevano cose diverse.</p>
 */
public final class StatiCanonici {

    /** Valori ammessi dal CHECK sulla colonna Treni.stato. */
    public static final List<String> TRENO =
            List.of("attivo", "fermo", "rotto", "in manutenzione");

    /** Valori con cui la Centrale descrive una stazione. */
    public static final String STAZIONE_ONLINE = "ONLINE";
    public static final String STAZIONE_GUASTA = "GUASTA";
    public static final String STAZIONE_MANUTENZIONE = "MANUTENZIONE";
    public static final String STAZIONE_OFFLINE = "OFFLINE";

    private StatiCanonici() {
    }

    /**
     * Converte lo stato dichiarato da un convoglio sul topic della telemetria.
     * IN_VIAGGIO&rarr;attivo, FERMO&rarr;fermo, EMERGENZA&rarr;rotto, SOPPRESSO&rarr;in manutenzione.
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
}
