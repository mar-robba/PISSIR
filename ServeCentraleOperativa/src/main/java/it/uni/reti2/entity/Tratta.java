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

    /**
     * Tempo di percorrenza nominale della tratta in minuti.
     * Usato dal digital twin dei treni per simulare il viaggio e dal
     * frontend per la colonna "travelTimes" della pagina Gestione Tratte.
     */
    @Column(name = "tempoPercorrenzaMinuti")
    public Integer tempoPercorrenzaMinuti = 15;

    /**
     * Etichetta leggibile della tratta, del tipo "Milano Centrale -> Bologna Centrale".
     * La copiano dentro di sé le righe di storico, che dopo RF02.7 non hanno più una
     * chiave esterna con cui risalire alle stazioni: il solo id ("T1_MI_BO") non dice
     * niente a chi rilegge lo storico, e la tratta nel frattempo può essere stata
     * eliminata dalla rete.
     */
    public String descrizione() {
        String partenza = stazionePartenza != null ? stazionePartenza.nome : "?";
        String arrivo = stazioneArrivo != null ? stazioneArrivo.nome : "?";
        return partenza + " -> " + arrivo;
    }
}
