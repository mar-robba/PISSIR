package it.uni.reti2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova del giro completo dei sensori di binario: battito, fail-stop e ritorno.
 *
 * <p>La stazione di prova la monta il test come fa il main, con i tempi accorciati (timeout
 * di 2 secondi invece di 30, controllo e battiti ogni secondo) e le API REST su una porta
 * libera qualsiasi, così i keepalive dei sensori simulati fanno davvero il giro via HTTP. Il
 * broker invece non c'è proprio: gli allarmi restano nel LocalBuffer, e il nodo di prova non
 * può finire a pubblicare guasti veri sul railway/alerts della Centrale accesa, che si
 * ritroverebbe un allarme per una stazione che non esiste. Anche il db locale è spento, così
 * il file SQLite del nodo vero resta dov'è.</p>
 */
public class SensoriFailStopTest {

    /** È la metà iniziale dell'id dei sensori. */
    private static final String STAZIONE = "STEST";

    private DBLocale dbLocale;
    private LocalBuffer localBuffer;
    private SimulatoreSensori simulatore;
    private ServerRest serverRest;
    private Orologio orologio;

    @BeforeEach
    void accendiLaStazioneDiProva() {
        PersistenzaLocale persistenza = new PersistenzaLocale();
        persistenza.abilitata = false;
        persistenza.apri();

        dbLocale = new DBLocale();
        dbLocale.persistenza = persistenza;
        dbLocale.stazioneId = STAZIONE;
        localBuffer = new LocalBuffer();
        localBuffer.persistenza = persistenza;

        AutomaStatoStazione statoStazione = new AutomaStatoStazione();
        statoStazione.dbLocale = dbLocale;
        statoStazione.persistenza = persistenza;
        AutomaSensori sensori = new AutomaSensori();
        sensori.dbLocale = dbLocale;
        sensori.timeoutSecondi = 2;
        AutomaTreniPresenti treni = new AutomaTreniPresenti();
        treni.dbLocale = dbLocale;
        AutomaConnessione connessione = new AutomaConnessione();
        connessione.dbLocale = dbLocale;
        sensori.sorveglia(List.of(idIngresso(), SensoreBinario.idPer(STAZIONE, 1, SensoreBinario.Tipo.USCITA)), Instant.now());

        PubblicatoreAlert pubblicatoreAlert = new PubblicatoreAlert();
        pubblicatoreAlert.dbLocale = dbLocale;
        pubblicatoreAlert.localBuffer = localBuffer;
        pubblicatoreAlert.connessione = connessione;
        PubblicatoreTransiti pubblicatoreTransiti = new PubblicatoreTransiti();
        pubblicatoreTransiti.dbLocale = dbLocale;
        pubblicatoreTransiti.localBuffer = localBuffer;
        pubblicatoreTransiti.connessione = connessione;

        GestoreEventi gestore = new GestoreEventi();
        gestore.dbLocale = dbLocale;
        gestore.statoStazione = statoStazione;
        gestore.sensori = sensori;
        gestore.treni = treni;
        gestore.connessione = connessione;
        gestore.pubblicatoreAlert = pubblicatoreAlert;
        gestore.pubblicatoreTransiti = pubblicatoreTransiti;
        GeneratoreEventiTempo generatoreTempo = new GeneratoreEventiTempo();
        generatoreTempo.gestore = gestore;

        simulatore = new SimulatoreSensori();
        simulatore.stazioneId = STAZIONE;
        simulatore.binario = 1;
        simulatore.httpClient = HttpClient.newHttpClient();
        simulatore.creaSensori();

        StationIngestion ingestion = new StationIngestion();
        ingestion.dbLocale = dbLocale;
        ingestion.localBuffer = localBuffer;
        ingestion.persistenza = persistenza;
        ingestion.gestore = gestore;
        ingestion.simulatoreSensori = simulatore;

        // porta 0: Javalin ne sceglie una libera, poi la si passa ai sensori
        serverRest = new ServerRest();
        serverRest.porta = 0;
        serverRest.tls = false;
        serverRest.ingestion = ingestion;
        serverRest.avvia();
        simulatore.indirizzoApi = "http://localhost:" + serverRest.porta();

        orologio = new Orologio();
        orologio.ogni("controllo dei sensori", 1, generatoreTempo::controllaSensori);
        orologio.ogni("keepalive dei sensori simulati", 1, simulatore::battito);
    }

    @AfterEach
    void spegniLaStazioneDiProva() {
        orologio.ferma();
        serverRest.ferma();
    }

    private String idIngresso() {
        return SensoreBinario.idPer(STAZIONE, 1, SensoreBinario.Tipo.INGRESSO);
    }

    /**
     * I sensori devono chiamarsi con l'id della stazione: se il simulatore e la stazione lo
     * costruissero in due modi diversi i battiti arriverebbero a nome di un sensore che la
     * stazione non sta aspettando, e il fail-stop scatterebbe comunque dopo il timeout, per
     * sempre.
     */
    @Test
    public void iSensoriSiChiamanoConLIdDellaStazione() {
        assertTrue(dbLocale.sensoriUltimoBattito.containsKey(idIngresso()),
                "la stazione non sta sorvegliando " + idIngresso() + ", ma " + dbLocale.sensoriUltimoBattito.keySet());
        assertTrue(simulatore.trova("IN") != null && idIngresso().equals(simulatore.trova("IN").getId()),
                "il sensore simulato ha un id diverso da quello atteso dalla stazione");
    }

    @Test
    public void iSensoriMandanoIlKeepaliveAllaStazione() {
        // Non basta guardare che ci sia un battito: la riga la scrive gia' il main all'avvio.
        // Quello che deve succedere e' che l'istante si sposti in avanti, cioe' che i
        // keepalive stiano davvero arrivando via REST.
        Instant partenza = dbLocale.sensoriUltimoBattito.get(idIngresso());
        assertTrue(attendi(() -> {
            Instant battito = dbLocale.sensoriUltimoBattito.get(idIngresso());
            return battito != null && (partenza == null || battito.isAfter(partenza));
        }), "il sensore di ingresso non ha mandato nessun keepalive nuovo");
    }

    @Test
    public void ilSensoreMutoGuastaLaStazioneEIlSuoRitornoLaRimetteOperativa() {
        assertTrue(attendi(() -> "ONLINE".equals(dbLocale.getStato())), "la stazione non parte operativa");

        // Fail-stop del sensore di ingresso: smette di battere
        simulatore.ferma("IN");

        assertTrue(attendi(() -> "GUASTA".equals(dbLocale.getStato())),
                "passato il timeout la stazione doveva dichiararsi GUASTA");
        assertTrue(dbLocale.sensoriGuasti.contains(idIngresso()), "il sensore muto non risulta fra quelli guasti");
        assertTrue(dbLocale.guastoDaSensori, "il guasto doveva risultare causato dai sensori");

        // Il tecnico rimette a posto il sensore: il primo battito rimette su la stazione
        simulatore.riprendi("IN");

        assertTrue(attendi(() -> "ONLINE".equals(dbLocale.getStato())),
                "tornati i keepalive la stazione doveva tornare operativa");
        assertTrue(dbLocale.sensoriGuasti.isEmpty(), "non doveva restare nessun sensore guasto");
        // e per la Centrale sono partiti (restano in coda, qui il broker non c'e') il guasto e il rientro
        assertTrue(localBuffer.getBuffer().stream().anyMatch(e -> e.payload().contains("\"GUASTO\"")));
        assertTrue(localBuffer.getBuffer().stream().anyMatch(e -> e.payload().contains("\"RESOLVED\"")));
    }

    @Test
    public void ilSensoreFermoNonRilevaIlPassaggioDelTreno() {
        assertTrue(simulatore.rilevaPassaggio("TEST-1", "ENTRATA"), "a sensore acceso il transito va rilevato");

        simulatore.ferma("IN");
        assertFalse(simulatore.rilevaPassaggio("TEST-1", "ENTRATA"),
                "un sensore in fail-stop non può accorgersi di niente");

        // Quello di uscita è un altro dispositivo e continua a funzionare
        assertTrue(simulatore.rilevaPassaggio("TEST-1", "USCITA"), "il sensore di uscita doveva essere ancora vivo");
    }

    /** Le API rispondono 400 a chi manda richieste incomplete o un corpo che non è JSON. */
    @Test
    public void leApiRifiutanoLeRichiesteSbagliate() throws Exception {
        assertEquals(400, post("/stazione/sensore/treno", "{\"trenoId\":\"TRN1\"}"));
        assertEquals(400, post("/stazione/sensore/treno", "{\"trenoId\":\"TRN1\",\"tipo\":\"DI_PASSAGGIO\"}"));
        assertEquals(400, post("/stazione/sensore/heartbeat", "questo non e' JSON"));
        assertEquals(404, post("/stazione/sensori/NESSUNO/stop", ""));
        assertEquals(200, post("/stazione/sensore/treno", "{\"trenoId\":\"TRN1\",\"tipo\":\"ENTRATA\"}"));
        assertTrue(dbLocale.getTreniPresenti().containsKey("TRN1"));
    }

    private int post(String path, String corpo) throws Exception {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create(simulatore.indirizzoApi + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        return HttpClient.newHttpClient().send(richiesta, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * Aspetta che una condizione diventi vera, fino a 15 secondi.
     * Serve perché fra il fail-stop e la reazione della stazione ci sono di mezzo il
     * timeout dei keepalive e il giro di controllo periodico, che non sono istantanei.
     */
    private boolean attendi(BooleanSupplier condizione) {
        Instant scadenza = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(scadenza)) {
            if (condizione.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condizione.getAsBoolean();
    }
}
