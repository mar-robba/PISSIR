package it.uni.reti2;

/**
 * Un evento per gli automi della stazione: che cosa è successo e i dati che servono per
 * reagire.
 *
 * <p>Lo costruiscono i generatori di eventi (GeneratoreEventiTempo, StationIngestion,
 * StationGateway), che traducono in questa forma il giro di controllo dei sensori, le
 * chiamate REST dei sensori di binario e gli alert della Centrale; lo consuma GestoreEventi,
 * che è l'unico a far muovere gli automi. I campi che a un tipo di evento non servono restano
 * null. È lo stesso schema di EventoTreno nel nodo Treno.</p>
 *
 * @param tipo       Che cosa è successo.
 * @param soggettoId Di chi parla l'evento: il sensore (BATTITO_SENSORE) o il treno (TRANSITO).
 * @param dettaglio  Il tipo di transito, ENTRATA o USCITA (TRANSITO), oppure la descrizione
 *                   del guasto (GUASTO_RILEVATO, GUASTO_DALLA_CENTRALE).
 * @param tipoGuasto La classificazione dichiarata da chi segnala il guasto (GUASTO_RILEVATO),
 *                   null se non l'ha dichiarata.
 */
public record EventoStazione(Tipo tipo, String soggettoId, String dettaglio, String tipoGuasto) {

    /** L'alfabeto degli automi della stazione: tutto quello che li può far muovere. */
    public enum Tipo {
        /** Un sensore di binario ha mandato il suo keepalive (POST /stazione/sensore/heartbeat). */
        BATTITO_SENSORE,
        /** È passato un giro del controllo dei keepalive: si guarda chi ha smesso di battere. */
        CONTROLLO_SENSORI,
        /** Un sensore di binario ha visto passare un convoglio (POST /stazione/sensore/treno). */
        TRANSITO,
        /** Un sottosistema locale segnala un'avaria (POST /stazione/sensore/guasto). */
        GUASTO_RILEVATO,
        /** Alert GUASTO CRITICAL della Centrale su questa stazione (RF02.1.2.2.1). */
        GUASTO_DALLA_CENTRALE,
        /** Alert RESOLVED della Centrale su questa stazione: il guasto è chiuso. */
        RIPRISTINO_DALLA_CENTRALE,
        /** POST /stazione/rete/offline: il collegamento con la Centrale viene staccato. */
        RETE_GIU,
        /** POST /stazione/rete/online: il collegamento con la Centrale torna. */
        RETE_SU
    }

    /**
     * @param tipo Tipo di un evento che non porta dati (CONTROLLO_SENSORI,
     *             RIPRISTINO_DALLA_CENTRALE, RETE_GIU, RETE_SU).
     * @return L'evento.
     */
    public static EventoStazione di(Tipo tipo) {
        return new EventoStazione(tipo, null, null, null);
    }

    /**
     * @param sensoreId Il sensore che ha battuto.
     * @return L'evento BATTITO_SENSORE.
     */
    public static EventoStazione battito(String sensoreId) {
        return new EventoStazione(Tipo.BATTITO_SENSORE, sensoreId, null, null);
    }

    /**
     * @param trenoId Il convoglio visto dal sensore.
     * @param tipo    "ENTRATA" oppure "USCITA".
     * @return L'evento TRANSITO.
     */
    public static EventoStazione transito(String trenoId, String tipo) {
        return new EventoStazione(Tipo.TRANSITO, trenoId, tipo, null);
    }

    /**
     * @param descrizione Che cosa si è rotto.
     * @param tipoGuasto  La classificazione dichiarata, oppure null.
     * @return L'evento GUASTO_RILEVATO.
     */
    public static EventoStazione guastoRilevato(String descrizione, String tipoGuasto) {
        return new EventoStazione(Tipo.GUASTO_RILEVATO, null, descrizione, tipoGuasto);
    }

    /**
     * @param messaggio Il testo dell'alert della Centrale.
     * @return L'evento GUASTO_DALLA_CENTRALE.
     */
    public static EventoStazione guastoDallaCentrale(String messaggio) {
        return new EventoStazione(Tipo.GUASTO_DALLA_CENTRALE, null, messaggio, null);
    }
}
