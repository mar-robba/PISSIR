
I will start by exploring the workspace directory to understand the project structure and determine what kind of application it is (e.g., Quarkus, Maven, Gradle, etc.) so that I can provide accurate testing instructions.

Listed directory MonitoraggioEGestioneDelTrafficoFerroviario

I will now inspect the contents of the main directories (`BrokerMosquitto`, `ClientWebAppIntefacciaUtente`, `ServeCentraleOperativa`, `Stazioni`, `Treni`) to understand the build tools and setup used for each component.

Listed directory BrokerMosquitto

I will list the contents of the remaining service directories to see how they are structured.

Listed directory ServeCentraleOperativa

I will list the contents of the `Stazioni` and `Treni` directories to confirm if they also use Maven.

Listed directory Stazioni

I will check `Treni` and `ClientWebAppIntefacciaUtente`.

Listed directory Treni

I will now list the contents of `ClientWebAppIntefacciaUtente`.

Listed directory ClientWebAppIntefacciaUtente

I will read the `docker-compose.yml` in `BrokerMosquitto` to see how the MQTT broker and potentially other dependencies/services are set up.

Viewed docker-compose.yml:1-14

I will check the Mosquitto configuration file to see if there are any specific settings or auth requirements.

Viewed mosquitto.conf:1-9

I will view the `pom.xml` file in `Stazioni` to identify the framework and dependencies.

Viewed pom.xml:1-81

I will view `Treni/pom.xml` to verify if it is also a Quarkus application or simple Java.

Viewed pom.xml:1-81

I will view `ServeCentraleOperativa/pom.xml` to see if it is Quarkus, Spring, or standard Java.

Viewed pom.xml:1-12

I will check if there is any project documentation or guidelines inside the `doc` directory to see if there are pre-existing setup instructions.

Listed directory doc

I will view the `considerazioniAggiuntiveAlTestoDElProf.org` file to see if there are any instructions or notes from the professor or the development team regarding testing/running.

Viewed considerazioniAggiuntiveAlTestoDElProf.org:1-91

I will list the contents of `ServeCentraleOperativa/src` to see the structure of the central server service.

Listed directory src

I will list the project files (excluding build files, node modules, and hidden directories) to get a clear overview of all the source files across all components of the application.

Ran command: `find . -maxdepth 4 -not -path '*/.*' -not -path '*/node_modules/*' -not -path '*/target/*'`

I will run a `find` command to list all Java files in the project to understand the class structure.

Ran command: `find . -name "*.java"`

I will list everything inside `ServeCentraleOperativa` to check if it contains any files.

Ran command: `find ServeCentraleOperativa`

I will read the `README.md` in `BrokerMosquitto/centrale-operativa` to understand what that project is and how it expects to be run.

Viewed README.md:1-59

I will view `BrokerMosquitto/doc/comeFunaziona.md` to see the developer notes on how the communication works.

Viewed comeFunaziona.md:1-109

I will view `BrokerMosquitto/doc/alberoDeiTopic.md` to see the structure of topics designed for this railway monitoring application.

I will list the `BrokerMosquitto/doc` directory to see the exact filenames.

Listed directory doc

I will view the `alberoDeiTopic.md ` file (which has a trailing space in its filename).

Viewed alberoDeiTopic.md%20:1-102

I will view the `CentraleOperativa.java` file to see how the central server handles messages, what database or in-memory storage it uses, and how it is implemented.

Viewed CentraleOperativa.java:1-92

I will find all `application.properties` configuration files in the project to understand the configuration ports and details.

Ran command: `find . -name "application.properties"`

I will view the `application.properties` file of `centrale-operativa` to check the configured ports, MQTT settings, and HTTP configurations.

Viewed application.properties:1-23

I will view the `application.properties` file of `Stazioni` to see its port and MQTT setup.

Viewed application.properties:1-43

I will view the `application.properties` file of `Treni` to see its configuration, port, and MQTT setup.

Viewed application.properties:1-34

I will view `Stazioni/src/main/java/it/uni/reti2/StazioneResource.java` to see what HTTP endpoints it exposes.

Viewed StazioneResource.java:1-101

I will view `Stazioni/src/main/java/it/uni/reti2/StazioneService.java`.

Viewed StazioneService.java:1-101

I will view `Treni/src/main/java/it/uni/reti2/TrenoResource.java` to check the endpoints exposed by the Train microservice.

Viewed TrenoResource.java:1-80

I will view `Treni/src/main/java/it/uni/reti2/TrenoService.java`.

Viewed TrenoService.java:1-105

I will view `BrokerMosquitto/centrale-operativa/src/main/java/it/uni/reti2/CentraleResource.java` to see what HTTP endpoints the Central Server exposes.

Viewed CentraleResource.java:1-95

I will check the frontend documentation to see how the client web application is integrated and how to run it.

Viewed frontend_documentation.md:1-145

I will view the `useSimulator.ts` file to see if the frontend is connected to a real backend or if it simulates the data locally.

Viewed useSimulator.ts:1-166

I will search for any REST API communication (like `fetch` or `axios`) inside the React application to see if it is currently connected to the Quarkus backend, or if it is mock-only.

Searched for "fetch"

I will search for `axios` in the frontend code.

Searched for "axios"

I will search for any Java test files in the project to see if there are automated JUnit/Quarkus tests.

Ran command: `find . -name "*Test.java"`

I will check if there are any files under any `src/test` directories in the project.

Ran command: `find . -path "*/src/test/*"`

I will read `BrokerMosquitto/centrale-operativa/pom.xml` to check if there are database dependencies configured.

Viewed pom.xml:1-118

Per testare questa applicazione di monitoraggio e gestione del traffico ferroviario, che è composta da più microservizi **Quarkus** in Java, un broker **MQTT (Mosquitto)** in Docker e una web app **React/Vite**, puoi seguire questa procedura passo-passo per avviare l'intero sistema ed eseguire dei test di integrazione ed eventi in tempo reale.

---

### Architettura del Sistema
*   **Broker MQTT (Mosquitto)**: Fa da intermediario (Pub/Sub) e gira su Docker.
*   **Centrale Operativa** (`BrokerMosquitto/centrale-operativa`): Ascolta tutti i messaggi sul topic wildcard `railway/#` (heartbeat stazioni, transiti, telemetrie) ed espone un'API HTTP per l'invio di alert a tutti.
*   **Stazioni** (`Stazioni`): Invia periodicamente un heartbeat (ogni 10 secondi) ed espone un'API HTTP per simulare il passaggio dei treni.
*   **Treni** (`Treni`): Invia periodicamente la telemetria/posizione (ogni 5 secondi) ed espone un'API HTTP per simulare le emergenze.
*   **Interfaccia Utente** (`ClientWebAppIntefacciaUtente`): Dashboard in React che simula l'interfaccia di monitoraggio (attualmente con dati simulati localmente).

---

### Step 1: Avviare il Broker MQTT (Mosquitto)
Il broker Mosquitto deve essere attivo per permettere lo scambio di messaggi.
1. Apri un terminale e posizionati nella cartella del broker:
   ```bash
   cd BrokerMosquitto
   ```
2. Avvia il container Docker in modalità detached:
   ```bash
   docker compose up -d
   ```
   *Nota: Il broker rimarrà in ascolto sulla porta standard `1883`.*

---

### Step 2: Avviare la Centrale Operativa
1. Apri un nuovo terminale e posizionati nella cartella centrale:
   ```bash
   cd BrokerMosquitto/centrale-operativa
   ```
2. Avvia il microservizio in modalità sviluppo (Dev Mode):
   ```bash
   ./mvnw quarkus:dev
   ```
   *Nota: Di default, questo servizio risponderà all'indirizzo HTTP `http://localhost:8080`.*

---

### Step 3: Avviare il Microservizio delle Stazioni
1. Apri un nuovo terminale e posizionati nella cartella:
   ```bash
   cd Stazioni
   ```
2. Avvia il microservizio in Dev Mode:
   ```bash
   ./mvnw quarkus:dev
   ```
   *Nota: Per default si avvia sulla porta HTTP `8081` con l'ID stazione `alessandria`. Nei log dovresti iniziare a vedere periodicamente:*
   `💓 [HEARTBEAT] Invio heartbeat: ...`
   *Mentre nella console della Centrale Operativa vedrai:*
   `💓 [HEARTBEAT STAZIONE] La stazione è operativa: ...`

   *Se vuoi testare più stazioni contemporaneamente su porte diverse con ID diversi, puoi avviarne un'altra specificando i parametri:*
   ```bash
   ./mvnw quarkus:dev -Dquarkus.http.port=8083 -Dstazione.id=torino
   ```

---

### Step 4: Avviare il Microservizio dei Treni
1. Apri un nuovo terminale e posizionati nella cartella:
   ```bash
   cd Treni
   ```
2. Avvia il microservizio in Dev Mode:
   ```bash
   ./mvnw quarkus:dev
   ```
   *Nota: Per default si avvia sulla porta HTTP `8082` con l'ID treno `REG-1234`. Vedrai subito i log delle posizioni GPS simulate inviate ogni 5 secondi:*
   `📡 [TELEMETRIA] Invio posizione: ...`
   *Mentre nella Centrale Operativa vedrai:*
   `📡 [TELEMETRIA TRENO] Posizione aggiornata: ...`

   *Per simulare un secondo treno contemporaneamente:*
   ```bash
   ./mvnw quarkus:dev -Dquarkus.http.port=8084 -Dtreno.id=AV-9876
   ```

---

### Step 5: Avviare l'Interfaccia Utente (React Web App)
La web app contiene una simulazione avanzata a livello frontend del traffico e dello stato delle stazioni.
1. Apri un nuovo terminale nella cartella del frontend:
   ```bash
   cd ClientWebAppIntefacciaUtente
   ```
2. Se non lo hai ancora fatto, installa le dipendenze:
   ```bash
   npm install
   ```
3. Avvia il server di sviluppo locale:
   ```bash
   npm run dev
   ```
4. Apri nel browser l'indirizzo mostrato nel terminale (solitamente `http://localhost:5173`) per visualizzare la dashboard.

---

### Step 6: Test di Integrazione tramite chiamate HTTP (curl)
Mentre tutti i servizi sono attivi in background, puoi simulare eventi reali inviando richieste HTTP da un terminale separato:

#### 6.1. Simulare il transito di un treno in stazione
Puoi comunicare al microservizio `Stazioni` (porta `8081`) che un treno è entrato in stazione:
```bash
curl -X POST http://localhost:8081/stazione/transito \
     -H "Content-Type: application/json" \
     -d '{"trenoId":"REG-1234","tipo":"INGRESSO"}'
```
*   **Risultato atteso**: La console di `Stazioni` mostrerà `🚂 [TRANSITO] Pubblico su MQTT: ...` e la console di `CentraleOperativa` loggherà la ricezione sul topic di transito: `🚂 [TRANSITO] Movimento treno rilevato: ...`.

#### 6.2. Simulare un'emergenza a bordo di un treno
Invia un segnale di emergenza al microservizio `Treni` (porta `8082`):
```bash
curl -X POST http://localhost:8082/treno/emergenza
```
*   **Risultato atteso**: La console di `Treni` segnalerà l'emergenza. La `CentraleOperativa` intercetterà il payload contenente `"stato":"EMERGENZA"` e mostrerà un log di errore colorato: `🆘 [EMERGENZA TRENO] Rilevata emergenza dal treno!`.

#### 6.3. Trasmettere un avviso/alert dalla Centrale a tutti i microservizi
Invia un comando di alert collettivo dal microservizio `Centrale` (porta `8080`):
```bash
curl -X POST http://localhost:8080/centrale/alert \
     -H "Content-Type: application/json" \
     -d '{"messaggio":"Rallentamento critico sulla tratta Alessandria-Torino","livello":"WARNING"}'
```
*   **Risultato atteso**: La Centrale pubblica su `railway/alerts`.
    *   La console di `Stazioni` loggherà: `🚨 [ALERT RICEVUTO] La Centrale dice: ...`
    *   La console di `Treni` loggherà: `🚨 [ALERT RICEVUTO] La Centrale dice: ...`
    *   Questo dimostra il corretto funzionamento del pattern **Publish/Subscribe** uno-a-molti (1 messaggio inviato dalla Centrale viene smistato dal broker Mosquitto a tutti i microservizi attivi iscritti al topic).
