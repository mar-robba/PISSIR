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
 * <h3>Campi persistiti</h3>
 * <p>Oltre a {@code id}, {@code risolto} e {@code operatore}, vengono persistiti anche
 * {@code tipo}, {@code severita}, {@code sorgenteTipo}, {@code sorgenteId}, {@code messaggio}
 * e i timestamp di apertura/risoluzione (colonne {@code ts_apertura}/{@code ts_risoluzione}).
 * Vengono popolati da {@code IngestionService} al momento della ricezione del messaggio MQTT
 * (o dal {@code FaultMonitor} per i guasti rilevati automaticamente) e letti da
 * {@code RestApiGateway} per comporre i DTO JSON destinati al frontend.</p>
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

    /** Tipologia del guasto (es. "stazione_guasta", "treno_fermo", "sensore_offline"). */
    @Column(name = "tipo", length = 50)
    public String tipo;

    /** Gravità dell'allarme (es. "CRITICAL", "WARNING", "INFO"). */
    @Column(name = "severita", length = 20)
    public String severita;

    /** Tipo del componente sorgente che ha generato l'allarme ("STAZIONE" o "TRENO"). */
    @Column(name = "sorgenteTipo", length = 20)
    public String sorgenteTipo;

    /** ID del componente sorgente che ha generato l'allarme (treno o stazione). */
    @Column(name = "sorgenteId", length = 50)
    public String sorgenteId;

    /** Messaggio di dettaglio leggibile dall'operatore. */
    @Column(name = "messaggio", length = 500)
    public String messaggio;

    /** Istante di apertura/segnalazione dell'allarme. */
    @Column(name = "ts_apertura")
    public Instant timestamp;

    /** Istante di risoluzione dell'allarme (null se ancora aperto). */
    @Column(name = "ts_risoluzione")
    public Instant timestampRisoluzione;

    /**
     * Catena di eventi a cui il guasto appartiene.
     *
     * <p>Per un guasto che nasce da un sensore (evento primario) coincide con il proprio id: la
     * catena parte da lì. Per un guasto che è la conseguenza di un altro evento — la stazione
     * che si dichiara non percorribile perché un convoglio si è guastato sui suoi binari — è la
     * catena <b>ereditata</b> dal guasto di partenza. Serve a due cose: non contare due volte la
     * stessa avaria e poter chiedere al database che cosa ha prodotto in rete un singolo
     * guasto.</p>
     */
    @Column(name = "catena_id", length = 50)
    public String catenaId;
}
