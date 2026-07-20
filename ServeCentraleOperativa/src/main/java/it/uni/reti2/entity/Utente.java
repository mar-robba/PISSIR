package it.uni.reti2.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

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

    @Column(name = "matricola", nullable = false, unique = true, length = 50)
    public String matricola;

    /**
     * Password in chiaro dell'utente (semplificazione didattica, verificata al login).
     * Mai serializzata nelle risposte JSON: l'entità Guasto espone l'operatore assegnato.
     */
    @JsonIgnore
    @Column(name = "password", length = 100)
    public String password;
}
