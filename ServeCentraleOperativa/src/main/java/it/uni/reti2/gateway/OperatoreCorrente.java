package it.uni.reti2.gateway;

import io.quarkus.security.identity.SecurityIdentity;
import it.uni.reti2.entity.DatiOperatore;
import it.uni.reti2.entity.Utente;
import it.uni.reti2.persistence.RailwayRepository;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

/**
 * Chi ha fatto <em>questa</em> chiamata.
 *
 * <p>Fino a poco fa il token serviva soltanto al {@link FiltroAutorizzazione} per decidere
 * se il comando poteva passare, e poi l'identità non la guardava più nessuno: né
 * {@code risolviAllarme} né {@code dispacciaManutenzione} scrivevano da nessuna parte chi
 * era stato. Con le assegnazioni degli operatori di RF02.7 quel dato serve, e serve in due
 * posti diversi ({@link AuthController} per il profilo, {@link RestApiGateway} per gli
 * storici): sta qui una volta sola invece che in tutti e due.</p>
 *
 * <p>Il bean è {@code @RequestScoped} perché è esattamente la sua durata: l'identità vale
 * per la richiesta HTTP in corso e non per quella dopo.</p>
 */
@RequestScoped
public class OperatoreCorrente {

    /** Identità costruita da quarkus-oidc a partire dal token presentato. */
    @Inject
    SecurityIdentity identita;

    /** L'anagrafica degli operatori: da lì arrivano l'id "U1" e il nome per esteso. */
    @Inject
    RailwayRepository repository;

    /** {@code true} se la richiesta non porta nessun token valido. */
    public boolean anonimo() {
        return identita == null || identita.isAnonymous();
    }

    /**
     * La matricola di chi ha fatto la chiamata.
     *
     * <p>Keycloak salva gli username in minuscolo (mat001), mentre in tutto il resto del
     * sistema l'operatore è identificato dalla matricola (MAT001). Il claim
     * {@code matricola} arriva dal protocol mapper del realm; se qualcuno crea un utente a
     * mano dalla console senza quell'attributo si ripiega sullo username.</p>
     *
     * @return La matricola, oppure null se la chiamata è anonima.
     */
    public String matricola() {
        if (anonimo()) {
            return null;
        }
        String matricola = claim("matricola");
        if (matricola == null || matricola.isBlank()) {
            matricola = identita.getPrincipal().getName().toUpperCase();
        }
        return matricola;
    }

    /** Il ruolo di realm con cui sta operando: amministratore se ce l'ha, altrimenti tecnico. */
    public String ruolo() {
        if (anonimo()) {
            return null;
        }
        return identita.hasRole(FiltroAutorizzazione.RUOLO_AMMINISTRATORE)
                ? FiltroAutorizzazione.RUOLO_AMMINISTRATORE
                : FiltroAutorizzazione.RUOLO_TECNICO;
    }

    /** La riga di anagrafica corrispondente alla matricola, oppure null se non c'è. */
    public Utente inAnagrafica() {
        String matricola = matricola();
        return matricola != null ? repository.trovaUtentePerMatricola(matricola) : null;
    }

    /**
     * I dati da congelare dentro le righe di storico (RF02.7).
     *
     * <p>Se l'anagrafica ha la riga corrispondente si usano il suo id e il nome per esteso,
     * che è quello che un domani rende leggibile lo storico. Se non ce l'ha si ripiega sulla
     * matricola: è comunque il codice che identifica la persona in Keycloak, e una riga con
     * la sola matricola è infinitamente meglio di nessuna riga.</p>
     *
     * @return I dati dell'operatore, oppure null se la chiamata è anonima.
     */
    public DatiOperatore dati() {
        if (anonimo()) {
            return null;
        }
        String matricola = matricola();
        Utente utente = repository.trovaUtentePerMatricola(matricola);
        String id = utente != null ? utente.id : matricola;
        String nome = utente != null ? (utente.nome + " " + utente.cognome).trim() : matricola;
        return new DatiOperatore(id, nome, matricola, ruolo());
    }

    /**
     * Legge un claim di testo dal token.
     *
     * <p>Il principal è un {@link JsonWebToken} solo quando l'identità arriva davvero da
     * Keycloak: nei test viene simulata con {@code @TestSecurity} e il principal è un
     * oggetto qualsiasi, quindi il controllo di tipo serve per non far esplodere i test.</p>
     *
     * @param nomeClaim Nome del claim dentro il JWT.
     * @return Il valore del claim, oppure null se assente.
     */
    public String claim(String nomeClaim) {
        if (anonimo()) {
            return null;
        }
        Principal principal = identita.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object valore = jwt.getClaim(nomeClaim);
            return valore != null ? valore.toString() : null;
        }
        return null;
    }
}
