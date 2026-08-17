# Bug della base di codice — analisi del 15/08/2026

Lettura completa di quello che c'è adesso nel repository: `ServeCentraleOperativa`, `Stazioni`,
`Treni`, `ClientWebAppIntefacciaUtente`, gli schemi SQL, i file di configurazione e la
documentazione finale (`doc/documentazioneFinale/*.org`), usata come metro di paragone per capire
cosa il sistema *dovrebbe* fare.

## Cosa è entrato in elenco e cosa no

Ci sono solo i **bug del codice attuale**: pezzi scritti che si comportano in modo diverso da come
dovrebbero, o che si contraddicono a vicenda. Restano fuori due categorie:

1. **Le cose mai implementate.** Il caso tipico è RF02.1.2.2.1 (il convoglio che si guasta mentre è
   fermo in stazione deve rendere la stazione non percorribile), che nel codice è ancora un `todo`
   in `IngestionService.onAlert`. Stesso discorso per il gemello RF02.1.2.2.2 (la tratta non
   percorribile) e per la gestione delle vie multiple. Non sono bug, sono funzioni che non ci sono.
2. **I limiti che la documentazione dichiara già.** Li ho verificati uno per uno e tornano: sono
   descritti bene e non ha senso ripeterli come se fossero scoperte. L'elenco è in fondo.

Quello che segue è quindi roba che la documentazione dà per funzionante e che invece non funziona,
oppure che nessuno ha ancora guardato.

La numerazione `Axx` è nuova e non ha niente a che vedere con i codici `B0xx` citati in
[gap_analysis.md](gap_analysis.md), che si riferiscono all'analisi vecchia (quei bug sono stati
sistemati).

## Riepilogo

| #   | Gravità | Dove          | In due parole                                                                       |
|-----|---------|---------------|-------------------------------------------------------------------------------------|
| A01 | alta    | Centrale + UI | Chiudere un guasto automatico fa comparire un allarme NUOVO invece di chiudere quello vecchio |
| A02 | alta    | Centrale + UI | Lo stato della stazione (OFFLINE, ritorno ONLINE) non viene spinto sulla WebSocket   |
| A03 | alta    | UI            | La fotografia periodica dei KPI non la legge nessuno, la dashboard se li ricalcola da sola con formule diverse |
| A04 | alta    | Treno         | Il convoglio rimasto senza itinerario continua a dichiararsi IN_VIAGGIO a 120 km/h  |
| A05 | alta    | Centrale      | Eliminare un itinerario non avvisa i convogli che ci stavano sopra                   |
| A06 | alta    | UI            | Modificare un itinerario sgancia i treni assegnati poco prima dall'altra schermata   |
| A07 | media   | Centrale      | L'anomalia "convoglio fermo fuori stazione" non scatta quasi mai, e quando scatta non aspetta `T_fer` |
| A08 | media   | Centrale      | Il guasto automatico `treno_fermo` non si richiude mai da solo                       |
| A09 | media   | Centrale + UI | I transiti riconsegnati dopo un'interruzione finiscono nello storico con l'ora sbagliata |
| A10 | media   | UI            | Lo storico dei transiti viene scaricato ma non c'è nessuna pagina che lo mostra      |
| A11 | media   | UI            | Il treno resta "in stazione" anche dopo essere ripartito                             |
| A12 | media   | Centrale      | Eliminare una tratta fisica usata come posizione di un treno risponde 500            |
| A13 | media   | Centrale      | Nello storico dei transiti il ritardo mostrato è quello di adesso                    |
| A14 | bassa   | UI            | Il pulsante "Invia Operatori" quasi non compare mai                                  |
| A15 | bassa   | UI            | Gli allarmi inventati dal browser non esistono sul server                            |
| A16 | bassa   | UI            | Una stazione che non ha mai battuto risulta con l'ultimo heartbeat "adesso"          |
| A17 | bassa   | Centrale      | Il FaultMonitor pubblica il guasto prima di aver fatto commit                        |
| A18 | bassa   | UI            | Ogni messaggio WebSocket ri-renderizza tutta l'applicazione                          |
| A19 | bassa   | Test          | I test `@QuarkusTest` scrivono sul database di sviluppo                              |
| A20 | bassa   | Centrale + DB | Un itinerario che ripassa sulla stessa tratta fa saltare la chiave primaria          |

---

## A01 — La chiusura di un guasto automatico arriva alla schermata come un allarme nuovo

**Sintomo.** Una stazione cade, la Centrale apre da sola il guasto "heartbeat assente", l'allarme
compare nell'elenco. Quando la stazione torna a battere la Centrale lo chiude correttamente (a
database `risolto = true`), ma sulla schermata già aperta compare un **secondo allarme rosso** con
lo stesso testo e lo stesso id, contato fra quelli attivi nel badge della sidebar. L'operatore vede
un allarme aperto per una condizione che è appena rientrata, ed è esattamente il contrario di quello
che RF02.1.4 vuole ottenere.

**Dove.** `IngestionService.java:269-273` (il `for` che rimanda i guasti chiusi),
`IngestionService.broadcastAlert` alle righe 618-633, `useRealtimeUpdates.ts:53-64`,
`railwayStore.ts:129-130`.

**Perché.** `broadcastAlert` costruisce l'evento con `id`, `type`, `severity`, `message`,
`sorgenteId` e `timestamp`: **non c'è nessun campo che dica se il guasto è aperto o chiuso**. Dal
lato del browser il gestore dell'evento `ALERT` scrive sempre `acknowledged: false` e `addAlert`
mette la riga in testa all'array senza guardare se un allarme con quell'id c'è già. Quindi lo stesso
guasto finisce due volte nella lista, la seconda volta come se fosse appena nato. Fra l'altro due
elementi con la stessa `key` in React è anche un errore di rendering (`key={alert.id}` in
`AlertsPage.tsx:73`).

Lo stesso meccanismo si vede anche sul percorso della deduplica: in `IngestionService.java:484`,
quando arriva un alert per un guasto già aperto, si rimanda `broadcastAlert(giaAperto)`. A database
la riga resta una sola (ed è giusto), ma nell'elenco del browser se ne aggiunge una a ogni
ripetizione — cioè a ogni treno che entra nella stazione guasta.

**Come dovrebbe essere.** L'evento `ALERT` deve portarsi dietro `risolto` e
`timestampRisoluzione`, e `addAlert` deve aggiornare l'allarme esistente quando l'id c'è già invece
di aggiungerne un altro.

**Riproduzione.** Avviare Centrale + stazione S1, aprire la pagina Allarmi, spegnere S1, aspettare
30 s (compare l'allarme), riaccenderla e aspettare il primo battito.

---

## A02 — Il cambio di stato di una stazione non viene spinto sulla WebSocket

**Sintomo.** Una stazione smette di battere: la Centrale la mette OFFLINE, la GET `/api/stazioni`
la restituisce OFFLINE, ma la mappa e l'elenco stazioni **continuano a mostrarla verde/operativa**
finché non si ricarica la pagina. Il KPI "Stazioni Anomale" non si muove. Stesso problema al
contrario: dopo "Presa Visione" o "Invia Operatori" la Centrale rimette la stazione ONLINE in cache,
ma sulla schermata resta guasta.

**Dove.** `FaultMonitor.controllaHeartbeat` (`FaultMonitor.java:83-118`) cambia solo la cache;
`RestApiGateway.risolviAllarme` (689-735) e `dispacciaManutenzione` (789-830) idem. Dal lato
browser la stazione viene aggiornata **solo** dal gestore dell'evento `HEARTBEAT`
(`useRealtimeUpdates.ts:44-51`), che per definizione non arriva più quando la stazione tace.

**Perché.** L'unico evento che il FaultMonitor manda al browser è l'`ALERT` del guasto, che nel
frontend finisce solo nella lista allarmi e non tocca `stations`. Non esiste nessun evento
"cambio stato stazione" generato dalla Centrale.

**Come dovrebbe essere.** Ogni volta che la Centrale cambia `stazione.stato` (watchdog, risoluzione
di un allarme, invio della squadra) dovrebbe fare un broadcast nello stesso formato dell'`HEARTBEAT`
(`stationId` + `status`), così la schermata si allinea senza F5. È quello che chiede RF01.1.4
("le schermate si aggiornano da sole"), e la documentazione della macchina M12 dà per scontato che
l'OFFLINE sia uno "dei tre stati che l'operatore vede davvero".

---

## A03 — La fotografia periodica non la legge nessuno e i KPI se li ricalcola il browser

**Sintomo.** La Centrale manda ogni 10 secondi un evento `SNAPSHOT` con i KPI. Nel frontend
**nessuno si sottoscrive a `SNAPSHOT`**: le uniche `subscribe` sono TELEMETRY, HEARTBEAT, ALERT e
TRANSIT (`useRealtimeUpdates.ts:18-76`). Anche `apiClient.getDashboard` (`apiClient.ts:182-187`) non
viene chiamato da nessuna parte. La `DashboardPage` si ricalcola tutto da sola dallo store
(`DashboardPage.tsx:24-32`).

**Perché è un problema e non solo codice morto.** I due calcoli non danno gli stessi numeri:

| KPI                | Centrale (`TrafficLogicEngine.kpiDashboard`, righe 212-234) | Browser (`DashboardPage`)                        |
|--------------------|--------------------------------------------------------------|--------------------------------------------------|
| treni in movimento | stato `attivo`                                               | stato `in_viaggio` **oppure** `in_ritardo`        |
| ritardo medio      | media su **tutti** i treni                                   | media sui **soli** treni in ritardo               |
| stazioni guaste    | `GUASTA` o `OFFLINE`                                         | tutte quelle diverse da `operativa` (dentro c'è anche `manutenzione`) |
| allarmi attivi     | guasti non risolti in cache                                  | lunghezza della lista locale, gonfiata dai doppioni di A01 |

Il diagramma SQ18 dichiara RF01.1.4, RF01.1.5 e RF02.6.3 "completi" e scrive nero su bianco che «i
numeri della dashboard li calcola una funzione sola, usata sia dalla chiamata REST sia dallo
snapshot: così la pagina appena aperta e quella aggiornata dal vivo non possono discordare». Nel
codice attuale sono due funzioni diverse e discordano.

C'è anche un secondo pezzo: il payload dello `SNAPSHOT` contiene **solo** i KPI, non treni e
stazioni. Anche se il browser lo leggesse non potrebbe riallineare niente, che è invece lo scopo
dichiarato di RF02.6.3 ("fotografia completa dello stato corrente").

**Come dovrebbe essere.** O la dashboard consuma lo `SNAPSHOT` (e allora il calcolo locale va
tolto), oppure lo `SNAPSHOT` va tolto dal FaultMonitor e il requisito va riscritto. La via coerente
con la documentazione è la prima.

---

## A04 — Il convoglio rimasto senza itinerario continua a dichiararsi in viaggio

**Sintomo.** Un convoglio a cui viene tolto l'itinerario (o il cui itinerario non è più scaricabile)
si ferma davvero — la posizione non avanza più — ma continua a mandare telemetria con
`stato = IN_VIAGGIO` e una velocità casuale fra 80 e 160 km/h. Sulla mappa resta un treno azzurro
"IN VIAGGIO" fermo sul posto; nell'elenco treni risulta "in viaggio" con una posizione congelata.

**Dove.** `TrainJourneyEngine.tick`, righe 160-179:

```java
if (ricaricaItinerarioRichiesta) {
    ricaricaItinerarioRichiesta = false;
    trainDB.itinerario = new ArrayList<>();
    trainDB.itinerarioId = null;
    viaggioAvviato = false;
    ultimoTentativoCaricamento = Instant.EPOCH;
}
...
if (trainDB.itinerario.isEmpty()) {
    tentaCaricamentoItinerario();
    return;          // <-- si esce senza aver toccato trainDB.stato
}
```

`trainDB.stato` non viene mai riportato a `FERMO`, e `TrainElab.generaTelemetria`
(`TrainElab.java:61-65`) mette la velocità a un valore casuale proprio perché legge
`"IN_VIAGGIO".equals(trainDB.stato)`.

**Effetto collaterale.** Il watchdog non se ne accorge: `controllaTreniFermi` cerca treni con
velocità 0 o telemetria vecchia, e qui la velocità è alta e la telemetria arriva ogni 5 secondi.
Quindi il sistema mostra uno stato *plausibile ma falso*, che è proprio il caso che RF02.5.1 chiama
"il caso peggiore possibile". Il requisito dice che il convoglio escluso "smette di viaggiare e
resta fermo in attesa di essere soppresso".

**Come dovrebbe essere.** Nel ramo della ricarica (e comunque quando l'itinerario è vuoto) il motore
deve mettere `trainDB.stato = "FERMO"`, `velocita = 0` e `faseViaggio = "IN_STAZIONE"` (o una fase
dedicata tipo `SENZA_ITINERARIO`).

---

## A05 — Eliminare un itinerario non avvisa i convogli che lo stavano percorrendo

**Sintomo.** L'amministratore cancella un itinerario dalla pagina Itinerari. I treni assegnati
vengono sganciati su database e in cache, ma **nessuno glielo dice**: il digital twin ha già
l'itinerario in memoria, non lo ricarica mai più e continua a percorrerlo, pubblicando passaggi e
transiti per un itinerario che non esiste più. La Centrale li registra regolarmente, perché
`registraTransito` lavora su treno + stazione e non controlla l'itinerario.

**Dove.** `RestApiGateway.deleteTratta`, righe 595-613: sgancia i treni, cancella
`Itinerario_Tratta` e `Storico_Itinerari`, ma non chiama `pubblicaItinerarioAggiornato` e non manda
nessuno `STOP`.

**Perché è strano.** La `PUT /api/tratte/{id}` lo fa correttamente (righe 566-582), e c'è pure il
commento che spiega perché i destinatari vanno letti *prima* di riassegnare. La `DELETE` è rimasta
indietro: stessa situazione, stesso bisogno, notifica assente.

**Come dovrebbe essere.** Nello stesso ciclo che sgancia i treni va pubblicato
`ITINERARIO_AGGIORNATO` per ciascuno (che, una volta sistemato A04, li lascia fermi e visibili).

---

## A06 — Modificare un itinerario sgancia i treni che gli erano stati assegnati dall'altra schermata

**Sintomo.** Sequenza tutt'altro che assurda per un amministratore:

1. dalla pagina Amministrazione assegno il treno `TRN007` all'itinerario `IT1_MI_NA` (form Treni);
2. poi apro lo stesso `IT1_MI_NA` nell'editor degli itinerari e sposto una stazione;
3. salvo: `TRN007` **viene sganciato dall'itinerario** senza che nessuno lo abbia chiesto, riceve
   `ITINERARIO_AGGIORNATO` e smette di viaggiare.

**Dove.** `railwayStore.adminUpdateTrain` / `adminCreateTrain` (righe 201-233) aggiornano solo
l'array `trains`: `routes[].trainIds` resta quello scaricato all'avvio da `initialize()`.
`RouteEditorModal` parte da `{...esistente}` (riga 45-46) e `apiClient.updateRoute`
(`apiClient.ts:303-317`) rimanda **quella lista vecchia** nel campo `treniIds`. Dal lato Centrale
`assegnaTreni(itinerario, dto.treniIds, true)` (`RestApiGateway.java:1058-1069`) sgancia tutti
quelli che non sono nell'elenco ricevuto — quindi anche quello aggiunto poco prima.

**Come dovrebbe essere.** Due strade, tutte e due ragionevoli: aggiornare anche `routes[].trainIds`
dentro `adminUpdateTrain`/`adminCreateTrain`, oppure non mandare `treniIds` nella PUT quando
l'editor degli itinerari non tocca le assegnazioni (l'editor infatti non ha nessun campo per
sceglierle: mostra solo il conteggio). La seconda è più sicura, perché toglie del tutto la
possibilità di sovrascrivere un dato che quella schermata non gestisce.

---

## A07 — L'anomalia "convoglio fermo fuori stazione" non scatta quando serve, e scatta quando non serve

Il controllo è in `FaultMonitor.controllaTreniFermi` (righe 126-144):

```java
if (!"attivo".equalsIgnoreCase(treno.stato)) continue;
boolean inStazione = treno.stazioneCorrente != null && !treno.stazioneCorrente.isEmpty();
boolean immobile   = treno.velocita == 0
                  || (treno.ultimoAggiornamento != null && treno.ultimoAggiornamento.isBefore(limite));
```

**Prima metà: non scatta.** Un convoglio veramente fermo fra due stazioni non è mai in stato
`attivo`. Se è trattenuto da una stazione guasta il twin dichiara `FERMO` (→ `fermo`), se è in
avaria dichiara `EMERGENZA` (→ `rotto`): in tutti e due i casi il `continue` lo salta. Lo scenario
SV04 dice invece "un convoglio è fermo fra due stazioni **per una causa qualsiasi**", e il
diagramma SQ11 dichiara RF02.6.2 completo. In pratica l'unico caso che fa scattare l'allarme è il
processo del treno che muore (telemetria che smette di arrivare), che è un caso diverso.

**Seconda metà: scatta a vuoto e subito.** La condizione `velocita == 0` non è legata a nessuna
durata: `T_fer` (90 s) vale solo per il ramo sulla telemetria vecchia. Siccome il twin manda
velocità 0 soltanto quando lo stato non è `IN_VIAGGIO`, l'unico treno che può avere insieme
`stato = attivo` e `velocita = 0` è **quello caricato dal database all'avvio della Centrale e mai
visto**: `TrafficLogicEngine.onStart` lo mette in cache con velocità 0 e `stazioneCorrente` nulla, e
al primo giro del watchdog (10 s dopo l'accensione) parte un guasto `treno_fermo` per ogni treno che
in tabella risulta `attivo` e il cui processo non è acceso. Con `populate_db.sql` sono TRN001 e
TRN002. È esattamente il falso positivo che per le stazioni è stato evitato apposta saltando quelle
che non hanno mai battuto (SQ10: «se no dieci secondi dopo l'accensione la centrale aprirebbe guasti
falsi su tutta la rete»); per i treni la stessa precauzione non c'è.

**Come dovrebbe essere.** Guardare la posizione e non lo stato dichiarato (fuori stazione + non
soppresso, come dice la precondizione di RF02.6.2), e pretendere che la condizione duri almeno
`T_fer` prima di aprire il guasto — per esempio ricordando da quando il treno è fermo, o saltando i
treni che non hanno mai trasmesso.

---

## A08 — Il guasto automatico `treno_fermo` non si chiude mai da solo

RF02.1.4 dice che un guasto aperto per deduzione si chiude da solo quando il nodo torna a dare
notizie. Nel codice questo vale solo per le stazioni: `IngestionService.chiudiGuastiHeartbeatPerso`
(righe 309-328) filtra su `sorgenteTipo = 'STAZIONE'`. Per un treno non c'è niente di simile: quando
la telemetria ricomincia ad arrivare (`onTelemetry`) nessuno va a chiudere il `treno_fermo` aperto
dal watchdog.

Effetto: l'allarme resta aperto per sempre finché non lo chiude un operatore, e siccome la deduplica
di `getGuastoApertoPerSorgente` guarda i guasti non risolti, per quella sorgente non se ne apre più
nessun altro. Sommato ad A07, dopo un riavvio della Centrale l'elenco allarmi si trova dentro dei
`treno_fermo` falsi che non se ne vanno più.

---

## A09 — I transiti riconsegnati dopo un'interruzione vengono storicizzati con l'ora del rientro

**Sintomo.** È lo scenario SV02 (store and forward). Durante l'interruzione la stazione accoda i
transiti nel `LocalBuffer` con dentro il loro `timestamp`; al ritorno del collegamento li rimanda
tutti in ordine. La Centrale li scrive **tutti con l'ora del rientro**, non con quella in cui il
treno è passato davvero: nello storico dieci minuti di transiti risultano avvenuti tutti nello
stesso istante.

**Dove.** `IngestionService.registraTransito`, riga 369: `Instant adesso = Instant.now();` e poi
`transito.tempoEntrata = adesso`. Il campo `timestamp` del payload — che
`StationGateway.inviaTransito` riempie al momento della rilevazione — non viene mai letto (nel
metodo `onTransit`, righe 332-362, si prendono solo `trenoId`, `stazioneId` e `tipo`).

Stessa cosa dal lato browser: `useRealtimeUpdates.ts:66-76` e `:53-64` scrivono
`timestamp: Date.now()` invece di usare `data.timestamp`, che pure viaggia nell'evento.

**Perché conta.** SQ12 dichiara RF02.1.1.1.1 e RF02.1.1.1.2 completi: l'ordine effettivamente regge
(il rientro in testa alla coda funziona), ma i tempi no, e lo storico è proprio il posto in cui
quell'informazione serve. Per gli alert vale lo stesso discorso: `parseTimestamp` in
`IngestionService` legge il `timestamp` del payload, quindi a database l'ora giusta c'è, ma
l'evento WebSocket la butta via e il browser mostra l'ora di arrivo.

---

## A10 — Lo storico dei transiti non è consultabile: manca la pagina

`initialize()` scarica i transiti (`railwayStore.ts:94`), `addTransit` li accumula a ogni evento
`TRANSIT` (riga 270-271), il tipo `Transit` è definito in `types/index.ts`... e **nessun componente
legge `transits`**. Non c'è una pagina Transiti, non c'è una voce nella sidebar, non compaiono in
nessuna delle schermate esistenti.

RF01.5 chiede che i transiti passati siano consultabili con convoglio, stazione, **tratta**, verso e
istante, e la tabella di copertura dei diagrammi di sequenza dice «SQ06 scrive lo storico dei
transiti, SQ18 lo porta a schermo». A schermo non ci arriva.

Lo metto fra i bug e non fra le cose non implementate perché la catena è tutta già montata (endpoint
REST, chiamata, mappatura, tipo, store, aggiornamento in tempo reale) e manca solo l'ultimo pezzo.
Va segnalato anche che il DTO di `GET /api/transiti` (`RestApiGateway.java:627-649`) non contiene la
tratta, che il requisito elenca fra i campi.

---

## A11 — Il treno resta "in stazione" anche dopo essere ripartito

**Sintomo.** Il convoglio parte da Milano: sulla mappa si muove correttamente lungo l'arco, ma nel
pannello di dettaglio continua a comparire "Stazione attuale: Milano Centrale", e nella pagina
Stazioni Milano lo elenca ancora fra i "Treni Attesi" come `FERMO/PARTENZA`. La cosa si trascina
fino al successivo ingresso in una stazione.

**Dove.** `useRealtimeUpdates.ts:22-38`:

```ts
const originStationId = typeof data.stazioneCorrente === 'string' && data.stazioneCorrente
  ? data.stazioneCorrente : null;
...
...(originStationId ? { currentStationId: originStationId, previousStationId: originStationId } : {}),
```

Quando il treno esce dalla stazione manda `stazioneCorrente: ""`, quindi `originStationId` è `null`
e il campo **non viene aggiornato**: resta l'ultimo valore buono. Per `previousStationId` è voluto e
giusto (serve alla mappa per sapere da dove è partito), per `currentStationId` no: quello deve
tornare `null`.

**Chi ci casca.** `StationsPage.getExpectedTrains` (righe 20-27),
`TrafficMapPage.tsx:892` (`treniInStazione`), il riquadro "Stazione attuale" della mappa
(`TrafficMapPage.tsx:1244-1259`) e la colonna "Posizione Attuale" della pagina Treni per i convogli
che non risultano `in_viaggio`. È il dato con cui RF01.1.3 chiede di mostrare "i convogli fermi sui
binari in quel momento".

Da notare che la cache della Centrale invece è giusta (`IngestionService.onTelemetry` righe 176-177
mette `null` sulla stringa vuota): il disallineamento nasce solo nel browser, e infatti dopo un F5
il dato torna corretto.

---

## A12 — Eliminare una tratta fisica usata come posizione di un treno risponde 500

`RestApiGateway.deleteTrattaElemento` (righe 466-476) prima di cancellare controlla che la tratta
non sia usata da itinerari, transiti e storico transiti. Non controlla la colonna
`Treni.PosizioneAttualeTrattaOStazione`, che è una chiave esterna verso `Tratte`
(`schema.sql`, definizione della tabella `Treni`). Con i dati di `populate_db.sql` basta provare a
cancellare `T3_FI_RM`, che è la posizione corrente di `TRN004`: parte il `DELETE`, Postgres rifiuta
per violazione di vincolo e l'utente si prende un 500 con l'eccezione, invece del 409 con la
spiegazione che RF01.3.5 chiede esplicitamente ("o l'eliminazione viene rifiutata con una
spiegazione comprensibile...").

Basta una `count` in più sulla stessa falsariga di quelle già scritte.

---

## A13 — Nello storico dei transiti il ritardo è quello attuale del treno

`RestApiGateway.getTransiti` (righe 634-646) per ogni riga di storico legge il ritardo dalla cache
in RAM:

```java
Treno cacheTreno = statoRete.getTreno(t.treno.id);
int ritardo = cacheTreno != null ? cacheTreno.ritardo : 0;
```

Quindi un transito di due ore fa mostra il ritardo che il treno ha adesso, e tutti i transiti dello
stesso convoglio mostrano lo stesso numero. Dopo un riavvio della Centrale mostrano tutti 0. Il
ritardo al momento del passaggio c'è (il treno lo manda dentro il messaggio di passaggio,
`TrainGateway.notificaPassaggioStazione`), semplicemente non viene salvato da nessuna parte:
andrebbe messo su `Transito`/`Storico_Transiti` al momento della registrazione.

---

## A14 — Il pulsante "Invia Operatori (UC5)" quasi non compare

In `AlertsPage.tsx:118` il pulsante compare solo se `alert.type === 'stazione_guasta'`. Il tipo lo
decide `IngestionService.tipoGuastoPerFrontend` (righe 598-609), che come prima cosa fa:

```java
if (messaggio != null && messaggio.toLowerCase().contains("sensore")) return "sensore_offline";
```

Risultato, guasto per guasto:

| Da dove arriva                                        | Messaggio                                      | Tipo finale       |
|-------------------------------------------------------|------------------------------------------------|-------------------|
| `POST /stazione/sensore/guasto` senza descrizione      | "Guasto generico **sensore**"                  | `sensore_offline` |
| keepalive di un sensore scaduto (`controllaSensori`)   | "**Sensore** X non invia keepalive"            | `sensore_offline` |
| fail-stop dedotto dalla Centrale (`FaultMonitor`)      | "Heartbeat assente: ..."                       | `sensore_offline` (imposto nel codice) |
| treno che entra in una stazione già guasta             | "Stazione S1 guasta: treno T trattenuto"       | `stazione_guasta` |

Cioè: il tipo che abilita il comando lo produce solo l'ultimo caso, quello in cui *un treno prova a
entrare* in una stazione già guasta. Per tutti gli altri l'operatore vede l'allarme ma non ha il
pulsante, e non c'è nessun altro punto dell'interfaccia da cui mandare la squadra (`dispatchOperators`
è richiamato solo da `AlertsPage`). RF01.4.1 dà per scontato che il comando sia raggiungibile.

Da sistemare su due lati: il filtro nel frontend (basterebbe guardare `alert.stationId`) e
l'euristica sulla parola "sensore", che oltretutto classifica come guasto di sensore anche un guasto
di bordo di un treno se qualcuno scrive "sensore" nella descrizione.

---

## A15 — Gli allarmi inventati dal browser non esistono sul server

`railwayStore.suppressTrain` e `dispatchOperators` (righe 279-320) aggiungono alla lista degli
allarmi delle righe costruite in locale, con id `al-supp-<timestamp>` / `al-ops-<timestamp>` e tipi
(`treno_soppresso`, `operatori_inviati`) che sul server non esistono. Conseguenze: spariscono al
primo F5, non le vede nessun altro operatore collegato, e se si preme "Presa Visione" parte una
`POST /api/allarmi/al-supp-.../risolvi` che risponde 404 — errore che viene solo scritto in console
mentre l'interfaccia segna comunque l'allarme come risolto (`acknowledgeAlert`, righe 137-148).

Sono notifiche d'interfaccia travestite da allarmi: o diventano un vero guasto sul server, o vanno
tenute in una lista separata da quella degli allarmi.

---

## A16 — Una stazione che non ha mai battuto risulta con l'ultimo heartbeat "adesso"

`apiClient.getStations`, riga 234:

```ts
lastHeartbeat: s.ultimoHeartbeat ? new Date(s.ultimoHeartbeat).getTime() : Date.now(),
```

All'avvio la Centrale tiene apposta `ultimoHeartbeat` a `null` per le stazioni che non hanno ancora
battuto (`TrafficLogicEngine.onStart`, il commento lo spiega bene). Il frontend però ci mette
l'ora corrente, quindi la colonna "Ultimo Heartbeat" della pagina Stazioni
(`StationsPage.tsx:68`) mostra l'orario di adesso per una stazione spenta. Meglio mostrare un
trattino.

---

## A17 — Il FaultMonitor pubblica il guasto prima del commit(in quale caso il vincolo potrebbe essere violato comunque bug leggero di robustezza)

`creaGuastoAutomatico` (`FaultMonitor.java:171-199`) gira dentro un metodo `@Transactional`: fa
`persist()` del guasto e dello storico e poi, **prima che la transazione sia chiusa**, chiama
`ingestion.broadcastAlert(guasto)` e `ingestion.pubblicaGuastoSuMqtt(guasto)`. Se il commit fallisce
(vincolo violato, database che non risponde) il guasto è già stato annunciato alla dashboard e ai
treni, che si fermano per un guasto che a database non esiste.

È lo stesso problema per cui in `IngestionService` le transazioni sono state spostate *dentro* il
try con `QuarkusTransaction.requiringNew()`: qui la correzione non è stata riportata.

---

## A18 — Ogni messaggio WebSocket ri-renderizza tutta l'applicazione

`useRealtimeUpdates` (riga 11) fa `const { ... } = useRailwayStore()` senza selettore, ed è
chiamato da `RailwayApp` (`App.tsx:57`), cioè dal componente che contiene tutte le rotte. Con Zustand
prendere lo store senza selettore vuol dire sottoscriversi a *qualsiasi* cambiamento: ogni frame di
telemetria (uno ogni 5 s per treno), ogni battito e ogni transito fanno ri-renderizzare l'intero
albero, mappa compresa. Con cinque treni si regge, ma è lavoro inutile e cresce in fretta con il
numero di nodi (RNF05 parla proprio di reggere più nodi contemporaneamente). Basta prendere le
singole azioni con un selettore (`useRailwayStore(s => s.updateTrain)`).

---

## A19 — I test `@QuarkusTest` scrivono sul database di sviluppo

`ServeCentraleOperativa/src/test/resources/application.properties` contiene solo host e porta HTTP:
non c'è nessun override `%test` del datasource, quindi i test usano
`jdbc:postgresql://localhost:5432/railway`, cioè lo stesso database dell'esecuzione normale.
`AdminApiTest` e `ItinerariTratteTest` creano davvero stazioni, tratte e treni (`STAZ_TEST_xxxx`,
`IT_A_xxxx`, ...) nel database di lavoro. La pulizia c'è — l'ultimo test in ordine cancella quello
che ha creato — ma vale solo se la sequenza arriva in fondo: se un test in mezzo fallisce, le righe
restano lì. E senza Postgres acceso `./mvnw test` fallisce in blocco, il che rende inutile il
motivo per cui i test erano stati scritti senza Keycloak ("ho preferito test che girano sempre").

---

## A20 — Un itinerario che ripassa sulla stessa tratta fa saltare la chiave primaria

La chiave di `Itinerario_Tratta` è `(id_itinerario, id_Tratta)`: la colonna `ordine` non ne fa
parte (schema.sql e `ItinerarioTratta.Id`). Quindi un itinerario che percorre due volte lo stesso
arco non è rappresentabile: `componiItinerario` (`RestApiGateway.java:1044-1049`) prova a inserire
due righe con la stessa chiave e la richiesta finisce in 500. Non è una situazione frequente, ma
l'editor degli itinerari non impedisce di comporre un elenco del genere, e il messaggio che
l'amministratore riceve non spiega niente. O la chiave comprende `ordine`, o il caso va rifiutato
con un 400 parlante.

---

## Cose che ho controllato e che NON ho messo in elenco

Le scrivo per far vedere che sono state guardate e che il giudizio è consapevole.

**Già dichiarate nella documentazione** (capitolo *Progettazione*, sezione *Limiti dichiarati*, e
righe "requisiti coperti" dei diagrammi):

- la WebSocket `/ws/realtime` non è autenticata;
- `GET /api/prossima-stazione` e `GET /api/treni/{id}/itinerario` restano aperte ai nodi di campo;
- lo stato `MANUTENZIONE` non sopravvive alla chiamata REST che lo imposta (M12, SQ14);
- la presa in carico di un allarme non registra nessun operatore, e `Guasti.OperatoreCheSeNeStaOccupandoFK`
  resta sempre nullo (SQ14, riepilogo delle classi: "solo quattro" delle sei entità di storico
  vengono scritte davvero);
- la precondizione "convoglio fermo in stazione" della soppressione non viene verificata (SQ15);
- RF02.5.2: dopo una modifica dell'itinerario il convoglio riparte dal capolinea e non dal punto in
  cui si trovava (SQ16);
- la distinzione fra guasto segnalato e guasto dedotto non arriva all'operatore (SQ10, M-riepilogo);
- alla riconnessione della WebSocket non c'è recupero dei messaggi persi;
- i token stanno nel `sessionStorage`; Keycloak resta in chiaro anche sotto il profilo TLS;
- il buffer della stazione è in RAM e non sopravvive al riavvio del processo;
- i transiti non sono idempotenti, per questo i canali `transit-in`/`passaggio-in` restano a QoS 0
  (commento in `application.properties` della Centrale).

**Non implementate** (quindi fuori dal perimetro, come da indicazione):

- RF02.1.2.2.1, il convoglio che si guasta in stazione non rende la stazione non percorribile
  (`todo` in `IngestionService.onAlert`);
- RF02.1.2.2.2, il convoglio che si guasta in tratta non rende la tratta non percorribile;
- gestione dei binari multipli (la colonna `binari` esiste ma non c'è nessuna logica di
  allocazione);
- persistenza locale sui nodi di campo (gli `schema.sql` di `Treni` e `Stazioni` sono modelli, non
  codice attivo).

**Altre note minori** che non ho contato come bug veri e propri:

- l'entità `TelemetriaTreno` non è usata da nessuna parte: Hibernate crea la tabella
  `telemetria_treni` e nessuno ci scrive;
- il contatore `Stazione.treniInStazione` viene mantenuto da `onTransit` ma non è letto da nessuno
  (il frontend non lo mappa) e non torna più se un treno viene riposizionato dalla ricarica di un
  itinerario, che non genera nessuna uscita;
- `import.sql` nelle risorse della Centrale non viene eseguito, perché con
  `quarkus.hibernate-orm.database.generation=update` Quarkus non lancia lo script di import: i dati
  di prova vanno caricati a mano con `populate_db.sql` (il README dice "vedi import.sql", che ne è
  la copia).
