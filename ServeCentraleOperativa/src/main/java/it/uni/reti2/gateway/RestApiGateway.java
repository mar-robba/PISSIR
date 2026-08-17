package it.uni.reti2.gateway;

import io.quarkus.narayana.jta.QuarkusTransaction;
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import it.uni.reti2.eventi.CausaEvento;
import it.uni.reti2.eventi.GestoreReazioni;
import it.uni.reti2.eventi.VocabolarioEventi;
import it.uni.reti2.ingestion.IngestionService;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

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
 * RestApiGateway implementa le API REST (JAX-RS) utilizzate dal Frontend
 * (Dashboard Web) per l'interrogazione dello stato della rete e l'esecuzione
 * di comandi da parte degli operatori.
 *
 * <p><b>Nota sul database.</b> Qui dentro non c'è più nessuna query: le letture e le
 * scritture le fa {@link RailwayRepository}, che è l'unica classe della Centrale a
 * parlare con il database. Questa classe si occupa di quello che è affare suo —
 * validare il corpo della richiesta, scegliere il codice HTTP, allineare la cache in
 * RAM e pubblicare i comandi su MQTT — e chiede al repository i dati che le servono.
 * Le transazioni invece restano qui, perché il confine giusto è la richiesta REST:
 * o si scrive tutto o non si scrive niente.</p>
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

    /**
     * L'accesso al database: ogni riga letta o scritta da questi endpoint passa di qui.
     * La transazione però la apre questa classe (con {@code @Transactional} sul metodo
     * oppure a mano dove serve), non il repository.
     */
    @Inject
    RailwayRepository repository;

    /**
     * Serve per rimandare al frontend i cambi di stato delle stazioni decisi da qui
     * (presa in carico di un allarme, invio della squadra): sono modifiche che nessun
     * heartbeat annuncerà mai, quindi senza broadcast la schermata resterebbe indietro.
     */
    @Inject
    IngestionService ingestion;

    /**
     * La porta unica dei cambiamenti di stato causati da un evento: qui servono per i comandi
     * dell'operatore, che nello schema degli eventi domino sono primari come gli altri, solo
     * che nascono da una decisione invece che da un sensore. Passando di qui il cambiamento
     * finisce anche negli storici con dentro la sua causa, invece di essere scritto a mano in
     * cache e sparire.
     */
    @Inject
    GestoreReazioni gestoreReazioni;

    /**
     * Chi ha fatto la chiamata. Prima il token lo guardava solo il filtro, per decidere se
     * il comando poteva passare; adesso serve anche qui, perché le assegnazioni degli
     * operatori di RF02.7 devono dire <em>chi</em> ha preso in carico un allarme e chi ha
     * mandato la squadra a una stazione.
     */
    @Inject
    OperatoreCorrente operatoreCollegato;

    /**
     * Canale reattivo di uscita per inviare comandi asincroni ai field edge devices
     * (es. bloccare i treni o notificare la risoluzione di guasti tramite MQTT/Kafka).
     */
    @Inject
    @Channel("alerts-out")
    Emitter<String> alertsEmitter;

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
        if (repository.esisteStazione(dto.id)) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Stazione già esistente")).build();
        }
        Stazione stazione = new Stazione(
                dto.id,
                dto.nome != null ? dto.nome : dto.id,
                dto.latitudine != null ? dto.latitudine : 0.0,
                dto.longitudine != null ? dto.longitudine : 0.0,
                dto.binari != null ? dto.binari : 1);
        stazione.stato = dto.stato != null ? normalizzaStatoStazione(dto.stato) : "OFFLINE";
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
        if (dto.stato != null) cache.stato = normalizzaStatoStazione(dto.stato);
        statoRete.aggiornaStazione(cache);
        if (dto.stato != null) {
            // Anche lo stato cambiato a mano dall'amministratore deve arrivare subito
            // alle altre schermate aperte, non al prossimo ricaricamento.
            ingestion.broadcastStatoStazione(cache);
        }

        return Response.ok(cache).build();
    }

    /**
     * Elimina una stazione. Se è referenziata da qualche tratta risponde 409, altrimenti
     * chiude la partita con i transiti ancora aperti e cancella la riga di anagrafica.
     *
     * <p><b>Lo storico non si tocca</b> (RF02.7): le righe di Storico_Transiti e
     * Storico_Stato_Stazioni restano dov'erano. Prima venivano
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
        if (repository.esisteTreno(nomeConvoglio)) {
            esito.errore = Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Esiste già un treno con il nome '" + nomeConvoglio + "'")).build();
            return esito;
        }
        Treno treno = new Treno(nomeConvoglio);
        treno.stato = dto.stato != null ? normalizzaStatoTreno(dto.stato) : "fermo";

        String itinerarioId = estraiItinerarioId(dto);
        Itinerario assegnato = null;
        if (itinerarioId != null) {
            Itinerario itinerario = repository.trovaItinerario(itinerarioId);
            if (itinerario != null) {
                treno.itinerario = itinerario;
                assegnato = itinerario;
            }
        }
        repository.salvaTreno(treno);
        // Il convoglio nasce già assegnato: è il primo itinerario che percorre (RF02.7).
        if (assegnato != null) {
            repository.registraAssegnazioneItinerario(nomeConvoglio, assegnato);
        }
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
            pubblicaItinerarioAggiornato(id);
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
        if (dto.stato != null) dbTreno.stato = normalizzaStatoTreno(dto.stato);

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
                // Il viaggio di prima finisce qui e ne comincia un altro: la riga vecchia
                // si chiude e se ne apre una nuova con il percorso di adesso (RF02.7).
                repository.registraAssegnazioneItinerario(id, itinerario);
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
        // Il convoglio viene rottamato: l'itinerario che stava percorrendo si chiude adesso.
        repository.registraFineItinerario(id);
        repository.eliminaTreno(dbTreno);
        statoRete.rimuoviTreno(id);

        String alertJson = String.format(
                "{\"tipoEvento\":\"STOP\",\"target\":\"%s\",\"motivo\":\"Treno eliminato da operatore\",\"timestamp\":\"%s\"}",
                id, Instant.now().toString());
        alertsEmitter.send(alertJson);
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
        // Anche in cache: da quando le tratte hanno una percorribilità (RF02.1.2.2.2) un arco
        // che non è in cache è un arco su cui nessuna reazione può essere applicata.
        statoRete.aggiornaTratta(tratta);
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
        statoRete.aggiornaTratta(tratta);
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
        statoRete.rimuoviTratta(id);
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
        for (Treno t : repository.treniDellItinerario(id)) {
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

        // Se le tappe sono cambiate, da adesso i convogli ancora assegnati ne percorrono
        // altre: la riga di storico vecchia si chiude e se ne apre una con il percorso
        // nuovo. Il controllo sul "davvero cambiato" lo fa il repository, quindi una PUT
        // che non tocca le stazioni non lascia niente (RF02.7).
        for (Treno t : repository.treniDellItinerario(id)) {
            repository.registraAssegnazioneItinerario(t.id, itinerario);
        }

        // Ogni treno coinvolto (vecchio o nuovo) deve ricaricare l'itinerario dal server
        for (String trenoId : daNotificare) {
            pubblicaItinerarioAggiornato(trenoId);
        }
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
        for (Treno t : repository.treniDellItinerario(id)) {
            t.itinerario = null;
            // L'itinerario sparisce, ma il viaggio fatto fin qui resta: la riga si chiude e
            // si porta dentro il percorso, che nella tabella viva non ci sarà più (RF02.7).
            repository.registraFineItinerario(t.id);
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
            pubblicaItinerarioAggiornato(trenoId);
        }
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
            dto.put("timestamp", g.timestamp != null ? g.timestamp.toString() : null);
            dto.put("risolto", g.risolto);
            dto.put("timestampRisoluzione", g.timestampRisoluzione != null ? g.timestampRisoluzione.toString() : null);
            // Chi lo ha preso in carico (RF01.4.2). Serve perché la presa in carico deve
            // sopravvivere al ricaricamento della pagina ed essere visibile anche all'altro
            // operatore: se stesse solo nello store del browser che ha premuto il pulsante,
            // sarebbe un'etichetta e non un'informazione.
            dto.put("operatore", g.operatore != null
                    ? (g.operatore.nome + " " + g.operatore.cognome).trim()
                    : null);
            result.add(dto);
        }
        return result;
    }

    /**
     * Presa in carico di un allarme: l'operatore collegato dichiara di occuparsene lui
     * (RF01.4.2 e l'assegnatario di RF02.1.3).
     *
     * <p>Il guasto non viene chiuso — per quello c'è {@code /risolvi} — ma da adesso ha un
     * nome sopra: la colonna {@code OperatoreCheSeNeStaOccupandoFK}, che fino a ieri restava
     * sempre vuota, e una riga aperta in {@code Storico_Assegnazioni_Guasti} che si chiuderà
     * alla risoluzione. Se se ne stava già occupando qualcun altro il passaggio di mano
     * resta scritto: la riga di prima viene chiusa e se ne apre una nuova.</p>
     *
     * <p>Il nome che il frontend mostra accanto all'allarme viene dall'anagrafica Utenti,
     * perché è l'unica che ha nome e cognome per esteso: un utente di Keycloak che in
     * anagrafica non c'è prende in carico regolarmente e nello storico resta con la sua
     * matricola, ma nell'elenco allarmi l'etichetta con il nome non compare. Con gli utenti
     * del realm, che in anagrafica ci sono tutti, il caso non si presenta.</p>
     *
     * @param id Identificativo del guasto.
     * @return 200 con il guasto aggiornato, 404 se non esiste, 409 se è già risolto.
     */
    @POST
    @Path("/allarmi/{id}/assegna")
    @Transactional
    public Response assegnaAllarme(@PathParam("id") String id) {
        Guasto guasto = repository.trovaGuasto(id);
        if (guasto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (guasto.risolto) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Il guasto è già stato risolto")).build();
        }
        DatiOperatore operatore = operatoreCollegato.dati();
        if (operatore == null) {
            // Non dovrebbe succedere: il filtro rifiuta le chiamate senza token. Se l'identità
            // manca lo stesso è meglio non scrivere un'assegnazione senza assegnatario.
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("errore", "Autenticazione richiesta: effettuare il login")).build();
        }
        guasto.operatore = repository.trovaUtentePerMatricola(operatore.matricola());
        repository.apriAssegnazioneGuasto(guasto, operatore);
        // Va annunciato, se no la presa in carico la vede solo chi l'ha premuta: l'altro
        // operatore continuerebbe a vedere l'allarme libero fino al ricaricamento.
        ingestion.broadcastAlert(guasto);
        LOG.infof("👷 Guasto %s preso in carico da %s (%s)", id, operatore.nome(), operatore.matricola());
        return Response.ok(guasto).build();
    }

    /**
     * Operazione per segnare manualmente un guasto come risolto da parte di un operatore di centrale.
     * Notifica l'avvenuta risoluzione verso il campo (con la sorgente reale del guasto)
     * e aggiorna stato locale, DB e storico.
     *
     * @param id Identificativo del guasto.
     * @return Guasto aggiornato.
     */
    @POST
    @Path("/allarmi/{id}/risolvi")
    @Transactional
    public Response risolviAllarme(@PathParam("id") String id) {
        Guasto guasto = repository.trovaGuasto(id);
        if (guasto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        DatiOperatore chiHaRisolto = operatoreCollegato.dati();
        chiudiGuasto(guasto, chiHaRisolto);
        // Con la causa si chiudono le sue conseguenze: sono lo stesso fatto, e la catena lo dice.
        List<Guasto> conseguenze = chiudiConseguenzeDellaCatena(guasto, chiHaRisolto);

        // Se il guasto proveniva da una stazione, questa torna operativa in cache.
        // Si fa ripartire anche il cronometro dell'heartbeat: senza, una stazione
        // rimessa ONLINE dall'operatore ma in realtà ancora spenta resterebbe
        // ONLINE per sempre, perché il FaultMonitor salta le stazioni che non hanno
        // mai battuto. Così invece, se il battito non arriva davvero, dopo il
        // timeout il watchdog la rimette OFFLINE.
        if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            Stazione stazione = statoRete.getStazione(guasto.sorgenteId);
            // Se la squadra è sul posto la stazione NON torna operativa adesso: ci pensa il
            // comando di fine intervento. Senza questo controllo il giro "invia operatori" +
            // "presa visione" dell'allarme, che la schermata fa uno dietro l'altro, rimetteva
            // ONLINE la stazione un istante dopo averla messa in manutenzione — cioè lo stesso
            // difetto di RF01.4.1 che il comando separato serve a togliere, rientrato da
            // un'altra porta.
            if (stazione != null && !"MANUTENZIONE".equalsIgnoreCase(stazione.stato)) {
                stazione.stato = "ONLINE";
                stazione.ultimoHeartbeat = Instant.now();
                statoRete.aggiornaStazione(stazione);
                // Il ritorno in servizio va spinto sulla WebSocket: prima la stazione
                // restava rossa sulla mappa finché l'operatore non ricaricava la pagina.
                ingestion.broadcastStatoStazione(stazione);
            }
        }

        // Stesso discorso per un treno: l'ingestione lo marca "rotto" in cache quando
        // arriva il suo allarme, quindi alla chiusura del guasto va rimesso "fermo",
        // altrimenti resterebbe guasto nell'interfaccia anche a riparazione avvenuta.
        // "fermo" e non "attivo": riparte con la prima telemetria che manda davvero.
        if ("TRENO".equalsIgnoreCase(guasto.sorgenteTipo)) {
            Treno treno = statoRete.getTreno(guasto.sorgenteId);
            if (treno != null && "rotto".equalsIgnoreCase(treno.stato)) {
                treno.stato = "fermo";
                statoRete.aggiornaTreno(treno);
            }
            Treno dbTreno = repository.trovaTreno(guasto.sorgenteId);
            if (dbTreno != null && "rotto".equalsIgnoreCase(dbTreno.stato)) {
                String statoPrecedente = dbTreno.stato;
                dbTreno.stato = "fermo";

                repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente);
            }
        }

        riapriTrattaSeEra(guasto, chiHaRisolto);

        pubblicaResolved(guasto);
        // Le conseguenze si annunciano come il guasto principale: al campo, perché i nodi si
        // srotolano per catena, e al frontend, perché l'elenco degli allarmi si svuoti da solo.
        for (Guasto conseguenza : conseguenze) {
            LOG.infof("🔗 Chiusa con la causa la conseguenza %s su %s %s (catena %s)",
                    conseguenza.id, conseguenza.sorgenteTipo, conseguenza.sorgenteId, conseguenza.catenaId);
            riapriTrattaSeEra(conseguenza, chiHaRisolto);
            pubblicaResolved(conseguenza);
            ingestion.broadcastAlert(conseguenza);
        }
        return Response.ok(guasto).build();
    }

    /**
     * Se il guasto chiuso riguardava un arco della rete, quell'arco torna percorribile.
     *
     * <p>Di norma la dichiarazione la fa il convoglio che ci era rimasto sopra, appena legge il
     * RESOLVED. Qui la fa la Centrale perché quel convoglio può essersi spento proprio mentre era
     * guasto, e un arco rimasto impercorribile per sempre fermerebbe tutti gli altri: è la stessa
     * rete di sicurezza che c'è già per le stazioni. Passa comunque dalla porta delle reazioni,
     * quindi lascia la sua riga di storico con dentro chi l'ha deciso.</p>
     *
     * @param guasto    Il guasto appena chiuso.
     * @param operatore Chi lo ha chiuso.
     */
    private void riapriTrattaSeEra(Guasto guasto, DatiOperatore operatore) {
        if (!VocabolarioEventi.NODO_TRATTA.equalsIgnoreCase(guasto.sorgenteTipo)) {
            return;
        }
        gestoreReazioni.applicaDallaCentrale(
                VocabolarioEventi.NODO_TRATTA, guasto.sorgenteId,
                VocabolarioEventi.TRATTA_PERCORRIBILE, VocabolarioEventi.TRATTA_IMPERCORRIBILE,
                new CausaEvento(VocabolarioEventi.NODO_OPERATORE,
                        operatore != null ? operatore.matricola() : null,
                        guasto.catenaId != null ? guasto.catenaId : guasto.id),
                false, "Allarme risolto: la tratta torna percorribile");
    }

    /**
     * Operazione manuale da parte dell'operatore per bloccare d'emergenza o sopprimere la corsa
     * di un treno specifico.
     *
     * @param id L'ID del treno da fermare.
     * @return Stato del treno aggiornato.
     */
    @POST
    @Path("/treni/{id}/sopprimi")
    @Transactional
    public Response sopprimiTreno(@PathParam("id") String id) {
        // Aggiorna lo stato in memoria per riflesso immediato sulle dashboard
        Treno treno = statoRete.getTreno(id);
        if (treno != null) {
            treno.stato = "SOPPRESSO";
            treno.velocita = 0;
            statoRete.aggiornaTreno(treno);

            // Aggiorna anche il salvataggio su DB persistente (stato canonico)
            Treno dbTreno = repository.trovaTreno(id);
            if (dbTreno != null) {
                String statoPrecedente = dbTreno.stato;
                dbTreno.stato = "in manutenzione";

                repository.salvaStoricoStatoTreno(dbTreno, statoPrecedente);
            }

            // Invia asincronamente il comando di STOP sul canale di allarme
            // affinché il gateway di bordo applichi la frenata definitiva.
            String alertJson = String.format(
                    "{\"tipoEvento\":\"STOP\",\"target\":\"%s\",\"motivo\":\"Soppresso da operatore\",\"timestamp\":\"%s\"}",
                    id, Instant.now().toString());
            alertsEmitter.send(alertJson);

            return Response.ok(treno).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
// sofisticazione assolutamente non richiesta fatta di propria zucca dell'ai
    /**
     * Operazione manuale da parte dell'operatore per inviare una squadra di manutenzione a una
     * stazione (RF01.4.1). Risolve tutti i guasti aperti della stazione, la mette in
     * MANUTENZIONE e notifica campo e frontend.
     *
     * <p><b>Perché il ritorno a ONLINE non è più qui.</b> Prima questo metodo metteva
     * MANUTENZIONE e rimetteva ONLINE nella stessa chiamata, a pochi millisecondi di distanza:
     * lo stato attraversava il canale ma non durava, e la stazione non /risultava/ in
     * manutenzione a nessuno che la guardasse — che è invece esattamente quello che il requisito
     * chiede. Adesso l'invio della squadra e la fine dell'intervento sono due fatti distinti,
     * come sono distinti nella realtà: il secondo è
     * {@link #concludiManutenzione(String)}.</p>
     *
     * <p>Il cambiamento di stato passa da {@link GestoreReazioni}, non da un'assegnazione a mano
     * sulla cache. È lo stesso schema degli eventi a catena, con il primario che nasce da una
     * decisione dell'operatore invece che da un sensore: la conseguenza è che il passaggio a
     * MANUTENZIONE finisce in {@code Storico_Stato_Stazioni} con dentro <i>chi</i> l'ha deciso,
     * mentre prima non ci finiva affatto.</p>
     *
     * @param id L'ID della stazione.
     * @return Stato della stazione aggiornato.
     */
    @POST
    @Path("/stazioni/{id}/manutenzione")
    @Transactional
    public Response dispacciaManutenzione(@PathParam("id") String id) {
        Stazione stazione = statoRete.getStazione(id);
        if (stazione == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if ("MANUTENZIONE".equalsIgnoreCase(stazione.stato)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Squadra già sul posto: l'intervento è in corso"))
                    .build();
        }
        // Lo stato di partenza serve alla riga di storico e a quella dell'intervento:
        // è da lì che la stazione viene (di solito GUASTA o OFFLINE).
        String statoPrima = stazione.stato;
        Instant tsInvio = Instant.now();
        DatiOperatore operatore = operatoreCollegato.dati();

        // Risolve TUTTI i guasti ancora aperti generati da questa stazione.
        // Il filtro su sorgenteTipo serve perché sorgenteId da solo non è univoco:
        // un treno con lo stesso identificativo di una stazione si vedrebbe chiudere
        // i propri guasti insieme a quelli della stazione.
        List<Guasto> aperti = repository.guastiApertiDi(id, "STAZIONE");
        for (Guasto guasto : aperti) {
            chiudiGuasto(guasto, operatore);
            if (guasto.sorgenteTipo == null) guasto.sorgenteTipo = "STAZIONE";
            pubblicaResolved(guasto);
        }

        // La catena dell'intervento è nuova e la conia la Centrale: il comando dell'operatore è
        // un evento primario come il guasto di un sensore, solo che nasce da una decisione. Non
        // eredita quella dei guasti appena chiusi proprio perché quelli sono chiusi: l'episodio
        // che comincia adesso è l'intervento, e va seguito per conto suo.
        String catena = id + "-manut-" + tsInvio.toEpochMilli();
        CausaEvento causa = new CausaEvento(VocabolarioEventi.NODO_OPERATORE,
                operatore != null ? operatore.matricola() : null, catena);
        String motivo = "Squadra di manutenzione inviata"
                + (operatore != null ? " da " + operatore.nome() : "");

        gestoreReazioni.applicaDallaCentrale(VocabolarioEventi.NODO_STAZIONE, id,
                "MANUTENZIONE", statoPrima, causa, true, motivo);
        // Il fatto va anche sul canale, come tutte le reazioni, marcato perché la Centrale non
        // se lo riascolti da sola.
        ingestion.pubblicaReazioneSuMqtt(VocabolarioEventi.NODO_STAZIONE, id,
                "MANUTENZIONE", statoPrima, causa, true, motivo);

        // Notifica informativa dell'invio degli operatori (il nodo non ha lo stato MANUTENZIONE,
        // RF02.3.4: per lui questo messaggio resta un avviso).
        String alertJson = String.format(
                "{\"tipoEvento\":\"MAINTENANCE_DISPATCHED\",\"sorgenteId\":\"%s\",\"catenaId\":\"%s\",\"timestamp\":\"%s\"}",
                id, catena, tsInvio.toString());
        alertsEmitter.send(alertJson);

        // L'intervento resta APERTO: ts_rientro si riempirà quando la squadra avrà finito. La
        // colonna era già prevista annullabile ("null finché è in corso") ma non lo era mai,
        // perché invio e rientro stavano nella stessa chiamata.
        Stazione dbStazione = repository.trovaStazione(id);
        if (dbStazione != null && operatore != null) {
            String guastoMotivante = aperti.isEmpty() ? null : aperti.get(0).id;
            repository.apriInterventoManutenzione(dbStazione, guastoMotivante, operatore,
                    statoPrima, tsInvio, catena);
        }
        LOG.infof("🛠️ Squadra inviata a %s (catena %s): la stazione resta in MANUTENZIONE "
                + "finché l'intervento non viene dichiarato concluso", id, catena);
        return Response.ok(stazione).build();
    }

    /**
     * Fine dell'intervento: la squadra ha finito e la stazione torna in servizio (RF01.4.1).
     *
     * <p>È il comando che prima non esisteva, ed è la ragione per cui MANUTENZIONE durava
     * millisecondi. Il ritorno a ONLINE non è una conseguenza automatica dell'invio della
     * squadra — è un fatto che accade dopo, quando l'intervento è davvero finito, e solo
     * l'operatore lo sa.</p>
     *
     * <p>Chiude anche la riga dell'intervento in {@code Storico_Interventi_Manutenzione}: fra
     * {@code ts_invio} e {@code ts_rientro} adesso passa la durata vera del lavoro, e non
     * qualche millisecondo.</p>
     *
     * @param id L'ID della stazione.
     * @return Stato della stazione aggiornato, 409 se non c'era nessun intervento in corso.
     */
    @POST
    @Path("/stazioni/{id}/manutenzione/conclusa")
    @Transactional
    public Response concludiManutenzione(@PathParam("id") String id) {
        Stazione stazione = statoRete.getStazione(id);
        if (stazione == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!"MANUTENZIONE".equalsIgnoreCase(stazione.stato)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Nessun intervento in corso su questa stazione"))
                    .build();
        }
        DatiOperatore operatore = operatoreCollegato.dati();
        StoricoInterventoManutenzione intervento = repository.interventoApertoDi(id);
        // La catena è quella aperta dall'invio della squadra: uscirne è ciò che chiude
        // l'episodio anche nel registro delle catene.
        String catena = intervento != null && intervento.catenaId != null
                ? intervento.catenaId
                : id + "-manut-" + Instant.now().toEpochMilli();
        CausaEvento causa = new CausaEvento(VocabolarioEventi.NODO_OPERATORE,
                operatore != null ? operatore.matricola() : null, catena);
        String motivo = "Intervento di manutenzione concluso"
                + (operatore != null ? " da " + operatore.nome() : "");

        gestoreReazioni.applicaDallaCentrale(VocabolarioEventi.NODO_STAZIONE, id,
                "ONLINE", "MANUTENZIONE", causa, false, motivo);
        ingestion.pubblicaReazioneSuMqtt(VocabolarioEventi.NODO_STAZIONE, id,
                "ONLINE", "MANUTENZIONE", causa, false, motivo);

        // Il cronometro dell'heartbeat riparte da adesso: se la stazione è ancora spenta
        // davvero, dopo il timeout il watchdog la rimette OFFLINE da solo, invece di lasciarla
        // ONLINE per sempre.
        stazione.ultimoHeartbeat = Instant.now();
        statoRete.aggiornaStazione(stazione);

        if (intervento != null) {
            repository.chiudiInterventoManutenzione(intervento, "ONLINE");
        }
        LOG.infof("🔧 Intervento concluso su %s: stazione di nuovo in servizio", id);
        return Response.ok(stazione).build();
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
            stazioni.add(tappaItinerario(t.stazionePartenza,
                    t.tempoPercorrenzaMinuti != null ? t.tempoPercorrenzaMinuti : 15, t.id));
            if (i == tratte.size() - 1) {
                // L'ultima stazione è il capolinea: tempo verso la prossima = 0 e nessuna
                // tratta successiva da percorrere.
                stazioni.add(tappaItinerario(t.stazioneArrivo, 0, null));
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
        List<String> stazioni = repository.stazioniOrdinateDi(treno.itinerario.id);
        int idx = stazioni.indexOf(stazioneId);

        Map<String, Object> result = new HashMap<>();
        result.put("prossimaStazione", null);
        if (idx >= 0) {
            int next = "R".equalsIgnoreCase(direzione) ? idx - 1 : idx + 1;
            if (next >= 0 && next < stazioni.size()) {
                Stazione prossima = repository.trovaStazione(stazioni.get(next));
                if (prossima != null) {
                    Map<String, Object> dto = new HashMap<>();
                    dto.put("id", prossima.id);
                    dto.put("nome", prossima.nome);
                    result.put("prossimaStazione", dto);
                }
            }
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

    /**
     * Costruisce la tappa {id,nome,latitudine,longitudine,tempoVersoProssimaMinuti,trattaVersoProssimaId}.
     *
     * <p>L'identificativo della tratta serve al convoglio per <b>nominare</b> il pezzo di rete su
     * cui si trova: senza, un convoglio che si guasta fra due stazioni può dire soltanto fra
     * quali, e "l'arco fra A e B" è un modo indiretto di indicare una cosa che la Centrale
     * chiama per nome. Con l'id la dichiarazione di percorribilità (RF02.1.2.2.2) usa la stessa
     * identità che hanno il database e gli altri convogli, compresi quelli che percorrono quel
     * tratto dentro un itinerario diverso.</p>
     *
     * @param trattaVersoProssima Id dell'arco che porta alla tappa successiva, null al capolinea.
     */
    private Map<String, Object> tappaItinerario(Stazione stazione, int tempoVersoProssima,
                                                String trattaVersoProssima) {
        Map<String, Object> tappa = new HashMap<>();
        tappa.put("id", stazione.id);
        tappa.put("nome", stazione.nome);
        tappa.put("latitudine", stazione.latitudine != null ? stazione.latitudine : 0.0);
        tappa.put("longitudine", stazione.longitudine != null ? stazione.longitudine : 0.0);
        tappa.put("tempoVersoProssimaMinuti", tempoVersoProssima);
        tappa.put("trattaVersoProssimaId", trattaVersoProssima != null ? trattaVersoProssima : "");
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
            for (Treno t : repository.treniDellItinerario(itinerario.id)) {
                if (!treniIds.contains(t.id)) {
                    t.itinerario = null;
                    // Sganciato: qui il convoglio ha smesso di percorrerlo (RF02.7).
                    repository.registraFineItinerario(t.id);
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
                // Chi era già su questo itinerario non lascia una riga nuova: il repository
                // scrive solo se l'assegnazione è davvero cambiata.
                repository.registraAssegnazioneItinerario(trenoId, itinerario);
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

    /** Mappa il tipo di un guasto nel vocabolario del frontend. */
    private String tipoAllarmePerFrontend(Guasto g) {
        if ("stazione_guasta".equals(g.tipo) || "treno_fermo".equals(g.tipo) || "sensore_offline".equals(g.tipo)) {
            return g.tipo;
        }
        if ("STAZIONE".equalsIgnoreCase(g.sorgenteTipo)) return "stazione_guasta";
        if ("TRENO".equalsIgnoreCase(g.sorgenteTipo)) return "treno_fermo";
        return "sensore_offline";
    }

    /** Chiude un guasto su DB, storico e cache. */
    private void chiudiGuasto(Guasto guasto, DatiOperatore operatore) {
        guasto.risolto = true;
        guasto.timestampRisoluzione = Instant.now();
        // Chi chiude un allarme se ne è occupato, anche se non lo aveva mai preso in carico
        // formalmente: da qui in poi l'assegnatario c'è, ed è lui.
        if (operatore != null && guasto.operatore == null) {
            guasto.operatore = repository.trovaUtentePerMatricola(operatore.matricola());
        }

        StoricoGuasto storico = repository.trovaStoricoGuasto(guasto.id);
        if (storico != null) {
            storico.risolto = true;
            storico.tsChiusura = guasto.timestampRisoluzione;
            // La riga era stata scritta all'apertura, quando un assegnatario non c'era
            // ancora: adesso che c'è va ricopiato anche lì, altrimenti di un guasto chiuso
            // continua a non risultare chi lo ha chiuso.
            if (operatore != null && storico.operatoreId == null) {
                storico.operatoreId = operatore.id();
                storico.nomeOperatore = operatore.nome();
                storico.matricolaOperatore = operatore.matricola();
            }
        } else {
            // Guasto senza storico (per esempio aperto prima che la tabella esistesse):
            // la riga si scrive adesso, già chiusa.
            repository.salvaStoricoGuasto(guasto);
        }
        // Riga di Storico_Assegnazioni_Guasti: chiude la presa in carico aperta, oppure ne
        // scrive una già chiusa se l'allarme è stato risolto senza passare da /assegna.
        repository.chiudiAssegnazioneGuasto(guasto, operatore);
        statoRete.risolviGuasto(guasto.id);
    }

    /**
     * Pubblica su MQTT l'evento RESOLVED con la sorgente reale del guasto.
     *
     * <p>Delega all'ingestione e non compone più il payload qui, perché mancavano due cose che
     * quella versione ha: il campo {@code catenaId} e la chiusura della catena nel registro.
     * Senza la catena nel RESOLVED lo srotolamento non funzionava proprio nel caso che conta —
     * il convoglio trattenuto da una stazione resa impercorribile da un <i>altro</i> convoglio
     * guasto, dove il guasto che si chiude ha una sorgente diversa da quella che teneva fermo
     * chi aspetta: chi legge non aveva modo di capire che quel ripristino lo riguardava.</p>
     */
    private void pubblicaResolved(Guasto guasto) {
        ingestion.pubblicaRisoluzioneSuMqtt(guasto);
    }

    /**
     * Chiude le conseguenze insieme alla causa: gli altri guasti aperti della <b>stessa catena</b>
     * nati <i>dopo</i> quello che l'operatore ha appena risolto.
     *
     * <p>Perché serve: un evento derivato non apre un guasto, ma quando la conseguenza è un pezzo
     * di infrastruttura che diventa inagibile (la stazione con un convoglio guasto sui binari, la
     * tratta occupata da un'avaria) il fatto viene ripubblicato come GUASTO, perché l'operatore
     * deve vederlo. È un allarme vero con la catena della causa: riparata la causa, l'intervento
     * è finito e restare in elenco sarebbe solo rumore da smaltire a mano.</p>
     *
     * <p>La direzione conta: si chiude ciò che <b>discende</b> dal guasto risolto, mai il
     * contrario. Chiudere l'allarme "stazione impercorribile" non ripara il convoglio, quindi il
     * guasto del convoglio (più vecchio, è lui la causa) non viene toccato.</p>
     *
     * @param risolto   Il guasto che l'operatore ha appena chiuso.
     * @param operatore Chi lo ha chiuso: le conseguenze risultano chiuse da lui.
     * @return I guasti derivati chiusi, da annunciare a campo e frontend.
     */
    private List<Guasto> chiudiConseguenzeDellaCatena(Guasto risolto, DatiOperatore operatore) {
        List<Guasto> chiusi = new ArrayList<>();
        Instant apertura = risolto.timestamp != null ? risolto.timestamp : Instant.EPOCH;
        for (Guasto derivato : repository.guastiApertiDellaCatena(risolto.catenaId)) {
            if (derivato.id.equals(risolto.id)) {
                continue;
            }
            if (derivato.timestamp != null && derivato.timestamp.isBefore(apertura)) {
                continue; // è più vecchio: è la causa, non la conseguenza
            }
            chiudiGuasto(derivato, operatore);
            chiusi.add(derivato);
        }
        return chiusi;
    }

    /** Pubblica su MQTT l'evento ITINERARIO_AGGIORNATO destinato a un treno. */
    private void pubblicaItinerarioAggiornato(String trenoId) {
        String alertJson = String.format(
                "{\"tipoEvento\":\"ITINERARIO_AGGIORNATO\",\"target\":\"%s\",\"timestamp\":\"%s\"}",
                trenoId, Instant.now().toString());
        alertsEmitter.send(alertJson);
    }

    /**
     * Normalizza lo stato di un treno ricevuto dalla UI nei valori canonici del DB
     * (attivo/fermo/rotto/in manutenzione): il CHECK sulla colonna rifiuta le maiuscole.
     */
    private static String normalizzaStatoTreno(String stato) {
        return stato == null ? null : stato.trim().toLowerCase();
    }

    /**
     * Normalizza lo stato runtime di una stazione nel vocabolario interno
     * (ONLINE/GUASTA/MANUTENZIONE/OFFLINE), accettando il sinonimo usato dalla UI.
     */
    private static String normalizzaStatoStazione(String stato) {
        if (stato == null) return null;
        String s = stato.trim().toUpperCase();
        return "OPERATIVA".equals(s) ? "ONLINE" : s;
    }
}
