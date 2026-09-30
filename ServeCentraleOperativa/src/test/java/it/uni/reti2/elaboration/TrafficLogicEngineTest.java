package it.uni.reti2.elaboration;

import it.uni.reti2.CentraleDiProva;
import it.uni.reti2.entity.Guasto;
import it.uni.reti2.entity.StatiCanonici;
import it.uni.reti2.entity.Stazione;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Il caricamento della cache all'avvio: e' l'unico momento in cui lo stato si ricostruisce dal
 * database invece che dagli eventi.
 */
public class TrafficLogicEngineTest {

    @Test
    void unInterventoApertoAlloSpegnimentoRiprendeAllAvvio() throws Exception {
        try (CentraleDiProva centrale = CentraleDiProva.avvia()) {
            String admin = centrale.admin();
            centrale.post("/api/stazioni", "{\"id\":\"A\",\"nome\":\"Alfa\"}", admin);
            centrale.post("/api/stazioni", "{\"id\":\"B\",\"nome\":\"Bravo\"}", admin);
            centrale.heartbeat("{\"stazioneId\":\"A\",\"stato\":\"ONLINE\"}");
            centrale.heartbeat("{\"stazioneId\":\"B\",\"stato\":\"ONLINE\"}");
            centrale.alert("{\"tipoEvento\":\"GUASTO\",\"origine\":\"STAZIONE\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"A\",\"severita\":\"CRITICAL\",\"messaggio\":\"x\"}");
            Assertions.assertEquals(200, centrale.post("/api/stazioni/A/manutenzione", null, admin).stato());

            // Un guasto vecchio, di prima che ci fossero ora di apertura e severita'
            Guasto vecchio = new Guasto();
            vecchio.id = "G-VECCHIO";
            vecchio.sorgenteTipo = "STAZIONE";
            vecchio.sorgenteId = "B";
            vecchio.tipo = "stazione_guasta";
            centrale.db.inTransazione(() -> centrale.repository.salvaGuasto(vecchio));

            // "Riavvio": una cache nuova sullo stesso database
            TrafficLogicEngine riavviata = new TrafficLogicEngine();
            riavviata.repository = centrale.repository;
            riavviata.carica();

            Stazione a = riavviata.getStazione("A");
            Assertions.assertEquals(StatiCanonici.STAZIONE_MANUTENZIONE, a.stato,
                    "la squadra e' ancora sui binari: la stazione non deve riaprirsi da sola");
            Assertions.assertNull(a.ultimoHeartbeat);
            // B ha un guasto aperto ma nessun intervento: riparte OFFLINE come tutte
            Assertions.assertEquals(StatiCanonici.STAZIONE_OFFLINE, riavviata.getStazione("B").stato);
            Guasto completato = centrale.repository.trovaGuasto("G-VECCHIO");
            Assertions.assertNotNull(completato.timestamp, "il guasto vecchio riceve l'ora di apertura");
            Assertions.assertEquals("warning", completato.severita);
        }
    }
}
