package it.uni.reti2;

import java.util.logging.Logger;

/**
 * Il generatore degli eventi del tempo: il battito da 1 secondo del digital twin.
 *
 * <p>Il treno è un motore a eventi discreti che simula il viaggio lungo l'itinerario
 * assegnato dalla Centrale. Questa classe è la parte che fa passare il tempo, e non decide
 * niente: a ogni secondo manda un TICK a GestoreEventi, ed è lì che si vede se la sosta è
 * scaduta, se la tratta è finita o se il treno è fermo. I tempi di percorrenza reali sono
 * scalati dal fattore di accelerazione (es. fattore 10: 1 minuto simulato = 6 secondi reali).</p>
 *
 * <p>È anche l'unica classe che chiede l'itinerario alla Centrale, perché la GET è lenta
 * (fino a 5 secondi di timeout) e non può stare dentro il gestore, che tiene il lock per
 * tutta la durata di un evento: la fa CaricatoreItinerario fuori dal gestore, e il suo esito
 * diventa un evento (ITINERARIO_CARICATO o ITINERARIO_REVOCATO).</p>
 */
public class GeneratoreEventiTempo {

    private static final Logger LOG = Logger.getLogger(GeneratoreEventiTempo.class.getName());

    /** Stato del treno: qui si guarda solo se il treno è riconosciuto e se ha un itinerario. */
    TrainDB trainDB;

    /**
     * Scarica l'itinerario dalla Centrale e dice com'è andata; che cosa farne lo decide il
     * gestore degli eventi.
     */
    CaricatoreItinerario caricatoreItinerario;

    /** Dove vanno gli eventi. */
    GestoreEventi gestore;

    /** Flag alzato dal gateway quando arriva ITINERARIO_AGGIORNATO: al tick successivo si ricarica. */
    private volatile boolean ricaricaItinerarioRichiesta = false;

    /**
     * Un secondo è passato. Lo chiama l'Orologio, una volta al secondo, su un suo thread (il
     * try/catch che tiene in vita il battito anche dopo un'eccezione sta lì).
     *
     * <p>Se un giro è lento (la GET dell'itinerario può aspettare fino a 5 secondi) i tick
     * successivi partono in ritardo e poi arrivano uno dietro l'altro fino a recuperare: non
     * è un problema, perché sosta e avanzamento si misurano con l'orologio e non contando i
     * tick, ed è anzi quello che serve al congelamento della tratta, che sposta l'inizio di
     * un secondo per ogni tick.</p>
     *
     * <p>Ordine dei controlli: treno non riconosciuto (nessun evento), ricarica
     * dell'itinerario richiesta, itinerario mancante (lo si chiede, e niente TICK finché non
     * c'è), altrimenti il TICK.</p>
     */
    public void tick() {
        // Non facciamo nulla finché il treno non è stato validato dal server centrale
        // o se l'ID del treno non è valido (null o vuoto).
        if (!trainDB.trenoRiconosciuto || trainDB.trenoId == null || trainDB.trenoId.trim().isEmpty() || "null".equalsIgnoreCase(trainDB.trenoId.trim())) {
            return;
        }

        // Richiesta di ricarica arrivata dalla Centrale (ITINERARIO_AGGIORNATO) attraverso il gateway.
        // L'itinerario vecchio NON si butta via qui: finché la Centrale non risponde il treno continua
        // a viaggiare su quello che ha in memoria. Svuotandolo subito il convoglio passava per
        // SENZA_ITINERARIO (fermo, velocità zero) anche quando un secondo dopo arrivava il percorso
        // nuovo, e la telemetria raccontava alla Centrale un treno fermo che fermo non era.
        // Che cosa farne lo decide il gestore: il treno assegnato resta dov'è (RF02.5.2),
        // quello sganciato riceve un 404 e solo allora si ferma (RF02.5.1).
        if (ricaricaItinerarioRichiesta) {
            ricaricaItinerarioRichiesta = false;
            LOG.info("🔄 [TWIN] Itinerario aggiornato dalla Centrale: lo riscarico");
            caricatoreItinerario.azzeraAttesa(); // riprova subito
            chiediItinerario();
        }

        // Senza itinerario non si viaggia: lo si chiede (con retry ogni 15s) e si aspetta il
        // prossimo giro. Fermo il convoglio lo è già: SENZA_ITINERARIO è la fase da cui parte,
        // e quella in cui lo mette il 404.
        if (trainDB.itinerario.isEmpty()) {
            chiediItinerario();
            return;
        }

        gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.TICK));
    }

    /**
     * Chiede alla Centrale l'itinerario del treno tramite il CaricatoreItinerario, che fa la
     * GET /api/treni/{id}/itinerario e in caso di errore (Centrale spenta, risposta non
     * valida...) riprova al massimo ogni 15 secondi. Qui l'esito diventa un evento.
     */
    private void chiediItinerario() {
        switch (caricatoreItinerario.caricaSeNecessario()) {
            case CARICATO -> gestore.gestisci(EventoTreno.itinerarioCaricato(caricatoreItinerario.ultimoCaricato()));
            case NON_ASSEGNATO -> gestore.gestisci(EventoTreno.di(EventoTreno.Tipo.ITINERARIO_REVOCATO));
            case RIPROVA -> { /* niente di nuovo: si riprova al prossimo tentativo */ }
        }
    }

    /**
     * Segnala che la Centrale ha modificato l'itinerario del treno (alert
     * ITINERARIO_AGGIORNATO): al prossimo tick lo si riscarica. Non chiama il gestore perché
     * a chiederlo è il thread MQTT, e la GET deve partire dal thread del tick.
     */
    public void richiediRicaricaItinerario() {
        ricaricaItinerarioRichiesta = true;
    }
}
