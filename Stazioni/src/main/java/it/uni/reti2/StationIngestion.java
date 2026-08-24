package it.uni.reti2;

import it.uni.reti2.DBLocale;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    @Inject
    PersistenzaLocale persistenza;

    /** Sorveglianza dei keepalive: è lui a tenere il conto dei battiti dei sensori. */
    @Inject
    MonitorSensori monitorSensori;

    /** I due sensori simulati del binario: si accendono e si spengono per le prove. */
    @Inject
    SimulatoreSensori simulatoreSensori;

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
            "stato", dbLocale.getStato(),
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

        if (!"ENTRATA".equals(tipo) && !"USCITA".equals(tipo)) {
            return Response.status(400).entity("tipo deve essere ENTRATA o USCITA").build();
        }

        // Registrazione del transito: fotografia dei binari, notifica alla Centrale e,
        // se la stazione è guasta, alert che trattiene il convoglio appena entrato.
        stationGateway.registraTransitoRilevato(trenoId, tipo);

        return Response.ok(Map.of("success", true, "messaggio", "Transito rilevato")).build();
    }

    /**
     * Riceve la segnalazione di un'avaria da un sottosistema locale (scambi, alimentazione,
     * un binario). Mette la stazione in stato di emergenza.
     *
     * <p>Il campo facoltativo {@code tipoGuasto} distingue un guasto dell'intera stazione
     * ({@code stazione_guasta}, il default se il campo manca) da un guasto di un singolo
     * binario/scambio ({@code binario_guasto}). Chi chiama sa che cosa si è rotto, quindi
     * lo dichiara qui invece di lasciare che la Centrale lo indovini: la classificazione
     * finale comunque la valida la Centrale.</p>
     *
     * @param dati Mappa con "descrizione" (facoltativa) e "tipoGuasto" (facoltativo) del guasto.
     * @return Conferma ricezione.
     */
    @POST
    @Path("/sensore/guasto")
    public Response rilevaGuasto(Map<String, String> dati) {
        String descrizione = dati.getOrDefault("descrizione", "Guasto generico sensore");
        String tipoGuasto = dati.get("tipoGuasto");
        // Delega al gateway la notifica immediata alla Centrale Operativa
        stationGateway.inviaGuasto(descrizione, "CRITICAL", tipoGuasto);
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

        // Il battito lo prende in carico il monitor: aggiorna l'orologio del sensore e,
        // se quel sensore era dato per fermo, fa tornare operativa la stazione.
        monitorSensori.registraBattito(sensoreId);
        return Response.ok(Map.of("success", true, "messaggio", "Keepalive registrato per " + sensoreId)).build();
    }

    /**
     * Elenco dei sensori di binario con il loro stato di salute visto dalla stazione.
     * È quello che la CLI stampa prima di chiedere su quale sensore intervenire.
     *
     * @return Response JSON con un elemento per sensore.
     */
    @GET
    @Path("/sensori")
    public Response getSensori() {
        List<Map<String, String>> elenco = new ArrayList<>();
        for (SensoreBinario sensore : simulatoreSensori.elenco()) {
            Instant ultimoBattito = dbLocale.sensoriUltimoBattito.get(sensore.getId());
            Map<String, String> riga = new LinkedHashMap<>();
            riga.put("sensoreId", sensore.getId());
            riga.put("tipo", sensore.getTipo().name());
            riga.put("binario", String.valueOf(sensore.getBinario()));
            // "acceso": sta mandando i keepalive. "guasto": la stazione non li riceve più.
            riga.put("acceso", String.valueOf(sensore.isAttivo()));
            riga.put("guasto", String.valueOf(dbLocale.sensoriGuasti.contains(sensore.getId())));
            riga.put("ultimoBattito", String.valueOf(ultimoBattito));
            elenco.add(riga);
        }

        Map<String, Object> risposta = new LinkedHashMap<>();
        risposta.put("stazioneId", dbLocale.stazioneId);
        risposta.put("stato", dbLocale.getStato());
        risposta.put("guastoDaSensori", dbLocale.guastoDaSensori);
        risposta.put("sensori", elenco);
        return Response.ok(risposta).build();
    }

    /**
     * Spegne un sensore simulato: da qui in poi non manda più il keepalive e non rileva
     * più i treni (fail-stop del dispositivo). Serve alle prove: passato il timeout la
     * stazione deve dichiararsi GUASTA da sola.
     *
     * @param sensoreId Id del sensore, anche solo "IN" o "OUT".
     * @return Conferma dello spegnimento.
     */
    @POST
    @Path("/sensori/{sensoreId}/stop")
    public Response fermaSensore(@PathParam("sensoreId") String sensoreId) {
        if (!simulatoreSensori.ferma(sensoreId)) {
            return Response.status(404).entity("Sensore non trovato: " + sensoreId).build();
        }
        return Response.ok(Map.of("success", true,
                "messaggio", "Sensore " + sensoreId + " spento: smette di inviare l'heartbeat")).build();
    }

    /**
     * Riaccende un sensore simulato: col primo keepalive che riparte la stazione torna
     * operativa, sempre che non ci siano altri sensori muti.
     *
     * @param sensoreId Id del sensore, anche solo "IN" o "OUT".
     * @return Conferma della riaccensione.
     */
    @POST
    @Path("/sensori/{sensoreId}/start")
    public Response riavviaSensore(@PathParam("sensoreId") String sensoreId) {
        if (!simulatoreSensori.riprendi(sensoreId)) {
            return Response.status(404).entity("Sensore non trovato: " + sensoreId).build();
        }
        return Response.ok(Map.of("success", true,
                "messaggio", "Sensore " + sensoreId + " riacceso: riprende a inviare l'heartbeat")).build();
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
        List<Map<String, String>> treni = dbLocale.getTreniPresenti().entrySet().stream()
                .map(e -> Map.of(
                        "trenoId", e.getKey(),
                        "dentroDa", e.getValue().toString()))
                .collect(Collectors.toList());

        return Response.ok(Map.of("treniPresenti", treni)).build();
    }

    /**
     * Mostra come sta messa la persistenza locale del nodo: dove sta il file SQLite,
     * se le scritture stanno davvero andando su disco e cosa c'è dentro adesso.
     * Serve per la dimostrazione del meccanismo: si guarda questa risposta, si ammazza
     * il processo, lo si riavvia e la si riguarda per vedere che i numeri sono gli stessi.
     *
     * @return Response JSON con lo stato della persistenza locale.
     */
    @GET
    @Path("/persistenza")
    public Response getPersistenza() {
        Map<String, Object> risposta = new LinkedHashMap<>();
        risposta.put("attiva", persistenza.isAttiva());
        risposta.put("file", persistenza.getPercorsoDb());
        risposta.put("stato", dbLocale.getStato());
        risposta.put("inizioUltimoFault", String.valueOf(dbLocale.getInizioUltimoFault()));
        risposta.put("fineUltimoFault", String.valueOf(dbLocale.getFineUltimoFault()));
        risposta.put("guastiLocaliAperti", persistenza.contaGuastiAperti());
        risposta.put("treniPresenti", dbLocale.getTreniPresenti().size());
        risposta.put("eventiInCoda", localBuffer.size());
        return Response.ok(risposta).build();
    }
}
