package it.uni.reti2.elaboration;

import it.uni.reti2.entity.Treno;
import it.uni.reti2.entity.Tratta;

import java.time.Duration;
import java.time.Instant;

/**
 * Decide se la Centrale sa <b>davvero</b> dove si trova un convoglio, oppure se la
 * posizione che pubblicherebbe sarebbe solo l'ultima che ha visto.
 *
 * <h3>Perche' serve</h3>
 * <p>La Centrale ha due fonti diverse sulla posizione, e non valgono uguale:</p>
 * <ul>
 *   <li>la <b>telemetria</b> del digital twin, che arriva di continuo e dice in quale
 *       stazione il convoglio si trova (vuoto se e' in mezzo a una tratta), quale ha
 *       davanti e in che verso sta percorrendo l'itinerario;</li>
 *   <li>il <b>passaggio</b> rilevato dai sensori di terra, che e' l'unico a scrivere
 *       {@code posizioneAttualeTratta} e che passa dal nodo Stazione.</li>
 * </ul>
 *
 * <p>Se una stazione e' spenta i suoi passaggi non li rileva nessuno, quindi
 * {@code posizioneAttualeTratta} resta ferma sull'ultima tratta confermata mentre il
 * treno e' andato avanti. Prendere quella tratta per buona significa inventare: e'
 * cosi' che sulla mappa comparivano convogli in viaggio fra due stazioni che in rete
 * non sono nemmeno collegate (la stazione di partenza veniva da una tratta vecchia,
 * quella di arrivo dalla telemetria di adesso).</p>
 *
 * <p>La regola qui dentro e' che <b>l'estremo di partenza vale solo se e' coerente con
 * la telemetria</b>: letta nel verso di marcia, la tratta confermata dai sensori deve
 * finire proprio nella stazione verso cui il treno dichiara di stare andando. Quando i
 * conti non tornano la Centrale non sceglie: dichiara la posizione incerta e lascia che
 * l'interfaccia metta il convoglio nell'elenco di quelli da ritrovare, invece di
 * disegnarlo su un binario che non esiste.</p>
 *
 * <p>Classe di sole funzioni pure: legge i campi gia' presenti sul convoglio in cache e
 * non tocca il database. Puo' quindi girare a ogni scrittura e a ogni lettura della
 * cache senza aprire transazioni.</p>
 */
public final class PosizioneConvoglio {

    /** Il convoglio non trasmette (processo spento, appena riavviata la Centrale). */
    public static final String NESSUNA_TELEMETRIA = "NESSUNA_TELEMETRIA";

    /** Nessun sensore ha confermato da dove il convoglio e' uscito. */
    public static final String PARTENZA_SCONOSCIUTA = "PARTENZA_SCONOSCIUTA";

    /** Il convoglio e' in linea ma non dichiara verso quale fermata. */
    public static final String ARRIVO_SCONOSCIUTO = "ARRIVO_SCONOSCIUTO";

    /** Il convoglio e' in linea ma non si sa in che verso percorre l'itinerario. */
    public static final String DIREZIONE_SCONOSCIUTA = "DIREZIONE_SCONOSCIUTA";

    private PosizioneConvoglio() {
    }

    /**
     * Ricalcola i campi di posizione dichiarata del convoglio: dove si trova, dove sta
     * andando, in che verso e se di tutto questo la Centrale e' sicura.
     *
     * @param treno              Convoglio in cache; i campi vengono scritti su di lui.
     * @param freschezzaSecondi  Oltre quanti secondi la telemetria e' considerata vecchia.
     */
    public static void valuta(Treno treno, long freschezzaSecondi) {
        if (treno == null) {
            return;
        }

        // Senza telemetria recente l'unica cosa in cache e' la riga del database, che puo'
        // avere ore: non si sa ne' dove sia il convoglio ne' in che verso stia andando.
        if (!telemetriaFresca(treno, freschezzaSecondi)) {
            scrivi(treno, null, null, null, false, NESSUNA_TELEMETRIA);
            return;
        }

        String direzione = valorizzato(treno.direzione);
        String stazioneCorrente = valorizzato(treno.stazioneCorrente);
        String prossimaStazione = valorizzato(treno.prossimaStazione);

        // Fermo in stazione: la posizione e' il nodo e la dichiara il convoglio stesso,
        // quindi non c'e' niente da dedurre. La prossima fermata puo' mancare senza che
        // questo renda incerto dove si trova: al capolinea non c'e' un dopo.
        if (stazioneCorrente != null) {
            scrivi(treno, stazioneCorrente, prossimaStazione, direzione, true, null);
            return;
        }

        // Da qui in poi il convoglio e' fra due stazioni: per disegnarlo servono entrambi
        // gli estremi della tratta che sta percorrendo.
        if (prossimaStazione == null) {
            scrivi(treno, null, null, direzione, false, ARRIVO_SCONOSCIUTO);
            return;
        }
        if (direzione == null) {
            // Senza il verso di marcia la tratta confermata dai sensori non si sa nemmeno
            // da che parte leggerla, quindi non conferma proprio niente.
            scrivi(treno, null, prossimaStazione, null, false, DIREZIONE_SCONOSCIUTA);
            return;
        }

        String partenza = partenzaConfermata(treno.posizioneAttualeTratta, direzione, prossimaStazione);
        if (partenza == null) {
            scrivi(treno, null, prossimaStazione, direzione, false, PARTENZA_SCONOSCIUTA);
            return;
        }
        scrivi(treno, partenza, prossimaStazione, direzione, true, null);
    }

    /**
     * La stazione da cui il convoglio e' uscito, ma solo se i sensori di terra la
     * confermano.
     *
     * <p>La tratta arriva dall'ultimo passaggio rilevato ed e' orientata come sta a
     * database; nella corsa di ritorno la si percorre al contrario, quindi i due estremi
     * si scambiano i ruoli. Se letta cosi' non finisce nella stazione verso cui il treno
     * dichiara di andare, vuol dire che il convoglio ha gia' lasciato quella tratta e
     * nessuno se n'e' accorto: la partenza e' sconosciuta, non e' l'estremo di questa.</p>
     *
     * @return L'id della stazione di partenza, oppure null se non e' confermata.
     */
    private static String partenzaConfermata(Tratta tratta, String direzione, String arrivoDichiarato) {
        if (tratta == null || tratta.stazionePartenza == null || tratta.stazioneArrivo == null) {
            return null;
        }
        boolean andata = NavigazioneItinerario.andata(direzione);
        String da = andata ? tratta.stazionePartenza.id : tratta.stazioneArrivo.id;
        String a = andata ? tratta.stazioneArrivo.id : tratta.stazionePartenza.id;
        return arrivoDichiarato.equals(a) ? da : null;
    }

    /** True se il convoglio ha trasmesso da meno del tempo concesso. */
    private static boolean telemetriaFresca(Treno treno, long freschezzaSecondi) {
        if (treno.ultimoAggiornamento == null) {
            return false; // non ha mai trasmesso: processo spento
        }
        return Duration.between(treno.ultimoAggiornamento, Instant.now()).getSeconds() <= freschezzaSecondi;
    }

    /** Riporta a null le stringhe vuote, che nei payload dal campo valgono "non lo so". */
    private static String valorizzato(String valore) {
        return valore == null || valore.isBlank() ? null : valore;
    }

    private static void scrivi(Treno treno, String partenza, String arrivo, String direzione,
                               boolean nota, String incertezza) {
        treno.partenzaCorrente = partenza;
        treno.arrivoCorrente = arrivo;
        treno.direzioneCorrente = direzione;
        treno.posizioneNota = nota;
        treno.incertezzaPosizione = incertezza;
    }
}
