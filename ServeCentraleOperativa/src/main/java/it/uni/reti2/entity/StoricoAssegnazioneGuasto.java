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
 * <p>Nessuno la scrive ancora: la presa in carico dei guasti è il passo successivo.</p>
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
}
