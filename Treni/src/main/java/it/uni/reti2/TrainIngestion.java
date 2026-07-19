package it.uni.reti2;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * l'input dei sensori al treno per il testing aatraverso la rest api come era nello scheletro
 * TrainIngestion funge da Controller REST per esporre le API dirette del singolo Treno.
 * Tramite queste API, altri sistemi (es. il portale di manutenzione locale a bordo, 
 * i sistemi di diagnostica, o la Centrale Operativa se accessibile direttamente via HTTP) 
 * possono interrogare o comandare le funzionalità del treno.
 */

@Path("/treno")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TrainIngestion {

    /**
     * 
     * come avvengono davvero le ingnezioni è grande mistero 
     */
    @Inject
    TrainDB trainDB;

    /**
     * Riferimento al Gateway per l'invio reattivo di messaggi ed eventi asincroni (guasti, passaggi).
     */
    @Inject
    TrainGateway trainGateway;

    /**
     * Endpoint per ottenere le informazioni generali e operative in tempo reale.
     * @return Una Response contenente un oggetto JSON con id, stato, coordinate e velocità.
     */
    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(Map.of(
            "trenoId", trainDB.trenoId,
            "stato", trainDB.stato,
            "latitudine", trainDB.latitudine,
            "longitudine", trainDB.longitudine,
            "velocita", trainDB.velocita
        )).build();
    }

    /**
     * Alias dell'endpoint '/info' orientato specificamente per interrogare lo stato.
     * @return Informazioni simili all'endpoint '/info'.
     */
    @GET
    @Path("/stato")
    public Response getStato() {
        return info(); // Riutilizza la logica di info() per coerenza
    }

    /**
     * Endpoint per recuperare i dettagli della tratta in cui si trova attualmente il treno.
     * Utile per i display informativi ai passeggeri (PIS - Passenger Information System).
     * @return Response con l'elenco delle stazioni e le informazioni di transito.
     */
    @GET
    @Path("/tratta")
    public Response getTratta() {
        return Response.ok(Map.of(
            "stazioniTratta", trainDB.stazioniTratta,
            "stazioneCorrente", trainDB.stazioneCorrente != null ? trainDB.stazioneCorrente : "",
            "prossimaStazione", trainDB.prossimaStazione != null ? trainDB.prossimaStazione : ""
        )).build();
    }

    /**
     * Endpoint per aggiornare la tratta prevista (lista di stazioni) o forzare la prossima stazione.
     * Viene chiamato quando il convoglio riceve un aggiornamento sull'itinerario, 
     * ad esempio in seguito ad una riprogrammazione del traffico da parte della Centrale.
     *
     * @param body Payload JSON contenente eventualmente le liste "stazioni" e la stringa "prossimaStazione".
     * @return Lo stato aggiornato della tratta chiamando getTratta().
     */
    @POST
    @Path("/tratta")
    public Response setTratta(Map<String, Object> body) {
        if (body.containsKey("stazioni")) {
            // Unchecked cast. In un ambiente di produzione andrebbe validato attentamente
            trainDB.stazioniTratta = (List<String>) body.get("stazioni");
        }
        if (body.containsKey("prossimaStazione")) {
            trainDB.prossimaStazione = (String) body.get("prossimaStazione");
        }
        // Restituisce l'output standard per confermare l'aggiornamento
        return getTratta();
    }

    /**
     * Endpoint per forzare, magari tramite il computer di bordo, un evento di guasto rilevato
     * dai sensori diagnostici del treno (freni, motori, porte, ecc.).
     *
     * @param body Mappa contenente la "descrizione" del guasto.
     * @return Una Response di conferma invio e arresto treno.
     */
    @POST
    @Path("/sensore/guasto")
    public Response rilevaGuasto(Map<String, String> body) {
        // Se non viene specificata una descrizione, usa un placeholder generico
        String desc = body.getOrDefault("descrizione", "Guasto generico treno");
        
        // Delega al gateway l'invio asincrono in Centrale e l'arresto logico del treno
        String grado = "Grave";
        trainGateway.inviaGuasto(desc,grado);
        
        return Response.ok(Map.of("success", true, "messaggio", "Guasto inviato. Treno in EMERGENZA.")).build();
    }

    /**
     * Endpoint per scatenare artificialmente o tramite rilevatori l'evento di entrata o uscita 
     * da una determinata stazione (passaggio ai segnali/boelis di stazione).
     *
     * @param body Mappa JSON contenente "stazioneId" e "tipo" (ENTRATA/USCITA).
     * @return Response HTTP di avvenuta operazione o Bad Request se parametri mancanti.
     */
    @POST
    @Path("/sensore/passaggio")
    public Response rilevaPassaggio(Map<String, String> body) {
        String stazioneId = body.get("stazioneId");
        String tipo = body.get("tipo"); // ENTRATA o USCITA
        
        // Validazione stringente dei parametri di ingresso
        if (stazioneId == null || tipo == null) {
            return Response.status(400).entity("stazioneId e tipo sono obbligatori").build();
        }
        
        // Delega al gateway per inoltrare l'evento di passaggio alla Centrale
        trainGateway.notificaPassaggioStazione(stazioneId, tipo);
        
        return Response.ok(Map.of("success", true, "messaggio", "Notifica passaggio inviata")).build();
    }
}
