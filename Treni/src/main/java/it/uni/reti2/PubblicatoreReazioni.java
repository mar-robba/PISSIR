package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Dichiara alla Centrale i cambiamenti di stato che il convoglio subisce <b>per causa di un
 * evento altrui</b> (tipoEvento REAZIONE sul canale condiviso railway/alerts).
 *
 * <p>Perché serve. Quando il treno si ferma perché una stazione della sua rotta è guasta, quel
 * fatto resta chiuso dentro {@code trainDB.faseViaggio}: non è nel frame di telemetria, che porta
 * solo stato, posizione, velocità, ritardo e passeggeri. Alla Centrale arriva quindi un convoglio
 * FERMO con velocità 0, indistinguibile da uno in sosta regolare, e nello storico resta una riga
 * "da attivo a fermo" senza nessun perché. Con questo messaggio il convoglio dice, insieme al
 * proprio stato nuovo, chi gliel'ha fatto cambiare e a quale catena di eventi appartiene.</p>
 *
 * <p>Non è un guasto: il convoglio sta benissimo. Se lo pubblicasse come GUASTO, dieci treni
 * fermi per una sola avaria diventerebbero dieci allarmi da risolvere a mano.</p>
 *
 * <p>Classe a sé e non un metodo di {@link TrainGateway} per non creare un anello fra il gateway
 * e il motore di viaggio: è il motore a decidere il blocco, e il motore non deve conoscere il
 * gateway per poterlo dichiarare.</p>
 */
@ApplicationScoped
public class PubblicatoreReazioni {

    private static final Logger LOG = Logger.getLogger(PubblicatoreReazioni.class);

    @Inject
    TrainDB trainDB;

    /**
     * Stesso canale usato per i guasti di bordo: la reazione è un messaggio del vocabolario
     * condiviso, si distingue per il campo "tipoEvento".
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    /**
     * Pubblica la reazione del convoglio.
     *
     * @param nuovoStato      Stato nuovo nel vocabolario del treno (es. BLOCCATO_GUASTO_STAZIONE).
     * @param statoPrecedente Stato da cui proviene, così la Centrale scrive un cambiamento.
     * @param causaTipo       Tipo del nodo che ha causato il cambiamento (di norma STAZIONE).
     * @param causaId         Identificativo di quel nodo.
     * @param catenaId        Catena di appartenenza (id del guasto primario).
     * @param attiva          true se il convoglio entra nella catena (si blocca), false se ne esce.
     * @param motivo          Descrizione leggibile, finisce nei log e nell'interfaccia.
     */
    public void pubblica(String nuovoStato, String statoPrecedente, String causaTipo, String causaId,
                         String catenaId, boolean attiva, String motivo) {
        pubblicaPerNodo("TRENO", trainDB.trenoId, nuovoStato, statoPrecedente,
                causaTipo, causaId, catenaId, attiva, motivo);
    }

    /**
     * Pubblica la reazione di un nodo che non è questo convoglio.
     *
     * <p>Serve per un solo caso, ed è il caso delle tratte (RF02.1.2.2.2): un arco della rete
     * cambia percorribilità, ma la tratta non è un processo — non ha un gateway, non ascolta e
     * non può accorgersi di niente. A dichiarare per lei è il convoglio che ci si è guastato
     * sopra, che è l'unico a sapere dove si trovava nel momento in cui si è rotto. Non è uno
     * strappo alla regola "decide il nodo che cambia stato": è quella regola applicata all'unico
     * nodo capace di parlare, perché fra i due è lui che <b>osserva</b> il fatto.</p>
     *
     * @param sorgenteTipo    Tipo del nodo che cambia stato (TRENO, TRATTA).
     * @param sorgenteId      Identificativo di quel nodo.
     * @param nuovoStato      Stato nuovo nel vocabolario del nodo.
     * @param statoPrecedente Stato da cui proviene.
     * @param causaTipo       Tipo del nodo che ha causato il cambiamento.
     * @param causaId         Identificativo di quel nodo.
     * @param catenaId        Catena di appartenenza (id del guasto primario).
     * @param attiva          true se il nodo entra nella catena, false se ne esce.
     * @param motivo          Descrizione leggibile.
     */
    public void pubblicaPerNodo(String sorgenteTipo, String sorgenteId, String nuovoStato,
                                String statoPrecedente, String causaTipo, String causaId,
                                String catenaId, boolean attiva, String motivo) {
        if (catenaId == null || catenaId.isBlank()) {
            // Senza catena la Centrale non potrebbe riconoscere i doppioni: meglio non
            // dichiarare niente che dichiarare una reazione che verrebbe riapplicata.
            LOG.debugf("Reazione '%s' senza catena: non pubblicata", nuovoStato);
            return;
        }

        String json = String.format(
                "{\"tipoEvento\":\"REAZIONE\",\"sorgenteTipo\":\"%s\",\"sorgenteId\":\"%s\","
                        + "\"nuovoStato\":\"%s\",\"statoPrecedente\":\"%s\","
                        + "\"causaTipo\":\"%s\",\"causaId\":\"%s\",\"catenaId\":\"%s\","
                        + "\"attiva\":%b,\"motivo\":\"%s\",\"timestamp\":\"%s\"}",
                sorgenteTipo, sorgenteId,
                nuovoStato, statoPrecedente == null ? "" : statoPrecedente,
                causaTipo == null ? "" : causaTipo, causaId == null ? "" : causaId, catenaId,
                attiva, motivo == null ? "" : motivo, Instant.now().toString());

        try {
            alertsEmitter.send(json);
            LOG.infof("🔗 [REAZIONE] Dichiaro alla Centrale: %s (catena %s)", nuovoStato, catenaId);
        } catch (Exception e) {
            // Il broker giù non deve far cadere il digital twin: il blocco è già stato applicato
            // in locale, quello che si perde è la dichiarazione. Al ripristino del guasto la
            // Centrale riceve comunque l'uscita dalla catena.
            LOG.errorf("Impossibile pubblicare la reazione: %s", e.getMessage());
        }
    }
}
