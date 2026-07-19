# Completamento Sistema di Monitoraggio e Gestione del Traffico Ferroviario

## Stato Attuale

Lo scheletro contiene:
- **BrokerMosquitto**: Docker Mosquitto ✅ completo
- **ServeCentraleOperativa**: Quarkus + MQTT consumer (solo ingestion base) + Spark (placeholder) — mancano REST API, WebSocket, logica guasti
- **Stazioni**: Quarkus — heartbeat + transito base — manca REST API sensori, logica guasti/offline/buffer
- **Treni**: Quarkus — telemetria GPS base — manca REST API sensori, gestione tratta, passaggio stazioni, guasti
- **ClientWebAppIntefacciaUtente**: React/Vite frontend completo con dati MOCK — va collegato al backend reale

> [!IMPORTANT]
> I file REST API dei sensori (StazioneResource.java e TrenoResource.java) vengono **conservati** come richiesto per il testing, e saranno estesi.

## Proposta di Modifiche Architetturali

### Correzione errore: Rimozione Apache Spark
Spark è un overkill per questo progetto (è un framework big data per cluster). Per il "polling ogni 10 secondi" della doc, basta un semplice `@Scheduled` di Quarkus. **Rimuovo Spark e il SparkProcessorService** dal POM e dal codice — questo è un evidente errore di design.

### Modifica porta Centrale: 8083 → 8080
La documentazione indica chiaramente porta 8080 per la Centrale. Correzione coerente.

### WebSocket per il tempo reale
Il frontend necessita di dati in tempo reale. Invece di polling REST, aggiungo un WebSocket endpoint sulla Centrale che invia eventi ogni volta che arrivano dati MQTT. Il frontend si collega via WS per ricevere aggiornamenti push.

### Rimozione schema.sql duplicati in conflitto
Gli schemi SQL nelle risorse sono per H2/PostgreSQL ma i microservizi edge (Stazione/Treno) non hanno dipendenze JPA nel POM. Uso Panache/Hibernate solo nella Centrale (che ha PostgreSQL). Per Stazione e Treno mantengo i dati in RAM (come da doc: "salvarla in RAM per il proprio uso").

---

## Proposed Changes

### Componente 1: ServeCentraleOperativa (Backend Principale)

#### [MODIFY] [pom.xml](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/pom.xml)
- Rimuovere dipendenze Apache Spark
- Aggiungere `quarkus-websockets` per tempo reale
- Aggiungere `quarkus-scheduler` per job periodici
- Correggere packaging Quarkus

#### [DELETE] [SparkProcessorService.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/src/main/java/it/uni/reti2/spark/SparkProcessorService.java)

#### [MODIFY] [application.properties](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/src/main/resources/application.properties)
- Porta 8083 → 8080
- Aggiungere CORS per il frontend
- Aggiungere topic per passaggi treni e guasti

#### [MODIFY] [CentraleMqttConsumer.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/src/main/java/it/uni/reti2/mqtt/CentraleMqttConsumer.java)
- Aggiungere subscription ai topic passaggi (treni/+/passaggio) e guasti (allarmi/+)
- Storicizzare transiti, aggiornare stato treni/stazioni nel DB
- Notificare WebSocket ad ogni evento ricevuto

#### [MODIFY] [EventoStazione.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/src/main/java/it/uni/reti2/entity/EventoStazione.java) e [TelemetriaTreno.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ServeCentraleOperativa/src/main/java/it/uni/reti2/entity/TelemetriaTreno.java)
- Arricchire i campi per allinearsi allo schema.sql (stazioni, treni, tratte, itinerari, transiti, guasti)

#### [NEW] Entità JPA Panache
- `entity/Stazione.java` — Stazione nel DB centrale
- `entity/Treno.java` — Treno/convoglio nel DB centrale  
- `entity/Tratta.java` — Tratte ferroviarie
- `entity/Itinerario.java` — Itinerari (composti da tratte ordinate)
- `entity/Transito.java` — Transiti storici
- `entity/Guasto.java` — Guasti pervenuti da treni o stazioni

#### [NEW] REST API Resource
- `rest/CentraleResource.java` — REST API principale:
  - `GET /api/dashboard` — KPI per la dashboard
  - `GET /api/stazioni` — Lista stazioni con stato live
  - `GET /api/treni` — Lista treni con stato live
  - `GET /api/tratte` — CRUD tratte (itinerari)
  - `POST /api/tratte` — Crea tratta
  - `PUT /api/tratte/{id}` — Modifica tratta
  - `DELETE /api/tratte/{id}` — Elimina tratta
  - `GET /api/transiti` — Storico transiti
  - `GET /api/allarmi` — Lista allarmi/guasti
  - `POST /api/allarmi/{id}/risolvi` — Tecnico risolve guasto → pubblica su MQTT allarmi
  - `POST /api/treni/{id}/sopprimi` — Soppressione treno

#### [NEW] WebSocket per tempo reale
- `websocket/RealtimeWebSocket.java` — Endpoint `/ws/realtime` che invia JSON ad ogni evento MQTT ricevuto

#### [NEW] Servizio cache in-memory
- `service/StatoReteFerroviaria.java` — Cache in-memory dello stato corrente di treni e stazioni (aggiornato da MQTT, letto dalle REST API per "tempo reale" con polling ogni 10s come da doc)

---

### Componente 2: Stazioni (Microservizio Edge)

#### [MODIFY] [StazioneService.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Stazioni/src/main/java/it/uni/reti2/StazioneService.java)
- Aggiungere stato in RAM (funzionante/guasta, connessione centrale ok/ko)
- Gestione allarmi ricevuti: se arriva alert che dice "stazione X ripristinata" → aggiornare stato
- Buffer locale: se la connessione MQTT cade, accumulare eventi, re-inviarli al ripristino
- Gestione passaggio treni: quando sensore comunica entrata/uscita treno, pubblicare su `railway/station/{id}/transit`

#### [MODIFY] [StazioneResource.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Stazioni/src/main/java/it/uni/reti2/StazioneResource.java)
- **CONSERVATO** per testing dei sensori
- Aggiungere endpoint sensori:
  - `POST /stazione/sensore/treno` — Sensore rileva passaggio treno (entrata/uscita)
  - `POST /stazione/sensore/guasto` — Sensore rileva guasto locale
  - `GET /stazione/stato` — Stato attuale della stazione
  - `GET /stazione/buffer` — Stato del buffer locale

#### [MODIFY] [application.properties](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Stazioni/src/main/resources/application.properties)
- Aggiungere topic per guasti della stazione

---

### Componente 3: Treni (Microservizio Edge)

#### [MODIFY] [TrenoService.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Treni/src/main/java/it/uni/reti2/TrenoService.java)
- Aggiungere stato tratta in RAM (lista stazioni, stazione corrente, prossima stazione)
- Gestione guasto treno: quando sensore rileva guasto → pubblica su telemetria con stato EMERGENZA
- Gestione evento "stazione fuori uso" da alert: aggiornare tratta, saltare stazione
- Gestione evento "stazione ripristinata": ripristinare tratta normale
- Pubblicare entrata/uscita stazione su topic `railway/train/{id}/passaggio`

#### [MODIFY] [TrenoResource.java](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Treni/src/main/java/it/uni/reti2/TrenoResource.java)
- **CONSERVATO** per testing dei sensori
- Aggiungere endpoint sensori:
  - `POST /treno/sensore/guasto` — Sensore rileva guasto treno
  - `POST /treno/tratta` — Aggiorna tratta del treno
  - `GET /treno/stato` — Stato completo del treno
  - `GET /treno/tratta` — Tratta corrente

#### [MODIFY] [application.properties](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/Treni/src/main/resources/application.properties)
- Aggiungere topic per passaggio stazioni

---

### Componente 4: ClientWebAppIntefacciaUtente (Frontend)

#### [NEW] `src/api/apiClient.ts`
- Client REST che chiama il backend sulla Centrale (porta 8080)
- Polling ogni 10 secondi per dati live (come da doc)

#### [NEW] `src/api/websocketClient.ts`
- Client WebSocket che si connette a `ws://localhost:8080/ws/realtime`
- Riceve eventi push e aggiorna lo store Zustand

#### [MODIFY] [railwayStore.ts](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ClientWebAppIntefacciaUtente/src/store/railwayStore.ts)
- `initialize()`: fetch dal backend reale anziché mock
- Aggiungere azioni per CRUD tratte che chiamano API REST
- Le azioni admin (sopprimi treno, invia operatori) chiamano il backend

#### [MODIFY] [useSimulator.ts](file:///home/marco/Marco/Uni/Anno_3/reti2/progetto/corretto/codice/MonitoraggioEGestioneDelTrafficoFerroviario/ClientWebAppIntefacciaUtente/src/hooks/useSimulator.ts)
- Trasformare da simulatore locale a consumer WebSocket + polling REST
- Mantenere la logica di fault detection heartbeat lato frontend come fallback

#### [CONSERVATI] Tutti i file delle pagine, componenti UI, CSS, mockData.ts
- mockData.ts rimane come fallback quando il backend non è disponibile

---

## Verification Plan

### Manual Verification
1. Avviare Docker Mosquitto
2. Avviare Centrale Operativa → verifica che si collega a MQTT e PostgreSQL
3. Avviare Stazione → verifica heartbeat ricevuto dalla Centrale
4. Avviare Treno → verifica telemetria ricevuta dalla Centrale
5. Test curl sulle REST API sensori (conservate)
6. Test curl sulle nuove REST API della Centrale (CRUD tratte, dashboard, allarmi)
7. Avviare frontend → verifica collegamento WebSocket e visualizzazione dati reali
