package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Entità JPA per la persistenza degli eventi relativi alle stazioni ferroviarie.
 *
 * <p>Questa tabella funge da <strong>audit log</strong>: ogni riga rappresenta un cambio
 * di stato o un'azione significativa che ha coinvolto una stazione (es. guasto rilevato,
 * manutenzione programmata, ripristino). È complementare alle tabelle storiche
 * {@code Storico_Stato_Stazioni} e {@code Storico_Guasti} perché cattura eventi
 * di livello più granulare e libero nel formato.</p>
 *
 * <p>Estende {@link PanacheEntity} che fornisce un ID auto-generato di tipo {@code Long}
 * e le operazioni CRUD out-of-the-box (Active Record pattern di Panache).</p>
 *
 * @see it.uni.reti2.entity.StoricoStatoStazione
 */
@Entity
@Table(name = "eventi_stazioni")
public class EventoStazione extends PanacheEntity {

    /**
     * Identificativo della stazione coinvolta dall'evento (riferimento logico, non
     * vincolato in DDL). Corrisponde a {@code Stazione.id_stazione} nella tabella
     * {@code Stazione}, ma senza chiave esterna: come tutte le tabelle di storico
     * (RF02.7) questa deve restare leggibile anche dopo che la stazione è stata
     * eliminata dall'anagrafica.
     */
    public String stazioneId;

    /**
     * Nome della stazione al momento dell'evento. È l'informazione che sostituisce la
     * chiave esterna: senza, di una stazione cancellata resterebbe solo la sigla.
     */
    public String nomeStazione;

    /**
     * Stato della stazione al momento dell'evento (es. "ONLINE", "GUASTA", "MANUTENZIONE").
     * Permette di ricostruire la cronologia degli stati senza dover interrogare lo storico.
     */
    public String stato;

    /**
     * Descrizione testuale libera dell'evento, utile per diagnostica e debugging
     * (es. "Guasto al segnale di blocco binario 3", "Ripristino alimentazione").
     */
    public String descrizione;

    /**
     * Classificazione dell'evento secondo una tassonomia interna
     * (es. "GUASTO", "MANUTENZIONE", "HEARTBEAT_LOST").
     */
    public String tipoEvento;

    /**
     * Timestamp locale di creazione del record.
     * Viene inizializzato automaticamente all'istante corrente al momento della costruzione.
     */
    public LocalDateTime timestamp = LocalDateTime.now();

}
