# Nodo Stazione

Un processo per ogni stazione: legge i sensori di binario, manda transiti e
heartbeat alla Centrale e reagisce agli alert. Istruzioni generali di avvio nel
`README.md` della radice.

## Avvio

```bash
./mvnw quarkus:dev -Dstazione.id=MI -Dquarkus.http.port=8081
./mvnw quarkus:dev -Dstazione.id=MI -Dquarkus.http.port=8081 -Dquarkus.profile=tls
```

L'ID deve esistere nel database centrale (`MI, TO, FI, BO, PA, BE, CA, PAL` con i
dati attuali), altrimenti il nodo si chiude subito. Ogni istanza vuole una porta
HTTP sua; per lanciarne tante insieme c'e' `./avvioStazioni.sh`, che legge gli ID
da `stazioni.conf` e scrive un log per istanza in `logs/`.

## Sensori

Due sensori simulati per stazione (`<ID>-BIN1-IN` e `-OUT`) che battono ogni 5s.
Se uno tace per piu' di `sensori.timeout.secondi` (30) la stazione si dichiara
GUASTA e torna ONLINE da sola quando i battiti riprendono.

```bash
curl http://localhost:8081/stazione/sensori
curl -X POST http://localhost:8081/stazione/sensori/IN/stop
curl -X POST http://localhost:8081/stazione/sensori/IN/start
```

Altri endpoint sotto `/stazione`: `info`, `stato`, `treni`, `buffer`,
`persistenza`, `rete/offline` e `rete/online` per simulare la caduta della rete.

## MQTT

Pubblica `railway/station/{id}/heartbeat` (ogni 10s) e
`railway/station/{id}/transit`; ascolta `railway/alerts` e
`railway/treno-simulatoriSensori/+`, filtrando i messaggi col proprio ID.

## Persistenza locale

SQLite in `data/stazione-<ID>.db` con stato, treni sui binari e coda degli eventi
non ancora spediti, cosi' un riavvio (o la rete giu') non li perde. Per ripartire
puliti si cancella il file oppure si usa `-Dstazione.db.abilitata=false`.
