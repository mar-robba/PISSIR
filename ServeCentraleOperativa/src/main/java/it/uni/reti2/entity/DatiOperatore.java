package it.uni.reti2.entity;

/**
 * Chi ha dato il comando, nei termini che servono agli storici.
 *
 * <p>Non è un'entità e non ha una tabella: è il gruppetto di dati sull'operatore che le
 * righe di storico si portano dentro. Gli storici di RF02.7 non hanno chiavi esterne,
 * quindi dell'operatore non basta l'identificativo: servono anche nome, matricola e ruolo,
 * congelati com'erano quel giorno.</p>
 *
 * <p>Lo compone il gateway leggendo il token di Keycloak
 * ({@code RestApiGateway.operatoreCollegato}) e non la tabella Utenti, perché chi ha fatto
 * la chiamata è un fatto della richiesta HTTP: l'anagrafica serve solo a recuperare l'id
 * "U1" e il nome per esteso, e può benissimo non avere la riga corrispondente (un utente
 * creato a mano dalla console di Keycloak, oppure i test, che girano su un database senza
 * anagrafica precaricata). In quel caso al posto dell'id e del nome resta la matricola,
 * che è comunque sufficiente a sapere chi è stato.</p>
 *
 * @param id        Id in anagrafica ("U1"), oppure la matricola se quella riga non c'è.
 * @param nome      Nome e cognome per esteso, oppure la matricola.
 * @param matricola Matricola: è il codice che lega l'operatore all'utente di Keycloak.
 * @param ruolo     Ruolo di realm con cui ha dato il comando (tecnico o amministratore).
 * @see it.uni.reti2.entity.StoricoAssegnazioneGuasto
 * @see it.uni.reti2.entity.StoricoInterventoManutenzione
 */
public record DatiOperatore(String id, String nome, String matricola, String ruolo) {
}
