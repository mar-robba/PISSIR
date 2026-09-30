package it.uni.reti2.entity;

import java.time.Instant;

/**
 * Una riga dello <strong>storico degli stati delle stazioni</strong>.
 *
 * <p>Corrisponde alla tabella {@code Storico_Stato_Stazioni}. Ogni record rappresenta
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
 * @see it.uni.reti2.elaboration.GestoreEventi
 */
public class StoricoStatoStazione {

    /** Chiave primaria auto-incrementale. */
    public Long id;

    /** Identificativo della stazione di cui si sta storicizzando lo stato (riferimento logico). */
    public String stazioneId;

    /** Nome della stazione al momento della storicizzazione. */
    public String nome;

    /** Tipo funzionale della stazione al momento del record (capolinea/partenza/normale). */
    public String tipo;

    /**
     * Stato operativo per esteso (ONLINE / OFFLINE / GUASTA / MANUTENZIONE).
     * Il solo booleano {@code funzionanteONo} schiacciava su due valori il vocabolario a
     * quattro stati usato dalla Centrale: una stazione in manutenzione e una spenta
     * risultavano la stessa cosa.
     */
    public String stato;

    /** Stato da cui la stazione proviene (null alla prima riga scritta per quella stazione). */
    public String statoPrecedente;

    /** Flag di operatività: {@code true} = funzionante, {@code false} = fuori servizio. */
    public Boolean funzionanteONo;

    /** Timestamp di inserimento del record nello storico. */
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
