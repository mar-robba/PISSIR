package it.uni.reti2.elaboration;

import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoGuasto;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.gateway.NotificatoreRealtime;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.ingestion.payload.AlertMsg;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Il ciclo di vita di un guasto, dall'apertura alla chiusura, in un posto solo.
 *
 * <p>Prima questa materia era divisa in tre: l'ingestione apriva i guasti segnalati dal
 * campo e chiudeva quelli dedotti, il watchdog apriva quelli dedotti, le API REST
 * chiudevano quelli risolti dall'operatore. Ognuno dei tre si era riscritto i suoi pezzi,
 * e i pezzi non erano identici: la chiusura di un guasto compariva in quattro copie e la
 * frase "se non restano guasti aperti la stazione torna operativa" in tre.</p>
 *
 * <h3>Le due origini di un guasto</h3>
 * <ul>
 *   <li><b>Segnalato dal campo</b>: lo dichiara un nodo, e a chiuderlo e' un operatore
 *       (oppure il nodo stesso, se la condizione rientra da sola).</li>
 *   <li><b>Dedotto dalla Centrale</b>: nessuno lo ha dichiarato, e' il watchdog ad
 *       accorgersene da un silenzio. Questi guasti la Centrale li richiude da sola quando
 *       la condizione sparisce, e per riconoscerli usa il prefisso del messaggio
 *       ({@link Guasto#MSG_HEARTBEAT_PERSO}, {@link Guasto#MSG_TRENO_FERMO}).</li>
 * </ul>
 * <p>La distinzione non e' burocratica: chiudere d'ufficio un'avaria dichiarata da un
 * treno significherebbe rimettere in servizio un convoglio che nessuno ha riparato.</p>
 *
 * <h3>Transazioni</h3>
 * <p>I metodi che portano un nome di comando ({@code registraSegnalazione}, {@code apri},
 * {@code risolviSuComandoOperatore}...) aprono la loro transazione e notificano solo a
 * commit avvenuto: se la scrittura fallisce non deve partire nessun annuncio, altrimenti
 * i treni si fermano per un guasto che a database non esiste. I metodi marcati "gira
 * dentro la transazione del chiamante" sono invece i mattoni, e non notificano niente.</p>
 */
@ApplicationScoped
public class GuastiService {

    private static final Logger LOG = Logger.getLogger(GuastiService.class);

    /** Il guasto che la Centrale apre su una stazione resa impercorribile da un convoglio. */
    public static final String TIPO_CONVOGLIO_IN_STAZIONE = "convoglio_guasto_in_stazione";

    /** Tipi di guasto che il frontend sa disegnare. */
    private static final List<String> TIPI_NOTI =
            List.of("sensore_offline", "stazione_guasta", "treno_fermo", "binario_guasto");

    @Inject
    TrafficLogicEngine statoRete;

    @Inject
    RailwayRepository repository;

    @Inject
    MonitoraggioTreni treni;

    @Inject
    NotificatoreRealtime notificatore;

    @Inject
    PubblicatoreAlert pubblicatore;

    /**
     * Riga di audit da scrivere su eventi_stazioni insieme al guasto: registra il fatto
     * che ha provocato il cambio di stato, mentre Storico_Stato_Stazioni registra il
     * cambio in se'.
     *
     * @param statoPrecedente Se valorizzato, fa scrivere anche la riga di
     *                        Storico_Stato_Stazioni per il passaggio a {@code stato}.
     */
    public record EventoAudit(String stato, String tipoEvento, String descrizione, String statoPrecedente) {
    }

    // ──────────────────────────────────────────────────────────────
    // CLASSIFICAZIONE
    // ──────────────────────────────────────────────────────────────

    /**
     * Determina il tipo di guasto nel vocabolario del frontend.
     *
     * <p>Un tempo veniva classificato come {@code sensore_offline} qualunque allarme che
     * contenesse la parola "sensore" nel messaggio: siccome tutti i guasti di terra
     * parlano di sensori, il tipo {@code stazione_guasta} non veniva quasi mai prodotto,
     * e con lui spariva il pulsante "Invia Operatori" (RF01.4.1). Bastava poi la parola
     * "sensore" nella descrizione di un'avaria di bordo per far passare per guasto di
     * terra il guasto di un treno.</p>
     *
     * <p>Adesso il tipo lo dichiara chi segnala, con il campo {@code tipoGuasto} del
     * payload; se manca decide il tipo di sorgente, che e' un dato strutturato e non una
     * parola in una frase.</p>
     */
    public static String tipoPerFrontend(String sorgenteTipo, String tipoDichiarato) {
        if (tipoDichiarato != null && !tipoDichiarato.isBlank()) {
            String tipo = tipoDichiarato.trim().toLowerCase();
            if (TIPI_NOTI.contains(tipo)) {
                return tipo;
            }
        }
        if ("TRENO".equalsIgnoreCase(sorgenteTipo)) {
            return "treno_fermo";
        }
        if ("STAZIONE".equalsIgnoreCase(sorgenteTipo)) {
            return "stazione_guasta";
        }
        return "sensore_offline";
    }

    /**
     * Genera l'identificativo di un guasto nuovo.
     *
     * <p>Il suffisso casuale e' indispensabile: i job del watchdog girano tutti e due ogni
     * dieci secondi e ciclano su tutte le sorgenti, quindi due guasti generati nello stesso
     * millisecondo avrebbero la stessa chiave primaria — violazione al commit e rollback
     * dell'intero giro di controllo.</p>
     */
    private static String nuovoId() {
        return "alert-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Prepara (senza scriverlo) un guasto dedotto dalla Centrale. */
    public Guasto nuovoDedotto(String tipo, String severita, String sorgenteTipo,
                               String sorgenteId, String messaggio) {
        return componi(tipo, severita, sorgenteTipo, sorgenteId, messaggio,
                Guasto.ORIGINE_DEDOTTO_CENTRALE, Instant.now());
    }

    private Guasto componi(String tipo, String severita, String sorgenteTipo, String sorgenteId,
                           String messaggio, String origine, Instant quando) {
        Guasto guasto = new Guasto();
        guasto.id = nuovoId();
        guasto.tipo = tipo;
        guasto.severita = severita;
        guasto.sorgenteTipo = sorgenteTipo;
        guasto.sorgenteId = sorgenteId;
        guasto.origine = origine;
        guasto.messaggio = messaggio;
        guasto.timestamp = quando;
        guasto.risolto = false;
        return guasto;
    }

    // ──────────────────────────────────────────────────────────────
    // APERTURA
    // ──────────────────────────────────────────────────────────────

    /**
     * Scrive un guasto e lo annuncia su tutte e due le strade: la WebSocket per la
     * dashboard e il topic railway/alerts per il campo. Senza la seconda i treni non
     * saprebbero mai di una stazione caduta e ci andrebbero dentro lo stesso.
     *
     * <p><b>Prima si scrive e si chiude la transazione, poi si annuncia.</b> Annunciare
     * prima del commit significava che un vincolo violato lasciava la dashboard con un
     * allarme e i treni fermi per un guasto che a database non esisteva.</p>
     *
     * @param audit Riga di audit facoltativa sulla stazione coinvolta, o null.
     * @return Il guasto scritto, oppure null se la scrittura non e' andata a buon fine.
     */
    public Guasto apri(Guasto guasto, EventoAudit audit) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                repository.salvaGuasto(guasto);
                repository.salvaStoricoGuasto(guasto);
                if (audit != null) {
                    scriviAudit(guasto, audit);
                }
            });
        } catch (Exception e) {
            LOG.errorf("❌ Guasto %s non scritto a database (%s): nessuna notifica inviata",
                    guasto.id, e.getMessage());
            return null;
        }

        statoRete.aggiungiGuasto(guasto);
        notificatore.allarme(guasto);
        pubblicatore.pubblicaGuasto(guasto);
        return guasto;
    }

    /** Gira dentro la transazione aperta da {@link #apri}. */
    private void scriviAudit(Guasto guasto, EventoAudit audit) {
        Stazione cache = statoRete.getStazione(guasto.sorgenteId);
        // Il nome si legge dalla cache, che e' la copia coerente con l'istante
        // dell'evento: la tabella non ha chiavi esterne verso l'anagrafica (RF02.7),
        // quindi e' l'unico posto in cui quel nome resta scritto.
        repository.salvaEventoStazione(
                guasto.sorgenteId,
                cache != null && cache.nome != null ? cache.nome : guasto.sorgenteId,
                audit.stato(),
                audit.tipoEvento(),
                audit.descrizione());

        if (audit.statoPrecedente() != null) {
            Stazione dbStazione = repository.trovaStazione(guasto.sorgenteId);
            if (dbStazione != null) {
                repository.salvaStoricoStatoStazione(dbStazione, audit.stato(), audit.statoPrecedente());
            }
        }
    }

    /**
     * Registra un guasto segnalato da un nodo sul campo.
     *
     * <p><b>Deduplica.</b> Una stazione guasta manda un alert per OGNI treno che entra: se
     * un guasto dello stesso tipo per la stessa sorgente e' gia' aperto si aggiorna il
     * messaggio invece di riempire la tabella di righe identiche. Con N righe aperte il
     * pulsante "risolvi" ne chiuderebbe una sola, e le altre resterebbero rosse per
     * sempre.</p>
     *
     * @return Il guasto aperto (nuovo o gia' esistente).
     */
    public Guasto registraSegnalazione(AlertMsg msg) {
        String tipo = tipoPerFrontend(msg.sorgenteTipo(), msg.tipoGuastoDichiarato());

        Guasto giaAperto = statoRete.getGuastoApertoPerSorgente(msg.sorgenteId(), tipo);
        if (giaAperto != null) {
            QuarkusTransaction.requiringNew().run(() -> aggiornaMessaggio(giaAperto.id, msg.messaggio()));
            giaAperto.messaggio = msg.messaggio();
            LOG.infof("♻️ Guasto già aperto per %s (%s): aggiornato il messaggio invece di crearne un altro",
                    msg.sorgenteId(), tipo);
            // Anche se il guasto non e' nuovo la sorgente va rimarcata: nel frattempo la
            // telemetria (o un heartbeat) puo' aver riscritto lo stato in cache.
            marcaSorgenteGuasta(msg);
            notificatore.allarme(giaAperto);
            return giaAperto;
        }

        Guasto guasto = componi(tipo, msg.severita(), msg.sorgenteTipo(), msg.sorgenteId(),
                msg.messaggio(), Guasto.ORIGINE_SEGNALATO_CAMPO, msg.quando());
        QuarkusTransaction.requiringNew().run(() -> {
            repository.salvaGuasto(guasto);
            repository.salvaStoricoGuasto(guasto);
        });
        statoRete.aggiungiGuasto(guasto);
        marcaSorgenteGuasta(msg);
        notificatore.allarme(guasto);
        return guasto;
    }

    /** Aggiorna il testo di un guasto gia' aperto. Gira dentro la transazione del chiamante. */
    private void aggiornaMessaggio(String guastoId, String messaggio) {
        Guasto dbGuasto = repository.trovaGuasto(guastoId);
        if (dbGuasto != null) {
            dbGuasto.messaggio = messaggio;
        }
    }

    /**
     * Riporta il guasto appena ricevuto sulla cache della sorgente che lo ha segnalato.
     *
     * <p>Senza questo passaggio l'allarme finirebbe solo nella tabella dei guasti: il nodo
     * continuerebbe a comparire operativo nelle API REST (che leggono la cache) e la mappa
     * lo mostrerebbe normale fino al frame successivo dal campo.</p>
     *
     * <p>Si marca solo sui CRITICAL: i WARNING restano allarmi informativi e non devono
     * contraddire gli heartbeat ONLINE della stazione ne' la telemetria del treno.</p>
     */
    private void marcaSorgenteGuasta(AlertMsg msg) {
        if (!msg.critico()) {
            return;
        }
        if (msg.sorgenteStazione()) {
            Stazione stazione = statoRete.getStazione(msg.sorgenteId());
            if (stazione != null) {
                stazione.stato = StatiCanonici.STAZIONE_GUASTA;
                statoRete.aggiornaStazione(stazione);
                notificatore.statoStazione(stazione);
            }
            return;
        }
        if (msg.sorgenteTreno()) {
            Treno treno = treni.marcaRotto(msg.sorgenteId());
            if (treno != null) {
                // RF02.1.2.2.1: se il convoglio e' fermo in una stazione, la stazione
                // diventa non percorribile (treno deragliato o avaria grave).
                marcaStazionePerConvoglioGuasto(treno);
            }
        }
    }

    /**
     * RF02.1.2.2.1 — Un convoglio guasto dentro una stazione blocca i binari: la stazione
     * diventa non percorribile e la Centrale apre un guasto dedicato a suo nome.
     */
    private void marcaStazionePerConvoglioGuasto(Treno treno) {
        String stazioneId = treno.stazioneCorrente;
        if (stazioneId == null || stazioneId.isEmpty()) {
            return; // il convoglio e' in tratta, non in stazione
        }
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            LOG.warnf("🚫 Stazione '%s' non trovata in cache: impossibile marcarla GUASTA per convoglio %s",
                    stazioneId, treno.id);
            return;
        }
        if (StatiCanonici.STAZIONE_GUASTA.equalsIgnoreCase(stazione.stato)) {
            LOG.infof("♻️ Stazione %s già GUASTA: convoglio %s non genera un guasto duplicato",
                    stazioneId, treno.id);
            return;
        }

        String statoPrecedente = stazione.stato;
        stazione.stato = StatiCanonici.STAZIONE_GUASTA;
        statoRete.aggiornaStazione(stazione);
        notificatore.statoStazione(stazione);

        // Deduplica: un solo guasto aperto per episodio.
        if (statoRete.getGuastoApertoPerSorgente(stazioneId, TIPO_CONVOGLIO_IN_STAZIONE) != null) {
            return;
        }

        Guasto guasto = nuovoDedotto(TIPO_CONVOGLIO_IN_STAZIONE, "CRITICAL", "STAZIONE", stazioneId,
                "Convoglio " + treno.id + " guasto in stazione " + stazioneId + ": stazione non percorribile");
        Guasto scritto = apri(guasto, new EventoAudit(
                StatiCanonici.STAZIONE_GUASTA,
                "CONVOGLIO_GUASTO",
                "Convoglio " + treno.id + " guasto: stazione non percorribile",
                statoPrecedente));

        if (scritto != null) {
            LOG.warnf("🚨 Stazione %s marcata GUASTA per convoglio %s guasto in stazione", stazioneId, treno.id);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // CHIUSURA
    // ──────────────────────────────────────────────────────────────

    /**
     * Chiude un guasto su database, storico e cache.
     * Gira dentro la transazione aperta dal chiamante e non notifica niente.
     */
    public void chiudi(Guasto guasto) {
        guasto.risolto = true;
        guasto.timestampRisoluzione = Instant.now();

        StoricoGuasto storico = repository.trovaStoricoGuasto(guasto.id);
        if (storico != null) {
            storico.risolto = true;
            storico.tsChiusura = guasto.timestampRisoluzione;
        } else {
            // Guasto senza storico (per esempio aperto prima che la tabella esistesse):
            // la riga si scrive adesso, gia' chiusa.
            repository.salvaStoricoGuasto(guasto);
        }
        statoRete.risolviGuasto(guasto.id);
    }

    /**
     * Chiude i soli guasti che la Centrale ha dedotto da sola per una sorgente,
     * riconoscendoli dal prefisso del messaggio.
     *
     * <p>Un solo metodo per stazioni e treni: prima erano due, identici parola per parola
     * tranne il prefisso e il tipo di sorgente. I guasti dichiarati dal nodo restano
     * aperti, perche' quelli li chiude un operatore.</p>
     *
     * <p>Gira dentro la transazione aperta dal chiamante.</p>
     *
     * @return I guasti chiusi, da annunciare dopo il commit con {@link #annunciaChiusure}.
     */
    public List<Guasto> chiudiDedotti(String sorgenteId, String sorgenteTipo, String prefissoMessaggio) {
        List<Guasto> chiusi = new ArrayList<>();
        for (Guasto guasto : repository.guastiApertiDi(sorgenteId, sorgenteTipo)) {
            if (!guasto.dedottoConPrefisso(prefissoMessaggio)) {
                continue;
            }
            chiudi(guasto);
            chiusi.add(guasto);
        }
        return chiusi;
    }

    /**
     * Annuncia al campo e alla dashboard i guasti appena chiusi.
     * Va chiamato a commit avvenuto.
     */
    public void annunciaChiusure(List<Guasto> chiusi, String motivo) {
        for (Guasto guasto : chiusi) {
            LOG.infof("✅ %s: chiuso il guasto %s", motivo, guasto.id);
            pubblicatore.pubblicaRisoluzione(guasto);
            notificatore.allarme(guasto);
        }
    }

    /**
     * Chiude il guasto "treno fermo" quando il convoglio torna a muoversi.
     *
     * <p>Il primo controllo e' sulla cache in RAM e costa niente: senza un guasto aperto
     * del tipo giusto non si tocca il database, altrimenti sarebbe una query ogni cinque
     * secondi per ogni convoglio della rete.</p>
     */
    public void chiudiDedottiSeTrenoRiparte(Treno treno) {
        boolean inMovimento = treno.velocita > 0
                || (treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty());
        if (!inMovimento) {
            return;
        }
        Guasto inCache = statoRete.getGuastoApertoPerSorgente(treno.id, "treno_fermo");
        if (inCache == null || !inCache.dedottoConPrefisso(Guasto.MSG_TRENO_FERMO)) {
            return; // nessun guasto dedotto da chiudere (o e' un'avaria dichiarata dal treno)
        }

        List<Guasto> chiusi = QuarkusTransaction.requiringNew()
                .call(() -> chiudiDedotti(treno.id, "TRENO", Guasto.MSG_TRENO_FERMO));
        annunciaChiusure(chiusi, "[WATCHDOG] Treno " + treno.id + " di nuovo in movimento");
    }

    /**
     * Chiude il guasto che una sorgente sul campo dichiara rientrato da sola.
     *
     * <p>E' il gemello della chiusura del fail-stop: la' e' la Centrale ad accorgersi che
     * una stazione muta e' tornata a battere, qui e' la stazione ad accorgersi che i suoi
     * sensori sono tornati a battere. In tutti e due i casi il guasto e' <b>transitorio</b>,
     * quindi non ha senso pretendere che sia un operatore a chiuderlo: la condizione che
     * l'aveva generato non c'e' piu'.</p>
     *
     * <p>Il guasto si cerca per sorgente + tipo, con la stessa chiave con cui viene
     * deduplicato all'arrivo: cosi' il RESOLVED di un sensore muto non si porta dietro un
     * guasto di altro genere aperto sulla stessa stazione. E se sulla stazione c'e' una
     * squadra di manutenzione non si chiude niente: lo stato MANUTENZIONE lo decide la
     * Centrale (RF01.4.1) e finche' il tecnico non ha finito il campo non ha titolo per
     * rimettere il nodo in servizio.</p>
     */
    public void chiudiSuDichiarazioneDalCampo(AlertMsg msg) {
        String tipo = tipoPerFrontend(msg.sorgenteTipo(), msg.tipoGuastoDichiarato());
        Guasto inCache = statoRete.getGuastoApertoPerSorgente(msg.sorgenteId(), tipo);
        if (inCache == null) {
            LOG.debugf("RESOLVED dal campo per %s (%s): nessun guasto aperto da chiudere", msg.sorgenteId(), tipo);
            return;
        }

        Stazione stazione = statoRete.getStazione(msg.sorgenteId());
        if (stazione != null && StatiCanonici.STAZIONE_MANUTENZIONE.equalsIgnoreCase(stazione.stato)) {
            LOG.infof("🛠️ %s ha segnalato il rientro del guasto, ma la squadra è ancora sul posto: allarme lasciato aperto",
                    msg.sorgenteId());
            return;
        }

        Stazione tornataOperativa = QuarkusTransaction.requiringNew().call(() -> {
            Guasto dbGuasto = repository.trovaGuasto(inCache.id);
            chiudi(dbGuasto != null ? dbGuasto : inCache);
            return riportaOnlineSeLibera(msg.sorgenteId(), false);
        });

        inCache.risolto = true;
        inCache.timestampRisoluzione = Instant.now();
        statoRete.risolviGuasto(inCache.id);
        LOG.infof("✅ Guasto %s chiuso: %s dichiara che la condizione è rientrata", inCache.id, msg.sorgenteId());

        notificatore.statoStazione(tornataOperativa);
        notificatore.allarme(inCache);
    }

    /**
     * Chiusura decisa dall'operatore dalla dashboard: chiude il guasto e rimette in
     * servizio la sorgente che lo aveva segnalato.
     *
     * @return Il guasto chiuso, oppure null se quell'identificativo non esiste.
     */
    public Guasto risolviSuComandoOperatore(String guastoId) {
        Guasto[] chiuso = {null};
        Stazione tornataOperativa = QuarkusTransaction.requiringNew().call(() -> {
            Guasto guasto = repository.trovaGuasto(guastoId);
            if (guasto == null) {
                return null;
            }
            chiuso[0] = guasto;
            chiudi(guasto);

            if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
                // Il guasto appena chiuso non viene contato: Hibernate scarica la modifica
                // prima di eseguire la query.
                return riportaOnlineSeLibera(guasto.sorgenteId, false);
            }
            if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
                // L'avaria e' rientrata: il convoglio smette di essere "rotto".
                treni.rimettiInServizio(guasto.sorgenteId);
                // RF02.1.2.2.1: se il convoglio guasto aveva reso inagibile una stazione,
                // alla sua riparazione si chiude anche il guasto derivato.
                return chiudiDerivatiDaConvoglio(guasto.sorgenteId);
            }
            return null;
        });

        if (chiuso[0] == null) {
            return null;
        }
        notificatore.statoStazione(tornataOperativa);
        pubblicatore.pubblicaRisoluzione(chiuso[0]);
        notificatore.allarme(chiuso[0]);
        return chiuso[0];
    }

    /**
     * Chiude i guasti che la Centrale aveva aperto su una stazione a causa di questo
     * convoglio. Gira dentro la transazione del chiamante.
     *
     * @return La stazione tornata operativa, se ce n'e' una, altrimenti null.
     */
    private Stazione chiudiDerivatiDaConvoglio(String trenoId) {
        Stazione tornataOperativa = null;
        for (Guasto derivato : repository.guastiDerivatiDaConvoglio()) {
            if (derivato.messaggio == null || !derivato.messaggio.contains(trenoId)) {
                continue;
            }
            chiudi(derivato);
            pubblicatore.pubblicaRisoluzione(derivato);
            Stazione stazione = riportaOnlineSeLibera(derivato.sorgenteId, true);
            if (stazione != null) {
                tornataOperativa = stazione;
            }
        }
        return tornataOperativa;
    }

    /**
     * Rimette una stazione in servizio se non le resta aperto nessun altro guasto:
     * chiuderne uno su due non la rende agibile.
     *
     * <p>Si fa ripartire anche il cronometro dell'heartbeat. Senza, una stazione rimessa
     * ONLINE dall'operatore ma in realta' ancora spenta resterebbe ONLINE per sempre,
     * perche' il watchdog salta le stazioni che non hanno mai battuto; cosi' invece, se il
     * battito non arriva davvero, dopo il timeout torna OFFLINE da sola.</p>
     *
     * <p>Gira dentro la transazione del chiamante.</p>
     *
     * @param soloSeGuasta true per non toccare una stazione che si trova in un altro stato
     *                     (e' il caso della chiusura automatica, che non deve interrompere
     *                     una manutenzione in corso). Il comando esplicito dell'operatore
     *                     invece rimette in servizio comunque: e' RF01.4.2.
     * @return La stazione se e' tornata operativa, altrimenti null.
     */
    private Stazione riportaOnlineSeLibera(String stazioneId, boolean soloSeGuasta) {
        Stazione stazione = statoRete.getStazione(stazioneId);
        if (stazione == null) {
            return null;
        }
        if (soloSeGuasta && !StatiCanonici.STAZIONE_GUASTA.equalsIgnoreCase(stazione.stato)) {
            return null;
        }
        if (!repository.guastiApertiDi(stazioneId, "STAZIONE").isEmpty()) {
            return null;
        }

        String statoPrecedente = stazione.stato;
        stazione.stato = StatiCanonici.STAZIONE_ONLINE;
        stazione.ultimoHeartbeat = Instant.now();
        statoRete.aggiornaStazione(stazione);

        // Chiude in storico l'intervallo aperto dall'invio della squadra: senza questa
        // riga la transizione MANUTENZIONE -> ONLINE non resterebbe da nessuna parte e
        // dell'intervento si vedrebbe solo l'inizio.
        Stazione dbStazione = repository.trovaStazione(stazioneId);
        if (dbStazione != null) {
            repository.salvaStoricoStatoStazione(dbStazione, StatiCanonici.STAZIONE_ONLINE, statoPrecedente);
        }
        return stazione;
    }
}
