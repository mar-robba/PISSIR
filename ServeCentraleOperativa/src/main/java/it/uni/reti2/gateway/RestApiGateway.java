package it.uni.reti2.gateway;

import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.GuastiService;
import it.uni.reti2.elaboration.MonitoraggioStazioni;
import it.uni.reti2.elaboration.MonitoraggioTreni;
import it.uni.reti2.elaboration.NavigazioneItinerario;
import it.uni.reti2.elaboration.PrevisioneArrivi;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.ingestion.PubblicatoreAlert;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Nota sul database: qui viene usato, tutte le operazioni su di esso sono
 * automatiche usando le entità
 * RestApiGateway implementa le API REST (JAX-RS) utilizzate dal Frontend
 * (Dashboard Web) per l'interrogazione dello stato della rete e l'esecuzione
 * di comandi da parte degli operatori.sas
 */
@Path("/api") // definizione della radice
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RestApiGateway {

    private static final Logger LOG = Logger.getLogger(RestApiGateway.class);

    /** Lunghezza massima del nome del convoglio: è la chiave primaria Treni.id_convoglio VARCHAR(50). */
    private static final int LUNGHEZZA_MAX_NOME_CONVOGLIO = 50;

    /** Referenza alla logica e cache di sistema. */
    @Inject
    TrafficLogicEngine statoRete;

    /** Unico punto da cui questa classe legge e scrive il database: nessuna query qui dentro. */
    @Inject
    RailwayRepository repository;

    /**
     * Il calcolo dei ritardi e delle previsioni di arrivo. Qui serve per due cose: il
     * tabellone dei treni attesi in una stazione e l'avviso, quando l'amministratore
     * cambia la rete, che i percorsi memorizzati non valgono più.
     */
    @Inject
    PrevisioneArrivi previsioni;

    /**
     * Serve per rimandare al frontend i cambi di stato delle stazioni decisi da qui
     * (modifica dall'anagrafica, invio della squadra): sono modifiche che nessun
     * heartbeat annuncerà mai, quindi senza broadcast la schermata resterebbe indietro.
     */
    @Inject
    NotificatoreRealtime notificatore;

    /**
     * L'uscita verso il campo: i comandi che l'operatore impartisce da questa API
     * (soppressione di una corsa, itinerario rifatto) devono arrivare ai nodi.
     */
    @Inject
    PubblicatoreAlert pubblicatore;

    /**
     * Il ciclo di vita dei guasti. Chiudere un allarme non è solo scrivere una riga:
     * rimette in servizio la sorgente, chiude i guasti che quella sorgente aveva
     * provocato altrove e avvisa il campo. Sono regole che valgono identiche quando a
     * chiudere il guasto è il campo stesso, quindi non stanno in un endpoint REST.
     */
    @Inject
    GuastiService guasti;

    /** I comandi dell'operatore sui convogli (soppressione di una corsa). */
    @Inject
    MonitoraggioTreni treni;

    /** I comandi dell'operatore sulle stazioni (invio della squadra di manutenzione). */
    @Inject
    MonitoraggioStazioni stazioni;

    /** La lettura dell'itinerario per il digital twin di bordo. */
    @Inject
    NavigazioneItinerario navigazione;

    // ──────────────────────────────────────────────────────────────
    // DTO usati per il dialogo col frontend (evitano di esporre
    // direttamente le entità JPA nelle richieste di scrittura).
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
     * frontend (e allora sul DB non è stato modificato niente), oppure l'entità appena
     * scritta. Serve perché la transazione viene chiusa PRIMA di toccare la cache in RAM:
     * così la cache resta l'immagine esatta della tabella Treni anche se il commit fallisce.
     */
    private static class EsitoScritturaTreno {
        Response errore;
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
    }

    /** DTO della singola tratta fisica tra due stazioni, distinto da un itinerario. */
    public static class TrattaElementoDTO {
        public String id;
        public String stazionePartenzaId;
        public String stazioneArrivoId;
        public Integer tempoPercorrenzaMinuti;
    }

    /**
     * Fornisce indicatori chiave di prestazione (KPI) per la dashboard di riepilogo.
     * @return Statistiche sommarie su treni e stazioni nel formato atteso dal frontend.
     */
    @GET
    @Path("/dashboard")
    public Response getDashboard() {
        return Response.ok(statoRete.kpiDashboard()).build();
    }

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    /**
     * Elenca tutte le stazioni con le relative informazioni cacheate in RAM.
     * @return Lista di Stazioni.
     */
    @GET
    @Path("/stazioni")
    public List<Stazione> getStazioni() {
        return statoRete.getTutteStazioni();
    }

    /**
     * Crea una nuova stazione persistendola sul DB e inserendola in cache.
     * @param dto Dati della stazione dal corpo JSON.
     * @return 201 Created con la stazione creata.
     */
    @POST
    @Path("/stazioni")
    @Transactional
    public Response createStazione(StazioneDTO dto) {
        if (dto == null || dto.id == null || dto.id.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "id mancante")).build();
        }
        if (repository.trovaStazione(dto.id) != null) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Stazione già esistente")).build();
        }
        Stazione stazione = new Stazione(
                dto.id,
                dto.nome != null ? dto.nome : dto.id,
                dto.latitudine != null ? dto.latitudine : 0.0,
                dto.longitudine != null ? dto.longitudine : 0.0,
                dto.binari != null ? dto.binari : 1);
        stazione.stato = dto.stato != null
                ? StatiCanonici.stazioneDallOperatore(dto.stato)
                : StatiCanonici.STAZIONE_OFFLINE;
        repository.salvaStazione(stazione);
        statoRete.aggiornaStazione(stazione);
        return Response.status(Response.Status.CREATED).entity(stazione).build();
    }

    /**
     * Aggiorna una stazione esistente: vengono modificati solo i campi non-null del body.
     * @param id ID della stazione.
     * @param dto Campi da aggiornare.
     * @return 200 OK oppure 404.
     */
    @PUT
    @Path("/stazioni/{id}")
    @Transactional
    public Response updateStazione(@PathParam("id") String id, StazioneDTO dto) {
        Stazione dbStazione = repository.trovaStazione(id);
        if (dbStazione == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (dto.nome != null) dbStazione.nome = dto.nome;
        if (dto.latitudine != null) dbStazione.latitudine = dto.latitudine;
        if (dto.longitudine != null) dbStazione.longitudine = dto.longitudine;
        if (dto.binari != null) dbStazione.binari = dto.binari;

        // Riflette le modifiche anche sulla cache RAM (stato incluso, se fornito)
        Stazione cache = statoRete.getStazione(id);
        if (cache == null) cache = dbStazione;
        if (dto.nome != null) cache.nome = dto.nome;
        if (dto.latitudine != null) cache.latitudine = dto.latitudine;
        if (dto.longitudine != null) cache.longitudine = dto.longitudine;
        if (dto.binari != null) cache.binari = dto.binari;
        if (dto.stato != null) cache.stato = StatiCanonici.stazioneDallOperatore(dto.stato);
        statoRete.aggiornaStazione(cache);
        if (dto.stato != null) {
            // Anche lo stato cambiato a mano dall'amministratore deve arrivare subito
            // alle altre schermate aperte, non al prossimo ricaricamento.
            notificatore.statoStazione(cache);
        }

        return Response.ok(cache).build();
    }

    /**
     * Elimina una stazione. Se è referenziata da qualche tratta risponde 409, altrimenti
     * chiude la partita con i transiti ancora aperti e cancella la riga di anagrafica.
     *
     * <p><b>Lo storico non si tocca</b> (RF02.7): le righe di Storico_Transiti,
     * Storico_Stato_Stazioni ed eventi_stazioni restano dov'erano. Prima venivano
     * cancellate, ma non era una scelta: era l'unico modo di far passare la DELETE finché
     * quelle tabelle avevano una chiave esterna verso Stazione. Adesso che gli storici
     * portano dentro l'id e il nome della stazione, la storia di una stazione dismessa
     * resta consultabile — che è esattamente quello che il requisito chiede.</p>
     *
     * @param id ID della stazione.
     * @return 204 No Content, 404 o 409.
     */
    @DELETE
    @Path("/stazioni/{id}")
    @Transactional
    public Response deleteStazione(@PathParam("id") String id) {
        Stazione dbStazione = repository.trovaStazione(id);
        if (dbStazione == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        long tratteCollegate = repository.contaTratteConStazione(id);
        if (tratteCollegate > 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Stazione referenziata da " + tratteCollegate + " tratte: eliminarle prima"))
                    .build();
        }
        // Restano da cancellare solo i transiti VIVI: Transiti è una tabella dello stato
        // corrente (il treno dentro la stazione) e ha davvero una chiave esterna verso
        // Stazione. Lo storico invece sopravvive alla stazione.
        repository.eliminaTransitiDiStazione(id);
        repository.eliminaStazione(dbStazione);
        statoRete.rimuoviStazione(id);
        return Response.noContent().build();
    }

    /**
     * I convogli che questa stazione deve aspettarsi, con l'orario previsto di ciascuno:
     * è la schermata che il testo del progetto mette fra i compiti del tecnico
     * ("quali treni ci si aspetta in ingresso e in uscita in una certa stazione").
     *
     * <p>La risposta la calcola la Centrale sull'itinerario dei treni, non il browser
     * sulla loro posizione istantanea: prima la pagina Stazioni filtrava i convogli con
     * {@code prossimaStazione == stazione}, quindi vedeva solo chi stava già arrivando
     * dalla tratta adiacente e non chi sarebbe passato di lì qualche fermata più avanti.</p>
     *
     * @param id Identificativo della stazione.
     * @return Elenco ordinato per tempo mancante, oppure 404 se la stazione non esiste.
     */
    @GET
    @Path("/stazioni/{id}/treni-attesi")
    public Response getTreniAttesi(@PathParam("id") String id) {
        if (statoRete.getStazione(id) == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Stazione inesistente: " + id)).build();
        }
        return Response.ok(previsioni.treniAttesi(id)).build();
    }

    // ──────────────────────────────────────────────────────────────
    // TRENI
    // ──────────────────────────────────────────────────────────────

    /**
     * Elenca tutti i treni e la loro telemetria istantanea (dalla RAM).
     * @return Lista di Treni.
     */
    @GET
    @Path("/treni")
    public List<Treno> getTreni() {
        return statoRete.getTuttiTreni();
    }

    /**
     * Crea un nuovo treno (stato di default "fermo") ed eventualmente lo assegna a un itinerario.
     * Il campo {@code id} del corpo è il nome del convoglio digitato dall'amministratore:
     * diventa la chiave primaria del treno, quindi è l'unica occasione per deciderlo.
     * @param dto Dati del treno dal corpo JSON.
     * @return 201 Created con il treno creato, 400 se il nome manca o è troppo lungo,
     *         409 se un convoglio con quel nome esiste già.
     */
    @POST
    @Path("/treni")
    public Response createTreno(TrenoDTO dto) {
        String nomeConvoglio = dto != null && dto.id != null ? dto.id.trim() : "";
        if (nomeConvoglio.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("errore", "Nome del convoglio mancante")).build();
        }
        // La chiave primaria è VARCHAR(50): meglio un 400 parlante che un errore SQL.
        if (nomeConvoglio.length() > LUNGHEZZA_MAX_NOME_CONVOGLIO) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("errore", "Il nome del convoglio non può superare i "
                            + LUNGHEZZA_MAX_NOME_CONVOGLIO + " caratteri")).build();
        }
        // La transazione si apre e si CHIUDE qui: la cache viene toccata solo dopo il
        // commit (vedi nota su EsitoScritturaTreno).
        EsitoScritturaTreno esito = QuarkusTransaction.requiringNew().call(() -> creaTrenoSuDb(nomeConvoglio, dto));
        if (esito.errore != null) {
            return esito.errore;
        }
        // ultimoAggiornamento resta null: il convoglio appena creato non ha ancora
        // trasmesso niente, ed è proprio quello che il watchdog usa per non aprire un
        // guasto "treno fermo" su un treno il cui processo non è nemmeno acceso.
        statoRete.aggiornaTreno(esito.treno);
        return Response.status(Response.Status.CREATED).entity(esito.treno).build();
    }

    /** Inserisce il treno nella tabella Treni. Gira dentro la transazione aperta dal chiamante. */
    private EsitoScritturaTreno creaTrenoSuDb(String nomeConvoglio, TrenoDTO dto) {
        EsitoScritturaTreno esito = new EsitoScritturaTreno();
        if (repository.trovaTreno(nomeConvoglio) != null) {
            esito.errore = Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Esiste già un treno con il nome '" + nomeConvoglio + "'")).build();
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
     * Aggiorna stato e itinerario di un treno esistente. Se cambia l'itinerario, il digital
     * twin viene avvisato con un evento ITINERARIO_AGGIORNATO via MQTT.
     *
     * <p>Il nome del convoglio NON si aggiorna qui: è la chiave primaria della tabella Treni
     * e il riferimento delle FK degli storici, quindi è immutabile per costruzione. Per
     * cambiarlo si elimina il treno e si ricrea (la UI infatti mostra il campo Convoglio in
     * sola lettura in modifica).</p>
     *
     * @param id Nome del convoglio da aggiornare.
     * @param dto Campi da aggiornare (stato, itinerario).
     * @return 200 OK, 404 se il treno non esiste, 400 se si prova a cambiargli il nome.
     */
    @PUT
    @Path("/treni/{id}")
    public Response updateTreno(@PathParam("id") String id, TrenoDTO dto) {
        TrenoDTO dati = dto != null ? dto : new TrenoDTO();
        if (dati.id != null && !dati.id.trim().isEmpty() && !dati.id.trim().equals(id)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("errore", "Il nome del convoglio identifica il treno e non è "
                            + "modificabile: eliminare il treno e ricrearlo con il nome nuovo")).build();
        }
        // La scrittura sul DB gira in una transazione che si apre e si chiude qui dentro,
        // così la cache RAM viene allineata SOLO a commit avvenuto: se l'allineamento sta
        // dentro la stessa transazione, un rollback lascia in memoria (e quindi nelle
        // risposte REST) valori che sul database non sono mai stati scritti.
        EsitoScritturaTreno esito = QuarkusTransaction.requiringNew().call(() -> aggiornaTrenoSuDb(id, dati));
        if (esito.errore != null) {
            return esito.errore;
        }

        // Allinea la cache RAM ai valori effettivamente scritti sul database,
        // conservando la telemetria volatile già presente in memoria.
        Treno cache = statoRete.getTreno(id);
        if (cache == null) {
            cache = esito.treno;
            cache.ultimoAggiornamento = Instant.now();
        }
        cache.stato = esito.treno.stato;
        cache.itinerario = esito.treno.itinerario;
        statoRete.aggiornaTreno(cache);

        if (esito.itinerarioCambiato) {
            pubblicatore.pubblicaItinerarioAggiornato(id);
        }
        return Response.ok(cache).build();
    }

    /** Applica sul database i campi presenti nel DTO. Gira dentro la transazione aperta dal chiamante. */
    private EsitoScritturaTreno aggiornaTrenoSuDb(String id, TrenoDTO dto) {
        EsitoScritturaTreno esito = new EsitoScritturaTreno();
        Treno dbTreno = repository.trovaTreno(id);
        if (dbTreno == null) {
            esito.errore = Response.status(Response.Status.NOT_FOUND).build();
            return esito;
        }
        if (dto.stato != null) dbTreno.stato = StatiCanonici.trenoDallOperatore(dto.stato);

        String itinerarioId = estraiItinerarioId(dto);
        if (itinerarioId != null) {
            String attuale = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
            if (!itinerarioId.equals(attuale)) {
                Itinerario itinerario = repository.trovaItinerario(itinerarioId);
                if (itinerario == null) {
                    esito.errore = Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("errore", "Itinerario inesistente: " + itinerarioId)).build();
                    return esito;
                }
                dbTreno.itinerario = itinerario;
                esito.itinerarioCambiato = true;
            }
        }
        esito.treno = dbTreno;
        return esito;
    }

    /**
     * Elimina un treno chiudendo i suoi transiti ancora aperti e notificando lo STOP
     * via MQTT.
     *
     * <p><b>Lo storico non si tocca</b> (RF02.7), come per le stazioni: i passaggi, i
     * cambi di stato e gli itinerari percorsi da quel convoglio sono cose successe e
     * restano scritte. La cascata di prima serviva solo a non violare le chiavi esterne,
     * che adesso non ci sono più.</p>
     *
     * @param id ID del convoglio.
     * @return 204 No Content oppure 404.
     */
    @DELETE
    @Path("/treni/{id}")
    @Transactional
    public Response deleteTreno(@PathParam("id") String id) {
        Treno dbTreno = repository.trovaTreno(id);
        if (dbTreno == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Solo i transiti vivi: è la tabella dello stato corrente e ha la chiave esterna.
        repository.eliminaTransitiDiTreno(id);
        repository.eliminaTreno(dbTreno);
        statoRete.rimuoviTreno(id);
        previsioni.dimenticaTreno(id);

        pubblicatore.pubblicaStop(id, "Treno eliminato da operatore");
        return Response.noContent().build();
    }

    // ──────────────────────────────────────────────────────────────
    // TRATTE (itinerari per il frontend)
    // ──────────────────────────────────────────────────────────────

    // TRATTE ELEMENTARI (archi fisici della rete)
    // ──────────────────────────────────────────────────────────────

    @GET
    @Path("/tratte-elementari")
    @Transactional
    public List<Map<String, Object>> getTratteElementari() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tratta tratta : repository.tutteLeTratte()) result.add(trattaElementoToDto(tratta));
        return result;
    }

    @POST
    @Path("/tratte-elementari")
    @Transactional
    public Response createTrattaElemento(TrattaElementoDTO dto) {
        if (dto == null || dto.id == null || dto.id.isBlank()
                || dto.stazionePartenzaId == null || dto.stazioneArrivoId == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "id e stazioni di partenza/arrivo sono obbligatori")).build();
        }
        if (repository.trovaTratta(dto.id) != null) return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta già esistente")).build();
        Stazione partenza = repository.trovaStazione(dto.stazionePartenzaId);
        Stazione arrivo = repository.trovaStazione(dto.stazioneArrivoId);
        if (partenza == null || arrivo == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di partenza o arrivo inesistente")).build();
        Tratta tratta = new Tratta();
        tratta.id = dto.id;
        tratta.stazionePartenza = partenza;
        tratta.stazioneArrivo = arrivo;
        tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti != null ? dto.tempoPercorrenzaMinuti : 15;
        repository.salvaTratta(tratta);
        previsioni.itinerariCambiati();
        return Response.status(Response.Status.CREATED).entity(trattaElementoToDto(tratta)).build();
    }

    @PUT
    @Path("/tratte-elementari/{id}")
    @Transactional
    public Response updateTrattaElemento(@PathParam("id") String id, TrattaElementoDTO dto) {
        Tratta tratta = repository.trovaTratta(id);
        if (tratta == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (dto.stazionePartenzaId != null) {
            Stazione s = repository.trovaStazione(dto.stazionePartenzaId);
            if (s == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di partenza inesistente")).build();
            tratta.stazionePartenza = s;
        }
        if (dto.stazioneArrivoId != null) {
            Stazione s = repository.trovaStazione(dto.stazioneArrivoId);
            if (s == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di arrivo inesistente")).build();
            tratta.stazioneArrivo = s;
        }
        if (dto.tempoPercorrenzaMinuti != null) tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti;
        // Il tempo di percorrenza è il dato con cui si calcolano gli orari previsti:
        // cambiarlo senza avvisare lascerebbe la Centrale a prevedere sui minuti vecchi.
        previsioni.itinerariCambiati();
        return Response.ok(trattaElementoToDto(tratta)).build();
    }

    @DELETE
    @Path("/tratte-elementari/{id}")
    @Transactional
    public Response deleteTrattaElemento(@PathParam("id") String id) {
        Tratta tratta = repository.trovaTratta(id);
        if (tratta == null) return Response.status(Response.Status.NOT_FOUND).build();
        long usi = repository.contaItinerariCheUsano(id);
        if (usi > 0) return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta usata da " + usi + " itinerari: modificare prima gli itinerari")).build();
        // Il rifiuto resta per la tabella viva Transiti, che verso Tratte ha davvero una
        // chiave esterna. Sui transiti STORICI invece è caduto con RF02.7: la riga di
        // storico si porta dentro l'id e la descrizione della tratta, quindi togliere
        // l'arco dalla rete non cancella più il percorso dei passaggi già avvenuti.
        if (repository.contaTransitiSuTratta(id) > 0) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta presente nei transiti e non eliminabile")).build();
        }
        // Treni.PosizioneAttualeTrattaOStazione è una chiave esterna verso Tratte: senza
        // questo controllo la DELETE partiva lo stesso, Postgres la rifiutava per violazione
        // di vincolo e l'amministratore si prendeva un 500 con l'eccezione invece del 409
        // con la spiegazione che chiede RF01.3.5.
        long treniFermiQui = repository.contaTreniInPosizioneSuTratta(id);
        if (treniFermiQui > 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Tratta usata come posizione corrente di " + treniFermiQui
                            + " convogli: spostarli prima di eliminarla"))
                    .build();
        }
        repository.eliminaTratta(tratta);
        previsioni.itinerariCambiati();
        return Response.noContent().build();
    }

    /**
     * Recupera l'elenco degli itinerari o tratte dal Database persistente (Panache ORM).
     * @return Lista degli Itinerari nel formato atteso dal frontend.
     */
    @GET
    @Path("/tratte")
    @Transactional
    public List<Map<String, Object>> getTratte() {
        List<Itinerario> itinerari = repository.tuttiGliItinerari();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Itinerario it : itinerari) {
            result.add(trattaToDto(it));
        }
        return result;
    }

    /**
     * Crea un nuovo Itinerario a partire dalla lista ordinata di stazioni fornita dal frontend.
     * Per ogni coppia consecutiva riusa la Tratta esistente o ne crea una nuova con il
     * tempo di percorrenza indicato in travelTimes; assegna eventualmente i treni indicati.
     * @param dto DTO della tratta fornito nel corpo JSON della richiesta.
     * @return HTTP 201 Created con il DTO nello stesso formato del GET.
     */
    @POST
    @Path("/tratte")
    @Transactional//le operazioni di lettura sono libere, ma quelle di scrittura (insert, update, delete) devono essere annotate con @Transactional.
    public Response createTratta(TrattaDTO dto) {
        if (dto == null || dto.stazioni == null || dto.stazioni.size() < 2) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("errore", "Servono almeno due stazioni")).build();
        }
        String id = (dto.id != null && !dto.id.isEmpty()) ? dto.id
                : "IT-" + UUID.randomUUID().toString().substring(0, 8);
        if (repository.trovaItinerario(id) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Itinerario già esistente: " + id)).build();
        }
        // Prima di scrivere qualsiasi cosa: se manca anche un solo arco non si crea niente.
        Response tratteMancanti = verificaTratteEsistenti(dto.stazioni);
        if (tratteMancanti != null) return tratteMancanti;

        Itinerario itinerario = new Itinerario();
        itinerario.id = id;
        repository.salvaItinerario(itinerario);

        Response errore = componiItinerario(itinerario, dto.stazioni, dto.travelTimes);
        if (errore != null) return errore;

        assegnaTreni(itinerario, dto.treniIds, false);
        previsioni.itinerariCambiati();
        return Response.status(Response.Status.CREATED).entity(trattaToDto(itinerario)).build();
    }

    /**
     * Modifica un itinerario preesistente: le associazioni con le tratte vengono
     * cancellate e ricreate dalla nuova lista di stazioni; i treni non più presenti
     * vengono sganciati e ogni treno assegnato riceve ITINERARIO_AGGIORNATO.
     * @param id L'identificativo univoco della tratta.
     * @param dto DTO con i dati nuovi.
     * @return 200 OK oppure 404 Not Found.
     */
    @PUT
    @Path("/tratte/{id}")
    @Transactional
    public Response updateTratta(@PathParam("id") String id, TrattaDTO dto) {
        Itinerario itinerario = repository.trovaItinerario(id);
        if (itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (dto.stazioni != null) {
            // Un elenco troppo corto è un rifiuto esplicito, non un aggiornamento da
            // ignorare: prima si rispondeva 200 senza cambiare le stazioni e il form
            // credeva di aver salvato. Se invece "stazioni" non c'è proprio nel JSON
            // (null) la PUT tocca solo i treni assegnati e l'itinerario resta com'è.
            if (dto.stazioni.size() < 2) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("errore", "Servono almeno due stazioni")).build();
            }
            // La verifica sta PRIMA della delete: se una coppia è scoperta si esce con 404
            // senza aver smontato niente. Al contrario l'itinerario resterebbe a pezzi,
            // perché la transazione va in commit lo stesso quando si torna un errore.
            Response tratteMancanti = verificaTratteEsistenti(dto.stazioni);
            if (tratteMancanti != null) return tratteMancanti;

            repository.eliminaTratteDellItinerario(id);
            Response errore = componiItinerario(itinerario, dto.stazioni, dto.travelTimes);
            if (errore != null) return errore;
        }

        // Gli id dei treni assegnati vanno letti PRIMA di toccare le assegnazioni:
        // assegnaTreni(..., true) sgancia quelli non più in elenco e una query fatta
        // dopo non li troverebbe più. Erano proprio i treni sganciati a non ricevere
        // mai ITINERARIO_AGGIORNATO, continuando a girare sulla tratta vecchia.
        Set<String> daNotificare = new LinkedHashSet<>();
        for (Treno t : Treno.<Treno>list("itinerario.id", id)) {
            daNotificare.add(t.id);
        }
        // "treniIds" assente nel JSON vuol dire "le assegnazioni non le sto toccando",
        // non "sgancia tutti": l'editor degli itinerari non ha nessun campo per sceglierle
        // e mandava indietro la lista che aveva scaricato all'avvio, sganciando i treni
        // assegnati nel frattempo dalla pagina Amministrazione. Adesso quel form non manda
        // più il campo e qui l'assenza viene rispettata.
        if (dto.treniIds != null) {
            assegnaTreni(itinerario, dto.treniIds, true);
            daNotificare.addAll(dto.treniIds); // anche i treni appena agganciati
        }

        // Ogni treno coinvolto (vecchio o nuovo) deve ricaricare l'itinerario dal server
        for (String trenoId : daNotificare) {
            pubblicatore.pubblicaItinerarioAggiornato(trenoId);
        }
        previsioni.itinerariCambiati();
        return Response.ok(trattaToDto(itinerario)).build();
    }

    /**
     * Elimina un itinerario: sgancia i treni assegnati, cancella le associazioni
     * con le tratte e i record storici collegati, poi rimuove l'itinerario.
     * @param id L'identificativo della tratta.
     * @return 204 No Content.
     */
    @DELETE
    @Path("/tratte/{id}")
    @Transactional
    public Response deleteTratta(@PathParam("id") String id) {
        Itinerario itinerario = repository.trovaItinerario(id);
        if (itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Sgancia i treni (DB + cache) tenendo da parte i loro id: vanno avvisati.
        // Senza la notifica il digital twin continuava a percorrere un itinerario che non
        // esisteva più — ce l'ha già in memoria e non lo ricarica mai da solo — pubblicando
        // passaggi e transiti che la Centrale registrava regolarmente. La PUT lo faceva già
        // (vedi updateTratta), la DELETE era rimasta indietro.
        Set<String> daNotificare = new LinkedHashSet<>();
        for (Treno t : Treno.<Treno>list("itinerario.id", id)) {
            t.itinerario = null;
            daNotificare.add(t.id);
            Treno cache = statoRete.getTreno(t.id);
            if (cache != null) {
                cache.itinerario = null;
                statoRete.aggiornaTreno(cache);
            }
        }
        repository.eliminaTratteDellItinerario(id);
        // Storico_Itinerari resta: i viaggi già fatti su questo itinerario sono avvenuti
        // e la riga di storico si porta dentro il percorso (RF02.7).
        repository.eliminaItinerario(itinerario);

        // Ricevuto ITINERARIO_AGGIORNATO il twin scarta l'itinerario in memoria e ne chiede
        // uno nuovo: non essendocene più resta fermo e visibile in attesa di essere soppresso.
        for (String trenoId : daNotificare) {
            pubblicatore.pubblicaItinerarioAggiornato(trenoId);
        }
        previsioni.itinerariCambiati();
        return Response.noContent().build();
    }

    // ──────────────────────────────────────────────────────────────
    // TRANSITI E ALLARMI
    // ──────────────────────────────────────────────────────────────

    /**
     * Restituisce lo storico persistito dei passaggi ai sensori delle stazioni,
     * nel formato atteso dal frontend (max 200 eventi, dal più recente).
     * @return Lista di transiti storicizzati.
     */
    @GET
    @Path("/transiti")
    @Transactional
    public List<Map<String, Object>> getTransiti() {
        List<StoricoTransito> transiti = repository.ultimiTransitiStorici(200);
        List<Map<String, Object>> result = new ArrayList<>();
        for (StoricoTransito t : transiti) {
            Instant timestamp = t.tempoUscita != null ? t.tempoUscita : t.tempoEntrata;
            // Il ritardo è quello CONGELATO al momento del passaggio, non quello che il
            // convoglio ha adesso: leggerlo dalla cache faceva vedere lo stesso numero su
            // tutti i transiti dello stesso treno, e degli zeri dopo ogni riavvio.
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
        return result;
    }

    /**
     * Restituisce tutti gli allarmi persistiti sul database (storico guasti)
     * nel formato atteso dal frontend.
     * @return Lista di allarmi.
     */
    @GET
    @Path("/allarmi")
    @Transactional
    public List<Map<String, Object>> getAllarmi() {
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
        return result;
    }

    /**
     * Chiude un allarme perché un operatore di centrale dichiara il guasto risolto.
     *
     * <p>Non è una semplice scrittura: la sorgente torna in servizio (una stazione
     * ridiventa operativa se non le resta nessun altro guasto aperto, un convoglio smette
     * di essere "rotto"), si chiudono i guasti che quel convoglio aveva provocato altrove
     * (RF02.1.2.2.1) e il campo viene avvisato con un RESOLVED, che è ciò che libera i
     * treni trattenuti. Tutto questo è il ciclo di vita del guasto e vive in
     * {@link GuastiService}: qui resta la traduzione fra HTTP e dominio.</p>
     *
     * <p>Niente {@code @Transactional} su questo metodo: la transazione la apre e la
     * chiude il servizio, che annuncia la risoluzione solo a commit avvenuto. Con
     * l'annotazione qui il RESOLVED partiva verso i treni <em>prima</em> del commit.</p>
     *
     * @param id Identificativo del guasto.
     * @return Il guasto aggiornato, oppure 404.
     */
    @POST
    @Path("/allarmi/{id}/risolvi")
    public Response risolviAllarme(@PathParam("id") String id) {
        Guasto risolto = guasti.risolviSuComandoOperatore(id);
        if (risolto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(risolto).build();
    }

    /**
     * Sopprime la corsa di un treno su richiesta dell'operatore: il convoglio riceve il
     * comando di frenata definitiva e la sua corsa esce dall'orario.
     *
     * @param id L'ID del treno da fermare.
     * @return Stato del treno aggiornato, 404 se non esiste, 400 se non è fermo in stazione.
     */
    @POST
    @Path("/treni/{id}/sopprimi")
    public Response sopprimiTreno(@PathParam("id") String id) {
        MonitoraggioTreni.EsitoComando esito = treni.sopprimi(id);
        if (!esito.trovato()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (esito.errore() != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("errore", esito.errore())).build();
        }
        return Response.ok(esito.treno()).build();
    }

    /**
     * Manda una squadra di manutenzione a una stazione guasta (RF01.4.1).
     *
     * <p>L'intervento si apre e basta: la stazione passa a MANUTENZIONE e ci resta, i
     * guasti aperti restano aperti e il nodo continua a trattenere i treni in ingresso.
     * A chiudere l'intervento è {@code POST /allarmi/{id}/risolvi}, che è l'unico punto in
     * cui la stazione torna ONLINE (RF01.4.2).</p>
     *
     * @param id L'ID della stazione.
     * @return Stato della stazione aggiornato, 404 se non esiste, 409 se non ha guasti aperti.
     */
    @POST
    @Path("/stazioni/{id}/manutenzione")
    public Response dispacciaManutenzione(@PathParam("id") String id) {
        MonitoraggioStazioni.EsitoInvioSquadra esito = stazioni.inviaSquadra(id);
        return switch (esito) {
            case NON_TROVATA -> Response.status(Response.Status.NOT_FOUND).build();
            case NESSUN_GUASTO_APERTO -> Response.status(Response.Status.CONFLICT).build();
            case SQUADRA_INVIATA -> Response.ok(statoRete.getStazione(id)).build();
        };
    }

    //------ da riordinare ma per comodità lascerò così
    // ──────────────────────────────────────────────────────────────
    // SUPPORTO AL DIGITAL TWIN DEI TRENI
    // ──────────────────────────────────────────────────────────────

    /**
     * Restituisce l'itinerario completo di un treno con coordinate e tempi di
     * percorrenza: è la "mappa di viaggio" scaricata dal digital twin al boot.
     * @param id ID del convoglio.
     * @return Itinerario con la sequenza di stazioni, oppure 404.
     */
    @GET
    @Path("/treni/{id}/itinerario")
    @Transactional
    public Response getItinerarioTreno(@PathParam("id") String id) {
        Treno treno = repository.trovaTreno(id);
        if (treno == null || treno.itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Treno inesistente o senza itinerario")).build();
        }
        List<ItinerarioTratta> tratte = repository.tratteOrdinateDi(treno.itinerario.id);
        if (tratte.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Itinerario senza tratte")).build();
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
        return Response.ok(result).build();
    }

    /**
     * Calcola la prossima stazione dell'itinerario di un treno rispetto a una
     * stazione data e alla direzione di marcia (A = andata, R = ritorno).
     * @return {"prossimaStazione":{"id","nome"}} oppure {"prossimaStazione":null} al capolinea.
     */
    @GET
    @Path("/prossima-stazione")
    @Transactional
    public Response getProssimaStazione(@QueryParam("treno") String trenoId,
                                        @QueryParam("stazione") String stazioneId,
                                        @QueryParam("direzione") @DefaultValue("A") String direzione) {
        Treno treno = trenoId != null ? repository.trovaTreno(trenoId) : null;
        if (treno == null || treno.itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Treno inesistente o senza itinerario")).build();
        }
        // Il conto lo fa la stessa classe che lo fa per l'ingestione: leggere un
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
        return Response.ok(result).build();
    }

    // ──────────────────────────────────────────────────────────────
    // METODI DI SUPPORTO PRIVATI
    // ──────────────────────────────────────────────────────────────

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

    /** Costruisce la tappa {id,nome,latitudine,longitudine,tempoVersoProssimaMinuti}. */
    private Map<String, Object> tappaItinerario(Stazione stazione, int tempoVersoProssima) {
        Map<String, Object> tappa = new HashMap<>();
        tappa.put("id", stazione.id);
        tappa.put("nome", stazione.nome);
        tappa.put("latitudine", stazione.latitudine != null ? stazione.latitudine : 0.0);
        tappa.put("longitudine", stazione.longitudine != null ? stazione.longitudine : 0.0);
        tappa.put("tempoVersoProssimaMinuti", tempoVersoProssima);
        return tappa;
    }


    /**
     * Controlla che ogni coppia di stazioni consecutive dell'elenco abbia già la sua tratta
     * registrata in rete. Copre in un colpo solo tutti i modi in cui l'elenco può cambiare
     * (stazione aggiunta, tolta o spostata di ordine): quando si sposta una stazione cambiano
     * anche le coppie dei suoi vicini, e qui vengono guardate tutte.
     *
     * <p><strong>Va chiamato prima di toccare Itinerario_Tratta.</strong> La {@code @Transactional}
     * fa rollback solo sulle eccezioni, non quando il metodo torna una Response di errore:
     * validare a metà composizione lascerebbe l'itinerario smontato sul database.</p>
     *
     * @return null se ogni coppia ha il suo arco, altrimenti la Response di errore da restituire
     *         (404 con l'elenco delle coppie scoperte, 400 se una stazione non esiste).
     */
    private Response verificaTratteEsistenti(List<String> stazioni) {
        List<String> descrizioni = new ArrayList<>();
        List<Map<String, String>> coppieMancanti = new ArrayList<>();
        Set<String> coppieGiaViste = new LinkedHashSet<>();

        for (int i = 0; i < stazioni.size() - 1; i++) {
            String partenzaId = stazioni.get(i);
            String arrivoId = stazioni.get(i + 1);

            // Un itinerario che ripassa sullo stesso arco non è rappresentabile: la chiave
            // primaria di Itinerario_Tratta è (id_itinerario, id_Tratta) e la colonna
            // "ordine" non ne fa parte, quindi la seconda riga violerebbe la PK e la
            // richiesta finirebbe in 500 senza spiegare niente. Meglio un rifiuto parlante.
            if (!coppieGiaViste.add(partenzaId + "->" + arrivoId)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("errore", "L'itinerario percorre due volte la stessa tratta ("
                                + partenzaId + " -> " + arrivoId + "): non è una composizione ammessa, "
                                + "ogni collegamento può comparire una volta sola."))
                        .build();
            }

            Stazione partenza = repository.trovaStazione(partenzaId);
            Stazione arrivo = repository.trovaStazione(arrivoId);
            if (partenza == null || arrivo == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("errore", "Stazione inesistente: " + (partenza == null ? partenzaId : arrivoId)))
                        .build();
            }
            // La tratta è ORIENTATA: l'arco A->B non vale come B->A, e infatti in rete
            // i due versi sono due righe distinte.
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
        return Response.status(Response.Status.NOT_FOUND).entity(corpo).build();
    }

    /**
     * Per ogni coppia consecutiva di stazioni recupera la Tratta corrispondente (aggiornandone
     * il tempo di percorrenza se fornito) e crea le righe di Itinerario_Tratta con l'ordine
     * progressivo. Le tratte devono esistere: le crea l'amministratore dalla pagina Tratte,
     * non questo metodo, perché un arco inventato qui sarebbe un collegamento fisico che in
     * rete non c'è. La verifica la fa {@link #verificaTratteEsistenti(List)} prima di entrare.
     * @return Una Response di errore se una tratta non esiste, altrimenti null.
     */
    private Response componiItinerario(Itinerario itinerario, List<String> stazioni, List<Integer> travelTimes) {
        for (int i = 0; i < stazioni.size() - 1; i++) {
            String partenzaId = stazioni.get(i);
            String arrivoId = stazioni.get(i + 1);
            Integer tempo = (travelTimes != null && i < travelTimes.size()) ? travelTimes.get(i) : null;

            Tratta tratta = repository.trovaTrattaFra(partenzaId, arrivoId);
            if (tratta == null) {
                // Non ci si arriva passando dalla verifica: resta come rete di sicurezza
                // se la tratta viene cancellata fra il controllo e la composizione.
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("errore", "Manca la tratta fra " + partenzaId + " e " + arrivoId))
                        .build();
            }
            if (tempo != null) {
                // La Tratta è un ARCO FISICO della rete, condiviso fra più itinerari
                // (es. T1_MI_BO sta sia in IT1_MI_NA sia in IT3_MI_RM): sovrascriverne
                // il tempo da qui cambierebbe di nascosto anche gli altri percorsi.
                // Lo si aggiorna solo se nessun altro itinerario usa quell'arco;
                // altrimenti il tempo si modifica dalla pagina "Tratte elementari".
                long usataAltrove = repository.contaAltriItinerariCheUsano(tratta.id, itinerario.id);
                if (usataAltrove == 0) {
                    tratta.tempoPercorrenzaMinuti = tempo;
                } else {
                    LOG.warnf("⏱️ Tratta %s condivisa con altri %d itinerari: tempo di percorrenza lasciato a %d minuti",
                            tratta.id, usataAltrove, tratta.tempoPercorrenzaMinuti);
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
     * Assegna i treni indicati all'itinerario. Se {@code sganciaAssenti} è true
     * (caso PUT), i treni prima assegnati e non più presenti vengono scollegati.
     *
     * <p>Con {@code treniIds} nullo non si sgancia niente: l'elenco assente significa che il
     * chiamante non sta gestendo le assegnazioni, non che le vuole azzerare tutte.</p>
     */
    private void assegnaTreni(Itinerario itinerario, List<String> treniIds, boolean sganciaAssenti) {
        if (sganciaAssenti && treniIds != null) {
            for (Treno t : Treno.<Treno>list("itinerario.id", itinerario.id)) {
                if (!treniIds.contains(t.id)) {
                    t.itinerario = null;
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
            Treno dbTreno = repository.trovaTreno(trenoId);
            if (dbTreno != null) {
                dbTreno.itinerario = itinerario;
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

        List<String> treniIds = new ArrayList<>();
        for (Treno t : Treno.<Treno>list("itinerario.id", it.id)) {
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
     * Mappa il tipo di un guasto nel vocabolario del frontend.
     *
     * <p>Calcolo delegato a {@link GuastiService#tipoPerFrontend}: la stessa whitelist che
     * usa l'ingestione quando il guasto arriva dal campo. Prima era scritta due volte, in
     * due file diversi, e poteva andare fuori sincrono.</p>
     */
    private String tipoAllarmePerFrontend(Guasto g) {
        return GuastiService.tipoPerFrontend(g.sorgenteTipo, g.tipo);
    }
}
