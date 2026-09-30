package it.uni.reti2.entity;

import java.time.Instant;

/**
 * Una riga dello <strong>storico dei transiti</strong> dei treni nelle stazioni.
 *
 * <p>Corrisponde alla tabella {@code Storico_Transiti}. Ogni record storicizza
 * un passaggio fisico di un convoglio attraverso una stazione, registrando
 * la tratta percorsa e i tempi di entrata/uscita.</p>
 *
 * <p>Complementare alla tabella live {@code Transiti} (classe {@link Transito}),
 * questa tabella è progettata per analisi offline e reportistica storica.</p>
 *
 * <h3>Niente chiavi esterne (RF02.7)</h3>
 * <p>Stazione, convoglio e tratta NON sono chiavi esterne ma semplici identificativi
 * accompagnati dal nome: un passaggio avvenuto è un fatto, e deve restare leggibile anche
 * dopo che la stazione o il convoglio che lo ha prodotto è stato eliminato dall'anagrafica.
 * Con la chiave esterna l'unico modo di cancellare una stazione era buttare via la sua
 * storia (lo faceva davvero {@code RestApiGateway.deleteStazione}).</p>
 *
 * @see it.uni.reti2.entity.Transito
 * @see it.uni.reti2.elaboration.GestoreEventi
 */
public class StoricoTransito {

    /** Chiave primaria auto-incrementale. */
    public Long id;

    /** ID del transito originale (riferimento logico verso la tabella live). */
    public String idTransito;

    /** Identificativo della stazione attraversata dal convoglio (riferimento logico). */
    public String stazioneId;

    /** Nome della stazione al momento del passaggio: è quello che rende leggibile la riga. */
    public String nomeStazione;

    /**
     * Identificativo del convoglio che ha effettuato il transito. Non serve una colonna
     * "nome" a parte: l'id del treno È il nome del convoglio (es. "IC 351").
     */
    public String trenoId;

    /** Identificativo della tratta su cui si è verificato il transito (opzionale). */
    public String trattaId;

    /** Descrizione leggibile della tratta ("Milano Centrale -> Bologna Centrale"). */
    public String descrizioneTratta;

    /** Istante di entrata del convoglio nella stazione. */
    public Instant tempoEntrata;

    /** Istante di uscita del convoglio dalla stazione (null se ancora presente). */
    public Instant tempoUscita;

    /**
     * Ritardo del convoglio (in minuti) nell'istante del passaggio, copiato dal
     * {@link Transito} corrispondente. È il dato che la pagina Transiti deve mostrare:
     * leggere il ritardo dalla cache farebbe vedere su ogni riga il ritardo corrente
     * del treno, uguale per tutti i suoi transiti e azzerato a ogni riavvio.
     */
    public Integer ritardoMinuti;

    /** Timestamp di inserimento del record nello storico. */
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
