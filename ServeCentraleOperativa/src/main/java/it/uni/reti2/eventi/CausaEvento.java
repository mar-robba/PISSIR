package it.uni.reti2.eventi;

/**
 * Perché un nodo si trova nello stato in cui si trova.
 *
 * <p>È il dato che oggi manca agli storici: dal database si legge <i>che</i> un convoglio si è
 * fermato, non <i>perché</i>. Le tre informazioni sono il minimo per rispondere a domande del
 * tipo "quanti minuti di ritardo ha prodotto in rete il guasto G-123": chi ha causato il
 * cambiamento ({@code tipo} + {@code id}) e a quale catena appartiene ({@code catenaId}).</p>
 *
 * <p>Vale anche qui la regola degli storici (RF02.7): sono <b>riferimenti logici</b>, non chiavi
 * esterne. Il guasto o la stazione che hanno causato il cambiamento possono sparire
 * dall'anagrafica senza che la storia diventi illeggibile.</p>
 *
 * @param tipo      Tipo del nodo che ha causato il cambiamento (STAZIONE, TRENO, TRATTA, OPERATORE).
 * @param id        Identificativo di quel nodo.
 * @param catenaId  Identificativo della catena, cioè del guasto primario che l'ha originata.
 */
public record CausaEvento(String tipo, String id, String catenaId) {

    /**
     * Causa di un cambiamento provocato da un altro nodo.
     *
     * @param tipo     Tipo del nodo che ha causato il cambiamento.
     * @param id       Identificativo di quel nodo.
     * @param catenaId Catena di appartenenza.
     * @return La causa, oppure {@code null} se non c'è abbastanza informazione per registrarla.
     */
    public static CausaEvento di(String tipo, String id, String catenaId) {
        if ((tipo == null || tipo.isBlank()) && (id == null || id.isBlank()) && (catenaId == null || catenaId.isBlank())) {
            return null;
        }
        return new CausaEvento(vuotoComeNull(tipo), vuotoComeNull(id), vuotoComeNull(catenaId));
    }

    /**
     * Causa di un cambiamento che il nodo si è provocato da solo dichiarando un guasto:
     * la sorgente del guasto è anche la causa, e la catena parte da lì.
     *
     * @param sorgenteTipo Tipo della sorgente del guasto.
     * @param sorgenteId   Identificativo della sorgente.
     * @param catenaId     Catena del guasto (di norma l'id del guasto stesso).
     * @return La causa da scrivere sulla riga di storico.
     */
    public static CausaEvento propria(String sorgenteTipo, String sorgenteId, String catenaId) {
        return di(sorgenteTipo, sorgenteId, catenaId);
    }

    /** @return Descrizione leggibile, per i log. */
    public String descrizione() {
        return String.format("%s %s (catena %s)", tipo, id, catenaId);
    }

    private static String vuotoComeNull(String valore) {
        return valore == null || valore.isBlank() ? null : valore;
    }
}
