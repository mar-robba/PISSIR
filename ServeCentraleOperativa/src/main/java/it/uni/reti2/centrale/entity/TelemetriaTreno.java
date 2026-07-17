package it.uni.reti2.centrale.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "telemetria_treni")
public class TelemetriaTreno extends PanacheEntity {

    public String trenoId;
    public String stato;
    public String descrizione;
    public LocalDateTime timestamp = LocalDateTime.now();

}
