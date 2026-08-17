package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico dei guasti</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Guasti}. Registra una copia storica
 * dell'evoluzione di ogni guasto nel tempo: apertura, eventuale chiusura,
 * operatore assegnato e stato di risoluzione.</p>
 *
 * <p>A differenza della tabella live {@code Guasti_Pervenuti_da_treni_o_Staz}
 * (entità {@link Guasto}), questa tabella sopravvive al guasto: la riga viene
 * inserita quando il guasto viene segnalato e completata con il timestamp di
 * chiusura al momento della risoluzione.</p>
 *
 * <h3>Niente chiavi esterne (RF02.7)</h3>
 * <p>Non c'è più il legame verso il guasto vivo né verso l'utente: al loro posto la riga
 * porta l'identificativo del guasto, la sua descrizione completa (tipo, severità, sorgente
 * con il nome, messaggio) e i dati dell'operatore. Un allarme risolto e poi archiviato
 * deve restare leggibile anche quando la riga viva non c'è più e anche se l'operatore che
 * se ne era occupato non lavora più in centrale.</p>
 *
 * @see it.uni.reti2.entity.Guasto
 * @see it.uni.reti2.ingestion.IngestionService#onAlert
 */
@Entity
@Table(name = "Storico_Guasti")
public class StoricoGuasto extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_guasto")
    public Long id;

    /** Identificativo del guasto nella tabella live (riferimento logico). */
    @Column(name = "id_Guasto", nullable = false, length = 50)
    public String guastoId;

    /** Tipologia del guasto (stazione_guasta / treno_fermo / sensore_offline). */
    @Column(name = "tipo", length = 50)
    public String tipo;

    /** Gravità dichiarata dalla sorgente (CRITICAL / WARNING / INFO). */
    @Column(name = "severita", length = 20)
    public String severita;

    /** Tipo della sorgente che ha generato l'allarme ("STAZIONE" o "TRENO"). */
    @Column(name = "sorgenteTipo", length = 20)
    public String sorgenteTipo;

    /** Identificativo della sorgente (riferimento logico verso stazione o convoglio). */
    @Column(name = "sorgenteId", length = 50)
    public String sorgenteId;

    /** Nome della sorgente al momento del guasto: per il convoglio coincide con l'id. */
    @Column(name = "nome_sorgente", length = 100)
    public String nomeSorgente;

    /** Messaggio di dettaglio così come è arrivato dalla sorgente. */
    @Column(name = "messaggio", length = 500)
    public String messaggio;

    /** Stato di risoluzione al momento della storicizzazione. */
    @Column(name = "Stato_RisoltoONO", nullable = false)
    public boolean risolto;

    /** Identificativo dell'operatore responsabile (null se non ancora assegnato). */
    @Column(name = "id_operatore", length = 50)
    public String operatoreId;

    /** Nome e cognome dell'operatore, congelati qui dentro. */
    @Column(name = "nome_operatore", length = 255)
    public String nomeOperatore;

    /** Matricola dell'operatore: è il codice con cui viene identificato in Keycloak. */
    @Column(name = "matricola_operatore", length = 50)
    public String matricolaOperatore;

    /** Timestamp di apertura/segnalazione del guasto. */
    @Column(name = "ts_apertura", nullable = false)
    public Instant tsApertura;

    /** Timestamp di chiusura/risoluzione (null se ancora aperto). */
    @Column(name = "ts_chiusura")
    public Instant tsChiusura;

    /**
     * Catena di eventi a cui il guasto apparteneva: l'identificativo del guasto primario da cui
     * discende (per un guasto primario è il proprio id). Riferimento logico, niente FK come tutto
     * il resto della riga.
     */
    @Column(name = "catena_id", length = 50)
    public String catenaId;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di storico copiando dal guasto tutta la sua descrizione.
     * I punti che aprono un guasto sono tre (allarme dal campo, guasto dedotto dal
     * watchdog, chiusura di un guasto che non aveva ancora storico) e copiavano i campi
     * a mano: adesso li copia un metodo solo, così non possono divergere.
     *
     * @param guasto Il guasto appena creato o appena chiuso.
     * @param nomeSorgente Nome leggibile del nodo che ha generato il guasto, da congelare
     *                     nella riga. Lo cerca il repository: l'anagrafica si legge da lì,
     *                     un'entità non fa query per conto suo.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoGuasto fotografiaDi(Guasto guasto, String nomeSorgente) {
        StoricoGuasto storico = new StoricoGuasto();
        storico.guastoId = guasto.id;
        storico.tipo = guasto.tipo;
        storico.severita = guasto.severita;
        storico.sorgenteTipo = guasto.sorgenteTipo;
        storico.sorgenteId = guasto.sorgenteId;
        storico.nomeSorgente = nomeSorgente;
        storico.messaggio = guasto.messaggio;
        storico.risolto = guasto.risolto;
        storico.tsApertura = guasto.timestamp != null ? guasto.timestamp : Instant.now();
        storico.tsChiusura = guasto.timestampRisoluzione;
        // Un guasto senza catena dichiarata è primario: la catena parte da lui.
        storico.catenaId = guasto.catenaId != null ? guasto.catenaId : guasto.id;
        if (guasto.operatore != null) {
            storico.operatoreId = guasto.operatore.id;
            storico.nomeOperatore = guasto.operatore.nome + " " + guasto.operatore.cognome;
            storico.matricolaOperatore = guasto.operatore.matricola;
        }
        return storico;
    }
}
