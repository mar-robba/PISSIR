package it.uni.reti2;

/**
 * Interfaccia per il sistema di Keep-Alive.
 * Stabilisce il contratto per la generazione dei payload di heartbeat, 
 * che confermano la vitalità del nodo.
 */
public interface HeartbeatKA {
    
    /**
     * Genera la stringa JSON per il segnale di "battito cardiaco".
     *
     * @param stazioneId ID univoco del nodo che invia.
     * @param stato Lo stato corrente del nodo (es. "ONLINE").
     * @return Una stringa formattata contenente il payload.
     */
    String generatePayload(String stazioneId, String stato);
}
