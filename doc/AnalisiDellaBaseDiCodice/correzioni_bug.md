# Correzioni dei bug elencati in bug_trovati.md — 16/08/2026

Documento di accompagnamento a [bug_trovati.md](bug_trovati.md): lì c'è l'elenco dei problemi
trovati leggendo il codice, qui c'è cosa ho cambiato per ognuno e perché. La numerazione `Axx` è
la stessa, così i due file si leggono affiancati.

Sono stati sistemati **tutti e venti** i bug dell'elenco. Restano fuori, come nell'analisi, le
funzioni mai implementate (RF02.1.2.2.1, RF02.1.2.2.2, binari multipli, persistenza sui nodi di
campo) e i limiti che la documentazione dichiara già: non erano bug e non li ho toccati.

## Riepilogo

| #   | In due parole                                              | Dove ho messo mano                                   |
|-----|------------------------------------------------------------|------------------------------------------------------|
| A01 | Chiusura di un guasto mostrata come allarme nuovo           | `IngestionService`, `railwayStore`, `useRealtimeUpdates` |
| A02 | Stato stazione non spinto sulla WebSocket                   | `IngestionService` (evento nuovo), `FaultMonitor`, `RestApiGateway`, `useRealtimeUpdates` |
| A03 | Snapshot inutilizzato e KPI ricalcolati dal browser         | `FaultMonitor`, `apiClient`, `railwayStore`, `useRealtimeUpdates`, `DashboardPage` |
| A04 | Treno senza itinerario che si dichiara in viaggio           | `TrainJourneyEngine`, `TrainDB`                       |
| A05 | Eliminazione itinerario senza notifica ai treni             | `RestApiGateway.deleteTratta`                         |
| A06 | Modifica itinerario che sgancia i treni assegnati altrove   | `RestApiGateway`, `apiClient`, `RouteEditorModal`, `railwayStore` |
| A07 | Anomalia "treno fermo" che non scatta / scatta a vuoto      | `FaultMonitor.controllaTreniFermi`, `TrafficLogicEngine`, `RestApiGateway` |
| A08 | Guasto `treno_fermo` che non si richiude mai                | `IngestionService`, `FaultMonitor`                    |
| A09 | Transiti storicizzati con l'ora del rientro                 | `IngestionService.onTransit`, `useRealtimeUpdates`    |
| A10 | Storico transiti scaricato ma non consultabile              | `TransitsPage` (nuova), `Sidebar`, `App`, `RestApiGateway` |
| A11 | Treno che resta "in stazione" dopo essere ripartito         | `useRealtimeUpdates`                                  |
| A12 | Tratta usata come posizione di un treno → 500               | `RestApiGateway.deleteTrattaElemento`                 |
| A13 | Ritardo dello storico preso dalla cache di adesso           | `Transito`, `StoricoTransito`, `IngestionService`, `RestApiGateway` |
| A14 | Pulsante "Invia Operatori" quasi mai visibile               | `IngestionService`, `StationGateway`, `HeartbeatGenerator`, `AlertsPage` |
| A15 | Allarmi inventati dal browser                               | `types`, `railwayStore`, `AlertsPage`                 |
| A16 | Ultimo heartbeat "adesso" per una stazione mai vista        | `apiClient`, `types`, `StationsPage`, `TrafficMapPage` |
| A17 | Guasto annunciato prima del commit                          | `FaultMonitor.creaGuastoAutomatico`                   |
| A18 | Ogni messaggio WebSocket ridisegna tutta l'applicazione     | `useRealtimeUpdates`, `App`, `Sidebar`                |
| A19 | Test che scrivono sul database di sviluppo                  | `pom.xml`, `src/test/resources/application.properties` |
| A20 | Itinerario che ripassa sulla stessa tratta → 500            | `RestApiGateway.verificaTratteEsistenti`              |

---

## A01 — La chiusura di un guasto arrivava come allarme nuovo

Il problema era che l'evento `ALERT` non diceva se il guasto fosse aperto o chiuso, e il browser
metteva sempre una riga nuova in cima all'elenco.

Ho lavorato sui tre punti della catena:

1. `IngestionService.broadcastAlert` adesso mette nell'evento anche `risolto` e
   `timestampRisoluzione`. È lo stesso metodo che serve sia per annunciare un guasto nuovo sia per
   dire che uno vecchio è rientrato, quindi il campo che distingue i due casi ci vuole per forza.
2. Il gestore dell'evento `ALERT` in `useRealtimeUpdates.ts` non scrive più `acknowledged: false`
   fisso, ma `acknowledged = data.risolto`, e riporta anche `resolvedAt`.
3. `railwayStore.addAlert` è diventata una upsert: se in elenco c'è già un allarme con quell'id lo
   **aggiorna** invece di aggiungerne un altro. Questo copre anche il percorso della deduplica in
   `IngestionService.onAlert`, dove lo stesso guasto viene riannunciato a ogni treno che entra nella
   stazione guasta.

Come effetto collaterale sparisce anche l'errore di rendering di React: non ci sono più due
elementi con la stessa `key`.

## A02 — Il cambio di stato della stazione adesso viaggia sulla WebSocket

Ho aggiunto un evento nuovo, `STATION_STATUS`, con `stationId` e `status`
(`IngestionService.broadcastStatoStazione`). Viene mandato **ogni volta che la Centrale scrive
`stazione.stato`**:

- `FaultMonitor.controllaHeartbeat`, quando la stazione passa OFFLINE;
- `IngestionService.marcaSorgenteGuasta`, quando arriva un guasto CRITICAL da terra;
- `RestApiGateway.risolviAllarme`, quando la stazione torna ONLINE;
- `RestApiGateway.dispacciaManutenzione`, sia sul passaggio a MANUTENZIONE sia sul rientro ONLINE;
- `RestApiGateway.updateStazione`, quando lo stato lo cambia a mano l'amministratore.

**Perché un evento nuovo e non un `HEARTBEAT` finto.** Il gestore dell'`HEARTBEAT` scrive anche
`lastHeartbeat`, e la stazione che il watchdog dichiara OFFLINE è per definizione quella che non
manda più battiti: spacciare l'istante del broadcast per un battito sarebbe stato scrivere una cosa
falsa, e avrebbe rimesso in piedi il problema di A16. Il gestore di `STATION_STATUS` tocca solo lo
stato.

## A03 — Lo snapshot serve a qualcosa e i KPI li calcola una funzione sola

Due cose separate, sistemate tutte e due:

**I numeri.** `DashboardPage` non si ricalcola più niente: legge `kpi` dallo store, che viene
riempito da `GET /api/dashboard` all'apertura (`initialize()`) e poi dallo `SNAPSHOT` ogni dieci
secondi. I KPI li produce sempre `TrafficLogicEngine.kpiDashboard()`, cioè la funzione unica di cui
parla il diagramma SQ18. Le quattro discordanze della tabella di `bug_trovati.md` non ci sono più
perché non esiste più il secondo calcolo. Finché la prima risposta non arriva la dashboard mostra
dei trattini invece di numeri inventati in locale.

**La fotografia.** Lo `SNAPSHOT` adesso porta anche `treni` e `stazioni`, non solo i KPI: sono gli
stessi oggetti in cache che serializzano `GET /api/treni` e `GET /api/stazioni`, quindi il browser
li rilegge con lo stesso mappatore. Per farlo ho estratto da `apiClient` due funzioni esportate,
`mapApiTrain` e `mapApiStation`, usate sia dalle GET sia dal gestore dello snapshot: un mappatore
solo per tutte e due le strade. Adesso lo snapshot fa davvero quello che RF02.6.3 gli chiede, cioè
riallineare lo stato, ed è anche la rete di sicurezza per gli eventi persi (alla riconnessione la
WebSocket non recupera niente, ed è un limite dichiarato).

## A04 — Il convoglio senza itinerario si ferma davvero

In `TrainJourneyEngine` ho aggiunto `fermaPerMancanzaItinerario()`, chiamata sia nel ramo della
ricarica sia quando l'itinerario è vuoto (prima da lì si usciva con un `return` senza toccare lo
stato). Mette `stato = "FERMO"`, `velocita = 0`, `faseViaggio = "SENZA_ITINERARIO"` e azzera la
prossima stazione.

Il valore `SENZA_ITINERARIO` è una fase nuova, documentata in `TrainDB.faseViaggio` e visibile da
`GET /treno/viaggio`: dice a chi guarda che il convoglio è fermo perché non ha un percorso, non
perché è arrivato o si è rotto.

Gli stati decisi da fuori non vengono toccati: un treno `SOPPRESSO` resta soppresso e uno in
`EMERGENZA` resta in avaria, che sono informazioni più importanti di questa.

Con questa correzione `TrainElab.generaTelemetria` smette da sola di inventare la velocità (il ramo
casuale è legato a `"IN_VIAGGIO".equals(stato)`), e il watchdog può accorgersi del convoglio fermo,
perché adesso la velocità che dichiara è zero.

## A05 — Eliminare un itinerario avvisa i convogli

`RestApiGateway.deleteTratta` raccoglie gli id dei treni mentre li sgancia e, dopo aver cancellato
l'itinerario, pubblica `ITINERARIO_AGGIORNATO` per ciascuno — la stessa cosa che la `PUT` già
faceva. Ricevuto l'evento il twin butta via l'itinerario che ha in memoria e ne chiede uno nuovo;
non trovandolo, grazie ad A04 resta fermo e visibile invece di continuare a percorrere un percorso
che non esiste più.

## A06 — Modificare un itinerario non sgancia più i treni

Qui ho applicato tutte e due le strade indicate nell'analisi, perché coprono cose diverse:

- **Lato Centrale** (la parte che conta): in `updateTratta` l'assenza del campo `treniIds` adesso
  vuol dire "le assegnazioni non le sto toccando", non "sgancia tutti". `assegnaTreni` sgancia solo
  se l'elenco è stato davvero mandato. Un elenco presente ma vuoto continua a sganciare tutti,
  perché quella è un'intenzione esplicita.
- **Lato browser**: `RouteEditorModal` non manda più `trainIds` (quel form non gestisce le
  assegnazioni, mostra solo il conteggio) e `apiClient.updateRoute` mette `treniIds` nel corpo solo
  se il chiamante l'ha impostato.
- In più `railwayStore` tiene adesso allineato `routes[].trainIds` quando si crea, si modifica o si
  elimina un treno (funzione `routesConAssegnazione`): prima quell'array veniva scaricato una volta
  sola all'avvio e restava fermo lì, quindi mostrava conteggi vecchi nella pagina Itinerari.

Lo scenario dell'analisi (assegno `TRN007` dal form Treni, poi sposto una stazione nell'editor) è
diventato il test `modificareLeStazioniNonSganciaITreniAssegnati`.

## A07 — L'anomalia "convoglio fermo fuori stazione" riscritta

`FaultMonitor.controllaTreniFermi` adesso guarda **dove** si trova il treno, non lo stato che
dichiara: salta solo i convogli soppressi, e per tutti gli altri considera anomalo l'essere fermi
fuori da una stazione, qualunque ne sia la causa (è quello che dice lo scenario SV04).

Contro i falsi positivi ci sono due precauzioni, le stesse che erano già state prese per le
stazioni:

- **Chi non ha mai trasmesso viene saltato.** Per poterlo sapere, `TrafficLogicEngine.onStart` non
  scrive più `ultimoAggiornamento = Instant.now()` sui treni caricati dal database: il campo resta
  nullo esattamente come `ultimoHeartbeat` per le stazioni. Per lo stesso motivo `createTreno` non
  lo imposta più su un convoglio appena creato. Era proprio quella riga a far sembrare "appena
  visti" dei treni il cui processo non è nemmeno acceso, ed è per questo che dieci secondi dopo ogni
  riavvio della Centrale comparivano guasti falsi (con `populate_db.sql`, TRN001 e TRN002).
- **La condizione deve durare `T_fer`.** Il FaultMonitor tiene una mappa `fermiDa` (id treno →
  da quando è fermo) e apre il guasto solo se sono passati almeno 90 secondi. Prima `velocita == 0`
  faceva scattare l'allarme al primo giro utile, senza aspettare niente. La mappa viene ripulita sia
  quando il treno riparte sia quando il convoglio viene eliminato.

## A08 — Il guasto `treno_fermo` si richiude da solo

Ho fatto per i treni quello che c'era già per le stazioni. I guasti aperti dal watchdog hanno adesso
un prefisso riconoscibile nel messaggio (`IngestionService.MSG_TRENO_FERMO`, gemello di
`MSG_HEARTBEAT_PERSO`), e `onTelemetry` chiama `chiudiGuastoTrenoFermoSeRiparte`: appena il
convoglio torna a muoversi (velocità diversa da zero o ingresso in stazione) il guasto viene chiuso
su database, in cache e nello storico, e la chiusura viene annunciata su MQTT e WebSocket.

I guasti dichiarati dal treno (avaria di bordo) non vengono toccati: quelli li chiude un operatore,
esattamente come per le stazioni.

Per non fare una query ogni cinque secondi per ogni convoglio, il primo controllo è sulla cache in
RAM: se lì non c'è un `treno_fermo` aperto con quel prefisso, non si tocca il database.

## A09 — I transiti tengono l'ora vera del passaggio

`onTransit` legge adesso il campo `timestamp` del payload (con `parseTimestamp`, che era già
scritto e usato per gli alert) e lo passa a `registraTransito`, che lo usa per `tempoEntrata` e
`tempoUscita` invece di `Instant.now()`. Così i transiti che una stazione riconsegna dopo
un'interruzione (scenario SV02) finiscono nello storico con l'ora in cui il treno è passato davvero,
e non tutti schiacciati sull'istante del rientro.

Lato browser, il gestore degli eventi `TRANSIT`, `ALERT` e `TELEMETRY` non scrive più
`timestamp: Date.now()` ma legge il timestamp che viaggia nell'evento (funzione `istanteEvento`,
che ripiega sull'ora di arrivo solo se il campo manca o è illeggibile).

## A10 — La pagina Transiti

Ho aggiunto `src/pages/TransitsPage.tsx`, la rotta `/transits` in `App.tsx` e la voce "Transiti"
nella sidebar. La pagina elenca i passaggi con convoglio, stazione, **tratta**, verso, istante e
ritardo, con ricerca per convoglio/stazione e filtro per verso.

La tratta mancava anche nei dati, non solo a schermo: l'ho aggiunta al DTO di `GET /api/transiti`
(`trattaId`) e all'evento WebSocket `TRANSIT` — la stazione non sa su quale tratta si trovi il
convoglio, lo sa solo la Centrale, che lo ricava dalla posizione corrente del treno. Il tipo
`Transit` del frontend ha il campo corrispondente (`trackSegmentId`) e la pagina lo mostra per
esteso ("Milano Centrale → Bologna Centrale") usando le tratte fisiche già in memoria.

## A11 — Il treno non resta più "in stazione" dopo la partenza

In `useRealtimeUpdates` il campo `currentStationId` viene adesso scritto sempre, anche quando la
telemetria manda stazione vuota (e quindi torna a `null`). `previousStationId` invece continua ad
aggiornarsi solo quando il valore è pieno, perché alla mappa serve sapere da dove il convoglio è
partito per disegnarlo sull'arco giusto: quel comportamento era voluto e l'ho lasciato.

Si rimettono a posto da soli i quattro punti che ci cascavano: i "Treni Attesi" della pagina
Stazioni, `treniInStazione` e il riquadro "Stazione attuale" della mappa, e la colonna "Posizione
Attuale" della pagina Treni.

## A12 — Tratta usata come posizione di un convoglio: 409, non 500

`deleteTrattaElemento` fa adesso anche il conteggio su `Treno.posizioneAttualeTratta`, sulla stessa
falsariga di quelli già scritti per itinerari e transiti, e risponde 409 con la spiegazione al posto
del 500 con l'eccezione. È il test `trattaUsataComePosizioneDiUnTrenoNonSiElimina`, che verifica
anche che liberata la tratta l'eliminazione passi: il rifiuto era sul dato, non sul comando.

## A13 — Il ritardo dello storico è quello del momento del passaggio

Ho aggiunto la colonna `ritardoMinuti` a `Transiti` e a `Storico_Transiti` (entità `Transito` e
`StoricoTransito`, più `schema.sql` che è il DDL di riferimento allegato alla relazione). Il valore
viene congelato da `onTransit` al momento della registrazione, leggendolo dalla cache del convoglio
— che in quell'istante contiene il ritardo mandato dal treno nel messaggio di passaggio — e
`getTransiti` legge quello invece di andarlo a ripescare dalla RAM.

Sul database di sviluppo le due colonne sono state create in automatico
(`quarkus.hibernate-orm.database.generation=update`): l'ho verificato, non serve nessuno script di
migrazione. Le righe già presenti restano con il campo nullo e il DTO le espone come ritardo 0.

## A14 — Il pulsante "Invia Operatori" si vede quando serve

Sistemato da tutti e due i lati, come diceva l'analisi:

- **L'euristica sulla parola "sensore" non c'è più.** `tipoGuastoPerFrontend` decide adesso in base
  al campo `tipoGuasto` del payload — cioè alla classificazione dichiarata da chi segnala il guasto
  — e in mancanza di quello in base al tipo di sorgente, che è un dato strutturato e non una parola
  in una frase. La stazione lo dichiara nel caso in cui è davvero pertinente, cioè il keepalive di
  un sensore scaduto (`HeartbeatGenerator.controllaSensori` →
  `StationGateway.inviaGuasto(..., "sensore_offline")`); un guasto generico dell'infrastruttura di
  terra resta `stazione_guasta`. Come conseguenza, un'avaria di bordo non viene più classificata
  come guasto di sensore solo perché qualcuno ha scritto "sensore" nella descrizione.
- **Il filtro nel frontend guarda `alert.stationId`**, non il tipo: se l'allarme riguarda una
  stazione il comando serve, che sia un sensore, un heartbeat perso o un guasto dichiarato. Restano
  esclusi solo gli allarmi già presi in carico e le stazioni dove la squadra è già stata mandata.

Il guasto di fail-stop aperto dal watchdog continua a essere di tipo `sensore_offline`: è il suo
"secchiello" di deduplica, e cambiarlo lo farebbe confondere con i guasti dichiarati dalla stazione,
che quel meccanismo deve poter distinguere. Con il filtro nuovo il pulsante compare lo stesso.

## A15 — Le notifiche non si travestono più da allarmi

`suppressTrain` e `dispatchOperators` non aggiungono più righe finte all'elenco degli allarmi. Ho
introdotto il tipo `UiNotification` e una lista `notifications` separata nello store, con
`pushNotification`/`dismissNotification`; la pagina Allarmi le mostra in un riquadro a parte
("Notifiche operative") che dice chiaramente che sono esiti di comandi di questa postazione e non
guasti della rete. Dal tipo `AlertType` sono spariti `treno_soppresso` e `operatori_inviati`, che
sul server non esistono.

Di conseguenza non gonfiano più il contatore degli allarmi attivi e non c'è più nessuna
`POST /api/allarmi/al-supp-.../risolvi` che risponde 404. Già che c'ero ho corretto anche il
comportamento di `acknowledgeAlert`, che marcava l'allarme come risolto anche quando il server
rifiutava (l'errore finiva solo in console): adesso in caso di errore lo stato locale non cambia e
l'operatore vede una notifica che glielo dice.

## A16 — Nessun heartbeat inventato

`Station.lastHeartbeat` è diventato `number | null`, `apiClient` non sostituisce più il valore
mancante con `Date.now()` e le due schermate che lo mostrano (pagina Stazioni e scheda della mappa)
scrivono un trattino quando la stazione non ha mai battuto. Anche il form di creazione di una
stazione parte adesso da `null` invece che dall'ora corrente.

## A17 — Prima il commit, poi l'annuncio

`FaultMonitor.creaGuastoAutomatico` scrive guasto, storico ed eventuale riga di audit dentro un
`QuarkusTransaction.requiringNew()` e manda le notifiche (WebSocket e MQTT) **dopo** che la
transazione è stata chiusa. Se la scrittura fallisce, il metodo logga e torna `null` senza aver
annunciato niente, e al giro successivo il watchdog riprova. Ho tolto la `@Transactional` dai due
job schedulati, che ora non aprono più una transazione lunga quanto tutto il giro di controllo: è
lo stesso spostamento già fatto a suo tempo in `IngestionService`.

## A18 — Niente più re-render dell'intera applicazione

`useRealtimeUpdates` prende ogni azione con un selettore (`useRailwayStore(s => s.updateTrain)`)
invece di destrutturare lo store intero. Le azioni di Zustand sono riferimenti stabili, quindi
l'hook non si risottoscrive e soprattutto non fa più ridisegnare `RailwayApp` — che contiene tutte
le rotte, mappa compresa — a ogni frame di telemetria, a ogni battito e a ogni transito. Stessa cosa
per `initialize` in `App.tsx` e per la lista degli allarmi nella `Sidebar`.

## A19 — I test hanno il loro database

`src/test/resources/application.properties` contiene adesso un datasource dedicato: H2 in memoria,
schema generato da Hibernate a ogni esecuzione (`drop-and-create`) e buttato via alla fine. In
`pom.xml` c'è la dipendenza `quarkus-jdbc-h2` in scope `test`.

Ne guadagnano due cose: i test non toccano più il database di lavoro (prima creavano davvero
`STAZ_TEST_xxxx`, `IT_A_xxxx` e compagnia dentro `railway`, e ce li lasciavano se un test in mezzo
falliva prima della pulizia), e `./mvnw test` gira anche senza Postgres acceso — che era il motivo
per cui i test erano stati scritti senza Keycloak. Nel log di avvio dei test si legge "Caricate 0
stazioni", che è la prova che il database è quello nuovo e vuoto.

Due dettagli di configurazione che servivano: `globally-quoted-identifiers=true`, perché lo schema
usa `timestamp` come nome di colonna (in `eventi_stazioni`) e in H2 2.x è una parola riservata; e
`sql-load-script=no-file`, per non far caricare `import.sql` e lasciare che ogni test si crei quello
che gli serve.

## A20 — L'itinerario che ripassa sulla stessa tratta viene rifiutato con un 400

Delle due strade possibili ho scelto il rifiuto parlante: `verificaTratteEsistenti` tiene traccia
delle coppie già viste e, se una si ripete, risponde 400 spiegando quale collegamento è duplicato.

Ho preferito questa alla modifica della chiave primaria di `Itinerario_Tratta` perché aggiungere
`ordine` alla chiave vuol dire cambiare la PK di una tabella esistente, cosa che
`hibernate-orm.database.generation=update` non fa: servirebbe uno script di migrazione da lanciare a
mano su un database che ha già dei dati, per rendere rappresentabile un caso che la rete di prova
non usa e che l'editor degli itinerari non permette nemmeno di comporre (non lascia ripetere una
stazione). Il rifiuto sta comunque **prima** di qualunque scrittura, come le altre verifiche, quindi
non lascia niente a metà.

---

## Come ho verificato

| Cosa                              | Comando                              | Esito |
|-----------------------------------|--------------------------------------|-------|
| Centrale: compilazione e test     | `cd ServeCentraleOperativa && ./mvnw test` | 30 test, tutti verdi |
| Treni: compilazione               | `cd Treni && ./mvnw compile`         | ok |
| Stazioni: compilazione            | `cd Stazioni && ./mvnw compile`      | ok |
| Web app: build di produzione      | `npm run build`                      | ok |
| Web app: test                     | `npx vitest run`                     | 6 test, tutti verdi |
| Colonne nuove sul Postgres reale  | avvio della Centrale + `information_schema` | `ritardominuti` creata in `transiti` e `storico_transiti` |

I test della Centrale erano 23, adesso sono 30: i sette nuovi stanno in
`src/test/java/it/uni/reti2/gateway/RegressioniBugTest.java` e coprono A06 (nei due versi: la PUT
senza `treniIds` non sgancia, quella con l'elenco vuoto sì), A20, A12 e A03 (il payload dello
snapshot si costruisce davvero e contiene i campi che il frontend si aspetta — sono entità JPA con
relazioni, ed è il punto in cui una serializzazione può rompersi). Gli altri bug non sono
verificabili da REST: dipendono da messaggi MQTT, dalla WebSocket o dal comportamento del browser.

## Cose da sapere

- **Bisogna riavviare i processi.** La Centrale, le stazioni e i treni che stanno girando adesso
  hanno ancora il codice vecchio; le correzioni si vedono solo dopo aver rilanciato i tre moduli.
- **Il database non richiede migrazioni a mano**: le due colonne di A13 le crea Hibernate all'avvio,
  già verificato sul `railway` di sviluppo.
- **Lo snapshot ogni dieci secondi sostituisce gli array `trains` e `stations` dello store.** È
  voluto (è la "fotografia" di RF02.6.3 e rimette a posto gli eventi persi), ma vuol dire che
  l'ultima parola ce l'ha sempre la cache della Centrale: un campo che esiste solo nel browser
  verrebbe sovrascritto.
- **Resta fuori una cosa che ho notato lavorando su A17**: anche `risolviAllarme` e
  `dispacciaManutenzione` pubblicano su MQTT (e adesso sulla WebSocket) da dentro un metodo
  `@Transactional`, quindi prima del commit. È lo stesso schema di A17 ma su endpoint REST, dove
  sistemarlo vuol dire riscrivere la gestione delle transazioni di quei due metodi: non l'ho fatto
  perché andava oltre il bug segnalato, però è il prossimo posto dove guardare.
- I limiti già dichiarati nella documentazione restano tali e quali: WebSocket non autenticata,
  nessun recupero dei messaggi persi alla riconnessione, stato `MANUTENZIONE` che non sopravvive,
  buffer della stazione in RAM, transiti non idempotenti.
