package it.uni.reti2.eventi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.RealtimeWebSocket;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * L'unico punto in cui la Centrale applica un cambiamento di stato <b>causato da un evento
 * altrui</b>.
 *
 * <p>Perché serve una porta sola: oggi un cambio di stato può entrare da due strade diverse. Se
 * lo dichiara il nodo (telemetria, heartbeat) passa dalla storicizzazione; se invece è la
 * conseguenza di un allarme, {@code IngestionService.marcaSorgenteGuasta} lo scriveva solo in
 * cache. Il risultato era che il passaggio ONLINE → GUASTA di una stazione non finiva in
 * {@code Storico_Stato_Stazioni}: al battito successivo lo stato in cache era già GUASTA, quindi
 * "lo stato è cambiato" risultava falso e la riga non la scriveva più nessuno.</p>
 *
 * <p>Qui invece cache, database, storico e WebSocket vengono aggiornati insieme, e la riga di
 * storico porta con sé la {@link CausaEvento}: dal database si legge non solo <i>che</i> il
 * convoglio si è fermato, ma <i>perché</i> e per quale catena.</p>
 *
 * <p><b>Sulla regola dei cambiamenti (RF02.7).</b> Le righe di reazione si scrivono anche quando
 * la stringa di stato non cambia: un convoglio che la telemetria mostrava già "fermo" perché era
 * in sosta e che adesso è "fermo perché la stazione MI è guasta" <i>è</i> cambiato, solo che a
 * cambiare è la causa. Il doppione non arriva comunque, perché a monte c'è
 * {@link RegistroCatene}: un nodo entra in una catena una volta sola.</p>
 */
@ApplicationScoped
public class GestoreReazioni {

    private static final Logger LOG = Logger.getLogger(GestoreReazioni.class);

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RailwayRepository repository;

    @Inject
    RealtimeWebSocket webSocket;

    @Inject
    RegistroCatene registroCatene;

    @Inject
    ObjectMapper mapper;

    /**
     * Applica un evento derivato arrivato dal campo.
     *
     * @param evento L'evento già interpretato, oppure null (payload non utilizzabile).
     */
    public void applica(EventoDerivato evento) {
        if (evento == null) {
            LOG.warn("⚠️ Evento derivato senza sorgente, stato o catena: ignorato");
            return;
        }

        if (evento.attiva()) {
            // Prima reazione di questo nodo a questa catena? Se no, il messaggio è un doppione
            // (MQTT consegna at-least-once) oppure un anello di ritorno: in entrambi i casi si
            // ferma qui, ed è questo che fa terminare le catene.
            if (!registroCatene.entra(evento.catenaId(), evento.nodo())) {
                return;
            }
        } else {
            registroCatene.esce(evento.catenaId(), evento.nodo());
        }

        switch (evento.sorgenteTipo().toUpperCase()) {
            case VocabolarioEventi.NODO_TRENO -> applicaSuTreno(evento);
            case VocabolarioEventi.NODO_STAZIONE -> applicaSuStazione(evento);
            case VocabolarioEventi.NODO_TRATTA -> applicaSuTratta(evento);
            default -> LOG.warnf("⚠️ Reazione da un tipo di nodo che la Centrale non gestisce: %s",
                    evento.sorgenteTipo());
        }
    }

    /**
     * Applica una reazione decisa <b>dalla Centrale</b>, cioè quella metà dello schema in cui il
     * primario non nasce da un sensore ma da un comando dell'operatore: la squadra mandata a una
     * stazione, la corsa soppressa, il pezzo di rete che l'operatore rimette in servizio.
     *
     * <p>Passa dalla stessa porta delle reazioni che arrivano dal campo, e questo è il punto: il
     * difetto che lo schema corregge non era una dimenticanza ma il fatto che un cambio di stato
     * causato da un evento entrava da una parte diversa rispetto a uno dichiarato dal nodo, e
     * quella parte non passava dalla storicizzazione. Scrivere {@code stazione.stato} a mano
     * nell'endpoint REST è esattamente quella parte.</p>
     *
     * @param sorgenteTipo    Tipo del nodo che cambia stato (STAZIONE, TRENO, TRATTA).
     * @param sorgenteId      Identificativo di quel nodo.
     * @param nuovoStato      Stato nuovo.
     * @param statoPrecedente Stato da cui proviene (può essere null: lo si legge dalla cache).
     * @param causa           Chi lo ha deciso e a quale catena appartiene.
     * @param attiva          true se il nodo entra nella catena, false se ne esce.
     * @param motivo          Descrizione leggibile per il log e per l'operatore.
     */
    public void applicaDallaCentrale(String sorgenteTipo, String sorgenteId, String nuovoStato,
                                     String statoPrecedente, CausaEvento causa, boolean attiva,
                                     String motivo) {
        applica(new EventoDerivato(sorgenteTipo, sorgenteId, nuovoStato, statoPrecedente,
                causa, motivo, attiva, Instant.now()));
    }

    /**
     * Il convoglio dichiara di aver cambiato stato per causa altrui (tipicamente si è fermato
     * perché una stazione della sua rotta è diventata non percorribile).
     *
     * @param evento L'evento derivato.
     */
    private void applicaSuTreno(EventoDerivato evento) {
        Treno treno = statoRete.getTreno(evento.sorgenteId());
        if (treno == null) {
            LOG.warnf("🚫 Reazione da un treno sconosciuto '%s': scartata", evento.sorgenteId());
            return;
        }

        String statoDb = VocabolarioEventi.normalizzaStatoTreno(evento.nuovoStato());
        String statoPrecedente = evento.statoPrecedente() != null
                ? VocabolarioEventi.normalizzaStatoTreno(evento.statoPrecedente())
                : treno.stato;

        treno.stato = statoDb;
        if ("fermo".equals(statoDb)) {
            treno.velocita = 0;
        }
        treno.ultimoAggiornamento = Instant.now();
        statoRete.aggiornaTreno(treno);

        QuarkusTransaction.requiringNew().run(() -> {
            Treno dbTreno = repository.trovaTreno(evento.sorgenteId());
            if (dbTreno == null) {
                LOG.warnf("🚫 Il treno '%s' non è nella tabella Treni: nessuna scrittura", evento.sorgenteId());
                return;
            }
            dbTreno.stato = statoDb;
            repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente, evento.causa());
        });

        LOG.infof("🔗 [REAZIONE] Treno %s: %s -> %s, causa %s", evento.sorgenteId(),
                statoPrecedente, evento.nuovoStato(), descriviCausa(evento));
        notificaFrontend(evento, "TELEMETRY", nodo -> {
            nodo.put("trainId", treno.id);
            nodo.put("stato", treno.stato);
            nodo.put("velocita", treno.velocita);
            nodo.put("progressPercent", treno.progresso);
            nodo.put("delayMinutes", treno.ritardo);
            nodo.put("latitudine", treno.latitudine);
            nodo.put("longitudine", treno.longitudine);
        });
    }

    /**
     * La stazione dichiara di aver cambiato stato per causa altrui (tipicamente è diventata non
     * percorribile perché un convoglio si è guastato sui suoi binari).
     *
     * @param evento L'evento derivato.
     */
    private void applicaSuStazione(EventoDerivato evento) {
        Stazione stazione = statoRete.getStazione(evento.sorgenteId());
        if (stazione == null) {
            LOG.warnf("🚫 Reazione da una stazione sconosciuta '%s': scartata", evento.sorgenteId());
            return;
        }

        String statoNuovo = evento.nuovoStato().toUpperCase();
        String statoPrecedente = evento.statoPrecedente() != null
                ? evento.statoPrecedente().toUpperCase()
                : stazione.stato;

        stazione.stato = statoNuovo;
        statoRete.aggiornaStazione(stazione);

        QuarkusTransaction.requiringNew().run(() -> {
            Stazione dbStazione = repository.trovaStazione(evento.sorgenteId());
            if (dbStazione == null) {
                LOG.warnf("🚫 La stazione '%s' non è nella tabella Stazione: nessuna scrittura", evento.sorgenteId());
                return;
            }
            // Lo stato corrente della stazione è @Transient (vive in cache e si ricostruisce
            // dagli heartbeat): a database va il cambiamento, cioè la riga di storico.
            repository.salvaStoricoStatoStazione(dbStazione, statoNuovo, statoPrecedente, evento.causa());
        });

        LOG.infof("🔗 [REAZIONE] Stazione %s: %s -> %s, causa %s", evento.sorgenteId(),
                statoPrecedente, statoNuovo, descriviCausa(evento));
        notificaFrontend(evento, "STATION_STATUS", nodo -> {
            nodo.put("stationId", stazione.id);
            nodo.put("status", VocabolarioEventi.statoStazionePerFrontend(stazione.stato));
        });
    }

    /**
     * Un arco della rete cambia percorribilità perché un convoglio si è guastato mentre lo stava
     * percorrendo (RF02.1.2.2.2).
     *
     * <p>La tratta è l'unico "nodo" dello schema che non è un processo: non ha un gateway, non
     * parla e non può accorgersi di niente. A dichiarare per lei è il convoglio che la occupa,
     * che è l'unico a sapere dove si trova quando si rompe — la Centrale lo saprebbe solo per
     * deduzione, e comunque troppo tardi. Qui la reazione si applica come le altre: cache,
     * riga di storico con la causa, evento al frontend.</p>
     *
     * @param evento L'evento derivato, con {@code sorgenteId} uguale all'id della tratta.
     */
    private void applicaSuTratta(EventoDerivato evento) {
        Tratta tratta = statoRete.getTratta(evento.sorgenteId());
        if (tratta == null) {
            LOG.warnf("🚫 Reazione su una tratta sconosciuta '%s': scartata", evento.sorgenteId());
            return;
        }

        String statoNuovo = evento.nuovoStato().toUpperCase();
        String statoPrecedente = evento.statoPrecedente() != null
                ? evento.statoPrecedente().toUpperCase()
                : tratta.stato;

        tratta.stato = statoNuovo;
        statoRete.aggiornaTratta(tratta);

        QuarkusTransaction.requiringNew().run(() -> {
            Tratta dbTratta = repository.trovaTratta(evento.sorgenteId());
            if (dbTratta == null) {
                LOG.warnf("🚫 La tratta '%s' non è nella tabella Tratte: nessuna scrittura", evento.sorgenteId());
                return;
            }
            // Come per le stazioni: la percorribilità corrente è @Transient, a database va il
            // cambiamento.
            repository.salvaStoricoStatoTratta(dbTratta, statoNuovo, statoPrecedente, evento.causa());
        });

        LOG.infof("🔗 [REAZIONE] Tratta %s: %s -> %s, causa %s", evento.sorgenteId(),
                statoPrecedente, statoNuovo, descriviCausa(evento));
        notificaFrontend(evento, "SEGMENT_STATUS", nodo -> {
            nodo.put("trattaId", tratta.id);
            nodo.put("percorribile", VocabolarioEventi.TRATTA_PERCORRIBILE.equalsIgnoreCase(tratta.stato));
            nodo.put("stato", tratta.stato);
        });
    }

    /**
     * Manda al frontend l'evento nel formato che già conosce, con in più i campi della causa:
     * sono quelli che permettono all'interfaccia di scrivere "trattenuto per il guasto alla
     * stazione MI" invece di un generico "fermo".
     *
     * @param evento     L'evento derivato.
     * @param tipoEvento Il tipo di evento WebSocket già usato per quel nodo.
     * @param dettagli   I campi specifici del nodo.
     */
    private void notificaFrontend(EventoDerivato evento, String tipoEvento, DettagliNodo dettagli) {
        ObjectNode wsEvent = mapper.createObjectNode();
        wsEvent.put("eventType", tipoEvento);
        dettagli.aggiungi(wsEvent);
        wsEvent.put(VocabolarioEventi.CAMPO_CATENA, evento.catenaId());
        if (evento.causa() != null) {
            wsEvent.put(VocabolarioEventi.CAMPO_CAUSA_TIPO, evento.causa().tipo());
            wsEvent.put(VocabolarioEventi.CAMPO_CAUSA_ID, evento.causa().id());
        }
        if (evento.motivo() != null) {
            wsEvent.put(VocabolarioEventi.CAMPO_MOTIVO, evento.motivo());
        }
        wsEvent.put("timestamp", evento.quando().toString());
        webSocket.broadcast(wsEvent.toString());
    }

    private String descriviCausa(EventoDerivato evento) {
        return evento.causa() != null ? evento.causa().descrizione() : "sconosciuta";
    }

    /** Piccola richiamata per i campi che cambiano da nodo a nodo nell'evento WebSocket. */
    @FunctionalInterface
    private interface DettagliNodo {
        void aggiungi(ObjectNode evento);
    }
}
