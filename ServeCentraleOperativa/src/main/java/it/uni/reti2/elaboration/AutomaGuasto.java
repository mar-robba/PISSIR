package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Guasto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L'automa di un guasto: APERTO -> RISOLTO ({@code Guasto.risolto}).
 *
 * <p>Un guasto nasce aperto in due modi: lo segnala un nodo del campo (origine
 * SEGNALATO_CAMPO) oppure lo deduce la Centrale da sola, quando una stazione smette di battere
 * o un treno resta fermo fuori stazione (origine DEDOTTO_CENTRALE). Si chiude in tre modi: lo
 * risolve l'operatore, lo dichiara rientrato il nodo che l'aveva segnalato, oppure la
 * Centrale vede sparire la condizione che l'aveva fatto dedurre. Da risolto non si torna
 * aperto: se il problema si ripresenta è un guasto nuovo.</p>
 *
 * <p>La guardia che conta è {@link #apertoPer}: per la stessa sorgente e lo stesso tipo c'è al
 * massimo un guasto aperto. Una stazione guasta ripete l'alert a ogni treno che entra, e senza
 * questo controllo l'elenco allarmi si riempirebbe di copie dello stesso episodio.</p>
 *
 * <p>Come gli altri automi offre guardie e mosse e non chiama nessuno. Le mosse scrivono la
 * copia in cache ({@link TrafficLogicEngine}); la riga del database la scrive GestoreEventi.
 * Qui c'è anche il vocabolario con cui i guasti si classificano, che prima stava in
 * GuastiService.</p>
 */
public class AutomaGuasto {

    /**
     * Tipo del guasto che una stazione riceve quando un convoglio si ferma rotto sui suoi
     * binari. Non è un'avaria della stazione: si chiude insieme al guasto del convoglio.
     */
    public static final String TIPO_CONVOGLIO_IN_STAZIONE = "convoglio_guasto_in_stazione";

    /** I tipi che il frontend sa mostrare; qualunque altro tipo dichiarato si riconduce a uno di questi. */
    private static final List<String> TIPI_NOTI =
            List.of("sensore_offline", "stazione_guasta", "treno_fermo", "binario_guasto");

    /** La cache in cui vivono i guasti attivi. */
    public TrafficLogicEngine statoRete;

    // ===============================================================
    // CLASSIFICAZIONE
    // ===============================================================

    /**
     * Mappa il tipo di un guasto nel vocabolario del frontend.
     *
     * <p>Se il nodo ha dichiarato un tipo che il frontend conosce si usa quello, altrimenti se
     * ne deduce uno dalla sorgente. È la stessa regola per l'ingestione e per GET
     * /api/allarmi: prima era scritta due volte, in due file diversi.</p>
     */
    public static String tipoPerFrontend(String sorgenteTipo, String tipoDichiarato) {
        if (tipoDichiarato != null && !tipoDichiarato.isBlank()) {
            String tipo = tipoDichiarato.trim().toLowerCase();
            if (TIPI_NOTI.contains(tipo)) {
                return tipo;
            }
        }
        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            return "treno_fermo";
        }
        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            return "stazione_guasta";
        }
        return "sensore_offline";
    }

    // ===============================================================
    // GUARDIE
    // ===============================================================

    /** @return Il guasto ancora aperto per quella sorgente e quel tipo, o null se non ce n'è. */
    public Guasto apertoPer(String sorgenteId, String tipo) {
        return statoRete.getGuastoApertoPerSorgente(sorgenteId, tipo);
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /**
     * Compone un guasto nuovo, aperto. Non è ancora nella cache: ci entra con {@link #apri},
     * dopo che il gestore lo ha scritto sul database.
     */
    public Guasto nuovo(String tipo, String severita, String sorgenteTipo, String sorgenteId,
                        String messaggio, String origine, Instant quando) {
        Guasto guasto = new Guasto();
        guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        guasto.tipo = tipo;
        guasto.severita = severita;
        guasto.sorgenteTipo = sorgenteTipo;
        guasto.sorgenteId = sorgenteId;
        guasto.origine = origine;
        guasto.messaggio = messaggio;
        guasto.timestamp = quando;
        guasto.risolto = false;
        return guasto;
    }

    /** Compone un guasto che la Centrale ha dedotto da sola (watchdog, convoglio rotto in stazione). */
    public Guasto nuovoDedotto(String tipo, String severita, String sorgenteTipo,
                               String sorgenteId, String messaggio) {
        return nuovo(tipo, severita, sorgenteTipo, sorgenteId, messaggio,
                Guasto.ORIGINE_DEDOTTO_CENTRALE, Instant.now());
    }

    /** -> APERTO: il guasto entra fra quelli attivi. */
    public void apri(Guasto guasto) {
        statoRete.aggiungiGuasto(guasto);
    }

    /** Il campo ha segnalato di nuovo lo stesso episodio: resta aperto, cambia solo il testo. */
    public void aggiornaMessaggio(Guasto guasto, String messaggio) {
        guasto.messaggio = messaggio;
    }

    /**
     * APERTO -> RISOLTO. Si scrive sulla copia in cache, che resta nella mappa dei guasti
     * attivi (il frontend ha così il tempo di vedere che è stato risolto), e sulla copia che
     * il gestore ha letto dal database, se è un'altra: è quella che parte nelle notifiche.
     */
    public void chiudi(Guasto guasto, Instant quando) {
        guasto.risolto = true;
        guasto.timestampRisoluzione = quando;
        Guasto inCache = statoRete.getGuasto(guasto.id);
        if (inCache != null) {
            inCache.risolto = true;
            inCache.timestampRisoluzione = quando;
        }
    }
}
