package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA per lo <strong>storico degli interventi di manutenzione</strong> sulle stazioni.
 *
 * <p>Mappa la tabella {@code Storico_Interventi_Manutenzione}. Una riga per ogni squadra
 * mandata a una stazione dal comando "Invia Operatori" della web app
 * ({@code POST /api/stazioni/{id}/manutenzione}, RF01.4.1).</p>
 *
 * <p><b>Perché una tabella a parte.</b> RF02.7 chiede di conservare anche le assegnazioni
 * degli operatori, e nel sistema ce ne sono di due tipi diversi: la presa in carico di un
 * allarme da parte di un operatore di centrale, che sta in {@link StoricoAssegnazioneGuasto},
 * e l'invio fisico di una squadra a una stazione guasta, che è quello che il comando fa
 * davvero e che finora non lasciava traccia da nessuna parte: passava solo per MQTT
 * ({@code MAINTENANCE_DISPATCHED}) e per la WebSocket, cioè spariva appena finito.</p>
 *
 * <p>Come tutti gli storici non ha chiavi esterne: stazione, guasto e operatore sono
 * identificativi accompagnati dai dati che servono a rileggere la riga.</p>
 *
 * <p>Invio e rientro stanno sulla stessa riga perché sono due momenti dello stesso intervento.
 * Fino a poco fa fra i due passavano millisecondi, perché il comando faceva tutte e due le cose
 * dentro la stessa chiamata — era il limite dichiarato di RF01.4.1. Adesso {@code ts_rientro}
 * resta nullo finché la squadra lavora e viene riempito dal comando di fine intervento, quindi
 * la differenza fra i due timestamp è la durata vera del lavoro.</p>
 *
 * @see it.uni.reti2.gateway.RestApiGateway#dispacciaManutenzione
 */
@Entity
@Table(name = "Storico_Interventi_Manutenzione")
public class StoricoInterventoManutenzione extends PanacheEntityBase {

    /** Chiave primaria auto-incrementale. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_storico_intervento")
    public Long id;

    /** Identificativo della stazione dove è stata mandata la squadra (riferimento logico). */
    @Column(name = "id_stazione", nullable = false, length = 50)
    public String stazioneId;

    /** Nome della stazione al momento dell'intervento. */
    @Column(name = "nome_stazione", length = 100)
    public String nomeStazione;

    /** Guasto che ha motivato l'invio, se ce n'era uno aperto (riferimento logico). */
    @Column(name = "id_Guasto", length = 50)
    public String guastoId;

    /** Identificativo dell'operatore che ha dato il comando dalla web app. */
    @Column(name = "id_operatore", length = 50)
    public String operatoreId;

    /** Nome e cognome di chi ha dato il comando. */
    @Column(name = "nome_operatore", length = 255)
    public String nomeOperatore;

    /** Matricola di chi ha dato il comando: arriva dal token di Keycloak. */
    @Column(name = "matricola_operatore", length = 50)
    public String matricolaOperatore;

    /** Stato della stazione prima dell'intervento (di solito GUASTA o OFFLINE). */
    @Column(name = "stato_stazione_prima", length = 30)
    public String statoStazionePrima;

    /** Stato della stazione a intervento concluso (ONLINE se è tornata in servizio). */
    @Column(name = "stato_stazione_dopo", length = 30)
    public String statoStazioneDopo;

    /** Istante in cui la squadra è stata mandata. */
    @Column(name = "ts_invio", nullable = false)
    public Instant tsInvio;

    /** Istante in cui l'intervento si è concluso (null finché è in corso). */
    @Column(name = "ts_rientro")
    public Instant tsRientro;

    /**
     * Catena di eventi dell'intervento: la conia la Centrale quando parte la squadra, perché il
     * comando dell'operatore è un evento primario come il guasto di un sensore, solo che nasce
     * da una decisione. È quella che lega fra loro le due righe di {@code Storico_Stato_Stazioni}
     * (l'ingresso in MANUTENZIONE e il ritorno in servizio) e questa riga di intervento.
     */
    @Column(name = "catena_id", length = 50)
    public String catenaId;

    /** Timestamp di inserimento del record nello storico. */
    @Column(name = "ts_storicizzazione", nullable = false)
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga della squadra mandata a una stazione, copiando il nome della
     * stazione e i dati di chi ha dato il comando: l'intervento deve restare leggibile
     * anche se poi la stazione viene dismessa.
     *
     * <p>La riga nasce <b>aperta</b>: {@code ts_rientro} resta nullo e lo stato di arrivo è
     * MANUTENZIONE, perché in questo istante la squadra sta andando e il lavoro non è finito.
     * Si chiude quando l'operatore dichiara concluso l'intervento.</p>
     *
     * @param stazione   La stazione dove è stata mandata la squadra.
     * @param guastoId   Il guasto che ha motivato l'invio, oppure null se non ce n'erano aperti.
     * @param operatore  Chi ha dato il comando dalla web app.
     * @param statoPrima Lo stato che la stazione aveva quando è partita la squadra.
     * @param tsInvio    Istante in cui la squadra è stata mandata.
     * @param catenaId   Catena coniata per questo intervento.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoInterventoManutenzione invioSquadra(Stazione stazione, String guastoId,
                                                             DatiOperatore operatore,
                                                             String statoPrima, Instant tsInvio,
                                                             String catenaId) {
        StoricoInterventoManutenzione storico = new StoricoInterventoManutenzione();
        storico.stazioneId = stazione.id;
        storico.nomeStazione = stazione.nome;
        storico.guastoId = guastoId;
        storico.operatoreId = operatore.id();
        storico.nomeOperatore = operatore.nome();
        storico.matricolaOperatore = operatore.matricola();
        storico.statoStazionePrima = statoPrima;
        storico.statoStazioneDopo = "MANUTENZIONE";
        storico.tsInvio = tsInvio;
        storico.tsRientro = null;
        storico.catenaId = catenaId;
        return storico;
    }
}
