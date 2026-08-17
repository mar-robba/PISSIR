package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import it.uni.reti2.eventi.CausaEvento;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli stati dei treni</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Stato_Treni}. Ogni record cattura
 * lo stato operativo di un convoglio in un dato istante: stato (attivo, fermo, rotto, ecc.),
 * itinerario corrente e posizione attuale lungo la tratta.</p>
 *
 * <p>La riga si scrive SOLO quando lo stato cambia davvero, non a ogni frame di
 * telemetria: è la regola di registrazione di RF02.7 (si registrano i cambiamenti,
 * non i campionamenti). Per questo la riga porta anche lo stato precedente: da sola
 * racconta il cambiamento e non solo il punto di arrivo.</p>
 *
 * <p>Niente chiave esterna verso {@code Treni} (RF02.7): il convoglio è indicato con il
 * suo identificativo, che è anche il suo nome, e la storia resta consultabile anche se
 * quel convoglio viene eliminato dalla flotta.</p>
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

    /**
     * Identificativo del convoglio di cui si sta storicizzando lo stato (riferimento
     * logico). È anche il nome del convoglio, quindi non serve una colonna in più.
     */
    @Column(name = "id_convoglio", nullable = false, length = 50)
    public String trenoId;

    /** Stato del treno al momento della storicizzazione (es. "attivo", "fermo", "rotto"). */
    @Column(name = "stato", nullable = false, length = 30)
    public String stato;

    /** Stato da cui il convoglio proviene (null alla prima riga scritta per quel treno). */
    @Column(name = "stato_precedente", length = 30)
    public String statoPrecedente;

    /** ID dell'itinerario corrente (denormalizzato per query storiche rapide). */
    @Column(name = "itinerario", length = 50)
    public String itinerarioId;

    /** ID della tratta o stazione in cui si trovava il treno (denormalizzato). */
    @Column(name = "PosizioneAttualeTrattaOStazione", length = 50)
    public String posizioneId;

    /** Descrizione leggibile della posizione ("Milano Centrale -> Bologna Centrale"). */
    @Column(name = "descrizione_posizione", length = 255)
    public String descrizionePosizione;

    /**
     * Tipo del nodo che ha causato il cambiamento (STAZIONE, TRENO, TRATTA, OPERATORE), null se
     * il convoglio ha cambiato stato per conto suo. Riferimento logico, niente FK: vale la regola
     * degli storici di RF02.7.
     */
    @Column(name = "causa_tipo", length = 20)
    public String causaTipo;

    /** Identificativo del nodo che ha causato il cambiamento (riferimento logico). */
    @Column(name = "causa_id", length = 50)
    public String causaId;

    /**
     * Catena di eventi a cui il cambiamento appartiene, cioè l'identificativo del guasto primario
     * che l'ha originata. È la colonna che permette di chiedere al database che cosa ha prodotto
     * in tutta la rete un singolo guasto.
     */
    @Column(name = "catena_id", length = 50)
    public String catenaId;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di storico a partire dal convoglio, che deve avere GIÀ lo stato
     * nuovo: i tre punti che storicizzano un cambio di stato (telemetria, risoluzione di un
     * allarme, soppressione) copiavano gli stessi campi a mano, e bastava dimenticarne uno
     * per avere righe di storico diverse a seconda di chi le aveva scritte.
     *
     * @param treno            Il convoglio con lo stato nuovo già assegnato.
     * @param statoPrecedente  Lo stato che aveva prima, letto dal chiamante prima di sovrascriverlo.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoStatoTreno fotografiaDi(Treno treno, String statoPrecedente) {
        return fotografiaDi(treno, statoPrecedente, null);
    }

    /**
     * Come sopra, ma registrando anche <b>perché</b> il convoglio ha cambiato stato: è la riga
     * che serve quando il cambiamento non nasce dal convoglio ma è la conseguenza di un evento
     * di un altro nodo (la stazione che diventa non percorribile, la corsa soppressa
     * dall'operatore).
     *
     * @param treno            Il convoglio con lo stato nuovo già assegnato.
     * @param statoPrecedente  Lo stato che aveva prima.
     * @param causa            Chi ha causato il cambiamento e a quale catena appartiene (può essere null).
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoStatoTreno fotografiaDi(Treno treno, String statoPrecedente, CausaEvento causa) {
        StoricoStatoTreno storico = new StoricoStatoTreno();
        storico.trenoId = treno.id;
        storico.stato = treno.stato;
        storico.statoPrecedente = statoPrecedente;
        storico.itinerarioId = treno.itinerario != null ? treno.itinerario.id : null;
        if (treno.posizioneAttualeTratta != null) {
            storico.posizioneId = treno.posizioneAttualeTratta.id;
            storico.descrizionePosizione = treno.posizioneAttualeTratta.descrizione();
        }
        if (causa != null) {
            storico.causaTipo = causa.tipo();
            storico.causaId = causa.id();
            storico.catenaId = causa.catenaId();
        }
        return storico;
    }
}
