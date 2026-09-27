package it.uni.reti2.elaboration;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Tiene aggiornato quello che la Centrale sa dei convogli: posizione, stato, ritardo.
 *
 * <p>Le due strade da cui arrivano le notizie sono diverse e non intercambiabili. La
 * <b>telemetria</b> e' un frame periodico che dice dove si trova il treno adesso; il
 * <b>passaggio</b> e' un evento puntuale che dice che ha appena toccato una stazione, ed
 * e' l'unico momento in cui ha senso misurare un ritardo. Tenerle separate e' il motivo
 * per cui il ritardo non e' piu' un campo che il convoglio si calcola per conto suo.</p>
 *
 * <h3>Transazioni</h3>
 * <p>Le scritture aprono qui la loro transazione con {@code QuarkusTransaction.requiringNew()}
 * e non con {@code @Transactional} sul metodo. La differenza conta: con l'annotazione il
 * commit avviene all'uscita del metodo, cioe' <em>dopo</em> che la cache e' gia' stata
 * aggiornata e le notifiche sono gia' partite, e un vincolo violato lascerebbe in memoria
 * (e sulle dashboard) valori che sul database non sono mai stati scritti. Con la
 * transazione aperta qui dentro il commit succede prima, e l'eventuale eccezione resta
 * catturabile dall'adapter senza spegnere il canale MQTT.</p>
 */
@ApplicationScoped
public class MonitoraggioTreni {

    private static final Logger LOG = Logger.getLogger(MonitoraggioTreni.class);

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RailwayRepository repository;

    @Inject
    PrevisioneArrivi previsioni;

    @Inject
    NavigazioneItinerario navigazione;

    @Inject
    NotificatoreRealtime notificatore;

    @Inject
    PubblicatoreAlert pubblicatore;

    // ──────────────────────────────────────────────────────────────
    // TELEMETRIA
    // ──────────────────────────────────────────────────────────────

    /**
     * Registra un frame di telemetria: aggiorna la cache, rifa' la previsione di arrivo,
     * allinea lo stato sul database e rilancia tutto al frontend.
     *
     * <p>Un convoglio che non e' in cache non esiste e il frame viene scartato. La cache
     * e' la fotografia della tabella Treni, quindi "non in cache" vuol dire "non in
     * anagrafica": prima la telemetria creava la riga da sola, e un processo lanciato con
     * un identificativo inventato si faceva validare l'identificativo da se'.</p>
     *
     * @param payloadDalCampo Il messaggio originale, che serve intatto al frontend.
     * @return Il treno aggiornato, oppure null se il convoglio e' sconosciuto.
     */
    public Treno registraTelemetria(TelemetriaMsg msg, JsonNode payloadDalCampo) {
        Treno treno = statoRete.getTreno(msg.trenoId());
        if (treno == null) {
            LOG.warnf("🚫 Telemetria dal treno sconosciuto '%s': frame scartato (i treni si creano dall'amministrazione)",
                    msg.trenoId());
            return null;
        }

        treno.stato = msg.stato();
        treno.latitudine = msg.latitudine();
        treno.longitudine = msg.longitudine();
        treno.velocita = msg.velocita();
        treno.progresso = msg.progresso();
        // treno.ritardo NON si tocca: non e' un dato del payload ma il risultato del
        // confronto fra orario previsto e passaggio reale, che la Centrale fa da se'
        // in PrevisioneArrivi.registraArrivo().
        treno.passeggeri = msg.passeggeri();
        treno.stazioneCorrente = msg.stazioneCorrente();
        treno.prossimaStazione = msg.prossimaStazione();
        treno.direzione = msg.direzione();
        treno.ultimoAggiornamento = java.time.Instant.now();

        // La tratta in corso non la scrive piu' soltanto il sensore di terra: la fermata
        // che il convoglio dichiara di avere davanti basta a dedurla, e arriva a ogni frame.
        allineaTrattaAllaProssimaFermata(treno);

        // Con la posizione appena aggiornata si rifa' la previsione: e' il frame di
        // telemetria a dire quanta strada resta della tratta in corso.
        previsioni.aggiornaPrevisione(treno);
        statoRete.aggiornaTreno(treno);

        QuarkusTransaction.requiringNew().run(() -> salvaStato(msg.trenoId(), msg.stato()));

        notificatore.telemetria(payloadDalCampo, treno);
        return treno;
    }

    /**
     * Rimette la Centrale sulla tratta giusta usando la fermata che il convoglio dichiara
     * di avere davanti.
     *
     * <p>{@code posizioneAttualeTratta} la scriveva solo {@link #registraPassaggio}, cioe'
     * il sensore di terra della stazione. Se una stazione del percorso e' spenta il suo
     * transito non arriva mai e il campo resta indietro di un segmento: la Centrale
     * continua a dire "sta fra Bologna e Firenze" per un treno che ha gia' superato
     * Firenze e sta andando verso Torino. Il frontend da quel segmento ricava i due capi
     * dell'arco su cui disegnare il convoglio, e con la tratta sbagliata gli capitava di
     * ricavare la stessa stazione da tutte e due le parti (l'estremo dedotto per il verso
     * coincideva con la prossima fermata): a quel punto non c'era piu' nessun arco da
     * interpolare e il treno spariva dalla mappa, contato fra quelli "senza posizione
     * nota". E' esattamente il caso di un convoglio fermo a meta' tratta per un guasto,
     * che sulla mappa e' proprio quello che si vuole vedere.</p>
     *
     * <p>Il ricalcolo costa una lettura dell'itinerario, quindi si fa solo quando serve:
     * se la tratta che si ha in cache porta gia' alla fermata dichiarata e' quella giusta
     * e il database non si tocca. A regime succede una volta per tratta percorsa.</p>
     */
    private void allineaTrattaAllaProssimaFermata(Treno treno) {
        // Senza percorso o al capolinea non c'e' nessuna fermata da cui dedurre la tratta.
        if (treno.itinerario == null || treno.prossimaStazione == null) {
            return;
        }
        Tratta inCache = treno.posizioneAttualeTratta;
        if (inCache != null && treno.prossimaStazione.equals(
                navigazione.estremoVersoCuiSiViaggia(inCache, treno.direzione))) {
            return; // gia' coerente con quello che il treno dichiara
        }

        // Copie locali: la lambda della transazione vuole variabili effettivamente finali.
        String itinerarioId = treno.itinerario.id;
        String prossima = treno.prossimaStazione;
        String direzione = treno.direzione;
        Tratta dedotta = QuarkusTransaction.requiringNew()
                .call(() -> scriviTrattaDedotta(treno.id, itinerarioId, prossima, direzione));
        if (dedotta != null) {
            treno.posizioneAttualeTratta = dedotta;
            LOG.debugf("🧭 Treno %s riallineato sulla tratta %s (prossima fermata %s, corsa di %s)",
                    treno.id, dedotta.id, prossima, direzione);
        }
    }

    /**
     * Deduce la tratta dalla prossima fermata e la scrive anche sulla riga del treno, cosi'
     * che a rileggerla dal database (i transiti ci appendono la tratta percorsa) si trovi
     * la stessa cosa che sta in cache. Gira dentro una transazione.
     *
     * @return La tratta dedotta, oppure null se l'itinerario non ne ha nessuna verso quella
     *         stazione: in quel caso quella gia' nota si lascia dov'e'.
     */
    private Tratta scriviTrattaDedotta(String trenoId, String itinerarioId,
                                       String prossimaStazione, String direzione) {
        Tratta tratta = navigazione.trattaVerso(itinerarioId, prossimaStazione, direzione);
        if (tratta == null) {
            return null;
        }
        Treno dbTreno = repository.trovaTreno(trenoId);
        if (dbTreno != null) {
            dbTreno.posizioneAttualeTratta = tratta;
        }
        return tratta;
    }

    /**
     * Allinea sul database lo stato del treno e lo storicizza SOLO quando cambia davvero:
     * altrimenti sarebbe una riga di storico ogni cinque secondi per ogni convoglio.
     *
     * <p>Lo stato di partenza va letto PRIMA di sovrascriverlo: e' quello che finisce
     * nella colonna stato_precedente e rende la riga un cambiamento ("da attivo a rotto")
     * invece di una fotografia isolata.</p>
     *
     * <p>Gira dentro la transazione aperta dal chiamante.</p>
     */
    private void salvaStato(String trenoId, String stato) {
        Treno dbTreno = repository.trovaTreno(trenoId);
        if (dbTreno == null) {
            LOG.warnf("🚫 Il treno '%s' non è nella tabella Treni: nessuna scrittura", trenoId);
            return;
        }
        String statoPrecedente = dbTreno.stato;
        boolean cambiato = !stato.equals(dbTreno.stato);
        dbTreno.stato = stato;
        if (cambiato) {
            repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // PASSAGGI IN STAZIONE
    // ──────────────────────────────────────────────────────────────

    /**
     * Registra il passaggio rilevato dal sensore di terra: e' qui che la Centrale misura
     * il ritardo, aggiorna la posizione sull'itinerario e ne informa il treno. Viene
     * chiamato da {@link TransitiService#registra} prima di congelare il ritardo sulla
     * riga di Transito, cosi' il numero storicizzato e' gia' quello aggiornato.
     *
     * <p>Il treno non dichiara piu' la propria direzione su un topic dedicato (era
     * {@code PassaggioMsg}, pubblicato su railway/train/+/passaggio): adesso l'unica
     * fonte e' la cache aggiornata dall'ultima telemetria, con "andata" come default.
     * Nella finestra fra un'inversione a capolinea e il frame di telemetria successivo
     * (fino a 5s) la direzione letta qui puo' quindi essere quella vecchia.</p>
     */
    public void registraPassaggio(TransitoMsg msg) {
        Treno cacheTreno = statoRete.getTreno(msg.trenoId());
        String direzione = cacheTreno != null && cacheTreno.direzione != null
                ? cacheTreno.direzione : "andata";

        // Letture sull'itinerario e aggiornamento della posizione: tutto in una
        // transazione, perche' senza non ci sarebbe nessuna sessione JPA attiva.
        NavigazioneItinerario.Posizione posizione = QuarkusTransaction.requiringNew()
                .call(() -> aggiornaPosizioneSuDb(msg, direzione));

        if (cacheTreno == null) {
            return;
        }

        // Il ritardo com'era prima della misura: e' il termine di paragone che serve
        // subito dopo per capire se questo passaggio l'ha cambiato.
        int ritardoPrima = cacheTreno.ritardo;
        if (msg.entrata()) {
            // All'ingresso si confronta il passaggio con l'orario che la Centrale aveva
            // fissato alla partenza precedente: la differenza e' il ritardo.
            previsioni.registraArrivo(cacheTreno, msg.stazioneId(), msg.quando());
            cacheTreno.stazioneCorrente = msg.stazioneId();
            cacheTreno.prossimaStazione = posizione.prossimaStazione();
        } else {
            // All'uscita si fissa l'orario in cui il convoglio dovra' presentarsi alla
            // fermata successiva: e' il termine di paragone del prossimo ingresso.
            previsioni.programmaArrivo(cacheTreno, msg.stazioneId(), msg.quando(), direzione);
            cacheTreno.stazioneCorrente = null;
        }
        if (posizione.trattaCorrente() != null) {
            cacheTreno.posizioneAttualeTratta = posizione.trattaCorrente();
        }

        // Il ritardo e' appena cambiato: il convoglio deve saperlo. E' l'unico punto in
        // cui succede (PrevisioneArrivi e' la sola a scrivere quel campo, e la si chiama
        // solo di qui), quindi l'alert parte solo di qui.
        if (cacheTreno.ritardo != ritardoPrima) {
            pubblicatore.pubblicaRitardo(cacheTreno, msg.stazioneId(),
                    cacheTreno.ritardo - ritardoPrima, msg.entrata());
        }

        allineaAlPassaggio(cacheTreno, msg.entrata());
        // Stazione corrente e prossima sono appena cambiate: la stima va rifatta subito,
        // senza aspettare il frame di telemetria successivo.
        previsioni.aggiornaPrevisione(cacheTreno);
        statoRete.aggiornaTreno(cacheTreno);
    }

    /**
     * Porta stato, progresso e velocita' al valore che il passaggio implica.
     *
     * <p>Vanno allineati QUI, non alla telemetria successiva: il passaggio arriva subito,
     * la telemetria fino a cinque secondi dopo, e nel mezzo la fotografia del convoglio
     * metterebbe insieme campi di due eta' diverse. Un treno appena entrato risultava
     * ancora "attivo" al 95% ma con la prossima stazione gia' avanzata, e la mappa lo
     * scagliava sulla stazione successiva; uno appena uscito risultava "fermo" senza piu'
     * una stazione corrente, e la mappa non sapeva piu' dove disegnarlo.</p>
     *
     * <p>Il convoglio rotto o soppresso non si tocca: quello stato non lo decide un
     * passaggio in stazione.</p>
     */
    private void allineaAlPassaggio(Treno treno, boolean entrata) {
        if ("attivo".equals(treno.stato) || "fermo".equals(treno.stato)) {
            treno.stato = entrata ? "fermo" : "attivo";
            treno.progresso = entrata ? 100.0 : 0.0;
            if (entrata) {
                treno.velocita = 0.0;
            }
        }
        treno.ultimoAggiornamento = java.time.Instant.now();
    }

    /** Scrive sul database la tratta su cui il convoglio si trova. Gira dentro una transazione. */
    private NavigazioneItinerario.Posizione aggiornaPosizioneSuDb(TransitoMsg msg, String direzione) {
        Treno dbTreno = repository.trovaTreno(msg.trenoId());
        if (dbTreno == null || dbTreno.itinerario == null) {
            return NavigazioneItinerario.Posizione.sconosciuta();
        }
        NavigazioneItinerario.Posizione posizione = navigazione.dopoIlPassaggio(
                dbTreno.itinerario.id, msg.stazioneId(), msg.entrata(), direzione);
        if (posizione.trattaCorrente() != null) {
            dbTreno.posizioneAttualeTratta = posizione.trattaCorrente();
        }
        return posizione;
    }

    // ──────────────────────────────────────────────────────────────
    // CAMBI DI STATO DECISI DALLA CENTRALE
    // ──────────────────────────────────────────────────────────────

    /**
     * Marca il convoglio come rotto perche' ha segnalato un'avaria.
     *
     * <p>"rotto" e' uno degli stati ammessi dal CHECK su Treni.stato; il frontend lo
     * traduce in "guasto" (mapBackendStatus in apiClient.ts). Si allinea anche il
     * database, perche' la cache viene ricostruita da li' al riavvio della Centrale.</p>
     *
     * @return Il treno marcato, oppure null se non e' in anagrafica.
     */
    public Treno marcaRotto(String trenoId) {
        Treno treno = statoRete.getTreno(trenoId);
        if (treno == null) {
            LOG.warnf("🚫 Guasto da un treno sconosciuto '%s': cache non aggiornata", trenoId);
            return null;
        }
        treno.stato = "rotto";
        treno.velocita = 0;
        treno.ultimoAggiornamento = java.time.Instant.now();
        statoRete.aggiornaTreno(treno);

        QuarkusTransaction.requiringNew().run(() -> salvaStato(trenoId, "rotto"));
        notificatore.statoTreno(treno);
        return treno;
    }

    /**
     * Rimette in servizio un convoglio riparato: torna "fermo" e non "attivo", perche'
     * riparte con la prima telemetria che manda davvero.
     *
     * <p>Gira dentro la transazione aperta dal chiamante: la chiusura del guasto e il
     * ritorno in servizio del treno sono la stessa decisione e devono stare o cadere
     * insieme.</p>
     */
    public void rimettiInServizio(String trenoId) {
        Treno cache = statoRete.getTreno(trenoId);
        if (cache != null && "rotto".equalsIgnoreCase(cache.stato)) {
            cache.stato = "fermo";
            statoRete.aggiornaTreno(cache);
        }
        Treno dbTreno = repository.trovaTreno(trenoId);
        if (dbTreno != null && "rotto".equalsIgnoreCase(dbTreno.stato)) {
            String statoPrecedente = dbTreno.stato;
            dbTreno.stato = "fermo";
            repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente);
        }
    }

    /** Esito di un comando dell'operatore su un convoglio. */
    public record EsitoComando(Treno treno, String errore, boolean trovato) {
        public static EsitoComando riuscito(Treno treno) {
            return new EsitoComando(treno, null, true);
        }

        public static EsitoComando rifiutato(String errore) {
            return new EsitoComando(null, errore, true);
        }

        public static EsitoComando nonTrovato() {
            return new EsitoComando(null, null, false);
        }
    }

    /**
     * Sopprime la corsa di un convoglio su richiesta dell'operatore.
     *
     * <p>Si puo' sopprimere solo un treno fermo in stazione: una corsa cancellata mentre
     * il convoglio e' in mezzo a una tratta lascerebbe un treno immobile fuori dalle
     * banchine, che il watchdog segnalerebbe subito come anomalia.</p>
     *
     * <p>La corsa soppressa e' cancellata, non in ritardo: si buttano l'orario di arrivo
     * che aveva davanti e l'ora dell'ingresso in stazione, altrimenti alla ripresa del
     * servizio tutto il tempo di fermo diventerebbe ritardo suo.</p>
     */
    public EsitoComando sopprimi(String trenoId) {
        Treno treno = statoRete.getTreno(trenoId);
        if (treno == null) {
            return EsitoComando.nonTrovato();
        }
        if (!"fermo".equalsIgnoreCase(treno.stato) || treno.stazioneCorrente == null) {
            return EsitoComando.rifiutato("Il treno può essere soppresso solo se fermo in una stazione");
        }

        treno.stato = "SOPPRESSO";
        treno.velocita = 0;
        statoRete.aggiornaTreno(treno);
        previsioni.dimenticaTreno(trenoId);

        // Sul database va il valore canonico: la colonna ha un CHECK e "SOPPRESSO" non e'
        // fra i suoi valori ammessi, quello e' il vocabolario del protocollo MQTT.
        QuarkusTransaction.requiringNew().run(() -> salvaStato(trenoId, StatiCanonici.trenoDalCampo("SOPPRESSO")));

        // Il comando di frenata definitiva al gateway di bordo.
        pubblicatore.pubblicaStop(trenoId, "Soppresso da operatore");
        return EsitoComando.riuscito(treno);
    }
}
