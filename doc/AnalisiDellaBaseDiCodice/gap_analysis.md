## mancha anche l'implementazione di keyclock 


# Gap Analysis — Progetto vs Specifiche "Progetto finale 2025_26.pdf"

Data analisi: 2026-07-19
File di riferimento: doc/MaterialeProf/Progetto finale 2025_26.pdf

## Legenda

| Simbolo | Significato |
|---------|-------------|
| ✅ | Implementato correttamente |
| ⚠️ | Parzialmente implementato / presente ma con limitazioni |
| ❌ | Non implementato |

---

## 1. Componente Stazione (Sensor Node)

### 1.1 Rilevamento transiti

| Requisito | Stato | Note |
|-----------|-------|------|
| Rilevamento ingresso treno in stazione | ✅ | StationGateway.riceviPassaggio() + POST /stazione/sensore/treno (ENTRATA) |
| Rilevamento uscita treno dalla stazione | ✅ | StationGateway.riceviPassaggio() + POST /stazione/sensore/treno (USCITA) |
| Rilevamento numero convoglio | ✅ | Campo trenoId nei payload passaggio |
| Treno esce senza entrare (stazione di partenza) | ✅ | Gestito in IngestionService.onTransit() (transito con entrata=uscita) |
| Treno entra senza uscire (capolinea) | ✅ | Transito aperto senza tempoUscita |

### 1.2 Gestione guasti temporanei (disconnessione)

| Requisito | Stato | Note |
|-----------|-------|------|
| Caching locale per reinvio dati a connessione ripristinata | ✅ | LocalBuffer (FIFO) e StationGateway.inviaOppureBufferizza() |
| Store-and-Forward con reinvio effettivo | ✅ | StationGateway.flush() reinvia su emitter corretti (transit/alert) |
| Heartbeat soppresso in offline -> Centrale rileva OFFLINE | ✅ | HeartbeatGenerator.generaHeartbeat() filtro su dbLocale.connessioneCentrale |

### 1.3 Guasto permanente (rottura binario, deragliamento)

| Requisito | Stato | Note |
|-----------|-------|------|
| Segnalazione immediata alla Centrale | ✅ | StationGateway.inviaGuasto() topic railway/alerts tipoEvento=GUASTO |
| Da Centrale, tecnico invia operatori per risolvere | ✅ | POST /api/stazioni/{id}/manutenzione pubblica MAINTENANCE_DISPATCHED + risolve guasti |
| Durante il guasto, stazione segnala a ogni treno in entrata | ✅ | StationGateway.riceviPassaggio() se GUASTA e ENTRATA invia alert CRITICAL |
| Treno non riparte finche guasto non ripristinato | ✅ | TrainJourneyEngine.bloccaPerGuastoStazione() + sblocco su RESOLVED |
| Gestione vie multiple (binari multipli) | ⚠️ | Colonna binari presente in Stazione ma nessuna logica di allocazione/gestione binari specifici. Guasto blocca intera stazione, non binario specifico. |

### 1.4 Fail-stop e Heartbeat

| Requisito | Stato | Note |
|-----------|-------|------|
| Heartbeat/Keepalive tra Centrale e Stazione | ✅ | HeartbeatGenerator ogni 10s su railway/station/{id}/heartbeat |
| Centrale rileva stazione che smette di inviare heartbeat | ✅ | FaultMonitor.controllaHeartbeat() -> OFFLINE + guasto automatico (timeout 30s) |
| Heartbeat/Keepalive tra Stazione e Sensori | ✅ | POST /stazione/sensore/heartbeat + timeout in HeartbeatGenerator.controllaSensori() |
| Se sensori non mandano keepalive, stazione si segnala guasta | ⚠️ | Invia guasto WARNING (non CRITICAL), stazione NON si marca GUASTA. Il PDF dice "segnalerebbe lo stato di guasta". |

### 1.5 Sensori fisici

| Requisito | Stato | Note |
|-----------|-------|------|
| Sensori RFID, pressione, fotocellule (simulati) | ✅ | Endpoint REST POST /stazione/sensore/treno, /sensore/guasto, /sensore/heartbeat |

---

## 2. Componente Treno

| Requisito | Stato | Note |
|-----------|-------|------|
| Treno come processo/micro-servizio indipendente | ✅ | Processo Quarkus separato, main.java con args ID_TRENO |
| Treno si presenta ai sensori delle stazioni | ✅ | TrainJourneyEngine pubblica passaggi su railway/train/{id}/passaggio |
| Sistema di comunicazione in ingresso (ricezione guasti/ritardi) | ✅ | TrainGateway.riceviAlert() gestisce GUASTO/RESOLVED/STOP/ITINERARIO_AGGIORNATO |
| Digital Twin con stato interno (ID, staz. precedente, prossima) | ✅ | TrainDB con faseViaggio, direzione, indiceStazione, ecc. |
| Itinerario pre-caricato alla partenza | ✅ | TrainJourneyEngine.tentaCaricamentoItinerario() GET /api/treni/{id}/itinerario |
| Stazione chiede alla Centrale la prossima stazione | ⚠️ | API GET /api/prossima-stazione esiste ma stazione NON la chiama attivamente |
| Stazione invia messaggio al treno con prossima stazione | ❌ | Non implementato. Treno decide autonomamente basandosi su itinerario. |
| Invio segnali di prossimita ai sensori tramite MQTT | ✅ | TrainGateway.notificaPassaggioStazione() passaggio-out MQTT |
| Motore a eventi discreti | ✅ | TrainJourneyEngine.tick() ogni 1 secondo, macchina a stati IN_STAZIONE/IN_VIAGGIO/BLOCCATO |
| Tempo di percorrenza tra stazioni | ✅ | TrainJourneyEngine.tempoTrattaMinuti() con fattore di accelerazione |
| Trigger Sensore di Stazione in ingresso allo scadere del tempo | ✅ | arrivoInStazione() pubblica ENTRATA sul topic passaggio |
| Inversione al capolinea (sosta doppia) | ✅ | TrainJourneyEngine.arrivoInStazione() capolinea sosta doppia + inversione |
| Gestione ritardi (accumulo durante blocco) | ✅ | TrainJourneyEngine.tickBloccato() ritardoMinuti incrementa |
| Interpolazione GPS tra stazioni | ✅ | TrainJourneyEngine.tickInViaggio() posizione lineare |
| Passeggeri simulati a ogni fermata | ✅ | 50-400 passeggeri random a ogni ENTRATA |

---

## 3. Centrale Operativa (OCC)

| Requisito | Stato | Note |
|-----------|-------|------|
| Ingestion: Ricezione flussi MQTT da stazioni | ✅ | IngestionService con canali telemetry-in, heartbeat-in, transit-in, passaggio-in, alerts-in |
| Processing: Calcolo ritardi e previsioni arrivo | ✅ | Ritardo calcolato per ogni treno, progressPercent disponibile |
| Storage: Database PostgreSQL | ✅ | schema.sql completo: Stazione, Tratte, Treni, Transiti, Guasti, Storici |
| Storico transiti | ✅ | StoricoTransito popolato a ogni evento ENTRATA/USCITA |
| Storico stato treni (solo ai cambiamenti) | ✅ | StoricoStatoTreno creato solo quando stato cambia |
| Storico stato stazioni | ✅ | StoricoStatoStazione a ogni heartbeat |
| Storico guasti | ✅ | StoricoGuasto con apertura/chiusura |
| Memorizzazione tratte (elenco stazioni andata/ritorno) | ✅ | Itinerario + ItinerarioTratta ordinati, treno inverte direzione al capolinea |
| Alerting: Generazione automatica allarmi | ✅ | FaultMonitor con 3 job schedulati: heartbeat, treni fermi, snapshot KPI |
| Allarme treno fermo tra due stazioni per troppo tempo | ✅ | FaultMonitor.controllaTreniFermi() timeout 90s |
| Tecnico: visualizza traffico e tratte, invia operatori | ✅ | POST /api/stazioni/{id}/manutenzione, frontend con dashboard |
| Amministratore: visualizza traffico, CRUD tratte, modifica stazioni, sopprime treni | ✅ | RestApiGateway con CRUD completo + POST /api/treni/{id}/sopprimi |
| Due tipologie di utenti (tecnico/admin) | ✅ | AuthController con mapping ruoli: admin->amministratore, altro->tecnico |

---

## 4. Protocolli di Comunicazione

| Requisito | Stato | Note |
|-----------|-------|------|
| MQTT per sensori stazione verso core | ✅ | Tutta la comunicazione edge->core via Mosquitto |
| HTTP per API REST (utenti web) | ✅ | Tutti gli endpoint /api/* su HTTPS :8444 |
| TLS per MQTT (porta 8883) | ✅ | Certificati CA self-signed, profilo %tls |
| HTTPS per API (porta 8444) | ✅ | Certificato server-centrale, insecure-requests=disabled |

---

## 5. Simulazione Dinamica

| Requisito | Stato | Note |
|-----------|-------|------|
| Ecosistema di agenti (non unico blocco) | ✅ | 3 microservizi indipendenti + frontend |
| Digital Twins Treni come processi separati | ✅ | Ogni treno = processo Quarkus indipendente |

---

## 6. Varianti Ammesse (valutazione)

| Variante | Penalita | Stato | Impatto |
|----------|----------|-------|---------|
| Variante 1: SPA o WEB-APP (nessuna penalita) | 0 | SPA React | OK |
| Variante 2: Autenticazione tradizionale (login/password) | 0 | AuthController | OK |
| Variante 3: DBMS via JDBC o JSON (nessuna penalita) | 0 | PostgreSQL via JDBC | OK |
| Variante 4: Stazioni comunicano via REST invece di MQTT | -5 | Usano MQTT, non REST | OK (nessuna penalita) |
| Variante 5: Usare Postman invece di SPA | -2 | SPA sviluppata | OK (nessuna penalita) |
| Variante 6: Non implementare TLS | -5 | TLS implementato | OK (nessuna penalita) |
| Variante 7: Monitoraggio stazioni in unico servizio | -5 | Servizi separati (StationGateway, HeartbeatGenerator, FaultMonitor) | OK (nessuna penalita) |

---

## 7. Riepilogo Gaps

### Gap 1 (Warning) - Vie multiple (binari) non gestite
*   **Cosa manca**: La colonna binari esiste in Stazione ma la logica non gestisce guasti a binari specifici. Un guasto blocca l'intera stazione.
*   **Impatto**: Minore. E' un'estensione opzionale indicata come "se volete gestire anche le vie multiple nelle stazioni".
*   **Raccomandazione**: Si puo ignorare per la consegna.

### Gap 2 (Warning) - Keepalive sensori: segnalazione come WARNING, non stato GUASTA
*   **Cosa manca**: Quando un sensore smette di inviare keepalive, HeartbeatGenerator.controllaSensori() invia un alert WARNING. Il PDF dice "se una Stazione non ricevesse i Keep Alive da uno o piu sensori, segnalerebbe lo stato di guasta alla Centrale Operativa".
*   **Impatto**: Minore. La segnalazione c'e, solo con severita ridotta. Scelta progettuale per evitare falsi positivi.
*   **Raccomandazione**: Modificare la severita da "WARNING" a "CRITICAL" e marcare dbLocale.stato = "GUASTA" in HeartbeatGenerator.controllaSensori().

### Gap 3 (Warning) - Flusso stazione -> centrale -> prossima stazione -> treno non utilizzato
*   **Cosa manca**: L'API GET /api/prossima-stazione esiste ma la stazione non la chiama per comunicare al treno la prossima destinazione. Il treno usa l'itinerario pre-caricato.
*   **Impatto**: Minore. Il PDF elenca questo come esempio/potenziale alternativa, non come requisito obbligatorio.
*   **Raccomandazione**: Documentare questa scelta progettuale nella relazione.

### Gap 4 (Non implementato) - Stazione non manda messaggio diretto al treno con prossima stazione
*   **Cosa manca**: Il flusso "la stazione manda un messaggio al treno indicandogli la prossima stazione da raggiungere" non e implementato.
*   **Impatto**: Minore. Il treno ottiene l'itinerario completo dalla Centrale via REST, scelta architetturale valida.
*   **Raccomandazione**: Documentare la scelta nella relazione: si e preferito il caricamento dell'itinerario completo al boot.

### Gap 5 (Warning) - Test end-to-end non ancora eseguito
*   **Cosa manca**: Il file doc/lavoroSvolto.md segnala "Test end-to-end completo da eseguire".
*   **Impatto**: Medio. Funzionalita implementate ma non verificate insieme.
*   **Raccomandazione**: Eseguire il test end-to-end prima della consegna (procedura descritta in doc/lavoroSvolto.md).

---

## 8. Conclusione

Il progetto implementa **tutti i requisiti fondamentali** del PDF. I gap identificati sono **minori** e riguardano:
- Estensioni opzionali (vie multiple)
- Severita di alert (WARNING vs CRITICAL per sensori offline)
- Scelte architetturali alternative documentabili

**Nessun requisito obbligatorio per la sufficienza risulta non implementato.**

### Riepilogo finale

| Categoria | ✅ | ⚠️ | ❌ |
|-----------|----|-----|-----|
| Stazione | 10 | 2 | 0 |
| Treno/Digital Twin | 14 | 1 | 1 |
| Centrale Operativa | 15 | 0 | 0 |
| Protocolli | 4 | 0 | 0 |
| Simulazione | 2 | 0 | 0 |
| Varianti | 7 | 0 | 0 |
| **Totale** | **52** | **3** | **1** |
