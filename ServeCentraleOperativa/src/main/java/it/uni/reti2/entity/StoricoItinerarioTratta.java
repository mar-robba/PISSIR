package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per le <strong>tratte di un itinerario storicizzato</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Itinerari_Tratte}, che sta a
 * {@link StoricoItinerario} come {@link ItinerarioTratta} sta a {@link Itinerario}:
 * una riga per ogni tratta percorsa, con il suo posto nella sequenza.</p>
 *
 * <p><b>Perché serve.</b> Nel modello corrente l'itinerario non ha un percorso proprio,
 * ce l'ha la tabella associativa, e quella si può riscrivere in qualunque momento
 * dall'editor degli itinerari. Senza questa copia, lo storico di un viaggio finito
 * mostrerebbe il percorso che l'itinerario ha adesso e non quello che il convoglio ha
 * davvero fatto — e se l'itinerario viene cancellato non mostrerebbe più niente.</p>
 *
 * <p>L'unico collegamento della riga è {@link #storicoItinerarioId}, cioè la riga padre
 * dentro lo storico: è un legame fra due tabelle di storico, non verso lo stato corrente,
 * e per questo è l'unico vincolo che RF02.7 lascia in piedi (nel DDL è dichiarato con
 * {@code ON DELETE CASCADE}, le due righe nascono e muoiono insieme).</p>
 *
 * @see it.uni.reti2.entity.StoricoItinerario
 * @see it.uni.reti2.persistence.RailwayRepository#registraAssegnazioneItinerario
 */
@Entity
@Table(name = "Storico_Itinerari_Tratte")
public class StoricoItinerarioTratta extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_itinerario_tratta")
    public Long id;

    /** Riga di {@link StoricoItinerario} a cui appartiene questa tratta. */
    @Column(name = "id_storico_itinerario", nullable = false)
    public Long storicoItinerarioId;

    /** Posizione della tratta nell'itinerario (1 = la prima percorsa). */
    @Column(name = "ordine", nullable = false)
    public int ordine;

    /** Identificativo della tratta (riferimento logico verso {@code Tratte}). */
    @Column(name = "id_Tratta", nullable = false, length = 50)
    public String trattaId;

    /** Identificativo della stazione di partenza della tratta. */
    @Column(name = "id_stazione_partenza", length = 50)
    public String stazionePartenzaId;

    /** Nome della stazione di partenza al momento del viaggio. */
    @Column(name = "nome_stazione_partenza", length = 100)
    public String nomeStazionePartenza;

    /** Identificativo della stazione di arrivo della tratta. */
    @Column(name = "id_stazione_arrivo", length = 50)
    public String stazioneArrivoId;

    /** Nome della stazione di arrivo al momento del viaggio. */
    @Column(name = "nome_stazione_arrivo", length = 100)
    public String nomeStazioneArrivo;

    /** Tempo di percorrenza nominale che la tratta aveva allora, in minuti. */
    @Column(name = "tempoPercorrenzaMinuti")
    public Integer tempoPercorrenzaMinuti;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Copia una tratta dell'itinerario dentro lo storico, con il posto che occupava nella
     * sequenza e il tempo di percorrenza che aveva allora: se domani l'amministratore
     * cambia quel tempo, il viaggio già fatto continua a raccontare il proprio.
     *
     * @param idStoricoItinerario La riga di {@link StoricoItinerario} appena scritta.
     * @param riga                La tratta dell'itinerario da copiare.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoItinerarioTratta fotografiaDi(Long idStoricoItinerario, ItinerarioTratta riga) {
        StoricoItinerarioTratta storico = new StoricoItinerarioTratta();
        storico.storicoItinerarioId = idStoricoItinerario;
        storico.ordine = riga.ordine;
        storico.trattaId = riga.tratta.id;
        storico.stazionePartenzaId = riga.tratta.stazionePartenza.id;
        storico.nomeStazionePartenza = riga.tratta.stazionePartenza.nome;
        storico.stazioneArrivoId = riga.tratta.stazioneArrivo.id;
        storico.nomeStazioneArrivo = riga.tratta.stazioneArrivo.nome;
        storico.tempoPercorrenzaMinuti = riga.tratta.tempoPercorrenzaMinuti;
        return storico;
    }
}
