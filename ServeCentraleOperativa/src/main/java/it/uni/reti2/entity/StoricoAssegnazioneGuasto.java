package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico delle assegnazioni degli operatori ai guasti</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Assegnazioni_Guasti}. Ogni record traccia
 * l'assegnazione di un operatore ({@link Utente}) alla gestione di un guasto
 * ({@link Guasto}), registrando i timestamp di inizio e fine intervento.</p>
 *
 * <p>Questo storico è fondamentale per l'audit trail delle operazioni di manutenzione
 * e per calcolare metriche di performance (es. MTTR — Mean Time To Repair).</p>
 *
 * @see it.uni.reti2.entity.Guasto
 * @see it.uni.reti2.entity.Utente
 */
@Entity
@Table(name = "Storico_Assegnazioni_Guasti")
public class StoricoAssegnazioneGuasto extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale generata dal database. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_assegnazione")
    public Long id;

    /** Riferimento al guasto oggetto dell'assegnazione. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_Guasto", nullable = false)
    public Guasto guasto;

    /** Riferimento all'operatore assegnato alla risoluzione del guasto. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_utente", nullable = false)
    public Utente utente;

    /** Istante in cui l'operatore è stato assegnato al guasto. */
    @Column(name = "ts_assegnazione", nullable = false)
    public Instant tsAssegnazione;

    /** Istante in cui il guasto è stato effettivamente risolto dall'operatore (null se ancora aperto). */
    @Column(name = "ts_risoluzione")
    public Instant tsRisoluzione;

    /** Timestamp di inserimento del record nello storico. Inizializzato automaticamente. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
