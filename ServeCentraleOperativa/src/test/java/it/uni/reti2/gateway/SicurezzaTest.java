package it.uni.reti2.gateway;

import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.PlainJWT;
import it.uni.reti2.CentraleDiProva;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Prova la verifica dei token fatta con pac4j e le regole di FiltroAutorizzazione.
 *
 * <p>I controlli sono codice della Centrale (SicurezzaKeycloak), e ognuno ha il suo caso:
 * un token scaduto, uno per un'altra applicazione, uno di un altro realm, uno firmato da
 * chi non e' Keycloak, uno non firmato affatto. Tutti devono prendere 401.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SicurezzaTest {

    private CentraleDiProva centrale;

    @BeforeAll
    void accendi() throws Exception {
        centrale = CentraleDiProva.avvia();
    }

    @AfterAll
    void spegni() throws Exception {
        centrale.close();
    }

    private void rifiutato(String token, String motivo) throws Exception {
        CentraleDiProva.Risposta risposta = centrale.get("/api/dashboard", token);
        Assertions.assertEquals(401, risposta.stato(), motivo);
        Assertions.assertEquals("Autenticazione richiesta: effettuare il login",
                risposta.json().getAsJsonObject().get("errore").getAsString());
    }

    @Test
    void unTokenBuonoPassa() throws Exception {
        Assertions.assertEquals(200, centrale.get("/api/dashboard", centrale.admin()).stato());
    }

    @Test
    void unTokenScadutoNonPassa() throws Exception {
        rifiutato(centrale.token("mat001", CentraleDiProva.EMITTENTE, "railway-centrale", -60,
                centrale.chiaveDelRealm(), FiltroAutorizzazione.RUOLO_AMMINISTRATORE), "token scaduto");
    }

    @Test
    void unTokenPerUnAltraApplicazioneNonPassa() throws Exception {
        rifiutato(centrale.token("mat001", CentraleDiProva.EMITTENTE, "un-altro-servizio", 300,
                centrale.chiaveDelRealm(), FiltroAutorizzazione.RUOLO_AMMINISTRATORE), "audience sbagliata");
    }

    @Test
    void unTokenDiUnAltroRealmNonPassa() throws Exception {
        rifiutato(centrale.token("mat001", "https://keycloak.di.prova/realms/altro", "railway-centrale", 300,
                centrale.chiaveDelRealm(), FiltroAutorizzazione.RUOLO_AMMINISTRATORE), "emittente sbagliato");
    }

    @Test
    void unTokenFirmatoDaUnAltraChiaveNonPassa() throws Exception {
        // Stesso identificativo della chiave del realm, ma e' un'altra coppia RSA: la firma
        // non torna.
        RSAKey falsa = new RSAKeyGenerator(2048).keyID("chiave-di-prova").keyUse(KeyUse.SIGNATURE).generate();
        rifiutato(centrale.token("mat001", CentraleDiProva.EMITTENTE, "railway-centrale", 300,
                falsa, FiltroAutorizzazione.RUOLO_AMMINISTRATORE), "firma di un'altra chiave");
    }

    @Test
    void unTokenNonFirmatoNonPassa() throws Exception {
        // Un JWT con "alg": "none": pac4j lo accetterebbe solo se non avesse nessuna chiave,
        // ed e' il motivo per cui SicurezzaKeycloak rifiuta tutto finche' le chiavi non ci sono.
        String nonFirmato = new PlainJWT(CentraleDiProva.claims("mat001", CentraleDiProva.EMITTENTE,
                "railway-centrale", 300, FiltroAutorizzazione.RUOLO_AMMINISTRATORE)).serialize();
        rifiutato(nonFirmato, "token non firmato");
    }

    @Test
    void unTokenManomessoNonPassa() throws Exception {
        String buono = centrale.admin();
        String[] parti = buono.split("\\.");
        // Si cambia il contenuto (un tecnico che si promuove amministratore) lasciando la firma
        String contenuto = new String(java.util.Base64.getUrlDecoder().decode(parti[1]));
        String promosso = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(contenuto.replace("mat001", "mat666").getBytes());
        rifiutato(parti[0] + "." + promosso + "." + parti[2], "contenuto manomesso");
    }

    @Test
    void senzaChiaviDelRealmNonPassaNiente() {
        // Keycloak non ancora raggiungibile: nessun token, neanche buono, deve passare
        SicurezzaKeycloak senzaChiavi = new SicurezzaKeycloak();
        Assertions.assertFalse(senzaChiavi.autentica(null));
    }

    @Test
    void unUtenteSenzaRuoliDelProgettoPrende403() throws Exception {
        CentraleDiProva.Risposta risposta = centrale.get("/api/dashboard", centrale.token("mat009"));
        Assertions.assertEquals(403, risposta.stato());
        Assertions.assertEquals("Utente senza ruoli abilitati sulla Centrale Operativa",
                risposta.json().getAsJsonObject().get("errore").getAsString());
    }

    @Test
    void ilTecnicoNonToccaLAnagraficaNeancheNeiPercorsiLunghi() throws Exception {
        String tecnico = centrale.tecnico();
        Assertions.assertEquals(403, centrale.delete("/api/tratte/IT1", tecnico).stato());
        Assertions.assertEquals(403, centrale.put("/api/tratte-elementari/T1", "{}", tecnico).stato());
        Assertions.assertEquals(403, centrale.put("/api/treni/TRN1", "{\"stato\":\"fermo\"}", tecnico).stato());
        // i tre comandi operativi invece gli sono permessi (qui rispondono 404: non esiste niente)
        Assertions.assertEquals(404, centrale.post("/api/allarmi/nessuno/risolvi", null, tecnico).stato());
        Assertions.assertEquals(404, centrale.post("/api/treni/nessuno/sopprimi", null, tecnico).stato());
        Assertions.assertEquals(404, centrale.post("/api/stazioni/nessuna/manutenzione", null, tecnico).stato());
    }

    @Test
    void gliEndpointDeiNodiDiCampoRestanoAperti() throws Exception {
        // Il digital twin non fa login su Keycloak: la sua GET dell'itinerario e quella della
        // prossima stazione passano senza token (qui 404, perche' il treno non esiste).
        Assertions.assertEquals(404, centrale.get("/api/treni/NESSUNO/itinerario", null).stato());
        Assertions.assertEquals(404, centrale.get("/api/prossima-stazione?treno=NESSUNO&stazione=S1", null).stato());
        // Ma solo in lettura
        Assertions.assertEquals(401, centrale.post("/api/treni/NESSUNO/itinerario", "{}", null).stato());
    }

    @Test
    void laPreflightDelBrowserPassaSenzaToken() throws Exception {
        CentraleDiProva.Risposta preflight = centrale.invia("OPTIONS", "/api/stazioni", null, null,
                "Origin", "https://localhost:5173",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type");
        Assertions.assertEquals(200, preflight.stato());
        Assertions.assertTrue(preflight.intestazioni().firstValue("Access-Control-Allow-Origin").isPresent(),
                "senza questa intestazione il browser blocca la chiamata vera");

        CentraleDiProva.Risposta vera = centrale.invia("GET", "/api/dashboard", null, centrale.admin(),
                "Origin", "https://localhost:5173");
        Assertions.assertEquals(200, vera.stato());
        Assertions.assertTrue(vera.intestazioni().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    @Test
    void unCorpoCheNonEJsonEUn400() throws Exception {
        CentraleDiProva.Risposta risposta = centrale.post("/api/stazioni", "{ questo non e' json", centrale.admin());
        Assertions.assertEquals(400, risposta.stato());
    }
}
