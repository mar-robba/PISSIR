package it.uni.reti2.entity;

import java.time.Instant;

/**
 * Una riga dello <strong>storico dei guasti</strong>.
 *
 * <p>Corrisponde alla tabella {@code Storico_Guasti}. Registra una copia storica
 * dell'evoluzione di ogni guasto nel tempo: apertura, eventuale chiusura,
 * operatore assegnato e stato di risoluzione.</p>
 *
 * <p>A differenza della tabella live {@code Guasti_Pervenuti_da_treni_o_Staz}
 * (classe {@link Guasto}), questa tabella sopravvive al guasto: la riga viene
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
 * @see it.uni.reti2.elaboration.GestoreEventi
 */
public class StoricoGuasto {

    /** Chiave primaria auto-incrementale. */
    public Long id;

    /** Identificativo del guasto nella tabella live (riferimento logico). */
    public String guastoId;

    /** Tipologia del guasto (stazione_guasta / treno_fermo / sensore_offline). */
    public String tipo;

    /** Gravità dichiarata dalla sorgente (CRITICAL / WARNING / INFO). */
    public String severita;

    /** Tipo della sorgente che ha generato l'allarme ("STAZIONE" o "TRENO"). */
    public String sorgenteTipo;

    /** Identificativo della sorgente (riferimento logico verso stazione o convoglio). */
    public String sorgenteId;

    /** Nome della sorgente al momento del guasto: per il convoglio coincide con l'id. */
    public String nomeSorgente;

    /** Messaggio di dettaglio così come è arrivato dalla sorgente. */
    public String messaggio;

    /** Stato di risoluzione al momento della storicizzazione. */
    public boolean risolto;

    /** Identificativo dell'operatore responsabile (null se non ancora assegnato). */
    public String operatoreId;

    /** Nome e cognome dell'operatore, congelati qui dentro. */
    public String nomeOperatore;

    /** Matricola dell'operatore: è il codice con cui viene identificato in Keycloak. */
    public String matricolaOperatore;

    /** Timestamp di apertura/segnalazione del guasto. */
    public Instant tsApertura;

    /** Timestamp di chiusura/risoluzione (null se ancora aperto). */
    public Instant tsChiusura;

    /** Timestamp di inserimento del record nello storico. */
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
        if (guasto.operatore != null) {
            storico.operatoreId = guasto.operatore.id;
            storico.nomeOperatore = guasto.operatore.nome + " " + guasto.operatore.cognome;
            storico.matricolaOperatore = guasto.operatore.matricola;
        }
        return storico;
    }
}
