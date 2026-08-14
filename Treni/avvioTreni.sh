#!/usr/bin/env bash
# File con i nomi dei treni da avviare (uno per riga)
NODI_FILE="$(dirname "$0")/treni.conf"
if [[ ! -f "$NODI_FILE" ]]; then
    echo "❌ Non trovo il file $NODI_FILE"
    exit 1
fi
# Lista degli argomenti da passare al main() del JAR, letta dal file
ARGS=()
while IFS= read -r riga || [[ -n "$riga" ]]; do
    # salto le righe vuote e i commenti
    [[ -z "${riga// }" || "$riga" == \#* ]] && continue
    ARGS+=("$riga")
done < "$NODI_FILE"
if [[ ${#ARGS[@]} -eq 0 ]]; then
    echo "❌ Il file $NODI_FILE non contiene nessun treno"
    exit 1
fi
# Numero di istanze (una per ogni riga letta dal file)
N_INSTANCES=${#ARGS[@]}
# Comando base
JAR_CMD="java -jar target/quarkus-app/quarkus-run.jar"
# Porta di partenza (prima istanza su 9080, poi 9081...)
# NB: le stazioni usano 8080+, quindi i treni partono da 9080 per non collidere
BASE_PORT=9080
# Directory per i log
LOG_DIR="$(pwd)/logs"
mkdir -p "$LOG_DIR"
# Array per i PID
PIDS=()
# Pulizia al Ctrl+C
cleanup() {
    echo ""
    echo "🛑 Arresto di ${#PIDS[@]} processi..."
    for pid in "${PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            echo "  → ucciso PID $pid"
        fi
    done
    exit 0
}
trap cleanup SIGINT SIGTERM
# Avvia ogni istanza
for i in $(seq 0 $((N_INSTANCES - 1))); do
    PORT=$((BASE_PORT + i))
    ARG="${ARGS[$i]}"
    LOG_FILE="${LOG_DIR}/${ARG}.log"
    echo "🚀 Avvio istanza $((i+1)): argomento='$ARG', porta=$PORT, log='$LOG_FILE'"
    QUARKUS_HTTP_PORT=$PORT $JAR_CMD "$ARG" > "$LOG_FILE" 2>&1 &
    PIDS+=($!)
done
echo "✅ Tutti i $N_INSTANCES processi avviati."
echo "PID: ${PIDS[@]}"
echo "Log: $LOG_DIR/<argomento>.log"
echo "Premi Ctrl+C per fermarli tutti."
wait
