package it.uni.reti2.gateway;

import io.javalin.http.Context;
import it.uni.reti2.elaboration.AutomaGuasto;
import it.uni.reti2.elaboration.EventoCentrale;
import it.uni.reti2.elaboration.GestoreEventi;
import it.uni.reti2.elaboration.NavigazioneItinerario;
import it.uni.reti2.elaboration.PrevisioneArrivi;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Itinerario;
import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.persistence.Database;
import it.uni.reti2.persistence.RailwayRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * RestApiGateway implementa le API REST utilizzate dal Frontend (Dashboard Web) per
 * l'interrogazione dello stato della rete e l'esecuzione di comandi da parte degli operatori.
 *
 * <p>Ogni endpoint è un metodo che riceve il {@link Context} di Javalin: ServerRest collega
 * a ogni indirizzo il suo metodo. Le richieste sono di tre tipi:</p>
 * <ul>
 *   <li><b>letture</b> (GET): dalla cache in RAM quando si parla dello stato della rete, dal
 *       database quando si parla di anagrafica e di storico;</li>
 *   <li><b>anagrafica</b> (creare, modificare, cancellare stazioni, treni, tratte e
 *       itinerari): si scrive il database qui, in una transazione, e poi si allinea la cache;</li>
 *   <li><b>comandi dell'operatore e cambi di stato</b> (risolvi un allarme, sopprimi una
 *       corsa, invia la squadra, lo stato di una stazione o di un treno cambiato a mano):
 *       diventano un EventoCentrale per il GestoreEventi, perché lo stato lo cambiano solo gli
 *       automi. Qui resta la traduzione fra HTTP e dominio.</li>
 * </ul>
 *
 * <p><b>Transazioni.</b> Con Quarkus ogni metodo di scrittura era {@code @Transactional}; qui
 * lo stesso lavoro sta dentro {@code db.inTransazione(...)}, che restituisce la risposta da
 * dare. La cache si tocca dopo il commit, così resta l'immagine esatta del database anche se
 * il commit fallisce.</p>
 */
public class RestApiGateway {

    private static final Logger LOG = Logger.getLogger(RestApiGateway.class.getName());

    /** Lunghezza massima del nome del convoglio: è la chiave primaria Treni.id_convoglio VARCHAR(50). */
    private static final int LUNGHEZZA_MAX_NOME_CONVOGLIO = 50;

    /** Referenza alla logica e cache di sistema. */
    public TrafficLogicEngine statoRete;

    /** Unico punto da cui questa classe legge e scrive il database: nessuna query qui dentro. */
    public RailwayRepository repository;

    /** Le transazioni delle scritture di anagrafica. */
    public Database db;

    /**
     * Il calcolo dei ritardi e delle previsioni di arrivo. Qui serve per due cose: il
     * tabellone dei treni attesi in una stazione e l'avviso, quando l'amministratore
     * cambia la rete, che i percorsi memorizzati non valgono più.
     */
    public PrevisioneArrivi previsioni;

    /**
     * L'uscita verso il campo: i comandi che l'amministratore impartisce cambiando
     * l'anagrafica (treno eliminato, itinerario rifatto) devono arrivare ai nodi.
     */
    public PubblicatoreAlert pubblicatore;

    /**
     * Chi fa muovere gli automi: i comandi dell'operatore e i cambi di stato passano da lui.
     * Chiudere un allarme, per esempio, non è solo scrivere una riga: rimette in servizio la
     * sorgente, chiude i guasti che quella sorgente aveva provocato altrove e avvisa il campo.
     */
    public GestoreEventi gestore;

    /** La lettura dell'itinerario per il digital twin di bordo. */
    public NavigazioneItinerario navigazione;

    // ──────────────────────────────────────────────────────────────
    // DTO usati per il dialogo col frontend (evitano di esporre
    // direttamente le entità nelle richieste di scrittura).
    // ──────────────────────────────────────────────────────────────

    /** DTO per la creazione/modifica di una stazione. I wrapper permettono update parziali. */
    public static class StazioneDTO {
        public String id;
        public String nome;
        public String stato;
        public Double latitudine;
        public Double longitudine;
        public Integer binari;
    }

    /** Riferimento minimale a un itinerario ({"id":"IT1"}). */
    public static class ItinerarioRef {
        public String id;
    }

    /**
     * DTO per la creazione/modifica di un treno. {@code id} è il nome del convoglio
     * digitato dall'amministratore: è la chiave primaria della tabella Treni, quindi si
     * accetta solo in creazione (in modifica un id diverso da quello nel path viene
     * rifiutato con un 400).
     */
    public static class TrenoDTO {
        public String id;
        public String stato;
        public ItinerarioRef itinerario;
        public String itinerarioId; // accettato anche il formato "itinerarioId":"IT1"
    }

    /**
     * Esito della scrittura di un treno sul database: o l'errore REST da restituire al
     * frontend (e allora sul DB non è stato modificato niente), oppure la riga appena
     * scritta. Serve perché la transazione viene chiusa PRIMA di toccare la cache in RAM:
     * così la cache resta l'immagine esatta della tabella Treni anche se il commit fallisce.
     */
    private static class EsitoScritturaTreno {
        Risposta errore;
        Treno treno;
        boolean itinerarioCambiato;
    }

    /** DTO per la creazione/modifica di una tratta (itinerario) dalla pagina Gestione Tratte. */
    public static class TrattaDTO {
        public String id;
        public String nome;
        public List<String> stazioni;
        public List<Integer> travelTimes;
        public Boolean attivo;
        public List<String> treniIds;
        /**
         * Id della stazione che chiude il servizio, o null se l'itinerario si percorre
         * all'infinito. Si legge <b>insieme a {@code stazioni}</b>: sono la stessa
         * decisione (quale percorso e dove finisce) e un capolinea si puo' giudicare solo
         * guardando l'elenco a cui si riferisce. Se "stazioni" non c'e' nel JSON, la PUT
         * non tocca nemmeno questo campo, e un null vuol dire "nessun capolinea" invece
         * di "non lo sto cambiando".
         */
        public String capolinea;
    }

    /** DTO della singola tratta fisica tra due stazioni, distinto da un itinerario. */
    public static class TrattaElementoDTO {
        public String id;
        public String stazionePartenzaId;
        public String stazioneArrivoId;
        public Integer tempoPercorrenzaMinuti;
    }

    /**
     * Una risposta HTTP decisa dentro una transazione e scritta dopo il commit.
     *
     * @param stato Il codice HTTP.
     * @param corpo Il corpo JSON, o null per una risposta vuota.
     */
    private record Risposta(int stato, Object corpo) {
    }

    /**
     * GET /api/dashboard: indicatori chiave di prestazione (KPI) per la dashboard di
     * riepilogo, nel formato atteso dal frontend.
     */
    public void getDashboard(Context ctx) {
        ctx.json(statoRete.kpiDashboard());
    }

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    /** GET /api/stazioni: tutte le stazioni con le relative informazioni cacheate in RAM. */
    public void getStazioni(Context ctx) {
        ctx.json(statoRete.getTutteStazioni());
    }

    /**
     * POST /api/stazioni: crea una nuova stazione persistendola sul DB e inserendola in
     * cache. Risponde 201 con la stazione creata.
     */
    public void createStazione(Context ctx) {
        StazioneDTO dto = corpo(ctx, StazioneDTO.class);
        if (dto == null || dto.id == null || dto.id.isEmpty()) {
            rispondi(ctx, errore(400, "id mancante"));
            return;
        }
        Stazione stazione = new Stazione(
                dto.id,
                dto.nome != null ? dto.nome : dto.id,
                dto.latitudine != null ? dto.latitudine : 0.0,
                dto.longitudine != null ? dto.longitudine : 0.0,
                dto.binari != null ? dto.binari : 1);
        // Lo stato di partenza della stazione appena nata: e' l'unico che non passa
        // dall'automa, perche' prima di adesso la stazione non esisteva.
        stazione.stato = dto.stato != null
                ? StatiCanonici.stazioneDallOperatore(dto.stato)
                : StatiCanonici.STAZIONE_OFFLINE;
        boolean creata = db.inTransazione(c -> {
            if (repository.trovaStazione(dto.id) != null) {
                return false;
            }
            repository.salvaStazione(stazione);
            return true;
        });
        if (!creata) {
            rispondi(ctx, errore(409, "Stazione già esistente"));
            return;
        }
        statoRete.aggiornaStazione(stazione);
        ctx.status(201).json(stazione);
    }

    /**
     * PUT /api/stazioni/{id}: aggiorna una stazione esistente, modificando solo i campi
     * non-null del body. Risponde 200 oppure 404.
     */
    public void updateStazione(Context ctx) {
        String id = ctx.pathParam("id");
        StazioneDTO dto = corpo(ctx, StazioneDTO.class);
        Stazione dbStazione = repository.trovaStazione(id);
        if (dbStazione == null) {
            ctx.status(404);
            return;
        }
        if (dto.nome != null) dbStazione.nome = dto.nome;
        if (dto.latitudine != null) dbStazione.latitudine = dto.latitudine;
        if (dto.longitudine != null) dbStazione.longitudine = dto.longitudine;
        if (dto.binari != null) dbStazione.binari = dto.binari;
        db.inTransazione(() -> repository.aggiornaStazione(dbStazione));

        // Riflette le modifiche anche sulla cache RAM
        Stazione cache = statoRete.getStazione(id);
        if (cache == null) cache = dbStazione;
        if (dto.nome != null) cache.nome = dto.nome;
        if (dto.latitudine != null) cache.latitudine = dto.latitudine;
        if (dto.longitudine != null) cache.longitudine = dto.longitudine;
        if (dto.binari != null) cache.binari = dto.binari;
        statoRete.aggiornaStazione(cache);
        if (dto.stato != null) {
            // Lo stato scelto a mano dall'amministratore e' un comando esplicito e passa
            // sopra a tutto, manutenzione compresa: lo scrive l'automa, e il gestore lo
            // annuncia alle altre schermate e ai treni.
            gestore.gestisci(EventoCentrale.statoDallOperatore(EventoCentrale.Tipo.STATO_STAZIONE_OPERATORE,
                    id, StatiCanonici.stazioneDallOperatore(dto.stato)));
        }
        ctx.json(cache);
    }

    /**
     * DELETE /api/stazioni/{id}: elimina una stazione. Se è referenziata da qualche tratta
     * risponde 409, altrimenti chiude la partita con i transiti ancora aperti e cancella la
     * riga di anagrafica. Risponde 204, 404 o 409.
     *
     * <p><b>Lo storico non si tocca</b> (RF02.7): le righe di Storico_Transiti,
     * Storico_Stato_Stazioni ed eventi_stazioni restano dov'erano. Adesso che gli storici
     * portano dentro l'id e il nome della stazione, la storia di una stazione dismessa
     * resta consultabile — che è esattamente quello che il requisito chiede.</p>
     */
    public void deleteStazione(Context ctx) {
        String id = ctx.pathParam("id");
        Risposta risposta = db.inTransazione(c -> {
            Stazione dbStazione = repository.trovaStazione(id);
            if (dbStazione == null) {
                return new Risposta(404, null);
            }
            long tratteCollegate = repository.contaTratteConStazione(id);
            if (tratteCollegate > 0) {
                return errore(409, "Stazione referenziata da " + tratteCollegate + " tratte: eliminarle prima");
            }
            // Restano da cancellare solo i transiti VIVI: Transiti è una tabella dello stato
            // corrente (il treno dentro la stazione) e ha davvero una chiave esterna verso
            // Stazione. Lo storico invece sopravvive alla stazione.
            repository.eliminaTransitiDiStazione(id);
            repository.eliminaStazione(dbStazione);
            return new Risposta(204, null);
        });
        if (risposta.stato() == 204) {
            statoRete.rimuoviStazione(id);
        }
        rispondi(ctx, risposta);
    }

    /**
     * GET /api/stazioni/{id}/treni-attesi: i convogli che questa stazione deve aspettarsi,
     * con l'orario previsto di ciascuno. È la schermata che il testo del progetto mette fra
     * i compiti del tecnico ("quali treni ci si aspetta in ingresso e in uscita in una certa
     * stazione").
     *
     * <p>La risposta la calcola la Centrale sull'itinerario dei treni, non il browser sulla
     * loro posizione istantanea. Elenco ordinato per tempo mancante, oppure 404 se la
     * stazione non esiste.</p>
     */
    public void getTreniAttesi(Context ctx) {
        String id = ctx.pathParam("id");
        if (statoRete.getStazione(id) == null) {
            rispondi(ctx, errore(404, "Stazione inesistente: " + id));
            return;
        }
        ctx.json(previsioni.treniAttesi(id));
    }

    // ──────────────────────────────────────────────────────────────
    // TRENI
    // ──────────────────────────────────────────────────────────────

    /** GET /api/treni: tutti i treni e la loro telemetria istantanea (dalla RAM). */
    public void getTreni(Context ctx) {
        ctx.json(statoRete.getTuttiTreni());
    }

    /**
     * POST /api/treni: crea un nuovo treno (stato di default "fermo") ed eventualmente lo
     * assegna a un itinerario. Il campo {@code id} del corpo è il nome del convoglio digitato
     * dall'amministratore: diventa la chiave primaria del treno, quindi è l'unica occasione
     * per deciderlo. Risponde 201 con il treno creato, 400 se il nome manca o è troppo lungo,
     * 409 se un convoglio con quel nome esiste già.
     */
    public void createTreno(Context ctx) {
        TrenoDTO dto = corpo(ctx, TrenoDTO.class);
        String nomeConvoglio = dto != null && dto.id != null ? dto.id.trim() : "";
        if (nomeConvoglio.isEmpty()) {
            rispondi(ctx, errore(400, "Nome del convoglio mancante"));
            return;
        }
        // La chiave primaria è VARCHAR(50): meglio un 400 parlante che un errore SQL.
        if (nomeConvoglio.length() > LUNGHEZZA_MAX_NOME_CONVOGLIO) {
            rispondi(ctx, errore(400, "Il nome del convoglio non può superare i "
                    + LUNGHEZZA_MAX_NOME_CONVOGLIO + " caratteri"));
            return;
        }
        // La transazione si apre e si CHIUDE qui: la cache viene toccata solo dopo il
        // commit (vedi nota su EsitoScritturaTreno).
        EsitoScritturaTreno esito = db.inTransazione(c -> creaTrenoSuDb(nomeConvoglio, dto));
        if (esito.errore != null) {
            rispondi(ctx, esito.errore);
            return;
        }
        // ultimoAggiornamento resta null: il convoglio appena creato non ha ancora
        // trasmesso niente, ed è proprio quello che il watchdog usa per non aprire un
        // guasto "treno fermo" su un treno il cui processo non è nemmeno acceso.
        statoRete.aggiornaTreno(esito.treno);
        ctx.status(201).json(esito.treno);
    }

    /** Inserisce il treno nella tabella Treni. Gira dentro la transazione aperta dal chiamante. */
    private EsitoScritturaTreno creaTrenoSuDb(String nomeConvoglio, TrenoDTO dto) {
        EsitoScritturaTreno esito = new EsitoScritturaTreno();
        if (repository.trovaTreno(nomeConvoglio) != null) {
            esito.errore = errore(409, "Esiste già un treno con il nome '" + nomeConvoglio + "'");
            return esito;
        }
        Treno treno = new Treno(nomeConvoglio);
        treno.stato = dto.stato != null ? StatiCanonici.trenoDallOperatore(dto.stato) : "fermo";

        String itinerarioId = estraiItinerarioId(dto);
        if (itinerarioId != null) {
            Itinerario itinerario = repository.trovaItinerario(itinerarioId);
            if (itinerario != null) {
                treno.itinerario = itinerario;
            }
        }
        repository.salvaTreno(treno);
        esito.treno = treno;
        return esito;
    }

    /**
     * PUT /api/treni/{id}: aggiorna stato e itinerario di un treno esistente. Se cambia
     * l'itinerario, il digital twin viene avvisato con un evento ITINERARIO_AGGIORNATO via MQTT.
     *
     * <p>L'itinerario è anagrafica e si scrive qui; lo stato invece è dell'automa, e ci
     * arriva con un evento STATO_TRENO_OPERATORE. Il vocabolario dello stato si controlla
     * però prima di scrivere l'itinerario, così una richiesta con uno stato sbagliato non
     * cambia nemmeno l'itinerario (prima le due scritture stavano nella stessa transazione).</p>
     *
     * <p>Il nome del convoglio NON si aggiorna qui: è la chiave primaria della tabella Treni,
     * quindi è immutabile per costruzione. Per cambiarlo si elimina il treno e si ricrea (la
     * UI infatti mostra il campo Convoglio in sola lettura in modifica). Risponde 200, 404 se
     * il treno non esiste, 400 se si prova a cambiargli il nome o se lo stato non è ammesso.</p>
     */
    public void updateTreno(Context ctx) {
        String id = ctx.pathParam("id");
        TrenoDTO letto = corpo(ctx, TrenoDTO.class);
        TrenoDTO dati = letto != null ? letto : new TrenoDTO();
        if (dati.id != null && !dati.id.trim().isEmpty() && !dati.id.trim().equals(id)) {
            rispondi(ctx, errore(400, "Il nome del convoglio identifica il treno e non è "
                    + "modificabile: eliminare il treno e ricrearlo con il nome nuovo"));
            return;
        }
        String stato = StatiCanonici.trenoDallOperatore(dati.stato);
        EsitoScritturaTreno esito = db.inTransazione(c -> aggiornaItinerarioSuDb(id, dati, stato));
        if (esito.errore != null) {
            rispondi(ctx, esito.errore);
            return;
        }

        // Allinea la cache RAM al database, conservando la telemetria volatile già presente
        // in memoria.
        Treno cache = statoRete.getTreno(id);
        if (cache == null) {
            cache = esito.treno;
            cache.ultimoAggiornamento = Instant.now();
        }
        cache.itinerario = esito.treno.itinerario;
        statoRete.aggiornaTreno(cache);
        if (stato != null) {
            gestore.gestisci(EventoCentrale.statoDallOperatore(EventoCentrale.Tipo.STATO_TRENO_OPERATORE, id, stato));
        }

        if (esito.itinerarioCambiato) {
            pubblicatore.pubblicaItinerarioAggiornato(id);
        }
        ctx.json(cache);
    }

    /**
     * Controlla la richiesta e scrive sul database l'itinerario del treno. Gira dentro la
     * transazione aperta dal chiamante.
     */
    private EsitoScritturaTreno aggiornaItinerarioSuDb(String id, TrenoDTO dto, String stato) {
        EsitoScritturaTreno esito = new EsitoScritturaTreno();
        Treno dbTreno = repository.trovaTreno(id);
        if (dbTreno == null) {
            esito.errore = new Risposta(404, null);
            return esito;
        }
        // Lo stesso vocabolario del CHECK di Treni.stato: meglio un 400 parlante adesso che
        // un errore SQL dopo aver gia' cambiato l'itinerario.
        if (stato != null && !StatiCanonici.TRENO.contains(stato)) {
            esito.errore = errore(400, "Stato del treno non ammesso: '" + stato + "' (valori ammessi: "
                    + String.join(", ", StatiCanonici.TRENO) + ")");
            return esito;
        }

        String itinerarioId = estraiItinerarioId(dto);
        if (itinerarioId != null) {
            String attuale = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
            if (!itinerarioId.equals(attuale)) {
                Itinerario itinerario = repository.trovaItinerario(itinerarioId);
                if (itinerario == null) {
                    esito.errore = errore(400, "Itinerario inesistente: " + itinerarioId);
                    return esito;
                }
                repository.aggiornaItinerarioTreno(id, itinerarioId);
                dbTreno.itinerario = itinerario;
                esito.itinerarioCambiato = true;
            }
        }
        esito.treno = dbTreno;
        return esito;
    }

    /**
     * DELETE /api/treni/{id}: elimina un treno chiudendo i suoi transiti ancora aperti e
     * notificando lo STOP via MQTT. Risponde 204 oppure 404.
     *
     * <p><b>Lo storico non si tocca</b> (RF02.7), come per le stazioni: i passaggi, i
     * cambi di stato e gli itinerari percorsi da quel convoglio sono cose successe e
     * restano scritte.</p>
     */
    public void deleteTreno(Context ctx) {
        String id = ctx.pathParam("id");
        Risposta risposta = db.inTransazione(c -> {
            Treno dbTreno = repository.trovaTreno(id);
            if (dbTreno == null) {
                return new Risposta(404, null);
            }
            // Solo i transiti vivi: è la tabella dello stato corrente e ha la chiave esterna.
            repository.eliminaTransitiDiTreno(id);
            repository.eliminaTreno(dbTreno);
            return new Risposta(204, null);
        });
        if (risposta.stato() == 204) {
            statoRete.rimuoviTreno(id);
            previsioni.dimenticaTreno(id);
            pubblicatore.pubblicaStop(id, "Treno eliminato da operatore");
        }
        rispondi(ctx, risposta);
    }

    // ──────────────────────────────────────────────────────────────
    // TRATTE ELEMENTARI (archi fisici della rete)
    // ──────────────────────────────────────────────────────────────

    /** GET /api/tratte-elementari. */
    public void getTratteElementari(Context ctx) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tratta tratta : repository.tutteLeTratte()) result.add(trattaElementoToDto(tratta));
        ctx.json(result);
    }

    /** POST /api/tratte-elementari. */
    public void createTrattaElemento(Context ctx) {
        TrattaElementoDTO dto = corpo(ctx, TrattaElementoDTO.class);
        if (dto == null || dto.id == null || dto.id.isBlank()
                || dto.stazionePartenzaId == null || dto.stazioneArrivoId == null) {
            rispondi(ctx, errore(400, "id e stazioni di partenza/arrivo sono obbligatori"));
            return;
        }
        Risposta risposta = db.inTransazione(c -> {
            if (repository.trovaTratta(dto.id) != null) return errore(409, "Tratta già esistente");
            Stazione partenza = repository.trovaStazione(dto.stazionePartenzaId);
            Stazione arrivo = repository.trovaStazione(dto.stazioneArrivoId);
            if (partenza == null || arrivo == null) return errore(400, "Stazione di partenza o arrivo inesistente");
            Tratta tratta = new Tratta();
            tratta.id = dto.id;
            tratta.stazionePartenza = partenza;
            tratta.stazioneArrivo = arrivo;
            tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti != null ? dto.tempoPercorrenzaMinuti : 15;
            repository.salvaTratta(tratta);
            return new Risposta(201, trattaElementoToDto(tratta));
        });
        if (risposta.stato() == 201) {
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    /**
     * PUT /api/tratte-elementari/{id}. Le due stazioni si controllano entrambe prima di
     * scrivere: una richiesta con la partenza giusta e l'arrivo sbagliato non cambia niente.
     */
    public void updateTrattaElemento(Context ctx) {
        String id = ctx.pathParam("id");
        TrattaElementoDTO dto = corpo(ctx, TrattaElementoDTO.class);
        Risposta risposta = db.inTransazione(c -> {
            Tratta tratta = repository.trovaTratta(id);
            if (tratta == null) return new Risposta(404, null);
            if (dto.stazionePartenzaId != null) {
                Stazione s = repository.trovaStazione(dto.stazionePartenzaId);
                if (s == null) return errore(400, "Stazione di partenza inesistente");
                tratta.stazionePartenza = s;
            }
            if (dto.stazioneArrivoId != null) {
                Stazione s = repository.trovaStazione(dto.stazioneArrivoId);
                if (s == null) return errore(400, "Stazione di arrivo inesistente");
                tratta.stazioneArrivo = s;
            }
            if (dto.tempoPercorrenzaMinuti != null) tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti;
            repository.aggiornaTratta(tratta);
            return new Risposta(200, trattaElementoToDto(tratta));
        });
        if (risposta.stato() == 200) {
            // Il tempo di percorrenza è il dato con cui si calcolano gli orari previsti:
            // cambiarlo senza avvisare lascerebbe la Centrale a prevedere sui minuti vecchi.
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    /** DELETE /api/tratte-elementari/{id}. */
    public void deleteTrattaElemento(Context ctx) {
        String id = ctx.pathParam("id");
        Risposta risposta = db.inTransazione(c -> {
            Tratta tratta = repository.trovaTratta(id);
            if (tratta == null) return new Risposta(404, null);
            long usi = repository.contaItinerariCheUsano(id);
            if (usi > 0) return errore(409, "Tratta usata da " + usi + " itinerari: modificare prima gli itinerari");
            // Il rifiuto resta per la tabella viva Transiti, che verso Tratte ha davvero una
            // chiave esterna. Sui transiti STORICI invece è caduto con RF02.7: la riga di
            // storico si porta dentro l'id e la descrizione della tratta.
            if (repository.contaTransitiSuTratta(id) > 0) {
                return errore(409, "Tratta presente nei transiti e non eliminabile");
            }
            // Treni.PosizioneAttualeTrattaOStazione è una chiave esterna verso Tratte: senza
            // questo controllo la DELETE partiva lo stesso, Postgres la rifiutava per violazione
            // di vincolo e l'amministratore si prendeva un 500 invece del 409 con la
            // spiegazione che chiede RF01.3.5.
            long treniFermiQui = repository.contaTreniInPosizioneSuTratta(id);
            if (treniFermiQui > 0) {
                return errore(409, "Tratta usata come posizione corrente di " + treniFermiQui
                        + " convogli: spostarli prima di eliminarla");
            }
            repository.eliminaTratta(tratta);
            return new Risposta(204, null);
        });
        if (risposta.stato() == 204) {
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    // ──────────────────────────────────────────────────────────────
    // TRATTE (itinerari per il frontend)
    // ──────────────────────────────────────────────────────────────

    /** GET /api/tratte: gli itinerari nel formato atteso dal frontend. */
    public void getTratte(Context ctx) {
        List<Map<String, Object>> result = db.inTransazione(c -> {
            List<Map<String, Object>> itinerari = new ArrayList<>();
            for (Itinerario it : repository.tuttiGliItinerari()) {
                itinerari.add(trattaToDto(it));
            }
            return itinerari;
        });
        ctx.json(result);
    }

    /**
     * POST /api/tratte: crea un nuovo Itinerario a partire dalla lista ordinata di stazioni
     * fornita dal frontend. Per ogni coppia consecutiva usa la Tratta esistente (aggiornandone
     * il tempo di percorrenza se nessun altro itinerario la usa) e assegna eventualmente i
     * treni indicati, che ricevono ITINERARIO_AGGIORNATO. Risponde 201 con il DTO nello
     * stesso formato del GET.
     */
    public void createTratta(Context ctx) {
        TrattaDTO dto = corpo(ctx, TrattaDTO.class);
        if (dto == null || dto.stazioni == null || dto.stazioni.size() < 2) {
            rispondi(ctx, errore(400, "Servono almeno due stazioni"));
            return;
        }
        String id = (dto.id != null && !dto.id.isEmpty()) ? dto.id
                : "IT-" + UUID.randomUUID().toString().substring(0, 8);
        Set<String> daNotificare = new LinkedHashSet<>();
        Risposta risposta = db.inTransazione(c -> {
            if (repository.trovaItinerario(id) != null) {
                return errore(409, "Itinerario già esistente: " + id);
            }
            // Prima di scrivere qualsiasi cosa: se manca anche un solo arco non si crea niente.
            Risposta tratteMancanti = verificaTratteEsistenti(dto.stazioni);
            if (tratteMancanti != null) return tratteMancanti;
            Risposta capolineaNonValido = verificaCapolinea(dto.stazioni, dto.capolinea);
            if (capolineaNonValido != null) return capolineaNonValido;

            Itinerario itinerario = new Itinerario();
            itinerario.id = id;
            itinerario.capolineaId = normalizzaCapolinea(dto.capolinea);
            repository.salvaItinerario(itinerario);

            Risposta errore = componiItinerario(itinerario, dto.stazioni, dto.travelTimes);
            if (errore != null) return errore;

            assegnaTreni(itinerario, dto.treniIds, false);
            // Come in updateTratta: un treno agganciato qui stava girando su un altro
            // itinerario (o su nessuno), e senza ITINERARIO_AGGIORNATO il suo digital twin
            // continuerebbe sul percorso vecchio mentre la Centrale lo crede su questo.
            if (dto.treniIds != null) {
                daNotificare.addAll(dto.treniIds);
            }
            return new Risposta(201, trattaToDto(itinerario));
        });
        if (risposta.stato() == 201) {
            for (String trenoId : daNotificare) {
                pubblicatore.pubblicaItinerarioAggiornato(trenoId);
            }
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    /**
     * PUT /api/tratte/{id}: modifica un itinerario preesistente. Le associazioni con le
     * tratte vengono cancellate e ricreate dalla nuova lista di stazioni; i treni non più
     * presenti vengono sganciati e ogni treno coinvolto riceve ITINERARIO_AGGIORNATO.
     * Risponde 200 oppure 404.
     */
    public void updateTratta(Context ctx) {
        String id = ctx.pathParam("id");
        TrattaDTO dto = corpo(ctx, TrattaDTO.class);
        Set<String> daNotificare = new LinkedHashSet<>();
        Risposta risposta = db.inTransazione(c -> {
            Itinerario itinerario = repository.trovaItinerario(id);
            if (itinerario == null) {
                return new Risposta(404, null);
            }
            if (dto.stazioni != null) {
                // Un elenco troppo corto è un rifiuto esplicito, non un aggiornamento da
                // ignorare. Se invece "stazioni" non c'è proprio nel JSON (null) la PUT tocca
                // solo i treni assegnati e l'itinerario resta com'è.
                if (dto.stazioni.size() < 2) {
                    return errore(400, "Servono almeno due stazioni");
                }
                // La verifica sta PRIMA della delete: se una coppia è scoperta si esce con 404
                // senza aver smontato niente.
                Risposta tratteMancanti = verificaTratteEsistenti(dto.stazioni);
                if (tratteMancanti != null) return tratteMancanti;
                Risposta capolineaNonValido = verificaCapolinea(dto.stazioni, dto.capolinea);
                if (capolineaNonValido != null) return capolineaNonValido;

                repository.eliminaTratteDellItinerario(id);
                Risposta errore = componiItinerario(itinerario, dto.stazioni, dto.travelTimes);
                if (errore != null) return errore;
                // Il capolinea segue l'elenco delle stazioni: toglierlo qui (capolinea null) e'
                // il modo con cui l'amministratore rimette in servizio un treno che aveva
                // chiuso la corsa, perche' subito sotto tutti i convogli assegnati ricevono
                // ITINERARIO_AGGIORNATO e il twin si riaggancia al percorso nuovo.
                itinerario.capolineaId = normalizzaCapolinea(dto.capolinea);
                repository.aggiornaCapolinea(itinerario);
            }

            // Gli id dei treni assegnati vanno letti PRIMA di toccare le assegnazioni:
            // assegnaTreni(..., true) sgancia quelli non più in elenco e una query fatta
            // dopo non li troverebbe più. Erano proprio i treni sganciati a non ricevere
            // mai ITINERARIO_AGGIORNATO, continuando a girare sulla tratta vecchia.
            for (Treno t : repository.treniDellItinerario(id)) {
                daNotificare.add(t.id);
            }
            // "treniIds" assente nel JSON vuol dire "le assegnazioni non le sto toccando",
            // non "sgancia tutti".
            if (dto.treniIds != null) {
                assegnaTreni(itinerario, dto.treniIds, true);
                daNotificare.addAll(dto.treniIds); // anche i treni appena agganciati
            }
            return new Risposta(200, trattaToDto(itinerario));
        });
        if (risposta.stato() == 200) {
            // Ogni treno coinvolto (vecchio o nuovo) deve ricaricare l'itinerario dal server
            for (String trenoId : daNotificare) {
                pubblicatore.pubblicaItinerarioAggiornato(trenoId);
            }
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    /**
     * DELETE /api/tratte/{id}: elimina un itinerario. Sgancia i treni assegnati, cancella le
     * associazioni con le tratte e poi l'itinerario. Risponde 204 oppure 404.
     */
    public void deleteTratta(Context ctx) {
        String id = ctx.pathParam("id");
        Set<String> daNotificare = new LinkedHashSet<>();
        Risposta risposta = db.inTransazione(c -> {
            Itinerario itinerario = repository.trovaItinerario(id);
            if (itinerario == null) {
                return new Risposta(404, null);
            }
            // Sgancia i treni tenendo da parte i loro id: vanno avvisati. Senza la notifica il
            // digital twin continuava a percorrere un itinerario che non esisteva più.
            for (Treno t : repository.treniDellItinerario(id)) {
                repository.aggiornaItinerarioTreno(t.id, null);
                daNotificare.add(t.id);
            }
            repository.eliminaTratteDellItinerario(id);
            // Storico_Itinerari resta: i viaggi già fatti su questo itinerario sono avvenuti
            // e la riga di storico si porta dentro il percorso (RF02.7).
            repository.eliminaItinerario(itinerario);
            return new Risposta(204, null);
        });
        if (risposta.stato() == 204) {
            for (String trenoId : daNotificare) {
                Treno cache = statoRete.getTreno(trenoId);
                if (cache != null) {
                    cache.itinerario = null;
                    statoRete.aggiornaTreno(cache);
                }
            }
            // Ricevuto ITINERARIO_AGGIORNATO il twin scarta l'itinerario in memoria e ne chiede
            // uno nuovo: non essendocene più resta fermo e visibile in attesa di essere soppresso.
            for (String trenoId : daNotificare) {
                pubblicatore.pubblicaItinerarioAggiornato(trenoId);
            }
            previsioni.itinerariCambiati();
        }
        rispondi(ctx, risposta);
    }

    // ──────────────────────────────────────────────────────────────
    // TRANSITI E ALLARMI
    // ──────────────────────────────────────────────────────────────

    /**
     * GET /api/transiti: lo storico persistito dei passaggi ai sensori delle stazioni, nel
     * formato atteso dal frontend (max 200 eventi, dal più recente).
     */
    public void getTransiti(Context ctx) {
        List<StoricoTransito> transiti = repository.ultimiTransitiStorici(200);
        List<Map<String, Object>> result = new ArrayList<>();
        for (StoricoTransito t : transiti) {
            Instant timestamp = t.tempoUscita != null ? t.tempoUscita : t.tempoEntrata;
            // Il ritardo è quello CONGELATO al momento del passaggio, non quello che il
            // convoglio ha adesso.
            int ritardo = t.ritardoMinuti != null ? t.ritardoMinuti : 0;

            Map<String, Object> dto = new HashMap<>();
            dto.put("id", t.idTransito + "-" + t.id);
            dto.put("trenoId", t.trenoId);
            dto.put("stazioneId", t.stazioneId);
            dto.put("tipo", t.tempoUscita == null ? "ingresso" : "uscita");
            dto.put("timestamp", timestamp != null ? timestamp.toString() : null);
            // La tratta è uno dei campi che RF01.5 chiede di poter consultare.
            dto.put("trattaId", t.trattaId);
            dto.put("inRitardo", ritardo > 0);
            dto.put("ritardo", ritardo);
            result.add(dto);
        }
        ctx.json(result);
    }

    /** GET /api/allarmi: tutti gli allarmi persistiti sul database, nel formato del frontend. */
    public void getAllarmi(Context ctx) {
        List<Guasto> guasti = repository.tuttiIGuastiRecenti();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Guasto g : guasti) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", g.id);
            dto.put("tipo", tipoAllarmePerFrontend(g));
            dto.put("severita", g.severita != null ? g.severita.toLowerCase() : "warning");
            dto.put("messaggio", g.messaggio != null ? g.messaggio : "Allarme di sistema per " + g.id);
            dto.put("sorgenteId", g.sorgenteId != null ? g.sorgenteId : "");
            dto.put("sorgenteTipo", g.sorgenteTipo != null ? g.sorgenteTipo : "");
            dto.put("origine", Guasto.originePerOperatore(g));
            dto.put("timestamp", g.timestamp != null ? g.timestamp.toString() : null);
            dto.put("risolto", g.risolto);
            dto.put("timestampRisoluzione", g.timestampRisoluzione != null ? g.timestampRisoluzione.toString() : null);
            result.add(dto);
        }
        ctx.json(result);
    }

    /**
     * POST /api/allarmi/{id}/risolvi: un operatore di centrale dichiara il guasto risolto.
     * Risponde con il guasto aggiornato, oppure 404. Tutto quello che ne segue (la sorgente
     * che torna in servizio, i guasti derivati che si chiudono, il RESOLVED al campo) è il
     * ciclo di vita del guasto e lo fa il GestoreEventi.
     */
    public void risolviAllarme(Context ctx) {
        rispondi(ctx, gestore.gestisci(EventoCentrale.comando(EventoCentrale.Tipo.RISOLVI_GUASTO, ctx.pathParam("id"))));
    }

    /**
     * POST /api/treni/{id}/sopprimi: sopprime la corsa di un treno. Risponde con il treno
     * aggiornato, 404 se non esiste, 400 se non è fermo in stazione.
     */
    public void sopprimiTreno(Context ctx) {
        rispondi(ctx, gestore.gestisci(EventoCentrale.comando(EventoCentrale.Tipo.SOPPRIMI_TRENO, ctx.pathParam("id"))));
    }

    /**
     * POST /api/stazioni/{id}/manutenzione: manda una squadra di manutenzione a una stazione
     * guasta (RF01.4.1). Risponde con la stazione aggiornata, 404 se non esiste, 409 se non
     * ha guasti aperti. A chiudere l'intervento è {@code POST /allarmi/{id}/risolvi}.
     */
    public void dispacciaManutenzione(Context ctx) {
        rispondi(ctx, gestore.gestisci(EventoCentrale.comando(EventoCentrale.Tipo.INVIA_SQUADRA, ctx.pathParam("id"))));
    }

    //------ da riordinare ma per comodità lascerò così
    // ──────────────────────────────────────────────────────────────
    // SUPPORTO AL DIGITAL TWIN DEI TRENI
    // ──────────────────────────────────────────────────────────────

    /**
     * GET /api/treni/{id}/itinerario: l'itinerario completo di un treno con coordinate e
     * tempi di percorrenza. È la "mappa di viaggio" scaricata dal digital twin al boot.
     * Risponde con la sequenza di stazioni, oppure 404.
     */
    public void getItinerarioTreno(Context ctx) {
        String id = ctx.pathParam("id");
        Treno treno = repository.trovaTreno(id);
        if (treno == null || treno.itinerario == null) {
            rispondi(ctx, errore(404, "Treno inesistente o senza itinerario"));
            return;
        }
        List<ItinerarioTratta> tratte = repository.tratteOrdinateDi(treno.itinerario.id);
        if (tratte.isEmpty()) {
            rispondi(ctx, errore(404, "Itinerario senza tratte"));
            return;
        }

        List<Map<String, Object>> stazioni = new ArrayList<>();
        for (int i = 0; i < tratte.size(); i++) {
            Tratta t = tratte.get(i).tratta;
            stazioni.add(tappaItinerario(t.stazionePartenza, t.tempoPercorrenzaMinuti != null ? t.tempoPercorrenzaMinuti : 15));
            if (i == tratte.size() - 1) {
                // L'ultima stazione è il capolinea: tempo verso la prossima = 0
                stazioni.add(tappaItinerario(t.stazioneArrivo, 0));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("itinerarioId", treno.itinerario.id);
        result.put("stazioni", stazioni);
        // L'unico canale con cui il convoglio impara il percorso e' questo, quindi e' anche
        // l'unico da cui puo' sapere dove finisce il servizio. Null = si va avanti e
        // indietro senza fine, che e' il comportamento di sempre.
        result.put("capolineaId", treno.itinerario.capolineaId);
        ctx.json(result);
    }

    /**
     * GET /api/prossima-stazione?treno=&stazione=&direzione=: la prossima stazione
     * dell'itinerario di un treno rispetto a una stazione data e alla direzione di marcia
     * (A = andata, R = ritorno). Risponde {"prossimaStazione":{"id","nome"}} oppure
     * {"prossimaStazione":null} al capolinea.
     */
    public void getProssimaStazione(Context ctx) {
        String trenoId = ctx.queryParam("treno");
        String stazioneId = ctx.queryParam("stazione");
        String direzione = ctx.queryParam("direzione") != null ? ctx.queryParam("direzione") : "A";
        Treno treno = trenoId != null ? repository.trovaTreno(trenoId) : null;
        if (treno == null || treno.itinerario == null) {
            rispondi(ctx, errore(404, "Treno inesistente o senza itinerario"));
            return;
        }
        // Il conto lo fa la stessa classe che lo fa per il gestore degli eventi: leggere un
        // itinerario nel verso giusto è una regola sola, non una per chi la chiede.
        String prossimaId = navigazione.prossimaStazione(treno.itinerario.id, stazioneId, direzione);

        Map<String, Object> result = new HashMap<>();
        result.put("prossimaStazione", null);
        Stazione prossima = prossimaId != null ? repository.trovaStazione(prossimaId) : null;
        if (prossima != null) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", prossima.id);
            dto.put("nome", prossima.nome);
            result.put("prossimaStazione", dto);
        }
        ctx.json(result);
    }

    // ──────────────────────────────────────────────────────────────
    // METODI DI SUPPORTO PRIVATI
    // ──────────────────────────────────────────────────────────────

    /**
     * Legge il corpo JSON della richiesta. Un corpo vuoto vale null, come faceva JAX-RS; un
     * corpo che non è JSON fa uscire un'eccezione che ServerRest trasforma in un 400.
     */
    private static <T> T corpo(Context ctx, Class<T> tipo) {
        String testo = ctx.body();
        return testo.isBlank() ? null : Json.GSON.fromJson(testo, tipo);
    }

    /** Una risposta di errore con il corpo {"errore": messaggio}, come tutte le altre. */
    private static Risposta errore(int stato, String messaggio) {
        return new Risposta(stato, Map.of("errore", messaggio));
    }

    /** Scrive la risposta: il codice e, se c'è, il corpo in JSON. */
    private static void rispondi(Context ctx, Risposta risposta) {
        ctx.status(risposta.stato());
        if (risposta.corpo() != null) {
            ctx.json(risposta.corpo());
        }
    }

    /** Traduce in HTTP l'esito di un comando passato dal gestore degli eventi. */
    private static void rispondi(Context ctx, GestoreEventi.Esito esito) {
        switch (esito.risposta()) {
            case FATTO -> ctx.json(esito.corpo());
            case RIFIUTATO -> rispondi(ctx, errore(400, esito.errore()));
            case CONFLITTO -> ctx.status(409);
            case NON_TROVATO, IGNORATO -> ctx.status(404);
        }
    }

    /** Estrae l'ID itinerario dal DTO accettando entrambi i formati previsti dal contratto. */
    private String estraiItinerarioId(TrenoDTO dto) {
        if (dto.itinerario != null && dto.itinerario.id != null && !dto.itinerario.id.isEmpty()) {
            return dto.itinerario.id;
        }
        if (dto.itinerarioId != null && !dto.itinerarioId.isEmpty()) {
            return dto.itinerarioId;
        }
        return null;
    }

    /** Costruisce la tappa {id,nome,latitudine,longitudine,tempoVersoProssimaMinuti,stato}. */
    private Map<String, Object> tappaItinerario(Stazione stazione, int tempoVersoProssima) {
        Map<String, Object> tappa = new HashMap<>();
        tappa.put("id", stazione.id);
        tappa.put("nome", stazione.nome);
        tappa.put("latitudine", stazione.latitudine != null ? stazione.latitudine : 0.0);
        tappa.put("longitudine", stazione.longitudine != null ? stazione.longitudine : 0.0);
        tappa.put("tempoVersoProssimaMinuti", tempoVersoProssima);
        // Lo stato di ogni fermata viaggia insieme al percorso: gli alert su railway/alerts
        // li sente solo chi era acceso quando sono passati, e un convoglio che si avvia
        // adesso non ha modo di sapere che una stazione della sua tratta e' chiusa. Si
        // legge dalla cache e non dalla riga di anagrafica perche' e' la cache la copia
        // aggiornata.
        Stazione inCache = statoRete.getStazione(stazione.id);
        tappa.put("stato", inCache != null ? inCache.stato : stazione.stato);
        return tappa;
    }

    /**
     * Controlla che ogni coppia di stazioni consecutive dell'elenco abbia già la sua tratta
     * registrata in rete. Copre in un colpo solo tutti i modi in cui l'elenco può cambiare
     * (stazione aggiunta, tolta o spostata di ordine).
     *
     * <p><strong>Va chiamato prima di toccare Itinerario_Tratta.</strong> La transazione fa
     * rollback solo sulle eccezioni, non quando si torna una risposta di errore: validare a
     * metà composizione lascerebbe l'itinerario smontato sul database.</p>
     *
     * @return null se ogni coppia ha il suo arco, altrimenti la risposta di errore da dare
     *         (404 con l'elenco delle coppie scoperte, 400 se una stazione non esiste).
     */
    private Risposta verificaTratteEsistenti(List<String> stazioni) {
        List<String> descrizioni = new ArrayList<>();
        List<Map<String, String>> coppieMancanti = new ArrayList<>();
        Set<String> coppieGiaViste = new LinkedHashSet<>();

        for (int i = 0; i < stazioni.size() - 1; i++) {
            String partenzaId = stazioni.get(i);
            String arrivoId = stazioni.get(i + 1);

            // Un itinerario che ripassa sullo stesso arco non è rappresentabile: la chiave
            // primaria di Itinerario_Tratta è (id_itinerario, id_Tratta) e la colonna
            // "ordine" non ne fa parte, quindi la seconda riga violerebbe la PK.
            if (!coppieGiaViste.add(partenzaId + "->" + arrivoId)) {
                return errore(400, "L'itinerario percorre due volte la stessa tratta ("
                        + partenzaId + " -> " + arrivoId + "): non è una composizione ammessa, "
                        + "ogni collegamento può comparire una volta sola.");
            }

            Stazione partenza = repository.trovaStazione(partenzaId);
            Stazione arrivo = repository.trovaStazione(arrivoId);
            if (partenza == null || arrivo == null) {
                return errore(400, "Stazione inesistente: " + (partenza == null ? partenzaId : arrivoId));
            }
            // La tratta è ORIENTATA: l'arco A->B non vale come B->A.
            if (repository.trovaTrattaFra(partenzaId, arrivoId) == null) {
                String nomePartenza = partenza.nome != null ? partenza.nome : partenzaId;
                String nomeArrivo = arrivo.nome != null ? arrivo.nome : arrivoId;
                descrizioni.add(nomePartenza + " -> " + nomeArrivo);
                coppieMancanti.add(Map.of(
                        "partenzaId", partenzaId,
                        "arrivoId", arrivoId,
                        "partenzaNome", nomePartenza,
                        "arrivoNome", nomeArrivo));
            }
        }

        if (descrizioni.isEmpty()) {
            return null;
        }
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("errore", "Manca la tratta fra " + String.join(", ", descrizioni)
                + ". Registrala nella pagina Tratte prima di usarla in un itinerario.");
        corpo.put("coppieMancanti", coppieMancanti);
        return new Risposta(404, corpo);
    }

    /**
     * Per ogni coppia consecutiva di stazioni recupera la Tratta corrispondente (aggiornandone
     * il tempo di percorrenza se fornito) e crea le righe di Itinerario_Tratta con l'ordine
     * progressivo. Le tratte devono esistere: le crea l'amministratore dalla pagina Tratte.
     *
     * @return Una risposta di errore se una tratta non esiste, altrimenti null.
     */
    private Risposta componiItinerario(Itinerario itinerario, List<String> stazioni, List<Integer> travelTimes) {
        for (int i = 0; i < stazioni.size() - 1; i++) {
            String partenzaId = stazioni.get(i);
            String arrivoId = stazioni.get(i + 1);
            Integer tempo = (travelTimes != null && i < travelTimes.size()) ? travelTimes.get(i) : null;

            Tratta tratta = repository.trovaTrattaFra(partenzaId, arrivoId);
            if (tratta == null) {
                // Non ci si arriva passando dalla verifica: resta come rete di sicurezza
                // se la tratta viene cancellata fra il controllo e la composizione.
                return errore(404, "Manca la tratta fra " + partenzaId + " e " + arrivoId);
            }
            if (tempo != null) {
                // La Tratta è un ARCO FISICO della rete, condiviso fra più itinerari:
                // sovrascriverne il tempo da qui cambierebbe di nascosto anche gli altri
                // percorsi. Lo si aggiorna solo se nessun altro itinerario usa quell'arco;
                // altrimenti il tempo si modifica dalla pagina "Tratte elementari".
                long usataAltrove = repository.contaAltriItinerariCheUsano(tratta.id, itinerario.id);
                if (usataAltrove == 0) {
                    tratta.tempoPercorrenzaMinuti = tempo;
                    repository.aggiornaTratta(tratta);
                } else {
                    LOG.warning(String.format("⏱️ Tratta %s condivisa con altri %d itinerari: tempo di percorrenza lasciato a %d minuti",
                            tratta.id, usataAltrove, tratta.tempoPercorrenzaMinuti));
                }
            }

            ItinerarioTratta riga = new ItinerarioTratta();
            riga.id = new ItinerarioTratta.Id(itinerario.id, tratta.id);
            riga.ordine = i + 1;
            riga.itinerario = itinerario;
            riga.tratta = tratta;
            repository.salvaItinerarioTratta(riga);
        }
        return null;
    }

    /**
     * Controlla che il capolinea dichiarato sia uno dei due <b>estremi</b> dell'elenco. Una
     * fermata intermedia marcata come capolinea spezzerebbe l'itinerario in due senza dire
     * che ne è del pezzo restante.
     *
     * @return null se va bene (capolinea assente compreso), altrimenti la risposta 400.
     */
    private Risposta verificaCapolinea(List<String> stazioni, String capolinea) {
        String scelto = normalizzaCapolinea(capolinea);
        if (scelto == null || stazioni == null || stazioni.isEmpty()) {
            return null;
        }
        String primo = stazioni.get(0);
        String ultimo = stazioni.get(stazioni.size() - 1);
        if (scelto.equals(primo) || scelto.equals(ultimo)) {
            return null;
        }
        return errore(400, "Il capolinea deve essere il primo o l'ultimo nodo "
                + "dell'itinerario (" + primo + " o " + ultimo + "), non " + scelto);
    }

    /** Campo vuoto e campo assente sono la stessa cosa: nessun capolinea. */
    private String normalizzaCapolinea(String capolinea) {
        if (capolinea == null) return null;
        String pulito = capolinea.trim();
        return pulito.isEmpty() ? null : pulito;
    }

    /**
     * Assegna i treni indicati all'itinerario, sul database e in cache. Se
     * {@code sganciaAssenti} è true (caso PUT), i treni prima assegnati e non più presenti
     * vengono scollegati. Con {@code treniIds} nullo non si sgancia niente.
     */
    private void assegnaTreni(Itinerario itinerario, List<String> treniIds, boolean sganciaAssenti) {
        if (sganciaAssenti && treniIds != null) {
            for (Treno t : repository.treniDellItinerario(itinerario.id)) {
                if (!treniIds.contains(t.id)) {
                    repository.aggiornaItinerarioTreno(t.id, null);
                    Treno cache = statoRete.getTreno(t.id);
                    if (cache != null) {
                        cache.itinerario = null;
                        statoRete.aggiornaTreno(cache);
                    }
                }
            }
        }
        if (treniIds == null) return;
        for (String trenoId : treniIds) {
            if (repository.esisteTreno(trenoId)) {
                repository.aggiornaItinerarioTreno(trenoId, itinerario.id);
                Treno cache = statoRete.getTreno(trenoId);
                if (cache != null) {
                    cache.itinerario = itinerario;
                    statoRete.aggiornaTreno(cache);
                }
            }
        }
    }

    /** Costruisce il DTO della tratta/itinerario nel formato del frontend. */
    private Map<String, Object> trattaToDto(Itinerario it) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", it.id);

        // Trova le tratte ordinate per questo itinerario
        List<ItinerarioTratta> tratte = repository.tratteOrdinateDi(it.id);

        List<String> stazioni = new ArrayList<>();
        List<String> nomiStazioni = new ArrayList<>();
        List<Integer> travelTimes = new ArrayList<>();
        if (!tratte.isEmpty()) {
            stazioni.add(tratte.get(0).tratta.stazionePartenza.id);
            nomiStazioni.add(tratte.get(0).tratta.stazionePartenza.nome);
            for (ItinerarioTratta itTratta : tratte) {
                stazioni.add(itTratta.tratta.stazioneArrivo.id);
                nomiStazioni.add(itTratta.tratta.stazioneArrivo.nome);
                travelTimes.add(itTratta.tratta.tempoPercorrenzaMinuti != null ? itTratta.tratta.tempoPercorrenzaMinuti : 15);
            }
        }

        String nome = nomiStazioni.isEmpty() ? it.id : String.join("-", nomiStazioni);
        dto.put("nome", nome);
        dto.put("stazioni", stazioni);
        dto.put("travelTimes", travelTimes);
        dto.put("attivo", true);
        dto.put("capolinea", it.capolineaId);

        List<String> treniIds = new ArrayList<>();
        for (Treno t : repository.treniDellItinerario(it.id)) {
            treniIds.add(t.id);
        }
        dto.put("treniIds", treniIds);
        return dto;
    }

    private Map<String, Object> trattaElementoToDto(Tratta tratta) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", tratta.id);
        dto.put("stazionePartenzaId", tratta.stazionePartenza.id);
        dto.put("stazioneArrivoId", tratta.stazioneArrivo.id);
        dto.put("tempoPercorrenzaMinuti", tratta.tempoPercorrenzaMinuti != null ? tratta.tempoPercorrenzaMinuti : 15);
        return dto;
    }

    /**
     * Mappa il tipo di un guasto nel vocabolario del frontend, con la stessa regola che usa
     * il gestore degli eventi quando il guasto arriva dal campo.
     */
    private String tipoAllarmePerFrontend(Guasto g) {
        return AutomaGuasto.tipoPerFrontend(g.sorgenteTipo, g.tipo);
    }
}
