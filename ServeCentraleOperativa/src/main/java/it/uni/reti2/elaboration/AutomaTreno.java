package it.uni.reti2.elaboration;

import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L'automa di un convoglio come lo vede la Centrale: lo stato canonico (attivo, fermo, rotto,
 * in manutenzione, capolinea, i valori ammessi dal CHECK della colonna Treni.stato) e i dati
 * che cambiano con gli stessi eventi (posizione, ritardo, previsione di arrivo).
 *
 * <p>Le transizioni sono queste:</p>
 * <ul>
 *   <li>la telemetria porta il convoglio nello stato che dichiara, tradotto da
 *       {@link StatiCanonici#trenoDalCampo}: è il treno a sapere se sta andando o no;</li>
 *   <li>il passaggio rilevato dalla stazione alterna attivo e fermo (entrata = fermo,
 *       uscita = attivo), ma solo fra quei due: un treno rotto o soppresso che passa davanti a
 *       un sensore resta com'è ({@link #cambiaFaseAlPassaggio});</li>
 *   <li>un guasto di bordo lo porta a rotto, e la risoluzione di quel guasto lo riporta a
 *       fermo ({@link #puoTornareInServizio});</li>
 *   <li>la soppressione lo porta a "in manutenzione", da fermo in una stazione
 *       ({@link #puoEssereSoppresso});</li>
 *   <li>l'amministratore dal pannello lo mette in uno qualsiasi degli stati ammessi.</li>
 * </ul>
 *
 * <p>Come gli altri automi offre guardie e mosse e non chiama nessuno: l'ordine lo decide
 * GestoreEventi. Le mosse scrivono la copia in cache; la riga del database la scrive il
 * gestore, nella fase degli assegnamenti.</p>
 *
 * <p>Qui vive anche l'orologio del watchdog dei treni fermi: da quando ogni convoglio risulta
 * fermo fuori da una stazione. È un dato del convoglio che dura fra un tick e l'altro, e serve
 * a far scattare l'anomalia solo se la condizione dura almeno {@code T_fer}.</p>
 */
public class AutomaTreno {

    /** La cache in cui vivono i convogli. */
    public TrafficLogicEngine statoRete;

    /** Il calcolo del ritardo e della previsione di arrivo, che cambiano con la posizione. */
    public PrevisioneArrivi previsioni;

    /**
     * Da quando ogni convoglio risulta fermo fuori da una stazione (id treno → istante del
     * primo rilevamento). Mappa concorrente perché la legge anche il thread del watchdog.
     */
    private final Map<String, Instant> fermiDa = new ConcurrentHashMap<>();

    // ===============================================================
    // LETTURE
    // ===============================================================

    /** @return true se la corsa è stata soppressa ("in manutenzione" nel vocabolario del DB). */
    public boolean soppresso(Treno treno) {
        return "in manutenzione".equalsIgnoreCase(treno.stato);
    }

    /** @return true se il convoglio è in una stazione (dichiarata dalla telemetria o dal passaggio). */
    public boolean inStazione(Treno treno) {
        return treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
    }

    /** @return Da quando il convoglio è fermo fuori stazione, o null se non lo è (o non ancora visto). */
    public Instant fermoDa(String trenoId) {
        return fermiDa.get(trenoId);
    }

    // ===============================================================
    // GUARDIE
    // ===============================================================

    /**
     * Il passaggio davanti al sensore alterna attivo e fermo, e basta: un convoglio rotto,
     * soppresso o arrivato al capolinea non cambia stato per un passaggio.
     */
    public boolean cambiaFaseAlPassaggio(Treno treno) {
        return "attivo".equals(treno.stato) || "fermo".equals(treno.stato);
    }

    /** Si rimette in servizio solo un convoglio rotto. */
    public boolean puoTornareInServizio(Treno treno) {
        return "rotto".equalsIgnoreCase(treno.stato);
    }

    /**
     * La corsa si sopprime solo con il convoglio fermo in una stazione: sopprimerla a metà
     * tratta lascerebbe un treno fermo in piena linea.
     */
    public boolean puoEssereSoppresso(Treno treno) {
        return "fermo".equalsIgnoreCase(treno.stato) && treno.stazioneCorrente != null;
    }

    /** Lo stato scelto dall'amministratore deve essere uno di quelli ammessi dal CHECK di Treni.stato. */
    public boolean statoAmmesso(String stato) {
        return StatiCanonici.TRENO.contains(stato);
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /**
     * Il convoglio ha trasmesso la sua telemetria: stato, posizione, velocità e fermate si
     * prendono dal frame. Il ritardo NO: non è un dato del payload ma il risultato del
     * confronto fra orario previsto e passaggio reale, che fa la Centrale al passaggio.
     *
     * @param trattaDedotta La tratta ricavata dalla fermata che il convoglio dichiara di avere
     *                      davanti, o null se quella in cache era già giusta.
     */
    public void telemetria(Treno treno, TelemetriaMsg msg, Tratta trattaDedotta, Instant quando) {
        treno.stato = msg.stato();
        treno.latitudine = msg.latitudine();
        treno.longitudine = msg.longitudine();
        treno.velocita = msg.velocita();
        treno.progresso = msg.progresso();
        treno.passeggeri = msg.passeggeri();
        treno.stazioneCorrente = msg.stazioneCorrente();
        treno.prossimaStazione = msg.prossimaStazione();
        treno.direzione = msg.direzione();
        treno.ultimoAggiornamento = quando;
        if (trattaDedotta != null) {
            treno.posizioneAttualeTratta = trattaDedotta;
        }
        // Con la posizione appena aggiornata si rifa' la previsione: e' il frame di
        // telemetria a dire quanta strada resta della tratta in corso.
        previsioni.aggiornaPrevisione(treno);
        statoRete.aggiornaTreno(treno);
    }

    /**
     * Il sensore di terra ha visto passare il convoglio. È qui che la Centrale misura il
     * ritardo: all'ingresso confronta l'ora vera con quella programmata, all'uscita misura la
     * sosta e programma l'arrivo alla fermata dopo.
     *
     * @param posizione     Dove si trova il convoglio dopo il passaggio (calcolato dal gestore).
     * @param cambiaFase    La risposta di {@link #cambiaFaseAlPassaggio}, data nella fase 1.
     * @return Di quanti minuti è cambiato il ritardo con questo passaggio (0 se non è cambiato).
     */
    public int passaggio(Treno treno, TransitoMsg msg, String direzione,
                         NavigazioneItinerario.Posizione posizione, boolean cambiaFase, Instant quando) {
        int ritardoPrima = treno.ritardo;
        if (msg.entrata()) {
            previsioni.registraArrivo(treno, msg.stazioneId(), msg.quando());
            treno.stazioneCorrente = msg.stazioneId();
            treno.prossimaStazione = posizione.prossimaStazione();
        } else {
            previsioni.programmaArrivo(treno, msg.stazioneId(), msg.quando(), direzione);
            treno.stazioneCorrente = null;
        }
        if (posizione.trattaCorrente() != null) {
            treno.posizioneAttualeTratta = posizione.trattaCorrente();
        }
        if (cambiaFase) {
            treno.stato = msg.entrata() ? "fermo" : "attivo";
            treno.progresso = msg.entrata() ? 100.0 : 0.0;
            if (msg.entrata()) {
                treno.velocita = 0.0;
            }
        }
        treno.ultimoAggiornamento = quando;
        previsioni.aggiornaPrevisione(treno);
        statoRete.aggiornaTreno(treno);
        return treno.ritardo - ritardoPrima;
    }

    /** -> rotto: un guasto di bordo. Il tachimetro va a zero subito, senza aspettare la telemetria. */
    public void rompi(Treno treno, Instant quando) {
        treno.stato = "rotto";
        treno.velocita = 0;
        treno.ultimoAggiornamento = quando;
        statoRete.aggiornaTreno(treno);
    }

    /** rotto -> fermo: il guasto di bordo è stato risolto dall'operatore. */
    public void rimettiInServizio(Treno treno) {
        treno.stato = "fermo";
        statoRete.aggiornaTreno(treno);
    }

    /**
     * fermo -> in manutenzione: la corsa è soppressa. Il valore è quello canonico del
     * database ("SOPPRESSO" è il vocabolario del protocollo MQTT, e la colonna non lo
     * accetta); la web app lo mostra come "soppresso". La corsa esce anche dalla tabella di
     * marcia, così nessuna stazione la aspetta più.
     */
    public void sopprimi(Treno treno) {
        treno.stato = "in manutenzione";
        treno.velocita = 0;
        statoRete.aggiornaTreno(treno);
        previsioni.dimenticaTreno(treno.id);
    }

    /** Lo stato scelto dall'amministratore (da chiamare solo se {@link #statoAmmesso} ha detto sì). */
    public void imponiDallOperatore(Treno treno, String stato) {
        treno.stato = stato;
        statoRete.aggiornaTreno(treno);
    }

    /** Il watchdog ha visto il convoglio fermo fuori stazione per la prima volta. */
    public void segnaFermo(String trenoId, Instant quando) {
        fermiDa.put(trenoId, quando);
    }

    /** Il convoglio si muove, o è fermo dove è lecito: l'orologio del watchdog si azzera. */
    public void azzeraFermo(String trenoId) {
        fermiDa.remove(trenoId);
    }

    /** Si dimenticano gli orologi dei convogli che non esistono più. */
    public void tieniFermiSoloDi(Set<String> treniEsistenti) {
        fermiDa.keySet().retainAll(treniEsistenti);
    }
}
