package it.uni.reti2.entity;

import java.time.Instant;

/**
 * Una riga dello <strong>storico degli stati dei treni</strong>.
 *
 * <p>Corrisponde alla tabella {@code Storico_Stato_Treni}. Ogni record cattura
 * lo stato operativo di un convoglio in un dato istante: stato (attivo, fermo, rotto, ecc.),
 * itinerario corrente e posizione attuale lungo la tratta.</p>
 *
 * <p>La riga si scrive SOLO quando lo stato cambia davvero, non a ogni frame di
 * telemetria: è la regola di registrazione di RF02.7 (si registrano i cambiamenti,
 * non i campionamenti). Per questo la riga porta anche lo stato precedente: da sola
 * racconta il cambiamento e non solo il punto di arrivo.</p>
 *
 * <p>Niente chiave esterna verso {@code Treni} (RF02.7): il convoglio è indicato con il
 * suo identificativo, che è anche il suo nome, e la storia resta consultabile anche se
 * quel convoglio viene eliminato dalla flotta.</p>
 *
 * @see it.uni.reti2.entity.Treno
 * @see it.uni.reti2.elaboration.GestoreEventi
 */
public class StoricoStatoTreno {

    /** Chiave primaria auto-incrementale. */
    public Long id;

    /**
     * Identificativo del convoglio di cui si sta storicizzando lo stato (riferimento
     * logico). È anche il nome del convoglio, quindi non serve una colonna in più.
     */
    public String trenoId;

    /** Stato del treno al momento della storicizzazione (es. "attivo", "fermo", "rotto"). */
    public String stato;

    /** Stato da cui il convoglio proviene (null alla prima riga scritta per quel treno). */
    public String statoPrecedente;

    /** ID dell'itinerario corrente (denormalizzato per query storiche rapide). */
    public String itinerarioId;

    /** ID della tratta o stazione in cui si trovava il treno (denormalizzato). */
    public String posizioneId;

    /** Descrizione leggibile della posizione ("Milano Centrale -> Bologna Centrale"). */
    public String descrizionePosizione;

    /** Timestamp di inserimento del record nello storico. */
    public Instant tsStoricizzazione = Instant.now();

    /**
     * Costruisce la riga di storico a partire dal convoglio, che deve avere GIÀ lo stato
     * nuovo: i tre punti che storicizzano un cambio di stato (telemetria, risoluzione di un
     * allarme, soppressione) copiavano gli stessi campi a mano, e bastava dimenticarne uno
     * per avere righe di storico diverse a seconda di chi le aveva scritte.
     *
     * @param treno            Il convoglio con lo stato nuovo già assegnato.
     * @param statoPrecedente  Lo stato che aveva prima, letto dal chiamante prima di sovrascriverlo.
     * @return La riga da persistere (non è ancora stata scritta).
     */
    public static StoricoStatoTreno fotografiaDi(Treno treno, String statoPrecedente) {
        StoricoStatoTreno storico = new StoricoStatoTreno();
        storico.trenoId = treno.id;
        storico.stato = treno.stato;
        storico.statoPrecedente = statoPrecedente;
        storico.itinerarioId = treno.itinerario != null ? treno.itinerario.id : null;
        if (treno.posizioneAttualeTratta != null) {
            storico.posizioneId = treno.posizioneAttualeTratta.id;
            storico.descrizionePosizione = treno.posizioneAttualeTratta.descrizione();
        }
        return storico;
    }
}
