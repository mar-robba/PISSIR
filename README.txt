

Runnare il sistema

1. Avviare i servizi infrastrutturali (Database centrale, Keycloak e Broker MQTT)
1.1  Database Centrale + Keycloak
$ docker-compose up -d

   Lo stesso compose fa partire due container:
     railway-postgres  -> database, porta 5432
     railway-keycloak  -> Identity Provider, porta 8180 (admin/admin)
   Al primo avvio Keycloak importa da solo il realm "railway" con ruoli e utenti
   (Keycloak/realm-railway.json). La Centrale non parte finche' Keycloak non
   risponde: ci prova per una trentina di secondi e poi si arrende.

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

3.1 Script di avvio in blocco

Per non lanciarli a mano uno per uno ci sono due script che avviano tutti i nodi
in ciclo, assegnando le porte in ordine (stazioni da 8080, treni da 9080) e un
file di log per istanza dentro logs/:

$ cd Stazioni && ./avvioStazioni.sh
$ cd Treni    && ./avvioTreni.sh

Serve prima il jar (./mvnw package). Ctrl+C ferma tutti i processi avviati.

Quali nodi far partire NON e' scritto dentro gli script: la lista sta nei file
Stazioni/stazioni.conf e Treni/treni.conf, un ID per riga (le righe vuote e
quelle che iniziano con # vengono ignorate). Per aggiungere o togliere un nodo
basta modificare quei file, senza toccare lo script.


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


5. Login e permessi (OAuth2 / OpenID Connect con Keycloak)

Le API della Centrale non sono pubbliche: serve un access token rilasciato da
Keycloak, allegato nell'header "Authorization: Bearer <token>". La web app lo
ottiene da sola con il flusso Authorization Code + PKCE:

  - si clicca "Accedi con Keycloak" su http://localhost:5173/login
  - il browser va sulla pagina di login di Keycloak (la password si digita LI',
    la web app non la vede mai)
  - Keycloak rimanda su /login?code=... e la web app scambia il codice con i token
  - da li' in poi ogni chiamata REST porta il token; la Centrale ne verifica la
    firma e legge i ruoli dal claim realm_access.roles

Utenti di prova (realm "railway"), password "password" per tutti. ATTENZIONE:
Keycloak tiene gli username in minuscolo, quindi si scrive "mat001", non "MAT001":
  mat001 -> amministratore: CRUD di stazioni, treni, tratte e itinerari
  mat003 / mat002 / mat004 -> tecnico: letture + invio operatori, soppressione
                              corsa e risoluzione allarmi

La tabella Utenti del database non contiene piu' nessuna password: e' rimasta solo
come anagrafica degli operatori (i guasti puntano a una sua riga). Il collegamento
fra utente Keycloak e riga della tabella e' la matricola, che viaggia nel token.

Prova da terminale: il flusso Authorization Code vuole il browser, quindi per un
curl veloce conviene abilitare temporaneamente "Direct access grants" sul client
railway-webapp dalla console di Keycloak (poi rimetterlo a off):
$ TOKEN=$(curl -s -X POST \
    http://localhost:8180/realms/railway/protocol/openid-connect/token \
    -d grant_type=password -d client_id=railway-webapp \
    -d username=mat001 -d password=password | jq -r .access_token)
$ curl -s http://localhost:8781/api/treni -H "Authorization: Bearer $TOKEN"
