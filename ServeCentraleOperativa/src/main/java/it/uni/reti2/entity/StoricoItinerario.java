package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

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

    /** Lunghezza della colonna descrizione_percorso: oltre, l'INSERT fallirebbe. */
    private static final int MAX_DESCRIZIONE = 1000;

    /**
     * Costruisce la riga di storico di un itinerario appena assegnato a un convoglio,
     * congelando il percorso che l'itinerario ha <em>in questo momento</em>: la sequenza
     * delle stazioni in chiaro e quante tratte la compongono. Le singole tratte le copia
     * poi {@link StoricoItinerarioTratta}, una riga per ciascuna.
     *
     * <p>La riga nasce aperta ({@code tsCompletamento} a null): si chiude quando il
     * convoglio smette di percorrere questo itinerario.</p>
     *
     * @param idTreno    Il convoglio a cui l'itinerario è stato assegnato.
     * @param itinerario L'itinerario assegnato.
     * @param tratte     Le sue tratte in ordine di percorrenza.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoItinerario fotografiaDi(String idTreno, Itinerario itinerario,
                                                 List<ItinerarioTratta> tratte) {
        StoricoItinerario storico = new StoricoItinerario();
        storico.trenoId = idTreno;
        storico.itinerarioId = itinerario.id;
        storico.numeroTratte = tratte.size();
        storico.descrizionePercorso = descriviPercorso(tratte);
        storico.tsAssegnazione = Instant.now();
        return storico;
    }

    /**
     * "Memoria Alfa - Memoria Bravo - Memoria Charlie": la stazione di partenza della prima
     * tratta e poi tutti gli arrivi, che è la sequenza delle stazioni toccate.
     *
     * <p>Se l'itinerario è talmente lungo da sforare la colonna la descrizione viene
     * troncata: si perde la coda della frase, non il dato, perché le tratte restano tutte
     * quante in {@link StoricoItinerarioTratta}.</p>
     *
     * @param tratte Le tratte dell'itinerario in ordine di percorrenza.
     * @return Il percorso in chiaro, oppure null se l'itinerario non ha ancora tratte.
     */
    private static String descriviPercorso(List<ItinerarioTratta> tratte) {
        if (tratte.isEmpty()) {
            return null;
        }
        StringBuilder percorso = new StringBuilder(tratte.get(0).tratta.stazionePartenza.nome);
        for (ItinerarioTratta riga : tratte) {
            percorso.append(" - ").append(riga.tratta.stazioneArrivo.nome);
        }
        if (percorso.length() > MAX_DESCRIZIONE) {
            return percorso.substring(0, MAX_DESCRIZIONE - 3) + "...";
        }
        return percorso.toString();
    }
}
