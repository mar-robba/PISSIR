package it.uni.reti2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** La Centrale di prova si accende, crea le tabelle e risponde. */
public class AvvioTest {

    @Test
    void laCentraleSiAccendeERisponde() throws Exception {
        try (CentraleDiProva centrale = CentraleDiProva.avvia()) {
            CentraleDiProva.Risposta dashboard = centrale.get("/api/dashboard", centrale.admin());
            Assertions.assertEquals(200, dashboard.stato(), dashboard.corpo());
            System.out.println(dashboard.corpo());
            CentraleDiProva.Risposta creata = centrale.post("/api/stazioni",
                    "{\"id\":\"S1\",\"nome\":\"Milano\",\"latitudine\":45.1,\"longitudine\":9.2,\"binari\":4}", centrale.admin());
            System.out.println(creata.stato() + " " + creata.corpo());
            Assertions.assertEquals(201, creata.stato());
            centrale.heartbeat("{\"stazioneId\":\"S1\",\"stato\":\"ONLINE\"}");
            System.out.println(centrale.eventiWeb);
            System.out.println(centrale.get("/api/stazioni", centrale.tecnico()).corpo());
        }
    }
}
