package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

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
    @ConfigProperty(name = "treno.id", defaultValue = "REG-1234")
    public String trenoId;

    /**
     * Stato corrente del treno. 
     * Valori possibili previsti dalla logica di business:
     * - IN_VIAGGIO: il treno sta procedendo regolarmente.
     * - FERMO: il treno è temporaneamente fermo (es. in stazione).
     * - EMERGENZA: è stato rilevato un guasto o un'emergenza.
     * - SOPPRESSO: la corsa del treno è stata cancellata.
     */
    public String stato = "IN_VIAGGIO"; 
    
    /**
     * Posizione geografica attuale del convoglio (Latitudine).
     * Inizializzata con le coordinate della zona di Alessandria come mock.
     */
    public double latitudine = 44.9131;
    
    /**
     * Posizione geografica attuale del convoglio (Longitudine).
     */
    public double longitudine = 8.6154;
    
    /**
     * Velocità attuale del treno in km/h.
     */
    public double velocita = 120.0;
    
    /**
     * Lista delle stazioni che compongono l'itinerario/tratta del treno.
     * Viene popolata in fase di configurazione del viaggio o aggiornata dalla Centrale Operativa.
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
}
