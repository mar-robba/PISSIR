package it.uni.reti2.persistence;

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unico punto della Centrale che parla con il database, in JDBC.
 *
 * <p>Ogni metodo è una query SQL scritta per intero, con i parametri passati da un
 * {@link PreparedStatement} (mai concatenati nella stringa). Le righe lette diventano gli
 * oggetti del package entity, che sono semplici classi con i campi pubblici: il repository
 * li riempie colonna per colonna e, per le chiavi esterne che il resto della Centrale usa
 * come oggetti (l'itinerario e la tratta di un treno, le due stazioni di una tratta), fa la
 * lettura in più che serve.</p>
 *
 * <h3>Niente scritture "automatiche"</h3>
 * <p>Cambiare un campo di un oggetto letto dentro una transazione non aggiorna la riga:
 * ogni cambiamento ha il suo UPDATE, con un metodo che dice cosa scrive ({@link #aggiornaStatoTreno},
 * {@link #chiudiGuasto}, {@link #chiudiTransito}...). È più lungo, ma chi legge il codice
 * vede ogni scrittura.</p>
 *
 * <h3>Cosa NON fa questa classe</h3>
 * <ul>
 *   <li><b>Non apre transazioni.</b> I confini li decide chi chiama, con
 *       {@link Database#inTransazione}: i metodi qui sotto usano la connessione della
 *       transazione in corso, o una connessione in autocommit se non ce n'è nessuna.</li>
 *   <li><b>Non decide niente.</b> Nessuna risposta REST, nessun controllo di validità,
 *       nessun messaggio d'errore: quelli restano nel RestApiGateway e nel GestoreEventi.</li>
 *   <li><b>Non tocca la cache.</b> Il TrafficLogicEngine è RAM, non database.</li>
 * </ul>
 */
public class RailwayRepository {

    /** Le connessioni e le transazioni. */
    public Database db;

    // ──────────────────────────────────────────────────────────────
    // STAZIONI
    // ──────────────────────────────────────────────────────────────

    private static final String COLONNE_STAZIONE =
            "id_stazione, nome, tipoCapolineaPartenzaoNormale, latitudine, longitudine, binari";

    /** Riga di anagrafica della stazione, o null se quell'id non esiste. */
    public Stazione trovaStazione(String id) {
        return db.conConnessione(c -> leggiStazione(c, id));
    }

    /** Serve alla validazione dell'id dei nodi di campo: basta sapere se c'è. */
    public boolean esisteStazione(String id) {
        return trovaStazione(id) != null;
    }

    /** Tutte le stazioni: la usa il TrafficLogicEngine per popolare la cache all'avvio. */
    public List<Stazione> tutteLeStazioni() {
        return db.conConnessione(c -> {
            List<Stazione> stazioni = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLONNE_STAZIONE + " FROM Stazione");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stazioni.add(stazioneDa(rs));
                }
            }
            return stazioni;
        });
    }

    /** Inserisce una stazione nuova (lo stato non è una colonna: resta solo in cache). */
    public void salvaStazione(Stazione stazione) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Stazione (" + COLONNE_STAZIONE + ") VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, stazione.id);
                ps.setString(2, stazione.nome);
                ps.setString(3, stazione.tipoCapolineaPartenzaoNormale);
                setDouble(ps, 4, stazione.latitudine);
                setDouble(ps, 5, stazione.longitudine);
                setInteger(ps, 6, stazione.binari);
                return ps.executeUpdate();
            }
        });
    }

    /** Riscrive i campi di anagrafica (nome, coordinate, binari) di una stazione esistente. */
    public void aggiornaStazione(Stazione stazione) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Stazione SET nome = ?, tipoCapolineaPartenzaoNormale = ?, latitudine = ?, "
                            + "longitudine = ?, binari = ? WHERE id_stazione = ?")) {
                ps.setString(1, stazione.nome);
                ps.setString(2, stazione.tipoCapolineaPartenzaoNormale);
                setDouble(ps, 3, stazione.latitudine);
                setDouble(ps, 4, stazione.longitudine);
                setInteger(ps, 5, stazione.binari);
                ps.setString(6, stazione.id);
                return ps.executeUpdate();
            }
        });
    }

    public void eliminaStazione(Stazione stazione) {
        esegui("DELETE FROM Stazione WHERE id_stazione = ?", stazione.id);
    }

    /**
     * Quante tratte partono da questa stazione o ci arrivano. Se sono più di zero la
     * stazione non si può eliminare: Tratte ha una chiave esterna verso Stazione.
     */
    public long contaTratteConStazione(String idStazione) {
        return conta("SELECT COUNT(*) FROM Tratte WHERE StazionePartenzaFK = ? OR StazioneArrivoFK = ?",
                idStazione, idStazione);
    }

    /**
     * Cancella i transiti VIVI della stazione (la tabella dello stato corrente, che la
     * chiave esterna ce l'ha davvero). Lo storico non si tocca: RF02.7.
     *
     * @return Quante righe sono state cancellate.
     */
    public long eliminaTransitiDiStazione(String idStazione) {
        return esegui("DELETE FROM Transiti WHERE id_stazione = ?", idStazione);
    }

    // ──────────────────────────────────────────────────────────────
    // TRENI
    // ──────────────────────────────────────────────────────────────

    /** Riga della tabella Treni, con itinerario e tratta corrente, o null se quel convoglio non esiste. */
    public Treno trovaTreno(String id) {
        return db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione FROM Treni WHERE id_convoglio = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? trenoDa(c, rs) : null;
                }
            }
        });
    }

    /** Gemella di {@link #esisteStazione(String)} per la validazione dei convogli. */
    public boolean esisteTreno(String id) {
        return conta("SELECT COUNT(*) FROM Treni WHERE id_convoglio = ?", id) > 0;
    }

    /** Tutti i convogli: la usa il TrafficLogicEngine all'avvio. */
    public List<Treno> tuttiITreni() {
        return treniDove("", null);
    }

    /**
     * I convogli assegnati a un itinerario. Serve alle PUT e DELETE degli itinerari, che
     * devono sganciarli o avvisarli.
     */
    public List<Treno> treniDellItinerario(String idItinerario) {
        return treniDove(" WHERE itinerario = ?", idItinerario);
    }

    /**
     * Quanti convogli risultano fermi su questa tratta. {@code Treni.PosizioneAttualeTrattaOStazione}
     * è una chiave esterna verso Tratte: se il conteggio è maggiore di zero la DELETE dell'arco
     * la rifiuterebbe Postgres, e l'amministratore si prenderebbe un 500 con l'eccezione invece
     * del 409 con la spiegazione che chiede RF01.3.5.
     */
    public long contaTreniInPosizioneSuTratta(String idTratta) {
        return conta("SELECT COUNT(*) FROM Treni WHERE PosizioneAttualeTrattaOStazione = ?", idTratta);
    }

    /** Inserisce un convoglio nuovo. */
    public void salvaTreno(Treno treno) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Treni (id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, treno.id);
                ps.setString(2, treno.stato);
                ps.setString(3, treno.itinerario != null ? treno.itinerario.id : null);
                ps.setString(4, treno.posizioneAttualeTratta != null ? treno.posizioneAttualeTratta.id : null);
                return ps.executeUpdate();
            }
        });
    }

    /** Scrive lo stato del convoglio (il CHECK della colonna accetta solo i valori canonici). */
    public void aggiornaStatoTreno(String idTreno, String stato) {
        esegui("UPDATE Treni SET stato = ? WHERE id_convoglio = ?", stato, idTreno);
    }

    /** Aggancia il convoglio a un itinerario, o lo sgancia con null. */
    public void aggiornaItinerarioTreno(String idTreno, String idItinerario) {
        esegui("UPDATE Treni SET itinerario = ? WHERE id_convoglio = ?", idItinerario, idTreno);
    }

    /** Scrive la tratta su cui si trova il convoglio. */
    public void aggiornaPosizioneTreno(String idTreno, String idTratta) {
        esegui("UPDATE Treni SET PosizioneAttualeTrattaOStazione = ? WHERE id_convoglio = ?", idTratta, idTreno);
    }

    public void eliminaTreno(Treno treno) {
        esegui("DELETE FROM Treni WHERE id_convoglio = ?", treno.id);
    }

    /** Come per le stazioni: spariscono solo i transiti vivi, lo storico resta. */
    public long eliminaTransitiDiTreno(String idTreno) {
        return esegui("DELETE FROM Transiti WHERE id_convoglio = ?", idTreno);
    }

    // ──────────────────────────────────────────────────────────────
    // ITINERARI E TRATTE
    // ──────────────────────────────────────────────────────────────

    public Itinerario trovaItinerario(String id) {
        return db.conConnessione(c -> leggiItinerario(c, id));
    }

    public List<Itinerario> tuttiGliItinerari() {
        return db.conConnessione(c -> {
            List<Itinerario> itinerari = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id_itinerario, capolinea FROM Itinerari");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itinerari.add(itinerarioDa(rs));
                }
            }
            return itinerari;
        });
    }

    public void salvaItinerario(Itinerario itinerario) {
        esegui("INSERT INTO Itinerari (id_itinerario, capolinea) VALUES (?, ?)",
                itinerario.id, itinerario.capolineaId);
    }

    /** Scrive il capolinea dell'itinerario (null = nessun capolinea). */
    public void aggiornaCapolinea(Itinerario itinerario) {
        esegui("UPDATE Itinerari SET capolinea = ? WHERE id_itinerario = ?", itinerario.capolineaId, itinerario.id);
    }

    public void eliminaItinerario(Itinerario itinerario) {
        esegui("DELETE FROM Itinerari WHERE id_itinerario = ?", itinerario.id);
    }

    public Tratta trovaTratta(String id) {
        return db.conConnessione(c -> leggiTratta(c, id));
    }

    /** Tutti gli archi fisici della rete, in ordine di id. */
    public List<Tratta> tutteLeTratte() {
        return db.conConnessione(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id_Tratta FROM Tratte ORDER BY id_Tratta");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            List<Tratta> tratte = new ArrayList<>();
            for (String id : ids) {
                tratte.add(leggiTratta(c, id));
            }
            return tratte;
        });
    }

    /**
     * L'arco che collega due stazioni, o null se in rete non c'è.
     * La tratta è ORIENTATA: A-&gt;B non vale come B-&gt;A, in rete sono due righe distinte.
     */
    public Tratta trovaTrattaFra(String idPartenza, String idArrivo) {
        return db.conConnessione(c -> {
            String id = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_Tratta FROM Tratte WHERE StazionePartenzaFK = ? AND StazioneArrivoFK = ?")) {
                ps.setString(1, idPartenza);
                ps.setString(2, idArrivo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getString(1);
                    }
                }
            }
            return id != null ? leggiTratta(c, id) : null;
        });
    }

    public void salvaTratta(Tratta tratta) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Tratte (id_Tratta, StazionePartenzaFK, StazioneArrivoFK, tempoPercorrenzaMinuti) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, tratta.id);
                ps.setString(2, tratta.stazionePartenza.id);
                ps.setString(3, tratta.stazioneArrivo.id);
                setInteger(ps, 4, tratta.tempoPercorrenzaMinuti);
                return ps.executeUpdate();
            }
        });
    }

    /** Riscrive stazioni e tempo di percorrenza di una tratta esistente. */
    public void aggiornaTratta(Tratta tratta) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Tratte SET StazionePartenzaFK = ?, StazioneArrivoFK = ?, tempoPercorrenzaMinuti = ? WHERE id_Tratta = ?")) {
                ps.setString(1, tratta.stazionePartenza.id);
                ps.setString(2, tratta.stazioneArrivo.id);
                setInteger(ps, 3, tratta.tempoPercorrenzaMinuti);
                ps.setString(4, tratta.id);
                return ps.executeUpdate();
            }
        });
    }

    public void eliminaTratta(Tratta tratta) {
        esegui("DELETE FROM Tratte WHERE id_Tratta = ?", tratta.id);
    }

    /**
     * Le righe di Itinerario_Tratta nell'ordine di percorrenza. È la lettura più usata di
     * tutta la Centrale: ci passano il caricamento dell'itinerario del digital twin, il
     * calcolo della prossima stazione e la composizione del DTO per il frontend.
     */
    public List<ItinerarioTratta> tratteOrdinateDi(String idItinerario) {
        return db.conConnessione(c -> {
            List<ItinerarioTratta> righe = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_itinerario, id_Tratta, ordine FROM Itinerario_Tratta WHERE id_itinerario = ? ORDER BY ordine")) {
                ps.setString(1, idItinerario);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItinerarioTratta riga = new ItinerarioTratta();
                        riga.id = new ItinerarioTratta.Id(rs.getString("id_itinerario"), rs.getString("id_Tratta"));
                        riga.ordine = rs.getInt("ordine");
                        righe.add(riga);
                    }
                }
            }
            // le letture annidate si fanno dopo aver chiuso il ResultSet
            Itinerario itinerario = righe.isEmpty() ? null : leggiItinerario(c, idItinerario);
            for (ItinerarioTratta riga : righe) {
                riga.itinerario = itinerario;
                riga.tratta = leggiTratta(c, riga.id.idTratta);
            }
            return righe;
        });
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
        return conta("SELECT COUNT(*) FROM Itinerario_Tratta WHERE id_Tratta = ?", idTratta);
    }

    /**
     * Come sopra ma escludendo un itinerario: serve a capire se il tempo di percorrenza
     * di un arco si può cambiare senza modificare di nascosto gli altri percorsi.
     */
    public long contaAltriItinerariCheUsano(String idTratta, String idItinerario) {
        return conta("SELECT COUNT(*) FROM Itinerario_Tratta WHERE id_Tratta = ? AND id_itinerario <> ?",
                idTratta, idItinerario);
    }

    public void salvaItinerarioTratta(ItinerarioTratta riga) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Itinerario_Tratta (id_itinerario, id_Tratta, ordine) VALUES (?, ?, ?)")) {
                ps.setString(1, riga.id.idItinerario);
                ps.setString(2, riga.id.idTratta);
                ps.setInt(3, riga.ordine);
                return ps.executeUpdate();
            }
        });
    }

    /** Smonta l'itinerario: cancella le sue righe di Itinerario_Tratta. */
    public long eliminaTratteDellItinerario(String idItinerario) {
        return esegui("DELETE FROM Itinerario_Tratta WHERE id_itinerario = ?", idItinerario);
    }

    // ──────────────────────────────────────────────────────────────
    // TRANSITI
    // ──────────────────────────────────────────────────────────────

    /**
     * Il transito ancora aperto (senza ora di uscita) di un treno in una stazione:
     * è quello che va chiuso quando arriva il passaggio in uscita.
     */
    public Transito trovaTransitoAperto(String idTreno, String idStazione) {
        return db.conConnessione(c -> {
            Transito transito = null;
            String idTratta = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_transito, id_Tratta, tempoEntrata, tempoUscita, ritardoMinuti FROM Transiti "
                            + "WHERE id_convoglio = ? AND id_stazione = ? AND tempoUscita IS NULL")) {
                ps.setString(1, idTreno);
                ps.setString(2, idStazione);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        transito = new Transito();
                        transito.id = rs.getString("id_transito");
                        idTratta = rs.getString("id_Tratta");
                        transito.tempoEntrata = istante(rs, "tempoEntrata");
                        transito.tempoUscita = istante(rs, "tempoUscita");
                        transito.ritardoMinuti = intero(rs, "ritardoMinuti");
                    }
                }
            }
            if (transito != null) {
                // Treno e stazione servono allo storico (id e nome), la tratta pure (descrizione)
                transito.treno = leggiTrenoSenzaRelazioni(c, idTreno);
                transito.stazione = leggiStazione(c, idStazione);
                transito.tratta = idTratta != null ? leggiTratta(c, idTratta) : null;
            }
            return transito;
        });
    }

    /**
     * Quanti transiti VIVI insistono su una tratta. Serve prima di eliminare l'arco: verso
     * Tratte la tabella dei transiti correnti ha davvero una chiave esterna (sui transiti
     * STORICI invece è caduta con RF02.7, e infatti quelli non si contano).
     */
    public long contaTransitiSuTratta(String idTratta) {
        return conta("SELECT COUNT(*) FROM Transiti WHERE id_Tratta = ?", idTratta);
    }

    public void salvaTransito(Transito transito) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Transiti (id_transito, id_stazione, id_convoglio, id_Tratta, tempoEntrata, tempoUscita, ritardoMinuti) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, transito.id);
                ps.setString(2, transito.stazione.id);
                ps.setString(3, transito.treno.id);
                ps.setString(4, transito.tratta != null ? transito.tratta.id : null);
                ps.setTimestamp(5, timestamp(transito.tempoEntrata));
                ps.setTimestamp(6, timestamp(transito.tempoUscita));
                setInteger(ps, 7, transito.ritardoMinuti);
                return ps.executeUpdate();
            }
        });
    }

    /** Scrive l'ora di uscita e il ritardo congelato di un transito già aperto. */
    public void chiudiTransito(Transito transito) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Transiti SET tempoUscita = ?, ritardoMinuti = ? WHERE id_transito = ?")) {
                ps.setTimestamp(1, timestamp(transito.tempoUscita));
                setInteger(ps, 2, transito.ritardoMinuti);
                ps.setString(3, transito.id);
                return ps.executeUpdate();
            }
        });
    }

    /** Gli ultimi passaggi storicizzati, dal più recente, per la pagina Transiti. */
    public List<StoricoTransito> ultimiTransitiStorici(int quanti) {
        return db.conConnessione(c -> {
            List<StoricoTransito> transiti = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_storico_transito, id_transito, id_stazione, nome_stazione, id_convoglio, id_Tratta, "
                            + "descrizione_tratta, tempoEntrata, tempoUscita, ritardoMinuti, ts_storicizzazione "
                            + "FROM Storico_Transiti ORDER BY ts_storicizzazione DESC LIMIT ?")) {
                ps.setInt(1, quanti);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        StoricoTransito t = new StoricoTransito();
                        t.id = rs.getLong("id_storico_transito");
                        t.idTransito = rs.getString("id_transito");
                        t.stazioneId = rs.getString("id_stazione");
                        t.nomeStazione = rs.getString("nome_stazione");
                        t.trenoId = rs.getString("id_convoglio");
                        t.trattaId = rs.getString("id_Tratta");
                        t.descrizioneTratta = rs.getString("descrizione_tratta");
                        t.tempoEntrata = istante(rs, "tempoEntrata");
                        t.tempoUscita = istante(rs, "tempoUscita");
                        t.ritardoMinuti = intero(rs, "ritardoMinuti");
                        t.tsStoricizzazione = istante(rs, "ts_storicizzazione");
                        transiti.add(t);
                    }
                }
            }
            return transiti;
        });
    }

    // ──────────────────────────────────────────────────────────────
    // GUASTI
    // ──────────────────────────────────────────────────────────────

    private static final String COLONNE_GUASTO =
            "id_Guasto, Stato_RisoltoONO, OperatoreCheSeNeStaOccupandoFK, tipo, severita, sorgenteTipo, "
                    + "sorgenteId, origine, messaggio, ts_apertura, ts_risoluzione";

    public Guasto trovaGuasto(String id) {
        List<Guasto> trovati = guastiDove(" WHERE id_Guasto = ?", id);
        return trovati.isEmpty() ? null : trovati.get(0);
    }

    /** Tutti i guasti, dal più recente: è l'elenco allarmi del frontend. */
    public List<Guasto> tuttiIGuastiRecenti() {
        return guastiDove(" ORDER BY ts_apertura DESC");
    }

    /**
     * I guasti ancora aperti di una sorgente. Il filtro su sorgenteTipo non è un di più:
     * sorgenteId da solo non è univoco, e un treno che si chiama come una stazione si
     * vedrebbe chiudere i guasti dell'altra.
     */
    public List<Guasto> guastiApertiDi(String sorgenteId, String sorgenteTipo) {
        return guastiDove(" WHERE sorgenteId = ? AND sorgenteTipo = ? AND Stato_RisoltoONO = FALSE",
                sorgenteId, sorgenteTipo);
    }

    /** Tutti i guasti non ancora risolti: la cache dei guasti attivi parte da qui. */
    public List<Guasto> guastiNonRisolti() {
        return guastiDove(" WHERE Stato_RisoltoONO = FALSE");
    }

    /**
     * I guasti di stazione ancora aperti che sono stati generati da un convoglio fermatosi
     * guasto sui suoi binari. Non sono avarie della stazione: quando il convoglio viene
     * riparato vanno chiusi con lui, e per ritrovarli serve il tipo, non la sorgente
     * (la sorgente di queste righe è la stazione, non il treno che le ha causate).
     */
    public List<Guasto> guastiDerivatiDaConvoglio() {
        return guastiDove(" WHERE tipo = 'convoglio_guasto_in_stazione' AND Stato_RisoltoONO = FALSE");
    }

    /** Inserisce un guasto nuovo. */
    public void salvaGuasto(Guasto guasto) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Guasti_Pervenuti_da_treni_o_Staz (" + COLONNE_GUASTO + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, guasto.id);
                ps.setBoolean(2, guasto.risolto);
                ps.setString(3, guasto.operatore != null ? guasto.operatore.id : null);
                ps.setString(4, guasto.tipo);
                ps.setString(5, guasto.severita);
                ps.setString(6, guasto.sorgenteTipo);
                ps.setString(7, guasto.sorgenteId);
                ps.setString(8, guasto.origine);
                ps.setString(9, guasto.messaggio);
                ps.setTimestamp(10, timestamp(guasto.timestamp));
                ps.setTimestamp(11, timestamp(guasto.timestampRisoluzione));
                return ps.executeUpdate();
            }
        });
    }

    /** Riscrive il testo di un guasto già aperto (il campo lo ha segnalato di nuovo). */
    public void aggiornaMessaggioGuasto(String guastoId, String messaggio) {
        esegui("UPDATE Guasti_Pervenuti_da_treni_o_Staz SET messaggio = ? WHERE id_Guasto = ?", messaggio, guastoId);
    }

    /** Riscrive istante di apertura e severità (servono ai guasti vecchi che non li avevano). */
    public void completaGuasto(Guasto guasto) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE Guasti_Pervenuti_da_treni_o_Staz SET ts_apertura = ?, severita = ? WHERE id_Guasto = ?")) {
                ps.setTimestamp(1, timestamp(guasto.timestamp));
                ps.setString(2, guasto.severita);
                ps.setString(3, guasto.id);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Scrive la chiusura di un guasto: la riga viva diventa risolta con l'istante dato e la
     * riga di storico prende l'istante di chiusura. Se il guasto non aveva una riga di storico
     * (quelli nati prima degli storici) la si scrive adesso, già chiusa.
     *
     * <p>L'oggetto che si passa non viene toccato: a segnarlo risolto è AutomaGuasto, dopo il
     * commit.</p>
     */
    public void chiudiGuasto(Guasto guasto, Instant quando) {
        esegui("UPDATE Guasti_Pervenuti_da_treni_o_Staz SET Stato_RisoltoONO = TRUE, ts_risoluzione = ? WHERE id_Guasto = ?",
                timestamp(quando), guasto.id);
        StoricoGuasto storico = trovaStoricoGuasto(guasto.id);
        if (storico != null) {
            esegui("UPDATE Storico_Guasti SET Stato_RisoltoONO = TRUE, ts_chiusura = ? WHERE id_storico_guasto = ?",
                    timestamp(quando), storico.id);
        } else {
            StoricoGuasto chiuso = StoricoGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto));
            chiuso.risolto = true;
            chiuso.tsChiusura = quando;
            inserisciStoricoGuasto(chiuso);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STORICI
    // ──────────────────────────────────────────────────────────────

    /** La riga di storico di un guasto, o null se quel guasto non ne ha una. */
    public StoricoGuasto trovaStoricoGuasto(String guastoId) {
        return db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_storico_guasto, Stato_RisoltoONO, ts_apertura, ts_chiusura FROM Storico_Guasti WHERE id_Guasto = ?")) {
                ps.setString(1, guastoId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    StoricoGuasto storico = new StoricoGuasto();
                    storico.id = rs.getLong("id_storico_guasto");
                    storico.guastoId = guastoId;
                    storico.risolto = rs.getBoolean("Stato_RisoltoONO");
                    storico.tsApertura = istante(rs, "ts_apertura");
                    storico.tsChiusura = istante(rs, "ts_chiusura");
                    return storico;
                }
            }
        });
    }

    /** Scrive la riga di Storico_Guasti copiando i dati dal guasto. */
    public void salvaStoricoGuasto(Guasto guasto) {
        inserisciStoricoGuasto(StoricoGuasto.fotografiaDi(guasto, nomeDellaSorgente(guasto)));
    }

    private void inserisciStoricoGuasto(StoricoGuasto storico) {
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Storico_Guasti (id_Guasto, tipo, severita, sorgenteTipo, sorgenteId, nome_sorgente, messaggio, "
                            + "Stato_RisoltoONO, id_operatore, nome_operatore, matricola_operatore, ts_apertura, ts_chiusura, ts_storicizzazione) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, storico.guastoId);
                ps.setString(2, storico.tipo);
                ps.setString(3, storico.severita);
                ps.setString(4, storico.sorgenteTipo);
                ps.setString(5, storico.sorgenteId);
                ps.setString(6, storico.nomeSorgente);
                ps.setString(7, storico.messaggio);
                ps.setBoolean(8, storico.risolto);
                ps.setString(9, storico.operatoreId);
                ps.setString(10, storico.nomeOperatore);
                ps.setString(11, storico.matricolaOperatore);
                ps.setTimestamp(12, timestamp(storico.tsApertura));
                ps.setTimestamp(13, timestamp(storico.tsChiusura));
                ps.setTimestamp(14, timestamp(storico.tsStoricizzazione));
                return ps.executeUpdate();
            }
        });
    }

    public void salvaStoricoStatoTreno(Treno treno, String statoPrecedente) {
        StoricoStatoTreno storico = StoricoStatoTreno.fotografiaDi(treno, statoPrecedente);
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Storico_Stato_Treni (id_convoglio, stato, stato_precedente, itinerario, "
                            + "PosizioneAttualeTrattaOStazione, descrizione_posizione, ts_storicizzazione) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, storico.trenoId);
                ps.setString(2, storico.stato);
                ps.setString(3, storico.statoPrecedente);
                ps.setString(4, storico.itinerarioId);
                ps.setString(5, storico.posizioneId);
                ps.setString(6, storico.descrizionePosizione);
                ps.setTimestamp(7, timestamp(storico.tsStoricizzazione));
                return ps.executeUpdate();
            }
        });
    }

    public void salvaStoricoStatoStazione(Stazione stazione, String stato, String statoPrecedente) {
        StoricoStatoStazione storico = StoricoStatoStazione.fotografiaDi(stazione, stato, statoPrecedente);
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Storico_Stato_Stazioni (id_stazione, nome, tipoCapolineaPartenzaoNormale, stato, "
                            + "stato_precedente, funzionanteONo, ts_storicizzazione) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, storico.stazioneId);
                ps.setString(2, storico.nome);
                ps.setString(3, storico.tipo);
                ps.setString(4, storico.stato);
                ps.setString(5, storico.statoPrecedente);
                ps.setBoolean(6, storico.funzionanteONo);
                ps.setTimestamp(7, timestamp(storico.tsStoricizzazione));
                return ps.executeUpdate();
            }
        });
    }

    /**
     * L'ultimo stato storicizzato di una stazione, o null se non ne ha mai avuto uno.
     *
     * <p>Serve all'avvio della Centrale: lo stato runtime non e' persistito, e questa riga
     * e' l'unica traccia che resta di un intervento di manutenzione aperto prima dello
     * spegnimento. L'ordinamento e' su timestamp e, a parita', su chiave: due cambi nello
     * stesso istante non sono impossibili (l'invio della squadra scrive subito dopo il
     * guasto) e senza il secondo criterio l'ordine fra i due sarebbe arbitrario.</p>
     */
    public StoricoStatoStazione ultimoStatoStoricizzato(String stazioneId) {
        return db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_storico_stazione, id_stazione, nome, tipoCapolineaPartenzaoNormale, stato, stato_precedente, "
                            + "funzionanteONo, ts_storicizzazione FROM Storico_Stato_Stazioni WHERE id_stazione = ? "
                            + "ORDER BY ts_storicizzazione DESC, id_storico_stazione DESC LIMIT 1")) {
                ps.setString(1, stazioneId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    StoricoStatoStazione storico = new StoricoStatoStazione();
                    storico.id = rs.getLong("id_storico_stazione");
                    storico.stazioneId = rs.getString("id_stazione");
                    storico.nome = rs.getString("nome");
                    storico.tipo = rs.getString("tipoCapolineaPartenzaoNormale");
                    storico.stato = rs.getString("stato");
                    storico.statoPrecedente = rs.getString("stato_precedente");
                    storico.funzionanteONo = (Boolean) rs.getObject("funzionanteONo");
                    storico.tsStoricizzazione = istante(rs, "ts_storicizzazione");
                    return storico;
                }
            }
        });
    }

    /**
     * Riga di audit sulla tabella eventi_stazioni. È complementare a Storico_Stato_Stazioni:
     * quello registra il cambio di stato, questa registra il fatto che l'ha provocato.
     *
     * <p>Il nome della stazione lo passa il chiamante e non lo si rilegge dall'anagrafica:
     * chi scrive questi eventi (il gestore degli eventi, per il watchdog e per i guasti) ha
     * in mano la copia in cache, che è quella coerente con l'istante dell'evento. La tabella
     * non ha una chiave esterna verso la stazione (RF02.7), quindi è l'unico posto dove quel
     * nome resta scritto.</p>
     */
    public void salvaEventoStazione(String stazioneId, String nomeStazione, String stato,
                                    String tipoEvento, String descrizione) {
        EventoStazione evento = new EventoStazione();
        evento.stazioneId = stazioneId;
        evento.nomeStazione = nomeStazione;
        evento.stato = stato;
        evento.tipoEvento = tipoEvento;
        evento.descrizione = descrizione;
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eventi_stazioni (id, stazioneid, nomestazione, stato, tipoevento, descrizione, \"timestamp\") "
                            + "VALUES (nextval('eventi_stazioni_seq'), ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, evento.stazioneId);
                ps.setString(2, evento.nomeStazione);
                ps.setString(3, evento.stato);
                ps.setString(4, evento.tipoEvento);
                ps.setString(5, evento.descrizione);
                ps.setTimestamp(6, evento.timestamp != null ? Timestamp.valueOf(evento.timestamp) : null);
                return ps.executeUpdate();
            }
        });
    }

    public void salvaStoricoTransito(Transito transito) {
        StoricoTransito storico = StoricoTransito.fotografiaDi(transito);
        db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Storico_Transiti (id_transito, id_stazione, nome_stazione, id_convoglio, id_Tratta, "
                            + "descrizione_tratta, tempoEntrata, tempoUscita, ritardoMinuti, ts_storicizzazione) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, storico.idTransito);
                ps.setString(2, storico.stazioneId);
                ps.setString(3, storico.nomeStazione);
                ps.setString(4, storico.trenoId);
                ps.setString(5, storico.trattaId);
                ps.setString(6, storico.descrizioneTratta);
                ps.setTimestamp(7, timestamp(storico.tempoEntrata));
                ps.setTimestamp(8, timestamp(storico.tempoUscita));
                setInteger(ps, 9, storico.ritardoMinuti);
                ps.setTimestamp(10, timestamp(storico.tsStoricizzazione));
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Nome della sorgente da congelare nello storico del guasto. Per una stazione va letto
     * dall'anagrafica (l'id da solo, tipo "S3", non dice niente a chi rilegge lo storico fra
     * un mese); per un convoglio l'identificativo è già il nome. La lettura sta qui e non
     * in StoricoGuasto perché le entità non fanno query.
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
        return db.conConnessione(c -> leggiUtente(c, "upper(matricola) = ?", matricola.toUpperCase()));
    }

    // ──────────────────────────────────────────────────────────────
    // LETTURE DELLE RIGHE (con la connessione già aperta)
    // ──────────────────────────────────────────────────────────────

    private Stazione leggiStazione(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLONNE_STAZIONE + " FROM Stazione WHERE id_stazione = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? stazioneDa(rs) : null;
            }
        }
    }

    /**
     * Stazione letta dalla riga. Lo stato non è una colonna: resta quello di partenza della
     * classe (OFFLINE), e quello vero lo tiene la cache.
     */
    private Stazione stazioneDa(ResultSet rs) throws SQLException {
        Stazione stazione = new Stazione();
        stazione.id = rs.getString("id_stazione");
        stazione.nome = rs.getString("nome");
        stazione.tipoCapolineaPartenzaoNormale = rs.getString("tipoCapolineaPartenzaoNormale");
        stazione.latitudine = decimale(rs, "latitudine");
        stazione.longitudine = decimale(rs, "longitudine");
        stazione.binari = intero(rs, "binari");
        return stazione;
    }

    private Itinerario leggiItinerario(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id_itinerario, capolinea FROM Itinerari WHERE id_itinerario = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? itinerarioDa(rs) : null;
            }
        }
    }

    private Itinerario itinerarioDa(ResultSet rs) throws SQLException {
        Itinerario itinerario = new Itinerario();
        itinerario.id = rs.getString("id_itinerario");
        itinerario.capolineaId = rs.getString("capolinea");
        return itinerario;
    }

    /** Tratta con le sue due stazioni già lette (servono alla descrizione e al verso di marcia). */
    private Tratta leggiTratta(Connection c, String id) throws SQLException {
        Tratta tratta = null;
        String partenza = null;
        String arrivo = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id_Tratta, StazionePartenzaFK, StazioneArrivoFK, tempoPercorrenzaMinuti FROM Tratte WHERE id_Tratta = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tratta = new Tratta();
                    tratta.id = rs.getString("id_Tratta");
                    partenza = rs.getString("StazionePartenzaFK");
                    arrivo = rs.getString("StazioneArrivoFK");
                    tratta.tempoPercorrenzaMinuti = intero(rs, "tempoPercorrenzaMinuti");
                }
            }
        }
        if (tratta != null) {
            tratta.stazionePartenza = leggiStazione(c, partenza);
            tratta.stazioneArrivo = leggiStazione(c, arrivo);
        }
        return tratta;
    }

    private List<Treno> treniDove(String filtro, String parametro) {
        return db.conConnessione(c -> {
            List<String[]> righe = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_convoglio, stato, itinerario, PosizioneAttualeTrattaOStazione FROM Treni" + filtro)) {
                if (parametro != null) {
                    ps.setString(1, parametro);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        righe.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                    }
                }
            }
            List<Treno> treni = new ArrayList<>();
            for (String[] riga : righe) {
                treni.add(trenoDa(c, riga[0], riga[1], riga[2], riga[3]));
            }
            return treni;
        });
    }

    private Treno trenoDa(Connection c, ResultSet rs) throws SQLException {
        String id = rs.getString("id_convoglio");
        String stato = rs.getString("stato");
        String itinerario = rs.getString("itinerario");
        String posizione = rs.getString("PosizioneAttualeTrattaOStazione");
        return trenoDa(c, id, stato, itinerario, posizione);
    }

    private Treno trenoDa(Connection c, String id, String stato, String idItinerario, String idPosizione) throws SQLException {
        Treno treno = new Treno();
        treno.id = id;
        treno.stato = stato;
        treno.itinerario = idItinerario != null ? leggiItinerario(c, idItinerario) : null;
        treno.posizioneAttualeTratta = idPosizione != null ? leggiTratta(c, idPosizione) : null;
        return treno;
    }

    /** Solo id e stato: al transito serve sapere di quale convoglio si tratta, non dove va. */
    private Treno leggiTrenoSenzaRelazioni(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id_convoglio, stato FROM Treni WHERE id_convoglio = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Treno treno = new Treno();
                treno.id = rs.getString("id_convoglio");
                treno.stato = rs.getString("stato");
                return treno;
            }
        }
    }

    private List<Guasto> guastiDove(String filtro, String... parametri) {
        return db.conConnessione(c -> {
            List<Guasto> guasti = new ArrayList<>();
            List<String> operatori = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLONNE_GUASTO + " FROM Guasti_Pervenuti_da_treni_o_Staz" + filtro)) {
                for (int i = 0; i < parametri.length; i++) {
                    ps.setString(i + 1, parametri[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Guasto guasto = new Guasto();
                        guasto.id = rs.getString("id_Guasto");
                        guasto.risolto = rs.getBoolean("Stato_RisoltoONO");
                        guasto.tipo = rs.getString("tipo");
                        guasto.severita = rs.getString("severita");
                        guasto.sorgenteTipo = rs.getString("sorgenteTipo");
                        guasto.sorgenteId = rs.getString("sorgenteId");
                        guasto.origine = rs.getString("origine");
                        guasto.messaggio = rs.getString("messaggio");
                        guasto.timestamp = istante(rs, "ts_apertura");
                        guasto.timestampRisoluzione = istante(rs, "ts_risoluzione");
                        guasti.add(guasto);
                        operatori.add(rs.getString("OperatoreCheSeNeStaOccupandoFK"));
                    }
                }
            }
            for (int i = 0; i < guasti.size(); i++) {
                if (operatori.get(i) != null) {
                    guasti.get(i).operatore = leggiUtente(c, "id_utente = ?", operatori.get(i));
                }
            }
            return guasti;
        });
    }

    private Utente leggiUtente(Connection c, String filtro, String valore) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id_utente, tipo, nome, cognome, matricola FROM Utenti WHERE " + filtro)) {
            ps.setString(1, valore);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Utente utente = new Utente();
                utente.id = rs.getString("id_utente");
                utente.tipo = rs.getString("tipo");
                utente.nome = rs.getString("nome");
                utente.cognome = rs.getString("cognome");
                utente.matricola = rs.getString("matricola");
                return utente;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SUPPORTO JDBC
    // ──────────────────────────────────────────────────────────────

    /** Esegue un INSERT, UPDATE o DELETE con parametri e dice quante righe ha toccato. */
    private int esegui(String sql, Object... parametri) {
        return db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < parametri.length; i++) {
                    ps.setObject(i + 1, parametri[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    /** Esegue un SELECT COUNT(*) con parametri testuali. */
    private long conta(String sql, String... parametri) {
        return db.conConnessione(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < parametri.length; i++) {
                    ps.setString(i + 1, parametri[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static Timestamp timestamp(Instant istante) {
        return istante != null ? Timestamp.from(istante) : null;
    }

    private static Instant istante(ResultSet rs, String colonna) throws SQLException {
        Timestamp valore = rs.getTimestamp(colonna);
        return valore != null ? valore.toInstant() : null;
    }

    private static Integer intero(ResultSet rs, String colonna) throws SQLException {
        int valore = rs.getInt(colonna);
        return rs.wasNull() ? null : valore;
    }

    private static Double decimale(ResultSet rs, String colonna) throws SQLException {
        double valore = rs.getDouble(colonna);
        return rs.wasNull() ? null : valore;
    }

    private static void setInteger(PreparedStatement ps, int indice, Integer valore) throws SQLException {
        if (valore != null) {
            ps.setInt(indice, valore);
        } else {
            ps.setNull(indice, Types.INTEGER);
        }
    }

    private static void setDouble(PreparedStatement ps, int indice, Double valore) throws SQLException {
        if (valore != null) {
            ps.setDouble(indice, valore);
        } else {
            ps.setNull(indice, Types.DOUBLE);
        }
    }
}
