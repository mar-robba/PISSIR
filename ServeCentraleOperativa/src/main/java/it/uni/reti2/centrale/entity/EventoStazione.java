package it.uni.reti2.centrale.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "eventi_stazioni")
public class EventoStazione extends PanacheEntity {

    public String stazioneId;
    public String stato;
    public String descrizione;
    public String tipoEvento;
    public LocalDateTime timestamp = LocalDateTime.now();

}
