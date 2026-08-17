package it.uni.reti2.eventi;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Un evento derivato letto dal canale condiviso: il nodo dichiara di aver cambiato stato
 * <b>per causa di un altro evento</b>.
 *
 * <p>Non è un guasto e non è telemetria. Non apre righe in {@code Guasti_Pervenuti_da_treni_o_Staz}:
 * aggiorna lo stato del nodo e scrive una riga di storico che porta con sé la causa.</p>
 *
 * @param sorgenteTipo    Tipo del nodo che ha reagito (STAZIONE o TRENO).
 * @param sorgenteId      Identificativo del nodo che ha reagito.
 * @param nuovoStato      Stato dichiarato dal nodo, nel vocabolario del nodo.
 * @param statoPrecedente Stato da cui proviene (può essere null).
 * @param causa           Chi gliel'ha fatto cambiare e a quale catena appartiene.
 * @param motivo          Descrizione leggibile, per il log e per l'operatore.
 * @param attiva          true se il nodo entra nella catena (si blocca), false se ne esce.
 * @param quando          Istante dichiarato dal nodo.
 */
public record EventoDerivato(
        String sorgenteTipo,
        String sorgenteId,
        String nuovoStato,
        String statoPrecedente,
        CausaEvento causa,
        String motivo,
        boolean attiva,
        Instant quando) {

    /**
     * Costruisce l'evento a partire dal JSON arrivato sul canale.
     *
     * @param root Payload già interpretato.
     * @return L'evento, oppure {@code null} se il payload non è un evento derivato utilizzabile.
     */
    public static EventoDerivato daJson(JsonNode root) {
        String sorgenteTipo = root.path(VocabolarioEventi.CAMPO_SORGENTE_TIPO).asText("");
        String sorgenteId = root.path(VocabolarioEventi.CAMPO_SORGENTE_ID).asText("");
        String nuovoStato = root.path(VocabolarioEventi.CAMPO_NUOVO_STATO).asText("");
        String catenaId = root.path(VocabolarioEventi.CAMPO_CATENA).asText("");

        // Senza sorgente, stato o catena il messaggio non è applicabile: la catena è ciò che
        // rende la reazione idempotente, quindi un derivato senza catena verrebbe riapplicato
        // a ogni ripetizione del broker (MQTT consegna at-least-once).
        if (sorgenteTipo.isBlank() || sorgenteId.isBlank() || nuovoStato.isBlank() || catenaId.isBlank()) {
            return null;
        }

        CausaEvento causa = CausaEvento.di(
                root.path(VocabolarioEventi.CAMPO_CAUSA_TIPO).asText(""),
                root.path(VocabolarioEventi.CAMPO_CAUSA_ID).asText(""),
                catenaId);

        return new EventoDerivato(
                sorgenteTipo,
                sorgenteId,
                nuovoStato,
                vuotoComeNull(root.path(VocabolarioEventi.CAMPO_STATO_PRECEDENTE).asText("")),
                causa,
                vuotoComeNull(root.path(VocabolarioEventi.CAMPO_MOTIVO).asText("")),
                root.path(VocabolarioEventi.CAMPO_ATTIVA).asBoolean(true),
                leggiTimestamp(root));
    }

    /** @return L'identificativo della catena a cui l'evento appartiene. */
    public String catenaId() {
        return causa != null ? causa.catenaId() : null;
    }

    /** @return La chiave del nodo che ha reagito, come la usa il registro delle catene. */
    public String nodo() {
        return sorgenteTipo + ":" + sorgenteId;
    }

    private static Instant leggiTimestamp(JsonNode root) {
        try {
            if (root.hasNonNull(VocabolarioEventi.CAMPO_TIMESTAMP)) {
                return Instant.parse(root.get(VocabolarioEventi.CAMPO_TIMESTAMP).asText());
            }
        } catch (Exception ignorata) {
            // timestamp illeggibile: vale l'istante di arrivo
        }
        return Instant.now();
    }

    private static String vuotoComeNull(String valore) {
        return valore == null || valore.isBlank() ? null : valore;
    }
}
