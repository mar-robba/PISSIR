package it.uni.reti2.elaboration;

import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.persistence.RailwayRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Il "Processing" che il testo del progetto assegna alla Centrale Operativa:
 * <em>calcolo dei ritardi e previsioni di arrivo</em>.
 *
 * <p>Le due cose stanno nella stessa classe perche' sono la stessa operazione guardata
 * da due lati: la Centrale calcola l'orario in cui il convoglio <b>deve</b> arrivare, e
 * il ritardo non e' altro che la differenza fra quell'orario e quello in cui il treno e'
 * arrivato davvero. Per questo il ritardo lo calcola la Centrale, che ha l'orario, e non
 * il digital twin a bordo.</p>
 *
 * <h3>Il ritardo cresce mentre il treno e' fermo</h3>
 * <p>Il ritardo e' quanto il convoglio e' indietro rispetto all'orario nel punto in cui si
 * trova: se fin li' ha impiegato esattamente il tempo delle tratte percorse piu' quello
 * delle soste previste, e' in orario. Il conto ha due parti. Quella <b>misurata</b> si
 * chiude a ogni passaggio davanti ai sensori di stazione, con l'ora vera dell'ingresso e
 * dell'uscita. Quella <b>in corso</b> e' il tempo che il convoglio sta perdendo adesso,
 * dall'ultimo passaggio: la sosta che dura piu' del previsto, oppure la tratta in cui
 * l'avanzamento resta indietro rispetto al tempo trascorso dalla partenza. Si rifa' a ogni
 * frame di telemetria, ed e' quella che fa crescere il ritardo di un treno trattenuto da
 * una stazione guasta per tutto il tempo in cui resta fermo, invece di farlo comparire
 * tutto insieme quando riparte. Al passaggio successivo la stima lascia il posto alla
 * misura dello stesso tempo, quindi niente viene contato due volte.</p>
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
public class PrevisioneArrivi {

    private static final Logger LOG = Logger.getLogger(PrevisioneArrivi.class.getName());

    /**
     * Il quanto di campionamento del convoglio: la sua macchina a stati gira una volta al
     * secondo, quindi entrata e uscita le annuncia sul tick successivo a quello in cui sono
     * successe davvero.
     */
    private static final Duration TICK_DEL_TWIN = Duration.ofSeconds(1);

    /**
     * Quanti tick di scarto restano rumore di misura e non diventano ritardo: uno lo mette
     * il campionamento del twin, l'altro il viaggio del passaggio fino a qui (sensore di
     * stazione, broker, consumer).
     */
    private static final int TICK_DI_TOLLERANZA = 2;

    /** Tempo di percorrenza di riserva per una tratta che non lo dichiara. */
    private static final int MINUTI_TRATTA_PREDEFINITI = 15;

    /**
     * Quanti secondi reali dura un minuto dell'orario di servizio: e' lo stesso valore
     * configurato sui convogli, ed e' la scala con cui la Centrale legge il cronometro.
     * Non e' una duplicazione della configurazione della flotta: e' l'orario di servizio,
     * che in un impianto vero e' la Centrale a tenere e i treni a rispettare.
     */
    public double fattoreAccelerazione = 100; // viaggio.fattore.accelerazione

    /** Sosta prevista in ogni fermata intermedia, in secondi reali (come sopra). */
    public long sostaSecondi = 2; // viaggio.sosta.secondi

    /** Unico punto da cui questa classe legge il database. */
    public RailwayRepository repository;

    /** La fotografia in RAM dei convogli: le previsioni si fanno su quella. */
    public TrafficLogicEngine statoRete;

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
     * La sosta in corso di ogni convoglio fermo in una stazione: dove, da quando e quanto
     * deve durare. Serve per misurare la sosta: il tempo perso non e' solo quello in
     * linea, un treno trattenuto in stazione da un guasto riparte tardi e quel ritardo va
     * contato. La riga si scrive quando il treno entra e si consuma quando esce.
     */
    private final Map<String, Sosta> soste = new ConcurrentHashMap<>();

    /**
     * La quota di ritardo che ogni convoglio sta accumulando dall'ultimo passaggio, gia'
     * compresa in {@code treno.ritardo}. Si tiene a parte per poterla togliere: a ogni frame
     * di telemetria la stima nuova prende il posto della vecchia, e al passaggio il suo
     * posto lo prende la misura.
     */
    private final Map<String, Integer> ritardoInCorso = new ConcurrentHashMap<>();

    /**
     * Una fermata dell'itinerario con il tempo che la separa dalla successiva
     * (0 per l'ultima). E' la stessa forma che la Centrale manda al digital twin con
     * GET /api/treni/{id}/itinerario.
     */
    public record Tappa(String stazioneId, int minutiVersoProssima) {}

    /** Impegno di orario preso da un convoglio alla partenza da una stazione. */
    private record Marcia(String stazioneAttesa, Instant partenza, Instant arrivoProgrammato) {}

    /** Sosta di un convoglio in una stazione, con la durata prevista in minuti di servizio. */
    private record Sosta(String stazioneId, Instant inizio, double minutiPrevisti) {}

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
        // Corsa soppressa o finita al capolinea: non c'e' nessun arrivo da prevedere. Per il
        // capolinea basterebbe la prossima stazione vuota, ma dirlo qui rende la regola
        // esplicita e regge anche l'ultimo frame di telemetria che arrivasse ancora con la
        // fermata successiva valorizzata.
        Previsione previsione = corsaFinita(treno) ? null : previsioneVerso(treno, treno.prossimaStazione);
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
            if (StatiCanonici.TRENO_CAPOLINEA.equalsIgnoreCase(treno.stato)) {
                // Corsa finita: il convoglio e' fermo al capolinea e non ne uscira'. Senza
                // questo salto resterebbe in eterno fra i treni "in partenza" dal tabellone
                // di quella stazione, con zero minuti all'arrivo, aspettato da tutti.
                continue;
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
            // La direzione e' quella che la Centrale garantisce, e resta null quando non
            // la garantisce. Prima qui c'era un "andata" di ripiego: un valore inventato,
            // indistinguibile da uno vero proprio nel caso in cui il convoglio non
            // trasmette piu' e nessuno sa piu' da che parte stia andando.
            riga.put("direzione", treno.direzioneCorrente);
            riga.put("itinerarioId", treno.itinerario.id);
            riga.put("stazioneCorrente", treno.stazioneCorrente);
            riga.put("prossimaStazione", treno.prossimaStazione);
            // Il tabellone si costruisce sull'itinerario, quindi il convoglio ci compare
            // anche quando la sua posizione non e' confermata: la stazione lo aspetta lo
            // stesso. Quello che cambia e' quanto vale l'orario qui accanto, ed e' giusto
            // che chi legge lo sappia invece di prendere per buona una stima calcolata
            // sull'ultima posizione certa, che puo' essere vecchia.
            riga.put("posizioneNota", treno.posizioneNota);
            riga.put("incertezzaPosizione", treno.incertezzaPosizione);
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
        // La sosta si chiude prima di cercare la stazione nel percorso: il treno da li' e'
        // uscito comunque, e una sosta lasciata aperta continuerebbe a contare ritardo
        // mentre il convoglio viaggia.
        contaSostaOltreIlPrevisto(treno, stazionePartenza, quandoEUscito);
        List<Tappa> tappe = percorsoDi(treno.itinerario.id);
        int qui = indiceDi(tappe, stazionePartenza);
        if (qui < 0) {
            return;
        }
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
        tabellaDiMarcia.put(treno.id, new Marcia(tappe.get(prossimo).stazioneId(), quandoEUscito, previsto));
        LOG.fine(String.format("⏱️ %s parte da %s: arrivo previsto a %s fra %d minuti di servizio",
                treno.id, stazionePartenza, tappe.get(prossimo).stazioneId(), percorrenza));
    }

    /**
     * ENTRATA in una stazione: si confronta l'orario reale del passaggio con quello
     * programmato alla partenza e la differenza si somma al ritardo del convoglio.
     *
     * <p>Il ritardo non scende sotto lo zero: un treno che recupera tempo torna in orario,
     * non "in anticipo" (la simulazione non fa viaggiare i convogli piu' veloci del tempo
     * di percorrenza, quindi un anticipo sarebbe solo un errore di misura).</p>
     *
     * <p>Il tempo perso su questa tratta la telemetria l'aveva gia' stimato e sommato al
     * ritardo: la stima si toglie prima di sommare la misura, che conta lo stesso tempo.</p>
     *
     * @param quandoEEntrato Istante del passaggio dichiarato dal convoglio.
     * @return true se il ritardo misurato del treno e' stato modificato.
     */
    public boolean registraArrivo(Treno treno, String stazioneRaggiunta, Instant quandoEEntrato) {
        if (treno == null || stazioneRaggiunta == null) {
            return false;
        }
        // L'ora dell'ingresso si annota sempre, anche quando non c'era nessun orario da
        // rispettare: e' l'inizio della sosta, e la sosta va misurata comunque. Non quando la
        // corsa e' finita (capolinea marcato): da li' il treno non riparte, e una sosta
        // aperta per sempre diventerebbe tutta ritardo il giorno in cui lo si rimette in
        // servizio.
        if (!corsaFinita(treno)) {
            soste.put(treno.id, new Sosta(stazioneRaggiunta, quandoEEntrato, sostaPrevistaIn(treno, stazioneRaggiunta)));
        }
        Marcia marcia = tabellaDiMarcia.remove(treno.id);
        if (marcia == null || !marcia.stazioneAttesa().equals(stazioneRaggiunta)) {
            // Nessun impegno di orario per questa fermata: succede al primo passaggio dopo
            // l'accensione della Centrale (la tabella di marcia sta in RAM) e dopo che il
            // convoglio e' stato sganciato dal suo itinerario.
            return false;
        }
        int prima = togliQuotaInCorso(treno);
        // Il confronto con la tolleranza si fa sullo scarto vero, non su quello arrotondato:
        // il quanto del twin vale 1,67 minuti di orario, che arrotondati diventano 2 e
        // passavano un controllo scritto proprio per fermarli.
        double scarto = minutiDiServizio(Duration.between(marcia.arrivoProgrammato(), quandoEEntrato));
        if (Math.abs(scarto) < tolleranzaMinuti()) {
            return false;
        }
        treno.ritardo = Math.max(0, prima + arrotonda(scarto));
        LOG.info(String.format("⏱️ %s arrivato a %s con %+d minuti sull'orario: ritardo da %d a %d",
                treno.id, stazioneRaggiunta, arrotonda(scarto), prima, treno.ritardo));
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
     * <p>Come all'arrivo, la stima fatta con la telemetria durante la sosta lascia il
     * posto alla misura.</p>
     *
     * @param stazione La stazione da cui il convoglio sta uscendo.
     */
    private void contaSostaOltreIlPrevisto(Treno treno, String stazione, Instant quandoEUscito) {
        Sosta sosta = soste.remove(treno.id);
        if (sosta == null || !sosta.stazioneId().equals(stazione)) {
            return; // nessuna sosta aperta in questa stazione: non c'e' niente da misurare
        }
        int prima = togliQuotaInCorso(treno);
        // Come per l'arrivo: si confronta l'eccesso vero con la tolleranza e si arrotonda solo
        // quello che finisce davvero nel ritardo.
        double eccesso = minutiDiServizio(Duration.between(sosta.inizio(), quandoEUscito)) - sosta.minutiPrevisti();
        if (eccesso < tolleranzaMinuti()) {
            return;
        }
        treno.ritardo = Math.max(0, prima + arrotonda(eccesso));
        LOG.info(String.format("⏱️ %s trattenuto in %s per %d minuti oltre la sosta: ritardo da %d a %d",
                treno.id, stazione, arrotonda(eccesso), prima, treno.ritardo));
    }

    /**
     * Ricalcola il ritardo del convoglio nel punto in cui si trova adesso: quello misurato
     * ai passaggi piu' quello che sta accumulando dall'ultimo. Lo chiama AutomaTreno a ogni
     * frame di telemetria e dopo ogni passaggio.
     *
     * <p>Il convoglio che marcia in orario o che sta facendo la sua sosta non accumula
     * niente. Quello fermo dove non dovrebbe (trattenuto da una stazione chiusa, in
     * emergenza, oltre la sosta) si', e il suo ritardo cresce finche' resta fermo.</p>
     *
     * <p>A corsa finita (capolinea, soppressione, convoglio sganciato dall'itinerario) il
     * ritardo resta quello raggiunto, perche' non c'e' piu' un orario da rispettare. La sosta
     * aperta si chiude: un treno rimesso in servizio dopo ore al capolinea si troverebbe
     * addosso tutte quelle ore. L'arrivo atteso invece resta finche' c'e' un itinerario: il
     * frame che annuncia il capolinea puo' arrivare prima dell'ENTRATA, e l'ultima tratta va
     * misurata lo stesso.</p>
     *
     * @param adesso L'istante della misura.
     */
    public void aggiornaRitardo(Treno treno, Instant adesso) {
        if (treno == null) {
            return;
        }
        if (corsaFinita(treno)) {
            soste.remove(treno.id);
            if (treno.itinerario == null) {
                tabellaDiMarcia.remove(treno.id); // sganciato: l'arrivo che aspettava non ci sara'
            }
            return;
        }
        boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
        if (inStazione && sostaValida(treno) == null && marciaValida(treno) == null) {
            // Il convoglio e' fermo in una stazione in cui non e' mai entrato: e' la prima
            // fermata della corsa, da cui si esce senza entrare, oppure quella in cui e' stato
            // rimesso con un itinerario nuovo. La sosta comincia adesso, ed e' quella
            // semplice, come la fa il digital twin alla partenza.
            soste.put(treno.id, new Sosta(treno.stazioneCorrente, adesso, sostaMinuti()));
        }
        int misurato = ritardoMisurato(treno);
        int inCorso = quotaInCorso(treno, adesso);
        ritardoInCorso.put(treno.id, inCorso);
        treno.ritardo = misurato + inCorso;
    }

    /** @return Il ritardo misurato ai passaggi: quello del convoglio senza la quota in corso. */
    public int ritardoMisurato(Treno treno) {
        return Math.max(0, treno.ritardo - ritardoInCorso.getOrDefault(treno.id, 0));
    }

    /**
     * Il convoglio non c'e' piu' (eliminato o soppresso): via il suo orario e la sua sosta.
     * Il ritardo che aveva resta com'era, stima compresa: nessun passaggio la misurera' meglio.
     */
    public void dimenticaTreno(String trenoId) {
        tabellaDiMarcia.remove(trenoId);
        soste.remove(trenoId);
        ritardoInCorso.remove(trenoId);
    }

    /**
     * L'amministratore ha toccato tratte o itinerari: i percorsi memorizzati non valgono piu'.
     *
     * <p>La tabella di marcia e le soste invece restano. Sono orari che i convogli hanno gia'
     * preso: chi e' in tratta la finisce con il percorso di prima (il digital twin installa
     * quello nuovo solo all'arrivo), e chi e' fermo in stazione ci e' entrato davvero a
     * quell'ora. Svuotarle azzerava il ritardo che sta maturando un treno trattenuto solo
     * perche' qualcuno aveva modificato un itinerario. Una riga rimasta indietro non fa
     * danni: ognuna dice a quale stazione si riferisce, e quando non e' piu' quella del
     * convoglio non viene usata.</p>
     */
    public void itinerariCambiati() {
        percorsi.clear();
    }

    // ──────────────────────────────────────────────────────────────
    // METODI DI SUPPORTO PRIVATI
    // ──────────────────────────────────────────────────────────────

    /**
     * Corsa soppressa, finita al capolinea o senza un itinerario: non c'e' nessun arrivo
     * da prevedere e nessun orario da rispettare.
     */
    private boolean corsaFinita(Treno treno) {
        return treno.itinerario == null
                || "in manutenzione".equalsIgnoreCase(treno.stato)
                || StatiCanonici.TRENO_CAPOLINEA.equalsIgnoreCase(treno.stato);
    }

    /**
     * La sosta prevista in una stazione. Agli estremi dell'itinerario e' doppia: il
     * convoglio deve invertire la marcia, e il digital twin la raddoppia per conto suo.
     */
    private double sostaPrevistaIn(Treno treno, String stazioneId) {
        List<Tappa> tappe = percorsoDi(treno.itinerario != null ? treno.itinerario.id : null);
        int qui = indiceDi(tappe, stazioneId);
        boolean estremo = qui >= 0 && (qui == 0 || qui == tappe.size() - 1);
        return estremo ? sostaMinuti() * 2 : sostaMinuti();
    }

    /**
     * La sosta aperta del convoglio, se riguarda ancora il punto in cui si trova: la
     * stazione in cui e' fermo, oppure la tratta appena imboccata, quando la telemetria dice
     * gia' che e' partito e l'USCITA non e' ancora arrivata (fa piu' strada: passa dai
     * sensori e dalla stazione).
     *
     * @return La sosta, oppure null se non c'e' o e' di un'altra stazione.
     */
    private Sosta sostaValida(Treno treno) {
        Sosta sosta = soste.get(treno.id);
        if (sosta == null) {
            return null;
        }
        boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
        return !inStazione || sosta.stazioneId().equals(treno.stazioneCorrente) ? sosta : null;
    }

    /**
     * L'arrivo che il convoglio deve rispettare, se riguarda ancora il punto in cui si
     * trova: la tratta verso la stazione attesa, oppure quella stazione, quando la telemetria
     * dice gia' che e' arrivato e l'ENTRATA non e' ancora arrivata.
     *
     * @return La marcia, oppure null se non c'e' o porta da un'altra parte.
     */
    private Marcia marciaValida(Treno treno) {
        Marcia marcia = tabellaDiMarcia.get(treno.id);
        if (marcia == null) {
            return null;
        }
        boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
        String dove = inStazione ? treno.stazioneCorrente : treno.prossimaStazione;
        return marcia.stazioneAttesa().equals(dove) ? marcia : null;
    }

    /**
     * Quanti minuti di orario il convoglio sta perdendo dall'ultimo passaggio. In sosta sono
     * quelli oltre la sosta prevista. In tratta e' la differenza fra il tempo trascorso dalla
     * partenza e quello che l'orario assegna alla strada fatta, cioe' alla quota di tratta
     * dichiarata dalla telemetria: un treno fermo a meta' tratta non avanza mentre l'orologio
     * corre, e la differenza cresce.
     *
     * <p>Sotto la tolleranza e' rumore di misura, come al passaggio: la quota di tratta
     * arriva con il frame, che puo' essere vecchio di un tick.</p>
     */
    private int quotaInCorso(Treno treno, Instant adesso) {
        Sosta sosta = sostaValida(treno);
        Marcia marcia = marciaValida(treno);
        double perso;
        if (sosta != null) {
            perso = minutiDiServizio(Duration.between(sosta.inizio(), adesso)) - sosta.minutiPrevisti();
        } else if (marcia != null) {
            double percorrenza = minutiDiServizio(Duration.between(marcia.partenza(), marcia.arrivoProgrammato()));
            double stradaFatta = percorrenza * Math.max(0.0, Math.min(100.0, treno.progresso)) / 100.0;
            perso = minutiDiServizio(Duration.between(marcia.partenza(), adesso)) - stradaFatta;
        } else {
            return 0; // nessun orario con cui confrontarsi
        }
        return perso < tolleranzaMinuti() ? 0 : arrotonda(perso);
    }

    /**
     * Toglie dal ritardo del convoglio la stima del tempo perso dall'ultimo passaggio: la
     * chiama il passaggio successivo, che quel tempo lo misura.
     *
     * @return Il ritardo misurato, a cui sommare la misura del passaggio.
     */
    private int togliQuotaInCorso(Treno treno) {
        treno.ritardo = ritardoMisurato(treno);
        ritardoInCorso.remove(treno.id);
        return treno.ritardo;
    }

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
     * <p>Il repository legge le tratte con le loro stazioni gia' dentro, quindi qui non
     * serve nessuna transazione: se il chiamante ne ha una aperta la lettura usa la sua
     * connessione, altrimenti se ne apre una apposta.</p>
     */
    private List<Tappa> leggiPercorso(String itinerarioId) {
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

    /**
     * Sotto questo scarto non si parla di ritardo ma di rumore di misura.
     *
     * <p>Non e' un numero fisso perche' non lo e' quello che deve coprire: il rumore nasce
     * da un tempo <em>reale</em> (i tick del twin e la latenza del passaggio) letto con il
     * righello dell'orario di servizio, quindi cresce insieme al fattore di accelerazione.
     * Con il fattore 100 un solo secondo reale vale gia' 1,67 minuti di orario: la vecchia
     * tolleranza di 2 minuti fissi ci passava sotto per un soffio, e ogni convoglio si
     * prendeva due minuti di ritardo a ogni fermata pur viaggiando in perfetto orario.</p>
     */
    private double tolleranzaMinuti() {
        return minutiDiServizio(TICK_DEL_TWIN.multipliedBy(TICK_DI_TOLLERANZA));
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
