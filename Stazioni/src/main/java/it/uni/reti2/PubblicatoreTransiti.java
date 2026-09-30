package it.uni.reti2;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Prepara il transito ufficiale di un convoglio per railway/station/{id}/transit e lo mette
 * nel LocalBuffer, da dove lo pubblica il ConsumatoreBuffer.
 *
 * <p>È la notifica che la Centrale storicizza (è la fonte unica dello storico dei transiti):
 * il treno annuncia il proprio passaggio ai sensori di binario, ma alla Centrale il transito
 * lo certifica solo la stazione. Come PubblicatoreAlert sta fuori dallo StationGateway perché
 * a chiamarlo è GestoreEventi, nella fase delle azioni.</p>
 */
public class PubblicatoreTransiti {

    private static final Logger LOG = Logger.getLogger(PubblicatoreTransiti.class.getName());

    /** Stato del nodo: serve l'id della stazione. Assegnato dal main. */
    DBLocale dbLocale;

    /** La coda verso la Centrale. */
    LocalBuffer localBuffer;

    /** Il collegamento: solo per dire nel log che il transito resta in coda. */
    AutomaConnessione connessione;

    /**
     * Accoda il transito di un convoglio.
     *
     * @param trenoId Identificativo del convoglio.
     * @param tipo    "ENTRATA" oppure "USCITA".
     */
    public void inviaTransito(String trenoId, String tipo) {
        JsonObject transito = new JsonObject();
        transito.addProperty("stazioneId", dbLocale.stazioneId);
        transito.addProperty("trenoId", trenoId);
        transito.addProperty("tipo", tipo);
        transito.addProperty("timestamp", Instant.now().toString());

        if (!connessione.collegata()) {
            LOG.warning("🔌 Offline: evento 'transit' salvato nel buffer locale.");
        }
        localBuffer.add("transit", transito.toString(), null, trenoId);
    }
}
