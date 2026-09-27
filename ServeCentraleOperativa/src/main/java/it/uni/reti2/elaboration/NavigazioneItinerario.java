package it.uni.reti2.elaboration;

import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Sa leggere un itinerario: dove si trova un convoglio dopo un passaggio e quale
 * fermata ha davanti.
 *
 * <p>E' geometria della rete, non ingestione di messaggi: le stesse domande se le
 * pone il digital twin del treno quando chiede {@code GET /api/prossima-stazione},
 * e prima la risposta era scritta due volte, una nel consumer MQTT e una
 * nell'endpoint REST, con due convenzioni diverse per la direzione ("ritorno" da
 * una parte, "R" dall'altra).</p>
 *
 * <p><b>La direzione ribalta tutto.</b> Un itinerario e' una sequenza ordinata di
 * tratte orientate: percorrerlo al ritorno significa leggerlo all'indietro e
 * scambiare i ruoli di stazione di partenza e di arrivo. E' l'unica sottigliezza di
 * questa classe, ed e' il motivo per cui vale la pena averla.</p>
 *
 * <p>Tutti i metodi leggono dal database, quindi <b>vanno chiamati dentro una
 * transazione gia' aperta</b>: senza non c'e' nessuna sessione JPA attiva.</p>
 */
@ApplicationScoped
public class NavigazioneItinerario {

    @Inject
    RailwayRepository repository;

    /** Dati che servono alla cache in RAM dopo un passaggio in stazione. */
    public record Posizione(String prossimaStazione, Tratta trattaCorrente) {
        public static Posizione sconosciuta() {
            return new Posizione(null, null);
        }
    }

    /**
     * true se il convoglio sta percorrendo l'itinerario nel verso originario.
     *
     * <p>Statica e pubblica perche' la stessa convenzione serve anche a
     * {@link PosizioneConvoglio}, che legge una tratta nel verso di marcia senza toccare
     * il database: riscriverla la' vorrebbe dire tenere due elenchi di sinonimi
     * ("ritorno", "R") che possono andare fuori sincrono.</p>
     */
    public static boolean andata(String direzione) {
        return !"ritorno".equalsIgnoreCase(direzione) && !"R".equalsIgnoreCase(direzione);
    }

    /**
     * Calcola dove si trova il convoglio dopo il passaggio appena dichiarato e quale
     * stazione ha davanti.
     *
     * @param entrata true per un ingresso in stazione, false per un'uscita.
     */
    public Posizione dopoIlPassaggio(String itinerarioId, String stazioneId,
                                     boolean entrata, String direzione) {
        if (itinerarioId == null) {
            return Posizione.sconosciuta();
        }
        Tratta tratta = trattaDelPassaggio(itinerarioId, stazioneId, entrata, direzione);
        // La prossima fermata ha senso solo per chi e' appena arrivato da qualche parte:
        // chi esce dalla stazione la sta ancora percorrendo, la tratta verso di essa.
        String prossima = entrata ? prossimaStazione(itinerarioId, stazioneId, direzione) : null;
        return new Posizione(prossima, tratta);
    }

    /**
     * La stazione successiva dell'itinerario rispetto a quella appena raggiunta.
     *
     * @return null al capolinea o se la stazione non appartiene all'itinerario.
     */
    public String prossimaStazione(String itinerarioId, String stazioneId, String direzione) {
        List<String> stazioni = repository.stazioniOrdinateDi(itinerarioId);
        int posizione = stazioni.indexOf(stazioneId);
        if (posizione < 0) {
            return null;
        }
        int successiva = andata(direzione) ? posizione + 1 : posizione - 1;
        if (successiva < 0 || successiva >= stazioni.size()) {
            return null; // capolinea
        }
        return stazioni.get(successiva);
    }

    /**
     * La tratta che il convoglio sta percorrendo <b>adesso</b>, dedotta dalla fermata che
     * dichiara di avere davanti: e' quella dell'itinerario che porta a
     * {@code prossimaStazione}, cioe' quella che la ha come stazione di arrivo in andata e
     * come stazione di partenza al ritorno.
     *
     * <p>Serve perche' {@link #dopoIlPassaggio} sa rispondere solo quando un sensore di
     * terra segnala un passaggio: se una stazione del percorso e' spenta quel transito non
     * arriva mai e la posizione del treno resta indietro di un segmento. La telemetria
     * invece arriva ogni secondo e la prossima fermata la porta sempre con se'.</p>
     *
     * @return null se nessuna tratta dell'itinerario porta a quella stazione (capolinea
     *         raggiunto, oppure stazione che non appartiene al percorso).
     */
    public Tratta trattaVerso(String itinerarioId, String prossimaStazione, String direzione) {
        if (itinerarioId == null || prossimaStazione == null) {
            return null;
        }
        for (ItinerarioTratta riga : repository.tratteOrdinateDi(itinerarioId)) {
            if (prossimaStazione.equals(estremoVersoCuiSiViaggia(riga.tratta, direzione))) {
                return riga.tratta;
            }
        }
        return null;
    }

    /**
     * L'estremo della tratta che il convoglio incontra per ultimo percorrendola in questa
     * direzione: la stazione di arrivo in andata, quella di partenza al ritorno.
     *
     * <p>E' pubblico perche' con questo si controlla, senza leggere niente dal database,
     * se una tratta gia' nota e' ancora quella giusta per la fermata dichiarata.</p>
     */
    public String estremoVersoCuiSiViaggia(Tratta tratta, String direzione) {
        return andata(direzione) ? tratta.stazioneArrivo.id : tratta.stazionePartenza.id;
    }

    /**
     * La tratta dell'itinerario coerente con l'evento di passaggio: per un ingresso e'
     * quella appena percorsa (il cui arrivo e' questa stazione), per un'uscita e' quella
     * che il convoglio sta imboccando (la cui partenza e' questa stazione). In direzione
     * "ritorno" i due ruoli si invertono.
     */
    private Tratta trattaDelPassaggio(String itinerarioId, String stazioneId,
                                      boolean entrata, String direzione) {
        boolean andata = andata(direzione);
        for (ItinerarioTratta riga : repository.tratteOrdinateDi(itinerarioId)) {
            Tratta tratta = riga.tratta;
            String estremoDaConfrontare;
            if (entrata) {
                estremoDaConfrontare = andata ? tratta.stazioneArrivo.id : tratta.stazionePartenza.id;
            } else {
                estremoDaConfrontare = andata ? tratta.stazionePartenza.id : tratta.stazioneArrivo.id;
            }
            if (estremoDaConfrontare.equals(stazioneId)) {
                return tratta;
            }
        }
        return null;
    }
}
