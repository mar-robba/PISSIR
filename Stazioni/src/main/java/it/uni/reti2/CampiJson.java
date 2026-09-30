package it.uni.reti2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Legge i campi di un oggetto JSON (Gson) con un valore di riserva.
 *
 * <p>I messaggi che arrivano alla stazione (alert della Centrale, passaggi dei treni,
 * risposta di validazione, corpi delle richieste REST dei sensori) non hanno sempre tutti i
 * campi: un RESOLVED non ha "severita", un guasto segnalato può non avere "tipoGuasto". Chi li
 * legge vuole un valore comunque, senza un controllo di null per ogni campo: se il campo
 * manca, vale null nel JSON o non è del tipo giusto, questi metodi restituiscono il valore di
 * riserva. È la stessa classe del nodo Treno, ed è una classe di sole funzioni statiche.</p>
 */
public final class CampiJson {

    private CampiJson() {
        // classe di sole utilita': non si istanzia
    }

    /** @return Il campo come stringa (anche se nel JSON è un numero), o il valore di riserva. */
    public static String testo(JsonObject oggetto, String campo, String riserva) {
        JsonElement valore = oggetto.get(campo);
        if (valore == null || !valore.isJsonPrimitive()) {
            return riserva;
        }
        return valore.getAsString();
    }

    /** @return Il campo come booleano (true o "true"), o il valore di riserva. */
    public static boolean booleano(JsonObject oggetto, String campo, boolean riserva) {
        JsonElement valore = oggetto.get(campo);
        if (valore == null || !valore.isJsonPrimitive()) {
            return riserva;
        }
        return valore.getAsBoolean();
    }

    /** @return Il campo come array, vuoto se manca: si può scorrere con un for senza controlli. */
    public static JsonArray lista(JsonObject oggetto, String campo) {
        JsonElement valore = oggetto.get(campo);
        if (valore == null || !valore.isJsonArray()) {
            return new JsonArray();
        }
        return valore.getAsJsonArray();
    }
}
