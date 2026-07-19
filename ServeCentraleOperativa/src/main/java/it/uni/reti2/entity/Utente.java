package it.uni.reti2.entity;

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
}
