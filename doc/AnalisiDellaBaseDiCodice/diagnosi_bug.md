# Diagnosi Bug - Monitoraggio e Gestione del Traffico Ferroviario

**Data revisione:** 05/08/2026 (revisione completa, sostituisce l'analisi del 21/03/2025)
**Base analizzata:** 48 file Java (Treni, Stazioni, Centrale Operativa), frontend React,
`schema.sql` / `import.sql`, i tre `application.properties`, configurazione Mosquitto e TLS.
**Compilazione:** i tre moduli Maven compilano senza errori (`./mvnw -q -DskipTests compile`, exit 0).

---

## Sommario

| Gravità     | Conteggio |
| ----------- | --------- |
| 🔴 CRITICA  | 5         |
| 🟠 ALTA     | 5         |
| 🟡 MEDIA    | 9         |
| 🔵 BASSA    | 6         |
| **TOTALE**  | **25**    |

---


# PARTE 2 — Bug attualmente presenti

## 🔴 BUG CRITICI

### B023 - Il profilo TLS rompe la validazione dell'ID (treni e stazioni non partono)

**File:** `Treni/src/main/resources/application.properties`,
`Stazioni/src/main/resources/application.properties`,
`ServeCentraleOperativa/src/main/resources/application.properties`

Sotto il profilo `tls` la porta del broker diventa 8883 **per tutti i canali**:

```properties
%tls.mqtt.port=8883
```

ma le proprietà `ssl=true` + truststore sono state scritte solo per alcuni canali.
Restano in chiaro (cioè senza handshake TLS) proprio i canali della validazione:

| Modulo | Canali senza `%tls....ssl=true` |
| ------ | ------------------------------- |
| Treni | `validation-request-out`, `validation-response-in` |
| Stazioni | `validation-request-out`, `validation-response-in` |
| Centrale | `validation-in`, `validation-response`, `validation-station-in`, `validation-station-response` |

**Conseguenza:** lanciando `-Dquarkus.profile=tls` (cioè la configurazione che evita i
5 punti di penalità della Variante 6) quei canali aprono una socket TCP in chiaro sulla
porta 8883, che è un listener TLS: l'handshake fallisce, la richiesta di validazione non
arriva mai alla Centrale, `StationDatabaseValidator`/`TrainDatabaseValidator` restano su
`RIPROVA` e **il processo edge resta bloccato nel loop di `main.run()` senza mai avviarsi**.
La demo in TLS non parte.

**Soluzione:** aggiungere per ognuno dei canali mancanti le tre righe già usate per gli altri:

```properties
%tls.mp.messaging.outgoing.validation-request-out.ssl=true
%tls.mp.messaging.outgoing.validation-request-out.ssl.truststore.type=pem
%tls.mp.messaging.outgoing.validation-request-out.ssl.truststore.location=../BrokerMosquitto/tls/certs/ca.crt
```

---

### B024 - La telemetria del treno aggira il controllo dell'ID sul database

**File:** `Treni/src/main/java/it/uni/reti2/TrainElab.java:39-45`,
`ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java:151-158`

`TrainElab.generaTelemetria()` è un `@Outgoing` reattivo che parte al boot di Quarkus e
pubblica ogni 5 secondi **senza controllare `trainDB.trenoRiconosciuto`**:

```java
@Outgoing("telemetry-out")
public Multi<String> generaTelemetria() {
    return Multi.createFrom().ticks().every(Duration.ofSeconds(5))
        .map(tick -> { ... });   // nessun filtro sulla validazione
}
```

Dall'altro lato la Centrale, se non trova il treno, **lo crea**:

```java
Treno dbTreno = Treno.findById(trenoId);
if (dbTreno == null) {
    treno.persist();     // il treno sconosciuto entra nel DB
    ...
}
```

**Conseguenza:** avviando `java -jar treno-app.jar TRN999` (ID inesistente), se il primo
frame di telemetria viene consumato prima della richiesta di validazione — cosa che
succede sistematicamente quando la Centrale viene avviata *dopo* i treni, perché il treno
continua a ritentare ogni 15s mentre la telemetria continua a fluire — la Centrale
inserisce `TRN999` in `Treni` e poi `ExistIdForEdge` risponde `esisteNelDb = true`.
**Il controllo dell'ID si auto-valida** e un convoglio fantasma entra nel database.

**Nota di asimmetria:** la Stazione fa la cosa giusta — `HeartbeatGenerator.generaHeartbeat()`
filtra i tick con `if (!dbLocale.stazioneRiconosciuta) return false;` (righe 92-100).
Il treno non ha lo stesso filtro.

**Soluzione:** replicare nel treno il filtro della stazione

```java
.filter(tick -> trainDB.trenoRiconosciuto)
```

e, in più, togliere la creazione automatica dalla `onTelemetry` (loggare e scartare i
frame di treni sconosciuti: i treni si creano solo dalla pagina di amministrazione).

---

### B007 - Le stazioni in fail-stop non vengono mai segnalate ai treni

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/elaboration/FaultMonitor.java:96-109, 162-184`

Il `FaultMonitor` rileva correttamente la stazione che ha smesso di mandare heartbeat e
apre un guasto automatico, ma `creaGuastoAutomatico()` chiude il giro così:

```java
statoRete.aggiungiGuasto(guasto);
ingestion.broadcastAlert(guasto);   // <-- solo WebSocket verso il frontend
```

`broadcastAlert()` scrive **unicamente sulla WebSocket**. Nessun messaggio viene pubblicato
sul topic `railway/alerts`: infatti in tutta la Centrale gli unici `alertsEmitter.send(...)`
stanno nel `RestApiGateway` (STOP, RESOLVED, MAINTENANCE_DISPATCHED, ITINERARIO_AGGIORNATO)
e l'emitter iniettato in `IngestionService.java:44-46` non è mai usato.

**Conseguenza:** il requisito evidenziato nel PDF del prof — *"se la centrale non ricevesse
i Keep Alive da una stazione, potrebbe considerarla guasta"* con il conseguente blocco dei
treni — si ferma a metà. I treni non sanno nulla della stazione caduta e **ci vanno dentro
lo stesso**. Anche se il messaggio venisse pubblicato non basterebbe, perché il guasto
automatico nasce con `severita = "warning"` mentre `TrainGateway.riceviAlert()` (righe 95-108)
si blocca solo sui `CRITICAL`.

**Soluzione:** in `creaGuastoAutomatico()` pubblicare l'alert anche su MQTT nel formato
condiviso (`tipoEvento:GUASTO`, `sorgenteTipo:STAZIONE`, `severita:CRITICAL` per il caso
heartbeat perso), iniettando un `@Channel("alerts-out")` nel FaultMonitor.

---

### B006 - I treni sganciati da un itinerario non ricevono ITINERARIO_AGGIORNATO

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/gateway/RestApiGateway.java:470-475`

```java
assegnaTreni(itinerario, dto.treniIds, true);   // qui i treni rimossi diventano itinerario = null

for (Treno t : Treno.<Treno>list("itinerario.id", id)) {   // la query non li trova più
    pubblicaItinerarioAggiornato(t.id);
}
```

`assegnaTreni(..., true)` sgancia i treni non più presenti nella lista; la query Panache
successiva forza il flush della transazione e quindi restituisce **solo i treni ancora
agganciati**.

**Conseguenza:** un treno tolto dall'itinerario dalla pagina di amministrazione continua
a girare all'infinito sull'itinerario vecchio, perché il suo digital twin non riceve mai
la richiesta di ricarica. Sulla dashboard il treno risulta senza tratta ma continua a
pubblicare passaggi.

**Soluzione:** salvare la lista degli id **prima** di sganciare e notificare l'unione dei
due insiemi (treni vecchi + treni nuovi).

---

### B004 - Ogni transito genera due eventi TRANSIT sul WebSocket

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java:308` e `:487`

Lo stesso passaggio fisico percorre due strade e le due strade fanno la stessa cosa:

1. il treno pubblica su `railway/train/{id}/passaggio` → la Centrale lo consuma con
   `onPassaggio()` → `broadcastTransit(...)`;
2. la stessa stazione riceve quel passaggio, lo riemette su `railway/station/{id}/transit`
   → la Centrale lo consuma con `onTransit()` → `broadcastTransit(...)`.

**Conseguenza:** il frontend riceve due eventi `TRANSIT` identici per ogni ENTRATA/USCITA.
In `useRealtimeUpdates.ts:66-76` l'id viene sintetizzato con `tr-${Date.now()}`: se i due
eventi arrivano nello stesso millisecondo hanno pure la stessa chiave React. Nella pagina
transiti ogni passaggio compare due volte.

**Soluzione:** decidere chi è la fonte ufficiale dell'evento per la UI. La scelta coerente
con l'architettura del prof è la **stazione** (è il sensore a rilevare il transito): quindi
`onPassaggio()` deve limitarsi ad aggiornare la cache e la posizione sulla tratta, senza
chiamare `broadcastTransit`.

---

## 🟠 BUG AD ALTA PRIORITÀ

### B019 - Nessun endpoint REST è realmente protetto

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/gateway/AuthController.java:68`,
tutto `RestApiGateway.java`, `ClientWebAppIntefacciaUtente/src/api/apiClient.ts`

Il login genera un token che non viene salvato da nessuna parte:

```java
payload.put("token", UUID.randomUUID().toString());
```

Nessun endpoint ha `@RolesAllowed` o un filtro, e nel frontend non esiste nessun
`Authorization:` (verificato con grep su tutto `src/`). Il ruolo tecnico/amministratore è
solo un flag nello store React.

**Conseguenza:** un `curl -X DELETE http://localhost:8781/api/treni/TRN001` o
`POST /api/treni/TRN001/sopprimi` funziona senza credenziali. La distinzione fra le due
tipologie di utenti richiesta dal PDF esiste solo lato client. In più le password sono in
chiaro in `Utenti.password` (`import.sql:23-28`).

**Soluzione minima e onesta per la consegna:** un `ContainerRequestFilter` JAX-RS che
verifica un token emesso e conservato in memoria dalla Centrale, con i metodi di scrittura
riservati al ruolo amministratore; nel frontend aggiungere l'header al `fetch`.
In alternativa, dichiarare esplicitamente in relazione che l'autorizzazione è simulata.

---

### B025 - Un errore di persistenza può spegnere un canale MQTT

**File:** `IngestionService.java` (tutti i metodi `@Incoming @Transactional`),
`DbValidator/ExistIdForEdge.java:37`, `DbValidator/ExistIdForEdgeStazione.java:43`

Nei consumer di `IngestionService` il `try/catch` copre il corpo del metodo, ma il
**commit della transazione avviene all'uscita del metodo**, fuori dal `try`. Un vincolo
violato (per esempio uno stato treno che non rispetta
`CHECK (stato IN ('attivo','fermo','rotto','in manutenzione'))`, vedi B035) esplode al
commit e l'eccezione risale al connettore reattivo.

Peggio ancora i due validatori, che non hanno nemmeno il `try/catch`:

```java
public CompletionStage<Void> interrogaIlDPerValidazione(Message<byte[]> messaggio)
        throws JsonProcessingException {
```

Basta un payload non-JSON pubblicato su `railway/train/+/validation` per far fallire il
canale.

**Conseguenza:** il canale può terminare in errore e da quel momento la Centrale **smette
di ricevere** su quel topic finché non viene riavviata. È il tipo di guasto che in demo si
manifesta come "da un certo punto in poi la dashboard si è congelata".

**Soluzione:** avvolgere il corpo dei validatori in try/catch (ack anche in caso di errore)
e spostare la scrittura DB in un metodo `@Transactional` separato chiamato dentro il try,
così l'eccezione di commit resta catturabile.

---

### B026 - Alla prima eccezione MQTT l'heartbeat della stazione muore per sempre

**File:** `Stazioni/src/main/java/it/uni/reti2/HeartbeatGenerator.java:116-121`

```java
.onFailure().invoke(e -> {
    LOG.error("🔌 Errore connessione MQTT Centrale!");
    dbLocale.connessioneCentrale = false;
});
```

`onFailure().invoke()` esegue un effetto collaterale ma **non recupera lo stream**: un
`Multi` che fallisce termina. Non c'è nessun `retry()` né `recoverWithMulti()`.

**Conseguenza:** al primo errore sul canale (broker riavviato, rete che sfarfalla) il
flusso di heartbeat si chiude definitivamente. La stazione continua a girare, il suo
`LocalBuffer` continua ad accumulare, ma non batterà mai più — e siccome il flush del
buffer è agganciato allo stesso tick (`.invoke(...)` righe 81-91), **anche lo
store-and-forward si ferma**: gli eventi bufferizzati non partiranno mai più.
La Centrale la marcherà OFFLINE per sempre. Stesso schema, senza nessuna gestione di
errore, in `TrainElab.generaTelemetria()`.

**Soluzione:** `.onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely()`
dopo l'`invoke`, e separare il flush del buffer dal flusso di heartbeat (un `@Scheduled`
dedicato è più robusto).

---

### B027 - Guasti duplicati a ogni treno che entra in una stazione guasta

**File:** `Stazioni/src/main/java/it/uni/reti2/StationGateway.java:144-147`,
`ServeCentraleOperativa/.../IngestionService.java:366-383`

La stazione guasta emette un alert **per ogni treno che entra**:

```java
if ("GUASTA".equals(dbLocale.stato) && "ENTRATA".equals(tipo)) {
    inviaGuasto(String.format("Stazione %s guasta: treno %s trattenuto", ...), "CRITICAL");
}
```

e `onAlert()` crea un nuovo record `Guasto` + `StoricoGuasto` per ognuno, senza il
controllo di deduplica che invece il FaultMonitor usa
(`statoRete.getGuastoApertoPerSorgente(...)`).

**Conseguenza:** la tabella dei guasti si riempie di righe identiche, la lista allarmi del
frontend diventa illeggibile e il KPI `activeAlerts` conta N volte lo stesso guasto.
`POST /api/allarmi/{id}/risolvi` ne chiude uno solo: gli altri restano aperti e la stazione
sulla dashboard sembra guasta anche dopo la riparazione (solo `dispacciaManutenzione` li
chiude tutti insieme).

**Soluzione:** in `onAlert()` riusare `getGuastoApertoPerSorgente(sorgenteId, tipo)`: se
esiste già un guasto aperto dello stesso tipo per la stessa sorgente, aggiornare il
messaggio invece di creare una riga nuova.

---

### B028 - Chiave primaria dei guasti automatici senza parte casuale

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/elaboration/FaultMonitor.java:165`

```java
guasto.id = "alert-" + Instant.now().toEpochMilli();
```

`IngestionService.onAlert()` (riga 367) per lo stesso scopo aggiunge un suffisso UUID; qui
no. I due job schedulati (`controllaHeartbeat` e `controllaTreniFermi`) scattano entrambi
ogni 10 secondi e ciclano su tutte le stazioni/treni: due guasti generati nello stesso
millisecondo producono la stessa chiave.

**Conseguenza:** violazione di PK → eccezione al commit → **rollback dell'intero job**
`@Transactional`: nessuna delle stazioni cadute viene marcata OFFLINE in quel giro (e per
B025 il job può anche smettere di funzionare).

**Soluzione:** allineare al formato usato da `onAlert()`:
`"alert-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0,8)`.

---

## 🟡 BUG A MEDIA PRIORITÀ

### B029 - All'avvio tutte le stazioni risultano ONLINE anche se sono spente

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/elaboration/TrafficLogicEngine.java:63-67`

```java
for (Stazione s : Stazione.<Stazione>listAll()) {
    s.stato = "ONLINE";
    s.ultimoHeartbeat = Instant.now();
```

Nessuna stazione ha ancora battuto: l'informazione è inventata. Per i primi 30 secondi
(il timeout del FaultMonitor) la dashboard mostra tutte le stazioni operative anche se non
ne è partita nemmeno una. Il valore di default del campo in `Stazione.java:69` è già
`"OFFLINE"`, che è la scelta corretta.

**Soluzione:** togliere le due righe e lasciare `stato = "OFFLINE"` con `ultimoHeartbeat = null`
(il FaultMonitor salta già le stazioni con heartbeat nullo).

---

### B030 - Modificare i tempi di un itinerario cambia anche gli altri itinerari

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/gateway/RestApiGateway.java:831-842`

`componiItinerario()` riusa la `Tratta` già esistente fra due stazioni e ne **sovrascrive**
il tempo di percorrenza:

```java
Tratta tratta = Tratta.find("stazionePartenza.id = ?1 and stazioneArrivo.id = ?2", ...).firstResult();
...
} else if (tempo != null) {
    tratta.tempoPercorrenzaMinuti = tempo;   // la tratta è condivisa fra piu' itinerari
}
```

Con i dati di `import.sql` la tratta `T1_MI_BO` appartiene sia a `IT1_MI_NA` sia a
`IT3_MI_RM`: cambiando il tempo Milano→Bologna sull'itinerario 1 si cambia anche
l'itinerario 3, senza nessun avviso all'amministratore.

**Soluzione:** o si documenta esplicitamente che le tratte sono archi condivisi della rete
(e allora il tempo si modifica solo dalla pagina "tratte elementari"), o si crea una tratta
dedicata per itinerario. La prima è più coerente col modello dati.

---

### B013 - `normalizeDecimalComma()` può corrompere il JSON

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java:58-62`

La regex `(?<=\d),(?=\d)` viene applicata all'**intero payload**, non ai soli numeri: un
array come `[1,2,3]` diventerebbe `[1.2.3]` e il parsing fallirebbe. Oggi nessun payload
contiene array numerici, quindi il bug è latente — ma è una mina per chi estenderà i
messaggi.

**Soluzione:** far generare ai nodi edge sempre il punto decimale (tutti i `String.format`
usano già `Locale.US`, quindi il problema in realtà non si pone) e togliere la
normalizzazione, oppure limitarla ai soli campi numerici noti.

---

### B021 - Uno storico di stazione a ogni battito

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java:224-229`

`StoricoStatoStazione` viene persistito a **ogni** heartbeat (uno ogni 10s per stazione),
mentre `StoricoStatoTreno` viene scritto solo quando lo stato cambia davvero (righe 171-178).
Con 5 stazioni sono ~43.000 righe al giorno di dati identici.

**Soluzione:** applicare lo stesso criterio dei treni, storicizzando solo al cambio di
`funzionanteONo` (o del campo `stato` in cache).

---

### B031 - La console dei sensori del treno non viene mai avviata

**File:** `Treni/src/main/java/it/uni/reti2/Sensori.java`

La classe è `@ApplicationScoped` ed espone `avviaMenu()`, ma nessuno la inietta e nessuno
chiama quel metodo (verificato con grep su tutto `Treni/src`): `main.run()` entra
direttamente nel `while(true)`.

**Conseguenza:** i "sensori di bordo simulati" citati in documentazione sono attivabili
solo via `curl` sulle POST di `TrainIngestion`. All'interrogazione, se il prof chiede di
mostrare il sensore di guasto del treno, il menù non compare.

**Soluzione:** iniettare `Sensori` nel `main` e avviare `avviaMenu()` su un thread separato
prima del loop di keep-alive (attenzione: solo se `System.console() != null`, altrimenti in
container lo `Scanner` gira a vuoto).

---

### B032 - `SecureHttpClient` delle Stazioni: codice morto che fa esplodere il bean

**File:** `Stazioni/src/main/java/it/uni/reti2/SecureHttpClient.java:38-40`

```java
} catch (Exception e) {
    throw new IllegalStateException("Impossibile configurare la CA TLS della Centrale: " + caPath, e);
}
```

La versione del Treno è stata corretta di recente (fallback sul client in chiaro con un
warning); quella della Stazione no. Al momento la classe non è iniettata da nessuna parte —
la Stazione non parla mai in HTTP con la Centrale — quindi il bean non viene istanziato e
il difetto resta latente.

**Conseguenza:** appena qualcuno la userà, senza certificati generati la stazione non
partirà più. Ed è comunque codice morto che il prof può chiedere di spiegare.

**Soluzione:** o si allinea al fallback del Treno, o si cancella la classe.

---

### B033 - Il flush del buffer locale rompe l'ordine FIFO

**File:** `Stazioni/src/main/java/it/uni/reti2/StationGateway.java:236-257`

In caso di errore durante il flush l'evento non recapitato viene rimesso in coda con
`localBuffer.add(...)`, che accoda **in fondo**:

```java
localBuffer.add(evento.canale(), evento.payload());   // torna ultimo, non primo
```

**Conseguenza:** l'evento più vecchio diventa il più recente. Su una sequenza
ENTRATA/USCITA dello stesso treno la Centrale può ricevere l'USCITA prima dell'ENTRATA e
`onTransit()` registrerebbe un transito "esce senza entrare" sbagliato. Il requisito del
PDF è proprio il *reinvio ordinato* dei dati bufferizzati.

**Nota minore:** `LocalBuffer.getBuffer()` (riga 77) espone la coda **senza `synchronized`**
mentre tutti gli altri metodi lo sono; l'endpoint `GET /stazione/buffer` la serializza
mentre l'heartbeat può modificarla.

**Soluzione:** aggiungere un `addFirst()` al `LocalBuffer` (usando `ArrayDeque`) e usarlo
nel ramo di errore; rendere `getBuffer()` sincronizzato restituendo una copia.

---

### B034 - Il treno trattenuto da una stazione guasta non accumula ritardo

**File:** `Treni/src/main/java/it/uni/reti2/TrainJourneyEngine.java:337-340`

```java
if (trainDB.stazioniGuaste.contains(corrente.id) || trainDB.stazioniGuaste.contains(prossima.id)) {
    return;   // resta fermo, ma la fase resta "IN_STAZIONE"
}
```

Se l'alert di guasto è arrivato **mentre il treno era altrove** (la stazione entra in
`stazioniGuaste` ma il treno non era né lì né diretto lì, quindi
`bloccaPerGuastoStazione()` non è stato chiamato), quando poi il treno arriva viene
trattenuto da questo `return` senza mai passare in `BLOCCATO_GUASTO_STAZIONE`.

**Conseguenza:** il treno resta fermo correttamente, ma `tickBloccato()` non gira: niente
incremento di `ritardoMinuti`, `stazioneBloccante` resta `null` e `GET /treno/viaggio`
mostra un treno "in sosta" senza spiegazione. Sulla dashboard il ritardo non cresce.

**Soluzione:** sostituire il `return` con la chiamata a
`bloccaPerGuastoStazione(stazioneGuastaTrovata)`, così si riusa la macchina a stati già
scritta (e lo sblocco su RESOLVED funziona già).

---

### B035 - Uno stato treno sconosciuto viola il CHECK della tabella

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java:68-74`

```java
return rawStato == null ? "fermo" : rawStato.toLowerCase();
```

Se un payload arriva senza campo `stato`, `rawStato` vale `"UNKNOWN"` (riga 117) e la
funzione restituisce `"unknown"`, che non è fra i valori ammessi da
`CHECK (stato IN ('attivo','fermo','rotto','in manutenzione'))`.

**Conseguenza:** violazione di vincolo al commit → si innesca B025.

**Soluzione:** il default deve essere `"fermo"` per qualunque valore non riconosciuto.

---

## 🔵 BUG A BASSA PRIORITÀ

### B005 - `eventi_stazioni` non è documentata in `schema.sql`
La tabella usata da `EventoStazione` non compare nel DDL. Non è un bug di runtime (il DB
reale lo crea Hibernate con `generation=update`), ma `schema.sql` è l'allegato che finisce
in relazione: va aggiunta per coerenza.

### B020 - `LocalBuffer` solo in memoria
Gli eventi bufferizzati si perdono se la stazione viene killata. È una scelta dichiarata
(commento in `DBLocale.java:9`: "da spostare in un sqlite"): va scritta in relazione come
limite noto.

### B036 - La Centrale logga la porta sbagliata all'avvio
`ServeCentraleOperativa/.../main.java:25` stampa `http://localhost:8080/api`, ma la porta
configurata è la **8781**. Confonde durante la demo.

### B037 - Porte HTTP fisse: due treni sulla stessa macchina non partono
`Treni` usa `quarkus.http.port=8082` e `Stazioni` la 8081. Lanciando un secondo processo
treno senza `-Dquarkus.http.port=...` il bind fallisce. Da documentare nelle istruzioni di
avvio (il progetto vive di istanze multiple).

### B038 - `dispacciaManutenzione` chiude i guasti per solo `sorgenteId`
`RestApiGateway.java:668` usa `Guasto.list("sorgenteId = ?1 and risolto = false", id)`
senza filtrare `sorgenteTipo`: se un treno avesse lo stesso id di una stazione i suoi
guasti verrebbero chiusi insieme. Aggiungere `and sorgenteTipo = 'STAZIONE'`.

### B039 - Emitter iniettato e mai usato
`IngestionService.java:44-46` inietta `@Channel("alerts-out")` senza mai chiamarlo.
O lo si usa per risolvere B007, o si toglie.

---

## RIEPILOGO PER MODULO

| Modulo | CRITICA | ALTA | MEDIA | BASSA |
| ------ | ------- | ---- | ----- | ----- |
| **Treni** | B023, B024 | B026 (variante telemetria) | B031, B034 | B037 |
| **Stazioni** | B023 | B026, B027 | B032, B033 | B020 |
| **Centrale Operativa** | B023, B024, B007, B006, B004 | B019, B025, B028 | B013, B021, B029, B030, B035 | B005, B036, B038, B039 |
| **Frontend** | B004 (effetto) | B019 (effetto) | – | – |

---

## ORDINE DI CORREZIONE CONSIGLIATO (prima della consegna)

1. **B023** – aggiungere `ssl=true` ai canali di validazione nei tre `application.properties`
   (senza questo la demo TLS, che vale 5 punti, non parte).
2. **B024** – filtro `trenoRiconosciuto` sulla telemetria + niente creazione automatica dei treni.
3. **B007** – il FaultMonitor deve pubblicare l'alert su `railway/alerts` con severità CRITICAL.
4. **B004** – una sola sorgente di verità per gli eventi TRANSIT.
5. **B006** – salvare la lista dei treni prima di sganciarli in `updateTratta()`.
6. **B028 + B035** – chiavi e stati sempre validi, per non far cadere le transazioni.
7. **B026** – `retry()` sul flusso di heartbeat.
8. **B019** – almeno un filtro di autorizzazione, oppure la scelta dichiarata in relazione.

Gli altri sono migliorie di qualità: vanno bene come "sviluppi futuri" nel capitolo finale
della relazione.

---

*Revisione svolta leggendo integralmente i sorgenti dei tre microservizi, la configurazione
MQTT/TLS e il frontend; i riferimenti riga si riferiscono allo stato del repo al 05/08/2026.*
