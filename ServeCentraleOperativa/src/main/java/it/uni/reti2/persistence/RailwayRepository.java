package it.uni.reti2.persistence;

import io.quarkus.panache.common.Sort;
import it.uni.reti2.entity.DatiOperatore;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.Itinerario;
import it.uni.reti2.entity.ItinerarioTratta;
import it.uni.reti2.entity.Stazione;
import it.uni.reti2.entity.StoricoAssegnazioneGuasto;
import it.uni.reti2.entity.StoricoGuasto;
import it.uni.reti2.entity.StoricoInterventoManutenzione;
import it.uni.reti2.entity.StoricoItinerario;
import it.uni.reti2.entity.StoricoItinerarioTratta;
import it.uni.reti2.entity.StoricoStatoStazione;
import it.uni.reti2.entity.StoricoStatoTratta;
import it.uni.reti2.entity.StoricoStatoTreno;
import it.uni.reti2.entity.StoricoTransito;
import it.uni.reti2.entity.Transito;
import it.uni.reti2.entity.Tratta;
import it.uni.reti2.entity.Treno;
import it.uni.reti2.entity.Utente;
import it.uni.reti2.eventi.CausaEvento;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    /** I convogli attualmente assegnati a un itinerario. */
    public List<Treno> treniDellItinerario(String idItinerario) {
        return Treno.list("itinerario.id", idItinerario);
    }

    /**
     * Quanti convogli hanno questa tratta come posizione corrente:
     * Treni.PosizioneAttualeTrattaOStazione è una chiave esterna verso Tratte.
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

    public void salvaTransito(Transito transito) {
        transito.persist();
    }

    /** Quanti transiti vivi insistono su una tratta: blocca l'eliminazione dell'arco. */
    public long contaTransitiSuTratta(String idTratta) {
        return Transito.count("tratta.id", idTratta);
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
    /**
     * I guasti ancora aperti che appartengono alla stessa catena di eventi, cioè che discendono
     * dalla stessa avaria. Serve alla chiusura: la stazione resa impercorribile da un convoglio
     * guasto sui suoi binari è un guasto vero, che l'operatore deve vedere, ma non è un'avaria
     * in più, e quando la causa viene riparata deve chiudersi con lei.
     *
     * @param catenaId Catena di appartenenza (null o vuota: nessun risultato, un guasto senza
     *                 catena non ha conseguenze riconoscibili).
     */
    public List<Guasto> guastiApertiDellaCatena(String catenaId) {
        if (catenaId == null || catenaId.isBlank()) {
            return new ArrayList<>();
        }
        return Guasto.list("catenaId = ?1 and risolto = false", catenaId);
    }

    public List<Guasto> guastiNonRisolti() {
        return Guasto.list("risolto", false);
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

    /** Scrive la riga di Storico_Guasti congelando anche il nome della sorgente. */
    public void salvaStoricoGuasto(Guasto guasto) {
        StoricoGuasto storico = StoricoGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto));
        storico.persist();
    }

    public void salvaStoricoStatoTreno(Treno treno, String statoPrecedente) {
        salvaStoricoStatoTreno(treno, statoPrecedente, null);
    }

    /**
     * Come sopra, ma la riga porta anche la causa del cambiamento: chi l'ha provocato e a quale
     * catena di eventi appartiene. È quello che rende leggibile a posteriori una reazione a
     * catena, dove il convoglio non si è fermato da solo ma perché qualcos'altro è successo.
     *
     * @param treno            Il convoglio con lo stato nuovo già assegnato.
     * @param statoPrecedente  Lo stato che aveva prima.
     * @param causa            La causa del cambiamento (null se il convoglio ha deciso da solo).
     */
    public void salvaStoricoStatoTreno(Treno treno, String statoPrecedente, CausaEvento causa) {
        StoricoStatoTreno storico = StoricoStatoTreno.fotografiaDi(treno, statoPrecedente, causa);
        storico.persist();
    }

    public void salvaStoricoStatoStazione(Stazione stazione, String stato, String statoPrecedente) {
        salvaStoricoStatoStazione(stazione, stato, statoPrecedente, null);
    }

    /**
     * Come sopra, con la causa del cambiamento.
     *
     * @param stazione        La stazione interessata.
     * @param stato           Lo stato nuovo.
     * @param statoPrecedente Lo stato che aveva prima.
     * @param causa           La causa del cambiamento (null se la stazione ha deciso da sola).
     */
    public void salvaStoricoStatoStazione(Stazione stazione, String stato, String statoPrecedente,
                                          CausaEvento causa) {
        StoricoStatoStazione storico = StoricoStatoStazione.fotografiaDi(stazione, stato, statoPrecedente, causa);
        storico.persist();
    }

    /**
     * Scrive la riga di {@code Storico_Stato_Tratte}: com'era la percorribilità dell'arco, com'è
     * adesso e per colpa di chi. Come per le stazioni, la percorribilità corrente è
     * {@code @Transient} e a database va solo il cambiamento.
     *
     * @param tratta          L'arco interessato.
     * @param stato           La percorribilità nuova.
     * @param statoPrecedente Quella che aveva prima.
     * @param causa           La causa del cambiamento (null se non è nota).
     */
    public void salvaStoricoStatoTratta(Tratta tratta, String stato, String statoPrecedente,
                                        CausaEvento causa) {
        StoricoStatoTratta storico = StoricoStatoTratta.fotografiaDi(tratta, stato, statoPrecedente, causa);
        storico.persist();
    }

    public void salvaStoricoTransito(Transito transito) {
        StoricoTransito storico = StoricoTransito.fotografiaDi(transito);
        storico.persist();
    }

    // ──────────────────────────────────────────────────────────────
    // STORICO DEGLI ITINERARI PERCORSI (RF02.7)
    // ──────────────────────────────────────────────────────────────

    /**
     * L'itinerario che il convoglio sta percorrendo adesso, come riga di storico ancora
     * aperta (senza istante di completamento).
     *
     * <p>È null quando il convoglio non ha un itinerario, ma anche quando ce l'ha e nessuno
     * ha mai registrato l'assegnazione: sono i convogli assegnati prima che questa
     * registrazione esistesse, quelli che sistema {@link #allineaItinerariPercorsi()}.</p>
     *
     * @param idTreno Il convoglio.
     * @return La riga aperta di Storico_Itinerari, oppure null.
     */
    public StoricoItinerario itinerarioPercorsoAperto(String idTreno) {
        return StoricoItinerario.find("trenoId = ?1 and tsCompletamento is null", idTreno)
                .firstResult();
    }

    /**
     * Registra che da adesso il convoglio percorre questo itinerario: chiude l'assegnazione
     * precedente e ne apre una nuova, copiandosi dentro il percorso di oggi tratta per tratta.
     *
     * <p><b>Registra i cambiamenti, non i campionamenti</b> (è la regola di RF02.7): se il
     * convoglio stava già percorrendo quell'itinerario <em>con quel percorso</em> non viene
     * scritto niente. Senza questo controllo ogni PUT su /api/tratte, che rimanda sempre
     * l'elenco completo dei convogli assegnati, lascerebbe una riga nuova per ciascuno anche
     * non avendo cambiato nulla.</p>
     *
     * <p>Il confronto è sul percorso e non solo sull'identificativo perché l'itinerario in
     * sé è solo un id: se l'amministratore ne riscrive le tappe, da quel momento i convogli
     * assegnati ne stanno percorrendo un altro, e quello vecchio va chiuso.</p>
     *
     * @param idTreno    Il convoglio a cui l'itinerario è stato assegnato.
     * @param itinerario L'itinerario assegnato.
     */
    public void registraAssegnazioneItinerario(String idTreno, Itinerario itinerario) {
        List<ItinerarioTratta> tratte = tratteOrdinateDi(itinerario.id);
        StoricoItinerario storico = StoricoItinerario.fotografiaDi(idTreno, itinerario, tratte);

        StoricoItinerario aperto = itinerarioPercorsoAperto(idTreno);
        if (aperto != null) {
            if (aperto.itinerarioId.equals(itinerario.id)
                    && Objects.equals(aperto.descrizionePercorso, storico.descrizionePercorso)) {
                return; // stesso itinerario e stesse tappe: non è cambiato niente
            }
            aperto.tsCompletamento = Instant.now();
        }

        // La persist() è qui e non in fondo apposta: la colonna è IDENTITY, quindi l'id lo
        // assegna il database all'INSERT ed è quello che le righe figlie devono puntare.
        storico.persist();
        for (ItinerarioTratta riga : tratte) {
            StoricoItinerarioTratta.fotografiaDi(storico.id, riga).persist();
        }
    }

    /**
     * Apre la riga di storico dei convogli che <em>stanno già</em> percorrendo un itinerario
     * ma non ne hanno una aperta.
     *
     * <p>Serve perché la registrazione degli itinerari percorsi è arrivata dopo i convogli:
     * chi era già assegnato non ha lasciato traccia dell'assegnazione, e senza questo
     * allineamento non ne lascerebbe mai una — la riga la apre solo un <em>cambio</em>, e
     * per quei convogli il cambio è già avvenuto. Il risultato sarebbe una memoria storica
     * che dice "nessuno sta percorrendo niente" mentre i convogli viaggiano.</p>
     *
     * <p>Si esegue a ogni avvio ed è idempotente: al secondo giro quei convogli la riga ce
     * l'hanno e vengono saltati. L'unica cosa che di loro non si può sapere è <em>quando</em>
     * l'assegnazione è cominciata davvero, perché nessuno l'aveva scritta: il
     * {@code ts_assegnazione} di queste righe è il momento dell'allineamento e non quello
     * dell'assegnazione. Da qui in avanti le due cose coincidono.</p>
     *
     * @return Quante righe sono state aperte.
     */
    public int allineaItinerariPercorsi() {
        int aperte = 0;
        for (Treno treno : tuttiITreni()) {
            if (treno.itinerario == null) {
                continue; // convoglio senza itinerario: non sta percorrendo niente
            }
            if (itinerarioPercorsoAperto(treno.id) != null) {
                continue; // la sua riga c'è già
            }
            registraAssegnazioneItinerario(treno.id, treno.itinerario);
            aperte++;
        }
        return aperte;
    }

    /**
     * Chiude l'itinerario che il convoglio stava percorrendo, se ne aveva uno: succede
     * quando viene sganciato, quando il suo itinerario viene cancellato e quando il
     * convoglio stesso viene rottamato.
     *
     * <p>{@code ts_completamento} vuol dire "qui il convoglio ha smesso di percorrerlo", non
     * "è arrivato a destinazione": il capolinea nel sistema non chiude niente, il digital
     * twin inverte la marcia e riparte, quindi un istante di arrivo vero non esiste.</p>
     *
     * @param idTreno Il convoglio che smette di percorrere il proprio itinerario.
     */
    public void registraFineItinerario(String idTreno) {
        StoricoItinerario aperto = itinerarioPercorsoAperto(idTreno);
        if (aperto != null) {
            aperto.tsCompletamento = Instant.now();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STORICO DELLE ASSEGNAZIONI DEGLI OPERATORI (RF02.7)
    // ──────────────────────────────────────────────────────────────

    /**
     * La presa in carico ancora aperta di un guasto, cioè l'operatore che ci sta lavorando
     * in questo momento.
     *
     * @param guastoId Il guasto.
     * @return La riga aperta di Storico_Assegnazioni_Guasti, oppure null.
     */
    public StoricoAssegnazioneGuasto assegnazioneApertaDi(String guastoId) {
        return StoricoAssegnazioneGuasto.find("guastoId = ?1 and tsRisoluzione is null", guastoId)
                .firstResult();
    }

    /**
     * Apre la presa in carico di un guasto da parte di un operatore. Se quel guasto era già
     * stato preso in carico da qualcun altro la riga di prima viene chiusa: il passaggio di
     * mano resta scritto, e a lavorarci non risulta mai più di uno alla volta.
     *
     * @param guasto    Il guasto preso in carico.
     * @param operatore Chi lo prende in carico.
     */
    public void apriAssegnazioneGuasto(Guasto guasto, DatiOperatore operatore) {
        StoricoAssegnazioneGuasto aperta = assegnazioneApertaDi(guasto.id);
        if (aperta != null) {
            if (operatore.id().equals(aperta.operatoreId)) {
                return; // lo stesso operatore lo aveva già preso in carico
            }
            aperta.tsRisoluzione = Instant.now();
        }
        StoricoAssegnazioneGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto), operatore).persist();
    }

    /**
     * Chiude la presa in carico di un guasto appena risolto.
     *
     * <p>Se non ce n'era una aperta e a chiudere è stato un operatore, la riga si scrive
     * adesso già chiusa: chi ha risolto un allarme senza prima prenderlo in carico se n'è
     * occupato lo stesso, e RF02.7 vuole sapere chi è stato. Se invece l'operatore non c'è
     * (chiusura automatica di M3 e M4, dove il guasto si richiude perché la condizione è
     * rientrata da sola) non si scrive niente: non c'è nessuna assegnazione da registrare.
     * Resta il caso di mezzo, cioè la condizione che rientra mentre qualcuno ci stava
     * lavorando: lì la riga aperta c'è e va chiusa, altrimenti quell'operatore risulterebbe
     * al lavoro per sempre su un allarme già finito.</p>
     *
     * @param guasto    Il guasto risolto.
     * @param operatore Chi lo ha risolto, oppure null se si è chiuso da solo.
     */
    public void chiudiAssegnazioneGuasto(Guasto guasto, DatiOperatore operatore) {
        StoricoAssegnazioneGuasto aperta = assegnazioneApertaDi(guasto.id);
        if (aperta == null) {
            if (operatore == null) {
                return;
            }
            aperta = StoricoAssegnazioneGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto), operatore);
            aperta.persist();
        }
        aperta.tsRisoluzione = guasto.timestampRisoluzione != null
                ? guasto.timestampRisoluzione
                : Instant.now();
    }

    /**
     * Apre la riga della squadra di manutenzione mandata a una stazione: è l'altra faccia
     * delle "assegnazioni degli operatori" di RF02.7, quella fisica.
     *
     * <p>La riga nasce <b>aperta</b> ({@code ts_rientro} nullo), perché l'intervento comincia
     * adesso e finisce quando la squadra ha finito. Prima nasceva già chiusa: invio e rientro
     * stavano nella stessa chiamata e fra i due timestamp passavano millisecondi.</p>
     *
     * @param stazione   La stazione dove è stata mandata la squadra.
     * @param guastoId   Il guasto che ha motivato l'invio, oppure null.
     * @param operatore  Chi ha dato il comando dalla web app.
     * @param statoPrima Lo stato della stazione prima dell'intervento.
     * @param tsInvio    Istante in cui la squadra è stata mandata.
     * @param catenaId   Catena coniata per questo intervento.
     */
    public void apriInterventoManutenzione(Stazione stazione, String guastoId,
                                           DatiOperatore operatore,
                                           String statoPrima, Instant tsInvio, String catenaId) {
        StoricoInterventoManutenzione storico = StoricoInterventoManutenzione.invioSquadra(
                stazione, guastoId, operatore, statoPrima, tsInvio, catenaId);
        storico.persist();
    }

    /**
     * L'intervento ancora in corso su una stazione, cioè la riga con il rientro non ancora
     * scritto. Se ce n'è più di una (invii ripetuti) vale la più recente.
     *
     * @param stazioneId La stazione.
     * @return La riga aperta, oppure null se nessuna squadra è sul posto.
     */
    public StoricoInterventoManutenzione interventoApertoDi(String stazioneId) {
        return StoricoInterventoManutenzione
                .find("stazioneId = ?1 and tsRientro is null", Sort.by("tsInvio").descending(), stazioneId)
                .firstResult();
    }

    /**
     * Chiude l'intervento: la squadra ha finito e la stazione è tornata nello stato indicato.
     *
     * @param intervento La riga aperta da {@link #apriInterventoManutenzione}.
     * @param statoDopo  Lo stato in cui la stazione è rimasta a lavoro concluso.
     */
    public void chiudiInterventoManutenzione(StoricoInterventoManutenzione intervento, String statoDopo) {
        if (intervento == null) {
            return;
        }
        intervento.tsRientro = Instant.now();
        intervento.statoStazioneDopo = statoDopo;
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
