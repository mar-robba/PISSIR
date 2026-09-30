package it.uni.reti2;

import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il consumatore della coda store and forward: un thread solo, "consumatore-buffer", che
 * prende dal LocalBuffer un evento alla volta, dal più vecchio, e lo pubblica sul broker.
 *
 * <p>Per ogni evento il giro è sempre lo stesso: si aspetta che la coda non sia vuota, si
 * guarda la testa senza toglierla, la si pubblica e solo se il broker l'ha presa la si toglie
 * (dalla RAM e dal db locale). Se l'invio non riesce la testa resta dov'è e si riprova dopo un
 * secondo: gli eventi dietro aspettano, quindi alla Centrale arrivano nell'ordine in cui sono
 * nati, e una USCITA non può mai passare davanti alla sua ENTRATA.</p>
 *
 * <p>Quando il collegamento è staccato (POST /stazione/rete/offline) non si tenta nemmeno:
 * tentare e fallire per ogni evento vorrebbe dire pagare il timeout del broker su ogni
 * transito. Quando invece è il broker a essere giù si tenta, e il tentativo fallisce subito:
 * Paho sa di non essere collegato e intanto si sta ricollegando da solo. In tutti e due i casi
 * la coda si svuota da sola appena si può, senza nessun job che passi a controllarla.</p>
 *
 * <p>I topic sono quelli di sempre: i transiti su railway/station/{id}/transit a QoS 0, gli
 * allarmi su railway/alerts a QoS 1, cioè con la conferma del broker (vedi ClientMqtt).</p>
 */
public class ConsumatoreBuffer {

    private static final Logger LOG = Logger.getLogger(ConsumatoreBuffer.class.getName());

    /** Pausa prima di riprovare quando la testa non può partire (millisecondi). */
    private static final long ATTESA_MS = 1000;

    /** La coda da svuotare. Assegnata dal main, come i campi qui sotto. */
    LocalBuffer localBuffer;

    /** Il collegamento con la Centrale: da staccato non si pubblica. */
    AutomaConnessione connessione;

    /** Con che cosa si pubblica. */
    ClientMqtt clientMqtt;

    /** Id della stazione: fa parte del topic dei transiti. */
    String stazioneId;

    private Thread thread;

    /** Fa partire il thread consumatore. Lo chiama il main dopo aver collegato ClientMqtt. */
    public void avvia() {
        thread = new Thread(this::consuma, "consumatore-buffer");
        thread.setDaemon(true); // non deve impedire la chiusura del processo
        thread.start();
    }

    /** Ferma il thread. Serve ai test, che accendono e spengono una stazione per prova. */
    public void ferma() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Il ciclo del consumatore. I tre flag servono solo ai log: quando c'è un arretrato (eventi
     * che hanno dovuto aspettare) si dice una volta che si sta svuotando il buffer e poi si
     * scrive ogni evento che riparte, come faceva il vecchio flush; il guasto del broker si
     * scrive una volta sola e non a ogni tentativo.
     */
    private void consuma() {
        // gli eventi rimasti su disco dall'ultima accensione sono un arretrato anche loro
        boolean arretrato = !localBuffer.isEmpty();
        boolean svuotamentoAnnunciato = false;
        boolean guastoAnnunciato = false;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                LocalBuffer.EventoBufferizzato testa = localBuffer.attendiTesta();

                if (!connessione.collegata()) {
                    arretrato = true;
                    Thread.sleep(ATTESA_MS);
                    continue;
                }

                try {
                    pubblica(testa);
                } catch (Exception e) {
                    if (!guastoAnnunciato) {
                        LOG.warning(String.format("📮 Evento '%s' non consegnato (%s): resta in testa alla coda, "
                                + "riprovo finché il broker non torna", testa.canale(), e.getMessage()));
                        guastoAnnunciato = true;
                    }
                    arretrato = true;
                    Thread.sleep(ATTESA_MS);
                    continue;
                }

                if (arretrato) {
                    if (!svuotamentoAnnunciato) {
                        LOG.info(String.format("📡 Connessione ok, svuoto il buffer (%d eventi)...", localBuffer.size()));
                        svuotamentoAnnunciato = true;
                    }
                    LOG.info(String.format("📤 Re-invio dal buffer [%s]: %s", testa.canale(), testa.payload()));
                }
                // Consegnato: adesso si può togliere dalla coda e dal db locale
                localBuffer.confermaInvio(testa);

                if (localBuffer.isEmpty()) {
                    arretrato = false;
                    svuotamentoAnnunciato = false;
                    guastoAnnunciato = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Un errore inatteso non deve fermare il thread: senza consumatore la coda
                // crescerebbe e basta, e alla Centrale non arriverebbe più niente.
                LOG.log(Level.SEVERE, "Errore nel consumatore del buffer: " + e.getMessage(), e);
                try {
                    Thread.sleep(ATTESA_MS);
                } catch (InterruptedException interrotto) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Pubblica un evento sul topic del suo canale. */
    private void pubblica(LocalBuffer.EventoBufferizzato evento) throws MqttException {
        if ("alert".equals(evento.canale())) {
            clientMqtt.pubblica("railway/alerts", evento.payload(), 1);
        } else {
            clientMqtt.pubblica("railway/station/" + stazioneId + "/transit", evento.payload(), 0);
        }
    }
}
