package it.uni.reti2.ingestion.payload;

import com.fasterxml.jackson.databind.JsonNode;

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

    /** Testo del campo, oppure il ripiego se il campo manca o e' null. */
    public static String testo(JsonNode root, String campo, String ripiego) {
        return root.hasNonNull(campo) ? root.get(campo).asText() : ripiego;
    }

    /** Come {@link #testo}, ma una stringa vuota diventa null: e' cosi' che si scrive "non lo so". */
    public static String testoOpzionale(JsonNode root, String campo) {
        String valore = testo(root, campo, "");
        return valore.isEmpty() ? null : valore;
    }

    /** Numero con la virgola, oppure il ripiego. */
    public static double numero(JsonNode root, String campo, double ripiego) {
        return root.hasNonNull(campo) ? root.get(campo).asDouble() : ripiego;
    }

    /** Numero intero, oppure il ripiego. */
    public static int intero(JsonNode root, String campo, int ripiego) {
        return root.hasNonNull(campo) ? root.get(campo).asInt() : ripiego;
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
    public static Instant istante(JsonNode root) {
        try {
            if (root.hasNonNull("timestamp")) {
                return Instant.parse(root.get("timestamp").asText());
            }
        } catch (Exception formatoNonValido) {
            // formato non riconosciuto: si ripiega sull'orario di ricezione
        }
        return Instant.now();
    }
}
