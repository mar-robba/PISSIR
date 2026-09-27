package it.uni.reti2.elaboration;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.ingestion.payload.HeartbeatMsg;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiene aggiornato quello che la Centrale sa delle stazioni: il battito che dichiara che
 * il nodo e' vivo, e i comandi con cui l'operatore ci manda una squadra.
 *
 * <p><b>Il battito dice che il nodo e' vivo, non in che stato si trova.</b> E' la regola
 * che governa questa classe: una stazione con un binario rotto continua a battere e a
 * dichiararsi GUASTA anche mentre i tecnici ci stanno lavorando, quindi se il suo stato
 * lo decidesse il campo il primo heartbeat cancellerebbe l'intervento appena aperto
 * (RF01.4.1). Lo stato di una stazione lo decide la Centrale.</p>
 */
@ApplicationScoped
public class MonitoraggioStazioni {

    private static final Logger LOG = Logger.getLogger(MonitoraggioStazioni.class);

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RailwayRepository repository;

    @Inject
    GuastiService guasti;

    @Inject
    NotificatoreRealtime notificatore;

    @Inject
    PubblicatoreAlert pubblicatore;

    /**
     * Registra il battito di una stazione.
     *
     * <p>Se la Centrale l'aveva marcata OFFLINE (fail-stop) e il battito e' tornato, il
     * guasto che il watchdog aveva aperto va chiuso e i treni trattenuti vanno sbloccati:
     * e' la chiusura automatica prevista da RF02.1.4, e senza di essa l'allarme dedotto
     * resterebbe aperto per sempre.</p>
     *
     * @param payloadDalCampo Il messaggio originale, che serve intatto al frontend.
     */
    public void registraHeartbeat(HeartbeatMsg msg, JsonNode payloadDalCampo) {
        // Come per i treni: una stazione che non e' in cache non e' nella tabella
        // Stazione, quindi non avrebbe nemmeno superato la validazione dell'identificativo.
        Stazione stazione = statoRete.getStazione(msg.stazioneId());
        if (stazione == null) {
            LOG.warnf("🚫 Heartbeat dalla stazione sconosciuta '%s': scartato", msg.stazioneId());
            return;
        }

        String statoPrecedente = stazione.stato;
        boolean tornataDalFailStop = StatiCanonici.STAZIONE_OFFLINE.equalsIgnoreCase(statoPrecedente);

        // Finche' la squadra e' sul posto il battito aggiorna il solo istante di ultimo
        // contatto: e' la cache a rifiutare il cambio di stato, con la stessa regola che
        // vale per i guasti segnalati dal campo. Lo stato torna a essere quello dichiarato
        // dal nodo quando il tecnico chiude il guasto.
        statoRete.cambiaStatoStazione(stazione, msg.stato());
        boolean statoCambiato = statoPrecedente == null || !statoPrecedente.equalsIgnoreCase(stazione.stato);
        stazione.ultimoHeartbeat = Instant.now();
        statoRete.aggiornaStazione(stazione);

        List<Guasto> guastiChiusi = QuarkusTransaction.requiringNew().call(() -> {
            // Si storicizza solo al cambio di stato, come per i treni: a ogni battito
            // sarebbero circa 8.600 righe identiche al giorno per stazione.
            if (statoCambiato) {
                storicizza(msg.stazioneId(), stazione.stato, statoPrecedente);
            }
            if (tornataDalFailStop) {
                return guasti.chiudiDedotti(msg.stazioneId(), "STAZIONE", Guasto.MSG_HEARTBEAT_PERSO);
            }
            return new ArrayList<Guasto>();
        });

        guasti.annunciaChiusure(guastiChiusi, "[FAIL-STOP] Stazione " + msg.stazioneId() + " di nuovo raggiungibile");

        // Il nodo dichiara di essere tornato in servizio e non gli resta aperto nessun
        // allarme: i convogli che aspettavano davanti a quella stazione vanno avvisati.
        // La chiusura del guasto da sola non basta piu' a farli ripartire — un guasto
        // chiuso non vuol dire binari liberi, puo' esserci ancora la squadra al lavoro —
        // quindi il permesso di ripartire e' questo messaggio e nessun altro. La
        // condizione sui guasti aperti serve a non aprire i binari mentre un allarme e'
        // ancora rosso in dashboard.
        if (statoCambiato
                && !StatiCanonici.stazioneTrattieneITreni(stazione.stato)
                && !statoRete.haGuastiAperti(msg.stazioneId(), "STAZIONE")) {
            pubblicatore.pubblicaStatoStazione(msg.stazioneId(), stazione.stato);
        }

        notificatore.heartbeat(payloadDalCampo, stazione);
    }

    /** Esito del comando con cui l'operatore manda la squadra su una stazione. */
    public enum EsitoInvioSquadra {
        /** La stazione non esiste. */
        NON_TROVATA,
        /** Non c'e' nessun guasto aperto da lavorare: la squadra non si manda a vuoto. */
        NESSUN_GUASTO_APERTO,
        /** Intervento aperto: la stazione e' passata in MANUTENZIONE. */
        SQUADRA_INVIATA
    }

    /**
     * Manda la squadra di manutenzione su una stazione guasta (RF01.4.1).
     *
     * <p>Apre l'intervento e si ferma li': la stazione passa a MANUTENZIONE e ci resta, i
     * guasti aperti restano aperti e il nodo continua a trattenere i treni in ingresso.
     * L'intervento si chiude con la risoluzione dell'allarme, che e' l'unico punto in cui
     * la stazione torna ONLINE (RF01.4.2).</p>
     *
     * <p>La squadra si manda solo dove c'e' davvero un guasto aperto. Il filtro sul tipo
     * di sorgente serve perche' l'identificativo da solo non e' univoco: un treno omonimo
     * di una stazione farebbe passare il controllo.</p>
     */
    public EsitoInvioSquadra inviaSquadra(String stazioneId) {
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            return EsitoInvioSquadra.NON_TROVATA;
        }
        // Lo stato di partenza serve alla riga di storico: e' da li' che la stazione
        // viene (di solito GUASTA o OFFLINE).
        String statoPrecedente = stazione.stato;

        // Il passaggio in manutenzione si storicizza adesso: da qui in poi lo stato dura
        // fino alla chiusura del guasto, quindi la riga racconta un intervallo vero e non
        // un istante.
        boolean intervento = QuarkusTransaction.requiringNew().call(() -> {
            if (repository.guastiApertiDi(stazioneId, "STAZIONE").isEmpty()) {
                return false;
            }
            storicizza(stazioneId, StatiCanonici.STAZIONE_MANUTENZIONE, statoPrecedente);
            return true;
        });
        if (!intervento) {
            return EsitoInvioSquadra.NESSUN_GUASTO_APERTO;
        }

        // Entrare in manutenzione e' un comando esplicito dell'operatore, quindi si scrive
        // senza chiedere permesso: da qui in avanti sara' la cache a difendere lo stato
        // dagli eventi del campo, finche' l'intervento non viene chiuso (RF01.4.2).
        statoRete.forzaStatoStazione(stazione, StatiCanonici.STAZIONE_MANUTENZIONE);
        notificatore.statoStazione(stazione);
        // Notifica informativa: il nodo la registra e basta, il suo stato locale resta
        // GUASTA ed e' quello che continua a trattenere i treni che entrano.
        pubblicatore.pubblicaManutenzioneInviata(stazioneId);
        // Ai convogli invece serve lo stato vero: con la squadra sui binari la stazione
        // non e' attraversabile, e chi ci sta arrivando deve fermarsi come davanti a un
        // guasto. Senza questo annuncio il treno avrebbe visto solo il GUASTO che c'era
        // prima, e appena qualcuno lo avesse chiuso sarebbe ripartito con i tecnici
        // ancora al lavoro.
        pubblicatore.pubblicaStatoStazione(stazioneId, StatiCanonici.STAZIONE_MANUTENZIONE);
        return EsitoInvioSquadra.SQUADRA_INVIATA;
    }

    /**
     * Scrive una riga di Storico_Stato_Stazioni.
     * Gira dentro la transazione aperta dal chiamante.
     *
     * @param statoPrecedente Stato da cui la stazione proviene: e' quello che rende la
     *                        riga un cambiamento e non una fotografia isolata.
     */
    private void storicizza(String stazioneId, String stato, String statoPrecedente) {
        Stazione dbStazione = repository.trovaStazione(stazioneId);
        if (dbStazione == null) {
            return;
        }
        repository.salvaStoricoStatoStazione(dbStazione, stato, statoPrecedente);
    }
}
