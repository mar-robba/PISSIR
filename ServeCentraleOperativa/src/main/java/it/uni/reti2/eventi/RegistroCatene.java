package it.uni.reti2.eventi;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chi sta reagendo, in questo momento, a quale catena di eventi.
 *
 * <p>È la struttura che fa terminare le catene. La regola è una sola:</p>
 *
 * <blockquote>un nodo reagisce al massimo una volta per ogni catena</blockquote>
 *
 * <p>I nodi sono un insieme finito, quindi una catena produce al massimo un messaggio per nodo e
 * si esaurisce da sola: non serve nessun contatore di salti né una soglia arbitraria da
 * giustificare. La stessa regola taglia i cicli (il convoglio che si ferma per la stazione, la
 * stazione che si guasterebbe di nuovo per il convoglio fermo) e regge la consegna
 * <i>at-least-once</i> di MQTT, dove lo stesso messaggio può arrivare due volte.</p>
 *
 * <p>Il registro sta in memoria e non a database di proposito: è lo stato <i>corrente</i> di una
 * catena, e lo stato corrente in questo sistema vive sempre in cache (la storia, quella sì, va
 * negli storici, dove ogni riga porta la propria colonna {@code catena_id}). Al riavvio della
 * Centrale il registro riparte vuoto: i nodi ancora bloccati lo ridichiarano appena ripubblicano
 * la propria reazione, esattamente come la cache si ricostruisce dai frame di telemetria.</p>
 */
@ApplicationScoped
public class RegistroCatene {

    private static final Logger LOG = Logger.getLogger(RegistroCatene.class);

    /**
     * catena -> nodi che le stanno reagendo. Mappa concorrente perché scritta dal thread MQTT
     * e letta anche dalle API REST.
     */
    private final Map<String, Set<String>> nodiPerCatena = new ConcurrentHashMap<>();

    /**
     * Registra che un nodo entra nella catena.
     *
     * @param catenaId Identificativo della catena.
     * @param nodo     Nodo che reagisce (nella forma TIPO:id).
     * @return {@code true} se è la prima reazione di quel nodo a quella catena (quindi va
     *         applicata), {@code false} se il nodo ci era già dentro (quindi il messaggio è un
     *         doppione o un anello di ritorno e va ignorato).
     */
    public boolean entra(String catenaId, String nodo) {
        if (catenaId == null || nodo == null) {
            return false;
        }
        Set<String> nodi = nodiPerCatena.computeIfAbsent(catenaId, k -> ConcurrentHashMap.newKeySet());
        boolean primaVolta = nodi.add(nodo);
        if (!primaVolta) {
            LOG.debugf("🔁 Il nodo %s ha già reagito alla catena %s: messaggio ignorato", nodo, catenaId);
        }
        return primaVolta;
    }

    /**
     * Registra che un nodo esce dalla catena (il guasto che lo teneva fermo si è chiuso).
     * L'uscita va sempre applicata, altrimenti il rientro resterebbe bloccato dalla stessa
     * regola che impedisce i doppioni.
     *
     * @param catenaId Identificativo della catena.
     * @param nodo     Nodo che esce.
     */
    public void esce(String catenaId, String nodo) {
        if (catenaId == null || nodo == null) {
            return;
        }
        Set<String> nodi = nodiPerCatena.get(catenaId);
        if (nodi == null) {
            return;
        }
        nodi.remove(nodo);
        if (nodi.isEmpty()) {
            nodiPerCatena.remove(catenaId);
        }
    }

    /**
     * Chiude la catena: da chiamare quando il guasto primario viene risolto. Serve anche come
     * rete di sicurezza per i nodi che non dichiarano l'uscita perché nel frattempo si sono
     * spenti, altrimenti resterebbero registrati per sempre.
     *
     * @param catenaId Identificativo della catena.
     * @return I nodi che le stavano reagendo (utile per il log e per la notifica).
     */
    public Set<String> chiudi(String catenaId) {
        if (catenaId == null) {
            return Set.of();
        }
        Set<String> nodi = nodiPerCatena.remove(catenaId);
        return nodi == null ? Set.of() : Set.copyOf(nodi);
    }

    /**
     * @param catenaId Identificativo della catena.
     * @return I nodi che stanno reagendo a quella catena.
     */
    public Set<String> nodiDi(String catenaId) {
        Set<String> nodi = catenaId == null ? null : nodiPerCatena.get(catenaId);
        return nodi == null ? Set.of() : Collections.unmodifiableSet(nodi);
    }

    /** @return Numero di catene attualmente aperte (diagnostica). */
    public int catenaAperte() {
        return nodiPerCatena.size();
    }
}
