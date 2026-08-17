package it.uni.reti2.eventi;

import java.util.List;

/**
 * Vocabolario del canale condiviso {@code railway/alerts}.
 *
 * <p>Sul canale viaggiano tre famiglie di messaggi e la differenza sta tutta nella
 * <b>causa</b>, non nel contenuto:</p>
 * <ul>
 *   <li>{@link #TIPO_GUASTO}: un <i>evento primario</i>, cioè un fatto che il nodo osserva
 *       su di sé (un sensore che tace, un'avaria di bordo). Apre un guasto.</li>
 *   <li>{@link #TIPO_RESOLVED}: la chiusura di quel guasto, decisa dalla Centrale.</li>
 *   <li>{@link #TIPO_REAZIONE}: un <i>evento derivato</i>, cioè il nodo che dichiara di aver
 *       cambiato stato <b>per colpa di un altro evento</b>. NON apre nessun guasto: se lo
 *       facesse, una sola avaria vera diventerebbe N allarmi da risolvere a mano.</li>
 * </ul>
 *
 * <p>Il campo che tiene insieme una catena di conseguenze è {@link #CAMPO_CATENA}: contiene
 * l'identificativo del guasto primario che l'ha originata e viene <b>ereditato</b> da tutti i
 * messaggi che discendono da quel guasto, anche quando un nodo ripubblica la propria reazione
 * come un GUASTO normale (è il caso della stazione che diventa non percorribile perché un
 * convoglio si è guastato sui suoi binari: per i treni quel messaggio è un primario come un
 * altro, ma porta con sé la catena di partenza).</p>
 *
 * <p>La regola che fa terminare le catene è una sola: <b>un nodo reagisce al massimo una volta
 * per ogni catena</b>. I nodi sono un insieme finito, quindi una catena produce al massimo un
 * messaggio per nodo e si esaurisce da sola. Lato Centrale la regola è applicata da
 * {@link RegistroCatene}, lato campo dagli insiemi delle catene già viste dei nodi.</p>
 *
 * <p>Qui stanno anche le due traduzioni fra il vocabolario dei nodi e quello della Centrale:
 * il campo parla di {@code IN_VIAGGIO} o {@code BLOCCATO_GUASTO_STAZIONE}, il database ha un
 * {@code CHECK} che ammette quattro valori. Chi traduce è la Centrale, perché è lei a conoscere
 * il proprio schema.</p>
 */
public final class VocabolarioEventi {

    // ── Tipi di evento (campo discriminante "tipoEvento") ──────────────────────
    /** Evento primario: il nodo dichiara un fatto osservato su di sé. */
    public static final String TIPO_GUASTO = "GUASTO";
    /** Chiusura di un guasto, pubblicata dalla Centrale. */
    public static final String TIPO_RESOLVED = "RESOLVED";
    /** Evento derivato: il nodo dichiara di aver cambiato stato per causa altrui. */
    public static final String TIPO_REAZIONE = "REAZIONE";

    // ── Nomi dei campi comuni ──────────────────────────────────────────────────
    public static final String CAMPO_TIPO_EVENTO = "tipoEvento";
    public static final String CAMPO_SORGENTE_TIPO = "sorgenteTipo";
    public static final String CAMPO_SORGENTE_ID = "sorgenteId";
    public static final String CAMPO_GUASTO_ID = "guastoId";
    public static final String CAMPO_TIMESTAMP = "timestamp";
    /** Identificativo della catena: l'id del guasto primario che l'ha originata. */
    public static final String CAMPO_CATENA = "catenaId";
    /** Tipo del nodo che ha causato la reazione (l'anello precedente della catena). */
    public static final String CAMPO_CAUSA_TIPO = "causaTipo";
    /** Identificativo del nodo che ha causato la reazione. */
    public static final String CAMPO_CAUSA_ID = "causaId";
    /** Stato dichiarato dal nodo che reagisce, nel vocabolario del nodo. */
    public static final String CAMPO_NUOVO_STATO = "nuovoStato";
    /** Stato da cui il nodo proviene: serve a scrivere la riga di storico come cambiamento. */
    public static final String CAMPO_STATO_PRECEDENTE = "statoPrecedente";
    /** Descrizione leggibile della reazione, per il log e per l'operatore. */
    public static final String CAMPO_MOTIVO = "motivo";
    /**
     * {@code true} quando il nodo <b>entra</b> nella catena (si blocca), {@code false} quando ne
     * <b>esce</b> (si sblocca). Senza questo campo la regola "una reazione per catena" bloccherebbe
     * anche il messaggio di rientro, e i nodi resterebbero registrati su catene ormai chiuse.
     */
    public static final String CAMPO_ATTIVA = "attiva";

    // ── Tipi di nodo ───────────────────────────────────────────────────────────
    public static final String NODO_STAZIONE = "STAZIONE";
    public static final String NODO_TRENO = "TRENO";
    public static final String NODO_TRATTA = "TRATTA";
    public static final String NODO_OPERATORE = "OPERATORE";

    // ── Percorribilità di una tratta (RF02.1.2.2.2) ────────────────────────────
    /** L'arco è libero: i convogli ci passano. */
    public static final String TRATTA_PERCORRIBILE = "PERCORRIBILE";
    /** L'arco è occupato da un'avaria: chi lo deve imboccare si ferma. */
    public static final String TRATTA_IMPERCORRIBILE = "IMPERCORRIBILE";

    /** Valori ammessi dal CHECK sulla colonna {@code Treni.stato}. */
    private static final List<String> STATI_TRENO_VALIDI =
            List.of("attivo", "fermo", "rotto", "in manutenzione");

    private VocabolarioEventi() {
        // classe di sole costanti e funzioni pure
    }

    /**
     * Traduce lo stato dichiarato da un convoglio (vocabolario del digital twin) in uno dei
     * quattro valori ammessi dal database. Le fasi che il database non conosce
     * ({@code BLOCCATO_GUASTO_STAZIONE}, {@code SENZA_ITINERARIO}) diventano "fermo": il
     * <i>perché</i> non va perso, ma finisce nelle colonne di causa dello storico, non qui.
     *
     * @param rawStato Stato dichiarato dal nodo.
     * @return Uno dei valori ammessi dal CHECK, "fermo" se lo stato non è riconosciuto.
     */
    public static String normalizzaStatoTreno(String rawStato) {
        if ("IN_VIAGGIO".equalsIgnoreCase(rawStato)) return "attivo";
        if ("FERMO".equalsIgnoreCase(rawStato)) return "fermo";
        // Le due fasi di blocco cadrebbero comunque nel "fermo" finale, ma stanno scritte qui
        // apposta: sono il vocabolario del digital twin e la traduzione è una decisione, non un
        // caso non gestito. Il perché del blocco non si perde, sta nelle colonne di causa.
        if ("BLOCCATO_GUASTO_STAZIONE".equalsIgnoreCase(rawStato)) return "fermo";
        if ("BLOCCATO_GUASTO_TRATTA".equalsIgnoreCase(rawStato)) return "fermo";
        if ("EMERGENZA".equalsIgnoreCase(rawStato)) return "rotto";
        if ("SOPPRESSO".equalsIgnoreCase(rawStato)) return "in manutenzione";
        String normalizzato = rawStato == null ? "" : rawStato.trim().toLowerCase();
        return STATI_TRENO_VALIDI.contains(normalizzato) ? normalizzato : "fermo";
    }

    /**
     * Converte lo stato interno della stazione nel valore atteso dal frontend per il campo
     * "status" degli eventi WebSocket.
     *
     * @param stato Stato interno (ONLINE / GUASTA / MANUTENZIONE / OFFLINE).
     * @return Il valore usato dall'interfaccia.
     */
    public static String statoStazionePerFrontend(String stato) {
        if ("ONLINE".equalsIgnoreCase(stato)) return "operativa";
        if ("GUASTA".equalsIgnoreCase(stato)) return "guasta";
        if ("MANUTENZIONE".equalsIgnoreCase(stato)) return "manutenzione";
        return "offline";
    }
}
