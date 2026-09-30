package it.uni.reti2;

import java.time.Instant;

/**
 * Modello di un sensore di binario simulato.
 *
 * <p>Nel sistema vero questi sono due scatolotti attaccati alla rotaia (spire induttive
 * o lettori RFID): uno all'imbocco del binario, che vede il convoglio ENTRARE, e uno
 * all'uscita, che lo vede USCIRE. Per adesso la stazione ha un binario solo, quindi i
 * sensori sono esattamente due, ma la classe è già scritta col numero di binario dentro
 * l'id: aggiungerne altri vuol dire solo creare altre istanze.</p>
 *
 * <p>Ogni sensore fa due cose: manda periodicamente il proprio keepalive alla stazione
 * (REST, come da scelta di progetto per i sensori) e segnala i passaggi che rileva.
 * Il flag {@link #isAttivo()} simula il <b>fail-stop</b> del dispositivo: un sensore
 * guasto si ferma e basta, non manda più il battito e soprattutto non rileva più niente
 * (è tutto lo stesso pezzo di elettronica). È il comportamento che la CLI permette di
 * accendere e spegnere a mano per far vedere la reazione della stazione.</p>
 */
public class SensoreBinario {

    /**
     * Che cosa sorveglia il sensore: l'imbocco del binario (treno che entra)
     * oppure la sua fine (treno che esce).
     */
    public enum Tipo {
        INGRESSO("ENTRATA"),
        USCITA("USCITA");

        /** Il tipo di transito che questo sensore è in grado di rilevare. */
        private final String transito;

        Tipo(String transito) {
            this.transito = transito;
        }

        public String transito() {
            return transito;
        }
    }

    private final String id;
    private final Tipo tipo;
    private final int binario;

    /**
     * false = sensore in fail-stop: non manda più il keepalive e non rileva più i treni.
     * volatile perché lo scrivono le richieste REST di stop/start (che arrivano anche dalla
     * console) e lo leggono il thread dell'orologio e quello di Paho.
     */
    private volatile boolean attivo = true;

    /** Istante dell'ultimo keepalive spedito, serve solo per raccontarlo nella CLI. */
    private volatile Instant ultimoBattitoInviato;

    public SensoreBinario(String id, Tipo tipo, int binario) {
        this.id = id;
        this.tipo = tipo;
        this.binario = binario;
    }

    /**
     * Costruisce l'identificativo di un sensore con la convenzione usata in tutto il nodo:
     * {@code <stazione>-BIN<n>-IN} oppure {@code <stazione>-BIN<n>-OUT}.
     *
     * <p>Il metodo è statico e sta qui apposta: lo usano sia il simulatore, che deve dare
     * un nome ai dispositivi che finge di far girare, sia il main, che mette sotto
     * osservazione in AutomaSensori i battiti da aspettarsi ancora prima di riceverne uno.
     * Se il nome lo costruissero per conto proprio, la stazione starebbe ad aspettare un
     * sensore con un id diverso da quello che manda i battiti e si dichiarerebbe guasta per
     * sempre.</p>
     *
     * @param stazioneId id della stazione a cui il sensore appartiene.
     * @param binario    numero del binario sorvegliato.
     * @param tipo       ingresso o uscita.
     * @return l'id del sensore.
     */
    public static String idPer(String stazioneId, int binario, Tipo tipo) {
        return String.format("%s-BIN%d-%s", stazioneId, binario, tipo == Tipo.INGRESSO ? "IN" : "OUT");
    }

    public String getId() {
        return id;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public int getBinario() {
        return binario;
    }

    public boolean isAttivo() {
        return attivo;
    }

    /** Simula il guasto del dispositivo: da qui in poi tace e non vede più niente. */
    public void ferma() {
        this.attivo = false;
    }

    /** Il tecnico ha rimesso a posto il sensore: ricomincia a battere e a rilevare. */
    public void riprendi() {
        this.attivo = true;
    }

    public Instant getUltimoBattitoInviato() {
        return ultimoBattitoInviato;
    }

    public void segnaBattito(Instant quando) {
        this.ultimoBattitoInviato = quando;
    }

    /**
     * @param tipoTransito "ENTRATA" oppure "USCITA".
     * @return true se è questo il sensore che deve accorgersi di quel passaggio.
     */
    public boolean rilevaTransito(String tipoTransito) {
        return tipo.transito().equalsIgnoreCase(tipoTransito);
    }
}
