# TLS nel progetto (variante 6)

## Perché

La variante 6 del professore richiede di proteggere le comunicazioni del sistema
con TLS: **senza questa variante la valutazione perde 5 punti**. In questo
progetto la cifratura copre:

- il traffico **MQTT** tra i microservizi (Centrale, Stazioni, Treni) e il broker
  Mosquitto, spostandolo dalla porta 1883 in chiaro alla **8883** cifrata;
- le **API REST/WebSocket della Centrale Operativa**, esposte anche in **HTTPS
  sulla porta 8444** (la 8781 in chiaro resta attiva per comodità in demo).

Tutto è opt-in tramite il profilo Quarkus `tls`: senza profilo il sistema
continua a funzionare esattamente come prima, in chiaro.

## Generazione dei certificati

I certificati sono self-signed e vengono generati da uno script unico:

```bash
cd BrokerMosquitto/tls
./gen-certs.sh
```

Lo script crea in `BrokerMosquitto/tls/certs/`:

| File | Ruolo |
|---|---|
| `ca.crt` / `ca.key` | CA self-signed del progetto (il "trust anchor") |
| `server.crt` / `server.key` | Certificato del broker Mosquitto, CN=localhost con SAN `DNS:localhost, IP:127.0.0.1` |
| `server-centrale.crt` / `server-centrale.key` | Certificato per l'HTTPS della Centrale Operativa |

Lo script esegue anche `openssl verify -CAfile ca.crt ...` per confermare che i
certificati emessi siano validi rispetto alla CA.

> Nota: le chiavi private non andrebbero mai committate in un progetto reale;
> qui è accettabile perché sono certificati didattici rigenerabili in qualsiasi
> momento con lo script.

## Avvio del sistema con TLS

1. **Generare i certificati** (vedi sopra), poi avviare il broker:

   ```bash
   cd BrokerMosquitto
   docker compose up -d
   ```

   Il `mosquitto.conf` ora espone due listener: 1883 in chiaro e 8883 TLS
   (i certificati sono montati dal compose in `/mosquitto/tls/certs/`).

2. **Avviare i microservizi con il profilo `tls`**:

   ```bash
   # Centrale Operativa (MQTT su 8883 + HTTPS su 8444)
   cd ServeCentraleOperativa && ./mvnw quarkus:dev -Dquarkus.profile=tls

   # Stazione
   cd Stazioni && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dstazione.id=S1

   # Treno
   cd Treni && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dtreno.id=TRN001
   ```

   Il profilo `%tls` nei tre `application.properties` sovrascrive
   `mqtt.port=8883` e aggiunge a **ogni canale MQTT** gli attributi
   `ssl=true`, `ssl.truststore.type=pem`,
   `ssl.truststore.location=../BrokerMosquitto/tls/certs/ca.crt`
   (path relativo alla directory del singolo servizio: i servizi vanno quindi
   lanciati dalla loro cartella, come si fa normalmente).

3. **Verifica rapida**:

   ```bash
   # MQTT cifrato
   mosquitto_sub -p 8883 --cafile BrokerMosquitto/tls/certs/ca.crt -t 'railway/#' -v

   # HTTPS della Centrale (-k perché il certificato è self-signed)
   curl -k https://localhost:8444/api/dashboard
   ```

## Cosa dire nella relazione

- La confidenzialità/integrità dei messaggi MQTT è garantita da TLS 1.2+ sul
  listener 8883; i client autenticano il broker validando il suo certificato
  contro la CA del progetto (truststore PEM per canale, supportato dal
  connettore SmallRye MQTT).
- Il certificato del broker usa il SAN (`DNS:localhost`, `IP:127.0.0.1`) perché
  i client TLS moderni ignorano il solo CN.
- La Centrale espone anche HTTPS (8444) con un certificato dedicato firmato
  dalla stessa CA.

## Limitazioni

- **Certificati self-signed**: la CA non è riconosciuta da browser o sistemi
  esterni; il frontend/curl richiedono di accettare manualmente il certificato
  (o `curl -k`). In produzione servirebbe una CA reale (es. Let's Encrypt).
- **Nessuna mutua autenticazione (mTLS)**: i client verificano il broker, ma il
  broker non richiede certificati ai client (`allow_anonymous true`).
- La porta 1883 in chiaro resta aperta per retrocompatibilità in demo: in
  produzione andrebbe chiusa.
