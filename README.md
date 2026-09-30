# Monitoraggio e Gestione del Traffico Ferroviario

Solo i comandi per compilare e avviare. Le spiegazioni stanno in `README.txt`.

## 1. Certificati e container (dalla radice)

```bash
cd BrokerMosquitto/tls && ./gen-certs.sh && cd ../..   # una volta sola, prima del compose
docker-compose up -d                                   # Postgres, Keycloak, Mosquitto
```

## 2. Compilare

```bash
./mvnw package -DskipTests                               # Centrale, Stazioni, Treni
cd ClientWebAppIntefacciaUtente && npm install && cd ..  # web app
```

## 3. Avviare (un terminale per riga)

```bash
cd ServeCentraleOperativa && java -Dprofilo=tls -jar target/centrale.jar
./popola_db.sh                                           # dopo il primo avvio della Centrale
cd Stazioni && ./avvioStazioni.sh                        # stazioni elencate in stazioni.conf
cd Treni    && ./avvioTreni.sh                           # treni elencati in treni.conf
cd ClientWebAppIntefacciaUtente && npm run dev           # https://localhost:5173
```

Login sulla web app: `mat001` (amministratore) o `mat002` (tecnico), password `password`.

## 4. Spegnere

```bash
# Ctrl+C sui terminali, poi:
docker-compose down
```
