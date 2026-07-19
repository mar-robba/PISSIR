package it.uni.reti2.gateway;

import it.uni.reti2.entity.*;
import it.uni.reti2.elaboration.TrafficLogicEngine;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Fornisce indicatori chiave di prestazione (KPI) per la dashboard di riepilogo.
     * @return Statistiche sommarie su treni e stazioni (in viaggio, emergenza, etc.).
     */
    @GET
    @Path("/dashboard")
    public Response getDashboard() {
        Map<String, Object> kpi = new HashMap<>();
        List<Treno> treni = statoRete.getTuttiTreni();
        List<Stazione> stazioni = statoRete.getTutteStazioni();
        
        long treniInViaggio = treni.stream().filter(t -> "IN_VIAGGIO".equals(t.stato)).count();
        long treniEmergenza = treni.stream().filter(t -> "EMERGENZA".equals(t.stato)).count();
        long stazioniOffline = stazioni.stream().filter(s -> "OFFLINE".equals(s.stato) || "GUASTA".equals(s.stato)).count();

        kpi.put("treniTotali", treni.size());
        kpi.put("treniInViaggio", treniInViaggio);
        kpi.put("treniEmergenza", treniEmergenza);
        kpi.put("stazioniTotali", stazioni.size());
        kpi.put("stazioniOffline", stazioniOffline);
        kpi.put("allarmiAttivi", statoRete.getGuastiAttivi().stream().filter(g -> !g.risolto).count());
        
        return Response.ok(kpi).build();
    }

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
     * Elenca tutti i treni e la loro telemetria istantanea (dalla RAM).
     * @return Lista di Treni.
     */
    @GET
    @Path("/treni")
    public List<Treno> getTreni() {
        return statoRete.getTuttiTreni();
    }

    /**
     * Recupera l'elenco degli itinerari o tratte dal Database persistente (Panache ORM).
     * @return Lista degli Itinerari.
     */
    @GET
    @Path("/tratte")
    @Transactional
    public List<Map<String, Object>> getTratte() {
        List<Itinerario> itinerari = Itinerario.listAll();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        
        for (Itinerario it : itinerari) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", it.id);
            
            // Trova stazioni ordinate per questo itinerario
            List<ItinerarioTratta> tratte = ItinerarioTratta.find("itinerario.id", io.quarkus.panache.common.Sort.by("ordine"), it.id).list();
            
            List<String> stazioni = new java.util.ArrayList<>();
            if (!tratte.isEmpty()) {
                stazioni.add(tratte.get(0).tratta.stazionePartenza.id);
                for (ItinerarioTratta itTratta : tratte) {
                    stazioni.add(itTratta.tratta.stazioneArrivo.id);
                }
            }
            
            String nome = String.join("-", stazioni);
            if (stazioni.isEmpty()) nome = it.id;
            
            dto.put("nome", nome);
            dto.put("stazioni", stazioni);
            dto.put("attivo", true);
            
            List<Treno> treni = Treno.find("itinerario.id", it.id).list();
            List<String> treniIds = new java.util.ArrayList<>();
            for(Treno t : treni) {
                treniIds.add(t.id);
            }
            dto.put("treniIds", treniIds);
            
            result.add(dto);
        }
        return result;
    }

    /**
     * Crea un nuovo Itinerario (tratta) persistendolo sul database.
     * @param tratta Oggetto Itinerario fornito nel corpo JSON della richiesta.
     * @return HTTP 201 Created se l'operazione ha successo.
     */
    @POST
    @Path("/tratte")
    @Transactional//le operazioni di lettura sono libere, ma quelle di scrittura (insert, update, delete) devono essere annotate con @Transactional.
    public Response createTratta(Itinerario tratta) {
        if (tratta.id == null || tratta.id.isEmpty()) {
            tratta.id = UUID.randomUUID().toString();
        }
        tratta.persist();
        return Response.status(Response.Status.CREATED).entity(tratta).build();
    }

    /**
     * Modifica un itinerario preesistente nel database.
     * @param id L'identificativo univoco della tratta.
     * @param trattaModificata L'entità con i dati nuovi.
     * @return 200 OK oppure 404 Not Found.
     */
    @PUT
    @Path("/tratte/{id}")
    @Transactional
    public Response updateTratta(@PathParam("id") String id, Itinerario trattaModificata) {
        Itinerario tratta = Itinerario.findById(id);
        if (tratta == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        tratta.nome = trattaModificata.nome;
        tratta.stazioni = trattaModificata.stazioni;
        tratta.attivo = trattaModificata.attivo;
        tratta.treniIds = trattaModificata.treniIds;
        return Response.ok(tratta).build();
    }

    /**
     * Elimina logicamente o fisicamente una tratta dal database.
     * @param id L'identificativo della tratta.
     * @return 204 No Content.
     */
    @DELETE
    @Path("/tratte/{id}")
    @Transactional
    public Response deleteTratta(@PathParam("id") String id) {
        Itinerario tratta = Itinerario.findById(id);
        if (tratta == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        tratta.delete();
        return Response.noContent().build();
    }

    /**
     * Restituisce lo storico persistito dei passaggi ai sensori delle stazioni.
     * @return Lista di Transiti dal database.
     */
    @GET
    @Path("/transiti")
    @Transactional
    public List<Map<String, Object>> getTransiti() {
        List<Transito> transiti = Transito.listAll();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Transito t : transiti) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("trenoId", t.treno.id);
            dto.put("stazioneId", t.stazione.id);
            dto.put("tipo", "TRANSIT");
            dto.put("timestamp", t.tempoEntrata != null ? t.tempoEntrata.toString() : null);
            result.add(dto);
        }
        return result;
    }

    /**
     * Restituisce tutti gli allarmi persistiti sul database (storico guasti).
     * @return Lista di entità Guasto.
     */
    @GET
    @Path("/allarmi")
    @Transactional
    public List<Map<String, Object>> getAllarmi() {
        List<Guasto> guasti = Guasto.listAll();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Guasto g : guasti) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", g.id);
            dto.put("risolto", g.risolto);
            dto.put("operatore", g.operatore != null ? g.operatore.matricola : null);
            
            StoricoGuasto sg = StoricoGuasto.find("guasto", g).firstResult();
            if (sg != null) {
                dto.put("timestamp", sg.tsApertura != null ? sg.tsApertura.toString() : null);
                dto.put("timestampRisoluzione", sg.tsChiusura != null ? sg.tsChiusura.toString() : null);
            }
            
            dto.put("tipo", g.tipo != null ? g.tipo : "stazione_guasta");
            dto.put("severita", g.severita != null ? g.severita : "critical");
            dto.put("sorgenteId", g.sorgenteId != null ? g.sorgenteId : (g.id.contains("TRN") ? g.id : "S" + g.id.replaceAll("[^0-9]", "")));
            dto.put("messaggio", g.messaggio != null ? g.messaggio : "Allarme di sistema per " + g.id);
            
            result.add(dto);
        }
        return result;
    }

    /**
     * Operazione per segnare manualmente un guasto come risolto da parte di un operatore di centrale.
     * Notifica l'avvenuta risoluzione verso il campo e aggiorna stato locale e DB.
     *
     * @param id Identificativo del guasto.
     * @return Guasto aggiornato.
     */
    @POST
    @Path("/allarmi/{id}/risolvi")
    @Transactional
    public Response risolviAllarme(@PathParam("id") String id) {
        Guasto guasto = Guasto.findById(id);
        if (guasto != null) {
            guasto.risolto = true;
            guasto.timestampRisoluzione = Instant.now();
            
            StoricoGuasto storico = StoricoGuasto.find("guasto", guasto).firstResult();
            if (storico != null) {
                storico.risolto = true;
                storico.tsChiusura = guasto.timestampRisoluzione;
            }
            
            // Aggiorna la cache
            statoRete.risolviGuasto(id);
            
            // Notifica via MQTT l'avvenuta risoluzione al treno/stazione che ha originato il fault
            String alertJson = String.format("{\"target\":\"ALL\",\"type\":\"RESOLVED\",\"id\":\"%s\"}", id);
            alertsEmitter.send(alertJson);
            
            return Response.ok(guasto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
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
            
            // Aggiorna anche il salvataggio su DB persistente
            Treno dbTreno = Treno.findById(id);
            if (dbTreno != null) {
                dbTreno.stato = "SOPPRESSO";
                dbTreno.velocita = 0;
                
                StoricoStatoTreno storico = new StoricoStatoTreno();
                storico.treno = dbTreno;
                storico.stato = "SOPPRESSO";
                storico.persist();
            }
            
            // Invia asincronamente un comando perentorio (STOP_ALL) sul canale di allarme 
            // affinché il gateway di bordo applichi la frenata.
            String alertJson = String.format("{\"target\":\"%s\",\"type\":\"STOP_ALL\",\"motivo\":\"Soppresso da operatore\"}", id);
            alertsEmitter.send(alertJson);
            
            return Response.ok(treno).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    /**
     * Operazione manuale da parte dell'operatore per inviare una squadra di manutenzione a una stazione.
     * @param id L'ID della stazione.
     * @return Stato della stazione aggiornato.
     */
    @POST
    @Path("/stazioni/{id}/manutenzione")
    @Transactional
    public Response dispacciaManutenzione(@PathParam("id") String id) {
        Stazione stazione = statoRete.getStazione(id);
        if (stazione != null) {
            stazione.stato = "MANUTENZIONE";
            statoRete.aggiornaStazione(stazione);
            
            Stazione dbStazione = Stazione.findById(id);
            if (dbStazione != null) {
                dbStazione.stato = "MANUTENZIONE";
                
                StoricoStatoStazione storico = new StoricoStatoStazione();
                storico.stazione = dbStazione;
                storico.nome = dbStazione.nome;
                storico.tipo = dbStazione.tipoCapolineaPartenzaoNormale;
                storico.funzionanteONo = false;
                storico.persist();
            }
            
            // Invia notifica
            String alertJson = String.format("{\"target\":\"%s\",\"type\":\"MAINTENANCE_DISPATCHED\",\"motivo\":\"Operatori inviati\"}", id);
            alertsEmitter.send(alertJson);
            
            return Response.ok(stazione).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
