package it.uni.reti2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Scarica dalla Centrale Operativa l'itinerario assegnato al treno
 * (GET /api/treni/{id}/itinerario) usando il client HTTP del JDK.
 *
 * <p>Fa solo la parte di rete: chiede, interpreta la risposta e dice com'è andata con un
 * {@link EsitoCaricamento}, come fa TrainDatabaseValidator con EsitoVerifica. Nel TrainDB non
 * scrive niente: l'esito lo trasforma in un evento GeneratoreEventiTempo, e che cosa fare
 * dell'itinerario (installarlo, tenerlo in attesa perché il treno è in mezzo a una tratta,
 * buttare quello vecchio) lo decide GestoreEventi, che è l'unico a sapere dove si trova il
 * convoglio.</p>
 *
 * <p>Se la Centrale non risponde (spenta, errore, itinerario non valido) si riprova al
 * massimo ogni 15 secondi.</p>
 */
public class CaricatoreItinerario {

    private static final Logger LOG = Logger.getLogger(CaricatoreItinerario.class.getName());

    /** Intervallo tra due tentativi di caricamento itinerario (secondi). */
    private static final long RETRY_SECONDI = 15;

    /** Com'è andato un tentativo di caricamento. */
    public enum EsitoCaricamento {
        /** Arrivato un itinerario valido: lo restituisce {@link #ultimoCaricato()}. */
        CARICATO,
        /** HTTP 404: la Centrale non assegna (più) nessun itinerario a questo treno. */
        NON_ASSEGNATO,
        /** Niente di nuovo: troppo presto, Centrale non raggiungibile o risposta non valida. */
        RIPROVA
    }

    /** Stato del treno: serve l'id per l'URL. */
    TrainDB trainDB;

    /**
     * Client HTTP per parlare con la Centrale. Sotto profilo tls l'URL è HTTPS e il
     * certificato viene validato contro la CA privata del progetto; in profilo di
     * default la stessa istanza fa normali richieste in chiaro sulla 8781.
     */
    SecureHttpClient secureHttpClient;

    /** URL base della Centrale Operativa (es. http://localhost:8781), property centrale.url. */
    String centraleUrl;

    /** Istante dell'ultimo tentativo (fallito o no) di caricamento itinerario. */
    private Instant ultimoTentativo = Instant.EPOCH;

    /** L'ultimo itinerario valido arrivato dalla Centrale. */
    private Itinerario ultimoCaricato = null;

    /**
     * Chiede alla Centrale l'itinerario del treno, se dall'ultimo tentativo sono passati
     * almeno 15 secondi. Insieme alle tappe legge lo stato di ogni fermata, che viaggia
     * dentro l'Itinerario.
     *
     * @return CARICATO se è arrivato un itinerario valido, NON_ASSEGNATO se la Centrale
     *         risponde 404, RIPROVA in tutti gli altri casi.
     */
    public EsitoCaricamento caricaSeNecessario() {
        // -- per non intasare il sistema di richieste dell'itinerario
        Instant adesso = Instant.now();
        if (Duration.between(ultimoTentativo, adesso).getSeconds() < RETRY_SECONDI) {
            return EsitoCaricamento.RIPROVA; // troppo presto per riprovare
        }
        ultimoTentativo = adesso;

        // richiesta http
        String url = centraleUrl + "/api/treni/" + trainDB.trenoId + "/itinerario";
        try {
            HttpRequest richiesta = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> risposta = secureHttpClient.get().send(richiesta, HttpResponse.BodyHandlers.ofString());

            // 404 = la Centrale non mi assegna (più) nessun itinerario: è il caso del convoglio
            // sganciato dall'amministratore. Che cosa fare del percorso in memoria lo decide il
            // gestore degli eventi (ITINERARIO_REVOCATO).
            if (risposta.statusCode() == 404) {
                return EsitoCaricamento.NON_ASSEGNATO;
            }

            if (risposta.statusCode() != 200) {
                LOG.warning(String.format("⚠️ [TWIN] Itinerario non disponibile (HTTP %d da %s), riprovo tra %ds",
                        risposta.statusCode(), url, RETRY_SECONDI));
                return EsitoCaricamento.RIPROVA;
            }

            // todo: controllla un po meglio come è sto modello che mi sembra un po strano questo inserimento dei dati

            //  --- si riempie il db locale delle informaizoni riguardo alla tratta
            // Parsing della risposta: {"itinerarioId":"...","capolineaId":"...","stazioni":[...]}
            JsonObject radice = JsonParser.parseString(risposta.body()).getAsJsonObject();
            List<TrainDB.TappaItinerario> tappe = new ArrayList<>();
            Map<String, String> statoFermate = new HashMap<>();
            // per ogni tappa nel json immetterla nella lista tappe
            for (JsonElement elemento : CampiJson.lista(radice, "stazioni")) {
                JsonObject nodo = elemento.getAsJsonObject();
                TrainDB.TappaItinerario tappa = new TrainDB.TappaItinerario(
                        CampiJson.testo(nodo, "id", ""),
                        CampiJson.testo(nodo, "nome", ""),
                        CampiJson.decimale(nodo, "latitudine", 0),
                        CampiJson.decimale(nodo, "longitudine", 0),
                        CampiJson.intero(nodo, "tempoVersoProssimaMinuti", 0));
                tappe.add(tappa);
                // Insieme al percorso arriva anche lo stato di ogni fermata. Serve perché gli
                // alert su railway/alerts li sente solo chi era acceso quando sono passati: un
                // convoglio che si avvia adesso, senza questa riga, non saprebbe che una
                // stazione della sua tratta è chiusa (guasta o in manutenzione) e ci andrebbe
                // dentro lo stesso. Nel TrainDB lo annota il gestore degli eventi.
                String statoTappa = CampiJson.testo(nodo, "stato", "");
                if (!statoTappa.isEmpty()) {
                    statoFermate.put(tappa.id, statoTappa);
                }
            }

            // un contrllo del cavolo sulla validità dell'itinerario,  e si dovrebbe attendere che la stazione riflagghi vera la ricaricaItinerarioRichiesta se no il treno rimane fermo
            if (tappe.size() < 2) {
                LOG.warning(String.format("⚠️ [TWIN] Itinerario con meno di 2 stazioni (%d): impossibile viaggiare, riprovo", tappe.size()));
                return EsitoCaricamento.RIPROVA;
            }

            // capolineaId assente o null = itinerario da percorrere all'infinito, cioè il
            // comportamento che il twin ha sempre avuto.
            ultimoCaricato = new Itinerario(CampiJson.testo(radice, "itinerarioId", null), tappe,
                    CampiJson.testo(radice, "capolineaId", null), statoFermate);
            LOG.info(String.format("🗺️ [TWIN] Itinerario %s caricato: %d stazioni %s",
                    ultimoCaricato.id(), tappe.size(), ultimoCaricato.ids()));
            return EsitoCaricamento.CARICATO;
        } catch (InterruptedException e) {
            // immagino totalmente di best practise, niente manda realmente interrupt a questo thread
            Thread.currentThread().interrupt();
            return EsitoCaricamento.RIPROVA;
        } catch (Exception e) {
            // sarebbe qualsiasi altra eccezione
            LOG.warning(String.format("⚠️ [TWIN] Centrale non raggiungibile (%s), riprovo tra %ds: %s",
                    url, RETRY_SECONDI, e.getMessage()));
            return EsitoCaricamento.RIPROVA;
        }
    }

    /**
     * @return L'itinerario arrivato con l'ultimo tentativo andato a buon fine (esito CARICATO).
     */
    public Itinerario ultimoCaricato() {
        return ultimoCaricato;
    }

    /**
     * Fa partire il prossimo tentativo subito, senza aspettare i 15 secondi: serve quando la
     * Centrale avvisa che l'itinerario è cambiato (ITINERARIO_AGGIORNATO).
     */
    public void azzeraAttesa() {
        ultimoTentativo = Instant.EPOCH; // un modo più elegante per dire 0
    }
}
