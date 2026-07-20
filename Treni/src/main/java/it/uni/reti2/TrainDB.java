package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Questa classe rappresenta lo stato locale e la persistenza in memoria del singolo nodo Treno.
 * Essendo marcata con {@link ApplicationScoped}, esiste una singola istanza (singleton)
 * condivisa per l'intera applicazione del treno.
 * Funge da "Database in-memory" (State store) per mantenere le informazioni operative del convoglio.
 */
@ApplicationScoped
public class TrainDB {

    /**
     * Identificativo univoco del treno.
     * Viene iniettato dalla configurazione (es. application.properties o variabili d'ambiente).
     * Il valore di default è "REG-1234" se non specificato altrimenti.
     */
                                                      // ard coded quando vorro istanzializzare più processi
                                                        // dovra essere una variabile del vettore di imput arg etc
    @ConfigProperty(name = "treno.id", defaultValue = "TRN001")
    public String trenoId;

    /**
     * Stato corrente del treno.
     * Valori possibili previsti dalla logica di business:
     * - IN_VIAGGIO: il treno sta procedendo regolarmente.
     * - FERMO: il treno è temporaneamente fermo (es. in stazione).
     * - EMERGENZA: è stato rilevato un guasto o un'emergenza.
     * - SOPPRESSO: la corsa del treno è stata cancellata.
     */
    public String stato = "FERMO";

    /**
     * Posizione geografica attuale del convoglio (Latitudine).
     * Inizializzata con le coordinate della zona di Alessandria come mock,
     * poi sovrascritta dal motore di viaggio con le coordinate reali della tratta.
     */
    public double latitudine = 44.9131;

    /**
     * Posizione geografica attuale del convoglio (Longitudine).
     */
    public double longitudine = 8.6154;

    /**
     * Velocità attuale del treno in km/h.
     */
    public double velocita = 0.0;

    /**
     * Lista delle stazioni (solo gli id) che compongono l'itinerario/tratta del treno.
     * Viene popolata quando l'itinerario è scaricato dalla Centrale Operativa.
     */
    public List<String> stazioniTratta = new ArrayList<>();

    /**
     * Identificativo della stazione in cui si trova correntemente il treno (se presente in stazione).
     * Sarà null se il treno è in viaggio tra due stazioni.
     */
    public String stazioneCorrente = null;

    /**
     * Identificativo della prossima stazione prevista lungo la tratta.
     * Utile per calcolare percorsi, visualizzare info ai passeggeri o prendere decisioni di routing.
     */
    public String prossimaStazione = null;

    // ===============================================================
    // STATO DEL DIGITAL TWIN (viaggio simulato a eventi discreti)
    // ===============================================================

    /**
     * Fase corrente della macchina a stati del viaggio.
     * Valori possibili: IN_STAZIONE, IN_VIAGGIO, BLOCCATO_GUASTO_STAZIONE, TERMINATO.
     */
    public String faseViaggio = "IN_STAZIONE";

    /**
     * Direzione di percorrenza dell'itinerario: "andata" (indici crescenti)
     * oppure "ritorno" (indici decrescenti, itinerario percorso al contrario).
     */
    public String direzione = "andata";

    /**
     * Indice (0-based) della stazione dell'itinerario in cui il treno si trova
     * o da cui è appena partito.
     */
    public int indiceStazione = 0;

    /**
     * Identificativo dell'itinerario assegnato al treno (es. "IT1_MI_NA"),
     * ricevuto dalla Centrale Operativa insieme alla lista delle tappe.
     */
    public String itinerarioId = null;

    /**
     * Itinerario completo del treno: lista ordinata di tappe con coordinate
     * e tempo di percorrenza verso la tappa successiva. Vuota finché la
     * Centrale non risponde alla richiesta di caricamento.
     */
    public List<TappaItinerario> itinerario = new ArrayList<>();

    /**
     * Istante (tempo reale) in cui il treno è partito per la tratta corrente.
     * Serve per calcolare il progressPercent con i tempi scalati.
     */
    public Instant tInizioTratta = null;

    /**
     * Istante (tempo reale) di arrivo nell'ultima stazione toccata.
     * Serve per far scadere la sosta prima della ripartenza.
     */
    public Instant tArrivoInStazione = null;

    /**
     * Ritardo accumulato dal treno in minuti simulati (cresce quando il treno
     * resta bloccato per un guasto di stazione).
     */
    public int ritardoMinuti = 0;

    /**
     * Numero di passeggeri attualmente a bordo (rigenerato in modo casuale
     * a ogni ENTRATA in stazione per simulare salite e discese).
     */
    public int passeggeri = 0;

    /**
     * Stazione guasta che sta bloccando il treno (null se il treno non è bloccato).
     */
    public String stazioneBloccante = null;

    /**
     * Percentuale di completamento della tratta corrente (0..100).
     */
    public double progressPercent = 0.0;

    /**
     * Insieme delle stazioni attualmente note come guaste (alimentato dagli
     * alert GUASTO/RESOLVED ricevuti sul canale railway/alerts).
     * Set concorrente perché scritto dal thread MQTT e letto dal tick del motore.
     */
    public Set<String> stazioniGuaste = ConcurrentHashMap.newKeySet();

    /**
     * Singola tappa dell'itinerario del treno, così come fornita dalla Centrale
     * con GET /api/treni/{id}/itinerario: identificativo e nome della stazione,
     * coordinate geografiche e tempo (in minuti) verso la tappa successiva
     * (0 per l'ultima tappa).
     */
    public static class TappaItinerario {
        public String id;
        public String nome;
        public double latitudine;
        public double longitudine;
        public int tempoVersoProssimaMinuti;

        public TappaItinerario() {
        }

        public TappaItinerario(String id, String nome, double latitudine, double longitudine, int tempoVersoProssimaMinuti) {
            this.id = id;
            this.nome = nome;
            this.latitudine = latitudine;
            this.longitudine = longitudine;
            this.tempoVersoProssimaMinuti = tempoVersoProssimaMinuti;
        }
    }
}
