package it.uni.reti2.elaboration;

import com.google.gson.JsonObject;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.ingestion.payload.AlertMsg;
import it.uni.reti2.ingestion.payload.HeartbeatMsg;
import it.uni.reti2.ingestion.payload.TelemetriaMsg;
import it.uni.reti2.ingestion.payload.TransitoMsg;
import it.uni.reti2.persistence.Database;
import it.uni.reti2.persistence.RailwayRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il gestore degli eventi della Centrale: l'unico punto da cui passano le mosse degli automi,
 * e quindi l'unico che cambia lo stato della rete.
 *
 * <p>Gli eventi arrivano da tre generatori, che traducono in un EventoCentrale quello che è
 * successo e non scrivono niente: IngestionService (i messaggi MQTT del campo: telemetria,
 * passaggi, battiti, alert), RestApiGateway (i comandi dell'operatore: risolvi un allarme,
 * sopprimi una corsa, invia la squadra, cambia a mano uno stato) e GeneratoreEventiTempo (il
 * tick del watchdog e della fotografia periodica).</p>
 *
 * <p>Ogni evento si gestisce in tre fasi, sempre in quest'ordine, come nel treno:</p>
 * <ol>
 *   <li><b>guardie</b>: si legge lo stato com'è adesso (la cache e, quando serve, il
 *       database) e si decide tutto in variabili locali, senza scrivere niente: quale
 *       automa si muove, quali guasti si aprono o si chiudono, quale stazione torna ONLINE.
 *       Se una guardia dice di no si esce qui, quando non c'è niente da disfare;</li>
 *   <li><b>assegnamenti</b>: si scrive il database, in una transazione, e poi si fanno le
 *       mosse degli automi sulla cache. Il database va prima apposta: se il commit fallisce
 *       l'eccezione esce da qui, e la cache (quindi le risposte REST e la mappa) non mostra
 *       valori che sul database non sono mai stati scritti;</li>
 *   <li><b>azioni</b>: si avvisano la web app (WebSocket) e il campo (MQTT), quando lo stato
 *       è già tutto scritto e coerente.</li>
 * </ol>
 *
 * <p>Gli automi (AutomaTreno, AutomaStazione, AutomaGuasto) non si chiamano mai fra loro:
 * quando un evento muove più cose le elenca questo gestore, nel metodo di quell'evento. Un
 * guasto CRITICAL di un treno, per esempio, apre il guasto, rompe il treno e chiude la stazione
 * in cui si trova: sono tre automi e tre mosse, decise tutte nella fase 1.</p>
 *
 * <p>{@link #gestisci} è synchronized: i messaggi MQTT (thread di Paho), le richieste REST
 * (thread di Javalin) e il watchdog arrivano su thread diversi, e le risposte della fase 1
 * valgono solo se nessun altro scrive fra la fase 1 e la fase 2. Così ogni evento va da cima a
 * fondo prima che parta il successivo. Le pubblicazioni MQTT non aspettano il broker (le
 * consegna il thread di CodaPubblicazioni), quindi il lock dura quanto le letture e le
 * scritture sul database.</p>
 */
public class GestoreEventi {

    private static final Logger LOG = Logger.getLogger(GestoreEventi.class.getName());

    /**
     * Come è andato un evento. Ai messaggi del campo non serve (non c'è nessuno da avvisare),
     * ai comandi dell'operatore sì: RestApiGateway lo traduce nella risposta HTTP.
     *
     * @param risposta Com'è finita.
     * @param corpo    Cosa rimandare alla web app (il treno, la stazione, il guasto), o null.
     * @param errore   Il motivo del rifiuto, per RIFIUTATO.
     */
    public record Esito(Risposta risposta, Object corpo, String errore) {

        public enum Risposta {
            /** L'evento ha fatto quello che doveva. */
            FATTO,
            /** L'evento non riguarda niente di noto (un treno o una stazione sconosciuti): scartato. */
            IGNORATO,
            /** Il comando si riferisce a qualcosa che non esiste (404). */
            NON_TROVATO,
            /** Una guardia ha detto di no, con un motivo da mostrare all'operatore (400). */
            RIFIUTATO,
            /** Il comando non ha senso nello stato attuale (409). */
            CONFLITTO
        }

        static Esito fatto(Object corpo) {
            return new Esito(Risposta.FATTO, corpo, null);
        }

        static Esito fatto() {
            return fatto(null);
        }

        static Esito ignorato() {
            return new Esito(Risposta.IGNORATO, null, null);
        }

        static Esito nonTrovato() {
            return new Esito(Risposta.NON_TROVATO, null, null);
        }

        static Esito rifiutato(String errore) {
            return new Esito(Risposta.RIFIUTATO, null, errore);
        }

        static Esito conflitto() {
            return new Esito(Risposta.CONFLITTO, null, null);
        }
    }

    /**
     * La riga di audit che accompagna un guasto dedotto su una stazione (tabella
     * eventi_stazioni) e, se c'è lo stato di prima, il cambio di stato da storicizzare.
     */
    private record EventoAudit(String stato, String tipoEvento, String descrizione, String statoPrecedente) {
    }

    /** Lo stato della rete: qui si legge soltanto, a scriverlo sono gli automi. */
    public TrafficLogicEngine statoRete;

    /** Le letture e le scritture sul database. */
    public RailwayRepository repository;

    /** Le transazioni della fase 2. */
    public Database db;

    /** Dove si trova un convoglio sul suo itinerario (letture per le guardie). */
    public NavigazioneItinerario navigazione;

    /** Stato, posizione e ritardo dei convogli. */
    public AutomaTreno treni;

    /** ONLINE, GUASTA, MANUTENZIONE, OFFLINE delle stazioni. */
    public AutomaStazione stazioni;

    /** APERTO, RISOLTO dei guasti. */
    public AutomaGuasto guasti;

    /** Azione: gli eventi per la web app. */
    public NotificatoreRealtime notificatore;

    /** Azione: gli alert per il campo, su railway/alerts. */
    public PubblicatoreAlert pubblicatore;

    /** Secondi di silenzio oltre i quali una stazione viene considerata OFFLINE. */
    public long heartbeatTimeoutSecondi = 30; // fault.heartbeat.timeout.secondi

    /** Secondi di immobilità fuori stazione oltre i quali un treno è considerato fermo anomalo. */
    public long trenoFermoTimeoutSecondi = 90; // fault.treno.fermo.timeout.secondi

    /**
     * Il gestore degli eventi: ogni tipo di evento ha il suo metodo, e ognuno è scritto nelle
     * tre fasi descritte sopra.
     *
     * @param evento Quello che è successo, già tradotto da uno dei generatori.
     * @return Com'è andata (serve ai comandi dell'operatore).
     */
    public synchronized Esito gestisci(EventoCentrale evento) {
        return switch (evento.tipo()) {
            case TELEMETRIA -> suTelemetria((TelemetriaMsg) evento.messaggio(), evento.payloadDalCampo());
            case PASSAGGIO -> suPassaggio((TransitoMsg) evento.messaggio(), evento.payloadDalCampo());
            case HEARTBEAT -> suHeartbeat((HeartbeatMsg) evento.messaggio(), evento.payloadDalCampo());
            case GUASTO_SEGNALATO -> suGuastoSegnalato((AlertMsg) evento.messaggio());
            case GUASTO_RIENTRATO -> suGuastoRientrato((AlertMsg) evento.messaggio());
            case RISOLVI_GUASTO -> suRisolviGuasto(evento.id());
            case SOPPRIMI_TRENO -> suSoppressione(evento.id());
            case INVIA_SQUADRA -> suInvioSquadra(evento.id());
            case STATO_STAZIONE_OPERATORE -> suStatoStazioneDallOperatore(evento.id(), evento.stato());
            case STATO_TRENO_OPERATORE -> suStatoTrenoDallOperatore(evento.id(), evento.stato());
            case CONTROLLO_HEARTBEAT -> suControlloHeartbeat();
            case CONTROLLO_TRENI_FERMI -> suControlloTreniFermi();
            case FOTOGRAFIA -> suFotografia();
        };
    }

    // ===============================================================
    // TELEMETRIA: dove si trova un convoglio adesso
    // ===============================================================

    /**
     * Registra un frame di telemetria: aggiorna il convoglio, rifà la previsione di arrivo,
     * allinea lo stato sul database e rilancia tutto al frontend.
     *
     * <p>Un convoglio che non è in cache non esiste e il frame viene scartato. La cache è la
     * fotografia della tabella Treni, quindi "non in cache" vuol dire "non in anagrafica": i
     * treni si creano dall'amministrazione, non dalla telemetria.</p>
     *
     * <p>Un frame di telemetria è anche un segno di vita: se il convoglio si sta di nuovo
     * muovendo, il guasto "treno fermo" che il watchdog gli aveva aperto va chiuso.</p>
     */
    private Esito suTelemetria(TelemetriaMsg msg, JsonObject payloadDalCampo) {
        // 1. guardie
        Treno treno = statoRete.getTreno(msg.trenoId());
        if (treno == null) {
            LOG.warning(String.format("🚫 Telemetria dal treno sconosciuto '%s': frame scartato (i treni si creano dall'amministrazione)",
                    msg.trenoId()));
            return Esito.ignorato();
        }
        Tratta trattaDedotta = trattaDaRiallineare(treno, msg);
        Treno suDb = repository.trovaTreno(msg.trenoId());
        List<Guasto> fermiDaChiudere = guastiTrenoFermoDaChiudere(treno.id, msg);
        Instant adesso = Instant.now();

        // 2. assegnamenti
        db.inTransazione(() -> {
            if (trattaDedotta != null && suDb != null) {
                // La tratta dedotta va anche sulla riga del treno, così che a rileggerla dal
                // database (i transiti ci appendono la tratta percorsa) si trovi la stessa
                // cosa che sta in cache.
                repository.aggiornaPosizioneTreno(suDb.id, trattaDedotta.id);
                suDb.posizioneAttualeTratta = trattaDedotta;
            }
            scriviStatoTreno(suDb, msg.trenoId(), msg.stato());
            for (Guasto guasto : fermiDaChiudere) {
                repository.chiudiGuasto(guasto, adesso);
            }
        });
        treni.telemetria(treno, msg, trattaDedotta, adesso);
        for (Guasto guasto : fermiDaChiudere) {
            guasti.chiudi(guasto, adesso);
        }

        // 3. azioni
        if (trattaDedotta != null) {
            LOG.fine(String.format("🧭 Treno %s riallineato sulla tratta %s (prossima fermata %s, corsa di %s)",
                    treno.id, trattaDedotta.id, msg.prossimaStazione(), msg.direzione()));
        }
        notificatore.telemetria(payloadDalCampo, treno);
        annunciaChiusure(fermiDaChiudere, "[WATCHDOG] Treno " + treno.id + " di nuovo in movimento");
        return Esito.fatto();
    }

    /**
     * La tratta su cui si trova il convoglio, dedotta dalla fermata che dichiara di avere
     * davanti.
     *
     * <p>{@code posizioneAttualeTratta} non la scrive solo il passaggio, cioè il sensore di
     * terra della stazione: se una stazione del percorso è spenta il suo transito non arriva
     * mai, e il campo resterebbe indietro di un segmento ("sta fra Bologna e Firenze" per un
     * treno che ha già superato Firenze). Il frontend da quel segmento ricava i due capi
     * dell'arco su cui disegnare il convoglio, e con la tratta sbagliata il treno sparirebbe
     * dalla mappa proprio quando è fermo a metà tratta per un guasto.</p>
     *
     * <p>Il ricalcolo costa una lettura dell'itinerario, quindi si fa solo quando serve: se la
     * tratta in cache porta già alla fermata dichiarata è quella giusta. A regime succede una
     * volta per tratta percorsa.</p>
     *
     * @return La tratta dedotta, oppure null se quella in cache va già bene o se l'itinerario
     *         non ne ha nessuna verso quella stazione (e allora quella nota resta dov'è).
     */
    private Tratta trattaDaRiallineare(Treno treno, TelemetriaMsg msg) {
        // Senza percorso o al capolinea non c'e' nessuna fermata da cui dedurre la tratta.
        if (treno.itinerario == null || msg.prossimaStazione() == null) {
            return null;
        }
        Tratta inCache = treno.posizioneAttualeTratta;
        if (inCache != null && msg.prossimaStazione().equals(
                navigazione.estremoVersoCuiSiViaggia(inCache, msg.direzione()))) {
            return null; // gia' coerente con quello che il treno dichiara
        }
        return navigazione.trattaVerso(treno.itinerario.id, msg.prossimaStazione(), msg.direzione());
    }

    /**
     * I guasti "treno fermo" dedotti dal watchdog da chiudere perché il convoglio si muove di
     * nuovo (o è entrato in una stazione, dove stare fermi è lecito). Un'avaria dichiarata dal
     * treno invece non si chiude così: la chiude solo l'operatore.
     */
    private List<Guasto> guastiTrenoFermoDaChiudere(String trenoId, TelemetriaMsg msg) {
        boolean inMovimento = msg.velocita() > 0
                || (msg.stazioneCorrente() != null && !msg.stazioneCorrente().isEmpty());
        if (!inMovimento) {
            return List.of();
        }
        Guasto inCache = guasti.apertoPer(trenoId, "treno_fermo");
        if (inCache == null || !inCache.dedottoConPrefisso(Guasto.MSG_TRENO_FERMO)) {
            return List.of(); // nessun guasto dedotto da chiudere (o e' un'avaria dichiarata dal treno)
        }
        return dedottiAperti(trenoId, "TRENO", Guasto.MSG_TRENO_FERMO);
    }

    // ===============================================================
    // PASSAGGIO: un convoglio entra o esce da una stazione
    // ===============================================================

    /**
     * Registra il passaggio rilevato dal sensore di terra: è qui che la Centrale misura il
     * ritardo, aggiorna la posizione sull'itinerario, ne informa il treno e scrive il transito.
     *
     * <p>Un transito è una <b>coppia</b> di eventi, ingresso e uscita, che il database tiene
     * su una riga sola: l'ingresso la apre lasciando l'uscita vuota, l'uscita la chiude. Fa
     * eccezione la stazione di partenza, da cui un convoglio esce senza esserci mai entrato: in
     * quel caso si registra un passaggio puntuale con entrata e uscita allo stesso istante.</p>
     *
     * <p>La direzione del treno è quella della sua ultima telemetria, con "andata" come
     * default: il treno non la dichiara più su un topic suo. Nella finestra fra un'inversione
     * al capolinea e il frame di telemetria successivo può quindi essere quella vecchia.</p>
     *
     * <p>Il ritardo lo misura la mossa dell'automa (PrevisioneArrivi confronta l'ora vera con
     * quella programmata): la fase 3 legge soltanto di quanto è cambiato, per dirlo al treno.
     * Sulla riga del transito si congela il ritardo di adesso: è quello che lo storico dovrà
     * mostrare fra un mese.</p>
     */
    private Esito suPassaggio(TransitoMsg msg, JsonObject payloadDalCampo) {
        // 1. guardie
        Treno treno = statoRete.getTreno(msg.trenoId());
        String direzione = treno != null && treno.direzione != null ? treno.direzione : "andata";
        Treno suDb = repository.trovaTreno(msg.trenoId());
        NavigazioneItinerario.Posizione posizione = suDb != null && suDb.itinerario != null
                ? navigazione.dopoIlPassaggio(suDb.itinerario.id, msg.stazioneId(), msg.entrata(), direzione)
                : NavigazioneItinerario.Posizione.sconosciuta();
        Stazione stazioneSuDb = repository.trovaStazione(msg.stazioneId());
        boolean registrabile = suDb != null && stazioneSuDb != null;
        Transito aperto = registrabile && !msg.entrata()
                ? repository.trovaTransitoAperto(msg.trenoId(), msg.stazioneId())
                : null;
        boolean cambiaFase = treno != null && treni.cambiaFaseAlPassaggio(treno);
        Stazione stazione = statoRete.getStazione(msg.stazioneId());
        Instant adesso = Instant.now();
        Instant quando = msg.quando() != null ? msg.quando() : adesso;

        // 2. assegnamenti
        if (posizione.trattaCorrente() != null) {
            db.inTransazione(() -> repository.aggiornaPosizioneTreno(suDb.id, posizione.trattaCorrente().id));
            suDb.posizioneAttualeTratta = posizione.trattaCorrente();
        }
        int variazioneRitardo = treno != null
                ? treni.passaggio(treno, msg, direzione, posizione, cambiaFase, adesso)
                : 0;
        // Il ritardo va congelato adesso: e' quello che il convoglio ha nel momento del
        // passaggio, appena aggiornato dalla mossa qui sopra.
        int ritardoAlPassaggio = treno != null ? treno.ritardo : 0;
        if (registrabile) {
            db.inTransazione(() -> scriviTransito(msg, suDb, stazioneSuDb, aperto, quando, ritardoAlPassaggio));
        }
        if (stazione != null) {
            if (msg.entrata()) {
                stazioni.entraTreno(stazione);
            } else {
                stazioni.esceTreno(stazione);
            }
        }

        // 3. azioni
        if (variazioneRitardo != 0) {
            pubblicatore.pubblicaRitardo(treno, msg.stazioneId(), variazioneRitardo, msg.entrata());
        }
        if (!registrabile) {
            LOG.warning(String.format("🚫 Transito ignorato: treno '%s' o stazione '%s' non presenti a DB",
                    msg.trenoId(), msg.stazioneId()));
        }
        notificatore.transito(payloadDalCampo, msg, treno);
        return Esito.fatto();
    }

    /**
     * Apre o chiude il transito sul database e lo storicizza. Gira dentro la transazione
     * aperta dal chiamante.
     *
     * @param aperto Il transito ancora aperto in quella stazione (solo per le uscite), o null.
     */
    private void scriviTransito(TransitoMsg msg, Treno suDb, Stazione stazioneSuDb, Transito aperto,
                                Instant quando, int ritardoMinuti) {
        if (msg.entrata()) {
            Transito transito = nuovoTransito(suDb, stazioneSuDb, quando, ritardoMinuti);
            repository.salvaTransito(transito);
            repository.salvaStoricoTransito(transito);
            return;
        }
        if (aperto != null) {
            aperto.tempoUscita = quando;
            aperto.ritardoMinuti = ritardoMinuti;
            repository.chiudiTransito(aperto);
            repository.salvaStoricoTransito(aperto);
            return;
        }
        // Esce senza essere entrato: e' la stazione di partenza.
        Transito puntuale = nuovoTransito(suDb, stazioneSuDb, quando, ritardoMinuti);
        puntuale.tempoUscita = quando;
        repository.salvaTransito(puntuale);
        repository.salvaStoricoTransito(puntuale);
    }

    /** La riga di un transito che comincia adesso, sulla tratta in cui si trova il convoglio. */
    private Transito nuovoTransito(Treno treno, Stazione stazione, Instant quando, int ritardoMinuti) {
        Transito transito = new Transito();
        transito.id = "TR-" + quando.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        transito.treno = treno;
        transito.stazione = stazione;
        transito.tratta = treno.posizioneAttualeTratta;
        transito.tempoEntrata = quando;
        transito.ritardoMinuti = ritardoMinuti;
        return transito;
    }

    // ===============================================================
    // HEARTBEAT: una stazione dichiara di essere viva
    // ===============================================================

    /**
     * Registra il battito di una stazione.
     *
     * <p>Lo stato dichiarato dal nodo si prende, salvo che la stazione sia in MANUTENZIONE
     * (guardia dell'automa). Se la stazione era OFFLINE il battito chiude il fail-stop: i
     * guasti "heartbeat assente" che il watchdog le aveva aperto si chiudono. Il nuovo stato
     * si annuncia al campo solo quando la stazione torna agibile e non le resta nessun guasto
     * aperto: è quello il messaggio che fa ripartire i treni trattenuti.</p>
     */
    private Esito suHeartbeat(HeartbeatMsg msg, JsonObject payloadDalCampo) {
        // 1. guardie
        Stazione stazione = statoRete.getStazione(msg.stazioneId());
        if (stazione == null) {
            LOG.warning(String.format("🚫 Heartbeat dalla stazione sconosciuta '%s': scartato", msg.stazioneId()));
            return Esito.ignorato();
        }
        String statoPrecedente = stazione.stato;
        boolean tornataDalFailStop = StatiCanonici.STAZIONE_OFFLINE.equalsIgnoreCase(statoPrecedente);
        boolean accettato = stazioni.accettaDalCampo(stazione, msg.stato());
        String statoNuovo = accettato ? msg.stato() : statoPrecedente;
        boolean statoCambiato = statoPrecedente == null || !statoPrecedente.equalsIgnoreCase(statoNuovo);
        // La riga di anagrafica serve solo per lo storico, che si scrive solo se lo stato cambia
        Stazione suDb = statoCambiato ? repository.trovaStazione(msg.stazioneId()) : null;
        List<Guasto> daChiudere = tornataDalFailStop
                ? dedottiAperti(msg.stazioneId(), "STAZIONE", Guasto.MSG_HEARTBEAT_PERSO)
                : List.of();
        boolean annunciaStato = statoCambiato
                && !StatiCanonici.stazioneTrattieneITreni(statoNuovo)
                && !restanoGuastiAperti(msg.stazioneId(), "STAZIONE", daChiudere);
        Instant adesso = Instant.now();

        // 2. assegnamenti
        db.inTransazione(() -> {
            if (suDb != null) {
                repository.salvaStoricoStatoStazione(suDb, statoNuovo, statoPrecedente);
            }
            for (Guasto guasto : daChiudere) {
                repository.chiudiGuasto(guasto, adesso);
            }
        });
        if (accettato) {
            stazioni.dalCampo(stazione, msg.stato());
        }
        stazioni.battito(stazione, adesso, msg.stato());
        for (Guasto guasto : daChiudere) {
            guasti.chiudi(guasto, adesso);
        }

        // 3. azioni
        annunciaChiusure(daChiudere, "[FAIL-STOP] Stazione " + msg.stazioneId() + " di nuovo raggiungibile");
        if (annunciaStato) {
            pubblicatore.pubblicaStatoStazione(msg.stazioneId(), statoNuovo);
        }
        notificatore.heartbeat(payloadDalCampo, stazione);
        return Esito.fatto();
    }

    // ===============================================================
    // GUASTI DAL CAMPO: GUASTO e RESOLVED su railway/alerts
    // ===============================================================

    /**
     * Registra un guasto segnalato da un nodo del campo.
     *
     * <p>Se per quella sorgente e quel tipo c'è già un guasto aperto non se ne crea un altro:
     * una stazione guasta ripete l'alert a ogni treno che entra, e l'elenco allarmi si
     * riempirebbe di copie dello stesso episodio. Cambia solo il testo.</p>
     *
     * <p>Se il guasto è CRITICAL la sorgente si ferma: una stazione diventa GUASTA (a meno che
     * ci sia la squadra al lavoro: allora resta in MANUTENZIONE, e lo si ridice al campo), un
     * treno diventa rotto. E se il treno si è rotto dentro una stazione, anche quella stazione
     * si chiude con un guasto dedotto dalla Centrale (RF02.1.2.2.1): con un convoglio fermo sui
     * binari non ci passa nessun altro.</p>
     *
     * <p>Il guasto segnalato non si ripubblica sul campo: l'ha già pubblicato il nodo, sullo
     * stesso topic. Si pubblica invece quello dedotto per il convoglio in stazione, che il campo
     * non conosce.</p>
     */
    private Esito suGuastoSegnalato(AlertMsg msg) {
        // 1. guardie: il guasto
        String tipo = AutomaGuasto.tipoPerFrontend(msg.sorgenteTipo(), msg.tipoGuastoDichiarato());
        Guasto giaAperto = guasti.apertoPer(msg.sorgenteId(), tipo);
        Guasto guasto = giaAperto != null ? giaAperto
                : guasti.nuovo(tipo, msg.severita(), msg.sorgenteTipo(), msg.sorgenteId(), msg.messaggio(),
                        Guasto.ORIGINE_SEGNALATO_CAMPO, msg.quando());

        //    la sorgente (solo un guasto CRITICAL la ferma)
        Stazione stazioneGuasta = msg.critico() && msg.sorgenteStazione()
                ? statoRete.getStazione(msg.sorgenteId()) : null;
        boolean stazioneAccetta = stazioneGuasta != null
                && stazioni.accettaDalCampo(stazioneGuasta, StatiCanonici.STAZIONE_GUASTA);
        boolean trenoCritico = msg.critico() && msg.sorgenteTreno();
        Treno trenoRotto = trenoCritico ? statoRete.getTreno(msg.sorgenteId()) : null;
        Treno trenoSuDb = trenoRotto != null ? repository.trovaTreno(trenoRotto.id) : null;

        //    la stazione in cui il convoglio si e' rotto
        String stazioneDelConvoglioId = trenoRotto != null ? trenoRotto.stazioneCorrente : null;
        boolean convoglioInStazione = stazioneDelConvoglioId != null && !stazioneDelConvoglioId.isEmpty();
        Stazione stazioneDelConvoglio = convoglioInStazione ? statoRete.getStazione(stazioneDelConvoglioId) : null;
        boolean giaChiusa = stazioneDelConvoglio != null && stazioni.trattieneITreni(stazioneDelConvoglio);
        boolean chiudiStazione = stazioneDelConvoglio != null && !giaChiusa;
        String statoPrimaDelConvoglio = chiudiStazione ? stazioneDelConvoglio.stato : null;
        Guasto derivato = chiudiStazione
                && guasti.apertoPer(stazioneDelConvoglioId, AutomaGuasto.TIPO_CONVOGLIO_IN_STAZIONE) == null
                ? guasti.nuovoDedotto(AutomaGuasto.TIPO_CONVOGLIO_IN_STAZIONE, "CRITICAL", "STAZIONE",
                        stazioneDelConvoglioId, "Convoglio " + trenoRotto.id + " guasto in stazione "
                                + stazioneDelConvoglioId + ": stazione non percorribile")
                : null;
        Instant adesso = Instant.now();

        // 2. assegnamenti
        db.inTransazione(() -> {
            if (giaAperto != null) {
                repository.aggiornaMessaggioGuasto(giaAperto.id, msg.messaggio());
            } else {
                repository.salvaGuasto(guasto);
                repository.salvaStoricoGuasto(guasto);
            }
            if (trenoRotto != null) {
                scriviStatoTreno(trenoSuDb, trenoRotto.id, "rotto");
            }
        });
        if (giaAperto != null) {
            guasti.aggiornaMessaggio(giaAperto, msg.messaggio());
        } else {
            guasti.apri(guasto);
        }
        if (stazioneAccetta) {
            stazioni.guasta(stazioneGuasta);
        }
        if (trenoRotto != null) {
            treni.rompi(trenoRotto, adesso);
        }
        if (chiudiStazione) {
            stazioni.guasta(stazioneDelConvoglio);
        }
        boolean derivatoScritto = derivato != null && scriviGuastoDedotto(derivato, new EventoAudit(
                StatiCanonici.STAZIONE_GUASTA,
                "CONVOGLIO_GUASTO",
                "Convoglio " + trenoRotto.id + " guasto: stazione non percorribile",
                statoPrimaDelConvoglio));
        if (derivatoScritto) {
            guasti.apri(derivato);
        }

        // 3. azioni
        if (giaAperto != null) {
            LOG.info(String.format("♻️ Guasto già aperto per %s (%s): aggiornato il messaggio invece di crearne un altro",
                    msg.sorgenteId(), tipo));
        }
        if (stazioneGuasta != null) {
            if (!stazioneAccetta) {
                LOG.info(String.format("🛠️ Guasto segnalato da %s mentre la squadra e' al lavoro: la stazione resta in MANUTENZIONE",
                        msg.sorgenteId()));
                pubblicatore.pubblicaStatoStazione(stazioneGuasta.id, stazioneGuasta.stato);
            }
            notificatore.statoStazione(stazioneGuasta);
        }
        if (trenoCritico && trenoRotto == null) {
            LOG.warning(String.format("🚫 Guasto da un treno sconosciuto '%s': cache non aggiornata", msg.sorgenteId()));
        }
        if (trenoRotto != null) {
            notificatore.statoTreno(trenoRotto);
            annunciaStazioneChiusaDalConvoglio(trenoRotto, stazioneDelConvoglioId, convoglioInStazione,
                    stazioneDelConvoglio, giaChiusa, derivatoScritto ? derivato : null);
        }
        notificatore.allarme(guasto);
        return Esito.fatto();
    }

    /** Le azioni sulla stazione in cui un convoglio si è rotto (fase 3 di GUASTO_SEGNALATO). */
    private void annunciaStazioneChiusaDalConvoglio(Treno treno, String stazioneId, boolean inStazione,
                                                    Stazione stazione, boolean giaChiusa, Guasto derivato) {
        if (!inStazione) {
            return; // il convoglio e' in tratta, non in stazione
        }
        if (stazione == null) {
            LOG.warning(String.format("🚫 Stazione '%s' non trovata in cache: impossibile marcarla GUASTA per convoglio %s",
                    stazioneId, treno.id));
            return;
        }
        if (giaChiusa) {
            LOG.info(String.format("♻️ Stazione %s già chiusa (%s): il convoglio %s non ne cambia lo stato",
                    stazioneId, stazione.stato, treno.id));
            return;
        }
        notificatore.statoStazione(stazione);
        if (derivato != null) {
            notificatore.allarme(derivato);
            pubblicatore.pubblicaGuasto(derivato);
            LOG.warning(String.format("🚨 Stazione %s marcata GUASTA per convoglio %s guasto in stazione",
                    stazioneId, treno.id));
        }
    }

    /**
     * Chiude un guasto che il nodo del campo dichiara rientrato (RESOLVED senza il marcatore
     * della Centrale): oggi lo fa la stazione quando i sensori di binario ricominciano a
     * mandare i keepalive.
     *
     * <p>Con la squadra ancora sul posto l'allarme resta aperto: l'intervento lo chiude
     * l'operatore (RF01.4.2), non il nodo. Se al nodo non resta nessun altro guasto aperto la
     * stazione torna ONLINE, e lo si dice al campo.</p>
     */
    private Esito suGuastoRientrato(AlertMsg msg) {
        // 1. guardie
        String tipo = AutomaGuasto.tipoPerFrontend(msg.sorgenteTipo(), msg.tipoGuastoDichiarato());
        Guasto inCache = guasti.apertoPer(msg.sorgenteId(), tipo);
        if (inCache == null) {
            LOG.fine(String.format("RESOLVED dal campo per %s (%s): nessun guasto aperto da chiudere", msg.sorgenteId(), tipo));
            return Esito.ignorato();
        }
        Stazione stazione = statoRete.getStazione(msg.sorgenteId());
        if (stazione != null && stazioni.inManutenzione(stazione)) {
            LOG.info(String.format("🛠️ %s ha segnalato il rientro del guasto, ma la squadra è ancora sul posto: allarme lasciato aperto",
                    msg.sorgenteId()));
            return Esito.ignorato();
        }
        Guasto suDb = repository.trovaGuasto(inCache.id);
        Guasto daChiudere = suDb != null ? suDb : inCache;
        Stazione tornaOnline = stazioneCheTornaOnline(msg.sorgenteId(), false, Set.of(inCache.id));
        String statoPrecedente = tornaOnline != null ? tornaOnline.stato : null;
        Stazione tornaOnlineSuDb = tornaOnline != null ? repository.trovaStazione(tornaOnline.id) : null;
        Instant adesso = Instant.now();

        // 2. assegnamenti
        db.inTransazione(() -> {
            repository.chiudiGuasto(daChiudere, adesso);
            if (tornaOnlineSuDb != null) {
                repository.salvaStoricoStatoStazione(tornaOnlineSuDb, StatiCanonici.STAZIONE_ONLINE, statoPrecedente);
            }
        });
        guasti.chiudi(inCache, adesso);
        if (tornaOnline != null) {
            stazioni.tornaOnline(tornaOnline, adesso);
        }

        // 3. azioni
        LOG.info(String.format("✅ Guasto %s chiuso: %s dichiara che la condizione è rientrata", inCache.id, msg.sorgenteId()));
        notificatore.statoStazione(tornaOnline);
        annunciaStatoAlCampo(tornaOnline);
        notificatore.allarme(inCache);
        return Esito.fatto();
    }

    // ===============================================================
    // COMANDI DELL'OPERATORE
    // ===============================================================

    /**
     * Chiude un allarme perché un operatore di centrale dichiara il guasto risolto.
     *
     * <p>Non è una semplice scrittura: la sorgente torna in servizio (una stazione ridiventa
     * ONLINE se non le resta nessun altro guasto aperto, un convoglio smette di essere rotto),
     * si chiudono i guasti che quel convoglio aveva provocato nelle stazioni (RF02.1.2.2.1) e il
     * campo viene avvisato con un RESOLVED, che è ciò che libera i treni trattenuti. Per una
     * stazione in MANUTENZIONE questo è l'unico modo di chiudere l'intervento (RF01.4.2).</p>
     *
     * <p>Un guasto già risolto si può richiudere: si riscrive l'istante di chiusura e si
     * ripete l'annuncio, come faceva la Centrale prima degli automi.</p>
     */
    private Esito suRisolviGuasto(String guastoId) {
        // 1. guardie
        Guasto guasto = repository.trovaGuasto(guastoId);
        if (guasto == null) {
            return Esito.nonTrovato();
        }
        Set<String> chiusi = new HashSet<>();
        chiusi.add(guasto.id);
        List<Stazione> tornanoOnline = new ArrayList<>();
        Treno trenoInCache = null;
        Treno trenoSuDb = null;
        List<Guasto> derivati = new ArrayList<>();
        if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            Stazione stazione = stazioneCheTornaOnline(guasto.sorgenteId, false, chiusi);
            if (stazione != null) {
                tornanoOnline.add(stazione);
            }
        } else if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            trenoInCache = statoRete.getTreno(guasto.sorgenteId);
            trenoSuDb = repository.trovaTreno(guasto.sorgenteId);
            // I guasti che il convoglio ha lasciato nelle stazioni: nel messaggio c'e' il suo id.
            // Ognuno che si chiude puo' liberare la sua stazione, ma solo se e' ancora GUASTA per
            // colpa sua (non se nel frattempo e' caduta o ci e' andata la squadra) e solo una
            // volta: dopo il primo e' gia' ONLINE.
            Set<String> giaRiaperte = new HashSet<>();
            for (Guasto derivato : repository.guastiDerivatiDaConvoglio()) {
                if (derivato.messaggio == null || !derivato.messaggio.contains(guasto.sorgenteId)) {
                    continue;
                }
                derivati.add(derivato);
                chiusi.add(derivato.id);
                if (giaRiaperte.contains(derivato.sorgenteId)) {
                    continue;
                }
                Stazione stazione = stazioneCheTornaOnline(derivato.sorgenteId, true, chiusi);
                if (stazione != null) {
                    tornanoOnline.add(stazione);
                    giaRiaperte.add(stazione.id);
                }
            }
        }
        boolean rientraInCache = trenoInCache != null && treni.puoTornareInServizio(trenoInCache);
        boolean rientraSuDb = trenoSuDb != null && "rotto".equalsIgnoreCase(trenoSuDb.stato);
        List<String> statiPrecedenti = new ArrayList<>();
        List<Stazione> tornanoOnlineSuDb = new ArrayList<>();
        for (Stazione stazione : tornanoOnline) {
            statiPrecedenti.add(stazione.stato);
            tornanoOnlineSuDb.add(repository.trovaStazione(stazione.id));
        }
        // Al campo e alla web app si annuncia l'ultima stazione tornata ONLINE
        Stazione annunciata = tornanoOnline.isEmpty() ? null : tornanoOnline.get(tornanoOnline.size() - 1);
        Treno trenoDaRimettere = trenoSuDb;
        Instant adesso = Instant.now();

        // 2. assegnamenti
        db.inTransazione(() -> {
            repository.chiudiGuasto(guasto, adesso);
            if (rientraSuDb) {
                scriviStatoTreno(trenoDaRimettere, trenoDaRimettere.id, "fermo");
            }
            for (Guasto derivato : derivati) {
                repository.chiudiGuasto(derivato, adesso);
            }
            for (int i = 0; i < tornanoOnline.size(); i++) {
                if (tornanoOnlineSuDb.get(i) != null) {
                    repository.salvaStoricoStatoStazione(tornanoOnlineSuDb.get(i),
                            StatiCanonici.STAZIONE_ONLINE, statiPrecedenti.get(i));
                }
            }
        });
        guasti.chiudi(guasto, adesso);
        if (rientraInCache) {
            treni.rimettiInServizio(trenoInCache);
        }
        for (Guasto derivato : derivati) {
            guasti.chiudi(derivato, adesso);
        }
        for (Stazione stazione : tornanoOnline) {
            stazioni.tornaOnline(stazione, adesso);
        }

        // 3. azioni
        for (Guasto derivato : derivati) {
            pubblicatore.pubblicaRisoluzione(derivato);
        }
        notificatore.statoStazione(annunciata);
        annunciaStatoAlCampo(annunciata);
        pubblicatore.pubblicaRisoluzione(guasto);
        notificatore.allarme(guasto);
        return Esito.fatto(guasto);
    }

    /**
     * Sopprime la corsa di un treno su richiesta dell'operatore: il convoglio riceve il comando
     * di frenata definitiva e la sua corsa esce dall'orario. Si può fare solo con il treno
     * fermo in una stazione (guardia dell'automa).
     */
    private Esito suSoppressione(String trenoId) {
        // 1. guardie
        Treno treno = statoRete.getTreno(trenoId);
        if (treno == null) {
            return Esito.nonTrovato();
        }
        if (!treni.puoEssereSoppresso(treno)) {
            return Esito.rifiutato("Il treno può essere soppresso solo se fermo in una stazione");
        }
        Treno suDb = repository.trovaTreno(trenoId);

        // 2. assegnamenti. Sul database va il valore canonico: la colonna ha un CHECK e
        //    "SOPPRESSO" non e' fra i suoi valori ammessi, quello e' il vocabolario MQTT.
        db.inTransazione(() -> scriviStatoTreno(suDb, trenoId, StatiCanonici.trenoDalCampo("SOPPRESSO")));
        treni.sopprimi(treno);

        // 3. azioni: il comando di frenata definitiva al gateway di bordo
        pubblicatore.pubblicaStop(trenoId, "Soppresso da operatore");
        return Esito.fatto(treno);
    }

    /**
     * Manda la squadra di manutenzione su una stazione guasta (RF01.4.1).
     *
     * <p>Apre l'intervento e si ferma lì: la stazione passa a MANUTENZIONE e ci resta, i guasti
     * aperti restano aperti e il nodo continua a trattenere i treni in ingresso. L'intervento
     * si chiude con la risoluzione dell'allarme, che è l'unico punto in cui la stazione torna
     * ONLINE (RF01.4.2). La squadra si manda solo dove c'è davvero un guasto aperto: il filtro
     * sul tipo di sorgente serve perché l'identificativo da solo non è univoco.</p>
     */
    private Esito suInvioSquadra(String stazioneId) {
        // 1. guardie
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            return Esito.nonTrovato();
        }
        if (repository.guastiApertiDi(stazioneId, "STAZIONE").isEmpty()) {
            return Esito.conflitto();
        }
        // Lo stato di partenza serve alla riga di storico: e' da li' che la stazione viene
        // (di solito GUASTA o OFFLINE)
        String statoPrecedente = stazione.stato;
        Stazione suDb = repository.trovaStazione(stazioneId);

        // 2. assegnamenti. Il passaggio in manutenzione si storicizza adesso: da qui in poi lo
        //    stato dura fino alla chiusura del guasto, quindi la riga racconta un intervallo.
        if (suDb != null) {
            db.inTransazione(() -> repository.salvaStoricoStatoStazione(suDb,
                    StatiCanonici.STAZIONE_MANUTENZIONE, statoPrecedente));
        }
        stazioni.inviaSquadra(stazione);

        // 3. azioni
        notificatore.statoStazione(stazione);
        // Notifica informativa: il nodo la registra e basta, il suo stato locale resta
        // GUASTA ed e' quello che continua a trattenere i treni che entrano.
        pubblicatore.pubblicaManutenzioneInviata(stazioneId);
        // Ai convogli invece serve lo stato vero: con la squadra sui binari la stazione non e'
        // attraversabile, e chi ci sta arrivando deve fermarsi come davanti a un guasto.
        pubblicatore.pubblicaStatoStazione(stazioneId, StatiCanonici.STAZIONE_MANUTENZIONE);
        return Esito.fatto(stazione);
    }

    /**
     * Lo stato di una stazione scelto a mano dall'amministratore (PUT /api/stazioni/{id}).
     *
     * <p>Deve arrivare subito alle altre schermate aperte e ai treni: se l'amministratore mette
     * la stazione in MANUTENZIONE (o la dichiara GUASTA), i convogli che stanno per arrivarci
     * devono fermarsi come per un guasto segnalato dal campo, e quelli già fermi lì devono
     * restarci. Lo stato di una stazione non è una colonna: non c'è niente da scrivere sul
     * database.</p>
     */
    private Esito suStatoStazioneDallOperatore(String stazioneId, String stato) {
        // 1. guardie
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            return Esito.nonTrovato();
        }

        // 2. assegnamenti
        stazioni.imponiDallOperatore(stazione, stato);

        // 3. azioni
        notificatore.statoStazione(stazione);
        pubblicatore.pubblicaStatoStazione(stazione.id, stazione.stato);
        return Esito.fatto(stazione);
    }

    /**
     * Lo stato di un treno scelto a mano dall'amministratore (PUT /api/treni/{id}): si scrive
     * sulla riga e sulla cache. Uno stato fuori dal vocabolario del database si rifiuta qui con
     * un 400, invece di arrivare al CHECK della colonna e diventare un errore del server.
     */
    private Esito suStatoTrenoDallOperatore(String trenoId, String stato) {
        // 1. guardie
        Treno treno = statoRete.getTreno(trenoId);
        if (treno == null) {
            return Esito.nonTrovato();
        }
        if (!treni.statoAmmesso(stato)) {
            return Esito.rifiutato("Stato del treno non ammesso: '" + stato + "' (valori ammessi: "
                    + String.join(", ", StatiCanonici.TRENO) + ")");
        }

        // 2. assegnamenti
        db.inTransazione(() -> repository.aggiornaStatoTreno(trenoId, stato));
        treni.imponiDallOperatore(treno, stato);

        // 3. azioni: nessuna, come prima (la web app che l'ha chiesto riceve la risposta)
        return Esito.fatto(treno);
    }

    // ===============================================================
    // WATCHDOG: le anomalie che i nodi non possono segnalare da soli
    // ===============================================================

    /**
     * Le stazioni che non battono più: diventano OFFLINE e ricevono un guasto automatico (una
     * sola volta per episodio). È il fail-stop: il guasto si pubblica anche sul campo, così i
     * treni sanno della stazione caduta e non ci entrano.
     */
    private Esito suControlloHeartbeat() {
        // 1. guardie
        Instant limite = Instant.now().minus(Duration.ofSeconds(heartbeatTimeoutSecondi));
        List<Stazione> cadute = new ArrayList<>();
        List<Guasto> nuoviGuasti = new ArrayList<>(); // allineata a cadute: null se il guasto c'e' gia'
        for (Stazione stazione : statoRete.getTutteStazioni()) {
            if (!stazioni.silenziosa(stazione, limite)) {
                continue;
            }
            cadute.add(stazione);
            nuoviGuasti.add(guasti.apertoPer(stazione.id, "sensore_offline") == null
                    ? guasti.nuovoDedotto("sensore_offline", "WARNING", "STAZIONE", stazione.id,
                            Guasto.MSG_HEARTBEAT_PERSO + " la stazione " + stazione.id
                                    + " non invia heartbeat da oltre " + heartbeatTimeoutSecondi + " secondi")
                    : null);
        }

        // 2. assegnamenti
        for (int i = 0; i < cadute.size(); i++) {
            stazioni.vaiOffline(cadute.get(i));
            Guasto guasto = nuoviGuasti.get(i);
            if (guasto != null && scriviGuastoDedotto(guasto, new EventoAudit(StatiCanonici.STAZIONE_OFFLINE,
                    "HEARTBEAT_LOST", "Heartbeat mancante, guasto automatico " + guasto.id, null))) {
                guasti.apri(guasto);
            } else {
                nuoviGuasti.set(i, null);
            }
        }

        // 3. azioni
        for (int i = 0; i < cadute.size(); i++) {
            Stazione stazione = cadute.get(i);
            LOG.warning(String.format("⚠️ [WATCHDOG] Stazione %s senza heartbeat da oltre %d secondi → OFFLINE",
                    stazione.id, heartbeatTimeoutSecondi));
            notificatore.statoStazione(stazione);
            Guasto guasto = nuoviGuasti.get(i);
            if (guasto != null) {
                notificatore.allarme(guasto);
                pubblicatore.pubblicaGuasto(guasto);
            }
        }
        return Esito.fatto();
    }

    /**
     * I convogli "attivi" ma immobili fuori dalle stazioni: se la condizione dura almeno
     * {@code T_fer} ricevono un guasto automatico. Si saltano quelli soppressi e quelli che non
     * hanno mai trasmesso da quando la Centrale è accesa (il loro processo può non essere
     * nemmeno acceso).
     */
    private Esito suControlloTreniFermi() {
        // 1. guardie
        Instant adesso = Instant.now();
        Instant limiteTelemetria = adesso.minus(Duration.ofSeconds(trenoFermoTimeoutSecondi));
        Set<String> treniEsistenti = new HashSet<>();
        List<String> daAzzerare = new ArrayList<>();
        List<String> daSegnare = new ArrayList<>();
        List<Guasto> daAprire = new ArrayList<>();
        List<Long> secondiFermi = new ArrayList<>(); // allineata a daAprire, per il log
        for (Treno treno : statoRete.getTuttiTreni()) {
            treniEsistenti.add(treno.id);
            if (treni.soppresso(treno) || treno.ultimoAggiornamento == null) {
                daAzzerare.add(treno.id);
                continue;
            }
            boolean immobile = treno.velocita == 0 || treno.ultimoAggiornamento.isBefore(limiteTelemetria);
            if (!immobile || treni.inStazione(treno)) {
                daAzzerare.add(treno.id); // si muove, o e' fermo dove e' lecito esserlo
                continue;
            }
            Instant fermoDa = treni.fermoDa(treno.id);
            if (fermoDa == null) {
                daSegnare.add(treno.id); // prima volta che lo si vede fermo: parte l'orologio
                continue;
            }
            long secondiFermo = Duration.between(fermoDa, adesso).getSeconds();
            if (secondiFermo < trenoFermoTimeoutSecondi) {
                continue; // T_fer non ancora scaduto
            }
            if (guasti.apertoPer(treno.id, "treno_fermo") == null) {
                daAprire.add(guasti.nuovoDedotto("treno_fermo", "warning", "TRENO", treno.id,
                        Guasto.MSG_TRENO_FERMO + " il convoglio " + treno.id
                                + " è fermo fuori da una stazione da oltre " + trenoFermoTimeoutSecondi + " secondi"));
                secondiFermi.add(secondiFermo);
            }
        }

        // 2. assegnamenti
        for (String trenoId : daAzzerare) {
            treni.azzeraFermo(trenoId);
        }
        for (String trenoId : daSegnare) {
            treni.segnaFermo(trenoId, adesso);
        }
        List<Guasto> aperti = new ArrayList<>();
        List<Long> secondiAperti = new ArrayList<>();
        for (int i = 0; i < daAprire.size(); i++) {
            if (scriviGuastoDedotto(daAprire.get(i), null)) {
                guasti.apri(daAprire.get(i));
                aperti.add(daAprire.get(i));
                secondiAperti.add(secondiFermi.get(i));
            }
        }
        treni.tieniFermiSoloDi(treniEsistenti);

        // 3. azioni
        for (int i = 0; i < aperti.size(); i++) {
            Guasto guasto = aperti.get(i);
            LOG.warning(String.format("⚠️ [WATCHDOG] Treno %s fermo fuori stazione da %d secondi → guasto automatico",
                    guasto.sorgenteId, secondiAperti.get(i)));
            notificatore.allarme(guasto);
            pubblicatore.pubblicaGuasto(guasto);
        }
        return Esito.fatto();
    }

    /**
     * Manda la fotografia completa dello stato corrente della rete (RF02.6.3): la rete di
     * sicurezza che rimette a posto il browser quando un evento in tempo reale si perde. Non
     * muove nessun automa, ma passa di qui lo stesso: così la fotografia non si fa mentre un
     * altro evento è a metà.
     */
    private Esito suFotografia() {
        try {
            notificatore.snapshot(statoRete.kpiDashboard(), statoRete.getTuttiTreni(), statoRete.getTutteStazioni());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "❌ Errore durante il broadcast dello snapshot", e);
        }
        return Esito.fatto();
    }

    // ===============================================================
    // SUPPORTO
    // ===============================================================

    /**
     * Allinea sul database lo stato del treno e lo storicizza SOLO quando cambia davvero:
     * altrimenti sarebbe una riga di storico a ogni frame di telemetria per ogni convoglio.
     * Gira dentro la transazione aperta dal chiamante.
     *
     * <p>Lo stato di partenza va letto PRIMA di sovrascriverlo: è quello che finisce nella
     * colonna stato_precedente e rende la riga un cambiamento ("da attivo a rotto").</p>
     *
     * @param suDb La riga del treno letta nella fase 1 (null se il treno non è in tabella).
     */
    private void scriviStatoTreno(Treno suDb, String trenoId, String stato) {
        if (suDb == null) {
            LOG.warning(String.format("🚫 Il treno '%s' non è nella tabella Treni: nessuna scrittura", trenoId));
            return;
        }
        String statoPrecedente = suDb.stato;
        if (stato.equals(statoPrecedente)) {
            return;
        }
        repository.aggiornaStatoTreno(trenoId, stato);
        suDb.stato = stato;
        repository.salvaStoricoStatoTreno(suDb, statoPrecedente);
    }

    /**
     * Scrive un guasto dedotto dalla Centrale con la sua riga di storico e, se c'è, l'audit.
     * Se il database non lo accetta ci si ferma qui: niente cache e niente notifiche per un
     * guasto che non esiste da nessuna parte.
     *
     * @return true se il guasto è stato scritto.
     */
    private boolean scriviGuastoDedotto(Guasto guasto, EventoAudit audit) {
        try {
            db.inTransazione(() -> {
                repository.salvaGuasto(guasto);
                repository.salvaStoricoGuasto(guasto);
                if (audit != null) {
                    scriviAudit(guasto, audit);
                }
            });
            return true;
        } catch (RuntimeException e) {
            LOG.severe(String.format("❌ Guasto %s non scritto a database (%s): nessuna notifica inviata",
                    guasto.id, e.getMessage()));
            return false;
        }
    }

    /**
     * Scrive la riga di audit di un guasto di stazione e, se c'è lo stato di prima, il cambio
     * di stato nello storico. Gira dentro la transazione aperta dal chiamante.
     */
    private void scriviAudit(Guasto guasto, EventoAudit audit) {
        Stazione inCache = statoRete.getStazione(guasto.sorgenteId);
        // Il nome si legge dalla cache, che e' la copia coerente con l'istante dell'evento:
        // la tabella non ha chiavi esterne verso l'anagrafica (RF02.7), quindi e' l'unico
        // posto in cui quel nome resta scritto.
        repository.salvaEventoStazione(
                guasto.sorgenteId,
                inCache != null && inCache.nome != null ? inCache.nome : guasto.sorgenteId,
                audit.stato(),
                audit.tipoEvento(),
                audit.descrizione());

        if (audit.statoPrecedente() != null) {
            Stazione suDb = repository.trovaStazione(guasto.sorgenteId);
            if (suDb != null) {
                repository.salvaStoricoStatoStazione(suDb, audit.stato(), audit.statoPrecedente());
            }
        }
    }

    /**
     * I guasti ancora aperti di una sorgente che la Centrale aveva dedotto da sola con quel
     * motivo (heartbeat perso, treno fermo). Sono quelli che si possono chiudere quando la
     * condizione sparisce; un guasto dichiarato dal campo o di un altro tipo resta aperto.
     */
    private List<Guasto> dedottiAperti(String sorgenteId, String sorgenteTipo, String prefissoMessaggio) {
        List<Guasto> dedotti = new ArrayList<>();
        for (Guasto guasto : repository.guastiApertiDi(sorgenteId, sorgenteTipo)) {
            if (guasto.dedottoConPrefisso(prefissoMessaggio)) {
                dedotti.add(guasto);
            }
        }
        return dedotti;
    }

    /**
     * Dice se a una sorgente resterà almeno un guasto aperto dopo aver chiuso quelli indicati.
     * È la domanda "questo nodo è pulito?", da farsi prima di dire al campo che una stazione è
     * di nuovo agibile.
     */
    private boolean restanoGuastiAperti(String sorgenteId, String sorgenteTipo, List<Guasto> chiusi) {
        Set<String> idChiusi = new HashSet<>();
        for (Guasto guasto : chiusi) {
            idChiusi.add(guasto.id);
        }
        for (Guasto guasto : statoRete.getGuastiAttivi()) {
            if (!guasto.risolto && !idChiusi.contains(guasto.id)
                    && sorgenteId.equals(guasto.sorgenteId)
                    && sorgenteTipo.equalsIgnoreCase(guasto.sorgenteTipo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * La stazione che torna ONLINE perché, con i guasti che questo evento sta chiudendo, non
     * le resta nessun guasto aperto. È l'unica strada per uscire da GUASTA o da MANUTENZIONE.
     *
     * @param soloSeGuasta true per i guasti lasciati da un convoglio: la stazione torna ONLINE
     *                     solo se è ancora GUASTA (non se nel frattempo è caduta o ci è andata
     *                     la squadra, che hanno i loro guasti e le loro regole).
     * @param chiusi       I guasti che questo evento chiude: sul database sono ancora aperti.
     * @return La stazione in cache da riportare ONLINE, o null.
     */
    private Stazione stazioneCheTornaOnline(String stazioneId, boolean soloSeGuasta, Set<String> chiusi) {
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            return null;
        }
        if (soloSeGuasta && !StatiCanonici.STAZIONE_GUASTA.equalsIgnoreCase(stazione.stato)) {
            return null;
        }
        for (Guasto aperto : repository.guastiApertiDi(stazioneId, "STAZIONE")) {
            if (!chiusi.contains(aperto.id)) {
                return null;
            }
        }
        return stazione;
    }

    /** Annuncia al campo e alla web app i guasti dedotti che si sono chiusi da soli. */
    private void annunciaChiusure(List<Guasto> chiusi, String motivo) {
        for (Guasto guasto : chiusi) {
            LOG.info(String.format("✅ %s: chiuso il guasto %s", motivo, guasto.id));
            pubblicatore.pubblicaRisoluzione(guasto);
            notificatore.allarme(guasto);
        }
    }

    /**
     * Dice al campo che una stazione è di nuovo agibile. Serve perché lo stato lo decide la
     * Centrale ma a subirlo sono i treni: è il messaggio che li fa ripartire.
     */
    private void annunciaStatoAlCampo(Stazione stazione) {
        if (stazione == null) {
            return; // non e' tornata operativa: non c'e' niente di nuovo da dire
        }
        pubblicatore.pubblicaStatoStazione(stazione.id, stazione.stato);
    }
}
