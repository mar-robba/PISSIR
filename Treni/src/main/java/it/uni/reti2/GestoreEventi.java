package it.uni.reti2;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Il gestore degli eventi del treno: l'unico punto da cui passano le mosse degli automi, e
 * quindi l'unico che cambia lo stato del convoglio.
 *
 * <p>Gli eventi arrivano da tre generatori, che traducono in un EventoTreno quello che è
 * successo e non scrivono niente nel TrainDB: GeneratoreEventiTempo (il tick da 1 secondo e
 * le risposte della Centrale sull'itinerario), TrainGateway (gli alert di railway/alerts) e
 * TrainIngestion (i sensori di bordo, via REST).</p>
 *
 * <p>Ogni evento si gestisce in tre fasi, sempre in quest'ordine:</p>
 * <ol>
 *   <li><b>guardie</b>: si legge lo stato com'è adesso e si decide tutto, comprese le mosse
 *       degli altri automi e il punto in cui si ritroverà il convoglio, in variabili locali
 *       e senza scrivere niente. Se una guardia dice di no si esce qui, quando nessun automa
 *       si è ancora mosso e non c'è niente da disfare;</li>
 *   <li><b>assegnamenti</b>: si scrivono le mosse decise, senza più nessun controllo (gli if
 *       che ci sono riusano risposte già date nella fase 1, non le richiedono);</li>
 *   <li><b>azioni</b>: si pubblica e si scrive nel log, quando lo stato è già tutto scritto e
 *       coerente (la ENTRATA, per esempio, porta la direzione già invertita).</li>
 * </ol>
 *
 * <p>Gli automi (AutomaViaggio, AutomaCondizione, AutomaStazioniNote) non si chiamano mai fra
 * loro: quando un evento muove più cose le elenca questo gestore, nel caso di quell'evento.
 * Se una mossa ne chiamasse un'altra, la guardia di quella starebbe dentro la prima insieme
 * alle sue scritture, e non si potrebbero più valutare tutte le guardie prima di tutte le
 * scritture.</p>
 *
 * <p>{@link #gestisci} è synchronized: il tick, gli alert MQTT e le richieste REST arrivano
 * su thread diversi, e le risposte della fase 1 valgono solo se nessun altro scrive fra la
 * fase 1 e la fase 2. Così ogni evento va da cima a fondo prima che parta il successivo,
 * come in TrainDatabaseValidator.verificaSeNecessario(). Dentro non c'è niente di lento: la
 * GET dell'itinerario la fa GeneratoreEventiTempo prima di chiamare, e le pubblicazioni MQTT
 * non aspettano la conferma del broker (finiscono in CodaPubblicazioni e le consegna il suo
 * thread).</p>
 */
public class GestoreEventi {

    private static final Logger LOG = Logger.getLogger(GestoreEventi.class.getName());

    /** Stato del treno: qui si legge soltanto, a scriverlo sono gli automi. */
    TrainDB trainDB;

    /** Dove si trova il convoglio: fase, posizione, direzione, itinerario (T5 e T7). */
    AutomaViaggio viaggio;

    /** Come sta il convoglio: REGOLARE, EMERGENZA, SOPPRESSO (T6). */
    AutomaCondizione condizione;

    /** Quello che il treno sa delle stazioni (T11). */
    AutomaStazioniNote stazioni;

    /** Azione: pubblica i passaggi ENTRATA/USCITA verso i sensori di binario. */
    PubblicatorePassaggi pubblicatorePassaggi;

    /** Azione: pubblica il guasto di bordo verso la Centrale. */
    PubblicatoreAlert pubblicatoreAlert;

    /**
     * Il gestore degli eventi: ogni tipo di evento ha il suo metodo, e ognuno è scritto nelle
     * tre fasi descritte sopra.
     *
     * @param evento Quello che è successo, già tradotto da uno dei generatori.
     */
    public synchronized void gestisci(EventoTreno evento) {
        switch (evento.tipo()) {
            case TICK -> suTick();
            case ITINERARIO_CARICATO -> suItinerarioCaricato(evento.itinerario()); //perchè è un linguaggio fortemente a oggetti e tale valore è ritornato da una metodo get compilato senza che lo si definisca direttamente
            case ITINERARIO_REVOCATO -> suItinerarioRevocato();
            case GUASTO_STAZIONE -> suGuastoStazione(evento.stazioneId());
            case GUASTO_STAZIONE_RISOLTO -> suGuastoStazioneRisolto(evento.stazioneId());
            case STATO_STAZIONE -> suStatoStazione(evento.stazioneId(), evento.dettaglio());
            case GUASTO_BORDO -> suGuastoBordo(evento.dettaglio());
            case GUASTO_BORDO_RISOLTO -> suGuastoBordoRisolto();
            case SOPPRESSIONE -> suSoppressione();
        }
    }

    // ===============================================================
    // TICK: il tempo che passa
    // ===============================================================

    /**
     * È passato un secondo. Il tick fa fare all'automa del viaggio al massimo una mossa, e
     * quale lo decidono la fase in cui si trova e la condizione del convoglio: da soppresso
     * non si muove più niente, in emergenza si resta dove si è (e a metà tratta
     * l'avanzamento si congela, così alla ripresa il treno riparte esattamente da lì).
     */
    private void suTick() {
        if (condizione.soppresso()) {
            return; // la corsa è cancellata: il convoglio non si muove più
        }
        switch (trainDB.faseViaggio) {
            case "SENZA_ITINERARIO" -> tickSenzaItinerario();
            case "IN_STAZIONE" -> {
                if (condizione.regolare()) {
                    tickInStazione();
                }
            }
            case "IN_VIAGGIO" -> {
                if (condizione.regolare()) {
                    tickInViaggio();
                } else {
                    viaggio.congela(); // in emergenza il convoglio resta dov'è
                }
            }
            case "BLOCCATO_IN_TRATTA" -> viaggio.congela(); // fermo sul binario: la tratta non avanza
            default -> { /* BLOCCATO_IN_STAZIONE e FERMO_CAPOLINEA aspettano un evento da fuori */ }
        }
    }

    /** SENZA_ITINERARIO -> IN_STAZIONE: un itinerario c'è, il convoglio si mette alla prima tappa. */
    private void tickSenzaItinerario() {
        // 1. guardie
        if (!viaggio.puoAvviarsi()) {
            return;
        }
        AutomaViaggio.Fermata prima = viaggio.primaFermata(trainDB.itinerario);

        // 2. assegnamenti
        viaggio.fermaIn(prima);

        // 3. azioni. Alla prima partenza NON viene pubblicata alcuna ENTRATA, solo la USCITA
        //    quando la sosta scade (caso "esce senza entrare" della stazione di partenza).
        LOG.info(String.format("🚉 [TWIN] Treno %s pronto alla partenza da %s (%s), prossima fermata %s",
                trainDB.trenoId, prima.tappa().nome, prima.tappa().id, prima.prossimaStazione()));
    }

    /**
     * IN_STAZIONE -> IN_VIAGGIO quando la sosta è scaduta e i binari sono liberi, con la
     * USCITA pubblicata. Se invece la stazione corrente o la prossima sono chiuse (guaste o
     * con la squadra al lavoro) IN_STAZIONE -> BLOCCATO_IN_STAZIONE: non basta un return
     * "muto", il convoglio entra in una fase che dice anche QUALE stazione lo trattiene.
     */
    private void tickInStazione() {
        // 1. guardie
        if (!viaggio.sostaScaduta()) {
            return; // sosta ancora in corso
        }
        TrainDB.TappaItinerario corrente = Percorso.tappaCorrente(trainDB);
        TrainDB.TappaItinerario prossima = Percorso.tappaProssima(trainDB);
        if (prossima == null) {
            return; // non succede: all'estremo la direzione si è già invertita all'arrivo
        }
        String stazioneChiusa = viaggio.stazioneChiusaInPartenza(corrente, prossima);

        // 2. assegnamenti
        if (stazioneChiusa != null) {
            viaggio.blocca(stazioneChiusa, "BLOCCATO_IN_STAZIONE");
        } else {
            viaggio.parti(prossima);
        }

        // 3. azioni
        if (stazioneChiusa != null) {
            // Il motivo va preso da dove l'ha preso stazioneImpercorribile: se la stazione è fra
            // quelle guaste, statoStazioni può avere ancora l'ultimo STATO_STAZIONE (ONLINE) e il
            // log direbbe il contrario di quello che succede.
            String motivo = trainDB.stazioniGuaste.contains(stazioneChiusa) ? "GUASTA"
                    : trainDB.statoStazioni.getOrDefault(stazioneChiusa, "GUASTA");
            LOG.warning(String.format("⛔ [TWIN] Resto in %s: la stazione %s non è attraversabile (%s)", corrente.id,
                    stazioneChiusa, motivo));
        } else {
            pubblicatorePassaggi.notificaPassaggioStazione(corrente.id, "USCITA");
            LOG.info(String.format("🚆 [TWIN] Partenza da %s verso %s (%d min simulati)",
                    corrente.id, prossima.id, Percorso.tempoTrattaMinuti(trainDB, corrente, prossima)));
        }
    }

    /**
     * IN_VIAGGIO: il treno avanza lungo la tratta, con la posizione interpolata fra le due
     * stazioni, finché l'avanzamento arriva a 100. A quel punto entra nella stazione di arrivo.
     */
    private void tickInViaggio() {
        // 1. guardie
        TrainDB.TappaItinerario da = Percorso.tappaCorrente(trainDB);
        TrainDB.TappaItinerario a = Percorso.tappaProssima(trainDB);
        if (a == null || trainDB.tInizioTratta == null) {
            return;
        }
        double progress = viaggio.avanzamento(da, a);
        if (progress >= 100.0) {
            arrivoInStazione(a);
            return;
        }

        // 2. assegnamenti (nessuna azione: fra due stazioni non c'è niente da annunciare)
        viaggio.avanza(da, a, progress);
    }

    /**
     * IN_VIAGGIO -> IN_STAZIONE, o FERMO_CAPOLINEA: il treno entra nella stazione di arrivo,
     * pubblica la ENTRATA e rinnova i passeggeri; all'estremo dell'itinerario inverte la
     * marcia con sosta doppia, al capolinea marcato chiude la corsa.
     *
     * <p>Se l'itinerario è cambiato mentre il treno percorreva questa tratta, adesso che è
     * fermo in stazione lo si installa e ci si riaggancia qui, senza tornare al capolinea
     * (RF02.5.2). È la mossa laterale di questo evento, e la sua guardia sta qui insieme alle
     * altre: la fermata si calcola sul percorso NUOVO, perché un treno arrivato al vecchio
     * capolinea, se il percorso nuovo prosegue oltre, deve andare avanti e non tornare
     * indietro.</p>
     */
    private void arrivoInStazione(TrainDB.TappaItinerario arrivo) {
        // 1. guardie: tutto si decide adesso, sullo stato di prima
        Itinerario daInstallare = viaggio.itinerarioPendente();
        AutomaViaggio.Fermata fermata;
        boolean daCapo = false;
        if (daInstallare != null) {
            // gli stati delle fermate di quell'itinerario sono già stati annotati quando è arrivato
            fermata = viaggio.fermataDiRiaggancio(daInstallare, arrivo.id, Map.of(), true);
            if (fermata == null) {
                // la stazione non è nel percorso nuovo: nessun punto di aggancio, si riparte
                // dalla prima tappa di quello
                fermata = viaggio.primaFermata(daInstallare.tappe());
                daCapo = true;
            }
        } else {
            fermata = viaggio.fermataDiArrivo();
        }
        boolean inversione = daInstallare == null && !fermata.direzione().equals(trainDB.direzione);

        // 2. assegnamenti
        if (daInstallare != null) {
            viaggio.installa(daInstallare);
        }
        viaggio.fermaIn(fermata);

        // 3. azioni. La ENTRATA parte a stato già scritto, quindi con la direzione già
        //    invertita se qui il treno si gira: è l'unico modo che gli altri nodi hanno di
        //    saperlo prima del prossimo frame di telemetria. Si pubblica anche al capolinea,
        //    perché in quella stazione il convoglio ci è entrato davvero: è il caso "un treno
        //    può entrarvi e non uscirvi", e sarà l'unico passaggio che lì registrerà per lui.
        pubblicatorePassaggi.notificaPassaggioStazione(arrivo.id, "ENTRATA");
        if (daCapo) {
            LOG.warning(String.format("⚠️ [TWIN] %s non fa più parte del mio percorso: riparto dalla prima tappa (%s)",
                    arrivo.id, fermata.tappa().id));
        } else if (daInstallare != null) {
            LOG.info(String.format("🚉 [TWIN] Arrivo in %s con l'itinerario nuovo %s (passeggeri %d)",
                    arrivo.id, daInstallare.id(), fermata.passeggeri()));
        } else if ("FERMO_CAPOLINEA".equals(fermata.fase())) {
            LOG.info(String.format("🏁 [TWIN] Capolinea %s: corsa terminata, resto fermo qui finché non "
                    + "mi assegnano un itinerario nuovo", arrivo.id));
        } else if (inversione) {
            LOG.info(String.format("🔁 [TWIN] Estremo %s raggiunto: inversione di marcia (%s), sosta doppia",
                    arrivo.id, fermata.direzione()));
        } else {
            LOG.info(String.format("🚉 [TWIN] Arrivo in %s (passeggeri %d)", arrivo.id, fermata.passeggeri()));
        }
    }

    // ===============================================================
    // ITINERARIO: le risposte della Centrale alla GET
    // ===============================================================

    /**
     * Decide che cosa fare dell'itinerario appena scaricato dalla Centrale (RF02.5.2). Il
     * convoglio già assegnato e in marcia non deve ricominciare dal capolinea, ma restare
     * dove si trova e proseguire sulle fermate nuove. I casi, nell'ordine in cui si
     * controllano:
     * <ul>
     *   <li>corsa cancellata o viaggio non ancora partito: si scrive e basta, l'avvio lo fa
     *       il tick;</li>
     *   <li>corsa chiusa al capolinea: ci si riaggancia sempre, anche con le stesse fermate;</li>
     *   <li>stesse identiche fermate: si scrive e basta;</li>
     *   <li>treno in mezzo a una tratta: il percorso nuovo resta in attesa e si installa
     *       all'arrivo, perché un treno fra due stazioni non si teletrasporta;</li>
     *   <li>treno fermo in stazione: si scrive e ci si riaggancia lì.</li>
     * </ul>
     * Se nel percorso nuovo la stazione del riaggancio non c'è, si riparte dalla prima tappa.
     */
    private void suItinerarioCaricato(Itinerario nuovo) {
        // 1. guardie
        String fase = trainDB.faseViaggio;
        boolean riaggancio = false;
        String stazioneAncora = null;
        boolean stesseFermate = false;
        boolean inAttesa = false;
        if (condizione.soppresso() || "SENZA_ITINERARIO".equals(fase)) {
            // niente da riagganciare: il convoglio non sta viaggiando
        } else if ("FERMO_CAPOLINEA".equals(fase)) {
            // Il convoglio che aveva chiuso la corsa va SEMPRE riagganciato, anche quando le
            // fermate sono identiche: se il capolinea è stato spostato o tolto, quella è
            // esattamente la modifica che deve rimetterlo in servizio, ed è l'unico modo che ha
            // per ripartire. Senza questo ramo cadrebbe nel caso "stesse fermate" qui sotto,
            // che scrive l'itinerario e basta, e resterebbe fermo lì per sempre.
            riaggancio = true;
            stazioneAncora = trainDB.stazioneCorrente;
        } else if (nuovo.ids().equals(trainDB.stazioniTratta)) {
            // Stesse identiche fermate: l'evento ITINERARIO_AGGIORNATO arriva a TUTTI i treni
            // della tratta anche quando l'amministratore ne ha soltanto agganciato un altro, e
            // non c'è motivo di rimettere in sosta un convoglio che sta viaggiando bene. I tempi
            // di percorrenza però possono essere cambiati, quindi la lista si scrive lo stesso.
            stesseFermate = true;
        } else if (viaggio.inTratta()) {
            // In mezzo a una tratta il cambio si rimanda all'arrivo: fino a quel momento il
            // treno continua sul vecchio itinerario, che è l'unico coerente con il binario che
            // sta percorrendo.
            inAttesa = true;
        } else {
            riaggancio = true;
            stazioneAncora = Percorso.tappaCorrente(trainDB).id;
        }
        AutomaViaggio.Fermata fermata = null;
        boolean daCapo = false;
        if (riaggancio) {
            // gli stati delle fermate arrivati insieme al percorso nel TrainDB non ci sono
            // ancora, ma per decidere se il treno resta trattenuto contano già
            fermata = viaggio.fermataDiRiaggancio(nuovo, stazioneAncora, nuovo.statoFermate(), false);
            if (fermata == null) {
                fermata = viaggio.primaFermata(nuovo.tappe());
                daCapo = true;
            }
        }

        // 2. assegnamenti
        stazioni.annotaStati(nuovo.statoFermate()); // notizie sulla rete: valgono in ogni caso
        if (inAttesa) {
            viaggio.mettiInAttesa(nuovo);
        } else {
            viaggio.installa(nuovo);
        }
        if (fermata != null) {
            viaggio.fermaIn(fermata);
        }

        // 3. azioni (nessun passaggio: il convoglio non si è mosso di un metro, e una ENTRATA
        //    qui inventerebbe un transito che la Centrale storicizzerebbe come vero)
        if (inAttesa) {
            LOG.info(String.format("🗺️ [TWIN] Itinerario %s in attesa: lo applico appena arrivo a %s",
                    nuovo.id(), trainDB.prossimaStazione));
        } else if (stesseFermate) {
            LOG.info("🗺️ [TWIN] Le mie fermate non sono cambiate: proseguo il viaggio");
        } else if (daCapo) {
            LOG.warning(String.format("⚠️ [TWIN] %s non fa più parte del mio percorso: riparto dalla prima tappa (%s)",
                    stazioneAncora, fermata.tappa().id));
        } else if (fermata != null) {
            LOG.info(String.format("📍 [TWIN] Riagganciato all'itinerario %s in %s (indice %d), prossima fermata %s",
                    nuovo.id(), fermata.tappa().id, fermata.indice(), fermata.prossimaStazione()));
        }
    }

    /**
     * La Centrale ha risposto 404: non assegna (più) nessun itinerario a questo convoglio, ed
     * è il caso di quello sganciato dall'amministratore (RF02.5.1). La mossa si fa qualunque
     * sia la condizione: un treno in emergenza o soppresso resta tale, perché la condizione è
     * un altro automa, ma un percorso non ce l'ha più.
     */
    private void suItinerarioRevocato() {
        // 1. guardie: nessuna, si guarda solo che cosa c'era da buttare per il log
        boolean avevaUnPercorso = !trainDB.itinerario.isEmpty();
        boolean eraInViaggio = !"SENZA_ITINERARIO".equals(trainDB.faseViaggio);

        // 2. assegnamenti
        viaggio.restaSenzaItinerario();

        // 3. azioni
        if (avevaUnPercorso) {
            LOG.warning("🚫 [TWIN] La Centrale non mi assegna più nessun itinerario: lo scarto");
        }
        if (eraInViaggio) {
            LOG.warning("🛑 [TWIN] Nessun itinerario assegnato: resto fermo in attesa di un percorso");
        }
    }

    // ===============================================================
    // STAZIONI: gli alert su railway/alerts
    // ===============================================================

    /**
     * Guasto CRITICAL di una stazione: da adesso non si attraversa, e se è quella in cui il
     * treno si trova o la prossima il convoglio si ferma subito. Se non lo riguarda adesso il
     * guasto resta annotato, e lo ricontrolla la partenza.
     */
    private void suGuastoStazione(String stazioneId) {
        // 1. guardie
        boolean miFermo = !condizione.soppresso() && viaggio.puoBloccarsiPer(stazioneId);
        String faseDiBlocco = viaggio.faseDiBlocco();

        // 2. assegnamenti
        stazioni.annotaGuasto(stazioneId);
        if (miFermo) {
            viaggio.blocca(stazioneId, faseDiBlocco);
        }

        // 3. azioni
        if (miFermo) {
            LOG.warning(String.format("🚨 [ALERT] Stazione %s guasta sulla mia rotta: mi fermo", stazioneId));
        }
    }

    /**
     * Quel singolo allarme non è più aperto: il treno se ne prende nota e nient'altro. Un
     * guasto chiuso NON vuol dire binari liberi (la stazione può avere la squadra ancora al
     * lavoro, o un secondo allarme aperto) e a saperlo è solo la Centrale, che quando la
     * fermata torna agibile lo dice con STATO_STAZIONE. Prima si ripartiva da qui, e siccome
     * questo messaggio lo pubblica anche il nodo di terra quando i suoi sensori tornano a
     * battere, bastava quello per far entrare un convoglio in una stazione in manutenzione.
     */
    private void suGuastoStazioneRisolto(String stazioneId) {
        // 1. guardie: nessuna mossa del viaggio, si guarda solo se era lei a trattenermi
        boolean eraLaBloccante = stazioneId.equals(trainDB.stazioneBloccante);

        // 2. assegnamenti
        stazioni.dimenticaGuasto(stazioneId);

        // 3. azioni
        if (eraLaBloccante) {
            LOG.info(String.format("🛠️ [ALERT] Guasto risolto alla stazione %s: resto fermo finché la Centrale "
                    + "non la dichiara agibile", stazioneId));
        }
    }

    /**
     * La Centrale dichiara in che stato ha messo una stazione. Per chi viaggia GUASTA e
     * MANUTENZIONE sono la stessa identica cosa: quei binari non si attraversano, quindi il
     * treno che sta per arrivarci si ferma e quello che è già dentro resta fermo. È l'unico
     * modo che il convoglio ha di sapere che i tecnici sono sul posto: la manutenzione la
     * decide la Centrale e non nasce da nessun guasto segnalato dal campo.
     *
     * <p>ONLINE è invece l'unico messaggio che rimette in marcia un convoglio trattenuto, e
     * per questo cancella anche il guasto che il treno si era segnato: la Centrale sta
     * dicendo che quei binari sono liberi, e lei è l'unica a saperlo davvero. Senza questo la
     * memoria vecchia avrebbe continuato a fermare il treno, perché l'annuncio della stazione
     * agibile e la chiusura dell'allarme sono due messaggi distinti e arrivano nell'ordine che
     * capita.</p>
     *
     * <p>Gli altri stati (OFFLINE su tutti) si annotano e basta: una stazione muta non è una
     * stazione riaperta, e chi è fermo davanti a dei binari chiusi ci resta finché non è la
     * Centrale a dire che sono di nuovo suoi.</p>
     */
    private void suStatoStazione(String stazioneId, String stato) {
        // 1. guardie
        boolean chiusa = TrainDB.statoImpercorribile(stato);
        boolean agibile = TrainDB.statoAgibile(stato);
        boolean miFermo = chiusa && !condizione.soppresso() && viaggio.puoBloccarsiPer(stazioneId);
        boolean riparto = agibile && !condizione.soppresso() && viaggio.bloccatoDa(stazioneId);
        String faseDiBlocco = viaggio.faseDiBlocco();
        String faseDiSblocco = viaggio.faseDiSblocco();

        // 2. assegnamenti
        stazioni.annotaStato(stazioneId, stato);
        if (agibile) {
            stazioni.dimenticaGuasto(stazioneId);
        }
        if (miFermo) {
            viaggio.blocca(stazioneId, faseDiBlocco);
        }
        if (riparto) {
            viaggio.sblocca(faseDiSblocco);
        }

        // 3. azioni
        if (miFermo) {
            LOG.warning(String.format("🛠️ [ALERT] Stazione %s dichiarata %s sulla mia rotta: mi fermo", stazioneId, stato));
        }
        if (riparto) {
            LOG.info(String.format("✅ [ALERT] Stazione %s di nuovo agibile (%s): riprendo la fase %s",
                    stazioneId, stato, faseDiSblocco));
        }
    }

    // ===============================================================
    // CONDIZIONE DEL CONVOGLIO: sensori di bordo e comandi della Centrale
    // ===============================================================

    /**
     * Un sensore di bordo ha rilevato un'avaria: il convoglio va in EMERGENZA e si ferma
     * dov'è, anche a metà tratta, senza cambiare fase (quella è dell'altro automa). La
     * Centrale riceve l'alert in ogni caso: un guasto nuovo va segnalato anche da un treno
     * già fermo per un'altra avaria.
     */
    private void suGuastoBordo(String descrizione) {
        // 1. guardie
        boolean vaInEmergenza = condizione.puoAndareInEmergenza();

        // 2. assegnamenti
        if (vaInEmergenza) {
            condizione.vaiInEmergenza();
        }

        // 3. azioni: per la Centrale un'avaria che ferma il convoglio è sempre CRITICAL
        pubblicatoreAlert.inviaGuasto(descrizione, "CRITICAL");
        LOG.warning(String.format("🚨 [TWIN] Avaria di bordo (%s): il convoglio è %s", descrizione, trainDB.stato()));
    }

    /** La Centrale ha chiuso l'allarme di bordo: il treno riprende dalla fase in cui era. */
    private void suGuastoBordoRisolto() {
        // 1. guardie
        if (!condizione.puoRientrare()) {
            return;
        }

        // 2. assegnamenti
        condizione.rientra();

        // 3. azioni
        LOG.info("✅ [ALERT] Guasto di bordo risolto: pronto a ripartire");
    }

    /** STOP della Centrale: la corsa è cancellata, e non riparte più (RF02.5.1). */
    private void suSoppressione() {
        // 1. guardie
        if (!condizione.puoEssereSoppresso()) {
            return;
        }

        // 2. assegnamenti
        condizione.sopprimi();

        // 3. azioni
        LOG.warning("🛑 [ALERT] Corsa soppressa dalla Centrale");
    }
}
