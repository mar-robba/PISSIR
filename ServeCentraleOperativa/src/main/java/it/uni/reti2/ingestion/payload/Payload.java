package it.uni.reti2.ingestion.payload;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;

/**
 * Piccoli attrezzi di lettura condivisi da tutti i messaggi che arrivano dal campo.
 *
 * <p>Prima ogni consumer si scriveva in proprio la stessa riga
 * ({@code root.has("x") ? root.get("x").asText() : "default"}) per ognuno dei suoi
 * campi: una decina di ternari per metodo, ripetuti cinque volte. Il valore di
 * ripiego di un campo assente e' una decisione che va presa una volta sola e in un
 * posto in cui si vede, non annegata dentro la logica.</p>
 */
public final class Payload {

    private Payload() {
    }

    /**
     * Testo del campo, oppure il ripiego se il campo manca o e' null. Un numero diventa il
     * suo testo ("12.5"); un oggetto o una lista, che al posto di un testo non hanno senso,
     * diventano la stringa vuota.
     */
    public static String testo(JsonObject root, String campo, String ripiego) {
        if (!presente(root, campo)) {
            return ripiego;
        }
        JsonElement valore = root.get(campo);
        return valore.isJsonPrimitive() ? valore.getAsString() : "";
    }

    /** Come {@link #testo}, ma una stringa vuota diventa null: e' cosi' che si scrive "non lo so". */
    public static String testoOpzionale(JsonObject root, String campo) {
        String valore = testo(root, campo, "");
        return valore.isEmpty() ? null : valore;
    }

    /**
     * Numero con la virgola, oppure il ripiego se il campo manca. Un campo che c'e' ma non e'
     * un numero (neanche scritto come testo, "12.5") vale 0.
     */
    public static double numero(JsonObject root, String campo, double ripiego) {
        if (!presente(root, campo)) {
            return ripiego;
        }
        try {
            return root.get(campo).getAsDouble();
        } catch (RuntimeException nonNumerico) {
            return 0.0;
        }
    }

    /** Numero intero, oppure il ripiego se il campo manca; 0 se c'e' ma non e' un numero. */
    public static int intero(JsonObject root, String campo, int ripiego) {
        if (!presente(root, campo)) {
            return ripiego;
        }
        try {
            JsonPrimitive valore = root.getAsJsonPrimitive(campo);
            return valore.isNumber() ? valore.getAsInt() : (int) Double.parseDouble(valore.getAsString());
        } catch (RuntimeException nonNumerico) {
            return 0;
        }
    }

    /** Il campo c'e' e non vale null. */
    private static boolean presente(JsonObject root, String campo) {
        return root.has(campo) && !root.get(campo).isJsonNull();
    }

    /**
     * Interpreta il timestamp ISO-8601 dichiarato dal nodo che ha spedito il messaggio.
     *
     * <p>Il ripiego sull'orario di ricezione serve perche' un messaggio con la data
     * scritta male non deve far cadere l'intero frame: si perde la precisione dell'ora
     * del passaggio, non l'evento. Vale la pena ricordare che con lo store and forward
     * (SV02) l'ora dichiarata e quella di arrivo possono essere lontanissime, quindi
     * questo ripiego e' davvero l'ultima spiaggia.</p>
     */
    public static Instant istante(JsonObject root) {
        try {
            if (presente(root, "timestamp")) {
                return Instant.parse(root.get("timestamp").getAsString());
            }
        } catch (Exception formatoNonValido) {
            // formato non riconosciuto: si ripiega sull'orario di ricezione
        }
        return Instant.now();
    }
}
