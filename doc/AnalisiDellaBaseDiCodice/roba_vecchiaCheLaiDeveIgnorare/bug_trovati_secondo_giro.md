# Secondo giro di lettura della base di codice — 16/08/2026

Questa è una rilettura fatta **dopo** le correzioni descritte in [correzioni_bug.md](correzioni_bug.md),
quindi sul codice com'è adesso. I venti problemi di [bug_trovati.md](bug_trovati.md) risultano tutti
sistemati e non li ripeto: qui ci sono solo cose nuove, comprese due che sono conseguenza delle
correzioni stesse.

La numerazione riparte con `Cxx` per non accavallarsi né con gli `Axx` del primo giro né con i
vecchi `B0xx` di [gap_analysis.md](gap_analysis.md).

Due di questi difetti non sono ipotesi: li ho visti nei dati del database di sviluppo e nello stato
dei processi che stanno girando adesso (C01 e C02). Per gli altri riporto il percorso nel codice.

## Riepilogo

| #   | gravità | dove          | in due parole                                                                 |
|-----|---------|---------------|-------------------------------------------------------------------------------|
| C01 | media   | Centrale      | L'uscita da una stazione chiude il transito sbagliato: soste di due giorni nello storico |
| C02 | media   | UI            | Al ritorno il convoglio resta incollato alla stazione di arrivo (mappa e pagina Treni) |
| C03 | media   | Centrale      | Se un sensore è già guasto, la caduta della stazione non apre più il fail-stop e i treni non vengono avvisati |
| C04 | bassa   | UI            | "Ultimo aggiornamento: adesso" per un convoglio che non ha mai trasmesso        |
| C05 | bassa   | Centrale + UI | `/api/allarmi` senza limite, e i guasti senza data finiscono in cima alla dashboard |
| C06 | bassa   | Centrale + UI | La spunta "Tratta Attiva" non fa niente                                        |
| C07 | bassa   | tutti         | I payload MQTT sono composti con `String.format` senza escape del JSON         |
| C08 | bassa   | Test          | `./mvnw test` scollega dal broker la Centrale accesa (stesso client-id MQTT)   |
| C09 | bassa   | UI            | `disconnect()` della WebSocket si riconnette da solo dopo cinque secondi       |
| C10 | bassa   | Centrale      | Itinerario creato a metà se la composizione fallisce dopo la `persist()`       |

---

## C01 — L'uscita chiude il transito aperto sbagliato

`IngestionService.registraTransito` (riga 481) chiude l'uscita così:

```java
Transito aperto = Transito.find(
        "treno.id = ?1 and stazione.id = ?2 and tempoUscita is null",
        trenoId, stazioneId).firstResult();
```

Non c'è nessun ordinamento, quindi `firstResult()` prende una riga qualunque fra quelle aperte —
in pratica la più vecchia. Lo stesso convoglio passa dalla stessa stazione a ogni giro di
andata e ritorno, e basta che una USCITA si perda una volta (il canale è a QoS 0, oppure la
Centrale era spenta in quel momento) perché resti un transito aperto per sempre: da lì in poi
**ogni uscita successiva chiude quel record vecchio invece di quello corrente**.

Sul database di sviluppo si vede già:

```
id_convoglio | id_stazione | tempoentrata               | tempouscita
Mario        | Padova      | 2026-08-14 18:07:14       | 2026-08-16 07:59:25
Mario        | Padova      | 2026-08-16 07:58:45       | (aperto)
```

La prima riga dice che il treno è stato fermo a Padova per quasi due giorni; la seconda, che è il
passaggio vero di stamattina, resta aperta e nello storico compare per sempre come "ingresso".
Ci sono anche tre coppie treno/stazione con due transiti aperti contemporaneamente
(`Francesco`/stazzaDellaMadonnaTroia, `Genoveffa`/MI, `Pietro`/stazzaDellaMadonnaTroia).

Ricade su RF01.5: i tempi di sosta dello storico sono inventati e la pagina Transiti mostra
ingressi che non si chiudono mai.

**Come lo sistemerei.** Ordinare la ricerca per `tempoEntrata` decrescente, così si chiude sempre
il passaggio più recente, e già che si è lì chiudere (o marcare) quelli rimasti indietro: un
convoglio non può essere dentro la stessa stazione due volte insieme.

## C02 — In direzione ritorno il convoglio resta fermo sulla stazione di arrivo

`apiClient.mapApiTrain` (riga 122) ricostruisce da dove è partito il treno così:

```ts
previousStationId: t.posizioneAttualeTratta?.stazionePartenza?.id || t.stazioneCorrente || null,
```

Ma la tratta è **orientata** e al ritorno viene percorsa al contrario: `stazionePartenza` è la
stazione verso cui il convoglio sta andando, non quella da cui è uscito. Il risultato è che
`previousStationId` e `nextStationId` diventano la stessa stazione.

Stato reale di adesso, preso dai processi accesi e dal database:

- il twin dice: `Mario`, direzione `ritorno`, in viaggio da Padova verso `MI`, progresso 39%;
- la tabella `treni` dice: `posizioneattualetrattaostazione = T_MI_Padova_1` (partenza MI, arrivo Padova);
- il mappatore quindi produce `previousStationId = MI` e `nextStationId = MI`.

Effetti visibili:

- **mappa**: l'interpolazione fra MI e MI dà sempre MI, quindi il treno viene disegnato fermo sul
  nodo di arrivo per tutta la tratta di ritorno (`TrafficMapPage`, blocco degli elementi);
- **pagina Treni**: la colonna "Posizione Attuale" scrive `Tra MI e MI` (righe 79-81).

In andata non si vede perché lì partenza della tratta e provenienza coincidono: **il difetto
riguarda metà di ogni viaggio**.

C'è anche un aggravante che viene da A03: l'evento `TELEMETRY` scrive `previousStationId` solo
quando il frame porta una stazione corrente (cioè quando il treno è fermo in stazione), quindi in
viaggio il valore buono resta quello messo alla partenza — ma lo `SNAPSHOT`, che ogni dieci secondi
rifà l'array dei treni con lo stesso mappatore, lo sovrascrive con quello sbagliato e da lì non si
recupera più fino alla fermata successiva.

**Come lo sistemerei.** La provenienza è deducibile dalla direzione: se `direzione == "ritorno"` i
due estremi della tratta vanno letti scambiati. In alternativa non ricostruirla affatto quando il
convoglio è in viaggio e lasciare che sia la telemetria a tenerla, ma allora lo snapshot non deve
azzerare il campo che ha già.

## C03 — Se un sensore è già guasto, la stazione che cade non apre più il fail-stop

`FaultMonitor.controllaHeartbeat` (riga 115) apre il guasto della stazione silente solo se non ce
n'è già uno aperto **dello stesso tipo**:

```java
if (statoRete.getGuastoApertoPerSorgente(stazione.id, "sensore_offline") == null) {
    creaGuastoAutomatico("sensore_offline", "CRITICAL", "STAZIONE", ...);
}
```

Solo che `sensore_offline` è anche il tipo che la stazione dichiara da sola quando un sensore di
binario smette di mandare il keepalive (`HeartbeatGenerator.controllaSensori` →
`StationGateway.inviaGuasto(..., "sensore_offline")`, il campo `tipoGuasto` introdotto con A14).

Quindi nella sequenza: sensore muto → guasto `sensore_offline` aperto → poi cade la connessione
della stazione, il watchdog la mette OFFLINE **ma non apre nessun guasto**. E siccome è
`creaGuastoAutomatico` a chiamare `pubblicaGuastoSuMqtt`, **sul topic `railway/alerts` non parte
niente**: i treni non ricevono l'avviso che la stazione davanti è caduta e continuano a entrarci,
che è esattamente ciò che il fail-stop doveva evitare. Lo stato OFFLINE arriva solo alla dashboard,
via `broadcastStatoStazione`, che sta prima del controllo.

La documentazione dichiara già che i due casi hanno lo stesso tipo e che nell'elenco non si
distinguono (nota su RF01.2.2). Quello che non è dichiarato — ed è la parte che conta — è che la
collisione di tipo **sopprime l'apertura del guasto**, non solo la sua leggibilità.

**Come lo sistemerei.** Al watchdog serve un secchiello suo: la deduplica può cercare il guasto
aperto per sorgente il cui messaggio comincia con `MSG_HEARTBEAT_PERSO`, che è già il marcatore
usato per richiuderlo in `onHeartbeat`. Così i due guasti convivono, ognuno con il suo ciclo di
vita, senza toccare il vocabolario dei tipi.

## C04 — "Ultimo aggiornamento: adesso" per un convoglio spento

Stessa identica sostituzione di A16, rimasta però sui treni
(`apiClient.mapApiTrain`, riga 130):

```ts
lastUpdate: t.ultimoAggiornamento ? new Date(t.ultimoAggiornamento).getTime() : Date.now()
```

Prima non si vedeva perché `TrafficLogicEngine.onStart` scriveva `Instant.now()` su tutti i treni
caricati. Con A07 quel campo resta **nullo** apposta (è ciò che impedisce i falsi "treno fermo"), e
adesso il fallback scatta davvero: il pannello della mappa (`TrafficMapPage`, riga 1241, voce
"Ultimo aggiornamento") mostra l'ora corrente per un convoglio il cui processo non è mai partito.

**Come lo sistemerei.** Come per le stazioni: `lastUpdate: number | null` e un trattino a schermo
quando è nullo.

## C05 — `/api/allarmi` scarica tutto, e i guasti senza data finiscono in cima

Due cose collegate, tutte e due visibili adesso:

1. `getAllarmi` (riga 708) fa `Guasto.listAll(...)` senza pagina. Sul database di sviluppo sono
   **293 righe**, spedite tutte a ogni apertura della web app, e crescono a ogni guasto della
   storia del sistema. `getTransiti`, due metodi più su, si ferma a 200: qui il limite non c'è.
2. L'ordinamento è `Sort.descending("timestamp")` e in Postgres il decrescente mette i **NULL per
   primi**. Le 27 righe che hanno `ts_apertura` nullo (guasti scritti prima che quelle colonne
   fossero persistite) sono quindi le prime dell'elenco, e il frontend le data con
   `Date.now()` (`apiClient.getAlerts`, riga 366).

Il risultato è che la card "Ultimi Allarmi" della dashboard — che prende `alerts.slice(0, 5)` senza
filtrare i risolti — mostra cinque righe vuote, "Allarme di sistema per G1", con l'ora corrente,
invece degli ultimi allarmi veri.

**Come lo sistemerei.** Paginare come i transiti, aggiungere `NULLS LAST` all'ordinamento (o
scartare le righe senza data), e nel frontend non inventare il timestamp mancante.

## C06 — La spunta "Tratta Attiva" non fa niente

`RouteEditorModal` ha la casella (righe 238-243), `apiClient` la manda come `attivo`, il DTO
`TrattaDTO` ha il campo (riga 115) — e nessuno lo legge mai: né `createTratta` né `updateTratta` lo
toccano, e `trattaToDto` risponde sempre `dto.put("attivo", true)` (riga 1181). L'entità
`Itinerario` non ha una colonna per tenerlo.

Quindi l'amministratore toglie la spunta, salva, e la tratta torna "Attiva": il badge "Sospesa" di
`RoutesPage` non può comparire mai, e il filtro `routes.filter(({ active }) => active)` con cui la
mappa costruisce gli archi è sempre vero.

**Come lo sistemerei.** O si aggiunge la colonna all'itinerario e la si usa davvero, o si toglie la
casella dal form: adesso è un comando che promette una cosa che non succede.

## C07 — I payload MQTT sono composti senza escape del JSON

Tutti i messaggi vengono costruiti con `String.format` interpolando direttamente i valori:

- `RestApiGateway`: `deleteTreno`, `sopprimiTreno`, `pubblicaResolved`, `pubblicaItinerarioAggiornato`;
- `StationGateway`: `inviaGuasto` (con la descrizione libera del guasto) e `inviaTransito`;
- `TrainGateway`: `inviaGuasto` e `notificaPassaggioStazione`.

Il nome del convoglio è la chiave primaria scelta a mano dall'amministratore e viene validato solo
sulla lunghezza (50 caratteri), quindi può contenere virgolette o barre rovesce; la descrizione di
un guasto arriva dal corpo di `POST /stazione/sensore/guasto`. In quel caso il JSON prodotto non è
valido: il destinatario logga "payload non interpretabile" e il comando (uno STOP, per dire) va
perso in silenzio.

Che il problema sia noto si vede da `Sensori.java` del modulo Treni, che ha già il suo
`escapeJson`: è stato risolto in un punto solo su nove.

**Come lo sistemerei.** Comporre i payload con `ObjectMapper`/`ObjectNode` come fa già
`IngestionService`, oppure passare tutte le stringhe da un `escapeJson` condiviso.

## C08 — I test scollegano dal broker la Centrale accesa

`src/test/resources/application.properties` mette a posto quello che serve per non toccare i dati
di lavoro (H2 in memoria, porta HTTP 0, OIDC spento), ma **non tocca MQTT**: i canali restano
quelli di `application.properties`, compresi i client-id fissi

```
mp.messaging.incoming.alerts-in.client-id=centrale-alerts-in
mp.messaging.outgoing.alerts-out.client-id=centrale-alerts-out
```

Il protocollo MQTT vuole i client-id unici: quando la suite di test si connette al broker, Mosquitto
**butta fuori la Centrale vera** che sta usando gli stessi identificativi. Con
`auto-clean-session=false` le si porta via anche la sessione persistente. Basta quindi lanciare
`./mvnw test` con il sistema acceso per far smettere alla Centrale di ricevere gli alert, senza
nessun errore evidente da nessuna delle due parti.

**Come lo sistemerei.** Nel profilo di test o si spengono i connettori
(`%test.mp.messaging...connector=smallrye-in-memory`), oppure si danno client-id diversi, per esempio
suffissati con il PID.

## C09 — Il `disconnect()` della WebSocket si riconnette da solo

In `websocketClient.disconnect()` si cancella il timer di riconnessione e *poi* si chiude la socket,
ma `close()` è asincrona: il gestore `onclose` scatta dopo e programma un nuovo tentativo fra cinque
secondi. Il canale quindi si riapre anche dopo una disconnessione voluta — al logout, o allo
smontaggio di `RailwayApp` — e da lì in poi resta agganciato a ricevere telemetria senza che nessuno
lo stia più leggendo. In più `connect()` controlla solo lo stato `OPEN`, quindi chiamarla mentre la
socket è ancora in `CONNECTING` ne apre una seconda e la prima resta appesa con i suoi gestori.

**Come lo sistemerei.** Un flag "chiusura voluta" da controllare dentro `onclose` prima di
riprogrammare il timer, e il controllo di `connect()` esteso anche a `CONNECTING`.

## C10 — Itinerario creato a metà se la composizione fallisce

In `createTratta` l'itinerario viene scritto prima di comporlo:

```java
itinerario.persist();
Response errore = componiItinerario(itinerario, dto.stazioni, dto.travelTimes);
if (errore != null) return errore;
```

Se `componiItinerario` torna un errore, la `@Transactional` **non** fa rollback (fa rollback solo
sulle eccezioni, come è già scritto nel commento di `verificaTratteEsistenti`), quindi la risposta è
404 ma l'itinerario resta a database senza nessuna tratta. Lo stesso vale per `updateTratta`, dove
il fallimento arriva dopo la `ItinerarioTratta.delete(...)` e lascia l'itinerario smontato.

Ci si arriva solo se una tratta viene cancellata fra la verifica e la composizione, ed è per questo
che nel codice quel ramo è chiamato "rete di sicurezza": ma la rete di sicurezza lascia il database
a metà, che è la cosa che le altre verifiche stanno attente a non fare.

**Come lo sistemerei.** In quei due rami basta un `QuarkusTransaction.rollback()` (o lanciare
un'eccezione applicativa e tradurla nella Response) prima di uscire.

---

## Cose minori, annotate e basta

- **A18 è applicato a metà.** Il re-render dell'intera applicazione non c'è più, ma
  `TrafficMapPage` (riga 454), `TrainsPage`, `StationsPage`, `RoutesPage`, `TrackSegmentsPage`,
  `AdminPage` e i tre modali di amministrazione prendono ancora lo store intero
  (`const { ... } = useRailwayStore()`), quindi si ridisegnano a ogni evento qualunque, compresi
  quelli che non li riguardano.
- **L'elenco dei transiti nel browser non ha tetto.** `addTransit` mette in testa e basta: in una
  sessione lunga l'array cresce indefinitamente (le altre liste arrivano dal server già limitate).
  E l'evento `TRANSIT` non porta un id, quindi il frontend ne inventa uno con `Date.now()`: due
  transiti nello stesso millisecondo hanno la stessa `key` React.
- **La cache dei guasti non si svuota mai.** `TrafficLogicEngine.risolviGuasto` lascia il guasto
  dentro `guastiAttivi` apposta ("così il frontend fa in tempo a vederlo"), ma nessuno lo toglie
  dopo: la mappa cresce per tutta la vita del processo e `getGuastoApertoPerSorgente`, che è una
  scansione lineare, viene chiamata a ogni telemetria di ogni treno.
- **Un treno soppresso e poi spento può prendersi un "convoglio fermo".** `sopprimiTreno` scrive
  `SOPPRESSO` nella cache mentre il watchdog salta i convogli in stato `in manutenzione`: finché il
  twin trasmette la telemetria rimette a posto il valore entro cinque secondi, ma se il processo del
  treno è già spento la cache resta com'è e dopo 90 secondi si apre un guasto automatico su un
  convoglio fermato apposta dall'operatore.

## Cosa ho escluso apposta

**Limiti già dichiarati nella documentazione** (capitolo Progettazione, "Limiti dichiarati", più le
note sotto la tabella dei requisiti): stato MANUTENZIONE che dura quanto la chiamata e quindi
`operatorsDispatched` che si azzera al `STATION_STATUS` successivo e a ogni snapshot; presa in
carico dell'allarme senza operatore assegnato; WebSocket non autenticata; nessun recupero degli
eventi persi alla riconnessione; transiti non idempotenti; RF02.5.2 che riparte dal capolinea;
passaggi spaiati ai capolinea; soppressione di un convoglio che non è fermo in stazione; endpoint di
campo aperti; `Storico_Itinerari` e `Storico_Assegnazioni_Guasti` mai scritte.

**Già noto e scritto in `correzioni_bug.md`**: `risolviAllarme` e `dispacciaManutenzione` pubblicano
su MQTT e WebSocket da dentro un metodo `@Transactional`, cioè prima del commit (è la nota finale di
A17).

**Funzioni mai implementate**, che non sono difetti del codice scritto: RF02.1.2.2.1 e RF02.1.2.2.2
(il guasto di un convoglio non rende inagibile stazione o tratta), binari multipli, persistenza su
disco del buffer dei nodi di campo.

## Come ho controllato

| Cosa                                   | Comando                                   | Esito |
|----------------------------------------|-------------------------------------------|-------|
| Centrale: compilazione                 | `./mvnw -o compile`                       | ok    |
| Treni: compilazione                    | `./mvnw -o compile`                       | ok    |
| Stazioni: compilazione                 | `./mvnw -o compile`                       | ok    |
| C01: transiti aperti e soste anomale   | query su `transiti` e `storico_transiti`  | confermato |
| C02: posizione del convoglio in ritorno| `GET :9080/treno/viaggio` + tabella `treni` | confermato |
| C05: guasti senza data in cima         | `SELECT ... ORDER BY ts_apertura DESC`    | confermato (27 righe su 293) |

I test della Centrale **non** li ho lanciati apposta: il sistema è acceso e per C08 la suite gli
avrebbe portato via la connessione al broker.
