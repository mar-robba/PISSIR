package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico dei transiti</strong> dei treni nelle stazioni.
 *
 * <p>Mappa la tabella {@code Storico_Transiti}. Ogni record storicizza
 * un passaggio fisico di un convoglio attraverso una stazione, registrando
 * la tratta percorsa e i tempi di entrata/uscita.</p>
 *
 * <p>Complementare alla tabella live {@code Transiti} (entità {@link Transito}),
 * questa tabella è progettata per analisi offline e reportistica storica.</p>
 *
 * <h3>Niente chiavi esterne (RF02.7)</h3>
 * <p>Stazione, convoglio e tratta NON sono più relazioni JPA ma semplici identificativi
 * accompagnati dal nome: un passaggio avvenuto è un fatto, e deve restare leggibile anche
 * dopo che la stazione o il convoglio che lo ha prodotto è stato eliminato dall'anagrafica.
 * Con la chiave esterna l'unico modo di cancellare una stazione era buttare via la sua
 * storia (lo faceva davvero {@code RestApiGateway.deleteStazione}).</p>
 *
 * @see it.uni.reti2.entity.Transito
 * @see it.uni.reti2.ingestion.IngestionService#onTransit
 */
@Entity
@Table(name = "Storico_Transiti")
public class StoricoTransito extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_transito")
    public Long id;

    /** ID del transito originale (riferimento logico verso la tabella live). */
    @Column(name = "id_transito", nullable = false, length = 50)
    public String idTransito;

    /** Identificativo della stazione attraversata dal convoglio (riferimento logico). */
    @Column(name = "id_stazione", nullable = false, length = 50)
    public String stazioneId;

    /** Nome della stazione al momento del passaggio: è quello che rende leggibile la riga. */
    @Column(name = "nome_stazione", length = 100)
    public String nomeStazione;

    /**
     * Identificativo del convoglio che ha effettuato il transito. Non serve una colonna
     * "nome" a parte: l'id del treno È il nome del convoglio (es. "IC 351").
     */
    @Column(name = "id_convoglio", nullable = false, length = 50)
    public String trenoId;

    /** Identificativo della tratta su cui si è verificato il transito (opzionale). */
    @Column(name = "id_Tratta", length = 50)
    public String trattaId;

    /** Descrizione leggibile della tratta ("Milano Centrale -> Bologna Centrale"). */
    @Column(name = "descrizione_tratta", length = 255)
    public String descrizioneTratta;

    /** Istante di entrata del convoglio nella stazione. */
    @Column(name = "tempoEntrata", nullable = false)
    public Instant tempoEntrata;

    /** Istante di uscita del convoglio dalla stazione (null se ancora presente). */
    @Column(name = "tempoUscita")
    public Instant tempoUscita;

    /**
     * Ritardo del convoglio (in minuti) nell'istante del passaggio, copiato dal
     * {@link Transito} corrispondente. È il dato che la pagina Transiti deve mostrare:
     * leggere il ritardo dalla cache farebbe vedere su ogni riga il ritardo corrente
     * del treno, uguale per tutti i suoi transiti e azzerato a ogni riavvio.
     */
    @Column(name = "ritardoMinuti")
    public Integer ritardoMinuti;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di storico copiando dal transito tutto quello che serve a
     * rileggerla senza join: gli identificativi e le etichette (nome della stazione,
     * descrizione della tratta).
     *
     * @param transito Il transito appena aperto o appena chiuso.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoTransito fotografiaDi(Transito transito) {
        StoricoTransito storico = new StoricoTransito();
        storico.idTransito = transito.id;
        storico.trenoId = transito.treno != null ? transito.treno.id : null;
        if (transito.stazione != null) {
            storico.stazioneId = transito.stazione.id;
            storico.nomeStazione = transito.stazione.nome;
        }
        if (transito.tratta != null) {
            storico.trattaId = transito.tratta.id;
            storico.descrizioneTratta = transito.tratta.descrizione();
        }
        storico.tempoEntrata = transito.tempoEntrata;
        storico.tempoUscita = transito.tempoUscita;
        storico.ritardoMinuti = transito.ritardoMinuti;
        return storico;
    }
}
