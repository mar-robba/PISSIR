#!/bin/bash
# Prepara il database centrale (il container railway-postgres avviato dal
# docker-compose nella radice del progetto) con i dati demo di populate_db.sql.
#
# Uso:
#   $ ./popola_db.sh                  # tabelle + dati demo di populate_db.sql
#   $ ./popola_db.sh altro_file.sql   # tabelle + un altro script SQL
#
# Va lanciato una volta sola, subito dopo "docker-compose up -d" e PRIMA di
# accendere la Centrale. Fa due cose:
#   1. crea le tabelle che mancano eseguendo lo schema.sql della Centrale (lo
#      stesso file che la Centrale esegue a ogni avvio: solo CREATE ... IF NOT
#      EXISTS, quindi rieseguirlo non cambia niente);
#   2. esegue populate_db.sql in un'unica transazione: se una riga fallisce non
#      entra niente e il database resta vuoto come prima.
# L'ordine conta perche' la Centrale legge l'anagrafica UNA volta, all'avvio: se
# era gia' accesa va spenta e riaccesa, altrimenti non vede i dati nuovi.

set -e

# Cartella in cui si trova questo script, cosi' funziona anche chiamandolo da fuori
CARTELLA="$(cd "$(dirname "$0")" && pwd)"

CONTAINER="${CONTAINER_DB:-railway-postgres}"
UTENTE="${POSTGRES_USER:-postgres}"
DATABASE="${POSTGRES_DB:-railway}"
SCHEMA_SQL="$CARTELLA/ServeCentraleOperativa/src/main/resources/schema.sql"
SCRIPT_SQL="${1:-$CARTELLA/populate_db.sql}"

for file in "$SCHEMA_SQL" "$SCRIPT_SQL"; do
  if [ ! -f "$file" ]; then
    echo "Errore: non trovo il file SQL '$file'"
    exit 1
  fi
done

# Il container deve essere in esecuzione, altrimenti docker exec fallisce con un
# messaggio poco chiaro
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Errore: il container '$CONTAINER' non e' in esecuzione."
  echo "Avvialo con:  docker-compose up -d"
  exit 1
fi

# Appena creato, il container ci mette qualche secondo prima di accettare
# connessioni: si aspetta al massimo 30 secondi
for tentativo in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -q -U "$UTENTE" -d "$DATABASE"; then
    break
  fi
  if [ "$tentativo" -eq 30 ]; then
    echo "Errore: Postgres non risponde dopo 30 secondi."
    exit 1
  fi
  sleep 1
done

# 1. Tabelle. PGOPTIONS zittisce i NOTICE "relation ... already exists, skipping"
# che Postgres stampa per ogni CREATE ... IF NOT EXISTS su un database gia' pronto.
echo "Creo le tabelle che mancano (schema.sql della Centrale)..."
docker exec -i -e PGOPTIONS='-c client_min_messages=warning' "$CONTAINER" \
  psql -q -v ON_ERROR_STOP=1 -U "$UTENTE" -d "$DATABASE" < "$SCHEMA_SQL"

# 2. I dati demo vanno in un database vuoto. Su uno gia' usato gli INSERT
# fallirebbero sulle chiavi gia' presenti, oppure si mescolerebbero con gli
# itinerari modificati dalla web app. Con un file passato a mano invece si
# lascia fare: chi lo scrive sa cosa contiene.
if [ -z "$1" ]; then
  STAZIONI=$(docker exec "$CONTAINER" psql -tA -U "$UTENTE" -d "$DATABASE" \
    -c "SELECT COUNT(*) FROM Stazione")
  if [ "$STAZIONI" != "0" ]; then
    echo "Il database contiene gia' $STAZIONI stazioni: i dati demo si caricano solo su un database vuoto."
    echo "Per ripartire da zero (si perde TUTTO il contenuto, storici compresi):"
    echo "  docker-compose down -v && docker-compose up -d && ./popola_db.sh"
    exit 1
  fi
fi

echo "Eseguo $(basename "$SCRIPT_SQL") su $DATABASE (container $CONTAINER)..."

# ON_ERROR_STOP=1: se una query fallisce si ferma subito invece di tirare avanti.
# --single-transaction: tutto il file e' una transazione sola, quindi un errore
# a meta' annulla anche le righe gia' inserite.
docker exec -i "$CONTAINER" \
  psql -q -v ON_ERROR_STOP=1 --single-transaction -U "$UTENTE" -d "$DATABASE" < "$SCRIPT_SQL"

echo
echo "Fatto. Contenuto delle tabelle di anagrafica:"
docker exec "$CONTAINER" psql -U "$UTENTE" -d "$DATABASE" -c "
  SELECT 'stazioni'  AS tabella, COUNT(*) FROM Stazione
  UNION ALL SELECT 'tratte',     COUNT(*) FROM Tratte
  UNION ALL SELECT 'itinerari',  COUNT(*) FROM Itinerari
  UNION ALL SELECT 'treni',      COUNT(*) FROM Treni
  UNION ALL SELECT 'utenti',     COUNT(*) FROM Utenti;"
