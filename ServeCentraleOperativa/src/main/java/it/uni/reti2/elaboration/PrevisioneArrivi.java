package it.uni.reti2.elaboration;

import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Il "Processing" che il testo del progetto assegna alla Centrale Operativa:
 * <em>calcolo dei ritardi e previsioni di arrivo</em>.
 *
 * <p>Le due cose stanno nella stessa classe perche' sono la stessa operazione guardata
 * da due lati: la Centrale calcola l'orario in cui il convoglio <b>deve</b> arrivare, e
 * il ritardo non e' altro che la differenza fra quell'orario e quello in cui il treno e'
 * arrivato davvero. Prima il ritardo lo contava il digital twin a bordo del convoglio e
 * la Centrale si limitava a ricopiarlo dal payload: il dato c'era, ma era calcolato nel
 * nodo sbagliato.</p>
 *
 * <h3>Da dove esce l'orario previsto</h3>
 * <p>L'itinerario del treno e' una sequenza di tratte, e ogni tratta porta il proprio
 * {@code tempoPercorrenzaMinuti}. Sommando i tempi delle tratte che restano da percorrere
 * (la prima solo per la quota non ancora coperta, che la telemetria dichiara con
 * {@code progressPercent}) si ottiene quanto manca all'arrivo. La sequenza viene letta dal
 * database una volta sola e tenuta in memoria: la telemetria arriva ogni secondo per ogni
 * convoglio, e rifare quella query a ogni frame significherebbe interrogare il database
 * per un dato che cambia solo quando l'amministratore modifica un itinerario.</p>
 *
 * <h3>Due orologi</h3>
 * <p>Il simulatore corre accelerato: un minuto di orario ferroviario dura
 * {@code 60/fattore} secondi reali. La Centrale deve quindi tenere separate due unita':</p>
 * <ul>
 *   <li>i <b>minuti di servizio</b>, quelli dell'orario ferroviario: sono l'unita' dei
 *       tempi di percorrenza, del ritardo e del "fra quanto arriva";</li>
 *   <li>gli <b>istanti reali</b>, quelli dell'orologio di chi guarda la dashboard: sono
 *       l'unita' di {@code arrivoPrevisto}, che il browser stampa come ora del giorno.</li>
 * </ul>
 * <p>{@link #durataReale(double)} e {@link #minutiDiServizio(Duration)} sono le due
 * conversioni, e sono l'unico punto in cui il fattore di accelerazione compare.</p>
 */
@ApplicationScoped
public class PrevisioneArrivi {

    private static final Logger LOG = Logger.getLogger(PrevisioneArrivi.class);

    /**
     * Sotto questo scarto non si parla di ritardo ma di rumore di misura.
     *
     * <p>Il twin controlla la propria posizione una volta al secondo, quindi l'arrivo lo
     * annuncia con un ritardo di campionamento che arriva fino a un tick; a quello si
     * somma il tempo che il messaggio impiega ad attraversare il broker. Con il fattore di
     * accelerazione in uso (100) un solo secondo reale vale gia' quasi due minuti di
     * servizio: senza una tolleranza ogni convoglio accumulerebbe un paio di minuti di
     * ritardo a ogni fermata anche viaggiando perfettamente in orario.</p>
     */
    private static final int TOLLERANZA_MINUTI = 2;

    /** Tempo di percorrenza di riserva per una tratta che non lo dichiara. */
    private static final int MINUTI_TRATTA_PREDEFINITI = 15;

    /**
     * Quanti secondi reali dura un minuto dell'orario di servizio: e' lo stesso valore
     * configurato sui convogli, ed e' la scala con cui la Centrale legge il cronometro.
     * Non e' una duplicazione della configurazione della flotta: e' l'orario di servizio,
     * che in un impianto vero e' la Centrale a tenere e i treni a rispettare.
     */
    @ConfigProperty(name = "viaggio.fattore.accelerazione", defaultValue = "100")
    double fattoreAccelerazione;

    /** Sosta prevista in ogni fermata intermedia, in secondi reali (come sopra). */
    @ConfigProperty(name = "viaggio.sosta.secondi", defaultValue = "2")
    long sostaSecondi;

    /** Unico punto da cui questa classe legge il database. */
    @Inject
    RailwayRepository repository;

    /** La fotografia in RAM dei convogli: le previsioni si fanno su quella. */
    @Inject
    TrafficLogicEngine statoRete;

    /**
     * Percorso di ogni itinerario, memorizzato alla prima richiesta.
     * Lo svuota {@link #itinerariCambiati()} quando l'amministratore tocca tratte o
     * itinerari, che e' l'unico momento in cui questo dato puo' cambiare.
     */
    private final Map<String, List<Tappa>> percorsi = new ConcurrentHashMap<>();

    /**
     * La tabella di marcia: per ogni convoglio in viaggio, dove deve arrivare e quando.
     * La riga si scrive quando il treno esce da una stazione e si consuma quando entra in
     * quella dopo.
     */
    private final Map<String, Marcia> tabellaDiMarcia = new ConcurrentHashMap<>();

    /**
     * L'ora in cui ogni convoglio e' entrato nella stazione in cui si trova. Serve per
     * misurare la sosta: il tempo perso non e' solo quello in linea, un treno trattenuto
     * in stazione da un guasto riparte tardi e quel ritardo va contato.
     */
    private final Map<String, Instant> arriviEffettivi = new ConcurrentHashMap<>();

    /**
     * Una fermata dell'itinerario con il tempo che la separa dalla successiva
     * (0 per l'ultima). E' la stessa forma che la Centrale manda al digital twin con
     * GET /api/treni/{id}/itinerario.
     */
    public record Tappa(String stazioneId, int minutiVersoProssima) {}

    /** Impegno di orario preso da un convoglio alla partenza da una stazione. */
    private record Marcia(String stazioneAttesa, Instant arrivoProgrammato) {}

    /** Previsione completa verso una stazione: quanto manca e quante fermate ci sono prima. */
    public record Previsione(int minuti, int fermateIntermedie) {}

    // ──────────────────────────────────────────────────────────────
    // PREVISIONI DI ARRIVO
    // ──────────────────────────────────────────────────────────────

    /**
     * Ricalcola la previsione di arrivo del convoglio nella sua prossima stazione e la
     * scrive sui campi dell'entita' in cache, da dove finisce sia nella risposta REST
     * sia nell'evento WebSocket.
     *
     * <p>Un convoglio soppresso non ha nessun arrivo da prevedere: i campi tornano vuoti
     * e la dashboard smette di mostrare l'orario.</p>
     *
     * @param treno Il treno appena aggiornato dalla telemetria.
     */
    public void aggiornaPrevisione(Treno treno) {
        if (treno == null) {
            return;
        }
        Previsione previsione = "in manutenzione".equalsIgnoreCase(treno.stato)
                ? null
                : previsioneVerso(treno, treno.prossimaStazione);
        if (previsione == null) {
            treno.minutiAllArrivo = null;
            treno.arrivoPrevisto = null;
            return;
        }
        treno.minutiAllArrivo = previsione.minuti();
        treno.arrivoPrevisto = Instant.now().plus(durataReale(previsione.minuti()));
    }

    /**
     * Quanto manca al convoglio per arrivare nella stazione indicata, seguendo il suo
     * itinerario nel verso in cui sta marciando.
     *
     * <p>Si contano i tempi di percorrenza delle tratte che restano piu' la sosta di ogni
     * fermata intermedia. Della sosta in corso, se il treno e' gia' fermo in stazione, non
     * si tiene conto: la Centrale sa quanto dura una fermata, non da quanto e' cominciata
     * (per saperlo servirebbe l'orario dell'ENTRATA, che pero' si perde a ogni riavvio).</p>
     *
     * @return La previsione, oppure null se la stazione non e' davanti al convoglio nel
     *         verso corrente (l'ha gia' passata, oppure non e' nel suo itinerario).
     */
    public Previsione previsioneVerso(Treno treno, String stazioneTarget) {
        if (treno == null || treno.itinerario == null || stazioneTarget == null || stazioneTarget.isEmpty()) {
            return null;
        }
        List<Tappa> tappe = percorsoDi(treno.itinerario.id);
        int target = indiceDi(tappe, stazioneTarget);
        if (target < 0) {
            return null;
        }
        boolean andata = !"ritorno".equalsIgnoreCase(treno.direzione);

        // Convoglio fermo in stazione: si parte dalla fermata in cui si trova.
        if (treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty()) {
            int qui = indiceDi(tappe, treno.stazioneCorrente);
            if (qui < 0 || superata(qui, target, andata)) {
                return null;
            }
            if (qui == target) {
                return new Previsione(0, 0);
            }
            int fermate = Math.abs(target - qui) - 1;
            return new Previsione(arrotonda(minutiFra(tappe, qui, target)), fermate);
        }

        // Convoglio in tratta: il primo nodo utile e' la stazione verso cui sta andando.
        int prossima = indiceDi(tappe, treno.prossimaStazione);
        if (prossima < 0 || superata(prossima, target, andata)) {
            return null;
        }
        double residuo = minutiResiduiDellaTratta(tappe, prossima, andata, treno.progresso);
        if (prossima == target) {
            return new Previsione(arrotonda(residuo), 0);
        }
        // Ogni nodo da qui al precedente del target e' una fermata in cui il treno sosta.
        int fermate = Math.abs(target - prossima);
        return new Previsione(arrotonda(residuo + sostaMinuti() + minutiFra(tappe, prossima, target)), fermate);
    }

    /**
     * I convogli che una stazione deve aspettarsi, con l'orario previsto di ciascuno.
     *
     * <p>Risponde alla domanda che il testo del progetto mette fra i compiti del tecnico
     * ("quali treni ci si aspetta in ingresso e in uscita in una certa stazione") e la
     * risponde <b>sull'itinerario</b>, non sulla posizione istantanea: compare anche il
     * convoglio che passera' di li' fra tre fermate, che guardando solo la tratta adiacente
     * non si sarebbe visto.</p>
     *
     * <p><b>Limite dichiarato.</b> Si guarda avanti fino al capolinea, non oltre. Un treno
     * che ha appena superato la stazione la rivedra' dopo l'inversione di marcia, ma quel
     * ritorno non viene previsto: il tempo dipenderebbe dalla sosta del capolinea e da
     * tutte le fermate intermedie, e soprattutto l'elenco finirebbe per contenere sempre
     * tutti i convogli dell'itinerario, che e' esattamente il contrario di un tabellone
     * degli arrivi.</p>
     *
     * @param stazioneId La stazione di cui si vuole il tabellone.
     * @return Le righe ordinate per tempo mancante, la piu' vicina per prima.
     */
    public List<Map<String, Object>> treniAttesi(String stazioneId) {
        List<Map<String, Object>> attesi = new ArrayList<>();
        for (Treno treno : statoRete.getTuttiTreni()) {
            if (treno.itinerario == null) {
                continue; // convoglio senza percorso: non e' atteso da nessuna parte
            }
            if ("in manutenzione".equalsIgnoreCase(treno.stato)) {
                continue; // corsa soppressa: la stazione non lo aspetta piu'
            }
            boolean inSosta = stazioneId.equals(treno.stazioneCorrente);
            Previsione previsione = inSosta ? new Previsione(0, 0) : previsioneVerso(treno, stazioneId);
            if (previsione == null) {
                continue;
            }

            Map<String, Object> riga = new HashMap<>();
            riga.put("trenoId", treno.id);
            // In sosta il convoglio e' quello che la stazione vedra' USCIRE, gli altri
            // sono quelli che vedra' ENTRARE: sono le due liste che chiede la consegna.
            riga.put("tipo", inSosta ? "in_partenza" : "in_arrivo");
            riga.put("stato", treno.stato);
            riga.put("direzione", treno.direzione != null ? treno.direzione : "andata");
            riga.put("itinerarioId", treno.itinerario.id);
            riga.put("stazioneCorrente", treno.stazioneCorrente);
            riga.put("prossimaStazione", treno.prossimaStazione);
            riga.put("ritardoMinuti", treno.ritardo);
            riga.put("minutiAllArrivo", previsione.minuti());
            riga.put("fermateIntermedie", previsione.fermateIntermedie());
            riga.put("arrivoPrevisto", inSosta
                    ? null
                    : Instant.now().plus(durataReale(previsione.minuti())).toString());
            attesi.add(riga);
        }
        attesi.sort(Comparator.comparingInt(riga -> (Integer) riga.get("minutiAllArrivo")));
        return attesi;
    }

    // ──────────────────────────────────────────────────────────────
    // CALCOLO DEL RITARDO
    // ──────────────────────────────────────────────────────────────

    /**
     * USCITA da una stazione: la Centrale fissa l'orario entro cui il convoglio deve
     * presentarsi alla fermata successiva. E' il momento in cui nasce la tabella di
     * marcia su cui poi si misura il ritardo.
     *
     * <p>L'orario si calcola dall'istante in cui il treno e' uscito davvero, non da un
     * orario di partenza teorico: cosi' il ritardo che il convoglio si porta dietro non
     * viene contato una seconda volta a ogni tratta, e la misura di questa tratta dice
     * solo quanto tempo si e' perso <em>qui</em>.</p>
     *
     * @param quandoEUscito Istante del passaggio dichiarato dal convoglio.
     * @param direzione     Verso di marcia al momento della partenza.
     */
    public void programmaArrivo(Treno treno, String stazionePartenza, Instant quandoEUscito, String direzione) {
        if (treno == null || treno.itinerario == null || stazionePartenza == null) {
            return;
        }
        List<Tappa> tappe = percorsoDi(treno.itinerario.id);
        int qui = indiceDi(tappe, stazionePartenza);
        if (qui < 0) {
            return;
        }
        contaSostaOltreIlPrevisto(treno, stazionePartenza, qui, tappe.size(), quandoEUscito);
        boolean andata = !"ritorno".equalsIgnoreCase(direzione);
        int prossimo = andata ? qui + 1 : qui - 1;
        if (prossimo < 0 || prossimo >= tappe.size()) {
            // Capolinea: non c'e' nessun arrivo da programmare finche' non riparte.
            tabellaDiMarcia.remove(treno.id);
            return;
        }
        int percorrenza = andata
                ? tappe.get(qui).minutiVersoProssima()
                : tappe.get(prossimo).minutiVersoProssima();
        Instant previsto = quandoEUscito.plus(durataReale(percorrenza));
        tabellaDiMarcia.put(treno.id, new Marcia(tappe.get(prossimo).stazioneId(), previsto));
        LOG.debugf("⏱️ %s parte da %s: arrivo previsto a %s fra %d minuti di servizio",
                treno.id, stazionePartenza, tappe.get(prossimo).stazioneId(), percorrenza);
    }

    /**
     * ENTRATA in una stazione: si confronta l'orario reale del passaggio con quello
     * programmato alla partenza e la differenza si somma al ritardo del convoglio.
     *
     * <p>Il ritardo non scende sotto lo zero: un treno che recupera tempo torna in orario,
     * non "in anticipo" (la simulazione non fa viaggiare i convogli piu' veloci del tempo
     * di percorrenza, quindi un anticipo sarebbe solo un errore di misura).</p>
     *
     * @param quandoEEntrato Istante del passaggio dichiarato dal convoglio.
     * @return true se il ritardo del treno e' stato modificato.
     */
    public boolean registraArrivo(Treno treno, String stazioneRaggiunta, Instant quandoEEntrato) {
        if (treno == null || stazioneRaggiunta == null) {
            return false;
        }
        // L'ora dell'ingresso si annota sempre, anche quando non c'era nessun orario da
        // rispettare: e' l'inizio della sosta, e la sosta va misurata comunque.
        arriviEffettivi.put(treno.id, quandoEEntrato);
        Marcia marcia = tabellaDiMarcia.remove(treno.id);
        if (marcia == null || !marcia.stazioneAttesa().equals(stazioneRaggiunta)) {
            // Nessun impegno di orario per questa fermata: succede al primo passaggio dopo
            // l'accensione della Centrale (la tabella di marcia sta in RAM) e quando
            // l'itinerario e' cambiato mentre il treno era in viaggio.
            return false;
        }
        int scarto = arrotonda(minutiDiServizio(Duration.between(marcia.arrivoProgrammato(), quandoEEntrato)));
        if (Math.abs(scarto) < TOLLERANZA_MINUTI) {
            return false;
        }
        int prima = treno.ritardo;
        treno.ritardo = Math.max(0, treno.ritardo + scarto);
        LOG.infof("⏱️ %s arrivato a %s con %+d minuti sull'orario: ritardo da %d a %d",
                treno.id, stazioneRaggiunta, scarto, prima, treno.ritardo);
        return treno.ritardo != prima;
    }

    /**
     * Somma al ritardo il tempo passato in stazione oltre la sosta prevista.
     *
     * <p>Senza questo conto il tempo perso fermi non si vedrebbe: l'orario di arrivo nella
     * fermata successiva viene fissato a partire dall'uscita reale, quindi un convoglio
     * trattenuto mezz'ora e poi ripartito percorrerebbe la tratta perfettamente in orario
     * e risulterebbe puntuale. E' il caso piu' frequente di tutti, perche' e' quello che
     * succede quando una stazione guasta trattiene un treno in banchina.</p>
     *
     * <p>Al capolinea la sosta prevista e' doppia: il convoglio deve invertire la marcia,
     * e il digital twin la raddoppia per conto suo.</p>
     *
     * @param stazione La stazione da cui il convoglio sta uscendo.
     * @param qui      Posizione di quella stazione nel percorso.
     * @param fermate  Quante fermate ha il percorso, per riconoscere i due capolinea.
     */
    private void contaSostaOltreIlPrevisto(Treno treno, String stazione, int qui, int fermate,
                                           Instant quandoEUscito) {
        Instant entrato = arriviEffettivi.remove(treno.id);
        if (entrato == null) {
            return; // prima partenza della corsa: non c'e' nessuna sosta da misurare
        }
        boolean capolinea = qui == 0 || qui == fermate - 1;
        double sostaPrevista = capolinea ? sostaMinuti() * 2 : sostaMinuti();
        int eccesso = arrotonda(minutiDiServizio(Duration.between(entrato, quandoEUscito)) - sostaPrevista);
        if (eccesso < TOLLERANZA_MINUTI) {
            return;
        }
        int prima = treno.ritardo;
        treno.ritardo = Math.max(0, treno.ritardo + eccesso);
        LOG.infof("⏱️ %s trattenuto in %s per %d minuti oltre la sosta: ritardo da %d a %d",
                treno.id, stazione, eccesso, prima, treno.ritardo);
    }

    /** Il convoglio non c'e' piu' (eliminato o soppresso): via il suo orario e la sua sosta. */
    public void dimenticaTreno(String trenoId) {
        tabellaDiMarcia.remove(trenoId);
        arriviEffettivi.remove(trenoId);
    }

    /**
     * L'amministratore ha toccato tratte o itinerari: i percorsi memorizzati non valgono
     * piu' e nemmeno gli orari calcolati con i tempi di percorrenza di prima.
     */
    public void itinerariCambiati() {
        percorsi.clear();
        tabellaDiMarcia.clear();
        arriviEffettivi.clear();
    }

    // ──────────────────────────────────────────────────────────────
    // METODI DI SUPPORTO PRIVATI
    // ──────────────────────────────────────────────────────────────

    /** Il percorso dell'itinerario, letto dal database solo la prima volta. */
    private List<Tappa> percorsoDi(String itinerarioId) {
        if (itinerarioId == null) {
            return List.of();
        }
        return percorsi.computeIfAbsent(itinerarioId, this::leggiPercorso);
    }

    /**
     * Ricostruisce la sequenza delle fermate dalle righe di Itinerario_Tratta: la partenza
     * di ogni tratta porta il tempo verso la fermata dopo, e in coda si aggiunge l'arrivo
     * dell'ultima con tempo zero.
     *
     * <p>La transazione si apre qui perche' le tratte si leggono in modo pigro e questo
     * metodo viene chiamato anche dal consumer MQTT, che una sessione JPA aperta non ce
     * l'ha; se invece il chiamante e' un endpoint REST gia' transazionale ci si accoda
     * alla sua ({@code joiningExisting}).</p>
     */
    private List<Tappa> leggiPercorso(String itinerarioId) {
        return QuarkusTransaction.joiningExisting().call(() -> {
            List<ItinerarioTratta> tratte = repository.tratteOrdinateDi(itinerarioId);
            List<Tappa> tappe = new ArrayList<>();
            for (ItinerarioTratta riga : tratte) {
                Integer minuti = riga.tratta.tempoPercorrenzaMinuti;
                tappe.add(new Tappa(riga.tratta.stazionePartenza.id,
                        Math.max(1, minuti != null ? minuti : MINUTI_TRATTA_PREDEFINITI)));
            }
            if (!tratte.isEmpty()) {
                tappe.add(new Tappa(tratte.get(tratte.size() - 1).tratta.stazioneArrivo.id, 0));
            }
            return tappe;
        });
    }

    /** Posizione di una stazione nel percorso, -1 se quell'itinerario non la tocca. */
    private int indiceDi(List<Tappa> tappe, String stazioneId) {
        if (stazioneId == null || stazioneId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < tappe.size(); i++) {
            if (tappe.get(i).stazioneId().equals(stazioneId)) {
                return i;
            }
        }
        return -1;
    }

    /** true se la stazione bersaglio sta dietro al convoglio nel verso in cui marcia. */
    private boolean superata(int posizione, int target, boolean andata) {
        return andata ? target < posizione : target > posizione;
    }

    /**
     * Minuti di servizio fra due fermate del percorso, soste intermedie comprese.
     * Il verso di marcia non entra nel conto: la tratta dura uguale in andata e al
     * ritorno, cambia solo l'ordine in cui la si percorre.
     */
    private double minutiFra(List<Tappa> tappe, int da, int a) {
        int primo = Math.min(da, a);
        int ultimo = Math.max(da, a);
        double minuti = 0;
        for (int i = primo; i < ultimo; i++) {
            minuti += tappe.get(i).minutiVersoProssima();
        }
        // Fra le due fermate estreme ce ne sono (ultimo - primo - 1): in ognuna si sosta.
        return minuti + sostaMinuti() * (ultimo - primo - 1);
    }

    /**
     * Quanto manca per finire la tratta che il convoglio sta percorrendo, in proporzione
     * alla quota non ancora coperta dichiarata dalla telemetria.
     *
     * @param prossima Indice della fermata verso cui sta andando.
     */
    private double minutiResiduiDellaTratta(List<Tappa> tappe, int prossima, boolean andata, double progresso) {
        // In andata il tempo della tratta e' scritto sulla fermata da cui e' partito, al
        // ritorno il segmento e' lo stesso ma lo si percorre al contrario, quindi il tempo
        // sta sulla fermata verso cui sta andando.
        int portaIlTempo = andata ? prossima - 1 : prossima;
        if (portaIlTempo < 0 || portaIlTempo >= tappe.size()) {
            return 0;
        }
        double quotaResidua = (100.0 - Math.max(0.0, Math.min(100.0, progresso))) / 100.0;
        return tappe.get(portaIlTempo).minutiVersoProssima() * quotaResidua;
    }

    /** La sosta in fermata, espressa nei minuti dell'orario di servizio. */
    private double sostaMinuti() {
        return minutiDiServizio(Duration.ofSeconds(sostaSecondi));
    }

    /** Quanto tempo reale dura, alla velocita' della simulazione, un tratto di orario. */
    private Duration durataReale(double minutiDiServizio) {
        return Duration.ofMillis(Math.round(minutiDiServizio * 60_000.0 / fattoreAccelerazione));
    }

    /** La conversione inversa: quanti minuti di orario valgono i secondi reali trascorsi. */
    private double minutiDiServizio(Duration reale) {
        return reale.toMillis() * fattoreAccelerazione / 60_000.0;
    }

    /** I minuti si mostrano interi: la frazione non aggiunge informazione a un tabellone. */
    private int arrotonda(double minuti) {
        return (int) Math.round(minuti);
    }
}
