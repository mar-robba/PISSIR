package it.uni.reti2;

import it.uni.reti2.DBLocale;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller REST che espone le API del nodo Edge Stazione.
 * Queste interfacce permettono l'interrogazione dello stato locale e 
 * l'invocazione di eventi diagnostici / simulati, per esempio da parte 
 * dei sensori RFID/boelis posizionati lungo i binari, o da terminali di manutenzione.
 */
@Path("/stazione")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StationIngestion {

    @Inject
    DBLocale dbLocale;

    @Inject
    LocalBuffer localBuffer;

    @Inject
    StationGateway stationGateway;

    /**
     * Recupera le informazioni operative della stazione.
     *
     * @return Response HTTP contenente stato e flag di connettività in formato JSON.
     */
    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(Map.of(
            "stazioneId", dbLocale.stazioneId,
            "stato", dbLocale.stato,
            "connessioneCentrale", dbLocale.connessioneCentrale
        )).build();
    }

    /**
     * Alias endpoint per convenienza operativa, identico a /info.
     */
    @GET
    @Path("/stato")
    public Response getStato() {
        return info();
    }

    /**
     * Consente l'ispezione della coda locale per scopi di debugging.
     * È utile per vedere quali eventi non sono ancora stati inviati in caso di offline.
     *
     * @return Response con dimensione e contenuto degli eventi in coda.
     */
    @GET
    @Path("/buffer")
    public Response getBuffer() {
        return Response.ok(Map.of(
            "dimensione", localBuffer.size(),
            "eventi", localBuffer.getBuffer()
        )).build();
    }

    /**
     * Simula il segnale di un sensore di binario che rileva il transito di un convoglio.
     * Può essere chiamato dai veri attuatori/sensori fisici per iniettare l'evento nel sistema.
     *
     * @param dati Payload contenente "trenoId" e il "tipo" di transito (ENTRATA o USCITA).
     * @return Response HTTP sull'esito dell'operazione.
     */
    @POST
    @Path("/sensore/treno")
    public Response rilevaTransitoTreno(Map<String, String> dati) {
        String trenoId = dati.get("trenoId");
        String tipo = dati.get("tipo"); // ENTRATA o USCITA
        
        // Verifica dei campi mandatori
        if (trenoId == null || tipo == null) {
            return Response.status(400).entity("trenoId e tipo sono obbligatori").build();
        }

        // Delega al Gateway l'invio asincrono verso il Cloud
        stationGateway.inviaTransito(trenoId, tipo);

        return Response.ok(Map.of("success", true, "messaggio", "Transito rilevato")).build();
    }

    /**
     * Riceve la segnalazione di un'avaria da un sottosistema locale (scambi, alimentazione).
     * Mette la stazione in stato di emergenza.
     *
     * @param dati Mappa con eventuale parametro "descrizione" del guasto.
     * @return Conferma ricezione.
     */
    @POST
    @Path("/sensore/guasto")
    public Response rilevaGuasto(Map<String, String> dati) {
        String descrizione = dati.getOrDefault("descrizione", "Guasto generico sensore");
        // Delega al gateway la notifica immediata alla Centrale Operativa
        stationGateway.inviaGuasto(descrizione);
        return Response.ok(Map.of("success", true, "messaggio", "Guasto inviato e stato aggiornato a GUASTA")).build();
    }

    /**
     * Riceve il segnale di keepalive periodico di un sensore di binario.
     * Aggiorna la mappa locale sensore -> ultimo battito; se un sensore smette
     * di chiamare questo endpoint verrà segnalato come guasto dal controllo periodico.
     *
     * @param dati Payload contenente "sensoreId".
     * @return Conferma ricezione del battito.
     */
    @POST
    @Path("/sensore/heartbeat")
    public Response heartbeatSensore(Map<String, String> dati) {
        String sensoreId = dati.get("sensoreId");

        // Verifica del campo mandatorio
        if (sensoreId == null || sensoreId.isBlank()) {
            return Response.status(400).entity("sensoreId è obbligatorio").build();
        }

        dbLocale.sensoriUltimoBattito.put(sensoreId, Instant.now());
        return Response.ok(Map.of("success", true, "messaggio", "Keepalive registrato per " + sensoreId)).build();
    }

    /**
     * Simula la caduta della connessione verso la Centrale Operativa.
     * Da questo momento gli eventi vengono accodati nel buffer locale
     * e l'heartbeat non viene più emesso (demo del meccanismo di caching).
     *
     * @return Conferma del cambio di modalità.
     */
    @POST
    @Path("/rete/offline")
    public Response reteOffline() {
        dbLocale.connessioneCentrale = false;
        return Response.ok(Map.of("success", true, "messaggio", "Connessione verso la Centrale disattivata (simulazione)")).build();
    }

    /**
     * Simula il ripristino della connessione verso la Centrale Operativa.
     * Oltre a riattivare il flag esegue subito il flush del buffer locale,
     * reinviando tutti gli eventi accodati durante l'offline.
     *
     * @return Conferma del cambio di modalità.
     */
    @POST
    @Path("/rete/online")
    public Response reteOnline() {
        dbLocale.connessioneCentrale = true;
        // Flush immediato: gli eventi accumulati partono subito verso la Centrale
        stationGateway.flush();
        return Response.ok(Map.of("success", true, "messaggio", "Connessione ripristinata, buffer svuotato")).build();
    }

    /**
     * Restituisce l'elenco dei treni fisicamente presenti in stazione,
     * con l'istante di ingresso di ciascuno.
     *
     * @return Response JSON con la lista dei treni presenti.
     */
    @GET
    @Path("/treni")
    public Response getTreniPresenti() {
        List<Map<String, String>> treni = dbLocale.treniPresenti.entrySet().stream()
                .map(e -> Map.of(
                        "trenoId", e.getKey(),
                        "dentroDa", e.getValue().toString()))
                .collect(Collectors.toList());

        return Response.ok(Map.of("treniPresenti", treni)).build();
    }
}
