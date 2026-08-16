package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli stati delle stazioni</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Stato_Stazioni}. Ogni record rappresenta
 * una "fotografia" dello stato di una stazione nell'istante in cui è cambiato
 * (es. ONLINE → GUASTA): non a ogni heartbeat, altrimenti sarebbero circa 8.600
 * righe identiche al giorno per stazione.</p>
 *
 * <p>Niente chiave esterna verso {@code Stazione} (RF02.7): la stazione è indicata
 * con l'identificativo, il nome e il tipo che aveva in quel momento. Sono proprio le
 * informazioni che permettono di rileggere la storia di una stazione che nel frattempo
 * è stata rinominata o eliminata dall'anagrafica.</p>
 *
 * @see it.uni.reti2.entity.Stazione
 * @see it.uni.reti2.ingestion.IngestionService#onHeartbeat
 */
@Entity
@Table(name = "Storico_Stato_Stazioni")
public class StoricoStatoStazione extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_stazione")
    public Long id;

    /** Identificativo della stazione di cui si sta storicizzando lo stato (riferimento logico). */
    @Column(name = "id_stazione", nullable = false, length = 50)
    public String stazioneId;

    /** Nome della stazione al momento della storicizzazione. */
    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    /** Tipo funzionale della stazione al momento del record (capolinea/partenza/normale). */
    @Column(name = "tipoCapolineaPartenzaoNormale", length = 50)
    public String tipo;

    /**
     * Stato operativo per esteso (ONLINE / OFFLINE / GUASTA / MANUTENZIONE).
     * Il solo booleano {@code funzionanteONo} schiacciava su due valori il vocabolario a
     * quattro stati usato dalla Centrale: una stazione in manutenzione e una spenta
     * risultavano la stessa cosa.
     */
    @Column(name = "stato", length = 30)
    public String stato;

    /** Stato da cui la stazione proviene (null alla prima riga scritta per quella stazione). */
    @Column(name = "stato_precedente", length = 30)
    public String statoPrecedente;

    /** Flag di operatività: {@code true} = funzionante, {@code false} = fuori servizio. */
    @Column(name = "funzionanteONo")
    public Boolean funzionanteONo;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di storico copiando dalla stazione i dati che la identificano
     * (id, nome, tipo) e registrando il passaggio di stato.
     *
     * @param stazione        La stazione interessata dal cambiamento.
     * @param stato           Lo stato nuovo.
     * @param statoPrecedente Lo stato che aveva prima.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoStatoStazione fotografiaDi(Stazione stazione, String stato, String statoPrecedente) {
        StoricoStatoStazione storico = new StoricoStatoStazione();
        storico.stazioneId = stazione.id;
        storico.nome = stazione.nome;
        storico.tipo = stazione.tipoCapolineaPartenzaoNormale;
        storico.stato = stato;
        storico.statoPrecedente = statoPrecedente;
        storico.funzionanteONo = !"GUASTA".equalsIgnoreCase(stato) && !"OFFLINE".equalsIgnoreCase(stato);
        return storico;
    }
}
