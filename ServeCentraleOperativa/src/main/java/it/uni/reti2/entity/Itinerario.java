package it.uni.reti2.entity;


/**
 * Una riga della tabella {@code Itinerari}: un <strong>itinerario</strong> ferroviario.
 *
 * <p>La tabella {@code Itinerari} contiene soltanto l'ID dell'itinerario e il
 * capolinea. La composizione effettiva dell'itinerario (quali tratte
 * lo compongono e in quale ordine) è modellata dalla tabella associativa
 * {@code Itinerario_Tratta}, le cui righe sono {@link ItinerarioTratta}.</p>
 *
 * <p>I campi {@code nome}, {@code stazioni}, {@code attivo} e {@code treniIds}
 * non esistono nel DB (il repository non li legge e non li scrive) ma sono utilizzati dal
 * {@code RestApiGateway} per costruire al volo i DTO JSON che il frontend
 * si aspetta per la pagina "Gestione Tratte".</p>
 *
 * @see it.uni.reti2.entity.ItinerarioTratta
 * @see it.uni.reti2.gateway.RestApiGateway#getTratte()
 */
public class Itinerario {

    /** Identificativo univoco dell'itinerario (es. "IT1_MI_NA"). */
    public String id;

    /**
     * La stazione che <strong>chiude il servizio</strong> su questo itinerario, oppure
     * null (il caso normale) se il percorso si ripete all'infinito, andata e ritorno.
     *
     * <p>Deve essere uno dei due estremi dell'elenco delle stazioni, e quale dei due
     * cambia la corsa: l'ultima chiude dopo la sola andata, la prima dopo andata e
     * ritorno. La verifica sta in {@code RestApiGateway}, che e' l'unico punto da cui il
     * campo viene scritto.</p>
     *
     * <p>E' un id "nudo" e non un oggetto {@link Stazione} apposta: gli itinerari finiscono
     * nella cache in RAM appesi ai treni e da li' vengono serializzati in JSON per il
     * frontend, che dell'itinerario vuole solo l'id del capolinea. Il nome della stazione,
     * quando serve, lo rilegge il gateway.</p>
     */
    public String capolineaId;

    // ──────────────────────────────────────────────────────────────
    // Campi di compatibilità con il frontend (NON persistiti).
    // Vengono popolati dinamicamente da RestApiGateway.getTratte().
    // ──────────────────────────────────────────────────────────────

    /** Nome descrittivo (es. "Milano-Bologna-Firenze-Roma-Napoli"), calcolato dal gateway. */
    public String nome;

    /** Elenco serializzato delle stazioni attraversate, per il frontend. */
    public String stazioni;

    /** Flag che indica se la tratta è attualmente operativa. */
    public boolean attivo;

    /** Lista serializzata degli ID dei treni assegnati a questo itinerario. */
    public String treniIds;
}
