

Runnare il sistema

1. Prerequisiti e servizi infrastrutturali

1.1 Generare i certificati TLS (una volta sola, PRIMA del docker-compose)
$ cd BrokerMosquitto/tls && ./gen-certs.sh && cd ../..

   Lo script crea la CA privata del progetto e i certificati di broker, Centrale,
   Keycloak e web app. Va lanciato per primo perche' il container di Keycloak li
   monta all'avvio: senza, non parte (RF04.3, canali cifrati).


1.2 Avviare i servizi infrastrutturali (Database centrale, Keycloak e Broker MQTT)
$ docker-compose up -d

   C'e' un unico docker-compose.yml nella radice del progetto e fa partire tutti
   e tre i container:
     railway-postgres  -> database, porta 5432
     railway-keycloak  -> Identity Provider, porta 8543 in HTTPS (admin/admin).
                          Parla SOLO cifrato: su questo canale passano le
                          password degli utenti, quindi l'HTTP e' spento
                          (KC_HTTP_ENABLED=false) e la vecchia 8180 in chiaro
                          non esiste piu'.
     broker-mosquitto  -> broker MQTT, porte 1883 (in chiaro), 8883 (TLS) e
                          9001 (WebSocket, quella che usa la web app)
   Al primo avvio Keycloak importa da solo il realm "railway" con ruoli e utenti
   (Keycloak/realm-railway.json). La Centrale non parte finche' Keycloak non
   risponde: ci prova per una trentina di secondi e poi si arrende.
   La configurazione del broker resta dov'era (BrokerMosquitto/config,
   BrokerMosquitto/tls, ...): il compose la monta da li'.


1.3 Popolare il database

Le tabelle le crea Hibernate da solo al primo avvio della Centrale, ma nascono
vuote. Per rimetterci dentro l'anagrafica (stazioni, tratte, itinerari, treni e
operatori) c'e' lo script nella radice:

$ ./popola_db.sh

Legge populate_db.sql e lo esegue dentro il container railway-postgres. Si puo'
rilanciare quante volte si vuole: gli INSERT hanno ON CONFLICT DO NOTHING, quindi
le righe gia' presenti non vengono toccate. Volendo si puo' passare un altro file
SQL da eseguire al posto di quello di default:

$ ./popola_db.sh un_altro_script.sql

Transiti, guasti e tabelle Storico_* non stanno nello script: li riempie il
sistema mentre gira.


2. Avviare il Codice

2.1 Interfaccia Grafica
$ cd ClientWebAppIntefacciaUtente
$ npm install
$ npm run dev

Parte in https su https://localhost:5173 (il certificato e' quello generato al
punto 1.1). E' l'unico indirizzo che il client railway-webapp accetta come
redirect dopo il login.

2.2 Server centrale
$ cd ServeCentraleOperativa
$ ./mvnw quarkus:dev -Dquarkus.profile=tls

Il profilo tls serve per usare la web app: la pagina e' servita in https e il
browser rifiuta le chiamate verso la 8781 in chiaro. Senza interfaccia grafica
(solo Stazioni e Treni via MQTT) va bene anche il semplice ./mvnw quarkus:dev.

2.3 Stazione
$ cd Stazioni
$ ./mvnw quarkus:dev -Dstazione.id=MI -Dquarkus.http.port=8081

2.4 Treno
$ cd Treni
$ ./mvnw quarkus:dev -Dtreno.id=Jaz -Dquarkus.http.port=8082

NOTA: gli ID passati qui devono esistere nelle tabelle Stazione / Treni del
database centrale (vedi populate_db.sql), altrimenti la validazione via MQTT li
rifiuta e il processo si chiude con "ID non presente nel database centrale".
Con i dati attuali le stazioni sono MI, TO, FI, BO, PA, BE, CA, PAL e i treni
Jaz, LUNGO, Smeraldo, Rosso, Pietra.

NOTA (sensori di binario): ogni stazione simula i sensori del proprio binario,
uno in ingresso e uno in uscita (id <STAZIONE>-BIN1-IN e <STAZIONE>-BIN1-OUT).
Sono loro a "vedere" passare i treni e a mandare il keepalive alla stazione con
POST /stazione/sensore/heartbeat ogni 5 secondi. Se un sensore smette di battere
per piu' di sensori.timeout.secondi (30 di default) la stazione si dichiara
GUASTA alla Centrale; quando i battiti tornano si rimette ONLINE da sola e manda
il RESOLVED. Se la stazione parte da un terminale interattivo compare la console
dei sensori (come quella dei sensori di bordo del Treno), da cui si spegne e si
riaccende l'heartbeat di un sensore per provare il meccanismo. Le stesse cose si
possono fare da fuori:
  $ curl http://localhost:8081/stazione/sensori
  $ curl -X POST http://localhost:8081/stazione/sensori/IN/stop
  $ curl -X POST http://localhost:8081/stazione/sensori/IN/start

NOTA 2: ogni stazione si crea il proprio database locale SQLite in
Stazioni/data/stazione-<ID>.db (schema in Stazioni/src/main/resources/schema.sql).
Ci tiene lo stato operativo, i treni fermi sui binari e la coda degli eventi non
ancora spediti alla Centrale, cosi' un riavvio del nodo non se li porta via.
La cartella e' in .gitignore. Per far ripartire una stazione "pulita" basta
cancellare il suo file, oppure lanciarla con -Dstazione.db.abilitata=false.
Cosa c'e' dentro lo dice anche la GET http://localhost:8081/stazione/persistenza.


3. Istanze multiple (piu' stazioni e piu' treni sulla stessa macchina)

Ogni processo apre il proprio server HTTP, quindi la porta va cambiata a mano:
lanciando un secondo treno senza -Dquarkus.http.port il bind fallisce
("Address already in use").

$ cd Stazioni && ./mvnw quarkus:dev -Dstazione.id=MI -Dquarkus.http.port=8081
$ cd Stazioni && ./mvnw quarkus:dev -Dstazione.id=FI -Dquarkus.http.port=8091
$ cd Treni    && ./mvnw quarkus:dev -Dtreno.id=Jaz -Dquarkus.http.port=8082
$ cd Treni    && ./mvnw quarkus:dev -Dtreno.id=Rosso -Dquarkus.http.port=8092

Con i jar l'ID si puo' passare anche come primo argomento:
$ java -Dquarkus.http.port=8092 -jar Treni/target/quarkus-app/quarkus-run.jar Rosso

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

Il canale delle credenziali (browser <-> Keycloak <-> Centrale) e' cifrato sempre,
in qualunque profilo, perche' Keycloak espone solo HTTPS. Il profilo tls aggiunge
la cifratura degli altri due canali: MQTT verso il broker e REST/WebSocket verso
la Centrale. Dato che la web app gira in https, e una pagina https non puo'
chiamare endpoint http:// o ws:// (il browser li blocca come "mixed content"),
per usare l'interfaccia utente la Centrale va avviata con questo profilo.

4.1 I certificati sono gia' stati generati al punto 1

4.2 Avviare i tre servizi con il profilo tls (MQTT sulla 8883, REST sulla 8444)
$ cd ServeCentraleOperativa && ./mvnw quarkus:dev -Dquarkus.profile=tls
$ cd Stazioni && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dstazione.id=MI -Dquarkus.http.port=8081
$ cd Treni    && ./mvnw quarkus:dev -Dquarkus.profile=tls -Dtreno.id=Jaz -Dquarkus.http.port=8082

4.3 Web app
Non serve fare niente: gli indirizzi cifrati sono gia' i default del codice
(.env.example serve solo se si vuole cambiarli). Il dev server di Vite parte in
https con il certificato del progetto, quindi la web app sta su

    https://localhost:5173

4.4 Fidarsi della CA (una volta sola, per non vedere gli avvisi del browser)
I certificati sono firmati da una CA privata, che nessun browser conosce: si
importa BrokerMosquitto/tls/certs/ca.crt fra le autorita' di certificazione
(in Firefox: Impostazioni -> Privacy e sicurezza -> Certificati -> Autorita').
Da riga di comando si passa invece --cacert:

$ curl --cacert BrokerMosquitto/tls/certs/ca.crt \
    https://localhost:8543/realms/railway/.well-known/openid-configuration


5. Login e permessi (OAuth2 / OpenID Connect con Keycloak)

Le API della Centrale non sono pubbliche: serve un access token rilasciato da
Keycloak, allegato nell'header "Authorization: Bearer <token>". La web app lo
ottiene da sola con il flusso Authorization Code + PKCE:

  - si clicca "Accedi con Keycloak" su https://localhost:5173/login
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
$ CA=BrokerMosquitto/tls/certs/ca.crt
$ TOKEN=$(curl -s --cacert $CA -X POST \
    https://localhost:8543/realms/railway/protocol/openid-connect/token \
    -d grant_type=password -d client_id=railway-webapp \
    -d username=mat001 -d password=password | jq -r .access_token)
$ curl -s --cacert $CA https://localhost:8444/api/treni -H "Authorization: Bearer $TOKEN"
