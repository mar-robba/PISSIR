#!/bin/bash
# Esegue populate_db.sql sul database centrale (il container railway-postgres
# avviato dal docker-compose nella radice del progetto).
#
# Uso:
#   $ ./popola_db.sh                  # esegue populate_db.sql
#   $ ./popola_db.sh altro_file.sql   # esegue un altro script SQL
#
# Le tabelle le crea Hibernate al primo avvio della Centrale, quindi prima di
# lanciare questo script conviene aver fatto partire almeno una volta
# ServeCentraleOperativa (./mvnw quarkus:dev).

set -e

# Cartella in cui si trova questo script, cosi' funziona anche chiamandolo da fuori
CARTELLA="$(cd "$(dirname "$0")" && pwd)"

CONTAINER="${CONTAINER_DB:-railway-postgres}"
UTENTE="${POSTGRES_USER:-postgres}"
DATABASE="${POSTGRES_DB:-railway}"
SCRIPT_SQL="${1:-$CARTELLA/populate_db.sql}"

if [ ! -f "$SCRIPT_SQL" ]; then
  echo "Errore: non trovo il file SQL '$SCRIPT_SQL'"
  exit 1
fi

# Il container deve essere in esecuzione, altrimenti docker exec fallisce con un
# messaggio poco chiaro
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Errore: il container '$CONTAINER' non e' in esecuzione."
  echo "Avvialo con:  docker-compose up -d"
  exit 1
fi

echo "Eseguo $(basename "$SCRIPT_SQL") su $DATABASE (container $CONTAINER)..."

# ON_ERROR_STOP=1: se una query fallisce si ferma subito invece di tirare avanti
docker exec -i "$CONTAINER" \
  psql -v ON_ERROR_STOP=1 -U "$UTENTE" -d "$DATABASE" < "$SCRIPT_SQL"

echo
echo "Fatto. Contenuto delle tabelle di anagrafica:"
docker exec -i "$CONTAINER" psql -U "$UTENTE" -d "$DATABASE" -c "
  SELECT 'stazioni'  AS tabella, COUNT(*) FROM Stazione
  UNION ALL SELECT 'tratte',     COUNT(*) FROM Tratte
  UNION ALL SELECT 'itinerari',  COUNT(*) FROM Itinerari
  UNION ALL SELECT 'treni',      COUNT(*) FROM Treni
  UNION ALL SELECT 'utenti',     COUNT(*) FROM Utenti;"
