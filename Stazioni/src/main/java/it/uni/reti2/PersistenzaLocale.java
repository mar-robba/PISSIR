package it.uni.reti2;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistenza locale del nodo Stazione su un file SQLite.
 *
 * <p>Prima lo stato della stazione viveva solo dentro {@link DBLocale} e {@link LocalBuffer},
 * cioè in RAM: se il processo moriva (il "fault energetico" del nodo di campo) tutto spariva
 * e la stazione ripartiva ONLINE, senza i treni che aveva sui binari e soprattutto senza gli
 * eventi che non era ancora riuscita a spedire alla Centrale. Qui invece ogni cambiamento di
 * stato viene scritto anche su disco (write-through), così al riavvio il nodo si ricostruisce
 * da solo la propria situazione senza dover chiedere niente a nessuno.</p>
 *
 * <p>Il db è un file SQLite, non un server: è la scelta giusta per un nodo edge, perché non
 * aggiunge un processo da tenere vivo sul campo e il "database" è un singolo file che si può
 * copiare via. Lo schema è quello di {@code src/main/resources/schema.sql}, eseguito a ogni
 * avvio (tutte le CREATE sono IF NOT EXISTS, quindi la prima volta crea e le altre no).</p>
 *
 * <p>Regola di fondo: <b>la persistenza non deve mai poter fermare la stazione</b>. Se il file
 * non è apribile (permessi, disco pieno, cartella in sola lettura) si logga l'errore, si mette
 * {@link #attiva} a false e il nodo continua a lavorare in memoria esattamente come faceva
 * prima. Vale anche per le singole query: una scrittura fallita è un warning, non un'eccezione
 * che risale fino al listener MQTT.</p>
 */
@ApplicationScoped
public class PersistenzaLocale {

    private static final Logger LOG = Logger.getLogger(PersistenzaLocale.class);

    /**
     * Percorso del file SQLite. Nell'application.properties contiene l'id della stazione,
     * perché sulla stessa macchina girano più nodi e ognuno deve avere il suo file.
     */
    @ConfigProperty(name = "stazione.db.path", defaultValue = "data/stazione.db")
    String percorsoDb;

    /**
     * Interruttore per spegnere del tutto la persistenza (test, o demo in cui si vuole
     * far ripartire la stazione "pulita" senza andare a cancellare il file a mano).
     */
    @ConfigProperty(name = "stazione.db.abilitata", defaultValue = "true")
    boolean abilitata;

    /**
     * L'unica connessione JDBC del nodo. SQLite scrive su un file solo e il traffico di
     * una stazione è irrisorio, quindi non serve un pool: basta una connessione sola con
     * tutti i metodi synchronized, visto che a toccarla sono thread diversi (i listener
     * MQTT, il job schedulato di manutenzione e le richieste REST).
     */
    private Connection connessione;

    /** false se il db non è disponibile: in quel caso ogni metodo diventa un no-op. */
    private volatile boolean attiva = false;

    // ================================================================
    // CICLO DI VITA
    // ================================================================

    /**
     * Apre (creandolo se non c'è) il file SQLite ed esegue lo schema.
     * È un @PostConstruct e non un osservatore di StartupEvent perché DBLocale e
     * LocalBuffer si ripristinano dentro il loro @PostConstruct: il proxy CDI costruisce
     * questo bean alla prima chiamata, quindi il db è già pronto quando serve.
     */
    @PostConstruct
    void apri() {
        if (!abilitata) {
            LOG.warn("💾 Persistenza locale disabilitata da configurazione: la stazione lavora solo in RAM.");
            return;
        }
        try {
            Path file = Path.of(percorsoDb).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            connessione = DriverManager.getConnection("jdbc:sqlite:" + file);
            impostaPragma();
            creaSchema();

            attiva = true;
            LOG.infof("💾 Persistenza locale attiva su %s", file);
        } catch (SQLException | IOException e) {
            // Volutamente non si rilancia: senza db la stazione funziona lo stesso,
            // perde solo la capacità di sopravvivere a un riavvio.
            LOG.errorf(e, "💾 Impossibile aprire il database locale '%s': si prosegue solo in memoria.", percorsoDb);
            attiva = false;
        }
    }

    /**
     * Parametri di durabilità del file. Sono la parte che conta davvero, visto che tutto
     * il senso di questa classe è resistere a una mancanza di corrente:
     * - WAL: le scritture vanno prima su un giornale a parte, che rende ogni commit atomico
     *   e permette di leggere mentre si scrive;
     * - synchronous=FULL: si aspetta la fsync del disco a ogni commit. È l'impostazione
     *   lenta, ma con NORMAL le ultime transazioni possono sparire proprio nel caso che qui
     *   interessa, cioè la corrente che va via;
     * - busy_timeout: se il file è momentaneamente occupato si riprova per 5 secondi invece
     *   di tornare subito un errore "database is locked".
     * Le foreign key restano disattivate di proposito, il motivo è scritto in schema.sql.
     */
    private void impostaPragma() throws SQLException {
        try (Statement st = connessione.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    /**
     * Legge schema.sql dal classpath e lo esegue istruzione per istruzione.
     * Si tolgono prima i commenti "--" (se no il punto e virgola dentro un commento
     * spezzerebbe l'istruzione sbagliata) e poi si divide sul punto e virgola.
     */
    private void creaSchema() throws SQLException {
        String ddl;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("schema.sql")) {
            if (in == null) {
                LOG.warn("💾 schema.sql non trovato nel classpath: il db locale resta senza tabelle.");
                return;
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Lettura di schema.sql fallita", e);
        }

        StringBuilder pulito = new StringBuilder();
        for (String riga : ddl.split("\n")) {
            int commento = riga.indexOf("--");
            pulito.append(commento >= 0 ? riga.substring(0, commento) : riga).append('\n');
        }

        try (Statement st = connessione.createStatement()) {
            for (String istruzione : pulito.toString().split(";")) {
                if (!istruzione.isBlank()) {
                    st.execute(istruzione);
                }
            }
        }
    }

    /** Chiude la connessione allo spegnimento del nodo (fa anche il checkpoint del WAL). */
    @PreDestroy
    void chiudi() {
        if (connessione == null) {
            return;
        }
        try {
            connessione.close();
            LOG.info("💾 Database locale chiuso correttamente.");
        } catch (SQLException e) {
            LOG.warnf("💾 Chiusura del database locale non riuscita: %s", e.getMessage());
        } finally {
            attiva = false;
        }
    }

    /** @return true se il db locale è stato aperto e le scritture stanno andando su disco. */
    public boolean isAttiva() {
        return attiva;
    }

    /** @return il percorso del file SQLite, utile da mostrare negli endpoint diagnostici. */
    public String getPercorsoDb() {
        return percorsoDb;
    }

    // ================================================================
    // TABELLA Stato
    // ================================================================

    /**
     * Fotografia dello stato letta da disco al riavvio.
     *
     * @param stato              "ONLINE" oppure "GUASTA", ricavato da funzionanteONo.
     * @param inizioUltimoFault  quando è iniziato l'ultimo guasto (null se non ce n'è mai stato).
     * @param fineUltimoFault    quando è stato risolto (null se è ancora in corso).
     */
    public record StatoPersistito(String stato, Instant inizioUltimoFault, Instant fineUltimoFault) {}

    /**
     * Scrive (o aggiorna) l'unica riga della tabella Stato. Il modello non ha una colonna
     * "stato" testuale ma due booleani, quindi ONLINE/GUASTA viene tradotto in
     * funzionanteONo + flagFaultInCorso, che sono la stessa informazione.
     *
     * @param stazioneId   id del nodo, chiave primaria della riga.
     * @param stato        "ONLINE" oppure "GUASTA".
     * @param inizioFault  istante di inizio dell'ultimo guasto, può essere null.
     * @param fineFault    istante di fine dell'ultimo guasto, può essere null.
     */
    public synchronized void salvaStato(String stazioneId, String stato, Instant inizioFault, Instant fineFault) {
        if (!attiva) {
            return;
        }
        boolean guasta = "GUASTA".equals(stato);
        String sql = "INSERT INTO Stato(id_stazione, nomeStazione, regioneGeograficaDiAppartenenza, "
                + "funzionanteONo, flagFaultInCorso, timestampInizioUltimoFault, timestampFineUltimoFault) "
                + "VALUES(?, ?, NULL, ?, ?, ?, ?) "
                + "ON CONFLICT(id_stazione) DO UPDATE SET "
                + "funzionanteONo = excluded.funzionanteONo, "
                + "flagFaultInCorso = excluded.flagFaultInCorso, "
                + "timestampInizioUltimoFault = excluded.timestampInizioUltimoFault, "
                + "timestampFineUltimoFault = excluded.timestampFineUltimoFault";

        try (PreparedStatement ps = connessione.prepareStatement(sql)) {
            ps.setString(1, stazioneId);
            ps.setString(2, stazioneId);
            ps.setInt(3, guasta ? 0 : 1);
            ps.setInt(4, guasta ? 1 : 0);
            ps.setString(5, testo(inizioFault));
            ps.setString(6, testo(fineFault));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("💾 Salvataggio dello stato non riuscito: %s", e.getMessage());
        }
    }

    /**
     * Rilegge lo stato del nodo dal disco.
     *
     * @param stazioneId id del nodo.
     * @return la riga trovata, oppure null se è il primo avvio (o se il db non è attivo).
     */
    public synchronized StatoPersistito leggiStato(String stazioneId) {
        if (!attiva) {
            return null;
        }
        String sql = "SELECT funzionanteONo, timestampInizioUltimoFault, timestampFineUltimoFault "
                + "FROM Stato WHERE id_stazione = ?";

        try (PreparedStatement ps = connessione.prepareStatement(sql)) {
            ps.setString(1, stazioneId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String stato = rs.getInt("funzionanteONo") == 1 ? "ONLINE" : "GUASTA";
                return new StatoPersistito(stato,
                        istante(rs.getString("timestampInizioUltimoFault")),
                        istante(rs.getString("timestampFineUltimoFault")));
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Lettura dello stato non riuscita: %s", e.getMessage());
            return null;
        }
    }

    // ================================================================
    // TABELLA Treno
    // ================================================================

    /**
     * Registra l'ENTRATA di un convoglio. La chiave è il treno, quindi un nuovo ingresso
     * sovrascrive il transito precedente: la tabella tiene l'ultimo passaggio di ognuno,
     * non tutta la storia (quella la storicizza la Centrale).
     * id_transito è un contatore locale progressivo, serve solo a numerare i transiti visti.
     *
     * @param trenoId identificativo del convoglio.
     * @param quando  istante dell'ingresso.
     */
    public synchronized void registraEntrataTreno(String trenoId, Instant quando) {
        if (!attiva) {
            return;
        }
        String sql = "INSERT INTO Treno(id_Treno, id_transito, tempoEntrata, tempoPartenza) "
                + "VALUES(?, (SELECT COALESCE(MAX(id_transito), 0) + 1 FROM Treno), ?, NULL) "
                + "ON CONFLICT(id_Treno) DO UPDATE SET "
                + "id_transito = excluded.id_transito, "
                + "tempoEntrata = excluded.tempoEntrata, "
                + "tempoPartenza = NULL";

        try (PreparedStatement ps = connessione.prepareStatement(sql)) {
            ps.setString(1, trenoId);
            ps.setString(2, testo(quando));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("💾 Salvataggio dell'entrata del treno %s non riuscito: %s", trenoId, e.getMessage());
        }
    }

    /**
     * Registra l'USCITA di un convoglio: la riga resta ma prende il tempo di partenza,
     * ed è proprio questo a farla sparire dai "treni presenti" al prossimo riavvio.
     *
     * @param trenoId identificativo del convoglio.
     * @param quando  istante dell'uscita.
     */
    public synchronized void registraUscitaTreno(String trenoId, Instant quando) {
        if (!attiva) {
            return;
        }
        try (PreparedStatement ps = connessione.prepareStatement(
                "UPDATE Treno SET tempoPartenza = ? WHERE id_Treno = ?")) {
            ps.setString(1, testo(quando));
            ps.setString(2, trenoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("💾 Salvataggio dell'uscita del treno %s non riuscito: %s", trenoId, e.getMessage());
        }
    }

    /**
     * Rilegge i treni che al momento dello spegnimento erano ancora dentro la stazione:
     * sono quelli entrati e mai usciti, cioè con tempoPartenza a NULL.
     *
     * @return mappa trenoId -&gt; istante di entrata, in ordine di ingresso.
     */
    public synchronized Map<String, Instant> leggiTreniPresenti() {
        Map<String, Instant> presenti = new LinkedHashMap<>();
        if (!attiva) {
            return presenti;
        }
        String sql = "SELECT id_Treno, tempoEntrata FROM Treno "
                + "WHERE tempoPartenza IS NULL ORDER BY id_transito";

        try (PreparedStatement ps = connessione.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Instant entrata = istante(rs.getString("tempoEntrata"));
                presenti.put(rs.getString("id_Treno"), entrata != null ? entrata : Instant.now());
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Lettura dei treni presenti non riuscita: %s", e.getMessage());
        }
        return presenti;
    }

    // ================================================================
    // TABELLA Guasto_Locale_Momentaneo
    // ================================================================

    /**
     * Scrive un guasto segnalato da questo nodo, aperto (statoRisoltoONo = 0).
     * L'id_Guasto non viene passato: lo assegna SQLite ed è una numerazione locale,
     * che non ha niente a che vedere con quella degli allarmi della Centrale.
     *
     * @param tipo        classificazione del guasto ("sensore_offline", "stazione_guasta", ...).
     * @param descrizione testo leggibile del problema.
     * @param quando      istante della segnalazione.
     * @return l'id assegnato al guasto, oppure -1 se il db non è attivo o la scrittura fallisce.
     */
    public synchronized long registraGuasto(String tipo, String descrizione, Instant quando) {
        if (!attiva) {
            return -1;
        }
        String sql = "INSERT INTO Guasto_Locale_Momentaneo(statoRisoltoONo, tipo, descrizione, timestamp) "
                + "VALUES(0, ?, ?, ?)";

        try (PreparedStatement ps = connessione.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tipo);
            ps.setString(2, descrizione);
            ps.setString(3, testo(quando));
            ps.executeUpdate();
            try (ResultSet chiavi = ps.getGeneratedKeys()) {
                return chiavi.next() ? chiavi.getLong(1) : -1;
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Salvataggio del guasto locale non riuscito: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Chiude tutti i guasti ancora aperti. Viene chiamata quando la stazione torna ONLINE,
     * cioè quando la Centrale manda il RESOLVED: da quel momento nessun guasto locale è più
     * in corso, e il timestamp di chiusura è lo stesso che finisce in Stato.
     *
     * @param quando istante della risoluzione.
     */
    public synchronized void risolviGuastiAperti(Instant quando) {
        if (!attiva) {
            return;
        }
        try (PreparedStatement ps = connessione.prepareStatement(
                "UPDATE Guasto_Locale_Momentaneo SET statoRisoltoONo = 1, timestamp = ? WHERE statoRisoltoONo = 0")) {
            ps.setString(1, testo(quando));
            int chiusi = ps.executeUpdate();
            if (chiusi > 0) {
                LOG.infof("💾 Chiusi %d guasti locali ancora aperti.", chiusi);
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Chiusura dei guasti locali non riuscita: %s", e.getMessage());
        }
    }

    /**
     * Come {@link #contaGuastiAperti()} ma limitato a una sola classificazione.
     * Serve al riavvio del nodo: la stazione che riparte GUASTA deve capire se quel guasto
     * era colpa dei sensori (e quindi può rientrare da solo quando i keepalive tornano)
     * oppure di qualcos'altro, che invece lo chiude solo la Centrale.
     *
     * @param tipo classificazione da contare, per esempio "sensore_offline".
     * @return quanti guasti locali di quel tipo risultano ancora non risolti.
     */
    public synchronized int contaGuastiApertiDiTipo(String tipo) {
        if (!attiva) {
            return 0;
        }
        try (PreparedStatement ps = connessione.prepareStatement(
                "SELECT COUNT(*) FROM Guasto_Locale_Momentaneo WHERE statoRisoltoONo = 0 AND tipo = ?")) {
            ps.setString(1, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Conteggio dei guasti aperti di tipo %s non riuscito: %s", tipo, e.getMessage());
            return 0;
        }
    }

    /** @return quanti guasti locali risultano ancora non risolti. */
    public synchronized int contaGuastiAperti() {
        if (!attiva) {
            return 0;
        }
        try (PreparedStatement ps = connessione.prepareStatement(
                "SELECT COUNT(*) FROM Guasto_Locale_Momentaneo WHERE statoRisoltoONo = 0");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            LOG.warnf("💾 Conteggio dei guasti aperti non riuscito: %s", e.getMessage());
            return 0;
        }
    }

    // ================================================================
    // TABELLA bufferChache
    // ================================================================

    /**
     * Aggiunge (o rimette) un evento nella coda store and forward.
     * È un INSERT OR REPLACE perché la stessa riga può tornare indietro: se il reinvio
     * fallisce a metà flush l'evento viene rimesso in testa alla coda in RAM e qui deve
     * ritrovarsi identico, id compreso.
     *
     * @param evento l'evento da persistere, con il suo id già assegnato da LocalBuffer.
     */
    public synchronized void salvaEventoBuffer(LocalBuffer.EventoBufferizzato evento) {
        if (!attiva) {
            return;
        }
        String sql = "INSERT OR REPLACE INTO bufferChache(id_Evento, tipo, id_Guasto, id_Treno, payload, timestamp) "
                + "VALUES(?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connessione.prepareStatement(sql)) {
            ps.setLong(1, evento.idEvento());
            ps.setString(2, evento.canale());
            if (evento.idGuasto() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, evento.idGuasto());
            }
            ps.setString(4, evento.idTreno());
            ps.setString(5, evento.payload());
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("💾 Salvataggio dell'evento %d nel buffer non riuscito: %s", evento.idEvento(), e.getMessage());
        }
    }

    /**
     * Cancella un evento dalla coda: da chiamare SOLO quando l'invio verso la Centrale
     * è andato a buon fine, se no si perderebbe proprio l'evento che si voleva salvare.
     *
     * @param idEvento id della riga da togliere.
     */
    public synchronized void rimuoviEventoBuffer(long idEvento) {
        if (!attiva) {
            return;
        }
        try (PreparedStatement ps = connessione.prepareStatement("DELETE FROM bufferChache WHERE id_Evento = ?")) {
            ps.setLong(1, idEvento);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("💾 Rimozione dell'evento %d dal buffer non riuscita: %s", idEvento, e.getMessage());
        }
    }

    /**
     * Rilegge la coda degli eventi ancora da spedire, dal più vecchio al più recente.
     * L'ordine FIFO è quello degli id crescenti: è importante, perché una USCITA non deve
     * mai arrivare alla Centrale prima della ENTRATA dello stesso treno.
     *
     * @return la lista degli eventi pendenti trovati su disco.
     */
    public synchronized List<LocalBuffer.EventoBufferizzato> leggiBuffer() {
        List<LocalBuffer.EventoBufferizzato> eventi = new ArrayList<>();
        if (!attiva) {
            return eventi;
        }
        String sql = "SELECT id_Evento, tipo, id_Guasto, id_Treno, payload FROM bufferChache ORDER BY id_Evento";

        try (PreparedStatement ps = connessione.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // wasNull() parla dell'ULTIMA colonna letta, quindi va interrogata subito
                // dopo id_Guasto: chiesta piu' avanti risponderebbe sul payload.
                long numeroGuasto = rs.getLong("id_Guasto");
                Long idGuasto = rs.wasNull() ? null : numeroGuasto;
                eventi.add(new LocalBuffer.EventoBufferizzato(
                        rs.getLong("id_Evento"),
                        rs.getString("tipo"),
                        rs.getString("payload"),
                        idGuasto,
                        rs.getString("id_Treno")));
            }
        } catch (SQLException e) {
            LOG.warnf("💾 Lettura del buffer non riuscita: %s", e.getMessage());
        }
        return eventi;
    }

    // ================================================================
    // CONVERSIONI
    // ================================================================

    /** Da Instant a stringa ISO-8601 (null resta null: le colonne dei timestamp sono nullable). */
    private static String testo(Instant istante) {
        return istante == null ? null : istante.toString();
    }

    /** Da stringa ISO-8601 a Instant, tollerante: se la colonna è vuota o illeggibile torna null. */
    private static Instant istante(String testo) {
        if (testo == null || testo.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(testo);
        } catch (DateTimeParseException e) {
            LOG.warnf("💾 Timestamp '%s' non interpretabile, ignorato.", testo);
            return null;
        }
    }
}
