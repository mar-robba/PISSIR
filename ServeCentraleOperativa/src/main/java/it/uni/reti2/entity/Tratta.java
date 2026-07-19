package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "Tratte")
public class Tratta extends PanacheEntityBase {
    @Id
    @Column(name = "id_Tratta", length = 50)
    public String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "StazionePartenzaFK", nullable = false)
    public Stazione stazionePartenza;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "StazioneArrivoFK", nullable = false)
    public Stazione stazioneArrivo;
}
