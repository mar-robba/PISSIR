package it.uni.reti2.gateway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Il convertitore JSON della Centrale (Gson), configurato una volta sola.
 *
 * <p>Lo usano le API REST (è il JsonMapper di Javalin), la fotografia periodica mandata sulla
 * WebSocket e la lettura dei payload MQTT. Deve essere lo stesso per tutti perché treni e
 * stazioni arrivano al browser sia da GET /api/treni sia dall'evento SNAPSHOT, e la web app
 * li legge con lo stesso codice: se i due JSON fossero diversi, lo sarebbe anche la pagina.</p>
 *
 * <p>Tre impostazioni, tutte per scrivere lo stesso JSON che scriveva Jackson prima:</p>
 * <ul>
 *   <li><b>serializeNulls</b>: un campo null si scrive come {@code "campo": null}, non si
 *       omette. La web app usa proprio il null per cancellare quello che sapeva prima (la
 *       previsione di arrivo di una corsa soppressa, la posizione non più garantita);</li>
 *   <li><b>gli istanti</b> ({@link Instant}) si scrivono come testo ISO-8601
 *       ({@code 2026-09-29T10:15:30Z}). Gson da solo proverebbe a entrare nei campi privati
 *       della classe del JDK, e Java 25 non glielo permette;</li>
 *   <li><b>disableHtmlEscaping</b>: senza, l'apostrofo di "e' fermo" diventerebbe
 *       {@code '}. Sarebbe JSON valido lo stesso, ma illeggibile con curl.</li>
 * </ul>
 */
public final class Json {

    /** L'unico Gson della Centrale. */
    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, new IstanteIso())
            .create();

    private Json() {
        // classe di sole utilita': non si istanzia
    }

    /**
     * Legge un payload arrivato dal campo.
     *
     * @throws com.google.gson.JsonParseException Se il testo non è JSON, o non è un oggetto.
     */
    public static JsonObject oggetto(String testo) {
        return JsonParser.parseString(testo).getAsJsonObject();
    }

    /** Un {@link Instant} scritto e letto come testo ISO-8601. */
    private static final class IstanteIso extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant istante) throws IOException {
            if (istante == null) {
                out.nullValue();
            } else {
                out.value(istante.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
