package it.uni.reti2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova del giro completo dei sensori di binario: battito, fail-stop e ritorno.
 *
 * <p>Il profilo accorcia i tempi (timeout di 2 secondi invece di 30) e soprattutto sposta
 * il broker MQTT su una porta dove non c'è nessuno: il nodo di prova non deve finire a
 * pubblicare guasti veri sul topic railway/alerts della Centrale accesa, che si ritroverebbe
 * un allarme per una stazione che non esiste. Anche il db locale è spento, così il file
 * SQLite del nodo vero resta dov'è.</p>
 */
@QuarkusTest
@TestProfile(SensoriFailStopTest.ProfiloSensoriVeloci.class)
public class SensoriFailStopTest {

    public static class ProfiloSensoriVeloci implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "stazione.id", "STEST",
                    "sensori.timeout.secondi", "2",
                    "sensori.controllo.periodo", "1s",
                    "sensori.heartbeat.periodo", "1s",
                    "stazione.db.abilitata", "false",
                    "mqtt.port", "18831",
                    // Il sensore bussa alla porta che legge da quarkus.http.port: nei test
                    // il server sta su quarkus.http.test-port, che di default e' casuale.
                    // Se le due non coincidono i keepalive finiscono su un'altra porta (nel
                    // caso peggiore su quella di una stazione vera accesa sulla macchina),
                    // quindi qui se ne sceglie una fissa e la si mette in tutte e due.
                    "quarkus.http.port", "18081",
                    "quarkus.http.test-port", "18081");
        }
    }

    /** Deve coincidere con lo "stazione.id" del profilo: è la metà iniziale dell'id dei sensori. */
    private static final String STAZIONE = "STEST";

    @Inject
    DBLocale dbLocale;

    @Inject
    SimulatoreSensori simulatore;

    private String idIngresso() {
        return SensoreBinario.idPer(STAZIONE, 1, SensoreBinario.Tipo.INGRESSO);
    }

    /** Qualunque cosa succeda nel test, i sensori vanno lasciati accesi per la prova dopo. */
    @AfterEach
    void riaccendiTutto() {
        for (SensoreBinario sensore : simulatore.elenco()) {
            sensore.riprendi();
        }
    }

    /**
     * I sensori devono chiamarsi con l'id della stazione: se il simulatore e il monitor lo
     * leggessero da due posti diversi (o da un campo non ancora valorizzato) i battiti
     * arriverebbero a nome di un sensore che la stazione non sta aspettando, e il fail-stop
     * scatterebbe comunque dopo il timeout, per sempre.
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
        // Non basta guardare che ci sia un battito: la riga la scrive gia' il monitor
        // all'avvio. Quello che deve succedere e' che l'istante si sposti in avanti,
        // cioe' che i keepalive stiano davvero arrivando via REST.
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
