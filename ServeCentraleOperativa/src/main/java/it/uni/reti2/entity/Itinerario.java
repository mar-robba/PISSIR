package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * Entità JPA che rappresenta un <strong>itinerario</strong> ferroviario.
 *
 * <p>Mappa la tabella {@code Itinerari}, che contiene esclusivamente l'ID
 * dell'itinerario. La composizione effettiva dell'itinerario (quali tratte
 * lo compongono e in quale ordine) è modellata dalla tabella associativa
 * {@code Itinerario_Tratta} tramite l'entità {@link ItinerarioTratta}.</p>
 *
 * <p>I campi {@code nome}, {@code stazioni}, {@code attivo} e {@code treniIds}
 * sono annotati {@link Transient}: non esistono nel DB ma sono utilizzati dal
 * {@code RestApiGateway} per costruire al volo i DTO JSON che il frontend
 * si aspetta per la pagina "Gestione Tratte".</p>
 *
 * @see it.uni.reti2.entity.ItinerarioTratta
 * @see it.uni.reti2.gateway.RestApiGateway#getTratte()
 */
@Entity
@Table(name = "Itinerari")
public class Itinerario extends PanacheEntityBase {

    /** Identificativo univoco dell'itinerario (es. "IT1_MI_NA"). */
    @Id
    @Column(name = "id_itinerario", length = 50)
    public String id;

    // ──────────────────────────────────────────────────────────────
    // Campi di compatibilità con il frontend (NON persistiti).
    // Vengono popolati dinamicamente da RestApiGateway.getTratte().
    // ──────────────────────────────────────────────────────────────

    /** Nome descrittivo (es. "Milano-Bologna-Firenze-Roma-Napoli"), calcolato dal gateway. */
    @Transient
    public String nome;

    /** Elenco serializzato delle stazioni attraversate, per il frontend. */
    @Transient
    public String stazioni;

    /** Flag che indica se la tratta è attualmente operativa. */
    @Transient
    public boolean attivo;

    /** Lista serializzata degli ID dei treni assegnati a questo itinerario. */
    @Transient
    public String treniIds;
}
