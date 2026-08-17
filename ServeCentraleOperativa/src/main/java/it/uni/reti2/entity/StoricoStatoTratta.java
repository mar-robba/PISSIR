package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import it.uni.reti2.eventi.CausaEvento;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico della percorribilità delle tratte</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Stato_Tratte}. Una riga per ogni CAMBIO di percorribilità
 * di un arco della rete: PERCORRIBILE → IMPERCORRIBILE quando un convoglio si guasta mentre lo
 * sta percorrendo (RF02.1.2.2.2), e ritorno quando l'avaria viene riparata.</p>
 *
 * <p><b>Perché una tabella nuova.</b> Le tratte non avevano nessuno stato: erano solo l'arco fra
 * due stazioni, con il suo tempo di percorrenza. Un convoglio che si guastava in mezzo alla
 * campagna apriva il proprio allarme e basta, e il pezzo di rete che occupava continuava a
 * risultare libero. Per registrare il cambiamento serviva un posto dove scriverlo, ed è questo:
 * è il "tipo di nodo nuovo" previsto dallo schema degli eventi domino.</p>
 *
 * <p>Valgono le regole degli storici (RF02.7): nessuna chiave esterna verso {@code Tratte}, e
 * la riga porta con sé la descrizione dell'arco ("Milano Centrale -&gt; Bologna Centrale") così
 * resta leggibile anche se quella tratta viene poi eliminata dalla rete. Le tre colonne di causa
 * dicono <i>perché</i>: quale convoglio l'ha resa impercorribile e a quale catena appartiene.</p>
 *
 * @see it.uni.reti2.eventi.GestoreReazioni
 * @see it.uni.reti2.entity.StoricoStatoStazione
 */
@Entity
@Table(name = "Storico_Stato_Tratte")
public class StoricoStatoTratta extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_tratta")
    public Long id;

    /** Identificativo della tratta (riferimento logico, NIENTE FK). */
    @Column(name = "id_Tratta", nullable = false, length = 50)
    public String trattaId;

    /** Descrizione dell'arco al momento del fatto ("Milano Centrale -&gt; Bologna Centrale"). */
    @Column(name = "descrizione_tratta", length = 255)
    public String descrizioneTratta;

    /** Identificativo della stazione di partenza dell'arco (riferimento logico). */
    @Column(name = "id_stazione_partenza", length = 50)
    public String stazionePartenzaId;

    /** Identificativo della stazione di arrivo dell'arco (riferimento logico). */
    @Column(name = "id_stazione_arrivo", length = 50)
    public String stazioneArrivoId;

    /** Percorribilità nuova: PERCORRIBILE / IMPERCORRIBILE. */
    @Column(name = "stato", length = 30)
    public String stato;

    /** Percorribilità da cui proviene, per leggere la riga come un cambiamento. */
    @Column(name = "stato_precedente", length = 30)
    public String statoPrecedente;

    /**
     * Tipo del nodo che ha causato il cambiamento (di norma TRENO: è il convoglio guasto che
     * occupa l'arco). Riferimento logico, niente FK (RF02.7).
     */
    @Column(name = "causa_tipo", length = 20)
    public String causaTipo;

    /** Identificativo del nodo che ha causato il cambiamento (riferimento logico). */
    @Column(name = "causa_id", length = 50)
    public String causaId;

    /** Catena di eventi a cui il cambiamento appartiene (id del guasto primario). */
    @Column(name = "catena_id", length = 50)
    public String catenaId;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga copiando dalla tratta i dati che la identificano (id, descrizione,
     * stazioni estreme) e registrando il passaggio di percorribilità con la sua causa.
     *
     * @param tratta          L'arco interessato dal cambiamento.
     * @param stato           La percorribilità nuova.
     * @param statoPrecedente Quella che aveva prima.
     * @param causa           Chi l'ha provocato e a quale catena appartiene (può essere null).
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoStatoTratta fotografiaDi(Tratta tratta, String stato, String statoPrecedente,
                                                  CausaEvento causa) {
        StoricoStatoTratta storico = new StoricoStatoTratta();
        storico.trattaId = tratta.id;
        storico.descrizioneTratta = tratta.descrizione();
        storico.stazionePartenzaId = tratta.stazionePartenza != null ? tratta.stazionePartenza.id : null;
        storico.stazioneArrivoId = tratta.stazioneArrivo != null ? tratta.stazioneArrivo.id : null;
        storico.stato = stato;
        storico.statoPrecedente = statoPrecedente;
        if (causa != null) {
            storico.causaTipo = causa.tipo();
            storico.causaId = causa.id();
            storico.catenaId = causa.catenaId();
        }
        return storico;
    }
}
