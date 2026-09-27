# Centrale Operativa

Il cervello del sistema: raccoglie quello che arriva da stazioni e treni via MQTT,
lo salva su Postgres e lo ripubblica alla web app via REST + WebSocket.
Istruzioni generali di avvio nel `README.md` della radice.

## Avvio

```bash
./mvnw quarkus:dev                          # in chiaro, porta 8781
./mvnw quarkus:dev -Dquarkus.profile=tls    # cifrato, porta 8444 (serve alla web app)
```

Prima devono girare Postgres, Keycloak e Mosquitto (`docker-compose up -d` in radice)
e il database va popolato con `./popola_db.sh`: le tabelle le crea Hibernate, ma
l'anagrafica no.

## Cosa espone

- REST sotto `/api` — `stazioni`, `treni`, `tratte`, `tratte-elementari` (CRUD),
  piu' `dashboard`, `transiti`, `allarmi`, `prossima-stazione`,
  `treni/{id}/itinerario` e i comandi `allarmi/{id}/risolvi`,
  `treni/{id}/sopprimi`, `stazioni/{id}/manutenzione`.
- WebSocket `/ws/realtime`: ogni evento elaborato viene spinto al frontend.

Tutte le API vogliono un token di Keycloak (`Authorization: Bearer ...`); i ruoli
si leggono da `realm_access.roles` (`amministratore` / `tecnico`).

## MQTT

Ascolta `railway/train/+/telemetry`, `railway/station/+/transit`,
`railway/station/+/heartbeat` e le richieste di validazione degli ID; pubblica su
`railway/alerts` (QoS 1). L'albero completo dei topic sta in
`BrokerMosquitto/doc/alberoDeiTopic.md`.

## Codice

- `ingestion/` — arrivo dei messaggi MQTT e pubblicazione degli alert
- `elaboration/` — logica: guasti, monitoraggio stazioni/treni, itinerari, previsioni
- `persistence/` + `entity/` — repository e tabelle (comprese le `Storico_*`)
- `gateway/` — REST, WebSocket e controllo dei permessi
