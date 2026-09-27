# Broker MQTT (Mosquitto)

Il canale su cui Centrale, Stazioni e Treni si scambiano i messaggi. Non si
avvia a mano: parte col `docker-compose up -d` della radice, con questa cartella
montata dentro il container (`config/`, `data/`, `log/`, `tls/certs/`).

## Porte

| Porta | Cosa | Chi la usa |
|---|---|---|
| 1883 | MQTT in chiaro | i nodi avviati senza profilo `tls` |
| 8883 | MQTT su TLS | i nodi avviati con `-Dquarkus.profile=tls` |
| 9001 | MQTT su WebSocket | la web app dal browser |

## Certificati

```bash
cd tls && ./gen-certs.sh
```

Da lanciare **prima** del docker-compose: lo script crea la CA privata del
progetto e i certificati di broker, Centrale, Keycloak e web app. Tutta la
catena TLS usa questa stessa CA, quindi `ca.crt` va importato nel browser (o
passato con `--cacert` da curl).

## Configurazione

`config/mosquitto.conf`: persistenza attiva, autosave ogni 60s, code fino a 5000
messaggi e sessioni persistenti che scadono dopo un giorno. Serve a garantire la
consegna degli alert (pubblicati in QoS 1) anche a un nodo che in quel momento
era spento; telemetria e heartbeat sono QoS 0 e non vengono accodati apposta.

## Documentazione

- `doc/alberoDeiTopic.md` — tutti i topic, i payload JSON e le regole di reazione
- `doc/comeFunaziona.md` — note sul funzionamento del broker
