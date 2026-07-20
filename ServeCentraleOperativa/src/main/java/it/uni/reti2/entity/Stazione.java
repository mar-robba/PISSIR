package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;

/**
 * Entità JPA che rappresenta una <strong>stazione ferroviaria</strong> nel sistema centrale.
 *
 * <p>Mappa la tabella {@code Stazione} dello schema centrale. Ogni stazione è identificata
 * da un ID univoco (es. "S1") e possiede attributi persistiti come nome e tipo
 * (capolinea/partenza/normale).</p>
 *
 * <h3>Campi in-memory (@Transient)</h3>
 * <p>Oltre ai dati persistiti, la classe mantiene in memoria informazioni
 * <em>volatili</em> come lo stato operativo, le coordinate GPS, il numero di binari
 * e il timestamp dell'ultimo heartbeat. Questi dati vengono aggiornati in tempo reale
 * da {@code IngestionService} quando arriva un heartbeat MQTT dalla stazione edge,
 * e letti da {@code RestApiGateway} e {@code TrafficLogicEngine} per alimentare
 * le API REST e la cache di sistema.</p>
 *
 * @see it.uni.reti2.ingestion.IngestionService#onHeartbeat
 * @see it.uni.reti2.elaboration.TrafficLogicEngine
 */
@Entity
@Table(name = "Stazione")
public class Stazione extends PanacheEntityBase {

    /** Identificativo univoco della stazione (es. "S1", "S2"). PK nel DB. */
    @Id
    @Column(name = "id_stazione", length = 50)
    public String id;

    /** Nome leggibile della stazione (es. "Milano Centrale", "Bologna Centrale"). */
    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    /**
     * Classificazione funzionale della stazione nella rete ferroviaria.
     * Valori ammessi dal CHECK nel DDL: {@code 'capolinea'}, {@code 'partenza'}, {@code 'normale'}.
     */
    @Column(name = "tipoCapolineaPartenzaoNormale", length = 50)
    public String tipoCapolineaPartenzaoNormale;

    /** Latitudine GPS della stazione, usata per il rendering sulla mappa. Persistita in DB. */
    @Column(name = "latitudine")
    public Double latitudine = 0.0;

    /** Longitudine GPS della stazione, usata per il rendering sulla mappa. Persistita in DB. */
    @Column(name = "longitudine")
    public Double longitudine = 0.0;

    /** Numero di binari disponibili nella stazione. Persistito in DB. */
    @Column(name = "binari")
    public Integer binari = 1;

    // ──────────────────────────────────────────────────────────────
    // Campi volatili / in-memory: NON persistiti su schema.sql.
    // Aggiornati dal ciclo di heartbeat MQTT e dalla cache
    // TrafficLogicEngine per fornire lo stato real-time al frontend.
    // ──────────────────────────────────────────────────────────────

    /** Stato operativo corrente (es. "ONLINE", "GUASTA", "MANUTENZIONE", "OFFLINE"). */
    @Transient
    public String stato = "OFFLINE";

    /** Timestamp dell'ultimo segnale di heartbeat ricevuto dalla stazione. */
    @Transient
    public Instant ultimoHeartbeat;

    /** Conteggio dei treni attualmente presenti in stazione. */
    @Transient
    public int treniInStazione;

    /** Costruttore vuoto richiesto da JPA. */
    public Stazione() {}

    /**
     * Costruttore di convenienza per la creazione programmatica.
     *
     * @param id     Identificativo univoco.
     * @param nome   Nome leggibile.
     * @param lat    Latitudine GPS.
     * @param lng    Longitudine GPS.
     * @param binari Numero di binari.
     */
    public Stazione(String id, String nome, double lat, double lng, int binari) {
        this.id = id;
        this.nome = nome;
        this.latitudine = lat;
        this.longitudine = lng;
        this.binari = binari;
        this.stato = "OFFLINE";
        this.tipoCapolineaPartenzaoNormale = "normale";
    }
}
