# Correzioni applicate — bug e gap

**Data intervento:** 05/08/2026
**Documenti di partenza:** [diagnosi_bug.md](diagnosi_bug.md) (25 bug) e [gap_analysis.md](gap_analysis.md) (9 gap)
**Non toccato:** `critica_scelte_progettuali.md` (per scelta, le sue considerazioni sono rimaste fuori da questo lavoro)

Questo file racconta cosa è stato cambiato, dove e soprattutto **perché**, con in fondo le
prove fatte a sistema acceso. Non modifica i due documenti di analisi: sono la fotografia
del "prima", questo è il "dopo".

---

## 1. Sommario

| | Bug/gap | Stato |
| --- | --- | --- |
| 🔴 CRITICI | B023, B024, B007, B006, B004 | 5/5 corretti |
| 🟠 ALTA | B019, B025, B026, B027, B028 | 5/5 corretti |
| 🟡 MEDIA | B029, B030, B013, B021, B031, B032, B033, B034, B035 | 9/9 corretti |
| 🔵 BASSA | B005, B020, B036, B037, B038, B039 | 6/6 corretti o dichiarati |
| Gap | Gap 1-9 | 9/9 chiusi (3 come scelta dichiarata) |

Verifiche: i tre moduli Maven compilano e si impacchettano (`./mvnw -DskipTests package`),
`npx tsc --noEmit` passa pulito, e il sistema è stato provato acceso sia in profilo di
default sia in profilo `tls` (§5).

---

## 2. Cosa è stato cambiato, bug per bug

### 🔴 B023 — TLS: i canali di validazione erano rimasti in chiaro

**File:** i tre `application.properties`.

Sotto il profilo `tls` la porta diventa 8883 per tutti i canali, ma `ssl=true` +
truststore erano stati scritti solo per alcuni. I canali della validazione dell'ID
aprivano quindi una socket in chiaro contro un listener TLS: handshake fallito, risposta
mai ricevuta, treni e stazioni bloccati per sempre nel loop di `main.run()`.

Aggiunte le tre righe mancanti per **otto** canali:

* Treni: `validation-request-out`, `validation-response-in`
* Stazioni: `validation-request-out`, `validation-response-in`
* Centrale: `validation-in`, `validation-response`, `validation-station-in`, `validation-station-response`

In ogni file c'è ora un commento che avvisa: sotto `%tls` **tutti** i canali vanno elencati,
dimenticarne uno non dà errore di configurazione, dà un nodo che non parte.

---

### 🔴 B024 — La telemetria aggirava il controllo dell'ID

**File:** `Treni/.../TrainElab.java`, `ServeCentraleOperativa/.../IngestionService.java`.

Due correzioni, una per lato:

1. **Lato treno:** il flusso di telemetria ora è filtrato come già faceva l'heartbeat della
   stazione:

   ```java
   .filter(tick -> { if (!trainDB.trenoRiconosciuto) { ...; return false; } return true; })
   ```

   Finché la Centrale non conferma l'ID non parte nemmeno un frame.

2. **Lato Centrale:** `onTelemetry()` **non crea più** il treno. Se il convoglio non è in
   cache (che è la fotografia della tabella `Treni`) il frame viene scartato con un warning:
   i treni si creano solo dalla pagina di amministrazione. Stessa cosa in `onHeartbeat()`
   per le stazioni, per simmetria.

Prima bastava lanciare un processo con un ID inventato e, se la telemetria arrivava prima
della richiesta di validazione, la Centrale creava la riga e poi rispondeva "sì, esiste":
**il controllo si auto-validava**.

---

### 🔴 B007 — La stazione caduta non veniva segnalata ai treni (Gap 2)

**File:** `FaultMonitor.java`, `IngestionService.java`.

Il `FaultMonitor` rilevava la stazione muta ma chiudeva il giro con la sola WebSocket:
i treni non ne sapevano nulla e ci andavano dentro lo stesso. Adesso:

* `creaGuastoAutomatico()` notifica su **due** strade: `ingestion.broadcastAlert()` per la
  dashboard e il nuovo `ingestion.pubblicaGuastoSuMqtt()` per il campo (topic `railway/alerts`);
* il guasto per heartbeat perso nasce con severità **CRITICAL** (prima `warning`), perché
  `TrainGateway.riceviAlert()` blocca i treni solo sui CRITICAL. Così la logica di blocco
  già scritta nel treno entra in funzione **senza modificare una riga lato treno**;
* il payload porta `"origine":"CENTRALE"` e `onAlert()` scarta gli alert con questo campo:
  la Centrale è sottoscritta al proprio stesso topic e senza il marcatore si sarebbe
  riascoltata da sola creando un secondo `Guasto` per un allarme appena scritto da lei.

**Aggiunta necessaria (chiusura del ciclo).** Rendendo CRITICAL il guasto, i treni restavano
bloccati per sempre anche dopo il ritorno della stazione, perché nessuno chiudeva il guasto
automatico. Quindi `onHeartbeat()` adesso riconosce il ritorno del battito di una stazione
che era OFFLINE, chiude il guasto aperto dalla Centrale e pubblica `RESOLVED` su MQTT.
Per distinguere i "suoi" guasti da quelli dichiarati dalla stazione si usa il prefisso del
messaggio (`IngestionService.MSG_HEARTBEAT_PERSO = "Heartbeat assente:"`): i guasti veri
segnalati dal campo restano aperti e li chiude un operatore, come prima.

---

### 🔴 B006 — I treni sganciati non ricevevano ITINERARIO_AGGIORNATO

**File:** `RestApiGateway.updateTratta()`.

`assegnaTreni(..., true)` sgancia i treni non più in elenco, e la query Panache che veniva
dopo non li trovava più: proprio i treni tolti dall'itinerario non venivano avvisati e
continuavano a girare all'infinito sulla tratta vecchia. Ora gli id vengono letti **prima**
in un `LinkedHashSet`, poi si aggiungono quelli nuovi e si notifica l'unione dei due insiemi.

---

### 🔴 B004 — Doppio evento TRANSIT sul WebSocket

**File:** `IngestionService.onPassaggio()`.

Lo stesso passaggio fisico arriva alla Centrale da due strade (dal treno su
`railway/train/{id}/passaggio` e dalla stazione su `railway/station/{id}/transit`) ed
entrambe chiamavano `broadcastTransit`. Il frontend riceveva due eventi identici, con
addirittura la stessa chiave React quando arrivavano nello stesso millisecondo.

Scelta fatta (quella coerente con l'architettura del prof): **la sorgente ufficiale per la
UI è la stazione**, perché è il sensore di terra a rilevare il transito. `onPassaggio()`
continua ad aggiornare cache e posizione sulla tratta ma non fa più il broadcast.

> Conseguenza da sapere per la demo: i transiti compaiono nella dashboard solo per le
> stazioni il cui processo è acceso. È corretto — senza il nodo stazione non c'è il sensore
> che rileva il passaggio — ma va tenuto presente quando si avviano solo alcune stazioni.

---

### 🟠 B019 — Nessun endpoint REST era protetto (Gap 4)

**File nuovi:** `gateway/SessioniAttive.java`, `gateway/FiltroAutorizzazione.java`.
**Modificati:** `AuthController.java`, `apiClient.ts`, `authStore.ts`, `AdminApiTest.java`.

Il token del login veniva generato e buttato via: nessuno lo conservava, nessuno lo
verificava, e un `curl -X DELETE .../api/treni/TRN001` funzionava senza credenziali.

* `SessioniAttive` è un registro in RAM `token -> (matricola, ruolo)`;
* `AuthController.login()` registra lì il token; aggiunto `POST /api/auth/logout` che lo invalida;
* `FiltroAutorizzazione` è un `ContainerRequestFilter` JAX-RS con priorità di autenticazione:

  | Chi | Cosa può fare |
  | --- | --- |
  | chiunque | `POST /api/auth/login` e le preflight `OPTIONS` |
  | tecnico + amministratore | tutte le GET, più `sopprimi`, `risolvi`, `manutenzione` (i comandi operativi del PDF) |
  | solo amministratore | tutte le altre scritture: CRUD di stazioni, treni, tratte elementari e itinerari |

  Senza token → **401**, con ruolo insufficiente → **403**, entrambi nel formato
  `{"errore": "..."}` già usato dagli altri endpoint.
* Nel frontend `apiClient` tiene il token in un modulo (`setAuthToken`) e lo allega a tutte
  le chiamate con l'helper `authHeaders()`; l'`authStore` lo imposta al login e lo azzera
  (chiamando anche il logout REST) all'uscita.
* `AdminApiTest` fa il login come MAT001 e allega il token; è stato aggiunto un test che
  verifica il **401 senza token**.

**Eccezione dichiarata.** Restano aperte due sole GET, riconosciute da
`FiltroAutorizzazione.endpointDiCampo()`: `GET /api/treni/{id}/itinerario` e
`GET /api/prossima-stazione`. Sono gli endpoint macchina-a-macchina usati dal digital twin,
che non fa login: la sua "autenticazione" è la validazione dell'ID via MQTT fatta all'avvio.
Non espongono nessuna scrittura. (Questa cosa è saltata fuori proprio durante la prova a
sistema acceso: il treno prendeva 401 e non riusciva più a scaricare l'itinerario.)

---

### 🟠 B025 — Un errore di persistenza poteva spegnere un canale MQTT

**File:** `IngestionService.java`, `ExistIdForEdge.java`, `ExistIdForEdgeStazione.java`.

Con `@Transactional` sul metodo `@Incoming`, il commit avviene all'uscita del metodo, cioè
**fuori** dal `try/catch`: una violazione di vincolo risaliva al connettore reattivo e il
canale terminava in errore. Da quel momento la Centrale smetteva di ricevere su quel topic
fino al riavvio (in demo si vede come "a un certo punto la dashboard si è congelata").

Adesso i consumer non sono più `@Transactional`: la scrittura gira dentro
`QuarkusTransaction.requiringNew().run(...)` chiamato **dentro** il try, quindi il commit
avviene lì e l'eccezione resta catturabile. I metodi sono marcati `@Blocking` (prima lo
erano implicitamente, essendo `@Transactional`) perché usano JDBC.

I due validatori, che non avevano proprio il try/catch, ora hanno il corpo protetto e fanno
comunque l'ack: basta un payload non-JSON su `railway/train/+/validation` per ucciderli, e
un canale di validazione morto significa che nessun nodo riesce più a partire.

---

### 🟠 B026 — Al primo errore MQTT l'heartbeat moriva per sempre (Gap 6)

**File:** `Stazioni/.../HeartbeatGenerator.java`, `Treni/.../TrainElab.java`.

`onFailure().invoke()` esegue l'effetto collaterale ma **non recupera lo stream**: un `Multi`
che fallisce termina. Aggiunto dopo l'`invoke`:

```java
.onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely()
```

sia sull'heartbeat della stazione sia sulla telemetria del treno.

Inoltre il **flush del buffer e il controllo dei sensori sono stati sganciati** dal tick
dell'heartbeat e spostati in un job dedicato `@Scheduled(every = "10s")`
(`HeartbeatGenerator.manutenzionePeriodica()`): erano dentro l'`.invoke()` del Multi, quindi
alla morte del flusso si fermava anche lo store-and-forward e gli eventi bufferizzati non
sarebbero mai più partiti.

---

### 🟠 B027 — Guasti duplicati a ogni treno che entra in una stazione guasta

**File:** `IngestionService.onAlert()`.

La stazione guasta emette un alert per ogni treno che entra. Ora `onAlert()` usa la stessa
deduplica del FaultMonitor (`statoRete.getGuastoApertoPerSorgente(sorgenteId, tipo)`): se un
guasto dello stesso tipo per la stessa sorgente è già aperto, **aggiorna il messaggio**
invece di creare una riga nuova. Prima la tabella si riempiva di righe identiche, il KPI
`activeAlerts` contava N volte lo stesso guasto e "Risolvi" ne chiudeva uno solo.

---

### 🟠 B028 — Chiave primaria dei guasti automatici senza parte casuale

**File:** `FaultMonitor.creaGuastoAutomatico()`.

```java
guasto.id = "alert-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
```

Stesso formato già usato da `onAlert()`. I due job schedulati scattano entrambi ogni 10
secondi e ciclano su tutte le sorgenti: due guasti nello stesso millisecondo davano la
stessa PK, violazione al commit e **rollback dell'intero giro di controllo**.

---

### 🟡 B029 — All'avvio tutte le stazioni risultavano ONLINE

**File:** `TrafficLogicEngine.onStart()`.

Tolte le due righe che inventavano `stato = "ONLINE"` e `ultimoHeartbeat = now()`. Resta il
default `OFFLINE` dell'entità con heartbeat nullo, che è la verità: all'avvio nessuna
stazione ha ancora battuto. Il `FaultMonitor` salta le stazioni con heartbeat nullo, quindi
non apre falsi guasti.

---

### 🟡 B030 — Modificare i tempi di un itinerario cambiava anche gli altri

**File:** `RestApiGateway.componiItinerario()` + nota nel `RouteEditorModal.tsx`.

Le `Tratta` sono archi **fisici** condivisi fra più itinerari. Adesso il tempo di
percorrenza di un arco già esistente viene sovrascritto solo se **nessun altro itinerario lo
usa**:

```java
long usataAltrove = ItinerarioTratta.count("id.idTratta = ?1 and id.idItinerario <> ?2", tratta.id, itinerario.id);
```

altrimenti si logga un warning e il valore resta quello impostato dalla pagina "Tratte
elementari". Nel modal della web app è stata aggiunta la nota che lo spiega
all'amministratore.

---

### 🟡 B013 — `normalizeDecimalComma()` poteva corrompere il JSON

**File:** `IngestionService.java`.

La regex `(?<=\d),(?=\d)` girava sull'**intero** payload: un array `[1,2,3]` sarebbe diventato
`[1.2.3]`. Il metodo è stato **rimosso** insieme alle sue cinque chiamate: tutti i nodi edge
formattano già con `String.format(Locale.US, ...)`, quindi il punto decimale è garantito
alla sorgente e la pezza non serviva.

---

### 🟡 B021 — Uno storico di stazione a ogni battito

**File:** `IngestionService.onHeartbeat()`.

`StoricoStatoStazione` viene ora scritto **solo quando lo stato cambia davvero**, con lo
stesso criterio già usato per i treni. Misurato durante la prova: 6 righe in 12 minuti con
2 stazioni, contro le **1630** righe dell'ora precedente girata col codice vecchio.

---

### 🟡 B031 — La console dei sensori del treno non partiva mai

**File:** `Treni/.../main.java`.

`Sensori` viene iniettata e `avviaMenu()` gira su un thread demone separato prima del ciclo
di keep-alive, ma **solo se `System.console() != null`**: in container o con l'output
rediretto lo `Scanner` girerebbe a vuoto, quindi in quel caso si logga che i sensori si
attivano via `POST /treno/sensore/*`.

---

### 🟡 B032 — `SecureHttpClient` delle Stazioni faceva esplodere il bean

**File:** `Stazioni/.../SecureHttpClient.java`.

Allineato al gemello del Treno: senza certificati generati non lancia più
`IllegalStateException` (che avrebbe impedito l'avvio del nodo appena qualcuno avesse usato
la classe) ma logga un warning e ripiega sul client HTTP in chiaro. La classe è stata tenuta
perché è il gemello simmetrico di quella del Treno ed è pronta per le chiamate REST della
stazione verso la Centrale.

---

### 🟡 B033 — Il flush del buffer rompeva l'ordine FIFO (Gap 6)

**File:** `LocalBuffer.java`, `StationGateway.flush()`.

`LocalBuffer` usa ora un `ArrayDeque` ed espone `addFirst()`: l'evento che non riesce a
partire torna **in testa** e non in fondo, altrimenti l'evento più vecchio diventava il più
recente e la Centrale poteva ricevere l'USCITA di un treno prima della sua ENTRATA.
`getBuffer()` è diventato `synchronized` e restituisce una **copia**: prima l'endpoint
`GET /stazione/buffer` serializzava la coda viva mentre un altro thread la modificava.

---

### 🟡 B034 — Il treno trattenuto non accumulava ritardo

**File:** `TrainJourneyEngine.tickInStazione()`.

Il `return` muto è stato sostituito dalla chiamata a `bloccaPerGuastoStazione(stazioneGuasta)`,
riusando la macchina a stati già scritta. Serve quando l'alert di guasto è arrivato mentre
il treno era altrove: in quel caso il gateway non aveva chiamato il blocco (la stazione non
era né la corrente né la prossima) e il treno restava fermo "senza motivo", con
`ritardoMinuti` fermo e `stazioneBloccante` a null. Adesso passa in
`BLOCCATO_GUASTO_STAZIONE`, il ritardo cresce e `GET /treno/viaggio` dice chi lo blocca.

---

### 🟡 B035 — Uno stato treno sconosciuto violava il CHECK

**File:** `IngestionService.normalizzaStatoTreno()`.

Qualunque valore non riconosciuto (compreso il caso "campo stato assente", che dava
`"unknown"`) viene riportato a `"fermo"`, confrontandolo con la lista dei valori ammessi dal
`CHECK (stato IN ('attivo','fermo','rotto','in manutenzione'))`. Era l'innesco tipico di B025.

---

### 🔵 Bug a bassa priorità

| Bug | Cosa è stato fatto |
| --- | --- |
| **B005** | Aggiunta a `schema.sql` la tabella `eventi_stazioni` con un commento che chiarisce che in esecuzione la crea Hibernate (`generation=update`) e che il file è il DDL di riferimento della relazione |
| **B020** | Nessuna modifica: il `LocalBuffer` solo in RAM è una **scelta dichiarata** (vedi §4) |
| **B036** | `main.java` della Centrale legge `quarkus.http.port` e logga la porta vera (8781): prima stampava 8080 e confondeva durante la demo |
| **B037** | Documentate in `README.txt` (nuove sezioni 3 e 4) le istanze multiple con `-Dquarkus.http.port`; gli script `Stazioni/avvioStazioni.sh` e `Treni/avvioTreni.sh` già assegnano porte diverse (8080+ e 9080+) |
| **B038** | `dispacciaManutenzione` filtra ora anche `sorgenteTipo = 'STAZIONE'`: `sorgenteId` da solo non è univoco fra treni e stazioni |
| **B039** | L'emitter `alerts-out` di `IngestionService` non è più codice morto: è quello che pubblica i guasti automatici e i RESOLVED della Centrale (B007) |

---

## 3. Gap chiusi

| Gap | Esito |
| --- | --- |
| 1 — profilo TLS non dimostrabile | **Corretto** (B023) e provato acceso: vedi §5.4 |
| 2 — stazione in fail-stop non blocca i treni | **Corretto** (B007), con anche la chiusura automatica al ritorno del battito |
| 3 — la verifica dell'ID può auto-validarsi | **Corretto** (B024) su entrambi i lati |
| 4 — autorizzazione assente sulle API | **Corretto** (B019): filtro JAX-RS + token + ruoli |
| 5 — keepalive sensori: WARNING invece di GUASTA | **Corretto**: `controllaSensori()` manda ora `CRITICAL`, quindi `inviaGuasto` porta la stazione a `GUASTA` e i treni in arrivo vengono trattenuti, come dice il PDF |
| 6 — store-and-forward non ordinato | **Corretto** (B033 + B026): reinserimento in testa e flush sganciato dall'heartbeat |
| 7 — vie multiple (binari) non gestite | **Scelta dichiarata**: il PDF la dà come estensione facoltativa, resta fra gli sviluppi futuri (§4) |
| 8 — flusso "stazione → centrale → prossima stazione → treno" non usato | **Scelta dichiarata**: il PDF ammette entrambe le strade, si usa l'itinerario pre-caricato; l'endpoint alternativo `GET /api/prossima-stazione` esiste, è mostrabile ed è pure fra quelli lasciati aperti ai nodi di campo (§4) |
| 9 — test end-to-end mai eseguito | **Fatto**, in profilo di default e in profilo `tls`: §5 |

---

## 4. Scelte dichiarate e limiti noti

Cose da dire in relazione perché sono scelte, non dimenticanze:

1. **Password in chiaro.** `Utenti.password` resta in chiaro (`import.sql`). L'autorizzazione
   ora è vera (token verificato lato server, ruoli distinti), ma le password non sono
   cifrate: è la semplificazione della variante 2 del PDF (autenticazione tradizionale al
   posto di OAUTH2/Keycloak).
2. **Sessioni in RAM.** `SessioniAttive` è una `ConcurrentHashMap`: al riavvio della Centrale
   i token scadono e bisogna rifare il login. Nessuna scadenza a tempo.
3. **Due GET aperte per i nodi di campo** (itinerario del treno e prossima stazione), perché
   il digital twin non fa login: si autentica con la validazione dell'ID via MQTT.
4. **WebSocket non autenticata.** `/ws/realtime` è di sola lettura e non passa dal filtro JAX-RS.
5. **`LocalBuffer` solo in memoria** (B020): se la stazione viene killata gli eventi
   bufferizzati si perdono. Il commento in `DBLocale.java` dice già "da spostare in un sqlite":
   resta uno sviluppo futuro.
6. **Binari multipli** (Gap 7): la colonna `binari` esiste ma nessuna logica alloca i binari,
   un guasto blocca l'intera stazione.
7. **Tratte come archi condivisi** (B030): il tempo di percorrenza di un arco usato da più
   itinerari si modifica dalla pagina "Tratte elementari", non dall'editor dell'itinerario.
8. **Transiti solo dalle stazioni** (B004): se il processo di una stazione non è acceso, i
   passaggi in quella stazione non compaiono fra i transiti della dashboard.

Aggiunta fuori elenco, emersa durante la prova: `risolviAllarme` adesso rimette anche
`ultimoHeartbeat = now()` sulla stazione che torna ONLINE. Senza, una stazione dichiarata
riparata dall'operatore ma in realtà ancora spenta sarebbe rimasta ONLINE per sempre, perché
il watchdog salta le stazioni che non hanno mai battuto.

---

## 5. Verifiche fatte

### 5.1 Compilazione e test automatici

* `ServeCentraleOperativa`, `Stazioni`, `Treni`: `./mvnw -DskipTests package` → BUILD SUCCESS
* `ClientWebAppIntefacciaUtente`: `npx tsc --noEmit` → nessun errore
* `./mvnw test -Dtest=AdminApiTest` → **Tests run: 7, Failures: 0, Errors: 0**
  (le sei prove CRUD già esistenti, ora autenticate, più la nuova che verifica il 401 senza token)

> Nota sul test: il login non può stare in un `@BeforeAll` statico, perché verrebbe eseguito
> prima che Quarkus comunichi a RestAssured la porta di test (casuale, `quarkus.http.test-port=0`)
> e finirebbe sulla 8080 con "connessione rifiutata". Si fa quindi al primo utilizzo dentro
> il metodo `autenticato()`.

### 5.2 Autorizzazione (B019)

```
GET /api/treni senza token              -> HTTP 401 {"errore":"Autenticazione richiesta: effettuare il login"}
login MAT001 (amministratore)           -> token
GET /api/treni con token                -> HTTP 200
login MAT003 (tecnico)
DELETE /api/treni/Mario come tecnico    -> HTTP 403 {"errore":"Operazione riservata all'amministratore"}
POST /api/treni/Mario/sopprimi (tecnico)-> HTTP 200
login con password sbagliata            -> HTTP 401
POST /api/auth/logout, poi GET /api/treni -> HTTP 401
```

### 5.3 Verifica dell'ID (B024)

Lanciato `java -jar ... TRN999-FANTASMA`:

```
❌ L'ID 'TRN999-FANTASMA' non è presente nel database centrale: il treno non è registrato.
❌ ERRORE FATALE: ID del treno non presente nel database centrale. Il processo verrà terminato.
processo uscito con codice = 1
select count(*) from Treni where id_convoglio='TRN999-FANTASMA'  ->  0
```

Nessuna riga creata e nemmeno un frame di telemetria pubblicato (il filtro blocca prima).
Nel database c'era ancora, come prova del bug vecchio, un treno con id letterale `null`.

### 5.4 Fail-stop della stazione (B007 + B028 + B034)

Spenta la stazione MI mentre il treno era in viaggio altrove:

```
14:44:24  [FaultMonitor] Stazione MI senza heartbeat da oltre 30 secondi → OFFLINE
14:44:24  MQTT railway/alerts:
          {"tipoEvento":"GUASTO","origine":"CENTRALE","sorgenteTipo":"STAZIONE","sorgenteId":"MI",
           "severita":"CRITICAL","messaggio":"Heartbeat assente: la stazione MI non invia heartbeat...",
           "guastoId":"alert-1785933864002-c7cf803f", ...}
14:44:34  [TWIN] Treno bloccato: stazione MI guasta (ero in fase IN_STAZIONE)
```

Stato del treno bloccato, a 20 secondi di distanza (fattore di accelerazione 60):

```
{'faseViaggio': 'BLOCCATO_GUASTO_STAZIONE', 'stazioneCorrente': 'Padova', 'stazioneBloccante': 'MI', 'ritardoMinuti': 24}
{'faseViaggio': 'BLOCCATO_GUASTO_STAZIONE', 'stazioneCorrente': 'Padova', 'stazioneBloccante': 'MI', 'ritardoMinuti': 44}
```

Il ritardo cresce: è esattamente il caso di B034 (l'alert era arrivato mentre il treno era
altrove). Riaccesa la stazione:

```
14:45:51  ✅ [FAIL-STOP] Stazione MI di nuovo raggiungibile: chiuso il guasto alert-1785933864002-c7cf803f
14:45:51  MQTT railway/alerts: {"tipoEvento":"RESOLVED","origine":"CENTRALE","sorgenteId":"MI",...}
14:45:51  🚆 [TWIN] Partenza da Padova verso MI (15 min simulati)
```

Provato anche il percorso manuale del PDF (guasto permanente → invio operatori): con la
stazione TO dichiarata guasta il treno si è fermato in MI accumulando 200 minuti di ritardo;
dopo `POST /api/stazioni/TO/manutenzione` fatto **dal tecnico**, la stazione ha ricevuto
`MAINTENANCE_DISPATCHED` + `RESOLVED`, è tornata ONLINE e il treno è ripartito conservando
il ritardo.

### 5.5 Un solo evento TRANSIT (B004)

Client WebSocket minimale collegato a `ws://localhost:8781/ws/realtime` per 65 secondi,
mentre il treno faceva la spola fra TO e MI:

```
eventi TRANSIT ricevuti: 3
   ('14:51:02', 'tenoAmmazzaRicchi', 'TO', 'uscita')
   ('14:51:18', 'tenoAmmazzaRicchi', 'MI', 'ingresso')
   ('14:51:24', 'tenoAmmazzaRicchi', 'MI', 'uscita')
```

Tre passaggi, tre eventi: nessun doppione.

### 5.6 Deduplica dei guasti (B027) e storico stazioni (B021)

Due guasti di fila dalla stessa stazione TO → **una sola riga** a DB:

```
♻️ Guasto già aperto per TO (stazione_guasta): aggiornato il messaggio invece di crearne un altro
```

Storico stazioni: 6 righe in 12 minuti (solo ai cambi di stato) contro 1630 righe nell'ora
precedente col codice vecchio.

### 5.7 Store-and-forward (Gap 6)

```
POST /stazione/rete/offline           -> gli eventi finiscono nel buffer
GET  /stazione/buffer                 -> 2 eventi, ordine ENTRATA poi USCITA
(la Centrale nel frattempo non riceve più heartbeat da MI)
POST /stazione/rete/online            -> flush
📤 Re-invio dal buffer [transit]: ... ENTRATA (12:52:40)
📤 Re-invio dal buffer [transit]: ... USCITA  (12:52:40)
📤 Re-invio dal buffer [transit]: ... ENTRATA (12:52:49)
📤 Re-invio dal buffer [transit]: ... USCITA  (12:52:54)
buffer dopo il flush: 0 eventi
```

Ordine cronologico rispettato.

### 5.8 Profilo TLS (B023 / Gap 1)

Tutti e tre i servizi lanciati con `-Dquarkus.profile=tls`:

```
Centrale: Profile tls activated. Listening on: http://0.0.0.0:8781 and https://0.0.0.0:8444
Stazione: 🚉 Processo associato con successo alla chiave primaria del database: TO   <- prima si bloccava QUI
          ✅ Processo Stazione AVVIATO CORRETTAMENTE + heartbeat regolari
Treno:    🚂 Processo associato con successo alla chiave primaria del database: tenoAmmazzaRicchi
          🗺️ [TWIN] Itinerario rt-1785880888800 caricato: 4 stazioni  <- scaricato via HTTPS sulla 8444
          🚆 [TWIN] Partenza da TO verso MI
```

Controllo delle connessioni di rete: i tre processi Java hanno socket **solo verso la porta
8883**, nessuna connessione sulla 1883 in chiaro. Nei tre minuti di prova sono stati
registrati 5 transiti a database, quindi il flusso dati completo funziona cifrato.

---

## 6. Come rifare la demo

Le istruzioni aggiornate sono in `README.txt` (sezioni 3, 4 e 5). In breve:

```bash
# infrastruttura
docker-compose up -d && (cd BrokerMosquitto && docker-compose up -d)

# profilo normale
cd ServeCentraleOperativa && ./mvnw quarkus:dev
cd Stazioni && ./avvioStazioni.sh      # 5 stazioni, porte 8080+
cd Treni    && ./avvioTreni.sh         # 5 treni, porte 9080+
cd ClientWebAppIntefacciaUtente && npm run dev

# profilo TLS (prima: cd BrokerMosquitto/tls && ./gen-certs.sh)
./mvnw quarkus:dev -Dquarkus.profile=tls     # in ognuno dei tre moduli
cp ClientWebAppIntefacciaUtente/.env.example ClientWebAppIntefacciaUtente/.env  # e scommentare le righe TLS
```

Login: **MAT001 / password** (amministratore), **MAT003 / password** (tecnico).

Per accorciare i tempi durante una dimostrazione si possono passare al treno
`-Dviaggio.fattore.accelerazione=60 -Dviaggio.sosta.secondi=5`: una tratta da 15 minuti
simulati dura 15 secondi reali.

---

## 7. File toccati

**Treni:** `main.java`, `TrainElab.java`, `TrainJourneyEngine.java`, `application.properties`
**Stazioni:** `HeartbeatGenerator.java`, `LocalBuffer.java`, `StationGateway.java`, `SecureHttpClient.java`, `application.properties`
**Centrale:** `main.java`, `IngestionService.java`, `FaultMonitor.java`, `TrafficLogicEngine.java`, `RestApiGateway.java`, `AuthController.java`, `ExistIdForEdge.java`, `ExistIdForEdgeStazione.java`, `application.properties`, `schema.sql`, `AdminApiTest.java`, **+ nuovi** `SessioniAttive.java`, `FiltroAutorizzazione.java`
**Frontend:** `apiClient.ts`, `websocketClient.ts`, `authStore.ts`, `RouteEditorModal.tsx`, **+ nuovo** `.env.example`
**Radice:** `README.txt`
