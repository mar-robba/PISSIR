package it.uni.reti2;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulatore dei sensori di binario della stazione.
 *
 * <p>I sensori veri sono dispositivi separati dal nodo Stazione, attaccati alla rotaia:
 * qui non ci sono, quindi il processo se li fa in casa. Il binario è uno solo, quindi i
 * dispositivi simulati sono due: quello di INGRESSO (vede il treno entrare) e quello di
 * USCITA (lo vede uscire).</p>
 *
 * <p>Il simulatore <b>non tocca il modello della stazione</b>: si comporta esattamente come
 * farebbe l'elettronica vera, cioè parla con la stazione via REST (è la scelta di progetto
 * per i sensori: sensori ↔ nodo in REST, nodo ↔ Centrale in MQTT). Manda il keepalive su
 * {@code POST /stazione/sensore/heartbeat} e segnala i transiti su
 * {@code POST /stazione/sensore/treno}. Le chiamate sono asincrone perché il rilevamento
 * arriva dal thread del connettore MQTT e non deve restare bloccato ad aspettare l'HTTP.</p>
 *
 * <p>Spegnere un sensore dalla CLI vuol dire simularne il <b>fail-stop</b>: smette di battere
 * e smette anche di rilevare i treni, perché è morto tutto il dispositivo. La stazione se ne
 * accorge da sola con {@link MonitorSensori}, che è il pezzo che sta dall'altra parte del filo
 * e non sa nulla di questa classe (deve funzionare identico con i sensori veri).</p>
 */
@ApplicationScoped
public class SimulatoreSensori {

    private static final Logger LOG = Logger.getLogger(SimulatoreSensori.class);

    /**
     * Id della stazione a cui i sensori appartengono: entra nel loro identificativo.
     *
     * <p>Si legge dalla configurazione e NON da {@code dbLocale.stazioneId}: quel campo
     * viene valorizzato in {@code main.run()}, cioè dopo che Quarkus è partito, mentre i
     * sensori si creano qui allo StartupEvent, che è prima. Leggendolo da lì i dispositivi
     * nascerebbero chiamati "null-BIN1-IN". La property invece è già a posto: l'id passato
     * come argomento viene messo in {@code System.setProperty("stazione.id", ...)} prima
     * ancora di far partire Quarkus.</p>
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    String stazioneId;

    /**
     * Porta su cui la stazione espone le proprie API: è lì che i sensori bussano.
     * Va letta dalla configurazione perché lo script di avvio ne assegna una diversa
     * a ogni istanza (QUARKUS_HTTP_PORT).
     */
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8081")
    int httpPort;

    /** Numero del binario sorvegliato: per ora ce n'è uno solo. */
    @ConfigProperty(name = "stazione.binario", defaultValue = "1")
    int binario;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * I dispositivi simulati, in ordine di creazione (prima l'ingresso, poi l'uscita):
     * la CLI li stampa in quest'ordine e il numeretto con cui si scelgono è la posizione.
     */
    private final Map<String, SensoreBinario> sensori = new LinkedHashMap<>();

    /**
     * Accende i due sensori del binario all'avvio del nodo.
     * L'id con cui si chiamano i dispositivi arriva dalla configurazione (vedi il campo
     * {@link #stazioneId}), non dallo stato del nodo: qui siamo ancora prima di
     * {@code main.run()}.
     */
    void avvio(@Observes StartupEvent evento) {
        creaSensore(SensoreBinario.Tipo.INGRESSO);
        creaSensore(SensoreBinario.Tipo.USCITA);
        LOG.infof("🛰️ Simulatore sensori avviato sul binario %d: %s", binario, sensori.keySet());
    }

    private void creaSensore(SensoreBinario.Tipo tipo) {
        String id = SensoreBinario.idPer(stazioneId, binario, tipo);
        sensori.put(id, new SensoreBinario(id, tipo, binario));
    }

    /**
     * Battito periodico di tutti i sensori accesi. Il periodo sta in configurazione
     * ({@code sensori.heartbeat.periodo}) e deve restare parecchio più corto del timeout
     * di {@link MonitorSensori}, se no la stazione dichiarerebbe guasto un sensore sano
     * solo perché non ha ancora avuto il tempo di battere.
     */
    @Scheduled(every = "{sensori.heartbeat.periodo}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void battito() {
        for (SensoreBinario sensore : sensori.values()) {
            if (!sensore.isAttivo()) {
                // Fail-stop simulato: il dispositivo è "morto", non manda niente.
                continue;
            }
            String payload = String.format("{\"sensoreId\":\"%s\",\"tipo\":\"%s\",\"binario\":%d}",
                    sensore.getId(), sensore.getTipo(), sensore.getBinario());
            sensore.segnaBattito(Instant.now());
            inviaAllaStazione("/stazione/sensore/heartbeat", payload);
        }
    }

    /**
     * Rilevamento del passaggio di un convoglio da parte del sensore competente.
     * Chi chiama è {@link StationGateway}, che sul topic MQTT dei treni ha "visto" passare
     * il convoglio: nel sistema vero quel passaggio lo vede la spira sul binario, quindi il
     * transito viene registrato solo se il sensore giusto è vivo.
     *
     * @param trenoId identificativo del convoglio.
     * @param tipo    "ENTRATA" oppure "USCITA".
     * @return true se il transito è stato rilevato e mandato alla stazione.
     */
    public boolean rilevaPassaggio(String trenoId, String tipo) {
        for (SensoreBinario sensore : sensori.values()) {
            if (!sensore.rilevaTransito(tipo)) {
                continue;
            }
            if (!sensore.isAttivo()) {
                LOG.warnf("🚫 Sensore %s guasto: il passaggio in %s del treno %s NON viene rilevato",
                        sensore.getId(), tipo, trenoId);
                return false;
            }
            LOG.infof("🛰️ Sensore %s: rilevato il treno %s in %s", sensore.getId(), trenoId, tipo);
            String payload = String.format("{\"trenoId\":\"%s\",\"tipo\":\"%s\",\"sensoreId\":\"%s\"}",
                    trenoId, tipo, sensore.getId());
            inviaAllaStazione("/stazione/sensore/treno", payload);
            return true;
        }
        LOG.warnf("Nessun sensore competente per un transito di tipo '%s'", tipo);
        return false;
    }

    /** @return i sensori simulati, in sola lettura, per la CLI e per l'endpoint di stato. */
    public Collection<SensoreBinario> elenco() {
        return sensori.values();
    }

    /**
     * Cerca un sensore per id, accettando anche solo la parte finale ("IN", "OUT"):
     * scrivere per intero "S1-BIN1-IN" nella CLI a ogni prova è una noia.
     *
     * @return il sensore, oppure null se non c'è.
     */
    public SensoreBinario trova(String idOSuffisso) {
        if (idOSuffisso == null || idOSuffisso.isBlank()) {
            return null;
        }
        String cercato = idOSuffisso.trim();
        SensoreBinario esatto = sensori.get(cercato);
        if (esatto != null) {
            return esatto;
        }
        for (SensoreBinario sensore : sensori.values()) {
            if (sensore.getId().equalsIgnoreCase(cercato)
                    || sensore.getId().toUpperCase().endsWith("-" + cercato.toUpperCase())
                    || sensore.getTipo().name().equalsIgnoreCase(cercato)) {
                return sensore;
            }
        }
        return null;
    }

    /**
     * Simula il guasto di un sensore (fail-stop): da qui in poi non batte e non rileva.
     *
     * @return false se l'id non corrisponde a nessun sensore.
     */
    public boolean ferma(String idSensore) {
        SensoreBinario sensore = trova(idSensore);
        if (sensore == null) {
            return false;
        }
        sensore.ferma();
        LOG.warnf("🔻 Sensore %s SPENTO (fail-stop simulato): la stazione se ne accorgerà allo scadere del timeout",
                sensore.getId());
        return true;
    }

    /**
     * Rimette in funzione un sensore fermato: il primo battito che riparte fa tornare
     * operativa la stazione (se non ci sono altri sensori muti).
     *
     * @return false se l'id non corrisponde a nessun sensore.
     */
    public boolean riprendi(String idSensore) {
        SensoreBinario sensore = trova(idSensore);
        if (sensore == null) {
            return false;
        }
        sensore.riprendi();
        LOG.infof("🔺 Sensore %s RIACCESO: riprende a mandare il keepalive", sensore.getId());
        return true;
    }

    /**
     * POST verso le API della stazione, in chiaro su localhost e senza aspettare la risposta:
     * il sensore è un dispositivo di campo, non deve rallentare né lo scheduler né il thread
     * MQTT da cui arriva il rilevamento. L'esito interessa solo per il log.
     */
    private void inviaAllaStazione(String path, String payload) {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        httpClient.sendAsync(richiesta, HttpResponse.BodyHandlers.ofString())
                .thenAccept(risposta -> {
                    if (risposta.statusCode() < 200 || risposta.statusCode() >= 300) {
                        LOG.warnf("Sensore: chiamata %s rifiutata (HTTP %d): %s",
                                path, risposta.statusCode(), risposta.body());
                    }
                })
                .exceptionally(errore -> {
                    // La stazione non risponde: per il sensore è come parlare nel vuoto.
                    LOG.warnf("Sensore: stazione irraggiungibile su %s (%s)", path, errore.getMessage());
                    return null;
                });
    }
}
