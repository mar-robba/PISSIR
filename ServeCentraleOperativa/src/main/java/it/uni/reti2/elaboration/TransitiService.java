package it.uni.reti2.elaboration;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.ingestion.payload.TransitoMsg;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Registra i passaggi rilevati dai sensori di terra: chi e' entrato dove, quando, e con
 * quanto ritardo.
 *
 * <p>Un transito e' una <b>coppia</b> di eventi, ingresso e uscita, che il database tiene
 * su una riga sola: l'ingresso la apre lasciando l'uscita vuota, l'uscita la chiude. Fa
 * eccezione la stazione di partenza, da cui un convoglio esce senza esserci mai entrato:
 * in quel caso si registra un passaggio puntuale con entrata e uscita allo stesso
 * istante, altrimenti la partenza non risulterebbe da nessuna parte.</p>
 *
 * <p>Il ritardo si congela sulla riga al momento del passaggio. Non e' ridondanza: e' il
 * dato che lo storico dovra' mostrare fra un mese, e rileggerlo dalla cache farebbe
 * vedere lo stesso numero su tutti i transiti dello stesso treno, e degli zeri dopo ogni
 * riavvio della Centrale.</p>
 */
@ApplicationScoped
public class TransitiService {

    private static final Logger LOG = Logger.getLogger(TransitiService.class);

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RailwayRepository repository;

    @Inject
    NotificatoreRealtime notificatore;

    @Inject
    MonitoraggioTreni treni;

    /**
     * Registra il passaggio rilevato dalla stazione e lo annuncia alla dashboard.
     *
     * <p>La stazione e' la SOLA sorgente di questo evento: non esiste piu' un topic
     * separato su cui il treno dichiara lo stesso passaggio. Prima di congelare il
     * ritardo sulla riga di Transito si chiama {@link MonitoraggioTreni#registraPassaggio},
     * che e' il punto in cui la Centrale lo misura davvero, aggiorna la posizione
     * sull'itinerario e ne informa il convoglio.</p>
     *
     * @param payloadDalCampo Il messaggio originale della stazione, che porta l'ora vera
     *                        del passaggio e va lasciato intatto.
     */
    public void registra(TransitoMsg msg, JsonNode payloadDalCampo) {
        treni.registraPassaggio(msg);

        // Il ritardo va congelato adesso: e' quello che il convoglio ha nel momento del
        // passaggio, appena aggiornato dalla chiamata sopra.
        Treno cacheTreno = statoRete.getTreno(msg.trenoId());
        int ritardoAlPassaggio = cacheTreno != null ? cacheTreno.ritardo : 0;

        QuarkusTransaction.requiringNew().run(() -> scrivi(msg, ritardoAlPassaggio));

        aggiornaContatoreStazione(msg);
        notificatore.transito(payloadDalCampo, msg, cacheTreno);
    }

    /** Apre o chiude il transito sul database, storicizzandolo. Gira dentro una transazione. */
    private void scrivi(TransitoMsg msg, int ritardoMinuti) {
        Treno dbTreno = repository.trovaTreno(msg.trenoId());
        Stazione dbStazione = repository.trovaStazione(msg.stazioneId());
        if (dbTreno == null || dbStazione == null) {
            LOG.warnf("🚫 Transito ignorato: treno '%s' o stazione '%s' non presenti a DB",
                    msg.trenoId(), msg.stazioneId());
            return;
        }
        Instant quando = msg.quando() != null ? msg.quando() : Instant.now();

        if (msg.entrata()) {
            storicizza(apri(dbTreno, dbStazione, quando, ritardoMinuti));
            return;
        }

        Transito aperto = repository.trovaTransitoAperto(msg.trenoId(), msg.stazioneId());
        if (aperto != null) {
            aperto.tempoUscita = quando;
            aperto.ritardoMinuti = ritardoMinuti;
            storicizza(aperto);
            return;
        }
        // Esce senza essere entrato: e' la stazione di partenza.
        Transito puntuale = apri(dbTreno, dbStazione, quando, ritardoMinuti);
        puntuale.tempoUscita = quando;
        storicizza(puntuale);
    }

    /** Crea e persiste la riga del transito in corso. */
    private Transito apri(Treno treno, Stazione stazione, Instant quando, int ritardoMinuti) {
        Transito transito = new Transito();
        transito.id = "TR-" + quando.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        transito.treno = treno;
        transito.stazione = stazione;
        transito.tratta = treno.posizioneAttualeTratta;
        transito.tempoEntrata = quando;
        transito.ritardoMinuti = ritardoMinuti;
        repository.salvaTransito(transito);
        return transito;
    }

    /**
     * Inserisce nello storico una fotografia del transito com'e' adesso: un record per
     * ogni evento di apertura e di chiusura. La riga si porta dietro anche il nome della
     * stazione e la descrizione della tratta, perche' lo storico non ha piu' chiavi
     * esterne verso l'anagrafica (RF02.7).
     */
    private void storicizza(Transito transito) {
        repository.salvaStoricoTransito(transito);
    }

    /** Tiene il conto di quanti convogli ci sono dentro la stazione (solo cache RAM). */
    private void aggiornaContatoreStazione(TransitoMsg msg) {
        Stazione cacheStazione = statoRete.getStazione(msg.stazioneId());
        if (cacheStazione == null) {
            return;
        }
        if (msg.entrata()) {
            cacheStazione.treniInStazione++;
        } else if (cacheStazione.treniInStazione > 0) {
            cacheStazione.treniInStazione--;
        }
        statoRete.aggiornaStazione(cacheStazione);
    }
}
