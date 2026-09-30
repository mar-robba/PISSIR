package it.uni.reti2;

import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * l'input dei sensori al treno per il testing aatraverso la rest api come era nello scheletro
 * TrainIngestion funge da Controller REST per esporre le API dirette del singolo Treno.
 * Tramite queste API, altri sistemi (es. il portale di manutenzione locale a bordo, 
 * i sistemi di diagnostica, o la Centrale Operativa se accessibile direttamente via HTTP) 
 * possono interrogare o comandare le funzionalità del treno.
 *
 * <p>Ogni metodo è un gestore di Javalin: riceve il Context della richiesta (da cui si
 * legge il corpo) e ci scrive la risposta. Quale indirizzo porta a quale metodo lo dice la
 * tabella delle rotte in ServerRest.</p>
 */
public class TrainIngestion {

    /** Stato del treno: assegnato dal main, come gli altri campi qui sotto. */
    TrainDB trainDB;

    /**
     * Il gestore degli eventi del treno: il guasto segnalato dal sensore di bordo diventa
     * un evento per lui, come gli alert del gateway.
     */
    GestoreEventi gestore;

    /**
     * Pubblica i passaggi in stazione verso i sensori di binario, gli stessi che manda il
     * gestore degli eventi quando il treno parte o arriva.
     */
    PubblicatorePassaggi pubblicatorePassaggi;

    /**
     * Endpoint per ottenere le informazioni generali e operative in tempo reale.
     * Risponde con un oggetto JSON con id, stato, coordinate e velocità.
     */
    public void info(Context ctx) {
        ctx.json(Map.of(
            "trenoId", trainDB.trenoId,
            "stato", trainDB.stato(),
            "latitudine", trainDB.latitudine,
            "longitudine", trainDB.longitudine,
            "velocita", trainDB.velocita
        ));
    }

    /**
     * Alias dell'endpoint '/info' orientato specificamente per interrogare lo stato.
     * Risponde come l'endpoint '/info'.
     */
    public void getStato(Context ctx) {
        info(ctx); // Riutilizza la logica di info() per coerenza
    }

    /**
     * Endpoint per interrogare lo stato completo del digital twin del viaggio:
     * lo stato dichiarato alla Centrale, la condizione e la fase dei due automi da cui
     * si calcola, direzione, indice della stazione, progresso sulla tratta, passeggeri e
     * itinerario caricato. Il ritardo compare, ma non e' un conto fatto qui: e' l'ultimo
     * valore che la Centrale ha comunicato al convoglio con l'alert RITARDO, con la
     * stazione e l'ora della misura.
     * Risponde con la fotografia completa del viaggio simulato.
     */
    public void getViaggio(Context ctx) {
        // LinkedHashMap perché Map.of non accetta valori null e non mantiene l'ordine
        Map<String, Object> viaggio = new LinkedHashMap<>();
        viaggio.put("trenoId", trainDB.trenoId);
        viaggio.put("stato", trainDB.stato());
        viaggio.put("condizione", trainDB.condizione);
        viaggio.put("faseViaggio", trainDB.faseViaggio);
        viaggio.put("direzione", trainDB.direzione);
        viaggio.put("indiceStazione", trainDB.indiceStazione);
        viaggio.put("progressPercent", trainDB.progressPercent);
        viaggio.put("passeggeri", trainDB.passeggeri);
        viaggio.put("ritardoMinuti", trainDB.ritardoMinuti);
        viaggio.put("ritardoRilevatoA", trainDB.ritardoRilevatoA != null ? trainDB.ritardoRilevatoA : "");
        viaggio.put("ritardoAggiornatoIl",
                trainDB.ritardoAggiornatoIl != null ? trainDB.ritardoAggiornatoIl.toString() : "");
        viaggio.put("stazioneCorrente", trainDB.stazioneCorrente != null ? trainDB.stazioneCorrente : "");
        viaggio.put("prossimaStazione", trainDB.prossimaStazione != null ? trainDB.prossimaStazione : "");
        viaggio.put("stazioneBloccante", trainDB.stazioneBloccante != null ? trainDB.stazioneBloccante : "");
        viaggio.put("itinerarioCaricato", !trainDB.itinerario.isEmpty());
        viaggio.put("itinerarioId", trainDB.itinerarioId != null ? trainDB.itinerarioId : "");
        viaggio.put("stazioni", trainDB.stazioniTratta);
        viaggio.put("latitudine", trainDB.latitudine);
        viaggio.put("longitudine", trainDB.longitudine);
        viaggio.put("velocita", trainDB.velocita);
        ctx.json(viaggio);
    }

    /**
     * Endpoint per recuperare i dettagli della tratta in cui si trova attualmente il treno.
     * Utile per i display informativi ai passeggeri (PIS - Passenger Information System).
     * Risponde con l'elenco delle stazioni e le informazioni di transito.
     */
    public void getTratta(Context ctx) {
        ctx.json(Map.of(
            "stazioniTratta", trainDB.stazioniTratta,
            "stazioneCorrente", trainDB.stazioneCorrente != null ? trainDB.stazioneCorrente : "",
            "prossimaStazione", trainDB.prossimaStazione != null ? trainDB.prossimaStazione : "",
            // Il ritardo comunicato dalla Centrale: su un display di bordo e' l'unica
            // informazione che i passeggeri guardano davvero.
            "ritardoMinuti", trainDB.ritardoMinuti
        ));
    }

    /**
     * Endpoint per aggiornare la tratta prevista (lista di stazioni) o forzare la prossima stazione.
     * Viene chiamato quando il convoglio riceve un aggiornamento sull'itinerario, 
     * ad esempio in seguito ad una riprogrammazione del traffico da parte della Centrale.
     *
     * Il corpo JSON contiene eventualmente la lista "stazioni" e la stringa "prossimaStazione".
     * Risponde con lo stato aggiornato della tratta chiamando getTratta().
     */
    public void setTratta(Context ctx) {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body.containsKey("stazioni")) {
            // Unchecked cast. In un ambiente di produzione andrebbe validato attentamente
            trainDB.stazioniTratta = (List<String>) body.get("stazioni");
        }
        if (body.containsKey("prossimaStazione")) {
            trainDB.prossimaStazione = (String) body.get("prossimaStazione");
        }
        // Restituisce l'output standard per confermare l'aggiornamento
        getTratta(ctx);
    }

    /**
     * Endpoint per forzare, magari tramite il computer di bordo, un evento di guasto rilevato
     * dai sensori diagnostici del treno (freni, motori, porte, ecc.).
     *
     * Il corpo JSON contiene la "descrizione" del guasto. Risponde con una conferma
     * dell'invio e con lo stato in cui è finito il treno.
     */
    public void rilevaGuasto(Context ctx) {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        // Se non viene specificata una descrizione, usa un placeholder generico
        String desc = (String) body.getOrDefault("descrizione", "Guasto generico treno");

        // L'avaria diventa un evento: l'arresto del treno (EMERGENZA) e l'invio dell'alert
        // alla Centrale li fa il gestore degli eventi
        gestore.gestisci(EventoTreno.guastoDiBordo(desc));

        // Di solito EMERGENZA; un treno gia' soppresso resta soppresso
        ctx.json(Map.of("success", true, "messaggio", "Guasto inviato. Treno in " + trainDB.stato() + "."));
    }

    /**
     * Endpoint per scatenare artificialmente o tramite rilevatori l'evento di entrata o uscita
     * da una determinata stazione (passaggio ai segnali/boelis di stazione).
     * Pubblica soltanto il messaggio per i sensori di binario: il viaggio simulato del
     * treno non si sposta, perché la posizione la scrive solo il suo automa.
     *
     * Il corpo JSON contiene "stazioneId" e "tipo" (ENTRATA/USCITA). Risponde con la conferma
     * dell'operazione, o con Bad Request se mancano dei parametri.
     */
    public void rilevaPassaggio(Context ctx) {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String stazioneId = (String) body.get("stazioneId");
        String tipo = (String) body.get("tipo"); // ENTRATA o USCITA
        
        // Validazione stringente dei parametri di ingresso
        if (stazioneId == null || tipo == null) {
            ctx.status(400).result("stazioneId e tipo sono obbligatori");
            return;
        }
        
        // Delega al pubblicatore per inoltrare l'evento di passaggio ai sensori di binario
        pubblicatorePassaggi.notificaPassaggioStazione(stazioneId, tipo);
        
        ctx.json(Map.of("success", true, "messaggio", "Notifica passaggio inviata"));
    }
}
