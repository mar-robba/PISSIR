package it.uni.reti2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Questa classe rappresenta lo stato locale e la persistenza in memoria del singolo nodo Treno.
 * Il main ne crea una sola istanza e la passa a tutte le classi che ne hanno bisogno, quindi
 * è condivisa dall'intera applicazione del treno.
 * Funge da "Database in-memory" (State store) per mantenere le informazioni operative del convoglio.
 */
public class TrainDB {

    /**
     * Identificativo univoco del treno.
     * Lo assegna il main: è il primo argomento della riga di comando, oppure la property
     * treno.id di application.properties se l'argomento manca.
     */
    public String trenoId;

    public boolean trenoRiconosciuto = false;

    /**
     * Condizione del convoglio, cioè COME sta (lo stato dell'AutomaCondizione):
     * - REGOLARE: il treno è in servizio e può muoversi;
     * - EMERGENZA: un sensore di bordo ha rilevato un'avaria, il convoglio resta dov'è
     *   finché la Centrale non la dichiara risolta;
     * - SOPPRESSO: la corsa è stata cancellata, ed è definitivo.
     *
     * <p>Dove si trova il convoglio non sta qui ma in {@link #faseViaggio}: le due cose
     * cambiano per cause diverse e un treno in emergenza resta nella fase in cui era, così
     * quando l'emergenza rientra riprende esattamente da lì.</p>
     */
    public String condizione = "REGOLARE";

    /**
     * Lo stato che il convoglio dichiara fuori (telemetria e REST): FERMO, IN_VIAGGIO,
     * EMERGENZA, SOPPRESSO o CAPOLINEA, che sono i valori che la Centrale si aspetta.
     *
     * <p>Non è un campo ma un calcolo: EMERGENZA e SOPPRESSO vengono dalla condizione, che
     * vince su tutto, e il resto si legge dalla fase del viaggio. Tenerlo in una variabile
     * a parte voleva dire scriverlo insieme alla fase in ogni punto del motore, e dove una
     * delle due scritture mancava (o cancellava un'EMERGENZA) le due copie non erano più
     * d'accordo.</p>
     *
     * @return Lo stato del convoglio.
     */
    public String stato() {
        if (!"REGOLARE".equals(condizione)) {
            return condizione; // EMERGENZA o SOPPRESSO
        }
        return switch (faseViaggio) {
            case "IN_VIAGGIO" -> "IN_VIAGGIO";
            case "FERMO_CAPOLINEA" -> "CAPOLINEA";
            default -> "FERMO"; // in sosta, trattenuto o senza un percorso
        };
    }

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
     * Fase del viaggio, cioè DOVE si trova il convoglio (lo stato dell'AutomaViaggio):
     * - SENZA_ITINERARIO: nessun percorso assegnato, o non ancora partito: fermo e aspetta;
     * - IN_STAZIONE: in sosta al binario;
     * - IN_VIAGGIO: in marcia fra due tappe;
     * - BLOCCATO_IN_STAZIONE: trattenuto al binario perché la stazione in cui si trova o
     *   la prossima non si può attraversare (guasta, o con la squadra di manutenzione);
     * - BLOCCATO_IN_TRATTA: stessa cosa, ma fermo sul binario a metà tratta;
     * - FERMO_CAPOLINEA: corsa finita all'estremo marcato dell'itinerario.
     */
    public String faseViaggio = "SENZA_ITINERARIO";

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
     * La stazione in cui questo itinerario chiude il servizio, oppure null (il caso
     * normale) se il percorso va percorso avanti e indietro senza fine.
     *
     * <p>Arriva dalla Centrale nella stessa risposta dell'itinerario: è lei a sapere che
     * cosa ha marcato l'amministratore, il convoglio si limita a fermarsi quando ci
     * arriva. È sempre uno dei due estremi dell'elenco: se è l'ultimo la corsa finisce
     * dopo la sola andata, se è il primo dopo l'andata e il ritorno.</p>
     */
    public String capolineaId = null;

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

    // Il ritardo non lo CALCOLA piu' il convoglio. Il testo del progetto mette il
    // calcolo dei ritardi fra i compiti della Centrale ("Processing: calcolo dei
    // ritardi e previsioni di arrivo") e la Centrale ha tutto per farlo: conosce i
    // tempi di percorrenza dell'itinerario, quindi sa a che ora il convoglio deve
    // presentarsi in ogni stazione, e riceve i passaggi con l'ora in cui sono
    // avvenuti davvero. Il contatore che stava qui era un secondo conteggio dello
    // stesso fenomeno, fatto nel nodo sbagliato.
    // I tre campi qui sotto non sono quel contatore: sono la copia di bordo del
    // ritardo che la Centrale COMUNICA al treno con l'alert RITARDO, cioe' l'altra
    // meta' del sistema di comunicazione in ingresso chiesto dal testo ("in grado di
    // ricevere informazioni (guasti o ritardi) dalle stazioni o dalla centrale").

    /**
     * Ritardo del convoglio in minuti di servizio, come lo ha comunicato la Centrale
     * con l'ultimo alert RITARDO ricevuto su railway/alerts. Vale 0 finche' nessun
     * ritardo e' stato comunicato: e' anche il valore a cui torna il convoglio che
     * recupera il tempo perso.
     *
     * <p>volatile perche' lo scrive il thread del broker (TrainGateway.riceviAlert) e
     * lo leggono il tick del motore e le richieste REST, che sono altri thread.</p>
     */
    public volatile int ritardoMinuti = 0;

    /**
     * Stazione al cui passaggio la Centrale ha misurato il ritardo comunicato.
     * null finche' non arriva il primo alert RITARDO.
     */
    public volatile String ritardoRilevatoA = null;

    /**
     * Istante (tempo reale) in cui e' arrivato l'ultimo alert RITARDO: serve a
     * distinguere un ritardo appena comunicato da uno vecchio di mezza corsa.
     * null finche' non arriva il primo alert RITARDO.
     */
    public volatile Instant ritardoAggiornatoIl = null;

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
     *
     * <p>Lo scrive AutomaStazioniNote e lo leggono le guardie di AutomaViaggio, sempre dentro
     * GestoreEventi, quindi un evento alla volta. Resta un set concorrente dai tempi in cui lo
     * scriveva direttamente il thread MQTT mentre il tick lo leggeva: oggi basterebbe anche
     * un set normale.</p>
     */
    public Set<String> stazioniGuaste = ConcurrentHashMap.newKeySet();

    /**
     * Ultimo stato che la Centrale ha dichiarato per ogni stazione (alert STATO_STAZIONE,
     * più quello che arriva insieme all'itinerario): ONLINE, GUASTA, MANUTENZIONE, OFFLINE.
     *
     * <p>Sta separato da {@link #stazioniGuaste} perché le due cose sono indipendenti: una
     * stazione può avere il guasto risolto e i tecnici ancora sui binari, e in quel caso il
     * RESOLVED non deve far ripartire nessuno. Chi la scrive e chi la legge è lo stesso del
     * set qui sopra.</p>
     */
    public Map<String, String> statoStazioni = new ConcurrentHashMap<>();

    /**
     * Dice se in quella stazione il treno non può entrare (o da cui non può uscire).
     *
     * <p>Per il convoglio una stazione guasta e una stazione in manutenzione sono la stessa
     * cosa: in tutti e due i casi i binari non sono suoi, quindi si ferma. Le due condizioni
     * arrivano da messaggi diversi (il GUASTO lo segnala il campo, la MANUTENZIONE la decide
     * la Centrale) ma la reazione è una sola, ed è per questo che la domanda si fa qui e non
     * in tre punti diversi del motore.</p>
     */
    public boolean stazioneImpercorribile(String stazioneId) {
        if (stazioneId == null) {
            return false;
        }
        if (stazioniGuaste.contains(stazioneId)) {
            return true;
        }
        return statoImpercorribile(statoStazioni.get(stazioneId));
    }

    /**
     * Dice se uno stato dichiarato dalla Centrale chiude i binari.
     *
     * <p>Sta separato da {@link #stazioneImpercorribile(String)} perché rispondono a due domande
     * diverse: qui si guarda il solo stato appena arrivato, là si mette insieme tutto
     * quello che il treno sa di quella stazione. Serve quando l'alert dichiara la stazione
     * agibile e il convoglio ha ancora in memoria il guasto di prima: con la domanda
     * completa la memoria vecchia vincerebbe sull'annuncio nuovo, e il treno non
     * ripartirebbe più.</p>
     */
    public static boolean statoImpercorribile(String stato) {
        return "GUASTA".equalsIgnoreCase(stato) || "MANUTENZIONE".equalsIgnoreCase(stato);
    }

    /**
     * Dice se uno stato dichiarato dalla Centrale riapre i binari. Lo fa solo ONLINE, ed è
     * l'unico permesso che un convoglio trattenuto ha di rimettersi in moto: OFFLINE non
     * basta (è una stazione che tace, non una stazione riaperta) e la chiusura di un
     * allarme nemmeno, perché parla del guasto e non dei binari.
     */
    public static boolean statoAgibile(String stato) {
        return "ONLINE".equalsIgnoreCase(stato);
    }

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
