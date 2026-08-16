package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli itinerari</strong> assegnati ai treni.
 *
 * <p>Mappa la tabella {@code Storico_Itinerari}. Traccia ogni assegnazione
 * di un itinerario a un convoglio, con i relativi timestamp di inizio e
 * fine percorrenza. Permette di ricostruire la cronologia dei viaggi
 * effettuati da ciascun treno nel tempo.</p>
 *
 * <h3>Niente chiavi esterne (RF02.7)</h3>
 * <p>L'itinerario nel database è solo un identificativo: il percorso vero sta nella
 * associativa {@code Itinerario_Tratta}, e l'amministratore può rifarlo da capo quando
 * vuole (la PUT su /api/tratte cancella e ricrea le righe). Un riferimento al solo id
 * racconterebbe quindi il percorso di oggi, non quello che il convoglio ha davvero
 * percorso: per questo la riga porta la descrizione del percorso e le sue tratte
 * vengono copiate una per una in {@link StoricoItinerarioTratta}.</p>
 *
 * @see it.uni.reti2.entity.Itinerario
 * @see it.uni.reti2.entity.StoricoItinerarioTratta
 */
@Entity
@Table(name = "Storico_Itinerari")
public class StoricoItinerario extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_itinerario")
    public Long id;

    /** Identificativo dell'itinerario assegnato al treno (riferimento logico). */
    @Column(name = "id_itinerario", nullable = false, length = 50)
    public String itinerarioId;

    /** Identificativo del convoglio che ha percorso l'itinerario (è anche il suo nome). */
    @Column(name = "id_convoglio", nullable = false, length = 50)
    public String trenoId;

    /** Percorso in chiaro ("Milano Centrale - Bologna Centrale - Firenze SMN - Roma Termini"). */
    @Column(name = "descrizione_percorso", length = 1000)
    public String descrizionePercorso;

    /** Quante tratte componevano l'itinerario al momento dell'assegnazione. */
    @Column(name = "numero_tratte")
    public Integer numeroTratte;

    /** Timestamp di inizio assegnazione dell'itinerario al treno. */
    @Column(name = "ts_assegnazione", nullable = false)
    public Instant tsAssegnazione;

    /** Timestamp di completamento del percorso (null se ancora in corso). */
    @Column(name = "ts_completamento")
    public Instant tsCompletamento;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();
}
