package it.uni.reti2.entity;

import java.time.Instant;
public class Transito {
    public String id;
    public Stazione stazione;
    public Treno treno;
    public Tratta tratta;
    public Instant tempoEntrata;
    public Instant tempoUscita;

    /**
     * Ritardo del convoglio (in minuti) NELL'ISTANTE del passaggio.
     * Va congelato qui: il ritardo che sta in cache cambia di continuo, e lo storico
     * deve dire quanto era in ritardo il treno quando è passato, non quanto lo è adesso.
     */
    public Integer ritardoMinuti;

    // For compatibility with old logic
    public String trenoId;
    public String stazioneId;
    public String tipo;
    public Instant timestamp;
}
