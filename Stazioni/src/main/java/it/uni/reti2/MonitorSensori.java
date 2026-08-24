package it.uni.reti2;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;

/**
 * Sorveglianza dei sensori di binario: è il lato stazione del meccanismo di
 * <b>heartbeat + fail-stop</b> chiesto dall'enunciato ("la stazione che non riceve più i
 * keepalive dai propri sensori segnala lo stato di guasta alla Centrale Operativa").
 *
 * <p>Il patto è semplice: ogni sensore del binario deve farsi vivo con una POST su
 * {@code /stazione/sensore/heartbeat} entro {@code sensori.timeout.secondi}. Se il battito
 * non arriva la stazione considera il dispositivo fermo (fail-stop), passa in GUASTA e lo
 * dice alla Centrale con un allarme CRITICAL di tipo {@code sensore_offline}; quando i
 * battiti riprendono la stazione torna da sola operativa e manda il RESOLVED.</p>
 *
 * <p>Questa classe non conosce {@link SimulatoreSensori}: parla solo di battiti ricevuti,
 * quindi funziona identica se domani al posto del simulatore ci sono le spire vere.</p>
 *
 * <p>Prima il controllo dei keepalive stava dentro {@link HeartbeatGenerator}, appeso al job
 * di manutenzione che serviva a svuotare il buffer. Erano due cose diverse messe insieme, e
 * soprattutto quel codice, appena scaduto il timeout, <i>cancellava</i> il sensore dalla
 * mappa: la stazione andava in GUASTA e non c'era più modo di accorgersi che il sensore era
 * tornato a battere. Il ripristino automatico richiedeva di tenere il sensore sotto
 * osservazione anche da guasto, ed è il motivo per cui il monitor ha una classe sua.</p>
 */
@ApplicationScoped
public class MonitorSensori {

    private static final Logger LOG = Logger.getLogger(MonitorSensori.class);

    /** Classificazione dichiarata alla Centrale per i guasti che apre questa classe. */
    public static final String TIPO_GUASTO_SENSORE = "sensore_offline";

    @Inject
    DBLocale dbLocale;

    @Inject
    StationGateway stationGateway;

    @Inject
    PersistenzaLocale persistenza;

    /** Secondi di silenzio oltre i quali un sensore viene considerato guasto. */
    @ConfigProperty(name = "sensori.timeout.secondi", defaultValue = "30")
    long timeoutSensoriSecondi;

    /** Numero del binario sorvegliato: per ora ce n'è uno solo, quindi due soli sensori. */
    @ConfigProperty(name = "stazione.binario", defaultValue = "1")
    int binario;

    /**
     * Id della stazione, letto dalla configurazione per lo stesso motivo spiegato in
     * {@link SimulatoreSensori}: allo StartupEvent il campo omonimo di {@link DBLocale} è
     * ancora nullo (lo riempie {@code main.run()}, che gira dopo), e i sensori attesi
     * finirebbero registrati con un nome che non corrisponde a quello di chi manda i battiti.
     */
    @ConfigProperty(name = "stazione.id", defaultValue = "S1")
    String stazioneId;

    /**
     * Mette sotto osservazione i sensori che questa stazione si aspetta di sentire.
     *
     * <p>L'elenco è noto in partenza e non si costruisce sui battiti che arrivano: un
     * sensore che è già morto quando il nodo si accende non manderebbe nulla, e la stazione
     * non avrebbe niente da mettere in timeout. L'istante di partenza è "adesso", così ogni
     * sensore ha il timeout intero di tempo per farsi vivo dopo l'avvio.</p>
     */
    void avvio(@Observes StartupEvent evento) {
        Instant adesso = Instant.now();
        for (SensoreBinario.Tipo tipo : SensoreBinario.Tipo.values()) {
            dbLocale.sensoriUltimoBattito.put(SensoreBinario.idPer(stazioneId, binario, tipo), adesso);
        }

        // Se il nodo si era spento mentre era guasto per colpa di un sensore, il guasto è
        // ancora aperto sul db locale: va ricordato che quel GUASTA è "roba nostra", se no
        // il ritorno dei battiti non riuscirebbe più a rimettere la stazione operativa.
        if ("GUASTA".equals(dbLocale.getStato())
                && persistenza.contaGuastiApertiDiTipo(TIPO_GUASTO_SENSORE) > 0) {
            dbLocale.guastoDaSensori = true;
            LOG.warn("💾 La stazione riparte GUASTA per un sensore muto: aspetto il ritorno dei keepalive.");
        }

        LOG.infof("👁️ Monitor sensori attivo su %d dispositivi (timeout %d s): %s",
                dbLocale.sensoriUltimoBattito.size(), timeoutSensoriSecondi, dbLocale.sensoriUltimoBattito.keySet());
    }

    /**
     * Registra il keepalive appena ricevuto da un sensore.
     *
     * <p>Il ripristino si fa qui e non nel job periodico apposta: la "rinnovata ricezione"
     * del battito è l'evento che rimette in servizio la stazione, e farla aspettare fino al
     * prossimo giro di controllo vorrebbe dire tenerla guasta per qualche secondo in più
     * senza motivo.</p>
     *
     * @param sensoreId identificativo del sensore che ha battuto.
     */
    public void registraBattito(String sensoreId) {
        dbLocale.sensoriUltimoBattito.put(sensoreId, Instant.now());

        if (dbLocale.sensoriGuasti.remove(sensoreId)) {
            LOG.infof("💚 Sensore %s tornato a battere.", sensoreId);
            ripristinaSePossibile();
        }
    }

    /**
     * Giro di controllo periodico: chi non batte da troppo tempo è fermo.
     * Il periodo è molto più corto del timeout, così il ritardo con cui ci si accorge
     * del guasto è dominato dal timeout e non dalla frequenza di questo job.
     */
    @Scheduled(every = "{sensori.controllo.periodo}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void controllaSensori() {
        Instant limite = Instant.now().minusSeconds(timeoutSensoriSecondi);

        for (Map.Entry<String, Instant> sensore : dbLocale.sensoriUltimoBattito.entrySet()) {
            String sensoreId = sensore.getKey();
            boolean muto = sensore.getValue().isBefore(limite);

            // Già segnalato: la riga resta nella mappa (serve per accorgersi del ritorno),
            // ma l'allarme alla Centrale si manda una volta sola.
            if (muto && dbLocale.sensoriGuasti.add(sensoreId)) {
                dichiaraGuasto(sensoreId);
            }
        }
    }

    /**
     * Porta la stazione in GUASTA e avvisa la Centrale.
     * La severità è CRITICAL perché una stazione senza sensori non sa più chi entra e chi
     * esce dai binari: i treni in arrivo vanno trattenuti finché non arriva la manutenzione.
     */
    private void dichiaraGuasto(String sensoreId) {
        LOG.errorf("⚠️ Sensore %s silente da oltre %d secondi: stazione GUASTA!", sensoreId, timeoutSensoriSecondi);
        dbLocale.guastoDaSensori = true;
        // inviaGuasto con CRITICAL fa già passare dbLocale allo stato GUASTA e scrive il
        // guasto sul db locale; il tipo è dichiarato, non lasciato indovinare alla Centrale.
        stationGateway.inviaGuasto("Sensore " + sensoreId + " non invia keepalive",
                "CRITICAL", TIPO_GUASTO_SENSORE);
    }

    /**
     * Rimette la stazione nel proprio stato di operatività, ma solo se il guasto era
     * davvero colpa dei sensori e nessun altro sensore è ancora muto: se la stazione è
     * GUASTA perché ce l'ha detto la Centrale (per esempio un convoglio deragliato sui
     * binari) il ritorno di un keepalive non c'entra niente e non deve rimetterla in
     * servizio da sola.
     */
    private void ripristinaSePossibile() {
        if (!dbLocale.guastoDaSensori || !dbLocale.sensoriGuasti.isEmpty()) {
            return;
        }

        dbLocale.guastoDaSensori = false;
        dbLocale.setStato("ONLINE");
        LOG.info("✅ Tutti i sensori battono di nuovo: stazione di nuovo operativa.");

        // La Centrale (e con lei i treni in attesa) deve saperlo: il guasto è rientrato.
        stationGateway.inviaRipristino(TIPO_GUASTO_SENSORE,
                "Keepalive dei sensori di binario ripristinato");
    }
}
