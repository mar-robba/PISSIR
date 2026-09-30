package it.uni.reti2.entity;

import java.time.Instant;

/**
 * Una <strong>stazione ferroviaria</strong> come la vede la Centrale.
 *
 * <p>I campi persistiti sono le colonne della tabella {@code Stazione}. Ogni stazione è identificata
 * da un ID univoco (es. "S1") e possiede attributi persistiti come nome e tipo
 * (capolinea/partenza/normale).</p>
 *
 * <h3>Campi in-memory (non sono colonne)</h3>
 * <p>Oltre ai dati persistiti, la classe mantiene in memoria informazioni
 * <em>volatili</em> come lo stato operativo, le coordinate GPS, il numero di binari
 * e il timestamp dell'ultimo heartbeat. Questi dati vengono aggiornati in tempo reale
 * da {@code AutomaStazione} (e da {@code GestoreEventi} per l'istante del battito),
 * e letti da {@code RestApiGateway} e {@code TrafficLogicEngine} per alimentare
 * le API REST e la cache di sistema.</p>
 *
 * @see it.uni.reti2.elaboration.AutomaStazione
 * @see it.uni.reti2.elaboration.TrafficLogicEngine
 */
public class Stazione {

    /** Identificativo univoco della stazione (es. "S1", "S2"). PK nel DB. */
    public String id;

    /** Nome leggibile della stazione (es. "Milano Centrale", "Bologna Centrale"). */
    public String nome;

    /**
     * Classificazione funzionale della stazione nella rete ferroviaria.
     * Valori ammessi dal CHECK nel DDL: {@code 'capolinea'}, {@code 'partenza'}, {@code 'normale'}.
     */
    public String tipoCapolineaPartenzaoNormale;

    /** Latitudine GPS della stazione, usata per il rendering sulla mappa. Persistita in DB. */
    public Double latitudine = 0.0;

    /** Longitudine GPS della stazione, usata per il rendering sulla mappa. Persistita in DB. */
    public Double longitudine = 0.0;

    /** Numero di binari disponibili nella stazione. Persistito in DB. */
    public Integer binari = 1;

    // ──────────────────────────────────────────────────────────────
    // Campi volatili / in-memory: NON sono colonne di schema.sql.
    // Li aggiornano gli automi a ogni evento (heartbeat, guasti, watchdog)
    // e vivono nella cache TrafficLogicEngine.
    // ──────────────────────────────────────────────────────────────

    /** Stato operativo corrente (es. "ONLINE", "GUASTA", "MANUTENZIONE", "OFFLINE"). */
    public String stato = "OFFLINE";

    /** Timestamp dell'ultimo segnale di heartbeat ricevuto dalla stazione. */
    public Instant ultimoHeartbeat;

    /** Conteggio dei treni attualmente presenti in stazione. */
    public int treniInStazione;

    /** Stazione vuota: la riempie il repository leggendo la riga. */
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
