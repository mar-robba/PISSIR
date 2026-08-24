package it.uni.reti2;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Tiene pulito il log dalle righe che le librerie MQTT ripetono all'infinito.
 *
 * <p>Vert.x stampa un {@code Connection with host:porta established successfully} a ogni
 * connessione e SmallRye un {@code Connection closed} a ogni caduta. Sono utili la prima
 * volta, ma i canali MQTT di questo servizio sono parecchi e la riconnessione automatica
 * riprova ogni secondo: se il broker chiude le sessioni (tipico quando lo stesso nodo è
 * avviato due volte e i due processi si rubano il client-id a vicenda) diventano decine di
 * righe al minuto che coprono telemetria, alert e fermate, cioè le uniche che interessano.</p>
 *
 * <p>Il filtro si limita a quelle due righe. Il collegamento che si apre e quello che cade
 * sono notizie e si stampano subito; quello che si butta è la ripetizione, cioè gli altri
 * canali che danno la stessa notizia e il ballo che va avanti all'infinito. Il tetto è di
 * {@link #RIGHE_PER_FINESTRA} righe ogni {@link #FINESTRA_MS} millisecondi, e la prima riga
 * dopo il silenzio si porta dietro il conto di quelle nascoste, così il problema resta
 * visibile senza intasare niente. Tutto il resto (errori, avvisi e il dettaglio in DEBUG se
 * lo si va ad accendere) passa senza essere toccato.</p>
 *
 * <p>Il filtro viene attaccato ai due logger delle librerie e non a un handler, così vale
 * per qualunque destinazione dei log (terminale o file di avvioTreni.sh).</p>
 */
@ApplicationScoped
public class FiltroLogMqtt implements Filter {

    /** I logger delle due librerie che stampano lo stato della connessione. */
    private static final String[] LOGGER_DA_FILTRARE = {
            "io.vertx.mqtt.impl.MqttClientImpl",
            "io.smallrye.reactive.messaging.mqtt.session.impl.MqttClientSessionImpl"
    };

    /** Ampiezza della finestra entro cui si contano le righe concesse (30 secondi). */
    private static final long FINESTRA_MS = 30_000;

    /**
     * Quante righe si concedono in una finestra: due bastano per raccontare una caduta e
     * il ritorno, e chi cade e torna in continuazione lo dice comunque nel riepilogo.
     */
    private static final int RIGHE_PER_FINESTRA = 2;

    /** Ampiezza della finestra in uso: è un campo solo perché i test la accorciano. */
    private final long finestraMs;

    /** Stato dell'ultima riga fatta passare: null finché non ne è passata nessuna. */
    private Boolean connesso;

    /** Inizio della finestra corrente. */
    private long inizioFinestra;

    /** Righe già fatte passare nella finestra corrente. */
    private int righeFatte;

    /** Quanti cambi di stato sono stati nascosti da quando si è stampata l'ultima riga. */
    private int nascoste;

    /** Costruttore usato da CDI: finestra da mezzo minuto. */
    public FiltroLogMqtt() {
        this(FINESTRA_MS);
    }

    /**
     * Costruttore per i test, che non possono stare mezzo minuto ad aspettare la
     * finestra successiva.
     *
     * @param finestraMs Ampiezza della finestra in millisecondi.
     */
    FiltroLogMqtt(long finestraMs) {
        this.finestraMs = finestraMs;
    }

    /**
     * Aggancia il filtro ai logger delle librerie MQTT appena il servizio parte.
     *
     * @param ev Evento di startup di Quarkus.
     */
    void installa(@Observes StartupEvent ev) {
        for (String nome : LOGGER_DA_FILTRARE) {
            java.util.logging.Logger.getLogger(nome).setFilter(this);
        }
    }

    /**
     * Decide se una riga delle librerie MQTT vada stampata o buttata.
     * Le righe che non parlano di connessione passano sempre.
     *
     * @param record La riga di log candidata alla stampa.
     * @return true se la riga va stampata.
     */
    @Override
    public synchronized boolean isLoggable(LogRecord record) {
        // Chi accende il DEBUG su queste librerie vuole vedere tutto: non ci si mette in mezzo.
        if (record.getLevel().intValue() < Level.INFO.intValue()) {
            return true;
        }

        String messaggio = record.getMessage();
        if (messaggio == null) {
            return true;
        }
        boolean aperta = messaggio.contains("established successfully");
        boolean chiusa = messaggio.startsWith("Connection closed");
        if (!aperta && !chiusa) {
            return true;
        }

        // Ogni canale MQTT ha il suo client: la stessa notizia arriva ripetuta N volte,
        // la prima basta e avanza.
        if (connesso != null && aperta == connesso) {
            nascoste++;
            return false;
        }
        connesso = aperta;

        // Un cambio di stato è una notizia e si stampa subito, ma non più di due per
        // finestra: se la connessione balla di continuo il log resta leggibile lo stesso.
        long adesso = System.currentTimeMillis();
        if (adesso - inizioFinestra >= finestraMs) {
            inizioFinestra = adesso;
            righeFatte = 0;
        }
        if (righeFatte >= RIGHE_PER_FINESTRA) {
            nascoste++;
            return false;
        }
        righeFatte++;

        if (nascoste > 0) {
            // Connessione che va e viene: la riga si porta dietro il conto di quelle saltate.
            record.setMessage((aperta ? "📶 Connessione MQTT ristabilita" : "🔌 Connessione MQTT caduta")
                    + ", ma continua ad andare e venire: nascosti altri " + nascoste
                    + " cambi di stato. Di solito vuol dire che lo stesso nodo è stato avviato"
                    + " due volte e i due processi si rubano il client-id a vicenda.");
        } else {
            record.setMessage(aperta
                    ? "📶 Connessione MQTT col broker stabilita"
                    : "🔌 Connessione MQTT col broker caduta: riprovo a connettermi");
        }
        record.setLevel(aperta ? Level.INFO : Level.WARNING);
        nascoste = 0;
        return true;
    }
}
