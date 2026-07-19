package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli stati delle stazioni</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Stato_Stazioni}. Ogni record rappresenta
 * una "fotografia" dello stato di una stazione in un determinato istante,
 * creata tipicamente quando arriva un heartbeat MQTT o quando avviene
 * un cambio di stato significativo (es. ONLINE → GUASTA).</p>
 *
 * @see it.uni.reti2.entity.Stazione
 * @see it.uni.reti2.ingestion.IngestionService#onHeartbeat
 */
@Entity
@Table(name = "Storico_Stato_Stazioni")
public class StoricoStatoStazione extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_stazione")
    public Long id;

    /** Riferimento alla stazione di cui si sta storicizzando lo stato. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_stazione", nullable = false)
    public Stazione stazione;

    /** Nome della stazione al momento della storicizzazione (denormalizzato per consultazione rapida). */
    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    /** Tipo funzionale della stazione al momento del record (capolinea/partenza/normale). */
    @Column(name = "tipoCapolineaPartenzaoNormale", length = 50)
    public String tipo;

    /** Flag di operatività: {@code true} = funzionante, {@code false} = fuori servizio. */
    @Column(name = "funzionanteONo")
    public Boolean funzionanteONo;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
