package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * DBLocale funge da archivio di stato in-memory per il nodo Stazione.
 * Essendo annotata con {@link ApplicationScoped}, l'istanza è unica e condivisa
 * a livello applicativo. Contiene le informazioni di base per identificare 
 * la stazione e tracciare il suo stato di salute e connettività.
 */
@ApplicationScoped
public class DBLocale {
    
    /**
     * Identificativo univoco della stazione.
     * Recuperato dalle properties di configurazione (es. application.properties).
     * Il valore di default è "alessandria" per agevolare il testing locale.
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "alessandria")
    public String stazioneId;

    /**
     * Stato operativo attuale della stazione.
     * - "ONLINE": stazione operativa, binari liberi da guasti.
     * - "GUASTA": stazione inagibile (es. guasto ai sistemi di terra).
     */
    public String stato = "ONLINE"; 
    
    /**
     * Flag booleano che indica se la connessione verso il sistema centrale (Centrale Operativa)
     * è attualmente attiva e funzionante. Viene utilizzato per gestire le logiche di buffering
     * locale in caso di disconnessione (fallback mode).
     */
    public boolean connessioneCentrale = true;
}
