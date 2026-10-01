package it.uni.reti2.persistence;

import it.uni.reti2.CentraleDiProva;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Le transazioni JDBC di {@link Database}: commit, rollback e transazioni annidate
 * indipendenti.
 */
public class DatabaseTest {

    private CentraleDiProva centrale;

    @BeforeEach
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
    }

    @AfterEach
    void spegni() throws Exception {
        centrale.close();
    }

    private long stazioni() throws Exception {
        return centrale.conta("SELECT COUNT(*) FROM Stazione");
    }

    private void nuovaStazione(String id) {
        it.uni.reti2.entity.Stazione stazione = new it.uni.reti2.entity.Stazione(id, "Stazione " + id, 45, 9, 1);
        centrale.repository.salvaStazione(stazione);
    }

    @Test
    void ilLavoroRiuscitoVaInCommit() throws Exception {
        centrale.db.inTransazione(() -> {
            nuovaStazione("S1");
            nuovaStazione("S2");
        });
        Assertions.assertEquals(2, stazioni());
    }

    @Test
    void unEccezioneAnnullaTuttoIlLavoro() throws Exception {
        Assertions.assertThrows(RuntimeException.class, () -> centrale.db.inTransazione(() -> {
            nuovaStazione("S1");
            nuovaStazione("S1"); // chiave duplicata: la seconda INSERT fallisce
        }));
        Assertions.assertEquals(0, stazioni(), "anche la prima INSERT deve essere annullata");
    }

    @Test
    void unaTransazioneDentroUnAltraEIndipendente() throws Exception {
        Assertions.assertThrows(IllegalStateException.class, () -> centrale.db.inTransazione(() -> {
            nuovaStazione("ESTERNA");
            centrale.db.inTransazione(() -> nuovaStazione("INTERNA")); // va in commit da sola
            throw new IllegalStateException("la esterna fallisce dopo");
        }));
        Assertions.assertEquals(1, stazioni());
        Assertions.assertNotNull(centrale.repository.trovaStazione("INTERNA"));
        Assertions.assertNull(centrale.repository.trovaStazione("ESTERNA"));
    }

    @Test
    void loSchemaSiPuoRieseguireSenzaToccareIDati() throws Exception {
        nuovaStazione("S1");
        centrale.db.creaTabelleMancanti(); // come a ogni riavvio della Centrale
        Assertions.assertEquals(1, stazioni());
    }
}
