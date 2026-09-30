package it.uni.reti2;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

//La stazione fa parte dello stato. Gli automi del treno sono automi estesi, come le macchine a stati UML che disegni in PlantUML. Lo stato non è solo la fase (le 6 bolle di T5): comprende anche le variabili dell'automa, cioè indiceStazione, direzione, stazioneCorrente, prossimaStazione, posizione e passeggeri. La bolla dice "IN_STAZIONE", ma quale stazione lo dicono le variabili. Senza indiceStazione e direzione, al tick successivo tickInStazione non saprebbe verso dove partire.
/**
 * L'automa del viaggio (T5, insieme a direzione e indice di T7): dice DOVE si trova il
 * convoglio lungo l'itinerario, cioè il valore di TrainDB.faseViaggio.
 *
 * <p>Le fasi sono sei: SENZA_ITINERARIO, IN_STAZIONE, IN_VIAGGIO, BLOCCATO_IN_STAZIONE,
 * BLOCCATO_IN_TRATTA e FERMO_CAPOLINEA. Il blocco è diviso in due perché le due situazioni
 * si comportano in modo diverso: fermo al binario il treno aspetta e basta, fermo a metà
 * tratta ha un avanzamento da congelare, e allo sblocco riprende la marcia e non la sosta.
 * Prima era una fase sola e la differenza stava in una variabile a parte (la fase di prima
 * del blocco), che era uno stato nascosto.</p>
 *
 * <p>La classe non decide quando muoversi e non parla con nessuno. Offre le guardie, cioè
 * metodi che leggono lo stato e rispondono senza scrivere niente (compresi quelli che
 * calcolano in anticipo la {@link Fermata} in cui il treno si ritroverà), e le mosse, cioè
 * metodi che scrivono senza controllare più niente. A chiamarle nell'ordine giusto è
 * GestoreEventi, che fa anche le azioni: le pubblicazioni USCITA/ENTRATA e i log.</p>
 *
 * <p>È l'unica classe che scrive la fase, la posizione, la direzione e l'itinerario del treno.</p>
 */
public class AutomaViaggio {

    /**
     * Database locale del treno: qui l'automa legge e scrive fase, posizione e itinerario.
     */
    TrainDB trainDB;

    /**
     * Durata della sosta in stazione, in secondi reali. Come i due campi qui sotto la
     * assegna il main leggendo application.properties (viaggio.sosta.secondi).
     */
    long sostaSecondi; //per una più corretta simulazione ogni stazione dovrebbe avere la propria

    /**
     * Fattore di accelerazione della simulazione: 1 minuto simulato dura
     * 60/fattore secondi reali (con fattore 10 una tratta da 15 minuti dura 90s).
     * Property viaggio.fattore.accelerazione.
     */
    double fattoreAccelerazione;

    /** Se true il viaggio parte da solo appena l'itinerario è disponibile (viaggio.autostart). */
    boolean autostart; // glielo mai chiesto ???

    private final Random random = new Random();

    /** Sosta da rispettare nella stazione corrente (raddoppiata all'estremo dell'itinerario). */
    private long sostaCorrenteSecondi;

    /**
     * Itinerario nuovo scaricato mentre il treno stava percorrendo una tratta: il convoglio
     * è fisicamente su un binario fra due stazioni e non può teletrasportarsi, quindi il
     * percorso resta qui in attesa e viene installato quando entra nella stazione di arrivo.
     *
     * <p>Il capolinea viaggia insieme alle tappe e non viene scritto subito nel TrainDB:
     * fino all'arrivo il convoglio sta percorrendo il vecchio percorso, e mescolare le
     * fermate di quello con il capolinea di questo lo farebbe fermare in un punto che sul
     * suo itinerario non chiude niente.</p>
     */
    private Itinerario itinerarioPendente = null;

    /**
     * Dove si ritrova il convoglio dopo una mossa che lo lascia fermo in una stazione:
     * l'avvio alla prima tappa, l'arrivo in fondo a una tratta, il riaggancio a un
     * itinerario nuovo. Si calcola tutta nella fase delle guardie, leggendo lo stato di
     * prima, e la mossa {@link #fermaIn(Fermata)} la scrive senza fare più nessun conto.
     *
     * @param tappa             La stazione.
     * @param indice            Il suo indice nell'itinerario.
     * @param direzione         La direzione di marcia, già invertita se lì si gira.
     * @param fase              IN_STAZIONE, BLOCCATO_IN_STAZIONE o FERMO_CAPOLINEA.
     * @param prossimaStazione  La prossima fermata, null al capolinea.
     * @param stazioneBloccante La stazione che trattiene il treno, null se non è trattenuto.
     * @param sostaSecondi      La sosta da rispettare prima di ripartire.
     * @param progressPercent   100 se il treno ci è arrivato percorrendo la tratta, 0 se ci
     *                          è stato messo (avvio, riaggancio).
     * @param passeggeri        I passeggeri a bordo dopo la fermata.
     */
    public record Fermata(TrainDB.TappaItinerario tappa, int indice, String direzione, String fase,
                          String prossimaStazione, String stazioneBloccante, long sostaSecondi,
                          double progressPercent, int passeggeri) {
    }

    // ===============================================================
    // GUARDIE: leggono e rispondono, non scrivono niente
    // ===============================================================

    /** @return true se il convoglio è su un binario fra due stazioni, in marcia o fermo. */
    public boolean inTratta() {
        return "IN_VIAGGIO".equals(trainDB.faseViaggio) || "BLOCCATO_IN_TRATTA".equals(trainDB.faseViaggio);
    }

    /** @return true se il convoglio è trattenuto da una stazione chiusa. */
    public boolean bloccato() {
        return "BLOCCATO_IN_STAZIONE".equals(trainDB.faseViaggio) || "BLOCCATO_IN_TRATTA".equals(trainDB.faseViaggio);
    }

    /**
     * Guardia dell'avvio: nessun viaggio in corso, un itinerario c'è e il viaggio deve
     * partire da solo.
     */
    public boolean puoAvviarsi() {
        return "SENZA_ITINERARIO".equals(trainDB.faseViaggio) && !trainDB.itinerario.isEmpty() && autostart;
    }

    /**
     * Guardia della partenza, per la parte del tempo: la sosta nella stazione corrente è finita.
     */
    public boolean sostaScaduta() {
        // Il confronto è in millisecondi e non con getSeconds(): quello tronca, e siccome i
        // tick non cadono mai esattamente sul secondo bastava un millesimo di anticipo perché
        // una sosta di 2s venisse letta come "1 secondo trascorso" e il treno aspettasse un
        // tick in più. Un secondo reale, col fattore 100, vale quasi due minuti di orario:
        // era ritardo che il convoglio si prendeva a ogni singola fermata senza motivo.
        return trainDB.tArrivoInStazione != null
                && Duration.between(trainDB.tArrivoInStazione, Instant.now()).toMillis() >= sostaCorrenteSecondi * 1000;
    }

    /**
     * Guardia della partenza, per la parte dei binari: la stazione corrente o la prossima
     * non si possono attraversare (guaste, o con la squadra di manutenzione al lavoro).
     *
     * <p>Serve quando l'alert è arrivato mentre il treno era altrove: in quel momento la
     * stazione non era né la corrente né la prossima, quindi nessuno lo aveva bloccato, e
     * senza questo controllo partirebbe verso un binario inagibile.</p>
     *
     * @return La stazione che trattiene il treno, oppure null se i binari sono liberi.
     */
    public String stazioneChiusaInPartenza(TrainDB.TappaItinerario corrente, TrainDB.TappaItinerario prossima) {
        if (trainDB.stazioneImpercorribile(corrente.id)) {
            return corrente.id;
        }
        if (trainDB.stazioneImpercorribile(prossima.id)) {
            return prossima.id;
        }
        return null;
    }

    /**
     * Guardia del blocco su alert: la stazione chiusa è una di quelle che il treno ha davanti
     * adesso, cioè la fermata in cui si trova o la prossima. È il filtro che evita di fermare
     * il convoglio per una stazione dall'altra parte della rete, che con questo itinerario
     * non incontrerà mai; se l'alert arriva mentre il treno è altrove non si perde niente,
     * perché lo stato resta annotato e lo ricontrolla la partenza
     * ({@link #stazioneChiusaInPartenza}).
     *
     * <p>Il treno poi deve essere in una fase in cui ha senso fermarlo: un convoglio senza
     * itinerario o al capolinea non sta andando da nessuna parte, e uno già trattenuto lo è
     * già (lo sblocco lo decide la stazione che lo trattiene).</p>
     */
    public boolean puoBloccarsiPer(String stazioneId) {
        boolean inMarciaOInSosta = "IN_STAZIONE".equals(trainDB.faseViaggio) || "IN_VIAGGIO".equals(trainDB.faseViaggio);
        return inMarciaOInSosta
                && (stazioneId.equals(trainDB.stazioneCorrente) || stazioneId.equals(trainDB.prossimaStazione));
    }

    /** @return La fase in cui porta il blocco, che dipende da dove si trova il treno adesso. */
    public String faseDiBlocco() {
        return "IN_VIAGGIO".equals(trainDB.faseViaggio) ? "BLOCCATO_IN_TRATTA" : "BLOCCATO_IN_STAZIONE";
    }

    /** Guardia dello sblocco: è proprio la stazione che trattiene il treno a essere riaperta. */
    public boolean bloccatoDa(String stazioneId) {
        return bloccato() && stazioneId.equals(trainDB.stazioneBloccante);
    }

    /** @return La fase che si riprende allo sblocco: la marcia se il treno era in tratta, la sosta se era al binario. */
    public String faseDiSblocco() {
        return "BLOCCATO_IN_TRATTA".equals(trainDB.faseViaggio) ? "IN_VIAGGIO" : "IN_STAZIONE";
    }

    /**
     * Avanzamento della tratta in corso adesso, in base al tempo trascorso rispetto al tempo
     * di tratta scalato dal fattore di accelerazione. Lo calcola e basta: a scriverlo è
     * {@link #avanza}, oppure l'arrivo se è arrivato a 100.
     *
     * @return L'avanzamento, da 0 a 100.
     */
    public double avanzamento(TrainDB.TappaItinerario da, TrainDB.TappaItinerario a) {
        double tempoTrattaSecondi = Percorso.tempoTrattaMinuti(trainDB, da, a) * 60.0 / fattoreAccelerazione;
        double trascorsi = Duration.between(trainDB.tInizioTratta, Instant.now()).toMillis() / 1000.0;
        return Math.min(100.0, trascorsi / tempoTrattaSecondi * 100.0);
    }

    /** @return L'itinerario arrivato mentre il treno era in tratta, oppure null. */
    public Itinerario itinerarioPendente() {
        return itinerarioPendente;
    }

    /**
     * La fermata dell'avvio: la prima stazione dell'itinerario, in andata, con la sosta
     * iniziale aperta e i primi passeggeri saliti al capolinea.
     *
     * @param tappe Tappe dell'itinerario da cui si parte (anche uno non ancora installato).
     */
    public Fermata primaFermata(List<TrainDB.TappaItinerario> tappe) {
        return new Fermata(tappe.get(0), 0, "andata", "IN_STAZIONE", tappe.get(1).id, null,
                sostaSecondi, 0, nuoviPasseggeri());
    }

    /**
     * La fermata in fondo alla tratta in corso, sul percorso in uso: la tappa successiva nel
     * verso di marcia.
     *
     * <p>Se quella tappa è il <b>capolinea marcato dall'amministratore</b> non c'è nessuna
     * inversione: la corsa finisce lì, scendono tutti e non c'è una prossima fermata. Il
     * controllo viene prima dell'inversione perché un estremo marcato non è un punto in cui
     * girarsi, è il punto in cui la corsa finisce.</p>
     *
     * <p>Altrimenti, se la tappa è un estremo dell'elenco (nel verso di marcia non c'è più
     * niente) la direzione si inverte e la sosta raddoppia.</p>
     */
    public Fermata fermataDiArrivo() {
        int indice = Percorso.indiceProssimo(trainDB);
        TrainDB.TappaItinerario arrivo = trainDB.itinerario.get(indice);

        if (arrivo.id.equals(trainDB.capolineaId)) {
            return new Fermata(arrivo, indice, trainDB.direzione, "FERMO_CAPOLINEA", null, null,
                    sostaSecondi, 100, 0); // al capolinea scendono tutti
        }

        String direzione = trainDB.direzione;
        long sosta = sostaSecondi;
        TrainDB.TappaItinerario dopo = Percorso.tappa(trainDB.itinerario, Percorso.indiceProssimo(direzione, indice));
        if (dopo == null) {
            // Estremo dell'itinerario: inversione di marcia, e la sosta è doppia
            direzione = Percorso.direzioneOpposta(direzione);
            sosta = sostaSecondi * 2;
            dopo = Percorso.tappa(trainDB.itinerario, Percorso.indiceProssimo(direzione, indice));
        }
        return new Fermata(arrivo, indice, direzione, "IN_STAZIONE", dopo != null ? dopo.id : null, null,
                sosta, 100, nuoviPasseggeri());
    }

    /**
     * La fermata del riaggancio a un itinerario nuovo, nella stazione in cui il treno si
     * trova (RF02.5.2): il convoglio è lo stesso, è cambiato solo l'elenco delle fermate che
     * ha davanti, quindi non ricomincia dal capolinea. Indice, direzione e prossima fermata
     * si ricalcolano sul percorso nuovo e la sosta riparte da adesso, così il treno non
     * schizza fuori dalla stazione un istante dopo il riaggancio solo perché era fermo lì da
     * un po'.
     *
     * @param nuovo              Itinerario nuovo (nel TrainDB non c'è ancora).
     * @param stazioneAncora     Stazione in cui il treno si trova.
     * @param statiNuovi         Stati delle fermate arrivati con l'itinerario: nel TrainDB si
     *                           scrivono solo dopo, ma per decidere se il treno resta
     *                           trattenuto contano già adesso.
     * @param arrivatoViaggiando true se il treno è appena entrato in stazione alla fine di una
     *                           tratta, false se era già fermo lì.
     * @return La fermata sul percorso nuovo, oppure null se quella stazione non ne fa più
     *         parte e quindi non c'è nessun punto a cui agganciarsi.
     */
    public Fermata fermataDiRiaggancio(Itinerario nuovo, String stazioneAncora,
                                       Map<String, String> statiNuovi, boolean arrivatoViaggiando) {
        List<TrainDB.TappaItinerario> tappe = nuovo.tappe();
        // All'arrivo l'indice nel TrainDB è ancora quello della stazione di partenza: il
        // treno però è in quella dopo, ed è da lì che va cercata l'occorrenza più vicina
        // (conta quando il percorso passa due volte dalla stessa stazione).
        int indiceVicino = arrivatoViaggiando ? Percorso.indiceProssimo(trainDB) : trainDB.indiceStazione;
        int indice = Percorso.indiceDi(tappe, stazioneAncora, indiceVicino);
        if (indice < 0) {
            return null;
        }

        // Se il punto in cui mi trovo è diventato un estremo del percorso nuovo, nel verso di
        // marcia non c'è più nessuna tappa: la direzione va invertita, se no il treno non
        // avrebbe una prossima fermata.
        String direzione = trainDB.direzione;
        if (Percorso.tappa(tappe, Percorso.indiceProssimo(direzione, indice)) == null) {
            direzione = Percorso.direzioneOpposta(direzione);
        }
        TrainDB.TappaItinerario prossima = Percorso.tappa(tappe, Percorso.indiceProssimo(direzione, indice));
        String prossimaId = prossima != null ? prossima.id : null;

        String fase = "IN_STAZIONE";
        String bloccante = null;
        if ("BLOCCATO_IN_STAZIONE".equals(trainDB.faseViaggio)) {
            // Il blocco era scattato sul percorso vecchio: resta solo se una stazione chiusa
            // (guasta o in manutenzione) è ancora fra quelle che mi riguardano.
            if (chiusa(stazioneAncora, statiNuovi)) {
                bloccante = stazioneAncora;
            } else if (chiusa(prossimaId, statiNuovi)) {
                bloccante = prossimaId;
            }
            if (bloccante != null) {
                fase = "BLOCCATO_IN_STAZIONE";
            }
        } else if (stazioneAncora.equals(nuovo.capolineaId())) {
            // Il percorso nuovo ha il capolinea proprio dove il treno si trova: la corsa è
            // (o resta) chiusa. Senza questo controllo basterebbe risalvare l'itinerario senza
            // cambiare niente (o agganciarci un altro convoglio: l'evento arriva a tutti) per
            // far ripartire un treno che aveva finito il servizio.
            fase = "FERMO_CAPOLINEA";
            prossimaId = null;
        }

        int passeggeri;
        if (!arrivatoViaggiando) {
            passeggeri = trainDB.passeggeri; // i passeggeri sono del convoglio, non dell'itinerario
        } else if ("FERMO_CAPOLINEA".equals(fase)) {
            passeggeri = 0; // al capolinea scendono tutti
        } else {
            passeggeri = nuoviPasseggeri();
        }
        return new Fermata(tappe.get(indice), indice, direzione, fase, prossimaId, bloccante,
                sostaSecondi, arrivatoViaggiando ? 100 : 0, passeggeri);
    }

    /**
     * Come {@link TrainDB#stazioneImpercorribile}, ma tenendo conto degli stati appena arrivati
     * con l'itinerario, che nel TrainDB non sono ancora scritti.
     */
    private boolean chiusa(String stazioneId, Map<String, String> statiNuovi) {
        if (stazioneId == null) {
            return false;
        }
        if (!statiNuovi.containsKey(stazioneId)) {
            return trainDB.stazioneImpercorribile(stazioneId);
        }
        return trainDB.stazioniGuaste.contains(stazioneId) || TrainDB.statoImpercorribile(statiNuovi.get(stazioneId));
    }

    /** Salita/discesa passeggeri simulata a ogni ENTRATA (e alla partenza dalla prima tappa). */
    private int nuoviPasseggeri() {
        return 50 + random.nextInt(351);
    }

    // ===============================================================
    // MOSSE: scrivono e basta, i controlli li ha già fatti il gestore
    // ===============================================================

    /**
     * Mette il convoglio fermo nella stazione calcolata dalla guardia (avvio, arrivo o
     * riaggancio). Nessun passaggio viene pubblicato qui: la ENTRATA, quando c'è, è
     * un'azione e la fa il gestore dopo.
     */
    public void fermaIn(Fermata fermata) {
        TrainDB.TappaItinerario tappa = fermata.tappa();
        trainDB.faseViaggio = fermata.fase();
        trainDB.indiceStazione = fermata.indice();
        trainDB.direzione = fermata.direzione();
        trainDB.stazioneCorrente = tappa.id;
        trainDB.prossimaStazione = fermata.prossimaStazione();
        trainDB.stazioneBloccante = fermata.stazioneBloccante();
        trainDB.latitudine = tappa.latitudine;
        trainDB.longitudine = tappa.longitudine;
        trainDB.progressPercent = fermata.progressPercent();
        trainDB.passeggeri = fermata.passeggeri();
        trainDB.velocita = 0;
        trainDB.tArrivoInStazione = Instant.now();
        sostaCorrenteSecondi = fermata.sostaSecondi();
    }

    /** IN_STAZIONE -> IN_VIAGGIO: parte il cronometro della tratta verso la prossima tappa. */
    public void parti(TrainDB.TappaItinerario prossima) {
        trainDB.faseViaggio = "IN_VIAGGIO";
        trainDB.stazioneCorrente = null; // in tratta il treno non è in nessuna stazione
        trainDB.prossimaStazione = prossima.id;
        trainDB.tInizioTratta = Instant.now();
        trainDB.progressPercent = 0;
    }

    /** Il treno avanza lungo la tratta: posizione GPS interpolata fra le due stazioni. */
    public void avanza(TrainDB.TappaItinerario da, TrainDB.TappaItinerario a, double progress) {
        trainDB.progressPercent = progress;
        trainDB.latitudine = Percorso.interpola(da.latitudine, a.latitudine, progress);
        trainDB.longitudine = Percorso.interpola(da.longitudine, a.longitudine, progress);
    }

    /**
     * Sposta avanti di un secondo l'inizio della tratta corrente: il tempo "percorso" resta
     * invariato e l'avanzamento non cresce mentre il treno è fermo in tratta (bloccato o in
     * emergenza). Il tempo perso qui non viene contato: che si traduca in un ritardo lo
     * stabilisce la Centrale, quando il treno si presenta alla fermata successiva più tardi
     * dell'orario previsto.
     */
    public void congela() {
        if (trainDB.tInizioTratta != null) {
            trainDB.tInizioTratta = trainDB.tInizioTratta.plusSeconds(1);
        }
    }

    /**
     * IN_STAZIONE/IN_VIAGGIO -> BLOCCATO_IN_STAZIONE/BLOCCATO_IN_TRATTA. Il blocco è una
     * fase vera e non un semplice "non partire", così GET /treno/viaggio dice anche QUALE
     * stazione trattiene il treno. Vale anche per la manutenzione: per il convoglio è lo
     * stesso identico blocco, cambia solo il motivo per cui quei binari non sono suoi.
     *
     * @param stazioneId   Stazione chiusa che causa il blocco.
     * @param faseDiBlocco Quella calcolata dalla guardia con {@link #faseDiBlocco()}.
     */
    public void blocca(String stazioneId, String faseDiBlocco) {
        trainDB.faseViaggio = faseDiBlocco;
        trainDB.stazioneBloccante = stazioneId;
        trainDB.velocita = 0;
    }

    /**
     * BLOCCATO_IN_x -> la fase di prima: la stazione che tratteneva il treno è di nuovo
     * agibile.
     *
     * @param faseDiSblocco Quella calcolata dalla guardia con {@link #faseDiSblocco()}.
     */
    public void sblocca(String faseDiSblocco) {
        trainDB.faseViaggio = faseDiSblocco;
        trainDB.stazioneBloccante = null;
    }

    /**
     * Scrive nel TrainDB l'itinerario ricevuto dalla Centrale: id, tappe e capolinea si
     * scrivono insieme e mai uno da solo, se no il treno finirebbe per fermarsi in un punto
     * che sul percorso in uso non chiude niente. Un eventuale itinerario ancora in attesa
     * decade: quello che vale è l'ultimo arrivato.
     */
    public void installa(Itinerario itinerario) {
        trainDB.itinerarioId = itinerario.id();
        trainDB.itinerario = itinerario.tappe();
        trainDB.stazioniTratta = itinerario.ids();
        trainDB.capolineaId = itinerario.capolineaId();
        itinerarioPendente = null;
    }

    /** Il treno è in tratta: il percorso nuovo aspetta l'arrivo in stazione. */
    public void mettiInAttesa(Itinerario itinerario) {
        itinerarioPendente = itinerario;
    }

    /**
     * -> SENZA_ITINERARIO: la Centrale non assegna più nessun percorso al convoglio (è il
     * caso di quello sganciato dall'amministratore), e qui e solo qui il percorso in memoria
     * va buttato via. Il treno resta dov'è, fermo, in attesa di un percorso o della
     * soppressione, come chiede RF02.5.1 ("smette di viaggiare e resta fermo in attesa di
     * essere soppresso").
     *
     * <p>Deve dichiararsi fermo e non limitarsi a non muoversi: la telemetria parte lo
     * stesso, e un convoglio rimasto IN_VIAGGIO racconterebbe alla Centrale di essere in
     * marcia a una velocità inventata mentre la posizione non si muove di un metro.</p>
     */
    public void restaSenzaItinerario() {
        trainDB.itinerario = new ArrayList<>();
        trainDB.itinerarioId = null;
        trainDB.stazioniTratta = new ArrayList<>();
        trainDB.capolineaId = null; // il capolinea se ne va con l'itinerario a cui apparteneva
        itinerarioPendente = null;
        trainDB.faseViaggio = "SENZA_ITINERARIO";
        trainDB.velocita = 0;
        trainDB.prossimaStazione = null;
        trainDB.stazioneBloccante = null;
    }
}
