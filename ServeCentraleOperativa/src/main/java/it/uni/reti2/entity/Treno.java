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

    // vincoli esterni
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "itinerario")
    public Itinerario itinerario;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "PosizioneAttualeTrattaOStazione")
    public Tratta posizioneAttualeTratta;

// allora il db ha una immagine esatta della chash
    // Campi volatili in-memory (per UI e logica), NON presenti in schema.sql.
    // I nomi sono quelli ESATTI attesi dal frontend nella serializzazione JSON.
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
    /** Ritardo accumulato in minuti (dalla telemetria/passaggi). */
    @Transient
    public int ritardo;
    /** Numero di passeggeri a bordo (simulato dal digital twin). */
    @Transient
    public int passeggeri;
    /** Percentuale di avanzamento sulla tratta corrente (0..100). */
    @Transient
    public double progresso;
    /** ID della stazione in cui si trova il treno (null se in viaggio). */
    @Transient
    public String stazioneCorrente;
    /** ID della prossima stazione dell'itinerario (null se capolinea). */
    @Transient
    public String prossimaStazione;
    /** Direzione di percorrenza dell'itinerario ("andata" o "ritorno"). */
    @Transient
    public String direzione;


    public Treno() {}
    public Treno(String id, String nome) {
        this.id = id;
        this.nome = nome;
        this.stato = "attivo";
    }
}
