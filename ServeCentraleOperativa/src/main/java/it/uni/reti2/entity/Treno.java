package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "Treni")
public class Treno extends PanacheEntityBase {
    @Id
    @Column(name = "id_convoglio", length = 50)
    public String id;

    @Column(name = "stato", nullable = false, length = 30)
    public String stato;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "itinerario")
    public Itinerario itinerario;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "PosizioneAttualeTrattaOStazione")
    public Tratta posizioneAttualeTratta;

    // In-memory volatile fields (for UI and logic) not in schema.sql
    @Transient
    public String nome;
    @Transient
    public double latitudine;
    @Transient
    public double longitudine;
    @Transient
    public double velocita;
    @Transient
    public Instant ultimoAggiornamento;
    
    public Treno() {}
    public Treno(String id, String nome) {
        this.id = id;
        this.nome = nome;
        this.stato = "attivo";
    }
}
