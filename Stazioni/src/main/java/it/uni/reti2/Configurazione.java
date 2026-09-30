package it.uni.reti2;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * La configurazione del processo Stazione: legge application.properties e ci applica sopra
 * il profilo e le -D della riga di comando.
 *
 * <p>Una chiave si cerca in quest'ordine, e vince la prima che c'è:</p>
 * <ol>
 *   <li>la -D della riga di comando (es. {@code -Dhttp.port=8085});</li>
 *   <li>la versione del profilo attivo nel file (es. {@code %tls.mqtt.port=8883} quando si
 *       lancia con {@code -Dprofilo=tls});</li>
 *   <li>la chiave normale del file;</li>
 *   <li>il valore di default scritto nel codice che la chiede.</li>
 * </ol>
 *
 * <p>È la stessa classe del nodo Treno, con la stessa convenzione del prefisso {@code %tls.}:
 * così i file di configurazione dei nodi si leggono allo stesso modo.</p>
 */
public final class Configurazione {

    /** Il file, cercato dentro il jar (src/main/resources). */
    private static final String FILE = "application.properties";

    private final Properties proprieta = new Properties();

    /** Il profilo attivo (es. "tls"), null se si usa quello di default. */
    private final String profilo;

    private Configurazione(String profilo) {
        this.profilo = profilo;
    }

    /**
     * Legge application.properties dal classpath. Il profilo si sceglie con -Dprofilo.
     *
     * @return La configurazione pronta da interrogare.
     */
    public static Configurazione carica() {
        String profilo = System.getProperty("profilo");
        if (profilo != null && profilo.isBlank()) {
            profilo = null;
        }
        Configurazione conf = new Configurazione(profilo);

        try (InputStream input = Configurazione.class.getClassLoader().getResourceAsStream(FILE)) {
            if (input != null) {
                // Reader in UTF-8: i commenti del file hanno le lettere accentate
                conf.proprieta.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // senza file si va avanti con le -D e con i default del codice
            System.err.println("⚠️ Impossibile leggere " + FILE + ": " + e.getMessage());
        }
        return conf;
    }

    /** @return Il profilo attivo, null se è quello di default. */
    public String profilo() {
        return profilo;
    }

    /**
     * @param chiave Nome della proprietà (es. "mqtt.host").
     * @param valoreDiDefault Cosa restituire se la chiave non c'è da nessuna parte.
     * @return Il valore della chiave, cercato nell'ordine descritto in cima alla classe.
     */
    public String get(String chiave, String valoreDiDefault) {
        String valore = System.getProperty(chiave);
        if (valore == null && profilo != null) {
            valore = proprieta.getProperty("%" + profilo + "." + chiave);
        }
        if (valore == null) {
            valore = proprieta.getProperty(chiave);
        }
        return valore != null ? valore.trim() : valoreDiDefault;
    }

    public int getInt(String chiave, int valoreDiDefault) {
        return Integer.parseInt(get(chiave, String.valueOf(valoreDiDefault)));
    }

    public long getLong(String chiave, long valoreDiDefault) {
        return Long.parseLong(get(chiave, String.valueOf(valoreDiDefault)));
    }

    public boolean getBoolean(String chiave, boolean valoreDiDefault) {
        return Boolean.parseBoolean(get(chiave, String.valueOf(valoreDiDefault)));
    }
}
