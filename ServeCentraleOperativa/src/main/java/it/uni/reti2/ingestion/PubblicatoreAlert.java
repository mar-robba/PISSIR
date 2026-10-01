package it.uni.reti2.ingestion;

import com.google.gson.JsonObject;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.AlertMsg;

import java.time.Instant;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * L'unica bocca con cui la Centrale parla al campo sul topic condiviso railway/alerts.
 *
 * <p><b>Ogni</b> messaggio della Centrale verso il campo esce da qui e viene firmato
 * {@link AlertMsg#ORIGINE_CENTRALE}: la Centrale e' sottoscritta al proprio stesso topic,
 * e senza la firma un suo RESOLVED che rientra verrebbe scambiato per una dichiarazione
 * del campo e potrebbe chiudere un secondo guasto.</p>
 *
 * <p>Il JSON si costruisce sempre con un {@link JsonObject} di Gson e mai concatenando stringhe: un
 * apice o un accento nel messaggio di un guasto basterebbe a produrre un payload
 * illeggibile, e dall'altra parte del topic un payload illeggibile viene scartato in
 * silenzio.</p>
 */
public class PubblicatoreAlert {

    private static final Logger LOG = Logger.getLogger(PubblicatoreAlert.class.getName());

    /**
     * Causa dichiarata nell'alert RITARDO quando il tempo si e' perso in linea: il
     * convoglio si e' presentato alla fermata dopo l'orario che la Centrale gli aveva
     * fissato alla partenza.
     */
    public static final String CAUSA_RITARDO_IN_LINEA = "ARRIVO_IN_RITARDO";

    /**
     * Causa dichiarata nell'alert RITARDO quando il tempo si e' perso fermi in banchina:
     * e' il caso del convoglio trattenuto da una stazione guasta, che riparte tardi pur
     * avendo poi percorso la tratta in orario.
     */
    public static final String CAUSA_SOSTA_PROLUNGATA = "SOSTA_PROLUNGATA";

    /** Il topic condiviso con i nodi del campo. */
    public static final String TOPIC_ALERTS = "railway/alerts";

    /**
     * La coda delle pubblicazioni MQTT: qui si mette il messaggio e si torna subito, a
     * consegnarlo al broker ci pensa il suo thread.
     */
    public CodaPubblicazioni coda;

    /**
     * Annuncia al campo un guasto rilevato dalla Centrale, nello stesso formato usato
     * dai nodi edge.
     *
     * <p>E' il pezzo che rende vero il fail-stop: senza questa pubblicazione i treni non
     * saprebbero nulla della stazione caduta e ci andrebbero dentro lo stesso. Con
     * severita' CRITICAL entra in funzione la logica di blocco gia' scritta nel treno
     * (TrainGateway la traduce in un evento, GestoreEventi e AutomaViaggio fermano il convoglio).</p>
     */
    public void pubblicaGuasto(Guasto guasto) {
        JsonObject alert = nuovoAlert("GUASTO");
        alert.addProperty("sorgenteTipo", guasto.sorgenteTipo);
        alert.addProperty("sorgenteId", guasto.sorgenteId);
        alert.addProperty("severita", guasto.severita);
        alert.addProperty("messaggio", guasto.messaggio);
        alert.addProperty("guastoId", guasto.id);
        alert.addProperty("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        invia(alert);
    }

    /**
     * Annuncia al campo che un guasto e' stato chiuso: e' il messaggio che libera i
     * convogli trattenuti da una stazione e rimette in servizio il nodo.
     */
    public void pubblicaRisoluzione(Guasto guasto) {
        JsonObject alert = nuovoAlert("RESOLVED");
        alert.addProperty("sorgenteTipo", guasto.sorgenteTipo != null ? guasto.sorgenteTipo : "STAZIONE");
        alert.addProperty("sorgenteId", guasto.sorgenteId != null ? guasto.sorgenteId : "");
        alert.addProperty("guastoId", guasto.id);
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Comunica al convoglio il ritardo che la Centrale gli ha appena misurato.
     *
     * <p>Il testo del progetto vuole il treno capace di ricevere "guasti o ritardi", e su
     * railway/alerts viaggiavano solo i guasti. Il ritardo il convoglio non se lo puo'
     * calcolare (l'orario di servizio ce l'ha la Centrale, che e' anche l'unica a sapere
     * quando il treno <em>doveva</em> passare), quindi l'unico modo perche' lo conosca e'
     * che glielo dica chi lo ha misurato.</p>
     *
     * <p>Il campo {@code target} e' il treno, come per STOP e ITINERARIO_AGGIORNATO,
     * mentre {@code sorgenteId} e' la stazione al cui passaggio la misura e' stata fatta:
     * il treno la mostra insieme ai minuti, cosi' a bordo si sa <em>dove</em> il tempo e'
     * stato perso.</p>
     *
     * @param stazioneId La stazione del passaggio che ha fatto scattare la misura.
     * @param variazione Minuti guadagnati (negativi) o persi (positivi) in questo passaggio.
     * @param entrata    true se la misura nasce da un ingresso (tempo perso in linea),
     *                   false se nasce da un'uscita (tempo perso in sosta).
     */
    public void pubblicaRitardo(Treno treno, String stazioneId, int variazione, boolean entrata) {
        if (treno == null) {
            return;
        }
        String dove = stazioneId != null ? stazioneId : "";
        JsonObject alert = nuovoAlert("RITARDO");
        alert.addProperty("sorgenteTipo", "STAZIONE");
        alert.addProperty("sorgenteId", dove);
        alert.addProperty("target", treno.id);
        alert.addProperty("ritardoMinuti", treno.ritardo);
        alert.addProperty("variazioneMinuti", variazione);
        alert.addProperty("causa", entrata ? CAUSA_RITARDO_IN_LINEA : CAUSA_SOSTA_PROLUNGATA);
        // Un convoglio tornato in orario non e' un allarme: e' una buona notizia.
        alert.addProperty("severita", treno.ritardo > 0 ? "WARNING" : "INFO");
        alert.addProperty("messaggio", String.format(Locale.US,
                "Ritardo aggiornato a %d minuti (%+d) al passaggio di %s",
                treno.ritardo, variazione, dove.isEmpty() ? "?" : dove));
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
        LOG.info(String.format("⏱️ Comunicato a %s il ritardo di %d minuti (%+d) misurato a %s",
                treno.id, treno.ritardo, variazione, dove));
    }

    /**
     * Ordina al convoglio la frenata definitiva: la corsa e' soppressa, oppure il treno
     * e' stato eliminato dall'anagrafica e non deve continuare a girare per la rete.
     */
    public void pubblicaStop(String trenoId, String motivo) {
        JsonObject alert = nuovoAlert("STOP");
        alert.addProperty("target", trenoId);
        alert.addProperty("motivo", motivo);
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Avvisa la stazione che la squadra di manutenzione e' in arrivo. E' una notifica
     * informativa: il nodo la registra e basta, il suo stato locale resta GUASTA ed e'
     * quello che continua a trattenere i treni in ingresso.
     */
    public void pubblicaManutenzioneInviata(String stazioneId) {
        JsonObject alert = nuovoAlert("MAINTENANCE_DISPATCHED");
        alert.addProperty("sorgenteTipo", "STAZIONE");
        alert.addProperty("sorgenteId", stazioneId);
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Dice al campo in che stato si trova adesso una stazione.
     *
     * <p>Serve perche' lo stato di una stazione lo decide la Centrale (RF01.4.1) ma a
     * subirlo sono i convogli, che stanno da tutt'altra parte. Finche' l'unico messaggio
     * che partiva era il GUASTO, una stazione messa in MANUTENZIONE dal pannello
     * dell'operatore non fermava nessuno: i treni ci entravano dentro mentre i tecnici
     * lavoravano sui binari. Con questo annuncio il twin di bordo sa che quella fermata
     * non e' attraversabile e si comporta esattamente come davanti a un guasto.</p>
     *
     * <p>Il messaggio si manda a ogni cambio deciso dalla Centrale, anche quando la
     * stazione torna agibile: e' quello il momento in cui i convogli trattenuti hanno il
     * permesso di ripartire.</p>
     *
     * @param stazioneId La stazione di cui si dichiara lo stato.
     * @param stato Stato canonico (ONLINE, GUASTA, MANUTENZIONE, OFFLINE).
     */
    public void pubblicaStatoStazione(String stazioneId, String stato) {
        JsonObject alert = nuovoAlert("STATO_STAZIONE");
        alert.addProperty("sorgenteTipo", "STAZIONE");
        alert.addProperty("sorgenteId", stazioneId);
        alert.addProperty("stato", stato);
        // La severita' non la guarda il treno (per lui conta lo stato) ma tiene il
        // messaggio nello stesso formato degli altri del canale.
        alert.addProperty("severita", StatiCanonici.stazioneTrattieneITreni(stato) ? "CRITICAL" : "INFO");
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Dice al digital twin di buttare l'itinerario che ha in memoria e riscaricarlo.
     * Il twin non lo ricarica mai da solo: senza questo messaggio continuerebbe a
     * percorrere un percorso che in rete non esiste piu'.
     */
    public void pubblicaItinerarioAggiornato(String trenoId) {
        JsonObject alert = nuovoAlert("ITINERARIO_AGGIORNATO");
        alert.addProperty("target", trenoId);
        alert.addProperty("timestamp", Instant.now().toString());
        invia(alert);
    }

    /** Apre un messaggio gia' firmato: nessun alert esce da qui senza il marcatore. */
    private JsonObject nuovoAlert(String tipoEvento) {
        JsonObject alert = new JsonObject();
        alert.addProperty("tipoEvento", tipoEvento);
        alert.addProperty("origine", AlertMsg.ORIGINE_CENTRALE);
        return alert;
    }

    /**
     * Mette l'alert in coda a QoS 1: sono messaggi che nessuno ripete (un GUASTO perso vuol
     * dire un treno che entra in una stazione chiusa), quindi se il broker è giù la coda li
     * riprova finché non torna. Chi notifica non aspetta niente e non può cadere per colpa
     * del broker.
     */
    private void invia(JsonObject alert) {
        coda.metti(TOPIC_ALERTS, alert.toString(), 1);
    }
}
