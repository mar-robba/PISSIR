package it.uni.reti2;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Sposta i messaggi di log dal terminale a un file dentro {@code logs/}, e viceversa.
 * In più, all'avvio, prepara i log sul terminale ({@link #preparaTerminale}). E' la gemella
 * della classe omonima lato Treno: stesso meccanismo, stesso formato delle righe, cambia solo
 * l'id che finisce nel nome del file.
 *
 * <p>Serve quando la stazione viene lanciata a mano: la console dei sensori
 * ({@link ConsoleSensori}) e i log finiscono sullo stesso terminale, e le righe di
 * heartbeat/MQTT coprono il menu mentre si sta scrivendo. Con questa classe il menu resta
 * a schermo (sono normali {@code System.out.println}, non passano dal logger) e tutto il
 * resto va a finire su un file uguale a quelli che genera {@code avvioStazioni.sh} quando
 * lancia le istanze in background con {@code > logs/instance-N-NOME.log}.</p>
 *
 * <p>Il trucco e' tutto sul logger radice: gli si tolgono gli handler che scrivono a
 * schermo (ce n'e' uno solo, il ConsoleHandler messo da preparaTerminale) e gli si
 * attacca un {@link FileHandler}. Si tengono da parte quelli tolti, cosi' scegliendo di
 * nuovo l'opzione dal menu si rimettono al loro posto e i log tornano sul terminale.</p>
 */
public final class LogSuFile {

    /** Stessa cartella usata da avvioStazioni.sh, relativa alla directory di lancio. */
    private static final Path CARTELLA_LOG = Path.of("logs");

    private static Handler[] handlerDelTerminale;
    private static FileHandler handlerSuFile;
    private static Path fileCorrente;

    /**
     * I logger a cui preparaTerminale cambia il livello. Stanno in un campo perché
     * java.util.logging tiene i logger con riferimenti deboli: un logger che nessuno
     * tiene in vita può essere buttato via e ricreato col livello di default.
     */
    private static Logger loggerDelProgetto;
    private static Logger loggerDiJetty;
    private static Logger loggerDiJavalin;

    private LogSuFile() {
        // classe di sole utilita': non si istanzia
    }

    /**
     * Prepara i log sul terminale all'avvio del processo: un solo ConsoleHandler, con lo
     * stesso formato delle righe che finiscono su file. Il ConsoleHandler di default del JDK
     * scriverebbe ogni messaggio su due righe, con la data in un formato diverso.
     *
     * <p>Si vede dal livello INFO in su. Con debug=true (property log.debug) si vedono anche
     * i messaggi FINE del progetto, cioè l'heartbeat di ogni 10 secondi e il payload grezzo
     * degli alert che passano su railway/alerts. Javalin e Jetty (il server su cui gira)
     * parlano solo se hanno un problema: la riga con l'indirizzo delle API la scrive gia'
     * ServerRest.</p>
     */
    public static synchronized void preparaTerminale(boolean debug) {
        Logger radice = Logger.getLogger("");
        for (Handler handler : radice.getHandlers()) {
            radice.removeHandler(handler);
        }
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(new FormatoRighe());
        radice.addHandler(console);
        radice.setLevel(Level.INFO);

        loggerDelProgetto = Logger.getLogger("it.uni.reti2");
        loggerDelProgetto.setLevel(debug ? Level.FINE : Level.INFO);
        loggerDiJetty = Logger.getLogger("org.eclipse.jetty");
        loggerDiJetty.setLevel(Level.WARNING);
        loggerDiJavalin = Logger.getLogger("io.javalin");
        loggerDiJavalin.setLevel(Level.WARNING);
    }

    /** @return true se in questo momento i log stanno andando su file invece che a schermo. */
    public static synchronized boolean attivo() {
        return handlerSuFile != null;
    }

    /** @return il file su cui si sta scrivendo, null se i log sono a schermo. */
    public static synchronized Path fileCorrente() {
        return fileCorrente;
    }

    /**
     * Dirotta i log su {@code logs/manuale-<stazioneId>.log} (in coda, se il file c'e' gia').
     *
     * @return il file su cui si sta scrivendo.
     * @throws IOException se la cartella o il file non si riescono ad aprire.
     */
    public static synchronized Path attiva(String stazioneId) throws IOException {
        if (handlerSuFile != null) {
            return fileCorrente;
        }

        Files.createDirectories(CARTELLA_LOG);
        Path file = CARTELLA_LOG.resolve("manuale-" + nomeSicuro(stazioneId) + ".log");

        // append=true: le sessioni successive si accodano invece di cancellare quella prima
        FileHandler nuovo = new FileHandler(file.toString(), true);
        nuovo.setLevel(Level.ALL);
        nuovo.setFormatter(new FormatoRighe());

        Logger radice = Logger.getLogger("");
        handlerDelTerminale = radice.getHandlers();
        for (Handler handler : handlerDelTerminale) {
            radice.removeHandler(handler);
        }
        radice.addHandler(nuovo);

        handlerSuFile = nuovo;
        fileCorrente = file;
        return file;
    }

    /** Rimette i log sul terminale e chiude il file. */
    public static synchronized void disattiva() {
        if (handlerSuFile == null) {
            return;
        }

        Logger radice = Logger.getLogger("");
        radice.removeHandler(handlerSuFile);
        handlerSuFile.close();

        if (handlerDelTerminale != null) {
            for (Handler handler : handlerDelTerminale) {
                radice.addHandler(handler);
            }
        }

        handlerSuFile = null;
        handlerDelTerminale = null;
        fileCorrente = null;
    }

    /** Toglie dall'id tutto quello che non puo' stare in un nome di file. */
    private static String nomeSicuro(String stazioneId) {
        if (stazioneId == null || stazioneId.isBlank()) {
            return "stazione";
        }
        return stazioneId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Il formato di una riga di log, uguale a schermo e su file (e uguale a quello del
     * Treno), cosi' il file prodotto da qui e quelli di avvioStazioni.sh si leggono allo
     * stesso modo, e allo stesso modo dei log di quando il nodo girava su Quarkus:
     * {@code 2026-08-24 17:54:44,715 INFO  [it.uni.reti2.main] (main) messaggio}.
     */
    private static final class FormatoRighe extends Formatter {

        private static final DateTimeFormatter ORARIO =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

        @Override
        public String format(LogRecord record) {
            StringBuilder riga = new StringBuilder();
            riga.append(ORARIO.format(LocalDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault())));
            riga.append(String.format(" %-5s ", nomeLivello(record.getLevel())));
            riga.append('[').append(record.getLoggerName()).append(']');
            // Il record non porta il nome del thread (solo l'id), ma l'handler scrive nello
            // stesso thread che ha emesso il log, quindi questo e' il nome giusto.
            riga.append(" (").append(Thread.currentThread().getName()).append(") ");
            riga.append(formatMessage(record));
            riga.append(System.lineSeparator());

            Throwable errore = record.getThrown();
            if (errore != null) {
                StringWriter buffer = new StringWriter();
                errore.printStackTrace(new PrintWriter(buffer));
                riga.append(buffer);
            }
            return riga.toString();
        }

        /**
         * I nomi corti dei livelli, quelli che si leggevano nei log di Quarkus: WARN e non
         * WARNING, ERROR e non SEVERE, DEBUG e non FINE.
         */
        private static String nomeLivello(Level livello) {
            if (livello.intValue() >= Level.SEVERE.intValue()) {
                return "ERROR";
            }
            if (livello.intValue() >= Level.WARNING.intValue()) {
                return "WARN";
            }
            if (livello.intValue() >= Level.INFO.intValue()) {
                return "INFO";
            }
            return "DEBUG";
        }
    }
}
