package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli itinerari</strong> assegnati ai treni.
 *
 * <p>Mappa la tabella {@code Storico_Itinerari}. Traccia ogni assegnazione
 * di un itinerario a un convoglio, con i relativi timestamp di inizio e
 * fine percorrenza. Permette di ricostruire la cronologia dei viaggi
 * effettuati da ciascun treno nel tempo.</p>
 *
 * @see it.uni.reti2.entity.Itinerario
 * @see it.uni.reti2.entity.Treno
 */
@Entity
@Table(name = "Storico_Itinerari")
public class StoricoItinerario extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_itinerario")
    public Long id;

    /** Riferimento all'itinerario assegnato al treno. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_itinerario", nullable = false)
    public Itinerario itinerario;

    /** Riferimento al convoglio che ha percorso l'itinerario. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_convoglio", nullable = false)
    public Treno treno;

    /** Timestamp di inizio assegnazione dell'itinerario al treno. */
    @Column(name = "ts_assegnazione", nullable = false)
    public Instant tsAssegnazione;

    /** Timestamp di completamento del percorso (null se ancora in corso). */
    @Column(name = "ts_completamento")
    public Instant tsCompletamento;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
