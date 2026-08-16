package it.uni.reti2.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "Transiti")
public class Transito extends PanacheEntityBase {
    @Id
    @Column(name = "id_transito", length = 50)
    public String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "id_stazione", nullable = false)
    public Stazione stazione;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "id_convoglio", nullable = false)
    public Treno treno;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "id_Tratta")
    public Tratta tratta;

    @Column(name = "tempoEntrata", nullable = false)
    public Instant tempoEntrata;

    @Column(name = "tempoUscita")
    public Instant tempoUscita;

    /**
     * Ritardo del convoglio (in minuti) NELL'ISTANTE del passaggio.
     * Va congelato qui: il ritardo che sta in cache cambia di continuo, e lo storico
     * deve dire quanto era in ritardo il treno quando è passato, non quanto lo è adesso.
     */
    @Column(name = "ritardoMinuti")
    public Integer ritardoMinuti;

    // For compatibility with old logic
    @Transient
    public String trenoId;
    @Transient
    public String stazioneId;
    @Transient
    public String tipo;
    @Transient
    public Instant timestamp;
}
