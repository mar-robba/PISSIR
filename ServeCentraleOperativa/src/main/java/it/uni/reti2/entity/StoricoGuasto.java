package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico dei guasti</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Guasti}. Registra una copia storica
 * dell'evoluzione di ogni guasto nel tempo: apertura, eventuale chiusura,
 * operatore assegnato e stato di risoluzione.</p>
 *
 * <p>A differenza della tabella live {@code Guasti_Pervenuti_da_treni_o_Staz}
 * (entità {@link Guasto}), questa tabella è <em>append-only</em>: un nuovo record
 * viene inserito quando il guasto viene segnalato, e aggiornato al momento
 * della risoluzione con il timestamp di chiusura.</p>
 *
 * @see it.uni.reti2.entity.Guasto
 * @see it.uni.reti2.ingestion.IngestionService#onAlert
 */
@Entity
@Table(name = "Storico_Guasti")
public class StoricoGuasto extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_guasto")
    public Long id;

    /** Riferimento al guasto originale nella tabella live. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_Guasto", nullable = false)
    public Guasto guasto;

    /** Stato di risoluzione al momento della storicizzazione. */
    @Column(name = "Stato_RisoltoONO", nullable = false)
    public boolean risolto;

    /** Operatore responsabile (può essere null se non ancora assegnato). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "OperatoreCheSeNeStaOccupandoFK")
    public Utente operatore;

    /** Timestamp di apertura/segnalazione del guasto. */
    @Column(name = "ts_apertura", nullable = false)
    public Instant tsApertura;

    /** Timestamp di chiusura/risoluzione (null se ancora aperto). */
    @Column(name = "ts_chiusura")
    public Instant tsChiusura;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
