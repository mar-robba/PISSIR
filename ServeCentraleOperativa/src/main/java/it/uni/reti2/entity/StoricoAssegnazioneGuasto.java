package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico delle assegnazioni degli operatori ai guasti</strong>.
 *
 * <p>Mappa la tabella {@code Storico_Assegnazioni_Guasti}. Ogni record traccia
 * l'assegnazione di un operatore ({@link Utente}) alla gestione di un guasto
 * ({@link Guasto}), registrando i timestamp di inizio e fine intervento.</p>
 *
 * <p>È la parte di RF02.7 che risponde alla domanda "chi se n'era occupato": senza,
 * di un allarme chiuso resta solo il fatto che è stato chiuso.</p>
 *
 * <p>Niente chiavi esterne (RF02.7): guasto e operatore sono identificativi accompagnati
 * dai dati che servono a rileggere la riga (tipo e sorgente del guasto, nome e matricola
 * dell'operatore), così l'assegnazione resta leggibile anche quando l'allarme non è più
 * fra quelli vivi e l'operatore non è più in anagrafica.</p>
 *
 * <p>La riga si apre con {@code POST /api/allarmi/&#123;id&#125;/assegna} (la presa in carico,
 * RF01.4.2) e si chiude con {@code POST /api/allarmi/&#123;id&#125;/risolvi}. Chi risolve un
 * allarme senza averlo prima preso in carico ne lascia comunque una, aperta e chiusa nello
 * stesso istante: è la presa in carico implicita di chi ha fatto tutto da solo.</p>
 *
 * @see it.uni.reti2.entity.Guasto
 * @see it.uni.reti2.entity.Utente
 */
@Entity
@Table(name = "Storico_Assegnazioni_Guasti")
public class StoricoAssegnazioneGuasto extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale generata dal database. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_assegnazione")
    public Long id;

    /** Identificativo del guasto oggetto dell'assegnazione (riferimento logico). */
    @Column(name = "id_Guasto", nullable = false, length = 50)
    public String guastoId;

    /** Tipologia del guasto, copiata qui per non doverlo più cercare. */
    @Column(name = "tipo_guasto", length = 50)
    public String tipoGuasto;

    /** Tipo della sorgente del guasto ("STAZIONE" o "TRENO"). */
    @Column(name = "sorgenteTipo", length = 20)
    public String sorgenteTipo;

    /** Identificativo della sorgente del guasto. */
    @Column(name = "sorgenteId", length = 50)
    public String sorgenteId;

    /** Nome della sorgente al momento dell'assegnazione. */
    @Column(name = "nome_sorgente", length = 100)
    public String nomeSorgente;

    /** Identificativo dell'operatore assegnato alla risoluzione (riferimento logico). */
    @Column(name = "id_operatore", nullable = false, length = 50)
    public String operatoreId;

    /** Nome e cognome dell'operatore, congelati qui dentro. */
    @Column(name = "nome_operatore", length = 255)
    public String nomeOperatore;

    /** Matricola dell'operatore: è il codice che lo lega all'utente di Keycloak. */
    @Column(name = "matricola_operatore", length = 50)
    public String matricolaOperatore;

    /** Ruolo dell'operatore in quel momento (operatore / tecnico / amministratore). */
    @Column(name = "ruolo_operatore", length = 50)
    public String ruoloOperatore;

    /** Istante in cui l'operatore è stato assegnato al guasto. */
    @Column(name = "ts_assegnazione", nullable = false)
    public Instant tsAssegnazione;

    /** Istante in cui il guasto è stato effettivamente risolto dall'operatore (null se ancora aperto). */
    @Column(name = "ts_risoluzione")
    public Instant tsRisoluzione;

    /** Timestamp di inserimento del record nello storico. Inizializzato automaticamente. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di presa in carico: da una parte il guasto con la sua sorgente,
     * dall'altra l'operatore che se ne sta occupando, tutti e due copiati per esteso perché
     * qui non ci sono chiavi esterne da seguire.
     *
     * <p>La riga nasce aperta ({@code tsRisoluzione} a null) e si chiude quando l'allarme
     * viene risolto.</p>
     *
     * @param guasto       Il guasto preso in carico.
     * @param nomeSorgente Nome della stazione o del convoglio che lo ha generato.
     * @param operatore    Chi lo ha preso in carico, come risulta dal token.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoAssegnazioneGuasto fotografiaDi(Guasto guasto, String nomeSorgente,
                                                         DatiOperatore operatore) {
        StoricoAssegnazioneGuasto storico = new StoricoAssegnazioneGuasto();
        storico.guastoId = guasto.id;
        storico.tipoGuasto = guasto.tipo;
        storico.sorgenteTipo = guasto.sorgenteTipo;
        storico.sorgenteId = guasto.sorgenteId;
        storico.nomeSorgente = nomeSorgente;
        storico.operatoreId = operatore.id();
        storico.nomeOperatore = operatore.nome();
        storico.matricolaOperatore = operatore.matricola();
        storico.ruoloOperatore = operatore.ruolo();
        storico.tsAssegnazione = Instant.now();
        return storico;
    }
}
