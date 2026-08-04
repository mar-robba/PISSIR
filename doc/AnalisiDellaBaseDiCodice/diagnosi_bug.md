# Diagnosi Bug - Monitoraggio e Gestione del Traffico Ferroviario

**Analisi:** Codice sorgente completo (Treni, Stazioni, Centrale Operativa, Frontend)

---

## Sommario


| Gravità    | Conteggio |
| ----------- | --------- |
| 🔴 CRITICAL | 7         |
| 🟠 ALTA     | 5         |
| 🟡 MEDIA    | 6         |
| 🔵 BASSA    | 4         |
| **TOTALE**  | **22**    |

---

## 🔴 BUG CRITICI

### B001 - ConcurrentModificationException in HeartbeatGenerator.controllaSensori()

**File:** `Stazioni/src/main/java/it/uni/reti2/HeartbeatGenerator.java`
**Metodo:** `controllaSensori()`

```java
// ❌ BUG: iterazione su ConcurrentHashMap con rimozione diretta
for (Map.Entry<String, Instant> sensore : dbLocale.sensoriUltimoBattito.entrySet()) {
    if (sensore.getValue().isBefore(limite)) {
        dbLocale.sensoriUltimoBattito.remove(sensore.getKey()); // ConcurrentModificationException!
    }
}
```

**Problema:** Nonostante `sensoriUltimoBattito` sia un `ConcurrentHashMap`, l'uso del costrutto `for(:)` con `entrySet()` e la chiamata diretta a `remove()` sulla mappa mentre si itera genera una `ConcurrentModificationException`.

**Soluzione:** Usare `Iterator.remove()`:

```java
Iterator<Map.Entry<String, Instant>> it = dbLocale.sensoriUltimoBattito.entrySet().iterator();
while (it.hasNext()) {
    Map.Entry<String, Instant> sensore = it.next();
    if (sensore.getValue().isBefore(limite)) {
        stationGateway.inviaGuasto(...);
        it.remove(); // sicuro
    }
}
```

### B003 - Sovrascrittura timestamp guasti all'avvio (Data Corruption)

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/elaboration/TrafficLogicEngine.java`
**Metodo:** `onStart()`

```java
for (Guasto g : Guasto.<Guasto>list("risolto", false)) {
    if (g.timestamp == null) g.timestamp = Instant.now();  // Sovrascrive!
    if (g.severita == null) g.severita = "warning";
}
```

**Problema:** Metodo annotato `@Transactional`, quindi tutte le modifiche alle entità vengono flushate sul DB. I timestamp nulli vengono sovrascritti con la data di avvio, perdendo l'informazione originale.

---

### B004 - Duplicazione eventi WebSocket (TRANSIT vs PASSAGGIO)

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/ingestion/IngestionService.java`

**Problema:** Due metodi distinti (`onTransit()` e `onPassaggio()`) consumano eventi di passaggio da due topic MQTT diversi ma generano lo stesso `eventType: "TRANSIT"` sul WebSocket. Il frontend riceve notifiche duplicate per lo stesso evento fisico.

---

### B005 - Tabella `eventi_stazioni` mancante in schema.sql

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/entity/EventoStazione.java`

**Problema:** La tabella `eventi_stazioni` non è definita in `schema.sql`. Con `database.generation=update` Hibernate la crea, ma in produzione non esisterebbe.

---

### B006 - Perdita notifica cambio itinerario per treni rimossi

**File:** `ServeCentraleOperativa/src/main/java/it/uni/reti2/gateway/RestApiGateway.java`
**Metodo:** `updateTratta()`

**Problema:** `assegnaTreni(itinerario, dto.treniIds, true)` setta `itinerario = null` sui treni rimossi, poi la query `Treno.list("itinerario.id", id)` non li trova piu`, quindi `ITINERARIO_AGGIORNATO` non viene inviato loro.

**Soluzione:** Salvare la lista treni prima di sganciarli.

---

### B007 - Severita` guasti non allineata

**File:** `FaultMonitor.java` (warning) vs `TrainGateway.java` (filtra CRITICAL)

**Problema:** FaultMonitor crea guasti automatici con `severita = "warning"`. TrainGateway ignora i WARNING. Le stazioni OFFLINE non bloccano i treni.

---

## 🟠 BUG AD ALTA PRIORITA`

### B008 - File mancanti: TrenoResource.java, TrenoService.java, StazioneResource.java

**Problema:** I file elencati nelle schede VSCode ma non presenti fisicamente. Refactoring incompleto.

### B009 - Doppia definizione EventoStazione

Package `entity/` e `db/entity/` contengono la stessa classe.

### B010 - Cache RAM e DB fuori sincrono

**File:** `IngestionService.java`

**Problema:** `treno` (cache) e `dbTreno` (DB via Panache) sono oggetti distinti. Modifiche a uno non si riflettono sull'altro. Race condition su creazione treni nuovi.

### B011 - @Transactional su FaultMonitor puo` causare lock

**File:** `FaultMonitor.java`

Transazione singola per TUTTE le stazioni: lock prolungato, rollback totale su singolo fallimento.

### B012 - concurrentExecution = SKIP rischioso

Se l'esecuzione dura oltre 10s, quella successiva salta. In condizioni di carico si perdono heartbeat.

---

## 🟡 BUG A MEDIA PRIORITA`

### B013 - normalizeDecimalComma() fragile

La regex `(?<=\d),(?=\d)` corrompe array JSON: `[1,2,3]` diventa `[1.2.3]`.

### B014 - treno.persist() automatico senza admin

Un treno sconosciuto viene creato automaticamente nel DB dalla telemetria.

### B015 - ConfigProperty treno.id vuoto

Se `treno.id` non configurato, il processo parte ma non invia telemetria.

### B016 - (RIVALUTATO) Corretto, nessun bug

### B017 - StoricoGuasto duplica timestamp

**File:** `StoricoGuasto.java`

Campi `tsApertura`/`tsChiusura` duplicati rispetto a `Guasto.timestamp`/`timestampRisoluzione`.

### B018 - Inconsistenza PanacheEntityBase vs PanacheEntity

---

## 🔵 BUG A BASSA PRIORITA`

### B019 - Endpoint REST senza autenticazione

Tutti gli endpoint accessibili senza token. `/api/treni/{id}/sopprimi` non protetto.

### B020 - LocalBuffer in-memory senza persistenza

Eventi persi su spegnimento brusco della stazione.

### B021 - StoricoStatoStazione.persist() a ogni heartbeat

10 secondi x 100 stazioni = 864.000 righe/giorno. Dovrebbe scrivere solo a cambiamento stato, come fa onTelemetry().

### B022 - SecureHttpClient senza HostnameVerifier

Certificati con CN diverso da localhost causano SSLHandshakeException.

---

## RIEPILOGO PER MODULO


| Modulo                 | CRITICAL               | ALTA                   | MEDIA                  | BASSA      |
| ---------------------- | ---------------------- | ---------------------- | ---------------------- | ---------- |
| **Treni**              | B002, B007             | B008                   | B015                   | B022       |
| **Stazioni**           | B001                   | -                      | B020                   | B022       |
| **Centrale Operativa** | B003, B004, B005, B006 | B009, B010, B011, B012 | B013, B014, B017, B018 | B019, B021 |
| **Frontend**           | -                      | -                      | -                      | -          |

---

## CORREZIONI RACCOMANDATE (IMMEDIATE)

1. **B001** - Iterator.remove() in HeartbeatGenerator.java
2. **B002** - Creare classe Sensori o rimuovere injection
3. **B003** - Rimuovere scritture non necessarie da TrafficLogicEngine.onStart()
4. **B004** - Unificare onTransit() e onPassaggio()
5. **B006** - Salvare lista treni prima di sganciarli in updateTratta()
6. **B007** - Allineare severita` guasti (CRITICAL per stazioni OFFLINE)
7. **B013** - Migliorare regex normalizeDecimalComma()

---

*Analisi generata il 21/03/2025 dopo lettura completa del codice sorgente dei 4 moduli.*
