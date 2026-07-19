package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA che modella un <strong>guasto</strong> pervenuto da un treno o da una stazione.
 *
 * <p>Mappa la tabella {@code Guasti_Pervenuti_da_treni_o_Staz} dello schema centrale.
 * Ogni record rappresenta una segnalazione di anomalia: può provenire dai sensori
 * di bordo di un treno (freni, motori) oppure dai sottosistemi di una stazione
 * (alimentazione, scambi).</p>
 *
 * <h3>Campi persistiti vs. transient</h3>
 * <p>Solo {@code id}, {@code risolto} e {@code operatore} sono colonne effettive in DB.
 * I campi annotati con {@link Transient} ({@code tipo}, {@code severita}, {@code sorgenteId},
 * {@code messaggio}, {@code timestamp}, {@code timestampRisoluzione}) vivono esclusivamente
 * in memoria e vengono popolati da {@code IngestionService} al momento della ricezione
 * del messaggio MQTT, e da {@code RestApiGateway} per comporre i DTO JSON destinati al frontend.
 * Questa scelta separa il modello relazionale dallo schema di comunicazione runtime.</p>
 *
 * @see it.uni.reti2.entity.StoricoGuasto
 * @see it.uni.reti2.ingestion.IngestionService#onAlert
 */
@Entity
@Table(name = "Guasti_Pervenuti_da_treni_o_Staz")
public class Guasto extends PanacheEntityBase {

    /** Identificativo univoco del guasto (es. "G1", "alert-1689773520000"). */
    @Id
    @Column(name = "id_Guasto", length = 50)
    public String id;

    /**
     * Flag di risoluzione: {@code false} = guasto aperto, {@code true} = guasto chiuso.
     * Viene settato a {@code true} dall'operatore tramite l'endpoint
     * {@code POST /api/allarmi/{id}/risolvi}.
     */
    @Column(name = "Stato_RisoltoONO", nullable = false)
    public boolean risolto = false;

    /**
     * L'operatore (utente) attualmente assegnato alla gestione di questo guasto.
     * Relazione ManyToOne verso la tabella {@code Utenti}.
     * Può essere {@code null} se nessun operatore è stato ancora assegnato.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "OperatoreCheSeNeStaOccupandoFK")
    public Utente operatore;

    // ──────────────────────────────────────────────────────────────
    // Campi @Transient: NON persistiti su DB, usati solo in-memory
    // per la comunicazione tra IngestionService, TrafficLogicEngine
    // e RestApiGateway.
    // ──────────────────────────────────────────────────────────────

    /** Tipologia del guasto (es. "STATION", "TRAIN", "SYSTEM"). */
    @Transient
    public String tipo;

    /** Gravità dell'allarme (es. "CRITICAL", "WARNING", "INFO"). */
    @Transient
    public String severita;

    /** ID del componente sorgente che ha generato l'allarme (treno o stazione). */
    @Transient
    public String sorgenteId;

    /** Messaggio di dettaglio leggibile dall'operatore. */
    @Transient
    public String messaggio;

    /** Istante di creazione dell'allarme (in-memory). */
    @Transient
    public Instant timestamp;

    /** Istante di risoluzione dell'allarme (in-memory, usato per DTO). */
    @Transient
    public Instant timestampRisoluzione;
}
