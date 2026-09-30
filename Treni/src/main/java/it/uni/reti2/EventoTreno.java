package it.uni.reti2;

/**
 * Un evento per gli automi del treno: che cosa è successo e i dati che servono per reagire.
 *
 * <p>Lo costruiscono i generatori di eventi (GeneratoreEventiTempo, TrainGateway,
 * TrainIngestion), che traducono in questa forma il tick, le risposte della Centrale, gli
 * alert MQTT e i sensori di bordo; lo consuma GestoreEventi, che è l'unico a far muovere
 * gli automi. I campi che a un tipo di evento non servono restano null.</p>
 *
 * @param tipo       Che cosa è successo.
 * @param stazioneId La stazione di cui parla l'evento (eventi sulle stazioni). // per ese se la stazione è quella su cui si è posizionati (guardia restituisce true) e si cambia lo stato
 * @param dettaglio  Lo stato dichiarato (STATO_STAZIONE) o la descrizione dell'avaria // riporta lo stato della stazione quidi dell'automa sull'altra macchina: utile per le guardie così verificano sull'altro automa
 *                   (GUASTO_BORDO).
 * @param itinerario Il percorso arrivato dalla Centrale (ITINERARIO_CARICATO). // un dato utile per la fase di azione e guardia
 */
public record EventoTreno(Tipo tipo, String stazioneId, String dettaglio, Itinerario itinerario) {

    /** L'alfabeto degli automi del treno: tutto quello che li può far muovere. */
    public enum Tipo {
        /** È passato un secondo: il treno avanza, e si vede se è scaduta la sosta o finita la tratta. */
        TICK,
        /** La Centrale ha risposto alla GET dell'itinerario con un percorso valido. */
        ITINERARIO_CARICATO,
        /** La Centrale ha risposto 404: il convoglio non è (più) assegnato a nessun itinerario. */
        ITINERARIO_REVOCATO,
        /** Alert GUASTO CRITICAL di una stazione. */
        GUASTO_STAZIONE,
        /** Alert RESOLVED di una stazione: quell'allarme è chiuso, i binari non per forza. */
        GUASTO_STAZIONE_RISOLTO,
        /** Alert STATO_STAZIONE: lo stato in cui la Centrale ha messo una stazione. */
        STATO_STAZIONE,
        /** Un sensore di bordo ha rilevato un'avaria (POST /treno/sensore/guasto). */
        GUASTO_BORDO,
        /** Alert RESOLVED con sorgente questo treno: l'avaria di bordo è risolta. */
        GUASTO_BORDO_RISOLTO,
        /** Alert STOP con target questo treno: la corsa è soppressa. */
        SOPPRESSIONE
    }

    /**
     * @param tipo Tipo di un evento che non porta dati (TICK, ITINERARIO_REVOCATO,
     *             GUASTO_BORDO_RISOLTO, SOPPRESSIONE).
     * @return L'evento.
     */
    public static EventoTreno di(Tipo tipo) {
        return new EventoTreno(tipo, null, null, null);
    }

    /**
     * @param tipo       Tipo di un evento su una stazione.
     * @param stazioneId La stazione.
     * @param dettaglio  Lo stato dichiarato per STATO_STAZIONE, null per gli altri.
     * @return L'evento.
     */
    public static EventoTreno suStazione(Tipo tipo, String stazioneId, String dettaglio) {
        return new EventoTreno(tipo, stazioneId, dettaglio, null);
    }

    /**
     * @param itinerario Il percorso arrivato dalla Centrale.
     * @return L'evento ITINERARIO_CARICATO.
     */
    public static EventoTreno itinerarioCaricato(Itinerario itinerario) {
        return new EventoTreno(Tipo.ITINERARIO_CARICATO, null, null, itinerario);
    }

    /**
     * @param descrizione Che cosa ha visto il sensore di bordo.
     * @return L'evento GUASTO_BORDO.
     */
    public static EventoTreno guastoDiBordo(String descrizione) {
        return new EventoTreno(Tipo.GUASTO_BORDO, null, descrizione, null);
    }
}
