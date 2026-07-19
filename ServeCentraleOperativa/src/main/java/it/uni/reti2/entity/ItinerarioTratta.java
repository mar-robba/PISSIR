package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/**
 * Entità JPA che modella la <strong>tabella associativa N:M</strong> tra
 * {@link Itinerario} e {@link Tratta}.
 *
 * <p>Mappa la tabella {@code Itinerario_Tratta}. Ogni riga associa un itinerario
 * a una tratta con un campo {@code ordine} che indica la posizione della tratta
 * all'interno dell'itinerario (1 = prima tratta, 2 = seconda, ecc.).</p>
 *
 * <p>La chiave primaria è <strong>composita</strong>, implementata tramite la classe
 * interna {@link Id} annotata con {@link Embeddable}.</p>
 *
 * @see it.uni.reti2.entity.Itinerario
 * @see it.uni.reti2.entity.Tratta
 */
@Entity
@Table(name = "Itinerario_Tratta")
public class ItinerarioTratta extends PanacheEntityBase {

    /**
     * Chiave primaria composita (id_itinerario, id_Tratta).
     * Implementata come classe {@link Id} con {@code @EmbeddedId}.
     */
    @EmbeddedId
    public Id id;

    /**
     * Posizione ordinale della tratta nell'itinerario.
     * Es.: ordine=1 significa che è la prima tratta percorsa dal convoglio
     * in quel determinato itinerario.
     */
    @Column(name = "ordine", nullable = false)
    public int ordine;

    /**
     * Riferimento all'itinerario padre.
     * {@code insertable=false, updatable=false} perché la FK è già gestita dalla chiave composita.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_itinerario", insertable = false, updatable = false)
    public Itinerario itinerario;

    /**
     * Riferimento alla tratta componente.
     * {@code insertable=false, updatable=false} perché la FK è già gestita dalla chiave composita.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_Tratta", insertable = false, updatable = false)
    public Tratta tratta;

    /**
     * Classe interna che implementa la <strong>chiave primaria composita</strong>
     * della tabella associativa {@code Itinerario_Tratta}.
     *
     * <p>Deve implementare {@link Serializable} e sovrascrivere {@code equals/hashCode}
     * come richiesto dalla specifica JPA per le chiavi composite.</p>
     */
    @Embeddable
    public static class Id implements Serializable {

        /** FK verso {@code Itinerari.id_itinerario}. */
        @Column(name = "id_itinerario", length = 50)
        public String idItinerario;

        /** FK verso {@code Tratte.id_Tratta}. */
        @Column(name = "id_Tratta", length = 50)
        public String idTratta;

        /** Costruttore vuoto richiesto da JPA. */
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
