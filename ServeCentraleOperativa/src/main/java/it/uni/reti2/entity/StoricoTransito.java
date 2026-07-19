package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico dei transiti</strong> dei treni nelle stazioni.
 *
 * <p>Mappa la tabella {@code Storico_Transiti}. Ogni record storicizza
 * un passaggio fisico di un convoglio attraverso una stazione, registrando
 * la tratta percorsa e i tempi di entrata/uscita.</p>
 *
 * <p>Complementare alla tabella live {@code Transiti} (entità {@link Transito}),
 * questa tabella è progettata per analisi offline e reportistica storica.</p>
 *
 * @see it.uni.reti2.entity.Transito
 * @see it.uni.reti2.ingestion.IngestionService#onTransit
 */
@Entity
@Table(name = "Storico_Transiti")
public class StoricoTransito extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_transito")
    public Long id;

    /** ID del transito originale (FK logica verso la tabella live). */
    @Column(name = "id_transito", nullable = false, length = 50)
    public String idTransito;

    /** Stazione attraversata dal convoglio. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_stazione", nullable = false)
    public Stazione stazione;

    /** Convoglio che ha effettuato il transito. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_convoglio", nullable = false)
    public Treno treno;

    /** Tratta su cui si è verificato il transito (opzionale). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_Tratta")
    public Tratta tratta;

    /** Istante di entrata del convoglio nella stazione. */
    @Column(name = "tempoEntrata", nullable = false)
    public Instant tempoEntrata;

    /** Istante di uscita del convoglio dalla stazione (null se ancora presente). */
    @Column(name = "tempoUscita")
    public Instant tempoUscita;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
