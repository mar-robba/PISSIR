package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Entità JPA per la persistenza dei dati di <strong>telemetria dei treni</strong>.
 *
 * <p>Mappa la tabella {@code telemetria_treni}. Ogni record archivia un
 * frame telemetrico ricevuto dalla Centrale Operativa, utile per ricostruire
 * lo storico degli eventi operativi e diagnostici dei convogli.</p>
 *
 * <p>Estende {@link PanacheEntity} (non {@code PanacheEntityBase}), pertanto
 * l'ID è un {@code Long} auto-generato fornito dalla superclasse.</p>
 */
@Entity
@Table(name = "telemetria_treni")
public class TelemetriaTreno extends PanacheEntity {

    /** Identificativo del treno a cui si riferisce questo dato telemetrico. */
    public String trenoId;

    /** Stato operativo del treno al momento dell'evento (es. "IN_VIAGGIO", "FERMO"). */
    public String stato;

    /** Note aggiuntive o descrizione dell'evento telemetrico. */
    public String descrizione;

    /** Istante di arrivo del dato telemetrico in centrale. Inizializzato automaticamente. */
    public LocalDateTime timestamp = LocalDateTime.now();

}
