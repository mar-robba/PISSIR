# Nodo Treno

Un processo per ogni treno: scarica l'itinerario dalla Centrale, lo percorre
simulando il viaggio, manda telemetria e annuncia il passaggio ai sensori delle
stazioni. Istruzioni generali di avvio nel `README.md` della radice.

## Avvio

```bash
./mvnw quarkus:dev -Dtreno.id=Jaz -Dquarkus.http.port=8082
./mvnw quarkus:dev -Dtreno.id=Jaz -Dquarkus.http.port=8082 -Dquarkus.profile=tls
```

L'ID deve esistere nel database centrale (`Jaz, LUNGO, Smeraldo, Rosso, Pietra`
con i dati attuali). Per lanciarne tanti insieme c'e' `./avvioTreni.sh`, che legge
gli ID da `treni.conf` e scrive un log per istanza in `logs/`.

Il viaggio parte da solo (`viaggio.autostart`), accelerato di
`viaggio.fattore.accelerazione` (100) rispetto ai tempi reali, con
`viaggio.sosta.secondi` di fermata in stazione.

## Cosa espone

REST sotto `/treno`: `info`, `stato`, `viaggio`, `tratta` (GET e POST) e i due
ingressi dei sensori di bordo, `sensore/passaggio` e `sensore/guasto`.

## MQTT

Pubblica `railway/train/{id}/telemetry` e
`railway/treno-simulatoriSensori/{id}`; ascolta `railway/alerts` e reagisce:
si ferma se una stazione del suo tragitto va in GUASTO o MANUTENZIONE, riparte
quando l'allarme si chiude, si sopprime su `STOP` con il proprio ID e riscarica
l'itinerario su `ITINERARIO_AGGIORNATO`.

## Codice

`TrainJourneyEngine` fa avanzare il viaggio, `TrainDB` tiene lo stato in memoria,
`TrainElab` decide le reazioni agli alert, `TrainGateway` parla col broker.
