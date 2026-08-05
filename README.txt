

Runnare il sistema

1. Avviare i servizi infrastrutturali (Database centrale e Broker MQTT)
1.1  Database Centrale
$ docker-compose up -d

1.2 Broker Mosquitto*
$ cd BrokerMosquitto
$ docker-compose up -d


2. Avviare il Codice

2.1 Interfaccia Grafica
$ cd ClientWebAppIntefacciaUtente
$ npm install
$ npm run dev

2.2 Server centrale
$ cd ServeCentraleOperativa
$ ./mvnw quarkus:dev

2.3 Stazione
$ cd Stazioni
$ ./mvnw quarkus:dev -Dstazione.id=S1 -Dquarkus.http.port=8081

2.4 Treno
$ cd Treni
$ ./mvnw quarkus:dev -Dtreno.id=TRN001 -Dquarkus.http.port=8082

NOTA: gli ID passati qui devono esistere nelle tabelle Stazione / Treni del
database centrale (vedi import.sql), altrimenti la validazione via MQTT li
rifiuta e il processo si chiude con "ID non presente nel database centrale".


3. Istanze multiple (piu' stazioni e piu' treni sulla stessa macchina)

Ogni processo apre il proprio server HTTP, quindi la porta va cambiata a mano:
lanciando un secondo treno senza -Dquarkus.http.port il bind fallisce
("Address already in use").

$ cd Stazioni && ./mvnw quarkus:dev -Dstazione.id=S1 -Dquarkus.http.port=8081
$ cd Stazioni && ./mvnw quarkus:dev -Dstazione.id=S2 -Dquarkus.http.port=8091
$ cd Treni    && ./mvnw quarkus:dev -Dtreno.id=TRN001 -Dquarkus.http.port=8082
$ cd Treni    && ./mvnw quarkus:dev -Dtreno.id=TRN002 -Dquarkus.http.port=8092

Con i jar l'ID si puo' passare anche come primo argomento:
$ java -Dquarkus.http.port=8092 -jar Treni/target/quarkus-app/quarkus-run.jar TRN002


4. Profilo TLS (variante 6: comunicazione cifrata)

4.1 Generare i certificati una volta sola
$ cd BrokerMosquitto/tls && ./gen-certs.sh

4.2 Avviare i tre servizi con il profilo tls (MQTT sulla 8883, REST sulla 8444)
$ cd ServeCentraleOperativa && ./mvnw quarkus:dev -Dquarkus.profile=tls
$ cd Stazioni && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dstazione.id=S1 -Dquarkus.http.port=8081
$ cd Treni    && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dtreno.id=TRN001 -Dquarkus.http.port=8082

4.3 Far puntare la web app all'HTTPS
$ cd ClientWebAppIntefacciaUtente && cp .env.example .env
  (poi scommentare le due righe della sezione TLS dentro .env)


5. Login e permessi

Le API della Centrale non sono pubbliche: serve il token restituito dal login,
allegato nell'header "Authorization: Bearer <token>" (la web app lo fa da sola).
Utenti di prova (tabella Utenti, seed di import.sql), password "password":
  MAT001 -> amministratore: CRUD di stazioni, treni, tratte e itinerari
  MAT002 / MAT003 / MAT004 -> tecnico: letture + invio operatori, soppressione
                              corsa e risoluzione allarmi

Esempio da terminale:
$ TOKEN=$(curl -s -X POST http://localhost:8781/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"MAT001","password":"password"}' | jq -r .token)
$ curl -s http://localhost:8781/api/treni -H "Authorization: Bearer $TOKEN"
