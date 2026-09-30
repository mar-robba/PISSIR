package it.uni.reti2;

import java.util.List;

/**
 * I conti sull'itinerario del treno: quale tappa ha davanti, in che punto dell'elenco si
 * trova una stazione, quanto dura una tratta e dove sta il convoglio lungo il binario.
 *
 * <p>Classe di sole funzioni pure, come PosizioneConvoglio nella Centrale: leggono
 * itinerario, indice e direzione ma non scrivono niente. Le decisioni (partire, fermarsi,
 * invertire la marcia) restano a GestoreEventi e AutomaViaggio.</p>
 *
 * <p>Alcune funzioni prendono l'elenco delle tappe invece del TrainDB: servono nella fase
 * delle guardie, quando si deve calcolare dove finirà il treno su un itinerario che nel
 * TrainDB non è ancora stato scritto.</p>
 *
 * <p>La direzione vale sempre "andata" o "ritorno": in andata l'itinerario si percorre a
 * indici crescenti, al ritorno a indici decrescenti.</p>
 */
public final class Percorso {

    private Percorso() {
    }

    /**
     * @param treno Stato del treno.
     * @return La tappa in cui il treno si trova (o da cui è partito).
     */
    public static TrainDB.TappaItinerario tappaCorrente(TrainDB treno) {
        return treno.itinerario.get(treno.indiceStazione);
    }

    /**
     * @param direzione "andata" o "ritorno".
     * @param indice    Indice della tappa di partenza.
     * @return L'indice della tappa successiva nel verso di marcia. Può cadere fuori
     *         dall'itinerario, e vuol dire che la tappa di partenza è un estremo.
     */
    public static int indiceProssimo(String direzione, int indice) {
        return "andata".equals(direzione) ? indice + 1 : indice - 1;
    }

    /**
     * @param treno Stato del treno.
     * @return L'indice della tappa successiva nel verso di marcia. Può cadere fuori
     *         dall'itinerario, e vuol dire che il treno è a un estremo.
     */
    public static int indiceProssimo(TrainDB treno) {
        return indiceProssimo(treno.direzione, treno.indiceStazione);
    }

    /**
     * @param tappe  Tappe dell'itinerario.
     * @param indice Indice cercato.
     * @return La tappa con quell'indice, oppure null se l'indice cade fuori dall'elenco.
     */
    public static TrainDB.TappaItinerario tappa(List<TrainDB.TappaItinerario> tappe, int indice) {
        if (indice < 0 || indice >= tappe.size()) {
            return null;
        }
        return tappe.get(indice);
    }

    /**
     * @param treno Stato del treno.
     * @return La prossima tappa nella direzione corrente, oppure null se il treno è
     *         all'estremo dell'itinerario in quel verso (e quindi deve invertire la marcia).
     */
    public static TrainDB.TappaItinerario tappaProssima(TrainDB treno) {
        return tappa(treno.itinerario, indiceProssimo(treno));
    }

    /**
     * @param direzione "andata" o "ritorno".
     * @return La direzione opposta.
     */
    public static String direzioneOpposta(String direzione) {
        return "andata".equals(direzione) ? "ritorno" : "andata";
    }

    /**
     * Cerca una stazione in un elenco di tappe. Se compare più volte (percorso che passa
     * due volte dalla stessa stazione) vince l'occorrenza più vicina all'indice di prima,
     * che è quella che meglio rappresenta il punto in cui il treno si trova.
     *
     * @param tappe        Tappe in cui cercare (anche di un itinerario non ancora installato).
     * @param stazioneId   Stazione da cercare (null non si trova mai).
     * @param indiceVicino Indice in cui il treno si trovava sul percorso di prima.
     * @return L'indice della tappa, oppure -1 se la stazione non c'è.
     */
    public static int indiceDi(List<TrainDB.TappaItinerario> tappe, String stazioneId, int indiceVicino) {
        int migliore = -1;
        for (int i = 0; i < tappe.size(); i++) {
            if (!tappe.get(i).id.equals(stazioneId)) {
                continue;
            }
            if (migliore < 0 || Math.abs(i - indiceVicino) < Math.abs(migliore - indiceVicino)) {
                migliore = i;
            }
        }
        return migliore;
    }

    /**
     * Tempo di percorrenza (in minuti simulati) del segmento tra due tappe adiacenti.
     * In andata il tempo è sulla tappa di partenza, al ritorno il segmento è lo
     * stesso dell'andata quindi il tempo sta sulla tappa di arrivo.
     *
     * @param treno Stato del treno (serve la direzione).
     * @param da    Tappa di partenza.
     * @param a     Tappa di arrivo.
     * @return I minuti simulati della tratta, mai meno di 1.
     */
    //?? capire come
    public static int tempoTrattaMinuti(TrainDB treno, TrainDB.TappaItinerario da, TrainDB.TappaItinerario a) {
        int minuti = "andata".equals(treno.direzione) ? da.tempoVersoProssimaMinuti : a.tempoVersoProssimaMinuti;
        return Math.max(1, minuti); // mai 0 per evitare divisioni per zero
    }

    /**
     * Interpolazione lineare fra due coordinate: con progress 0 si è sulla prima, con 100
     * sulla seconda.
     *
     * @param da              Coordinata della stazione di partenza.
     * @param a               Coordinata della stazione di arrivo.
     * @param progressPercent Avanzamento della tratta, da 0 a 100.
     * @return La coordinata del treno.
     */
    public static double interpola(double da, double a, double progressPercent) {
        return da + (a - da) * progressPercent / 100.0;
    }
}
