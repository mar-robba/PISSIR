package it.uni.reti2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Il gestore degli eventi della stazione: l'unico punto da cui passano le mosse degli
 * automi, e quindi l'unico che cambia lo stato del nodo. È lo stesso schema del nodo Treno.
 *
 * <p>Gli eventi arrivano da tre generatori, che traducono in un EventoStazione quello che è
 * successo e non scrivono niente nel DBLocale: GeneratoreEventiTempo (il giro di controllo dei
 * keepalive), StationIngestion (i sensori di binario e le leve della demo, via REST) e
 * StationGateway (gli alert della Centrale su railway/alerts).</p>
 *
 * <p>Ogni evento si gestisce in tre fasi, sempre in quest'ordine:</p>
 * <ol>
 *   <li><b>guardie</b>: si legge lo stato com'è adesso e si decide tutto, comprese le mosse
 *       degli altri automi, in variabili locali e senza scrivere niente. Se una guardia dice
 *       di no si esce qui, quando nessun automa si è ancora mosso;</li>
 *   <li><b>assegnamenti</b>: si scrivono le mosse decise, senza più nessun controllo (gli if
 *       che ci sono riusano risposte già date nella fase 1, non le richiedono);</li>
 *   <li><b>azioni</b>: si accodano i messaggi per la Centrale e si scrive nel log, quando lo
 *       stato è già tutto scritto.</li>
 * </ol>
 *
 * <p>Gli automi (AutomaStatoStazione, AutomaSensori, AutomaTreniPresenti, AutomaConnessione)
 * non si chiamano mai fra loro: quando un evento muove più cose le elenca questo gestore. Il
 * RESOLVED della Centrale, per esempio, rimette in servizio la stazione e azzera i sensori
 * muti: sono due mosse di due automi, e stanno una sotto l'altra in {@link #suRipristino}.</p>
 *
 * <p>{@link #gestisci} è synchronized: i keepalive e i transiti arrivano sui thread di Javalin,
 * gli alert su quello di Paho, il giro di controllo su quello dell'orologio, e le risposte
 * della fase 1 valgono solo se nessun altro scrive fra la fase 1 e la fase 2. Dentro non c'è
 * niente di lento: i messaggi per la Centrale finiscono nel LocalBuffer e li pubblica il
 * ConsumatoreBuffer, senza che il gestore aspetti il broker.</p>
 */
public class GestoreEventi {

    private static final Logger LOG = Logger.getLogger(GestoreEventi.class.getName());

    /** Stato del nodo: qui si legge soltanto, a scriverlo sono gli automi. */
    DBLocale dbLocale;

    /** ONLINE o GUASTA, con la causa e il registro dei guasti (S10). */
    AutomaStatoStazione statoStazione;

    /** I keepalive dei sensori di binario (S7). */
    AutomaSensori sensori;

    /** I convogli sui binari (S11). */
    AutomaTreniPresenti treni;

    /** Il collegamento con la Centrale (S4). */
    AutomaConnessione connessione;

    /** Azione: accoda i GUASTO e i RESOLVED per railway/alerts. */
    PubblicatoreAlert pubblicatoreAlert;

    /** Azione: accoda i transiti per railway/station/{id}/transit. */
    PubblicatoreTransiti pubblicatoreTransiti;

    /**
     * Il gestore degli eventi: ogni tipo di evento ha il suo metodo, e ognuno è scritto nelle
     * tre fasi descritte sopra.
     *
     * @param evento Quello che è successo, già tradotto da uno dei generatori.
     */
    public synchronized void gestisci(EventoStazione evento) {
        switch (evento.tipo()) {
            case BATTITO_SENSORE -> suBattito(evento.soggettoId());
            case CONTROLLO_SENSORI -> suControlloSensori();
            case TRANSITO -> suTransito(evento.soggettoId(), evento.dettaglio());
            case GUASTO_RILEVATO -> suGuastoRilevato(evento.dettaglio(), evento.tipoGuasto());
            case GUASTO_DALLA_CENTRALE -> suGuastoDallaCentrale(evento.dettaglio());
            case RIPRISTINO_DALLA_CENTRALE -> suRipristino();
            case RETE_GIU -> suReteGiu();
            case RETE_SU -> suReteSu();
        }
    }

    // ===============================================================
    // SENSORI DI BINARIO: keepalive e fail-stop
    // ===============================================================

    /**
     * Un sensore ha battuto. Se era fermo ed era l'ultimo, i sensori sono di nuovo tutti vivi:
     * i loro guasti si chiudono e la Centrale riceve il RESOLVED. La stazione però torna ONLINE
     * da sola solo se era guasta soltanto per i sensori; se nel frattempo si è aggiunto un altro
     * guasto resta GUASTA, e a rimetterla in servizio sarà la Centrale.
     *
     * <p>Il ripristino si fa qui e non al giro di controllo apposta: la "rinnovata ricezione"
     * del battito è l'evento che rimette in servizio la stazione, e farla aspettare fino al
     * prossimo giro vorrebbe dire tenerla guasta qualche secondo in più senza motivo.</p>
     */
    private void suBattito(String sensoreId) {
        Instant adesso = Instant.now();

        // 1. guardie
        boolean eraMuto = sensori.muto(sensoreId);
        boolean sensoriTornati = eraMuto && sensori.ultimoMuto(sensoreId);
        boolean rientra = sensoriTornati && statoStazione.puoRientrareDaSola();

        // 2. assegnamenti
        sensori.registraBattito(sensoreId, adesso);
        if (rientra) {
            statoStazione.tornaOnline(adesso); // chiude anche i guasti dei sensori
        } else if (sensoriTornati) {
            statoStazione.chiudiGuastiDeiSensori(adesso);
        }

        // 3. azioni
        if (eraMuto) {
            LOG.info(String.format("💚 Sensore %s tornato a battere.", sensoreId));
        }
        if (rientra) {
            LOG.info("✅ Tutti i sensori battono di nuovo: stazione di nuovo operativa.");
        } else if (sensoriTornati) {
            LOG.warning("⚠️ Tutti i sensori battono di nuovo, ma la stazione resta GUASTA: "
                    + "c'e' un altro guasto aperto, la rimette in servizio la Centrale.");
        }
        if (sensoriTornati) {
            // La Centrale deve saperlo: l'allarme dei sensori è rientrato e va chiuso.
            pubblicatoreAlert.inviaRipristino(AutomaStatoStazione.TIPO_GUASTO_SENSORE,
                    "Keepalive dei sensori di binario ripristinato");
        }
    }

    /**
     * Giro di controllo dei keepalive: chi non batte da troppo tempo è fermo. Per ogni sensore
     * appena scaduto il guasto si annota nel registro e parte un allarme CRITICAL di tipo
     * sensore_offline, una volta sola. La stazione passa GUASTA, e il guasto risulta dei soli
     * sensori solo se prima era ONLINE: se era già guasta per altro, resta guasta per altro.
     *
     * <p>La severità è CRITICAL perché una stazione senza sensori non sa più chi entra e chi
     * esce dai binari: i treni in arrivo vanno trattenuti finché non arriva la manutenzione. Il
     * tipo è dichiarato, non lasciato indovinare alla Centrale.</p>
     */
    private void suControlloSensori() {
        Instant adesso = Instant.now();

        // 1. guardie
        List<String> nuoviMuti = sensori.nuoviMuti(adesso);
        if (nuoviMuti.isEmpty()) {
            return;
        }
        boolean siGuasta = statoStazione.puoGuastarsi();

        // 2. assegnamenti
        sensori.segnaMuti(nuoviMuti);
        if (siGuasta) {
            statoStazione.guasta(true, adesso);
        }
        List<Long> idGuasti = new ArrayList<>();
        for (String sensoreId : nuoviMuti) {
            idGuasti.add(statoStazione.annotaGuasto(AutomaStatoStazione.TIPO_GUASTO_SENSORE,
                    descrizioneSensoreMuto(sensoreId), adesso));
        }

        // 3. azioni
        for (int i = 0; i < nuoviMuti.size(); i++) {
            String sensoreId = nuoviMuti.get(i);
            LOG.severe(String.format("⚠️ Sensore %s silente da oltre %d secondi: stazione GUASTA!",
                    sensoreId, sensori.timeoutSecondi()));
            pubblicatoreAlert.inviaGuasto(descrizioneSensoreMuto(sensoreId), "CRITICAL",
                    AutomaStatoStazione.TIPO_GUASTO_SENSORE, idGuasti.get(i));
        }
    }

    private static String descrizioneSensoreMuto(String sensoreId) {
        return "Sensore " + sensoreId + " non invia keepalive";
    }

    // ===============================================================
    // TRANSITI E GUASTI DICHIARATI DAL CAMPO
    // ===============================================================

    /**
     * Un sensore ha visto passare un convoglio. È il punto unico in cui la stazione prende atto
     * che un treno è entrato o uscito: la fotografia dei binari si aggiorna, alla Centrale parte
     * il transito ufficiale e, se la stazione è guasta, il treno appena entrato viene trattenuto
     * con un allarme dedicato.
     *
     * <p>L'allarme del treno trattenuto non apre un guasto nel registro locale: la stazione era
     * già guasta, e questo è solo il modo con cui chiede alla Centrale di fermare il convoglio.
     * Se aprisse una riga, al riavvio sembrerebbe un secondo guasto, e una stazione guasta per i
     * soli sensori non potrebbe più rientrare da sola quando tornano a battere.</p>
     */
    private void suTransito(String trenoId, String tipo) {
        Instant adesso = Instant.now();

        // 1. guardie
        boolean entrata = "ENTRATA".equals(tipo);
        boolean trattenuto = entrata && statoStazione.guasta();

        // 2. assegnamenti
        if (entrata) {
            treni.entra(trenoId, adesso);
        } else {
            treni.esce(trenoId, adesso);
        }

        // 3. azioni: il transito verso la Centrale (fonte per la storicizzazione) e l'allarme
        //    che trattiene il treno appena arrivato in una stazione guasta
        pubblicatoreTransiti.inviaTransito(trenoId, tipo);
        if (trattenuto) {
            LOG.warning(String.format("⛔ Stazione guasta: il treno %s resta trattenuto sui binari", trenoId));
            pubblicatoreAlert.inviaGuasto(String.format("Stazione %s guasta: treno %s trattenuto",
                    dbLocale.stazioneId, trenoId), "CRITICAL", null, null);
        }
    }

    /**
     * Un sottosistema locale (scambi, alimentazione, un binario) segnala un'avaria: la stazione
     * passa GUASTA, il guasto si annota nel registro locale e parte l'allarme per la Centrale.
     * Se la stazione era già guasta per i sensori, da adesso il loro ritorno non basta più.
     */
    private void suGuastoRilevato(String descrizione, String tipoGuasto) {
        Instant adesso = Instant.now();

        // 1. guardie
        boolean siGuasta = statoStazione.puoGuastarsi();

        // 2. assegnamenti
        if (siGuasta) {
            statoStazione.guasta(false, adesso);
        } else {
            statoStazione.aggiungiAltraCausa();
        }
        long idGuasto = statoStazione.annotaGuasto(
                tipoGuasto != null ? tipoGuasto : AutomaStatoStazione.TIPO_GUASTO_STAZIONE, descrizione, adesso);

        // 3. azioni
        LOG.warning(String.format("🚨 Guasto segnalato alla stazione: %s", descrizione));
        pubblicatoreAlert.inviaGuasto(descrizione, "CRITICAL", tipoGuasto, idGuasto);
    }

    // ===============================================================
    // ALERT DELLA CENTRALE
    // ===============================================================

    /**
     * RF02.1.2.2.1: la Centrale ha dedotto che questa stazione non è percorribile (per esempio
     * un convoglio deragliato sui binari). Si allinea lo stato locale e il guasto si annota nel
     * registro, così un riavvio non lo scambia per un guasto dei soli sensori. Alla Centrale non
     * parte niente: lo sa già, è lei che l'ha dichiarato.
     */
    private void suGuastoDallaCentrale(String messaggio) {
        Instant adesso = Instant.now();

        // 1. guardie
        boolean siGuasta = statoStazione.puoGuastarsi();

        // 2. assegnamenti
        if (siGuasta) {
            statoStazione.guasta(false, adesso);
        } else {
            statoStazione.aggiungiAltraCausa();
        }
        statoStazione.annotaGuasto(AutomaStatoStazione.TIPO_GUASTO_STAZIONE, messaggio, adesso);

        // 3. azioni
        LOG.warning(String.format("🚨 Stazione marcata GUASTA dalla Centrale: %s", messaggio));
    }

    /**
     * Il RESOLVED della Centrale per questa stazione: si torna ONLINE, qualunque fosse la causa,
     * e si ricomincia a sorvegliare i sensori con la lavagna pulita. Il guasto lo dichiara la
     * stazione, ma a chiuderlo è l'operatore: un nodo che si auto-riparasse farebbe ripartire i
     * treni senza che nessuno sia andato a controllare i binari. L'unica eccezione sono i
     * sensori muti, perché lì la prova che il guasto è rientrato è il battito stesso.
     */
    private void suRipristino() {
        Instant adesso = Instant.now();

        // 1. guardie
        if (!statoStazione.puoTornareOnline()) {
            LOG.fine("RESOLVED della Centrale per una stazione gia' ONLINE: niente da fare");
            return;
        }

        // 2. assegnamenti
        statoStazione.tornaOnline(adesso);
        sensori.azzeraMuti();

        // 3. azioni
        LOG.info("🔧 Stazione ripristinata dalla Centrale! Stato di nuovo ONLINE.");
    }

    // ===============================================================
    // COLLEGAMENTO CON LA CENTRALE: le leve della demo
    // ===============================================================

    /** POST /stazione/rete/offline: COLLEGATA -> ISOLATA (caduta simulata del collegamento). */
    private void suReteGiu() {
        // 1. guardie
        if (!connessione.collegata()) {
            return; // già isolata
        }

        // 2. assegnamenti
        connessione.isola();

        // 3. azioni
        LOG.warning("🔌 Collegamento con la Centrale staccato (simulazione): transiti e allarmi restano nel buffer locale.");
    }

    /** POST /stazione/rete/online: ISOLATA -> COLLEGATA; la coda riparte da sola, in ordine. */
    private void suReteSu() {
        // 1. guardie
        if (connessione.collegata()) {
            return; // già collegata
        }

        // 2. assegnamenti
        connessione.ricollega();

        // 3. azioni
        LOG.info("📡 Collegamento con la Centrale ripristinato: gli eventi nel buffer ripartono in ordine.");
    }
}
