package it.uni.reti2;

import java.util.Map;

/**
 * Quello che il treno sa delle stazioni (T11): quali hanno un guasto aperto
 * (TrainDB.stazioniGuaste) e in che stato le ha messe la Centrale (TrainDB.statoStazioni).
 *
 * <p>Qui ci sono solo le mosse, cioè le scritture: la domanda "questa stazione si può
 * attraversare?" la fa TrainDB.stazioneImpercorribile, e se la pongono le guardie di
 * AutomaViaggio. A chiamare le mosse è GestoreEventi, nella fase degli assegnamenti, ed è
 * l'unica strada da cui passano: gli alert e le risposte della Centrale non scrivono più
 * direttamente nel TrainDB.</p>
 */
public class AutomaStazioniNote {

    /** Stato del treno: il set delle stazioni guaste e la mappa degli stati dichiarati. */
    TrainDB trainDB;

    /** Alert GUASTO CRITICAL: da adesso quella stazione non si attraversa. */
    public void annotaGuasto(String stazioneId) {
        trainDB.stazioniGuaste.add(stazioneId);
    }

    /**
     * Il guasto non c'è più: l'allarme è stato chiuso, oppure la Centrale ha dichiarato la
     * stazione di nuovo agibile (e lei è l'unica a saperlo davvero).
     */
    public void dimenticaGuasto(String stazioneId) {
        trainDB.stazioniGuaste.remove(stazioneId);
    }

    /** Alert STATO_STAZIONE: lo stato in cui la Centrale ha messo quella stazione. */
    public void annotaStato(String stazioneId, String stato) {
        trainDB.statoStazioni.put(stazioneId, stato);
    }

    /**
     * Gli stati che arrivano insieme all'itinerario: la fotografia della rete con cui
     * comincia un convoglio che non era acceso quando sono passati gli alert.
     */
    public void annotaStati(Map<String, String> stati) {
        trainDB.statoStazioni.putAll(stati);
    }
}
