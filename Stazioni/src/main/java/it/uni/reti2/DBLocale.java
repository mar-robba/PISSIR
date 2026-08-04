package it.uni.reti2;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
// da spostare in un sqlite per maggiore resilienza nel caso di un fault energetico da parte della stazione

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
    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    public String stazioneId;

    /**
     * Stato operativo attuale della stazione.
     * - "ONLINE": stazione operativa, binari liberi da guasti.
     * - "GUASTA": stazione inagibile (es. guasto ai sistemi di terra).
     */
    public String stato = "ONLINE";

    // per il riconoscimento della stazione
    public boolean stazioneRiconosciuta = false ;

    /**
     * Flag booleano che indica se la connessione verso il sistema centrale (Centrale Operativa)
     * è attualmente attiva e funzionante. Viene utilizzato per gestire le logiche di buffering
     * locale in caso di disconnessione (fallback mode).
     */
    public boolean connessioneCentrale = true;

    /**
     * Mappa dei treni fisicamente presenti in stazione in questo momento.
     * Chiave: id del treno; valore: istante in cui il treno è entrato (evento ENTRATA).
     * Viene aggiornata dai messaggi di passaggio ricevuti via MQTT.
     */
    public final Map<String, Instant> treniPresenti = new ConcurrentHashMap<>();

    /**
     * Mappa dei sensori di binario monitorati tramite keepalive.
     * Chiave: id del sensore; valore: istante dell'ultimo battito ricevuto.
     * Se un sensore non invia battiti oltre il timeout configurato viene
     * segnalato un guasto alla Centrale e rimosso dalla mappa.
     */
    public final Map<String, Instant> sensoriUltimoBattito = new ConcurrentHashMap<>();
}
