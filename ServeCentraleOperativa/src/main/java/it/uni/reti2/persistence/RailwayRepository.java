package it.uni.reti2.persistence;

import io.quarkus.panache.common.Sort;
import it.uni.reti2.entity.EventoStazione;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Itinerario;
import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoGuasto;
import it.uni.reti2.entity.StoricoStatoStazione;
import it.uni.reti2.entity.StoricoStatoTreno;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.entity.Utente;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unico punto della Centrale che parla con il database.
 *
 * <p>Le entità restano quelle di Panache in stile <em>active record</em> (i metodi statici
 * {@code findById}, {@code list}, {@code persist}, {@code delete} stanno sulla classe entità):
 * qui dentro vengono solo raccolti tutti i punti in cui quei metodi venivano chiamati. Prima
 * erano sparsi fra gli endpoint REST, il consumer MQTT, il watchdog e perfino dentro un'entità,
 * e la stessa query era riscritta in più copie — l'elenco ordinato delle tratte di un itinerario
 * compariva in quattro posti diversi.</p>
 *
 * <h3>Cosa NON fa questa classe</h3>
 * <ul>
 *   <li><b>Non apre transazioni.</b> Niente {@code @Transactional} e niente
 *       {@code QuarkusTransaction}: i confini restano nei chiamanti, dove sono stati messi
 *       apposta (l'ingestion e il FaultMonitor devono chiudere la transazione <em>dentro</em>
 *       il try, così le notifiche su MQTT e WebSocket partono solo a commit riuscito).
 *       I metodi qui sotto girano nella transazione che ha aperto chi li chiama.</li>
 *   <li><b>Non decide niente.</b> Nessuna {@code Response} REST, nessun controllo di
 *       validità, nessun messaggio d'errore: quelli restano nel RestApiGateway.</li>
 *   <li><b>Non tocca la cache.</b> Il TrafficLogicEngine è RAM, non database.</li>
 * </ul>
 */
@ApplicationScoped
public class RailwayRepository {

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    /** Riga di anagrafica della stazione, o null se quell'id non esiste. */
    public Stazione trovaStazione(String id) {
        return Stazione.findById(id);
    }

    /** Serve alla validazione dell'id dei nodi di campo: basta sapere se c'è. */
    public boolean esisteStazione(String id) {
        return Stazione.findById(id) != null;
    }

    /** Tutte le stazioni: la usa il TrafficLogicEngine per popolare la cache all'avvio. */
    public List<Stazione> tutteLeStazioni() {
        return Stazione.listAll();
    }

    public void salvaStazione(Stazione stazione) {
        stazione.persist();
    }

    public void eliminaStazione(Stazione stazione) {
        stazione.delete();
    }

    /**
     * Quante tratte partono da questa stazione o ci arrivano. Se sono più di zero la
     * stazione non si può eliminare: Tratte ha una chiave esterna verso Stazione.
     */
    public long contaTratteConStazione(String idStazione) {
        return Tratta.count("stazionePartenza.id = ?1 or stazioneArrivo.id = ?1", idStazione);
    }

    /**
     * Cancella i transiti VIVI della stazione (la tabella dello stato corrente, che la
     * chiave esterna ce l'ha davvero). Lo storico non si tocca: RF02.7.
     *
     * @return Quante righe sono state cancellate.
     */
    public long eliminaTransitiDiStazione(String idStazione) {
        return Transito.delete("stazione.id", idStazione);
    }

    // ──────────────────────────────────────────────────────────────
    // TRENI
    // ──────────────────────────────────────────────────────────────

    /** Riga della tabella Treni, o null se quel convoglio non esiste. */
    public Treno trovaTreno(String id) {
        return Treno.findById(id);
    }

    /** Gemella di {@link #esisteStazione(String)} per la validazione dei convogli. */
    public boolean esisteTreno(String id) {
        return Treno.findById(id) != null;
    }

    /** Tutti i convogli: la usa il TrafficLogicEngine all'avvio. */
    public List<Treno> tuttiITreni() {
        return Treno.listAll();
    }

    /**
     * Quanti convogli risultano fermi su questa tratta. {@code Treni.PosizioneAttualeTrattaOStazione}
     * è una chiave esterna verso Tratte: se il conteggio è maggiore di zero la DELETE dell'arco
     * la rifiuterebbe Postgres, e l'amministratore si prenderebbe un 500 con l'eccezione invece
     * del 409 con la spiegazione che chiede RF01.3.5.
     */
    public long contaTreniInPosizioneSuTratta(String idTratta) {
        return Treno.count("posizioneAttualeTratta.id", idTratta);
    }

    public void salvaTreno(Treno treno) {
        treno.persist();
    }

    public void eliminaTreno(Treno treno) {
        treno.delete();
    }

    /** Come per le stazioni: spariscono solo i transiti vivi, lo storico resta. */
    public long eliminaTransitiDiTreno(String idTreno) {
        return Transito.delete("treno.id", idTreno);
    }

    // ──────────────────────────────────────────────────────────────
    // ITINERARI E TRATTE
    // ──────────────────────────────────────────────────────────────

    public Itinerario trovaItinerario(String id) {
        return Itinerario.findById(id);
    }

    public List<Itinerario> tuttiGliItinerari() {
        return Itinerario.listAll();
    }

    public void salvaItinerario(Itinerario itinerario) {
        itinerario.persist();
    }

    public void eliminaItinerario(Itinerario itinerario) {
        itinerario.delete();
    }

    public Tratta trovaTratta(String id) {
        return Tratta.findById(id);
    }

    /** Tutti gli archi fisici della rete, in ordine di id. */
    public List<Tratta> tutteLeTratte() {
        return Tratta.listAll(Sort.by("id"));
    }

    /**
     * L'arco che collega due stazioni, o null se in rete non c'è.
     * La tratta è ORIENTATA: A-&gt;B non vale come B-&gt;A, in rete sono due righe distinte.
     */
    public Tratta trovaTrattaFra(String idPartenza, String idArrivo) {
        return Tratta.find("stazionePartenza.id = ?1 and stazioneArrivo.id = ?2",
                idPartenza, idArrivo).firstResult();
    }

    public void salvaTratta(Tratta tratta) {
        tratta.persist();
    }

    public void eliminaTratta(Tratta tratta) {
        tratta.delete();
    }

    /**
     * Le righe di Itinerario_Tratta nell'ordine di percorrenza. È la lettura più usata di
     * tutta la Centrale: ci passano il caricamento dell'itinerario del digital twin, il
     * calcolo della prossima stazione e la composizione del DTO per il frontend.
     */
    public List<ItinerarioTratta> tratteOrdinateDi(String idItinerario) {
        return ItinerarioTratta.find("itinerario.id", Sort.by("ordine"), idItinerario).list();
    }

    /**
     * La sequenza ordinata degli id di stazione di un itinerario, ricostruita dalle sue
     * tratte: la prima partenza e poi tutti gli arrivi.
     */
    public List<String> stazioniOrdinateDi(String idItinerario) {
        List<ItinerarioTratta> tratte = tratteOrdinateDi(idItinerario);
        List<String> stazioni = new ArrayList<>();
        if (!tratte.isEmpty()) {
            stazioni.add(tratte.get(0).tratta.stazionePartenza.id);
            for (ItinerarioTratta it : tratte) {
                stazioni.add(it.tratta.stazioneArrivo.id);
            }
        }
        return stazioni;
    }

    /** Quanti itinerari usano questo arco: se non è zero l'arco non si elimina. */
    public long contaItinerariCheUsano(String idTratta) {
        return ItinerarioTratta.count("id.idTratta", idTratta);
    }

    /**
     * Come sopra ma escludendo un itinerario: serve a capire se il tempo di percorrenza
     * di un arco si può cambiare senza modificare di nascosto gli altri percorsi.
     */
    public long contaAltriItinerariCheUsano(String idTratta, String idItinerario) {
        return ItinerarioTratta.count("id.idTratta = ?1 and id.idItinerario <> ?2",
                idTratta, idItinerario);
    }

    public void salvaItinerarioTratta(ItinerarioTratta riga) {
        riga.persist();
    }

    /** Smonta l'itinerario: cancella le sue righe di Itinerario_Tratta. */
    public long eliminaTratteDellItinerario(String idItinerario) {
        return ItinerarioTratta.delete("id.idItinerario", idItinerario);
    }

    // ──────────────────────────────────────────────────────────────
    // TRANSITI
    // ──────────────────────────────────────────────────────────────

    /**
     * Il transito ancora aperto (senza ora di uscita) di un treno in una stazione:
     * è quello che va chiuso quando arriva il passaggio in uscita.
     */
    public Transito trovaTransitoAperto(String idTreno, String idStazione) {
        return Transito.find("treno.id = ?1 and stazione.id = ?2 and tempoUscita is null",
                idTreno, idStazione).firstResult();
    }

    /**
     * Quanti transiti VIVI insistono su una tratta. Serve prima di eliminare l'arco: verso
     * Tratte la tabella dei transiti correnti ha davvero una chiave esterna (sui transiti
     * STORICI invece è caduta con RF02.7, e infatti quelli non si contano).
     */
    public long contaTransitiSuTratta(String idTratta) {
        return Transito.count("tratta.id", idTratta);
    }

    public void salvaTransito(Transito transito) {
        transito.persist();
    }

    /** Gli ultimi passaggi storicizzati, dal più recente, per la pagina Transiti. */
    public List<StoricoTransito> ultimiTransitiStorici(int quanti) {
        return StoricoTransito.findAll(Sort.descending("tsStoricizzazione"))
                .page(0, quanti)
                .list();
    }

    // ──────────────────────────────────────────────────────────────
    // GUASTI
    // ──────────────────────────────────────────────────────────────

    public Guasto trovaGuasto(String id) {
        return Guasto.findById(id);
    }

    /** Tutti i guasti, dal più recente: è l'elenco allarmi del frontend. */
    public List<Guasto> tuttiIGuastiRecenti() {
        return Guasto.listAll(Sort.descending("timestamp"));
    }

    /**
     * I guasti ancora aperti di una sorgente. Il filtro su sorgenteTipo non è un di più:
     * sorgenteId da solo non è univoco, e un treno che si chiama come una stazione si
     * vedrebbe chiudere i guasti dell'altra.
     */
    public List<Guasto> guastiApertiDi(String sorgenteId, String sorgenteTipo) {
        return Guasto.list("sorgenteId = ?1 and sorgenteTipo = ?2 and risolto = false",
                sorgenteId, sorgenteTipo);
    }

    /** Tutti i guasti non ancora risolti: la cache dei guasti attivi parte da qui. */
    public List<Guasto> guastiNonRisolti() {
        return Guasto.list("risolto", false);
    }

    /**
     * I guasti di stazione ancora aperti che sono stati generati da un convoglio fermatosi
     * guasto sui suoi binari. Non sono avarie della stazione: quando il convoglio viene
     * riparato vanno chiusi con lui, e per ritrovarli serve il tipo, non la sorgente
     * (la sorgente di queste righe è la stazione, non il treno che le ha causate).
     */
    public List<Guasto> guastiDerivatiDaConvoglio() {
        return Guasto.list("tipo = 'convoglio_guasto_in_stazione' and risolto = false");
    }

    public void salvaGuasto(Guasto guasto) {
        guasto.persist();
    }

    // ──────────────────────────────────────────────────────────────
    // STORICI
    // ──────────────────────────────────────────────────────────────

    /** La riga di storico di un guasto, o null se quel guasto non ne ha una. */
    public StoricoGuasto trovaStoricoGuasto(String guastoId) {
        return StoricoGuasto.find("guastoId", guastoId).firstResult();
    }

    /** Scrive la riga di Storico_Guasti copiando i dati dal guasto. */
    public void salvaStoricoGuasto(Guasto guasto) {
        StoricoGuasto storico = StoricoGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto));
        storico.persist();
    }

    public void salvaStoricoStatoTreno(Treno treno, String statoPrecedente) {
        StoricoStatoTreno storico = StoricoStatoTreno.fotografiaDi(treno, statoPrecedente);
        storico.persist();
    }

    public void salvaStoricoStatoStazione(Stazione stazione, String stato, String statoPrecedente) {
        StoricoStatoStazione storico = StoricoStatoStazione.fotografiaDi(stazione, stato, statoPrecedente);
        storico.persist();
    }

    /**
     * Riga di audit sulla tabella eventi_stazioni. È complementare a Storico_Stato_Stazioni:
     * quello registra il cambio di stato, questa registra il fatto che l'ha provocato.
     *
     * <p>Il nome della stazione lo passa il chiamante e non lo si rilegge dall'anagrafica:
     * chi scrive questi eventi (watchdog e ingestion) ha in mano la copia in cache, che è
     * quella coerente con l'istante dell'evento. La tabella non ha una chiave esterna verso
     * la stazione (RF02.7), quindi è l'unico posto dove quel nome resta scritto.</p>
     */
    public void salvaEventoStazione(String stazioneId, String nomeStazione, String stato,
                                    String tipoEvento, String descrizione) {
        EventoStazione evento = new EventoStazione();
        evento.stazioneId = stazioneId;
        evento.nomeStazione = nomeStazione;
        evento.stato = stato;
        evento.tipoEvento = tipoEvento;
        evento.descrizione = descrizione;
        evento.persist();
    }

    public void salvaStoricoTransito(Transito transito) {
        StoricoTransito storico = StoricoTransito.fotografiaDi(transito);
        storico.persist();
    }



    /**
     * Nome della sorgente da congelare nello storico del guasto. Per una stazione va letto
     * dall'anagrafica (l'id da solo, tipo "S3", non dice niente a chi rilegge lo storico fra
     * un mese); per un convoglio l'identificativo è già il nome.
     *
     * <p>Questa lettura stava dentro StoricoGuasto: era l'unica query nascosta in un'entità,
     * ed è la ragione per cui adesso il nome lo passa il repository.</p>
     */
    private String nomeDellaSorgente(Guasto guasto) {
        if (guasto.sorgenteId == null || guasto.sorgenteId.isEmpty()) {
            return null;
        }
        if ("STAZIONE".equalsIgnoreCase(guasto.sorgenteTipo)) {
            Stazione stazione = trovaStazione(guasto.sorgenteId);
            return stazione != null ? stazione.nome : guasto.sorgenteId;
        }
        return guasto.sorgenteId;
    }

    // ──────────────────────────────────────────────────────────────
    // UTENTI
    // ──────────────────────────────────────────────────────────────

    /**
     * Riga di anagrafica dell'operatore a partire dalla matricola. Il confronto è in
     * maiuscolo perché Keycloak salva gli username in minuscolo (mat001) mentre nel resto
     * del sistema la matricola è MAT001.
     */
    public Utente trovaUtentePerMatricola(String matricola) {
        return Utente.find("upper(matricola) = ?1", matricola.toUpperCase()).firstResult();
    }
}
