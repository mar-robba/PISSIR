package it.uni.reti2.entity;


/**
 * Anagrafica degli operatori della Centrale.
 *
 * <p>Da quando l'autenticazione è passata a Keycloak questa tabella NON serve più
 * per il login: le password e i ruoli stanno nel realm "railway" e la Centrale non
 * ne conserva copia (la colonna "password" è infatti sparita). La riga resta perché
 * i guasti hanno una chiave esterna verso l'operatore che li ha presi in carico
 * ({@link Guasto}, {@link StoricoAssegnazioneGuasto}): l'aggancio fra l'utente
 * Keycloak e la riga qui dentro è la matricola, che viaggia nel token come claim
 * omonimo.</p>
 */
public class Utente {
    public String id;
    public String tipo;
    public String nome;
    public String cognome;

    /** Matricola dell'operatore: è il collegamento con l'utente di Keycloak. */
    public String matricola;
}
