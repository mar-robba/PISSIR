package it.uni.reti2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Legge i campi di un oggetto JSON (Gson) con un valore di riserva.
 *
 * <p>I messaggi che arrivano al treno (alert, itinerario, risposta di validazione) non hanno
 * sempre tutti i campi: un alert di GUASTO non ha "target", un RESOLVED non ha "stato". Chi
 * li legge vuole un valore comunque, senza un controllo di null per ogni campo: se il campo
 * manca, vale null nel JSON o non è del tipo giusto, questi metodi restituiscono il valore
 * di riserva. Come Percorso e ContestoTls è una classe di sole funzioni statiche.</p>
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

    /** @return Il campo come intero (anche se nel JSON è la stringa "7"), o il valore di riserva. */
    public static int intero(JsonObject oggetto, String campo, int riserva) {
        JsonElement valore = oggetto.get(campo);
        if (valore == null || !valore.isJsonPrimitive()) {
            return riserva;
        }
        try {
            return valore.getAsInt();
        } catch (NumberFormatException e) {
            return riserva;
        }
    }

    /** @return Il campo come numero decimale, o il valore di riserva. */
    public static double decimale(JsonObject oggetto, String campo, double riserva) {
        JsonElement valore = oggetto.get(campo);
        if (valore == null || !valore.isJsonPrimitive()) {
            return riserva;
        }
        try {
            return valore.getAsDouble();
        } catch (NumberFormatException e) {
            return riserva;
        }
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
