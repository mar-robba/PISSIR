# Gap Analysis — Progetto vs Specifiche "Progetto finale 2025_26.pdf"

**Data analisi:** 05/08/2026 (revisione completa, sostituisce quella del 19/07/2026)
**File di riferimento:** `doc/Progetto finale 2025_26.pdf`
**Analisi bug collegata:** [diagnosi_bug.md](diagnosi_bug.md) — i codici B0xx citati qui sotto
rimandano a quel documento.

## Legenda

| Simbolo | Significato |
|---------|-------------|
| ✅ | Implementato correttamente |
| ⚠️ | Presente ma con limitazioni / bug che ne riducono l'efficacia |
| ❌ | Non implementato |

---

## 1. Componente Stazione (Sensor Node)

### 1.1 Rilevamento transiti

| Requisito | Stato | Note |
|-----------|-------|------|
| Rilevamento ingresso treno in stazione | ✅ | `StationGateway.riceviPassaggio()` (listener `railway/train/+/passaggio`) e `POST /stazione/sensore/treno` con tipo ENTRATA |
| Rilevamento uscita treno dalla stazione | ✅ | Stessa coppia di ingressi con tipo USCITA |
| Rilevamento numero convoglio | ✅ | Campo `trenoId` in tutti i payload di passaggio |
| Treno esce senza entrare (stazione di partenza) | ✅ | `IngestionService.onTransit()` righe 281-292: crea un transito puntuale con `tempoEntrata = tempoUscita` |
| Treno entra senza uscire (capolinea) | ✅ | Transito lasciato aperto (`tempoUscita` null) |

### 1.2 Gestione guasti temporanei (disconnessione)

| Requisito | Stato | Note |
|-----------|-------|------|
| Caching locale per reinvio dati a connessione ripristinata | ✅ | `LocalBuffer` (coda FIFO) + `StationGateway.inviaOppureBufferizza()` |
| Store-and-Forward con reinvio effettivo | ⚠️ | `flush()` funziona, ma in caso di errore rimette l'evento **in fondo** alla coda perdendo l'ordine (**B033**) e il flush è agganciato al tick di heartbeat, che dopo un errore MQTT muore per sempre (**B026**) |
| Heartbeat soppresso in offline → la Centrale rileva OFFLINE | ✅ | Filtro su `dbLocale.connessioneCentrale` in `HeartbeatGenerator` righe 101-108; simulabile con `POST /stazione/rete/offline` |

### 1.3 Guasto permanente (rottura binario, deragliamento)

| Requisito | Stato | Note |
|-----------|-------|------|
| Segnalazione immediata alla Centrale | ✅ | `StationGateway.inviaGuasto()` su `railway/alerts` con `tipoEvento=GUASTO` |
| Dalla centrale un tecnico invia operatori | ✅ | `POST /api/stazioni/{id}/manutenzione`: pubblica MAINTENANCE_DISPATCHED e chiude i guasti aperti |
| Durante il guasto la stazione segnala a ogni treno in entrata | ⚠️ | Implementato (`riceviPassaggio` righe 144-147) ma genera un guasto nuovo per ogni treno, senza deduplica (**B027**) |
| Il treno non riparte finché il guasto non è ripristinato | ⚠️ | Funziona per i guasti dichiarati dalla stazione; il treno però resta fermo **senza accumulare ritardo** se l'alert era arrivato mentre si trovava altrove (**B034**) |
| Gestione vie multiple (binari multipli) | ⚠️ | La colonna `binari` esiste su `Stazione` ma nessuna logica alloca binari: un guasto blocca l'intera stazione. Il PDF la dà come estensione facoltativa ("se volete gestire anche le vie multiple") |

### 1.4 Fail-stop e Heartbeat

| Requisito | Stato | Note |
|-----------|-------|------|
| Heartbeat/Keepalive tra Centrale e Stazione | ✅ | `HeartbeatGenerator` ogni 10s su `railway/station/{id}/heartbeat` |
| La Centrale rileva la stazione che smette di inviare heartbeat | ✅ | `FaultMonitor.controllaHeartbeat()`: OFFLINE + guasto automatico dopo 30s |
| **La stazione caduta viene segnalata ai treni** | ❌ | **Nuovo gap (B007).** Il FaultMonitor scrive il guasto solo su DB e WebSocket: non pubblica nulla su `railway/alerts`, quindi i treni non lo sanno e continuano a dirigersi verso una stazione che non risponde più. È il caso evidenziato in giallo nel PDF |
| Heartbeat/Keepalive tra Stazione e Sensori | ✅ | `POST /stazione/sensore/heartbeat` + timeout 30s in `HeartbeatGenerator.controllaSensori()` |
| Se i sensori non mandano keepalive la stazione si segnala guasta | ⚠️ | Invia un guasto **WARNING** e non imposta `dbLocale.stato = "GUASTA"`. Il PDF dice "segnalerebbe lo stato di *guasta* alla Centrale Operativa" |

### 1.5 Sensori fisici

| Requisito | Stato | Note |
|-----------|-------|------|
| Sensori RFID, pressione, fotocellule (simulati) | ✅ | `StationIngestion`: `POST /stazione/sensore/treno`, `/sensore/guasto`, `/sensore/heartbeat`. Scelta dichiarata: i sensori parlano con la stazione via REST, la stazione con la Centrale via MQTT |

---

## 2. Componente Treno

| Requisito | Stato | Note |
|-----------|-------|------|
| Treno come processo/micro-servizio indipendente | ✅ | Processo Quarkus separato, ID da `args[0]` con fallback sulla property `treno.id` |
| Verifica dell'ID del treno sul DB centrale prima di operare | ⚠️ | Il pattern richiesta/risposta MQTT c'è ed è simmetrico a quello della stazione, **ma la telemetria parte prima della validazione e fa creare il treno nel DB dalla Centrale**: la verifica può auto-validarsi (**B024**) |
| Il treno si presenta ai sensori delle stazioni | ✅ | `TrainGateway.notificaPassaggioStazione()` su `railway/train/{id}/passaggio` |
| Sistema di comunicazione in ingresso (guasti/ritardi) | ✅ | `TrainGateway.riceviAlert()`: GUASTO / RESOLVED / STOP / ITINERARIO_AGGIORNATO |
| Digital Twin con stato interno (ID, stazione precedente, prossima) | ✅ | `TrainDB`: `faseViaggio`, `direzione`, `indiceStazione`, `stazioneCorrente`, `prossimaStazione` |
| Itinerario pre-caricato alla partenza | ✅ | `tentaCaricamentoItinerario()` → `GET /api/treni/{id}/itinerario`, retry ogni 15s |
| La stazione chiede alla Centrale la prossima stazione | ⚠️ | `GET /api/prossima-stazione` esiste e funziona, ma nessuna stazione la chiama: è l'alternativa che il PDF elenca come *opzionale* rispetto all'itinerario pre-caricato |
| La stazione manda al treno un messaggio con la prossima stazione | ❌ | Non implementato: scelta architetturale (itinerario completo scaricato al boot). Il PDF ammette esplicitamente entrambe le strade |
| Segnali di prossimità ai sensori tramite MQTT | ✅ | Topic `railway/train/{id}/passaggio` |
| Motore a eventi discreti | ✅ | `TrainJourneyEngine.tick()` ogni secondo, macchina a stati IN_STAZIONE / IN_VIAGGIO / BLOCCATO_GUASTO_STAZIONE / TERMINATO |
| Tempo di percorrenza fra stazioni | ✅ | `tempoTrattaMinuti()` con fattore di accelerazione configurabile (`viaggio.fattore.accelerazione`) |
| Trigger al sensore della stazione allo scadere del tempo | ✅ | `arrivoInStazione()` pubblica ENTRATA quando `progressPercent >= 100` |
| Inversione al capolinea con sosta doppia | ✅ | `arrivoInStazione()` righe 408-418 |
| Gestione ritardi | ⚠️ | `tickBloccato()` accumula un minuto simulato per volta, ma non viene raggiunto nel caso descritto in **B034** |
| Interpolazione GPS fra stazioni | ✅ | Interpolazione lineare in `tickInViaggio()` |
| Passeggeri simulati a ogni fermata | ✅ | 50-400 passeggeri rigenerati a ogni ENTRATA |
| Console dei sensori di bordo | ❌ | La classe `Sensori` esiste ma non viene mai avviata (**B031**): i guasti di bordo si iniettano solo via REST |

---

## 3. Centrale Operativa (OCC)

| Requisito | Stato | Note |
|-----------|-------|------|
| Ingestion: ricezione flussi MQTT | ✅ | `IngestionService`: canali `telemetry-in`, `heartbeat-in`, `transit-in`, `passaggio-in`, `alerts-in` |
| Robustezza dell'ingestion | ⚠️ | Un errore di persistenza al commit sfugge al `try/catch` e può spegnere il canale reattivo (**B025**) |
| Processing: calcolo ritardi e previsioni di arrivo | ✅ | Ritardo propagato da telemetria e passaggi; `progressPercent` per la stima di avanzamento |
| Storage: database PostgreSQL | ✅ | `schema.sql`: Stazione, Tratte, Itinerari, Itinerario_Tratta, Treni, Transiti, Guasti + 5 tabelle storiche |
| Storico transiti | ✅ | `StoricoTransito` a ogni apertura/chiusura di transito |
| Storico stato treni (solo ai cambiamenti) | ✅ | `IngestionService` righe 171-178 |
| Storico stato stazioni | ⚠️ | Scritto a **ogni** heartbeat invece che al cambio di stato (**B021**) |
| Storico guasti | ✅ | `StoricoGuasto` con apertura e chiusura |
| Memorizzazione tratte andata/ritorno | ✅ | `Itinerario` + `ItinerarioTratta` ordinate; il ritorno è l'itinerario percorso al contrario (il twin inverte la direzione al capolinea) |
| Alerting: generazione automatica di allarmi | ⚠️ | `FaultMonitor` con 3 job (heartbeat, treni fermi, snapshot KPI), ma gli allarmi restano dentro la Centrale: nessuna pubblicazione MQTT verso il campo (**B007**) |
| Allarme treno fermo fra due stazioni per troppo tempo | ✅ | `controllaTreniFermi()`, timeout 90s configurabile |
| Tecnico: visualizza traffico e tratte, invia operatori | ✅ | Dashboard + `POST /api/stazioni/{id}/manutenzione` |
| Amministratore: CRUD tratte, modifica stazioni, sopprime treni | ✅ | CRUD completo su stazioni/treni/tratte elementari/itinerari + `POST /api/treni/{id}/sopprimi` |
| Modifica di un itinerario propagata ai treni | ⚠️ | I treni ancora assegnati ricevono ITINERARIO_AGGIORNATO; quelli **sganciati** no e continuano con la tratta vecchia (**B006**) |
| Due tipologie di utenti (tecnico/amministratore) | ⚠️ | `AuthController` distingue i ruoli, ma il token non viene verificato da nessuna parte e il frontend non lo invia: **gli endpoint sono di fatto pubblici** (**B019**) |

---

## 4. Protocolli di Comunicazione

| Requisito | Stato | Note |
|-----------|-------|------|
| MQTT per i dati dall'edge al core | ✅ | Tutta la comunicazione stazione/treno → Centrale passa da Mosquitto |
| HTTP per le API REST degli utenti web | ✅ | Endpoint `/api/*` sulla 8781, consumati dalla SPA |
| TLS per MQTT (porta 8883) | ⚠️ | Configurato per telemetria, transiti, heartbeat, alert e passaggi, **ma non per i canali di validazione dell'ID**: con `-Dquarkus.profile=tls` i nodi edge non riescono più a validarsi e non partono (**B023**) |
| HTTPS per le API (porta 8444) | ⚠️ | Il profilo `tls` espone la 8444 con certificato firmato dalla CA del progetto, ma la SPA punta comunque a `http://localhost:8781` e `ws://localhost:8781` (URL fissi in `apiClient.ts:18` e `websocketClient.ts:143`): il traffico browser↔Centrale resta in chiaro anche in profilo TLS |

---

## 5. Simulazione Dinamica

| Requisito | Stato | Note |
|-----------|-------|------|
| Ecosistema di agenti (non un unico blocco di codice) | ✅ | 3 microservizi indipendenti + broker + frontend, ognuno con il proprio processo |
| Digital Twin dei treni come processi separati | ✅ | Un processo Quarkus per convoglio, con ID passato da riga di comando |
| Istanze multiple sulla stessa macchina | ⚠️ | Porte HTTP fisse (8081/8082): il secondo processo va lanciato con `-Dquarkus.http.port=...`, cosa da scrivere nelle istruzioni di avvio (**B037**) |

---

## 6. Varianti Ammesse (impatto sul punteggio)

Attenzione a come si legge la tabella: la colonna **"Costo della variante"** è la penalità che
il PDF assegna a chi *sceglie* quella semplificazione; la colonna **"Penalità applicata"** dice
quanto costa **a questo progetto**, cioè zero ogni volta che la semplificazione non è stata presa.

| Variante (semplificazione ammessa dal prof) | Costo della variante | Il progetto l'ha adottata? | Penalità applicata |
|----------|----------|-------|---------|
| 1: SPA o WEB-APP | 0 | Sì (SPA React/Vite) | 0 — la variante non è penalizzata comunque |
| 2: autenticazione tradizionale invece di OAUTH2/Keycloak | 0 | Sì (`AuthController` con utenti nella tabella `Utenti`) | 0 — **Keycloak non serve**, la variante 2 lo sostituisce esplicitamente. Resta però da sistemare la protezione degli endpoint (B019) |
| 3: DBMS via JDBC | 0 | Sì (PostgreSQL via JDBC/Panache) | 0 |
| 4: stazioni che comunicano via REST invece di MQTT | −5 | **No**: le stazioni usano MQTT | 0 |
| 5: Postman invece della SPA | −2 | **No**: la SPA è stata sviluppata | 0 |
| 6: nessuna comunicazione TLS | −5 | **No**: profilo `%tls` implementato | 0, ma **a rischio**: se in sede d'esame il profilo TLS non parte per B023, la variante di fatto non è dimostrabile e la penalità potrebbe scattare |
| 7: monitoraggio stazioni in un unico servizio | −5 | **No**: servizi separati (`StationGateway`, `HeartbeatGenerator`, `LocalBuffer`, `StationIngestion`) | 0 |

**Totale penalità previste: 0 punti** (a patto di sistemare B023 per la variante 6).

---

## 7. Riepilogo dei gap

### Gap 1 (CRITICO, nuovo) — Il profilo TLS non è dimostrabile così com'è
* **Cosa manca:** `ssl=true` sui canali di validazione dei tre moduli (**B023**).
* **Impatto:** alto. È l'unica variante penalizzata (−5) che il progetto dichiara di coprire;
  se in demo il profilo TLS non parte, la copertura non è dimostrabile.
* **Raccomandazione:** correggere le proprietà e provare la demo completa con
  `-Dquarkus.profile=tls` su tutti e tre i servizi.

### Gap 2 (CRITICO, nuovo) — La stazione in fail-stop non blocca i treni
* **Cosa manca:** la pubblicazione MQTT dell'allarme generato dal `FaultMonitor` (**B007**).
* **Impatto:** alto. È il requisito evidenziato in giallo nel PDF: la Centrale deve accorgersi
  della stazione muta *e* il sistema deve reagire. Oggi se ne accorge solo la dashboard.
* **Raccomandazione:** pubblicare l'alert su `railway/alerts` con `severita = CRITICAL`, così
  la logica di blocco già scritta in `TrainGateway`/`TrainJourneyEngine` entra in funzione
  senza modifiche lato treno.

### Gap 3 (ALTO, nuovo) — La verifica dell'ID può auto-validarsi
* **Cosa manca:** il filtro sulla telemetria prima della validazione e il divieto di creare
  treni dalla telemetria (**B024**).
* **Impatto:** medio-alto. È una domanda molto probabile all'interrogazione ("cosa succede se
  lancio un treno con un ID che non esiste?"): oggi la risposta corretta dipende da un
  ordine di arrivo dei messaggi.
* **Raccomandazione:** copiare il filtro `stazioneRiconosciuta` già usato dalla stazione.

### Gap 4 (ALTO) — Autorizzazione assente sulle API
* **Cosa manca:** verifica del token e distinzione dei permessi lato server (**B019**).
* **Impatto:** medio. La variante 2 consente il login tradizionale, ma "due tipologie di
  utenti" implica che l'amministratore possa fare cose che il tecnico non può.
* **Raccomandazione:** un filtro JAX-RS minimale, oppure dichiarare il limite in relazione.

### Gap 5 (MEDIO) — Keepalive sensori: WARNING invece di stato GUASTA
* **Cosa manca:** `HeartbeatGenerator.controllaSensori()` segnala con severità WARNING e non
  imposta `dbLocale.stato = "GUASTA"`.
* **Impatto:** minore. La segnalazione c'è, con gravità ridotta per evitare falsi positivi.
* **Raccomandazione:** o si alza a CRITICAL marcando la stazione GUASTA (aderenza letterale al
  PDF), o si scrive in relazione perché si è scelto diversamente.

### Gap 6 (MEDIO) — Store-and-forward non ordinato
* **Cosa manca:** reinvio FIFO garantito dopo un errore (**B033**) e sopravvivenza del flush a
  un errore MQTT (**B026**).
* **Impatto:** medio: è proprio la funzionalità che il PDF chiede per i guasti temporanei.
* **Raccomandazione:** rimettere in testa l'evento fallito e sganciare il flush dal tick di
  heartbeat.

### Gap 7 (MINORE) — Vie multiple (binari) non gestite
* **Cosa manca:** allocazione dei binari; un guasto blocca l'intera stazione.
* **Impatto:** nullo ai fini della sufficienza: il PDF la presenta come estensione facoltativa.
* **Raccomandazione:** citarla fra gli sviluppi futuri.

### Gap 8 (MINORE) — Flusso "stazione → centrale → prossima stazione → treno" non usato
* **Cosa manca:** la stazione non chiama `GET /api/prossima-stazione` e non inoltra nulla al treno.
* **Impatto:** nullo: il PDF elenca le due strategie come alternative ("oppure").
* **Raccomandazione:** documentare la scelta dell'itinerario pre-caricato, ricordando che
  l'endpoint alternativo è comunque implementato e mostrabile.

### Gap 9 (MEDIO) — Test end-to-end completo non ancora eseguito
* **Cosa manca:** la prova integrata broker + centrale + N stazioni + N treni + SPA, sia in
  profilo di default sia in profilo `tls`.
* **Impatto:** medio: molti dei bug qui sopra (B023, B024, B026) si vedono solo a sistema acceso.
* **Raccomandazione:** eseguirla dopo le correzioni dei Gap 1-3 e allegare gli screenshot in relazione.

---

## 8. Conclusione

Tutti i **requisiti funzionali obbligatori** del PDF sono presenti nel codice: nodi stazione
con sensori e caching locale, treni come processi/digital twin indipendenti con motore a
eventi discreti, Centrale con ingestion MQTT, storage PostgreSQL, storici, alerting
automatico e due tipologie di utenti, il tutto su MQTT + REST con SPA di controllo.

Rispetto alla revisione precedente il quadro cambia però su un punto importante: alcuni
requisiti risultano implementati "a metà catena". Il caso più serio è il **fail-stop della
stazione** (Gap 2), rilevato dalla Centrale ma mai propagato ai treni, e la **variante TLS**
(Gap 1), configurata ma non funzionante sui canali di validazione. Sono entrambi difetti di
poche righe, ma toccano requisiti evidenziati dal prof.

### Riepilogo finale

| Categoria | ✅ | ⚠️ | ❌ |
|-----------|----|-----|-----|
| Stazione | 13 | 5 | 1 |
| Treno / Digital Twin | 12 | 3 | 2 |
| Centrale Operativa | 10 | 5 | 0 |
| Protocolli | 2 | 2 | 0 |
| Simulazione | 2 | 1 | 0 |
| Varianti | 7 | 0 | 0 |
| **Totale** | **46** | **16** | **3** |

I tre ❌ sono: la propagazione ai treni della stazione caduta (da correggere, Gap 2), il
messaggio diretto stazione→treno con la prossima stazione (alternativa ammessa dal PDF) e la
console dei sensori di bordo mai avviata (B031).
