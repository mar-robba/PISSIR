package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli stati dei treni</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Stato_Treni}. Ogni record cattura
 * lo stato operativo di un convoglio in un dato istante: stato (attivo, fermo, rotto, ecc.),
 * itinerario corrente e posizione attuale lungo la tratta.</p>
 *
 * <p>Viene creato da {@code IngestionService} alla ricezione di ogni pacchetto
 * telemetrico MQTT, garantendo la tracciabilità completa dei cambi di stato.</p>
 *
 * @see it.uni.reti2.entity.Treno
 * @see it.uni.reti2.ingestion.IngestionService#onTelemetry
 */
@Entity
@Table(name = "Storico_Stato_Treni")
public class StoricoStatoTreno extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_treno")
    public Long id;

    /** Riferimento al convoglio di cui si sta storicizzando lo stato. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_convoglio", nullable = false)
    public Treno treno;

    /** Stato del treno al momento della storicizzazione (es. "attivo", "fermo", "EMERGENZA"). */
    @Column(name = "stato", nullable = false, length = 30)
    public String stato;

    /** ID dell'itinerario corrente (denormalizzato per query storiche rapide). */
    @Column(name = "itinerario", length = 50)
    public String itinerarioId;

    /** ID della tratta o stazione in cui si trovava il treno (denormalizzato). */
    @Column(name = "PosizioneAttualeTrattaOStazione", length = 50)
    public String posizioneId;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
