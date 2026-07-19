package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Treno;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache in-memory dello stato corrente della rete ferroviaria.
 * Viene aggiornato dal consumer MQTT in tempo reale (IngestionService) e letto 
 * in modo veloce dalle API REST (RestApiGateway), fungendo da strato intermedio
 * per ridurre il carico sul database relazionale.
 */
@ApplicationScoped
public class TrafficLogicEngine {

    /** Mappa thread-safe per indicizzare i treni in base al loro ID univoco */
    private final Map<String, Treno> treni = new ConcurrentHashMap<>();
    
    /** Mappa thread-safe per indicizzare le stazioni */
    private final Map<String, Stazione> stazioni = new ConcurrentHashMap<>();
    
    /** Mappa thread-safe per tenere traccia dei guasti attualmente segnalati e non ancora risolti */
    private final Map<String, Guasto> guastiAttivi = new ConcurrentHashMap<>();

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        System.out.println("TrafficLogicEngine: Inizializzazione cache dal DB...");
        
        // Popola Stazioni
        for (Stazione s : Stazione.<Stazione>listAll()) {
            s.stato = "ONLINE";
            s.ultimoHeartbeat = Instant.now();
            stazioni.put(s.id, s);
        }
        System.out.println("Caricate " + stazioni.size() + " stazioni.");

        // Popola Treni
        for (Treno t : Treno.<Treno>listAll()) {
            t.nome = t.id; // Usa ID come nome se assente
            t.ultimoAggiornamento = Instant.now();
            treni.put(t.id, t);
        }
        System.out.println("Caricati " + treni.size() + " treni.");

        // Popola Guasti
        for (Guasto g : Guasto.<Guasto>list("risolto", false)) {
            g.timestamp = Instant.now();
            g.tipo = "sconosciuto";
            g.severita = "warning";
            guastiAttivi.put(g.id, g);
        }
        System.out.println("Caricati " + guastiAttivi.size() + " guasti attivi.");
    }

    /**
     * Aggiorna i dati di un treno nella cache.
     * @param treno L'entità Treno aggiornata.
     */
    public void aggiornaTreno(Treno treno) {
        treni.put(treno.id, treno);
    }

    /**
     * Recupera un treno dalla cache tramite ID.
     * @param id L'identificativo del convoglio.
     * @return Il treno richiesto, o null se non presente.
     */
    public Treno getTreno(String id) {
        return treni.get(id);
    }

    /**
     * Estrae la lista completa di tutti i treni noti.
     * @return Una nuova lista contenente i valori memorizzati.
     */
    public List<Treno> getTuttiTreni() {
        return new ArrayList<>(treni.values());
    }

    /**
     * Aggiorna i dati di una stazione nella cache.
     * @param stazione L'entità Stazione aggiornata.
     */
    public void aggiornaStazione(Stazione stazione) {
        stazioni.put(stazione.id, stazione);
    }

    /**
     * Recupera una stazione dalla cache tramite ID.
     * @param id L'identificativo della stazione.
     * @return La stazione richiesta, o null se non presente.
     */
    public Stazione getStazione(String id) {
        return stazioni.get(id);
    }

    /**
     * Estrae la lista completa di tutte le stazioni monitorate.
     * @return Una nuova lista delle stazioni.
     */
    public List<Stazione> getTutteStazioni() {
        return new ArrayList<>(stazioni.values());
    }

    /**
     * Inserisce un nuovo allarme/guasto nella mappa dei guasti attivi.
     * @param guasto Il guasto appena rilevato.
     */
    public void aggiungiGuasto(Guasto guasto) {
        guastiAttivi.put(guasto.id, guasto);
    }

    /**
     * Contrassegna un guasto come risolto all'interno della cache.
     * @param id ID dell'allarme da chiudere.
     */
    public void risolviGuasto(String id) {
        if (guastiAttivi.containsKey(id)) {
            Guasto g = guastiAttivi.get(id);
            g.risolto = true;
            guastiAttivi.put(id, g);
            // Non lo rimuoviamo subito dalla cache così il frontend ha tempo di vedere che è stato risolto
            // Eventualmente si potrebbe pulire dopo un timeout o spostarlo in uno storico dedicato.
        }
    }

    /**
     * Ritorna l'elenco dei guasti (sia attivi che recentemente risolti ma non ancora rimossi).
     * @return Lista di guasti.
     */
    public List<Guasto> getGuastiAttivi() {
        return new ArrayList<>(guastiAttivi.values());
    }
}
