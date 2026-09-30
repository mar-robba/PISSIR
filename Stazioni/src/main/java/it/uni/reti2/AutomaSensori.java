package it.uni.reti2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * L'automa dei sensori di binario (S7): tiene l'ultimo battito di ogni sensore
 * (DBLocale.sensoriUltimoBattito) e quali sensori sono considerati fermi
 * (DBLocale.sensoriGuasti).
 *
 * <p>È il lato stazione del meccanismo di <b>heartbeat + fail-stop</b> chiesto dall'enunciato
 * ("la stazione che non riceve più i keepalive dai propri sensori segnala lo stato di guasta
 * alla Centrale Operativa"): ogni sensore deve farsi vivo con una POST su
 * {@code /stazione/sensore/heartbeat} entro {@link #timeoutSecondi}, se no è fermo. Il
 * sensore fermo non viene dimenticato: resta sotto osservazione, ed è dal suo battito
 * successivo che la stazione capisce che il guasto è rientrato.</p>
 *
 * <p>Parla solo di battiti ricevuti e non conosce SimulatoreSensori: funziona identico se al
 * posto del simulatore ci sono le spire vere. Offre guardie e mosse, e le chiama
 * GestoreEventi.</p>
 */
public class AutomaSensori {

    /** Stato del nodo: le due strutture dei sensori. */
    DBLocale dbLocale;

    /** Secondi di silenzio oltre i quali un sensore viene considerato guasto (sensori.timeout.secondi). */
    long timeoutSecondi;

    // ===============================================================
    // LETTURE E GUARDIE
    // ===============================================================

    /** @return I secondi di silenzio concessi a un sensore. */
    public long timeoutSecondi() {
        return timeoutSecondi;
    }

    /** @return true se il sensore è considerato fermo. */
    public boolean muto(String sensoreId) {
        return dbLocale.sensoriGuasti.contains(sensoreId);
    }

    /** @return true se il sensore è l'unico ancora fermo: col suo battito non ne resta nessuno. */
    public boolean ultimoMuto(String sensoreId) {
        return muto(sensoreId) && dbLocale.sensoriGuasti.size() == 1;
    }

    /**
     * Guardia del giro di controllo: i sensori che non battono da oltre il timeout e che non
     * sono ancora stati segnalati. Quelli già segnalati restano nella mappa (serve per
     * accorgersi del ritorno) ma qui non tornano: l'allarme alla Centrale si manda una volta
     * sola.
     *
     * @param adesso L'istante del controllo.
     * @return I sensori appena scaduti, vuota se non ce ne sono.
     */
    public List<String> nuoviMuti(Instant adesso) {
        Instant limite = adesso.minusSeconds(timeoutSecondi);
        List<String> scaduti = new ArrayList<>();
        for (Map.Entry<String, Instant> sensore : dbLocale.sensoriUltimoBattito.entrySet()) {
            if (sensore.getValue().isBefore(limite) && !muto(sensore.getKey())) {
                scaduti.add(sensore.getKey());
            }
        }
        return scaduti;
    }

    // ===============================================================
    // MOSSE
    // ===============================================================

    /**
     * Stato iniziale: mette sotto osservazione i sensori che la stazione si aspetta di
     * sentire, con l'orologio che parte da adesso, così ogni sensore ha il timeout intero per
     * farsi vivo dopo l'avvio. L'elenco è noto in partenza e non si costruisce sui battiti che
     * arrivano: un sensore già morto quando il nodo si accende non manderebbe nulla, e non ci
     * sarebbe niente da mettere in timeout. Lo chiama il main all'avvio.
     */
    public void sorveglia(Collection<String> sensoreIds, Instant adesso) {
        for (String sensoreId : sensoreIds) {
            dbLocale.sensoriUltimoBattito.put(sensoreId, adesso);
        }
    }

    /** Il sensore ha battuto: si aggiorna il suo orologio e, se era fermo, torna vivo. */
    public void registraBattito(String sensoreId, Instant adesso) {
        dbLocale.sensoriUltimoBattito.put(sensoreId, adesso);
        dbLocale.sensoriGuasti.remove(sensoreId);
    }

    /** I sensori appena scaduti diventano fermi. */
    public void segnaMuti(List<String> sensoreIds) {
        dbLocale.sensoriGuasti.addAll(sensoreIds);
    }

    /**
     * Lavagna pulita: la Centrale ha rimesso in servizio la stazione e i sensori si
     * ricominciano a sorvegliare da capo. Se uno è ancora muto davvero, al primo giro di
     * controllo il timeout scatta di nuovo e la stazione si ridichiara GUASTA: meglio un
     * allarme ripetuto che una stazione data per buona mentre nessuno guarda i binari.
     */
    public void azzeraMuti() {
        dbLocale.sensoriGuasti.clear();
    }
}
