package it.uni.reti2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Un itinerario così come lo assegna la Centrale Operativa (GET /api/treni/{id}/itinerario):
 * identificativo, tappe in ordine di percorrenza, stazione che chiude il servizio e stato
 * delle fermate al momento della risposta.
 *
 * <p>Ha lo stesso nome dell'entità Itinerario della Centrale perché ne è la copia che arriva
 * al treno, ridotta a quello che serve per viaggiare: le stazioni sono già tradotte in tappe
 * con coordinate e tempi di percorrenza.</p>
 *
 * <p>Id, tappe e capolinea stanno in un solo oggetto perché valgono solo insieme: il capolinea
 * appartiene al suo itinerario, e scritto sulle tappe di un altro fermerebbe il treno in un
 * punto che sul percorso in uso non chiude niente. Per questo AutomaViaggio lo tiene in
 * attesa e lo scrive nel TrainDB sempre tutto intero.</p>
 *
 * <p>Lo stato delle fermate invece non fa parte del percorso: è una fotografia della rete,
 * che arriva nella stessa risposta perché gli alert su railway/alerts li sente solo chi era
 * acceso quando sono passati. GestoreEventi lo annota subito, anche quando il percorso resta
 * in attesa.</p>
 *
 * @param id           Identificativo dell'itinerario (es. "IT1_MI_NA").
 * @param tappe        Tappe in ordine di percorrenza.
 * @param capolineaId  Stazione che chiude il servizio, null se il percorso non finisce
 *                     (il treno lo percorre avanti e indietro all'infinito).
 * @param statoFermate Stato di ogni fermata dichiarato dalla Centrale (ONLINE, GUASTA,
 *                     MANUTENZIONE, OFFLINE), per id di stazione.
 */
public record Itinerario(String id, List<TrainDB.TappaItinerario> tappe, String capolineaId,
                         Map<String, String> statoFermate) {

    /**
     * @return Gli id delle tappe, in ordine, nel formato di TrainDB.stazioniTratta.
     */
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (TrainDB.TappaItinerario tappa : tappe) {
            ids.add(tappa.id);
        }
        return ids;
    }
}
