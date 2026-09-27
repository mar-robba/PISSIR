# Monitoraggio e Gestione del Traffico Ferroviario

Riassunto di come si fa partire il sistema. La versione lunga, con tutte le note
e le spiegazioni, sta in `README.txt`.

Pezzi del sistema: **Centrale Operativa** (Quarkus + Postgres), **Stazioni** e
**Treni** (nodi Quarkus che parlano MQTT), **web app** React, **broker Mosquitto**
e **Keycloak** per il login.

## 1. Prima di tutto: certificati e container

```bash
cd BrokerMosquitto/tls && ./gen-certs.sh && cd ../..   # una volta sola
docker-compose up -d                                   # Postgres, Keycloak, Mosquitto
./popola_db.sh                                         # anagrafica nel DB (rilanciabile)
```

I certificati vanno generati **prima** del compose, altrimenti Keycloak non parte.
Il compose alza tre container:

| container | porta | note |
|---|---|---|
| `railway-postgres` | 5432 | database centrale |
| `railway-keycloak` | 8543 (HTTPS) | admin/admin, solo cifrato, importa da solo il realm `railway` |
| `broker-mosquitto` | 1883 / 8883 / 9001 | chiaro / TLS / WebSocket (usata dalla web app) |

Le tabelle le crea Hibernate al primo avvio della Centrale ma sono vuote:
`popola_db.sh` esegue `populate_db.sql` dentro il container (gli INSERT hanno
`ON CONFLICT DO NOTHING`, quindi si puo' rilanciare senza danni). Transiti,
guasti e tabelle `Storico_*` li riempie il sistema mentre gira.

## 2. Avviare i nodi

```bash
cd ClientWebAppIntefacciaUtente && npm install && npm run dev   # https://localhost:5173
cd ServeCentraleOperativa && ./mvnw quarkus:dev -Dquarkus.profile=tls
cd Stazioni && ./mvnw quarkus:dev -Dstazione.id=MI  -Dquarkus.http.port=8081
cd Treni    && ./mvnw quarkus:dev -Dtreno.id=Jaz    -Dquarkus.http.port=8082
```

- Con la web app la Centrale va avviata **con il profilo `tls`**: la pagina e' in
  https e il browser blocca le chiamate in chiaro verso la 8781 (mixed content).
  Se si usano solo Stazioni e Treni via MQTT basta `./mvnw quarkus:dev`.
- Gli ID devono esistere nel database centrale, altrimenti il nodo si chiude con
  "ID non presente nel database centrale". Con i dati attuali:
  stazioni `MI, TO, FI, BO, PA, BE, CA, PAL` — treni `Jaz, LUNGO, Smeraldo, Rosso, Pietra`.

### Piu' nodi sulla stessa macchina

Ogni processo apre il suo server HTTP, quindi la porta va cambiata a mano
(`-Dquarkus.http.port=...`). Per non lanciarli uno per uno:

```bash
cd Stazioni && ./avvioStazioni.sh     # serve prima ./mvnw package
cd Treni    && ./avvioTreni.sh
```

Gli script leggono la lista degli ID da `Stazioni/stazioni.conf` e `Treni/treni.conf`
(un ID per riga), assegnano le porte in ordine e scrivono un log per istanza in `logs/`.

## 3. Sensori e persistenza locale

Ogni stazione simula due sensori di binario (`<STAZIONE>-BIN1-IN` e `-OUT`) che
mandano un heartbeat ogni 5 secondi. Se un sensore tace per piu' di
`sensori.timeout.secondi` (30) la stazione si dichiara GUASTA alla Centrale e
torna ONLINE da sola quando i battiti riprendono. Si prova dalla console dei
sensori o da fuori:

```bash
curl http://localhost:8081/stazione/sensori
curl -X POST http://localhost:8081/stazione/sensori/IN/stop
curl -X POST http://localhost:8081/stazione/sensori/IN/start
```

Ogni stazione ha anche un SQLite suo in `Stazioni/data/stazione-<ID>.db` con stato
operativo, treni sui binari e coda degli eventi non ancora spediti, cosi' un riavvio
non li perde. Per ripartire puliti si cancella il file oppure si lancia con
`-Dstazione.db.abilitata=false`.

## 4. TLS (variante 6)

Il canale delle credenziali e' sempre cifrato perche' Keycloak espone solo HTTPS.
Il profilo `tls` cifra anche gli altri due canali: MQTT sulla **8883** e
REST/WebSocket sulla **8444**. Si passa `-Dquarkus.profile=tls` a Centrale,
Stazioni e Treni; la web app usa gia' gli indirizzi cifrati di default.

Per non vedere gli avvisi del browser si importa `BrokerMosquitto/tls/certs/ca.crt`
fra le autorita' di certificazione; da riga di comando si usa `--cacert`.

## 5. Login e permessi

Le API della Centrale vogliono un access token di Keycloak
(`Authorization: Bearer <token>`); la web app lo ottiene con Authorization Code +
PKCE, quindi la password si digita sulla pagina di Keycloak e la web app non la
vede mai. La Centrale verifica la firma e legge i ruoli da `realm_access.roles`.

Utenti di prova del realm `railway`, password `password` per tutti — **username in
minuscolo** (`mat001`, non `MAT001`):

- `mat001` — amministratore: CRUD di stazioni, treni, tratte e itinerari
- `mat002` / `mat003` / `mat004` — tecnico: letture, invio operatori, soppressione
  corsa e risoluzione allarmi

Nella tabella `Utenti` non ci sono piu' password: e' rimasta solo come anagrafica
degli operatori, collegata all'utente Keycloak tramite la matricola nel token.
