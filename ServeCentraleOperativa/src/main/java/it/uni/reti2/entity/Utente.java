package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

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
@Entity
@Table(name = "Utenti")
public class Utente extends PanacheEntityBase {
    @Id
    @Column(name = "id_utente", length = 50)
    public String id;

    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    @Column(name = "cognome", nullable = false, length = 100)
    public String cognome;

    /** Matricola dell'operatore: è il collegamento con l'utente di Keycloak. */
    @Column(name = "matricola", nullable = false, unique = true, length = 50)
    public String matricola;
}
