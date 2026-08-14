package it.uni.reti2.gateway;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
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
        if (Stazione.findById(dto.id) != null) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Stazione già esistente")).build();
        }
        Stazione stazione = new Stazione(
                dto.id,
                dto.nome != null ? dto.nome : dto.id,
                dto.latitudine != null ? dto.latitudine : 0.0,
                dto.longitudine != null ? dto.longitudine : 0.0,
                dto.binari != null ? dto.binari : 1);
        stazione.stato = dto.stato != null ? normalizzaStatoStazione(dto.stato) : "OFFLINE";
        stazione.persist();
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
        Stazione dbStazione = Stazione.findById(id);
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

        return Response.ok(cache).build();
    }

    /**
     * Elimina una stazione. Se è referenziata da qualche tratta risponde 409,
     * altrimenti rimuove prima i record storici collegati per evitare violazioni di FK.
     * @param id ID della stazione.
     * @return 204 No Content, 404 o 409.
     */
    @DELETE
    @Path("/stazioni/{id}")
    @Transactional
    public Response deleteStazione(@PathParam("id") String id) {
        Stazione dbStazione = Stazione.findById(id);
        if (dbStazione == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        long tratteCollegate = Tratta.count("stazionePartenza.id = ?1 or stazioneArrivo.id = ?1", id);
        if (tratteCollegate > 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Stazione referenziata da " + tratteCollegate + " tratte: eliminarle prima"))
                    .build();
        }
        // Pulizia dei record collegati per non violare le FK
        StoricoTransito.delete("stazione.id", id);
        Transito.delete("stazione.id", id);
        StoricoStatoStazione.delete("stazione.id", id);
        EventoStazione.delete("stazioneId", id);
        dbStazione.delete();
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
        esito.treno.ultimoAggiornamento = Instant.now();
        statoRete.aggiornaTreno(esito.treno);
        return Response.status(Response.Status.CREATED).entity(esito.treno).build();
    }

    /** Inserisce il treno nella tabella Treni. Gira dentro la transazione aperta dal chiamante. */
    private EsitoScritturaTreno creaTrenoSuDb(String nomeConvoglio, TrenoDTO dto) {
        EsitoScritturaTreno esito = new EsitoScritturaTreno();
        if (Treno.findById(nomeConvoglio) != null) {
            esito.errore = Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Esiste già un treno con il nome '" + nomeConvoglio + "'")).build();
            return esito;
        }
        Treno treno = new Treno(nomeConvoglio);
        treno.stato = dto.stato != null ? normalizzaStatoTreno(dto.stato) : "fermo";

        String itinerarioId = estraiItinerarioId(dto);
        if (itinerarioId != null) {
            Itinerario itinerario = Itinerario.findById(itinerarioId);
            if (itinerario != null) {
                treno.itinerario = itinerario;
            }
        }
        treno.persist();
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
        Treno dbTreno = Treno.findById(id);
        if (dbTreno == null) {
            esito.errore = Response.status(Response.Status.NOT_FOUND).build();
            return esito;
        }
        if (dto.stato != null) dbTreno.stato = normalizzaStatoTreno(dto.stato);

        String itinerarioId = estraiItinerarioId(dto);
        if (itinerarioId != null) {
            String attuale = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
            if (!itinerarioId.equals(attuale)) {
                Itinerario itinerario = Itinerario.findById(itinerarioId);
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
     * Elimina un treno cancellando a cascata i record storici che lo referenziano
     * (scelta progettuale per evitare violazioni di FK) e notificando lo STOP via MQTT.
     * @param id ID del convoglio.
     * @return 204 No Content oppure 404.
     */
    @DELETE
    @Path("/treni/{id}")
    @Transactional
    public Response deleteTreno(@PathParam("id") String id) {
        Treno dbTreno = Treno.findById(id);
        if (dbTreno == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Cancellazione a cascata dei record storici del convoglio
        StoricoStatoTreno.delete("treno.id", id);
        StoricoTransito.delete("treno.id", id);
        Transito.delete("treno.id", id);
        StoricoItinerario.delete("treno.id", id);
        dbTreno.delete();
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
        for (Tratta tratta : Tratta.<Tratta>listAll(Sort.by("id"))) result.add(trattaElementoToDto(tratta));
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
        if (Tratta.findById(dto.id) != null) return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta già esistente")).build();
        Stazione partenza = Stazione.findById(dto.stazionePartenzaId);
        Stazione arrivo = Stazione.findById(dto.stazioneArrivoId);
        if (partenza == null || arrivo == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di partenza o arrivo inesistente")).build();
        Tratta tratta = new Tratta();
        tratta.id = dto.id;
        tratta.stazionePartenza = partenza;
        tratta.stazioneArrivo = arrivo;
        tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti != null ? dto.tempoPercorrenzaMinuti : 15;
        tratta.persist();
        return Response.status(Response.Status.CREATED).entity(trattaElementoToDto(tratta)).build();
    }

    @PUT
    @Path("/tratte-elementari/{id}")
    @Transactional
    public Response updateTrattaElemento(@PathParam("id") String id, TrattaElementoDTO dto) {
        Tratta tratta = Tratta.findById(id);
        if (tratta == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (dto.stazionePartenzaId != null) {
            Stazione s = Stazione.findById(dto.stazionePartenzaId);
            if (s == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di partenza inesistente")).build();
            tratta.stazionePartenza = s;
        }
        if (dto.stazioneArrivoId != null) {
            Stazione s = Stazione.findById(dto.stazioneArrivoId);
            if (s == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("errore", "Stazione di arrivo inesistente")).build();
            tratta.stazioneArrivo = s;
        }
        if (dto.tempoPercorrenzaMinuti != null) tratta.tempoPercorrenzaMinuti = dto.tempoPercorrenzaMinuti;
        return Response.ok(trattaElementoToDto(tratta)).build();
    }

    @DELETE
    @Path("/tratte-elementari/{id}")
    @Transactional
    public Response deleteTrattaElemento(@PathParam("id") String id) {
        Tratta tratta = Tratta.findById(id);
        if (tratta == null) return Response.status(Response.Status.NOT_FOUND).build();
        long usi = ItinerarioTratta.count("id.idTratta", id);
        if (usi > 0) return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta usata da " + usi + " itinerari: modificare prima gli itinerari")).build();
        if (Transito.count("tratta.id", id) > 0 || StoricoTransito.count("tratta.id", id) > 0) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("errore", "Tratta presente nei transiti storici e non eliminabile")).build();
        }
        tratta.delete();
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
        List<Itinerario> itinerari = Itinerario.listAll();
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
        if (Itinerario.findById(id) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errore", "Itinerario già esistente: " + id)).build();
        }
        // Prima di scrivere qualsiasi cosa: se manca anche un solo arco non si crea niente.
        Response tratteMancanti = verificaTratteEsistenti(dto.stazioni);
        if (tratteMancanti != null) return tratteMancanti;

        Itinerario itinerario = new Itinerario();
        itinerario.id = id;
        itinerario.persist();

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
        Itinerario itinerario = Itinerario.findById(id);
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

            ItinerarioTratta.delete("id.idItinerario", id);
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
        assegnaTreni(itinerario, dto.treniIds, true);
        if (dto.treniIds != null) {
            daNotificare.addAll(dto.treniIds); // anche i treni appena agganciati
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
        Itinerario itinerario = Itinerario.findById(id);
        if (itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Sgancia i treni (DB + cache)
        for (Treno t : Treno.<Treno>list("itinerario.id", id)) {
            t.itinerario = null;
            Treno cache = statoRete.getTreno(t.id);
            if (cache != null) {
                cache.itinerario = null;
                statoRete.aggiornaTreno(cache);
            }
        }
        ItinerarioTratta.delete("id.idItinerario", id);
        StoricoItinerario.delete("itinerario.id", id);
        itinerario.delete();
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
        List<StoricoTransito> transiti = StoricoTransito
                .findAll(Sort.descending("tsStoricizzazione"))
                .page(0, 200)
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StoricoTransito t : transiti) {
            Instant timestamp = t.tempoUscita != null ? t.tempoUscita : t.tempoEntrata;
            Treno cacheTreno = statoRete.getTreno(t.treno.id);
            int ritardo = cacheTreno != null ? cacheTreno.ritardo : 0;

            Map<String, Object> dto = new HashMap<>();
            dto.put("id", t.idTransito + "-" + t.id);
            dto.put("trenoId", t.treno.id);
            dto.put("stazioneId", t.stazione.id);
            dto.put("tipo", t.tempoUscita == null ? "ingresso" : "uscita");
            dto.put("timestamp", timestamp != null ? timestamp.toString() : null);
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
        List<Guasto> guasti = Guasto.listAll(Sort.descending("timestamp"));
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
            result.add(dto);
        }
        return result;
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
        Guasto guasto = Guasto.findById(id);
        if (guasto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        chiudiGuasto(guasto);

        // Se il guasto proveniva da una stazione, questa torna operativa in cache.
        // Si fa ripartire anche il cronometro dell'heartbeat: senza, una stazione
        // rimessa ONLINE dall'operatore ma in realtà ancora spenta resterebbe
        // ONLINE per sempre, perché il FaultMonitor salta le stazioni che non hanno
        // mai battuto. Così invece, se il battito non arriva davvero, dopo il
        // timeout il watchdog la rimette OFFLINE.
        if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            Stazione stazione = statoRete.getStazione(guasto.sorgenteId);
            if (stazione != null) {
                stazione.stato = "ONLINE";
                stazione.ultimoHeartbeat = Instant.now();
                statoRete.aggiornaStazione(stazione);
            }
        }
        pubblicaResolved(guasto);
        return Response.ok(guasto).build();
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
            Treno dbTreno = Treno.findById(id);
            if (dbTreno != null) {
                dbTreno.stato = "in manutenzione";

                StoricoStatoTreno storico = new StoricoStatoTreno();
                storico.treno = dbTreno;
                storico.stato = "in manutenzione";
                storico.itinerarioId = dbTreno.itinerario != null ? dbTreno.itinerario.id : null;
                storico.posizioneId = dbTreno.posizioneAttualeTratta != null ? dbTreno.posizioneAttualeTratta.id : null;
                storico.persist();
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

    /**
     * Operazione manuale da parte dell'operatore per inviare una squadra di manutenzione a una stazione.
     * Risolve tutti i guasti aperti della stazione e notifica campo e frontend.
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
        stazione.stato = "MANUTENZIONE";
        statoRete.aggiornaStazione(stazione);

        // Notifica informativa dell'invio degli operatori
        String alertJson = String.format(
                "{\"tipoEvento\":\"MAINTENANCE_DISPATCHED\",\"sorgenteId\":\"%s\",\"timestamp\":\"%s\"}",
                id, Instant.now().toString());
        alertsEmitter.send(alertJson);

        // Risolve TUTTI i guasti ancora aperti generati da questa stazione.
        // Il filtro su sorgenteTipo serve perché sorgenteId da solo non è univoco:
        // un treno con lo stesso identificativo di una stazione si vedrebbe chiudere
        // i propri guasti insieme a quelli della stazione.
        List<Guasto> aperti = Guasto.list(
                "sorgenteId = ?1 and sorgenteTipo = 'STAZIONE' and risolto = false", id);
        for (Guasto guasto : aperti) {
            chiudiGuasto(guasto);
            if (guasto.sorgenteTipo == null) guasto.sorgenteTipo = "STAZIONE";
            pubblicaResolved(guasto);
        }

        // A manutenzione completata la stazione torna operativa
        stazione.stato = "ONLINE";
        stazione.ultimoHeartbeat = Instant.now();
        statoRete.aggiornaStazione(stazione);

        Stazione dbStazione = Stazione.findById(id);
        if (dbStazione != null) {
            StoricoStatoStazione storico = new StoricoStatoStazione();
            storico.stazione = dbStazione;
            storico.nome = dbStazione.nome;
            storico.tipo = dbStazione.tipoCapolineaPartenzaoNormale;
            storico.funzionanteONo = true;
            storico.persist();
        }
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
        Treno treno = Treno.findById(id);
        if (treno == null || treno.itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Treno inesistente o senza itinerario")).build();
        }
        List<ItinerarioTratta> tratte = ItinerarioTratta
                .find("itinerario.id", Sort.by("ordine"), treno.itinerario.id).list();
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
        Treno treno = trenoId != null ? Treno.findById(trenoId) : null;
        if (treno == null || treno.itinerario == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("errore", "Treno inesistente o senza itinerario")).build();
        }
        List<String> stazioni = stazioniOrdinate(treno.itinerario.id);
        int idx = stazioni.indexOf(stazioneId);

        Map<String, Object> result = new HashMap<>();
        result.put("prossimaStazione", null);
        if (idx >= 0) {
            int next = "R".equalsIgnoreCase(direzione) ? idx - 1 : idx + 1;
            if (next >= 0 && next < stazioni.size()) {
                Stazione prossima = Stazione.findById(stazioni.get(next));
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

    /** Ricostruisce la sequenza ordinata di ID stazione dell'itinerario. */
    private List<String> stazioniOrdinate(String itinerarioId) {
        List<ItinerarioTratta> tratte = ItinerarioTratta
                .find("itinerario.id", Sort.by("ordine"), itinerarioId).list();
        List<String> stazioni = new ArrayList<>();
        if (!tratte.isEmpty()) {
            stazioni.add(tratte.get(0).tratta.stazionePartenza.id);
            for (ItinerarioTratta it : tratte) {
                stazioni.add(it.tratta.stazioneArrivo.id);
            }
        }
        return stazioni;
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

        for (int i = 0; i < stazioni.size() - 1; i++) {
            String partenzaId = stazioni.get(i);
            String arrivoId = stazioni.get(i + 1);
            Stazione partenza = Stazione.findById(partenzaId);
            Stazione arrivo = Stazione.findById(arrivoId);
            if (partenza == null || arrivo == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("errore", "Stazione inesistente: " + (partenza == null ? partenzaId : arrivoId)))
                        .build();
            }
            // La tratta è ORIENTATA: l'arco A->B non vale come B->A, e infatti in rete
            // i due versi sono due righe distinte.
            long esiste = Tratta.count("stazionePartenza.id = ?1 and stazioneArrivo.id = ?2",
                    partenzaId, arrivoId);
            if (esiste == 0) {
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

            Tratta tratta = Tratta.find("stazionePartenza.id = ?1 and stazioneArrivo.id = ?2",
                    partenzaId, arrivoId).firstResult();
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
                long usataAltrove = ItinerarioTratta.count(
                        "id.idTratta = ?1 and id.idItinerario <> ?2", tratta.id, itinerario.id);
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
            riga.persist();
        }
        return null;
    }

    /**
     * Assegna i treni indicati all'itinerario. Se {@code sganciaAssenti} è true
     * (caso PUT), i treni prima assegnati e non più presenti vengono scollegati.
     */
    private void assegnaTreni(Itinerario itinerario, List<String> treniIds, boolean sganciaAssenti) {
        if (sganciaAssenti) {
            for (Treno t : Treno.<Treno>list("itinerario.id", itinerario.id)) {
                if (treniIds == null || !treniIds.contains(t.id)) {
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
            Treno dbTreno = Treno.findById(trenoId);
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
        List<ItinerarioTratta> tratte = ItinerarioTratta
                .find("itinerario.id", Sort.by("ordine"), it.id).list();

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
    private void chiudiGuasto(Guasto guasto) {
        guasto.risolto = true;
        guasto.timestampRisoluzione = Instant.now();

        StoricoGuasto storico = StoricoGuasto.find("guasto", guasto).firstResult();
        if (storico != null) {
            storico.risolto = true;
            storico.tsChiusura = guasto.timestampRisoluzione;
        } else {
            storico = new StoricoGuasto();
            storico.guasto = guasto;
            storico.risolto = true;
            storico.tsApertura = guasto.timestamp != null ? guasto.timestamp : Instant.now();
            storico.tsChiusura = guasto.timestampRisoluzione;
            storico.persist();
        }
        statoRete.risolviGuasto(guasto.id);
    }

    /** Pubblica su MQTT l'evento RESOLVED con la sorgente reale del guasto. */
    private void pubblicaResolved(Guasto guasto) {
        String sorgenteTipo = guasto.sorgenteTipo != null ? guasto.sorgenteTipo : "STAZIONE";
        String sorgenteId = guasto.sorgenteId != null ? guasto.sorgenteId : "";
        String alertJson = String.format(
                "{\"tipoEvento\":\"RESOLVED\",\"sorgenteTipo\":\"%s\",\"sorgenteId\":\"%s\",\"guastoId\":\"%s\",\"timestamp\":\"%s\"}",
                sorgenteTipo, sorgenteId, guasto.id, Instant.now().toString());
        alertsEmitter.send(alertJson);
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
