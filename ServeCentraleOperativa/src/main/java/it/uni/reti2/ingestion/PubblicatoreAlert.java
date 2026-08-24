package it.uni.reti2.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.payload.AlertMsg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Locale;

/**
 * L'unica bocca con cui la Centrale parla al campo sul topic condiviso railway/alerts.
 *
 * <p>Prima i messaggi in uscita nascevano in due classi diverse e in due modi diversi:
 * l'ingestione li componeva con l'ObjectMapper, le API REST li scrivevano a mano con
 * {@code String.format} su una stringa JSON. Da quella doppia scrittura era gia' nato un
 * difetto silenzioso: il RESOLVED pubblicato dalle REST non portava il campo
 * {@code origine}, quindi quando rientrava alla Centrale (che e' sottoscritta al proprio
 * stesso topic) veniva scambiato per una dichiarazione del campo e poteva chiudere un
 * secondo guasto che nessuno aveva chiesto di chiudere. Adesso <b>ogni</b> messaggio esce
 * da qui e viene firmato {@link AlertMsg#ORIGINE_CENTRALE}, cosi' l'eco si riconosce da
 * sola.</p>
 *
 * <p>Il JSON si costruisce sempre con l'ObjectMapper e mai concatenando stringhe: un
 * apice o un accento nel messaggio di un guasto basterebbe a produrre un payload
 * illeggibile, e dall'altra parte del topic un payload illeggibile viene scartato in
 * silenzio.</p>
 */
@ApplicationScoped
public class PubblicatoreAlert {

    private static final Logger LOG = Logger.getLogger(PubblicatoreAlert.class);

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

    @Inject
    ObjectMapper mapper;

    /** Canale verso railway/alerts, lo stesso su cui parlano i nodi del campo. */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

    /**
     * Annuncia al campo un guasto rilevato dalla Centrale, nello stesso formato usato
     * dai nodi edge.
     *
     * <p>E' il pezzo che rende vero il fail-stop: senza questa pubblicazione i treni non
     * saprebbero nulla della stazione caduta e ci andrebbero dentro lo stesso. Con
     * severita' CRITICAL entra in funzione la logica di blocco gia' scritta in
     * TrainGateway/TrainJourneyEngine.</p>
     */
    public void pubblicaGuasto(Guasto guasto) {
        ObjectNode alert = nuovoAlert("GUASTO");
        alert.put("sorgenteTipo", guasto.sorgenteTipo);
        alert.put("sorgenteId", guasto.sorgenteId);
        alert.put("severita", guasto.severita);
        alert.put("messaggio", guasto.messaggio);
        alert.put("guastoId", guasto.id);
        alert.put("timestamp", guasto.timestamp != null ? guasto.timestamp.toString() : Instant.now().toString());
        invia(alert);
    }

    /**
     * Annuncia al campo che un guasto e' stato chiuso: e' il messaggio che libera i
     * convogli trattenuti da una stazione e rimette in servizio il nodo.
     */
    public void pubblicaRisoluzione(Guasto guasto) {
        ObjectNode alert = nuovoAlert("RESOLVED");
        alert.put("sorgenteTipo", guasto.sorgenteTipo != null ? guasto.sorgenteTipo : "STAZIONE");
        alert.put("sorgenteId", guasto.sorgenteId != null ? guasto.sorgenteId : "");
        alert.put("guastoId", guasto.id);
        alert.put("timestamp", Instant.now().toString());
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
        ObjectNode alert = nuovoAlert("RITARDO");
        alert.put("sorgenteTipo", "STAZIONE");
        alert.put("sorgenteId", dove);
        alert.put("target", treno.id);
        alert.put("ritardoMinuti", treno.ritardo);
        alert.put("variazioneMinuti", variazione);
        alert.put("causa", entrata ? CAUSA_RITARDO_IN_LINEA : CAUSA_SOSTA_PROLUNGATA);
        // Un convoglio tornato in orario non e' un allarme: e' una buona notizia.
        alert.put("severita", treno.ritardo > 0 ? "WARNING" : "INFO");
        alert.put("messaggio", String.format(Locale.US,
                "Ritardo aggiornato a %d minuti (%+d) al passaggio di %s",
                treno.ritardo, variazione, dove.isEmpty() ? "?" : dove));
        alert.put("timestamp", Instant.now().toString());
        invia(alert);
        LOG.infof("⏱️ Comunicato a %s il ritardo di %d minuti (%+d) misurato a %s",
                treno.id, treno.ritardo, variazione, dove);
    }

    /**
     * Ordina al convoglio la frenata definitiva: la corsa e' soppressa, oppure il treno
     * e' stato eliminato dall'anagrafica e non deve continuare a girare per la rete.
     */
    public void pubblicaStop(String trenoId, String motivo) {
        ObjectNode alert = nuovoAlert("STOP");
        alert.put("target", trenoId);
        alert.put("motivo", motivo);
        alert.put("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Avvisa la stazione che la squadra di manutenzione e' in arrivo. E' una notifica
     * informativa: il nodo la registra e basta, il suo stato locale resta GUASTA ed e'
     * quello che continua a trattenere i treni in ingresso.
     */
    public void pubblicaManutenzioneInviata(String stazioneId) {
        ObjectNode alert = nuovoAlert("MAINTENANCE_DISPATCHED");
        alert.put("sorgenteTipo", "STAZIONE");
        alert.put("sorgenteId", stazioneId);
        alert.put("timestamp", Instant.now().toString());
        invia(alert);
    }

    /**
     * Dice al digital twin di buttare l'itinerario che ha in memoria e riscaricarlo.
     * Il twin non lo ricarica mai da solo: senza questo messaggio continuerebbe a
     * percorrere un percorso che in rete non esiste piu'.
     */
    public void pubblicaItinerarioAggiornato(String trenoId) {
        ObjectNode alert = nuovoAlert("ITINERARIO_AGGIORNATO");
        alert.put("target", trenoId);
        alert.put("timestamp", Instant.now().toString());
        invia(alert);
    }

    /** Apre un messaggio gia' firmato: nessun alert esce da qui senza il marcatore. */
    private ObjectNode nuovoAlert(String tipoEvento) {
        ObjectNode alert = mapper.createObjectNode();
        alert.put("tipoEvento", tipoEvento);
        alert.put("origine", AlertMsg.ORIGINE_CENTRALE);
        return alert;
    }

    /** Invio difensivo: un broker giu' non deve far cadere chi stava solo notificando. */
    private void invia(ObjectNode alert) {
        try {
            alertsEmitter.send(alert.toString());
        } catch (Exception e) {
            LOG.errorf("Impossibile pubblicare l'alert su railway/alerts: %s", e.getMessage());
        }
    }
}
