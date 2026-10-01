# Monitoraggio e Gestione del Traffico Ferroviario

Solo i comandi per compilare e avviare tutto il sistema. Per lanciare un nodo alla volta e per i
test ci sono i README nelle cartelle dei singoli moduli.

## Prima di usare la web app: far accettare i certificati al browser

Tutti gli indirizzi https del sistema usano certificati firmati dalla CA privata del progetto
(`BrokerMosquitto/tls/certs/ca.crt`), che il browser non conosce, quindi mostra un avviso di
sicurezza. La connessione e' cifrata lo stesso: l'avviso dice solo che il browser non riconosce chi
ha firmato il certificato. Va accettato una volta per ognuno dei tre indirizzi usati dalla web app,
con il sistema gia' avviato (punti 1-3 qui sotto), in quest'ordine:

1. **Centrale Operativa** (REST e WebSocket): aprire a mano <https://localhost:8444/api/auth/me>
   e accettare. Dopo compare `{"errore":"Autenticazione richiesta: effettuare il login"}`: e'
   giusto cosi', la scheda si puo' chiudere.
2. **Web app**: aprire <https://localhost:5173> e accettare. Compare la pagina di login.
3. **Keycloak** (porta 8543): l'avviso compare da solo cliccando "Accedi con Keycloak".
   Accettato quello, si arriva alla pagina di login di Keycloak.

Per accettare l'avviso:

- Firefox / Zen: "Avanzate..." -> "Accetta il rischio e continua"
- Chrome / Edge / Brave: "Avanzate" -> "Procedi su localhost (non sicuro)"

Il punto 1 e' quello che si dimentica: la web app chiama la Centrale in background, e per quelle
chiamate il browser non mostra nessun avviso, le blocca e basta. Se dopo il login le pagine restano
vuote e compare "NetworkError when attempting to fetch resource." (Firefox) o "Failed to fetch"
(Chrome), manca l'eccezione sulla 8444: aprire l'indirizzo del punto 1, accettare e ricaricare la
web app.

Le eccezioni valgono per il certificato accettato: se i certificati si rigenerano con
`gen-certs.sh` vanno accettate di nuovo. Per non vedere piu' nessun avviso si puo' invece importare
`ca.crt` fra le autorita' di certificazione del browser, una volta sola (Firefox / Zen:
Impostazioni -> Privacy e sicurezza -> Certificati -> Mostra certificati -> Autorita' -> Importa,
spuntando la fiducia per i siti web). Da terminale si passa la CA a curl con
`--cacert BrokerMosquitto/tls/certs/ca.crt`.

## 1. Certificati e container (dalla radice)

```bash
cd BrokerMosquitto/tls && ./gen-certs.sh && cd ../..   # una volta sola, prima del compose
docker-compose up -d                                   # Postgres, Keycloak, Mosquitto
./popola_db.sh                                         # tabelle e dati demo, prima della Centrale
```

## 2. Compilare

```bash
./mvnw package -DskipTests                               # Centrale, Stazioni, Treni
cd ClientWebAppIntefacciaUtente && npm install && cd ..  # web app
```

## 3. Avviare (un terminale per riga)

```bash
cd ServeCentraleOperativa && java -Dprofilo=tls -jar target/centrale.jar
cd Stazioni && ./avvioStazioni.sh                        # stazioni elencate in stazioni.conf
cd Treni    && ./avvioTreni.sh                           # treni elencati in treni.conf
cd ClientWebAppIntefacciaUtente && npm run dev           # https://localhost:5173
```

Login sulla web app, password `password` per tutti: `mat001` (amministratore), `mat002`, `mat003`,
`mat004` (tecnici).

## 4. Provare il guasto di una stazione (sensori di binario)

Ogni stazione simula due sensori di binario, `IN` e `OUT`, che le mandano un heartbeat ogni 5
secondi. Se uno tace per piu' di 30 secondi la stazione si dichiara GUASTA alla Centrale; quando
riparte torna ONLINE da sola. Con `avvioStazioni.sh` le porte seguono l'ordine di `stazioni.conf`
(la prima sulla 8080, la seconda sulla 8081, ...):

```bash
CA=BrokerMosquitto/tls/certs/ca.crt
curl --cacert $CA https://localhost:8080/stazione/sensori                # stato dei sensori
curl --cacert $CA -X POST https://localhost:8080/stazione/sensori/IN/stop    # spegne il sensore
curl --cacert $CA -X POST https://localhost:8080/stazione/sensori/IN/start   # lo riaccende
```

## 5. Spegnere

```bash
# Ctrl+C sui terminali, poi:
docker-compose down
```

Per ripartire da zero (si perdono anche gli storici) si cancellano i volumi e il database locale
SQLite di ogni stazione, poi si ricaricano i dati demo:

```bash
docker-compose down -v && rm -f Stazioni/data/stazione-*      # stazioni gia' spente
docker-compose up -d && ./popola_db.sh
```
