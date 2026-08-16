package it.uni.reti2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
// Schematizza sta roba se no è troppo faticoso da memorizzare  conta è forse l'unico nodo del sistema(un nodo è quella parte che si discosta da sistemi crud basati)
// disegnati proprio un diagramma a stati finiti cos'ì che possa essere meglio visualizzare le transizioni e dunque schematizzare che così si "eccede per spazio"

/**
 * TrainJourneyEngine è il "digital twin" del treno: un motore a eventi discreti
 * che simula l'intero viaggio del convoglio lungo l'itinerario assegnato dalla
 * Centrale Operativa.
 *
 * Funzionamento generale:
 * - al boot scarica l'itinerario dalla Centrale (GET /api/treni/{id}/itinerario)
 *   usando il client HTTP del JDK; se la Centrale non risponde riprova ogni 15 secondi;
 * - un tick reattivo ogni secondo (Multi.ticks, stesso approccio della telemetria)
 *   fa avanzare la macchina a stati del viaggio:
 *   IN_STAZIONE -> (passaggio USCITA) -> IN_VIAGGIO -> (passaggio ENTRATA) -> IN_STAZIONE;
 * - i tempi di percorrenza reali sono scalati dal fattore di accelerazione
 *   (es. fattore 10: 1 minuto simulato = 6 secondi reali);
 * - la posizione GPS è interpolata linearmente tra le coordinate delle due stazioni;
 * - al capolinea il treno inverte la direzione (con sosta doppia);
 * - se una stazione della tratta (corrente o prossima) risulta guasta il treno
 *   si blocca e accumula ritardo finché non arriva l'alert RESOLVED.
 *
 * NOTA: l'emissione MQTT dei passaggi NON avviene qui direttamente (un Multi di tick
 * non può emettere su più canali) ma passa dagli Emitter del TrainGateway.
 */
@ApplicationScoped
public class TrainJourneyEngine {

    private static final Logger LOG = Logger.getLogger(TrainJourneyEngine.class);

    /** Intervallo tra due tentativi di caricamento itinerario (secondi). */
    private static final long RETRY_CARICAMENTO_SECONDI = 15;

    /**
     * Database locale del treno: contiene tutto lo stato del twin (fase, indici,
     * itinerario, ritardo, passeggeri...).
     */
    @Inject
    TrainDB trainDB;

    /**
     * Gateway MQTT del treno: usato per pubblicare i passaggi ENTRATA/USCITA
     * sul topic railway/train/{id}/passaggio tramite i suoi Emitter.
     */
    @Inject
    TrainGateway trainGateway;

    /**
     * Mapper JSON (fornito da Quarkus) per interpretare la risposta della Centrale.
     */
    @Inject
    ObjectMapper objectMapper;

    /** URL base della Centrale Operativa (es. http://localhost:8781). */
    @ConfigProperty(name = "centrale.url", defaultValue = "http://localhost:8781")
    String centraleUrl;

    /** Durata della sosta in stazione, in secondi reali. */
    @ConfigProperty(name = "viaggio.sosta.secondi", defaultValue = "20")
    long sostaSecondi; //per una più corretta simulazione ogni stazione dovrebbe avere la propria

    /**
     * Fattore di accelerazione della simulazione: 1 minuto simulato dura
     * 60/fattore secondi reali (con fattore 10 una tratta da 15 minuti dura 90s).
     */
    @ConfigProperty(name = "viaggio.fattore.accelerazione", defaultValue = "10")
    double fattoreAccelerazione;

    /** Se true il viaggio parte da solo appena l'itinerario è disponibile. */
    @ConfigProperty(name = "viaggio.autostart", defaultValue = "true")
    boolean autostart;

    /**
     * Client HTTP per parlare con la Centrale. Sotto profilo tls l'URL è HTTPS e il
     * certificato viene validato contro la CA privata del progetto; in profilo di
     * default la stessa istanza fa normali richieste in chiaro sulla 8781.
     */
    @Inject
    SecureHttpClient secureHttpClient;

    private final Random random = new Random();

    /** Istante dell'ultimo tentativo (fallito o no) di caricamento itinerario. */
    private Instant ultimoTentativoCaricamento = Instant.EPOCH;

    /** True quando la posizione iniziale è stata impostata e il viaggio è partito. */
    private boolean viaggioAvviato = false;

    /** Sosta da rispettare nella stazione corrente (raddoppiata al capolinea). */
    private long sostaCorrenteSecondi;

    /** Fase in cui si trovava il treno quando è scattato il blocco per guasto stazione. */
    private String fasePrimaDelBlocco = null;

    /** Contatore dei secondi reali di blocco, per accumulare il ritardo simulato. */
    private double secondiDiBlocco = 0;

    /** Flag alzato dal gateway quando arriva ITINERARIO_AGGIORNATO: al tick successivo si ricarica. */
    private volatile boolean ricaricaItinerarioRichiesta = false;

    /** Evita di sottoscrivere due volte il flusso di tick. */
    private boolean tickAvviato = false;

    /**
     * All'avvio dell'applicazione sottoscrive il flusso di tick (uno al secondo)
     * che fa girare la macchina a stati. Stesso pattern reattivo della telemetria,
     * ma senza @Outgoing: qui il tick pilota solo la logica interna e delega
     * al TrainGateway le pubblicazioni MQTT.
     *
     * @param ev Evento di startup di Quarkus.
     */
    void avvia(@Observes StartupEvent ev) {
        if (tickAvviato) {
            return;
        }
        tickAvviato = true;
        LOG.infof("🧭 [TWIN] Motore di viaggio avviato (sosta=%ds, fattore=%.0fx, autostart=%s)",
                sostaSecondi, fattoreAccelerazione, autostart);
        Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(1))
                .onOverflow().drop() // se un tick è lento non accumuliamo arretrati
                .subscribe().with(t -> {
                    try {
                        tick();
                    } catch (Exception e) {
                        LOG.error("Errore nel tick del motore di viaggio: " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Un passo della simulazione (chiamato ogni secondo reale).
     * Ordine dei controlli: ricarica itinerario richiesta, itinerario mancante,
     * viaggio non ancora partito, stati speciali (SOPPRESSO/EMERGENZA), poi la
     * fase corrente della macchina a stati.
     */
    private void tick() {
        // Richiesta di ricarica arrivata dalla Centrale (ITINERARIO_AGGIORNATO) lo varia la centrale attraverso il gateway
        // se si sta richiedendo un nuovo itinerario cancella il precedente
        if (ricaricaItinerarioRichiesta) {
            ricaricaItinerarioRichiesta = false;
            LOG.info("🔄 [TWIN] Itinerario aggiornato dalla Centrale: ricarico e ricomincio il viaggio");
            trainDB.itinerario = new ArrayList<>(); // ?
            trainDB.itinerarioId = null; // ?
            viaggioAvviato = false;// ?
            ultimoTentativoCaricamento = Instant.EPOCH; // riprova subito// un modo più elegante per dire 0 // Istante dell'ultimo tentativo (fallito o no) di caricamento itinerario. */
            fermaPerMancanzaItinerario();
        }

        // Non facciamo nulla finché il treno non è stato validato dal server centrale
        // o se l'ID del treno non è valido (null o vuoto).
        if (!trainDB.trenoRiconosciuto || trainDB.trenoId == null || trainDB.trenoId.trim().isEmpty() || "null".equalsIgnoreCase(trainDB.trenoId.trim())) {
            return;
        }

        // Senza itinerario non si viaggia: tenta il caricamento (con retry ogni 15s).
        // Prima di uscire bisogna però DICHIARARSI fermi: la telemetria continua a partire
        // ogni 5 secondi e, restando IN_VIAGGIO, il convoglio raccontava alla Centrale di
        // essere in marcia a una velocità inventata fra 80 e 160 km/h mentre la posizione
        // non si muoveva di un metro. Sulla mappa restava un treno azzurro "IN VIAGGIO"
        // fermo sul posto, e nemmeno il watchdog poteva accorgersene, perché cercava treni
        // a velocità zero. È il caso peggiore: uno stato plausibile ma falso.
        if (trainDB.itinerario.isEmpty()) {
            fermaPerMancanzaItinerario();
            tentaCaricamentoItinerario();
            return;
        }

        // --- inizializzazione delle variabili per la partenza
        // Itinerario pronto ma viaggio non ancora partito (se l'itinerario non fosse pronto si sabbe usciti prima)
        if (!viaggioAvviato) {
            if (autostart) {// nel programma non c'è maniera ancora per cambiare tale variabile
                avviaViaggio();
            }
            return;
        }

        // Il treno soppresso non si muove più (stato definitivo)
        if ("SOPPRESSO".equals(trainDB.stato)) {
            trainDB.velocita = 0;
            trainDB.faseViaggio = "TERMINATO";
            return;
        }

        // In emergenza il viaggio è congelato: il tempo di tratta non avanza
        if ("EMERGENZA".equals(trainDB.stato)) {
            congelaAvanzamentoTratta();
            return;
        }

        switch (trainDB.faseViaggio) {
            case "IN_STAZIONE" -> tickInStazione();
            case "IN_VIAGGIO" -> tickInViaggio();
            case "BLOCCATO_GUASTO_STAZIONE" -> tickBloccato();
            default -> { /* TERMINATO o fase sconosciuta: nulla da fare */ }
        }
    }

    // ===============================================================
    // CARICAMENTO ITINERARIO DALLA CENTRALE
    // ===============================================================

    /**
     * Chiede alla Centrale l'itinerario del treno (GET /api/treni/{id}/itinerario).
     * In caso di errore (Centrale spenta, 404 perché il treno non ha ancora un
     * itinerario assegnato...) logga e riprova al massimo ogni 15 secondi.
     */
    private void tentaCaricamentoItinerario() {
        // -- per non intasare il sistema di richieste dell'itinerario
        Instant adesso = Instant.now();
        if (Duration.between(ultimoTentativoCaricamento, adesso).getSeconds() < RETRY_CARICAMENTO_SECONDI) {
            return; // troppo presto per riprovare
        }
        ultimoTentativoCaricamento = adesso;

        // richiesta http
        String url = centraleUrl + "/api/treni/" + trainDB.trenoId + "/itinerario";
        try {
            HttpRequest richiesta = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> risposta = secureHttpClient.get().send(richiesta, HttpResponse.BodyHandlers.ofString());

            if (risposta.statusCode() != 200) {
                LOG.warnf("⚠️ [TWIN] Itinerario non disponibile (HTTP %d da %s), riprovo tra %ds",
                        risposta.statusCode(), url, RETRY_CARICAMENTO_SECONDI);
                return;
            }

            // todo: controllla un po meglio come è sto modello che mi sembra un po strano questo inserimento dei dati

            //  --- si riempie il db locale delle informaizoni riguardo alla tratta
            // Parsing della risposta: {"itinerarioId":"...","stazioni":[{...},...]}
            JsonNode radice = objectMapper.readTree(risposta.body());
            List<TrainDB.TappaItinerario> tappe = new ArrayList<>();
            List<String> soloId = new ArrayList<>();// ?
            // per ogni tappa nel json immetterla nella lista tappe
            for (JsonNode nodo : radice.path("stazioni")) {
                TrainDB.TappaItinerario tappa = new TrainDB.TappaItinerario(
                        nodo.path("id").asText(),
                        nodo.path("nome").asText(),
                        nodo.path("latitudine").asDouble(),
                        nodo.path("longitudine").asDouble(),
                        nodo.path("tempoVersoProssimaMinuti").asInt());
                tappe.add(tappa);
                soloId.add(tappa.id);
            }

            // un contrllo del cavolo sulla validità dell'itinerario,  e si dovrebbe attendere che la stazione riflagghi vera la ricaricaItinerarioRichiesta se no il treno rimane fermo
            if (tappe.size() < 2) {
                LOG.warnf("⚠️ [TWIN] Itinerario con meno di 2 stazioni (%d): impossibile viaggiare, riprovo", tappe.size());
                return;
            }
            // aggiornamento del db
            trainDB.itinerarioId = radice.path("itinerarioId").asText(null);
            trainDB.itinerario = tappe; // perchè è una lista di tappe
            trainDB.stazioniTratta = soloId; // ma come cazzo lo abbiamo fatto sto modello ??

            LOG.infof("🗺️ [TWIN] Itinerario %s caricato: %d stazioni %s",
                    trainDB.itinerarioId, tappe.size(), soloId);
            // immagino totalmente di best practise, niente manda realmente interrupt a questo thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // sarebbe qualsiasi altra eccezione
        } catch (Exception e) {
            LOG.warnf("⚠️ [TWIN] Centrale non raggiungibile (%s), riprovo tra %ds: %s",
                    url, RETRY_CARICAMENTO_SECONDI, e.getMessage());
        }
    }

    /**
     * Mette il convoglio in uno stato coerente con il fatto che non ha (più) un itinerario:
     * fermo, a velocità zero, in attesa di un percorso o della soppressione, come chiede
     * RF02.5.1 ("smette di viaggiare e resta fermo in attesa di essere soppresso").
     *
     * <p>Gli stati decisi da fuori non si toccano: un convoglio SOPPRESSO resta soppresso e
     * uno in EMERGENZA resta in avaria, che sono informazioni più importanti di questa.</p>
     */
    private void fermaPerMancanzaItinerario() {
        if ("SOPPRESSO".equals(trainDB.stato) || "EMERGENZA".equals(trainDB.stato)) {
            return;
        }
        boolean eraInMarcia = !"FERMO".equals(trainDB.stato)
                || !"SENZA_ITINERARIO".equals(trainDB.faseViaggio);
        trainDB.stato = "FERMO";
        trainDB.velocita = 0;
        trainDB.faseViaggio = "SENZA_ITINERARIO";
        trainDB.prossimaStazione = null;
        trainDB.stazioneBloccante = null;
        if (eraInMarcia) {
            LOG.warn("🛑 [TWIN] Nessun itinerario assegnato: resto fermo in attesa di un percorso");
        }
    }

    /**
     * Posiziona il treno alla prima stazione dell'itinerario e apre la sosta iniziale.
     * NOTA: alla prima partenza NON viene pubblicata alcuna ENTRATA, solo la USCITA
     * quando la sosta scade (caso "esce senza entrare" della stazione di partenza).
     */
    private void avviaViaggio() {
        TrainDB.TappaItinerario prima = trainDB.itinerario.get(0);
        trainDB.indiceStazione = 0;
        trainDB.direzione = "andata";
        trainDB.stato = "FERMO";
        trainDB.faseViaggio = "IN_STAZIONE";
        trainDB.latitudine = prima.latitudine;
        trainDB.longitudine = prima.longitudine;
        trainDB.velocita = 0;
        trainDB.progressPercent = 0;
        trainDB.stazioneCorrente = prima.id;
        trainDB.prossimaStazione = trainDB.itinerario.get(1).id;
        trainDB.tArrivoInStazione = Instant.now();
        trainDB.passeggeri = 50 + random.nextInt(351); // primi passeggeri saliti al capolinea
        sostaCorrenteSecondi = sostaSecondi;
        viaggioAvviato = true;
        LOG.infof("🚉 [TWIN] Treno %s pronto alla partenza da %s (%s), prossima fermata %s",
                trainDB.trenoId, prima.nome, prima.id, trainDB.prossimaStazione);
    }

    // ===============================================================
    // FASI DELLA MACCHINA A STATI
    // ===============================================================

    /**
     * Fase IN_STAZIONE: il treno riparte quando la sosta è scaduta, lo stato è FERMO
     * (non SOPPRESSO/EMERGENZA) e né la stazione corrente né la prossima risultano guaste.
     * Alla partenza pubblica il passaggio USCITA via TrainGateway.
     */
    private void tickInStazione() {
        trainDB.velocita = 0;
        if (trainDB.tArrivoInStazione == null
                || Duration.between(trainDB.tArrivoInStazione, Instant.now()).getSeconds() < sostaCorrenteSecondi) { // sostaCorrenteSecondi: hardcoddato nelle proprietà di application.propriety modificate in run allinizione del file
            return; // sosta ancora in corso
        }
        // cotrollo di robustezza
        if (!"FERMO".equals(trainDB.stato)) {
            return; // solo un treno regolarmente fermo può ripartire
        }

        TrainDB.TappaItinerario corrente = tappaCorrente();
        TrainDB.TappaItinerario prossima = tappaProssima();
        if (prossima == null) {
            trainDB.faseViaggio = "TERMINATO"; // non dovrebbe accadere con l'inversione al capolinea
            return;
        }

        // Trattenuto in stazione se la corrente o la prossima sono note come guaste.
        // Non basta un return "muto": si entra nella fase BLOCCATO_GUASTO_STAZIONE, così
        // il treno accumula ritardo e /treno/viaggio dice anche QUALE stazione lo blocca.
        // Serve quando l'alert di guasto è arrivato mentre il treno era altrove: in quel
        // caso il gateway non aveva chiamato bloccaPerGuastoStazione perché la stazione
        // non era né la corrente né la prossima.
        String stazioneGuasta = null;
        if (trainDB.stazioniGuaste.contains(corrente.id)) {
            stazioneGuasta = corrente.id;
        } else if (trainDB.stazioniGuaste.contains(prossima.id)) {
            stazioneGuasta = prossima.id;
        }
        if (stazioneGuasta != null) {
            bloccaPerGuastoStazione(stazioneGuasta);
            return;
        }

        // Partenza: pubblica USCITA e passa in viaggio
        trainGateway.notificaPassaggioStazione(corrente.id, "USCITA");
        trainDB.prossimaStazione = prossima.id;
        trainDB.stato = "IN_VIAGGIO";
        trainDB.faseViaggio = "IN_VIAGGIO";
        trainDB.tInizioTratta = Instant.now();
        trainDB.progressPercent = 0;
        LOG.infof("🚆 [TWIN] Partenza da %s verso %s (%d min simulati)",
                corrente.id, prossima.id, tempoTrattaMinuti(corrente, prossima));
    }

    /**
     * Fase IN_VIAGGIO: aggiorna progressPercent in base al tempo trascorso rispetto
     * al tempo di tratta scalato e interpola linearmente le coordinate GPS tra le
     * due stazioni. Al 100% il treno arriva: pubblica ENTRATA e torna IN_STAZIONE.
     */
    private void tickInViaggio() {
        // Se lo stato era rientrato da un'emergenza (RESOLVED), il treno riprende la marcia
        if ("FERMO".equals(trainDB.stato)) {
            trainDB.stato = "IN_VIAGGIO";
        }

        TrainDB.TappaItinerario da = tappaCorrente();
        TrainDB.TappaItinerario a = tappaProssima();
        if (a == null || trainDB.tInizioTratta == null) {
            return;
        }

        double tempoTrattaSecondi = tempoTrattaMinuti(da, a) * 60.0 / fattoreAccelerazione;
        double trascorsi = Duration.between(trainDB.tInizioTratta, Instant.now()).toMillis() / 1000.0;
        double progress = Math.min(100.0, trascorsi / tempoTrattaSecondi * 100.0);

        trainDB.progressPercent = progress;
        // Interpolazione lineare della posizione tra le coordinate delle due stazioni
        trainDB.latitudine = da.latitudine + (a.latitudine - da.latitudine) * progress / 100.0;
        trainDB.longitudine = da.longitudine + (a.longitudine - da.longitudine) * progress / 100.0;
        //?
        trainDB.prossimaStazione = a.id;

        if (progress >= 100.0) {
            arrivoInStazione(a);
        }
    }

    /**
     * Gestisce l'arrivo nella stazione di destinazione della tratta corrente:
     * avanza l'indice, pubblica ENTRATA, rinnova i passeggeri e, se la stazione
     * è un capolinea, inverte la direzione con sosta doppia.
     *
     * @param arrivo Tappa in cui il treno è appena entrato.
     */
    private void arrivoInStazione(TrainDB.TappaItinerario arrivo) {
        // Pubblica arrivo
        trainDB.tArrivoInStazione = Instant.now();

        boolean andata = "andata".equals(trainDB.direzione);
        trainDB.indiceStazione = andata ? trainDB.indiceStazione + 1 : trainDB.indiceStazione - 1;
        trainDB.latitudine = arrivo.latitudine;
        trainDB.longitudine = arrivo.longitudine;
        trainDB.progressPercent = 100;
        trainDB.velocita = 0;
        trainDB.stato = "FERMO";
        trainDB.faseViaggio = "IN_STAZIONE";

        // Salita/discesa passeggeri simulata a ogni ENTRATA
        trainDB.passeggeri = 50 + random.nextInt(351);

        boolean capolinea = (andata && trainDB.indiceStazione == trainDB.itinerario.size() - 1)
                || (!andata && trainDB.indiceStazione == 0);
        if (capolinea) {
            // Inversione di direzione: al capolinea la sosta è doppia
            trainDB.direzione = andata ? "ritorno" : "andata";
            sostaCorrenteSecondi = sostaSecondi * 2;
            LOG.infof("🔁 [TWIN] Capolinea %s raggiunto: inversione di marcia (%s), sosta doppia",
                    arrivo.id, trainDB.direzione);
        } else {
            sostaCorrenteSecondi = sostaSecondi;
        }

        // Pubblica il passaggio ENTRATA (aggiorna anche stazioneCorrente nel DB)
        trainGateway.notificaPassaggioStazione(arrivo.id, "ENTRATA");
        TrainDB.TappaItinerario prossima = tappaProssima();
        trainDB.prossimaStazione = prossima != null ? prossima.id : null;
        LOG.infof("🚉 [TWIN] Arrivo in %s (ritardo %d min, passeggeri %d)",
                arrivo.id, trainDB.ritardoMinuti, trainDB.passeggeri);
    }

    /**
     * Fase BLOCCATO_GUASTO_STAZIONE: il treno resta fermo e accumula un minuto
     * di ritardo simulato ogni 60/fattore secondi reali. Se il blocco è scattato
     * a metà tratta il cronometro della tratta viene congelato per non far
     * avanzare il progressPercent.
     */
    private void tickBloccato() {
        trainDB.velocita = 0;
        if ("IN_VIAGGIO".equals(fasePrimaDelBlocco)) {
            congelaAvanzamentoTratta();
        }
        secondiDiBlocco += 1.0;
        double secondiPerMinutoSimulato = 60.0 / fattoreAccelerazione;
        while (secondiDiBlocco >= secondiPerMinutoSimulato) {
            trainDB.ritardoMinuti++;
            secondiDiBlocco -= secondiPerMinutoSimulato;
        }
    }

    /**
     * Sposta avanti di un secondo l'inizio della tratta corrente: il tempo
     * "percorso" resta invariato e il progressPercent non cresce mentre il
     * treno è bloccato o in emergenza.
     */
    private void congelaAvanzamentoTratta() {
        if (trainDB.tInizioTratta != null) {
            trainDB.tInizioTratta = trainDB.tInizioTratta.plusSeconds(1);
        }
    }

    // ===============================================================
    // API USATE DAL GATEWAY (alert MQTT)
    // ===============================================================

    /**
     * Blocca il treno perché una stazione rilevante (corrente o prossima) è guasta.
     * Memorizza la fase in corso per poterla riprendere allo sblocco.
     *
     * @param stazioneId Stazione guasta che causa il blocco.
     */
    public void bloccaPerGuastoStazione(String stazioneId) {
        if (!viaggioAvviato || "SOPPRESSO".equals(trainDB.stato)
                || "BLOCCATO_GUASTO_STAZIONE".equals(trainDB.faseViaggio)) {
            return;
        }
        fasePrimaDelBlocco = trainDB.faseViaggio;
        secondiDiBlocco = 0;
        trainDB.stazioneBloccante = stazioneId;
        trainDB.faseViaggio = "BLOCCATO_GUASTO_STAZIONE";
        trainDB.stato = "FERMO";
        trainDB.velocita = 0;
        LOG.warnf("⛔ [TWIN] Treno bloccato: stazione %s guasta (ero in fase %s)", stazioneId, fasePrimaDelBlocco);
    }

    /**
     * Sblocca il treno dopo la risoluzione del guasto della stazione bloccante
     * e riprende la fase in cui si trovava prima del blocco.
     */
    public void sbloccaDaGuastoStazione() {
        if (!"BLOCCATO_GUASTO_STAZIONE".equals(trainDB.faseViaggio)) {
            return;
        }
        String faseDaRiprendere = fasePrimaDelBlocco != null ? fasePrimaDelBlocco : "IN_STAZIONE";
        fasePrimaDelBlocco = null;
        trainDB.stazioneBloccante = null;
        trainDB.faseViaggio = faseDaRiprendere;
        if ("IN_VIAGGIO".equals(faseDaRiprendere)) {
            trainDB.stato = "IN_VIAGGIO"; // riprende la marcia dal punto in cui era
        }
        LOG.infof("✅ [TWIN] Guasto stazione risolto: riprendo la fase %s (ritardo accumulato %d min)",
                faseDaRiprendere, trainDB.ritardoMinuti);
    }

    /**
     * Segnala che la Centrale ha modificato l'itinerario del treno
     * (alert ITINERARIO_AGGIORNATO): al prossimo tick il motore lo riscarica
     * e fa ripartire il viaggio dalla prima stazione.
     */
    public void richiediRicaricaItinerario() {
        ricaricaItinerarioRichiesta = true;
    }

    // ===============================================================
    // HELPER SULL'ITINERARIO
    // ===============================================================

    /** @return La tappa in cui il treno si trova (o da cui è partito). */
    private TrainDB.TappaItinerario tappaCorrente() {
        return trainDB.itinerario.get(trainDB.indiceStazione);
    }

    /**
     * @return La prossima tappa nella direzione corrente, oppure null se il treno
     *         è oltre i limiti dell'itinerario (non dovrebbe accadere).
     */
    private TrainDB.TappaItinerario tappaProssima() {
        int prossimoIndice = "andata".equals(trainDB.direzione)
                ? trainDB.indiceStazione + 1
                : trainDB.indiceStazione - 1;
        if (prossimoIndice < 0 || prossimoIndice >= trainDB.itinerario.size()) {
            return null;
        }
        return trainDB.itinerario.get(prossimoIndice);
    }

    /**
     * Tempo di percorrenza (in minuti simulati) del segmento tra due tappe adiacenti.
     * In andata il tempo è sulla tappa di partenza, al ritorno il segmento è lo
     * stesso dell'andata quindi il tempo sta sulla tappa di arrivo.
     */
    //?? capire come
    private int tempoTrattaMinuti(TrainDB.TappaItinerario da, TrainDB.TappaItinerario a) {
        int minuti = "andata".equals(trainDB.direzione) ? da.tempoVersoProssimaMinuti : a.tempoVersoProssimaMinuti;
        return Math.max(1, minuti); // mai 0 per evitare divisioni per zero
    }
}
