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
 * Vengono popolati da {@code GuastiService} al momento della ricezione del messaggio MQTT
 * (o su richiesta del {@code FaultMonitor} per i guasti rilevati automaticamente) e letti da
 * {@code RestApiGateway} per comporre i DTO JSON destinati al frontend.</p>
 *
 * @see it.uni.reti2.entity.StoricoGuasto
 * @see it.uni.reti2.elaboration.GuastiService
 */
@Entity
@Table(name = "Guasti_Pervenuti_da_treni_o_Staz")
public class Guasto extends PanacheEntityBase {

    // ──────────────────────────────────────────────────────────────
    // VOCABOLARIO DEI GUASTI
    //
    // Sono i valori che finiscono davvero nelle colonne di questa tabella, quindi
    // stanno sull'entita' e non nel servizio che la scrive: chi legge un guasto
    // (le API REST, la WebSocket, i test) ha bisogno di riconoscerli tanto quanto
    // chi lo crea, e prima per averli doveva importare l'IngestionService.
    // ──────────────────────────────────────────────────────────────

    /** Valore della colonna origine per un guasto dichiarato da un nodo sul campo. */
    public static final String ORIGINE_SEGNALATO_CAMPO = "SEGNALATO_CAMPO";

    /** Valore della colonna origine per un guasto che la Centrale ha dedotto da sola. */
    public static final String ORIGINE_DEDOTTO_CENTRALE = "DEDOTTO_CENTRALE";

    /**
     * Prefisso dei messaggi dei guasti aperti dal watchdog quando una stazione smette
     * di battere: permette alla Centrale di riconoscerli come propri e di chiuderli da
     * sola al ritorno dell'heartbeat, senza toccare i guasti segnalati dalla stazione,
     * che invece li chiude un operatore.
     */
    public static final String MSG_HEARTBEAT_PERSO = "Heartbeat assente:";

    /**
     * Prefisso dei messaggi dei guasti che il watchdog apre quando un convoglio resta
     * fermo fuori da una stazione. Stesso scopo di {@link #MSG_HEARTBEAT_PERSO} per i
     * treni: distingue il guasto dedotto dall'avaria di bordo dichiarata dal convoglio.
     */
    public static final String MSG_TRENO_FERMO = "Convoglio fermo:";

    /**
     * Dice all'operatore se il guasto lo ha dichiarato il campo o lo ha dedotto la
     * Centrale. Il ripiego sul prefisso del messaggio mantiene leggibili anche gli
     * allarmi creati prima che la colonna origine esistesse.
     */
    public static String originePerOperatore(Guasto guasto) {
        boolean dedotto = ORIGINE_DEDOTTO_CENTRALE.equals(guasto.origine)
                || (guasto.origine == null && guasto.messaggio != null
                    && (guasto.messaggio.startsWith(MSG_HEARTBEAT_PERSO)
                        || guasto.messaggio.startsWith(MSG_TRENO_FERMO)));
        return dedotto ? ORIGINE_DEDOTTO_CENTRALE : ORIGINE_SEGNALATO_CAMPO;
    }

    /** true se il messaggio porta il marcatore dei guasti dedotti dal watchdog. */
    public boolean dedottoConPrefisso(String prefisso) {
        return messaggio != null && messaggio.startsWith(prefisso);
    }

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

    /** Origine dell'allarme: segnalato dal campo oppure dedotto dalla Centrale. */
    @Column(name = "origine", length = 30)
    public String origine;

    /** Messaggio di dettaglio leggibile dall'operatore. */
    @Column(name = "messaggio", length = 500)
    public String messaggio;

    /** Istante di apertura/segnalazione dell'allarme. */
    @Column(name = "ts_apertura")
    public Instant timestamp;

    /** Istante di risoluzione dell'allarme (null se ancora aperto). */
    @Column(name = "ts_risoluzione")
    public Instant timestampRisoluzione;
}
