package it.uni.reti2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.javalin.http.Context;

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
 *
 * <p>È un generatore di eventi: quello che dichiarano i sensori (un keepalive, un transito,
 * un guasto) e le due leve della demo sul collegamento con la Centrale diventano un
 * EventoStazione per GestoreEventi. Le GET invece leggono lo stato e basta.</p>
 *
 * <p>Ogni metodo è un gestore di Javalin: riceve il Context della richiesta (da cui si
 * legge il corpo) e ci scrive la risposta. Quale indirizzo porta a quale metodo lo dice la
 * tabella delle rotte in ServerRest.</p>
 */
public class StationIngestion {

    /** Stato del nodo: assegnato dal main, come gli altri campi qui sotto. */
    DBLocale dbLocale;

    /** La coda verso la Centrale, per GET /buffer e GET /persistenza. */
    LocalBuffer localBuffer;

    /** Il db locale, per GET /persistenza. */
    PersistenzaLocale persistenza;

    /** Dove vanno gli eventi dichiarati dai sensori. */
    GestoreEventi gestore;

    /** I due sensori simulati del binario: si accendono e si spengono per le prove. */
    SimulatoreSensori simulatoreSensori;

    /**
     * Recupera le informazioni operative della stazione.
     * Risponde con stato e flag di connettività in formato JSON.
     */
    public void info(Context ctx) {
        ctx.json(Map.of(
            "stazioneId", dbLocale.stazioneId,
            "stato", dbLocale.getStato(),
            "connessioneCentrale", dbLocale.connessioneCentrale
        ));
    }

    /**
     * Alias endpoint per convenienza operativa, identico a /info.
     */
    public void getStato(Context ctx) {
        info(ctx);
    }

    /**
     * Consente l'ispezione della coda locale per scopi di debugging.
     * È utile per vedere quali eventi non sono ancora stati consegnati alla Centrale:
     * a collegamento attivo di solito è vuota, perché il consumatore la svuota subito.
     * Risponde con dimensione e contenuto degli eventi in coda.
     */
    public void getBuffer(Context ctx) {
        ctx.json(Map.of(
            "dimensione", localBuffer.size(),
            "eventi", localBuffer.getBuffer()
        ));
    }

    /**
     * Il segnale di un sensore di binario che rileva il transito di un convoglio.
     * Può essere chiamato dai veri attuatori/sensori fisici per iniettare l'evento nel sistema.
     * Il corpo contiene "trenoId" e il "tipo" di transito (ENTRATA o USCITA).
     */
    public void rilevaTransitoTreno(Context ctx) {
        JsonObject dati = corpo(ctx);
        String trenoId = CampiJson.testo(dati, "trenoId", null);
        String tipo = CampiJson.testo(dati, "tipo", null); // ENTRATA o USCITA

        // Verifica dei campi mandatori
        if (trenoId == null || tipo == null) {
            ctx.status(400).result("trenoId e tipo sono obbligatori");
            return;
        }

        if (!"ENTRATA".equals(tipo) && !"USCITA".equals(tipo)) {
            ctx.status(400).result("tipo deve essere ENTRATA o USCITA");
            return;
        }

        // Registrazione del transito: fotografia dei binari, notifica alla Centrale e,
        // se la stazione è guasta, alert che trattiene il convoglio appena entrato.
        gestore.gestisci(EventoStazione.transito(trenoId, tipo));

        ctx.json(Map.of("success", true, "messaggio", "Transito rilevato"));
    }

    /**
     * Riceve la segnalazione di un'avaria da un sottosistema locale (scambi, alimentazione,
     * un binario). Mette la stazione in stato di emergenza.
     *
     * <p>Il campo facoltativo {@code tipoGuasto} distingue un guasto dell'intera stazione
     * ({@code stazione_guasta}, il default se il campo manca) da un guasto di un singolo
     * binario/scambio ({@code binario_guasto}). Chi chiama sa che cosa si è rotto, quindi
     * lo dichiara qui invece di lasciare che la Centrale lo indovini: la classificazione
     * finale comunque la valida la Centrale. L'altro campo facoltativo è "descrizione".</p>
     */
    public void rilevaGuasto(Context ctx) {
        JsonObject dati = corpo(ctx);
        String descrizione = CampiJson.testo(dati, "descrizione", "Guasto generico sensore");
        String tipoGuasto = CampiJson.testo(dati, "tipoGuasto", null);
        // Il gestore porta la stazione a GUASTA e accoda l'allarme per la Centrale Operativa
        gestore.gestisci(EventoStazione.guastoRilevato(descrizione, tipoGuasto));
        ctx.json(Map.of("success", true, "messaggio", "Guasto inviato e stato aggiornato a GUASTA"));
    }

    /**
     * Riceve il segnale di keepalive periodico di un sensore di binario (corpo con
     * "sensoreId"). Se un sensore smette di chiamare questo endpoint verrà segnalato come
     * guasto dal giro di controllo periodico.
     */
    public void heartbeatSensore(Context ctx) {
        JsonObject dati = corpo(ctx);
        String sensoreId = CampiJson.testo(dati, "sensoreId", null);

        // Verifica del campo mandatorio
        if (sensoreId == null || sensoreId.isBlank()) {
            ctx.status(400).result("sensoreId è obbligatorio");
            return;
        }

        // Il battito aggiorna l'orologio del sensore e, se quel sensore era dato per fermo,
        // può far tornare operativa la stazione: lo decide il gestore.
        gestore.gestisci(EventoStazione.battito(sensoreId));
        ctx.json(Map.of("success", true, "messaggio", "Keepalive registrato per " + sensoreId));
    }

    /**
     * Elenco dei sensori di binario con il loro stato di salute visto dalla stazione.
     * È quello che la CLI stampa prima di chiedere su quale sensore intervenire.
     */
    public void getSensori(Context ctx) {
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
        ctx.json(risposta);
    }

    /**
     * Spegne un sensore simulato: da qui in poi non manda più il keepalive e non rileva
     * più i treni (fail-stop del dispositivo). Serve alle prove: passato il timeout la
     * stazione deve dichiararsi GUASTA da sola. L'id nel percorso può essere anche solo
     * "IN" o "OUT".
     */
    public void fermaSensore(Context ctx) {
        String sensoreId = ctx.pathParam("sensoreId");
        if (!simulatoreSensori.ferma(sensoreId)) {
            ctx.status(404).result("Sensore non trovato: " + sensoreId);
            return;
        }
        ctx.json(Map.of("success", true,
                "messaggio", "Sensore " + sensoreId + " spento: smette di inviare l'heartbeat"));
    }

    /**
     * Riaccende un sensore simulato: col primo keepalive che riparte la stazione torna
     * operativa, sempre che non ci siano altri sensori muti o altri guasti aperti.
     */
    public void riavviaSensore(Context ctx) {
        String sensoreId = ctx.pathParam("sensoreId");
        if (!simulatoreSensori.riprendi(sensoreId)) {
            ctx.status(404).result("Sensore non trovato: " + sensoreId);
            return;
        }
        ctx.json(Map.of("success", true,
                "messaggio", "Sensore " + sensoreId + " riacceso: riprende a inviare l'heartbeat"));
    }

    /**
     * Simula la caduta della connessione verso la Centrale Operativa.
     * Da questo momento gli eventi restano nel buffer locale
     * e l'heartbeat non viene più emesso (demo del meccanismo di caching).
     */
    public void reteOffline(Context ctx) {
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.RETE_GIU));
        ctx.json(Map.of("success", true, "messaggio", "Connessione verso la Centrale disattivata (simulazione)"));
    }

    /**
     * Simula il ripristino della connessione verso la Centrale Operativa.
     * Gli eventi accodati durante l'offline ripartono da soli, in ordine: li manda il
     * consumatore del buffer appena vede il collegamento di nuovo attivo, quindi la risposta
     * torna subito, senza aspettare che la coda sia vuota.
     */
    public void reteOnline(Context ctx) {
        gestore.gestisci(EventoStazione.di(EventoStazione.Tipo.RETE_SU));
        ctx.json(Map.of("success", true, "messaggio", "Connessione ripristinata: gli eventi nel buffer ripartono verso la Centrale"));
    }

    /**
     * Restituisce l'elenco dei treni fisicamente presenti in stazione,
     * con l'istante di ingresso di ciascuno.
     */
    public void getTreniPresenti(Context ctx) {
        List<Map<String, String>> treni = dbLocale.getTreniPresenti().entrySet().stream()
                .map(e -> Map.of(
                        "trenoId", e.getKey(),
                        "dentroDa", e.getValue().toString()))
                .collect(Collectors.toList());

        ctx.json(Map.of("treniPresenti", treni));
    }

    /**
     * Mostra come sta messa la persistenza locale del nodo: dove sta il file SQLite,
     * se le scritture stanno davvero andando su disco e cosa c'è dentro adesso.
     * Serve per la dimostrazione del meccanismo: si guarda questa risposta, si ammazza
     * il processo, lo si riavvia e la si riguarda per vedere che i numeri sono gli stessi.
     */
    public void getPersistenza(Context ctx) {
        Map<String, Object> risposta = new LinkedHashMap<>();
        risposta.put("attiva", persistenza.isAttiva());
        risposta.put("file", persistenza.getPercorsoDb());
        risposta.put("stato", dbLocale.getStato());
        risposta.put("inizioUltimoFault", String.valueOf(dbLocale.getInizioUltimoFault()));
        risposta.put("fineUltimoFault", String.valueOf(dbLocale.getFineUltimoFault()));
        risposta.put("guastiLocaliAperti", persistenza.contaGuastiAperti());
        risposta.put("treniPresenti", dbLocale.getTreniPresenti().size());
        risposta.put("eventiInCoda", localBuffer.size());
        ctx.json(risposta);
    }

    /**
     * Il corpo della richiesta come oggetto JSON. Un corpo vuoto vale come oggetto vuoto (i
     * campi mancanti li controlla ogni endpoint); un corpo che non è un oggetto JSON fa
     * rispondere 400, con il gestore di JsonParseException registrato in ServerRest.
     */
    private static JsonObject corpo(Context ctx) {
        String testo = ctx.body();
        if (testo == null || testo.isBlank()) {
            return new JsonObject();
        }
        JsonElement elemento = JsonParser.parseString(testo);
        if (!elemento.isJsonObject()) {
            throw new JsonParseException("il corpo non e' un oggetto JSON");
        }
        return elemento.getAsJsonObject();
    }
}
