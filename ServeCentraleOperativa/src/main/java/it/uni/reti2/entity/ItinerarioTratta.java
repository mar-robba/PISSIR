package it.uni.reti2.entity;

import java.util.Objects;

/**
 * Una riga della <strong>tabella associativa N:M</strong> tra
 * {@link Itinerario} e {@link Tratta}.
 *
 * <p>Corrisponde alla tabella {@code Itinerario_Tratta}. Ogni riga associa un itinerario
 * a una tratta con un campo {@code ordine} che indica la posizione della tratta
 * all'interno dell'itinerario (1 = prima tratta, 2 = seconda, ecc.).</p>
 *
 * <p>La chiave primaria è <strong>composita</strong> (id_itinerario, id_Tratta): la
 * coppia sta nella classe interna {@link Id}.</p>
 *
 * @see it.uni.reti2.entity.Itinerario
 * @see it.uni.reti2.entity.Tratta
 */
public class ItinerarioTratta {

    /**
     * Chiave primaria composita (id_itinerario, id_Tratta).
     */
    public Id id;

    /**
     * Posizione ordinale della tratta nell'itinerario.
     * Es.: ordine=1 significa che è la prima tratta percorsa dal convoglio
     * in quel determinato itinerario.
     */
    public int ordine;

    /**
     * L'itinerario padre. In scrittura conta solo {@code id.idItinerario}: la colonna
     * della chiave esterna è quella della chiave composita.
     */
    public Itinerario itinerario;

    /**
     * La tratta componente, letta con le sue due stazioni (servono per l'ordine delle
     * fermate). In scrittura conta solo {@code id.idTratta}.
     */
    public Tratta tratta;

    /**
     * Classe interna che implementa la <strong>chiave primaria composita</strong>
     * della tabella associativa {@code Itinerario_Tratta}.
     *
     * <p>{@code equals/hashCode} confrontano la coppia: due chiavi con gli stessi id sono
     * la stessa riga.</p>
     */
    public static class Id {

        /** FK verso {@code Itinerari.id_itinerario}. */
        public String idItinerario;

        /** FK verso {@code Tratte.id_Tratta}. */
        public String idTratta;

        /** Chiave vuota, da riempire campo per campo. */
        public Id() {}

        /**
         * Costruttore parametrizzato per comodità di creazione programmatica.
         * @param idItinerario ID dell'itinerario.
         * @param idTratta ID della tratta.
         */
        public Id(String idItinerario, String idTratta) {
            this.idItinerario = idItinerario;
            this.idTratta = idTratta;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Id id1 = (Id) o;
            return Objects.equals(idItinerario, id1.idItinerario) &&
                   Objects.equals(idTratta, id1.idTratta);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idItinerario, idTratta);
        }
    }
}
