package it.uni.reti2.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * La connessione JDBC al database della Centrale, e le transazioni.
 *
 * <p>Una connessione si apre con {@link DriverManager} quando serve e si chiude subito dopo:
 * è il modo del materiale del corso (JDBC puro, senza pool). Le operazioni sono di due tipi:</p>
 * <ul>
 *   <li><b>{@link #inTransazione}</b>: apre una connessione con l'autocommit spento, esegue il
 *       lavoro e fa commit, o rollback se il lavoro lancia un'eccezione. Tutte le chiamate al
 *       repository fatte DENTRO il lavoro usano quella stessa connessione, quindi finiscono
 *       nella stessa transazione. Una transazione aperta dentro un'altra è una transazione
 *       nuova e indipendente (la esterna resta sospesa finché la interna non ha finito): è lo
 *       stesso comportamento del {@code QuarkusTransaction.requiringNew()} di prima;</li>
 *   <li><b>le letture fuori da una transazione</b> (le GET della web app, per esempio): il
 *       repository apre una connessione in autocommit, legge e la chiude.</li>
 * </ul>
 *
 * <p>La connessione della transazione in corso sta in un {@link ThreadLocal}: ogni thread (il
 * client MQTT, i thread di Javalin, il watchdog) vede solo la propria. È il motivo per cui il
 * repository non riceve la connessione come parametro.</p>
 */
public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /** Un pezzo di lavoro sul database che restituisce un risultato. */
    public interface Lavoro<T> {
        T esegui(Connection connessione) throws SQLException;
    }

    /** Un pezzo di lavoro sul database che non restituisce niente. */
    public interface LavoroSenzaRisultato {
        void esegui() throws SQLException;
    }

    /** Indirizzo JDBC (jdbc:postgresql://localhost:5432/railway, o jdbc:h2:mem:... nei test). */
    public String url;
    public String utente;
    public String password;

    /** La connessione della transazione aperta da questo thread, se ce n'è una. */
    private final ThreadLocal<Connection> transazioneCorrente = new ThreadLocal<>();

    /**
     * Esegue il lavoro in una transazione nuova e restituisce il suo risultato.
     *
     * @throws RuntimeException Se il lavoro o il commit falliscono: la transazione è già stata
     *                          annullata, e chi chiama decide cosa fare (di solito niente
     *                          notifiche, perché sul database non è cambiato niente).
     */
    public <T> T inTransazione(Lavoro<T> lavoro) {
        Connection esterna = transazioneCorrente.get();
        try (Connection connessione = apri()) {
            connessione.setAutoCommit(false);
            transazioneCorrente.set(connessione);
            try {
                T risultato = lavoro.esegui(connessione);
                connessione.commit();
                return risultato;
            } catch (SQLException | RuntimeException e) {
                connessione.rollback();
                throw e instanceof RuntimeException r ? r : new RuntimeException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            if (esterna != null) {
                transazioneCorrente.set(esterna);
            } else {
                transazioneCorrente.remove();
            }
        }
    }

    /** Come {@link #inTransazione(Lavoro)}, per un lavoro che non restituisce niente. */
    public void inTransazione(LavoroSenzaRisultato lavoro) {
        inTransazione(connessione -> {
            lavoro.esegui();
            return null;
        });
    }

    /**
     * Esegue una lettura o una scrittura del repository: dentro la transazione del thread se
     * ce n'è una, altrimenti su una connessione aperta apposta e in autocommit.
     */
    <T> T conConnessione(Lavoro<T> lavoro) {
        Connection inCorso = transazioneCorrente.get();
        try {
            if (inCorso != null) {
                return lavoro.esegui(inCorso);
            }
            try (Connection connessione = apri()) {
                return lavoro.esegui(connessione);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Crea le tabelle che mancano, eseguendo lo script schema.sql del classpath.
     *
     * <p>Prima le creava Hibernate al primo avvio ({@code database.generation=update}); adesso
     * lo script ha solo {@code CREATE ... IF NOT EXISTS}, quindi su un database già pieno non
     * tocca niente e su uno vuoto prepara tutte le tabelle. Le istruzioni sono separate dal
     * punto e virgola a fine riga; le righe di commento ({@code --}) si saltano.</p>
     */
    public void creaTabelleMancanti() {
        String script;
        try (InputStream input = Database.class.getClassLoader().getResourceAsStream("schema.sql")) {
            if (input == null) {
                LOG.warning("schema.sql non trovato nel classpath: nessuna tabella creata");
                return;
            }
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("schema.sql illeggibile: " + e.getMessage(), e);
        }

        StringBuilder istruzione = new StringBuilder();
        int eseguite = 0;
        try (Connection connessione = apri(); Statement statement = connessione.createStatement()) {
            for (String riga : script.split("\n")) {
                // il commento a fine riga va tolto: contiene apostrofi e punti e virgola
                int commento = riga.indexOf("--");
                String codice = (commento >= 0 ? riga.substring(0, commento) : riga).strip();
                if (codice.isEmpty()) {
                    continue;
                }
                istruzione.append(codice).append('\n');
                if (codice.endsWith(";")) {
                    statement.execute(istruzione.substring(0, istruzione.lastIndexOf(";")));
                    istruzione.setLength(0);
                    eseguite++;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Creazione delle tabelle non riuscita: " + e.getMessage(), e);
        }
        LOG.info("🗄️ Schema del database verificato (" + eseguite + " istruzioni di schema.sql)");
    }

    private Connection apri() throws SQLException {
        return DriverManager.getConnection(url, utente, password);
    }
}
