# Eventi domino — schema generale — 17/08/2026

Questo file non è un elenco di bug e non è un requisito: è la generalizzazione di uno schema che nel
sistema c'era già, ma una volta sola e a metà — il guasto della stazione che ferma il convoglio
(RF02.1.1.2). Lo schema lo si riconosce quando lo si guarda dall'alto: un nodo di campo dichiara un
fatto suo, e da quel fatto **altri nodi cambiano stato senza che nessuno glielo ordini**. Il fatto di
partenza e la reazione a catena sono due cose diverse, e il sistema ne registrava bene solo la prima.

L'idea era scrivere una volta come deve funzionare in generale, così che i casi che allora mancavano
(N01 e N02: il treno guasto che contagia la stazione o la tratta) non fossero tre soluzioni
artigianali diverse ma tre istanze della stessa regola. **L'infrastruttura descritta qui è
implementata** (paragrafi 6 e 7), il paragrafo 9 è la ricetta per usarla e **adesso lo sono anche i
tre casi particolari**: N01 (RF02.1.2.2.1), N02 (RF02.1.2.2.2) e N04 (RF01.4.1). Il paragrafo 9.4
resta scritto come esempio guidato, perché la ricetta serve ancora per i casi che verranno; il
paragrafo 8 dice riga per riga a che punto sono.

Gli altri requisiti incompleti **non** ricadono qui, e vale la pena dire perché, visto che la
domanda si ripresenta: a RF01.4.3 manca la verifica di una precondizione (è una validazione, non una
conseguenza), a RF02.5.2 manca il calcolo del punto di ripresa (la catena c'è già e funziona), e
RF02.1.1.2.3 è dichiarato al paragrafo 10 come un caso che questo schema non risolve.

Nota sui nomi: qui *edge* sono i processi di campo (le stazioni e i convogli, moduli `Stazioni/` e
`Treni/`) e *Centrale* è `ServeCentraleOperativa/`, cioè il nodo che riceve, persiste e storicizza.
Il canale è sempre e solo MQTT: `railway/alerts` è condiviso da tutti, gli altri topic sono per
sorgente.

---

## 1. Il vocabolario

| termine | che cos'è | esempio di oggi |
|---|---|---|
| **evento primario** | un fatto che il nodo osserva **su di sé**: nessun altro glielo ha detto | la stazione perde un sensore e si dichiara `GUASTA` (`HeartbeatGenerator.java:156`) |
| **reazione locale** | il cambio di stato che un altro nodo decide **da solo**, dopo aver letto l'evento primario | il convoglio entra in `BLOCCATO_GUASTO_STAZIONE` (`TrainJourneyEngine.java:513`) |
| **evento derivato** | il messaggio con cui il nodo che ha reagito **dichiara la propria reazione e la sua causa** | `tipoEvento: REAZIONE` sullo stesso canale (`PubblicatoreReazioni`) |
| **catena (domino)** | primario → reazioni → derivati → eventuali altre reazioni | stazione guasta → treno fermo → ritardo → coincidenza persa |

La differenza fra evento primario ed evento derivato non è nel contenuto ma **nella causa**: il
primario nasce da un sensore, il derivato nasce da un altro evento. Ed è esattamente per questo che
la Centrale li deve trattare in modo diverso: un primario apre un guasto, un derivato **non deve
aprirne uno nuovo**, altrimenti dieci treni fermi diventano dieci guasti da risolvere a mano per una
sola avaria vera.

## 2. Lo schema in generale

```
    A (edge)                  Centrale                    B, C, … (edge interessati)
       |                          |                                |
  (1)  |---- primario ----------->|<------ stesso topic -----------|
       |     railway/alerts       |                                |
       |                     (2) gestione classica:                |
       |                       - riga in Guasti_*                  |
       |                       - riga in Storico_Guasti            |
       |                       - cache + WebSocket al frontend     |
       |                          |                                |
       |                          |                          (3) "mi riguarda?"
       |                          |                              sì -> cambio stato locale
       |                          |                                |
       |                          |<------ (4) derivato -----------|
       |                          |     causa = id del primario    |
       |                     (5) gestione derivata:                |
       |                       - NIENTE nuovo guasto               |
       |                       - stato + Storico_Stato_*           |
       |                         con la causa dentro               |
       |                       - WebSocket al frontend             |
```

I cinque passi, uno per uno.

**(1) A pubblica il primario.** Il nodo dichiara solo quello che sa: chi è, che cosa gli è successo,
quanto è grave. Non dice a nessuno che cosa deve fare, e questa è la proprietà che tiene il sistema
disaccoppiato: la stazione che si guasta non sa nemmeno quanti treni ci sono in rete.

**(2) La Centrale lo gestisce in modo classico.** È il percorso che c'è già in
`IngestionService.onAlert` (`IngestionService.java:547`): apre il `Guasto`, ne scrive la fotografia
in `Storico_Guasti`, aggiorna la cache e manda l'evento al frontend.

**(3) B e C reagiscono da soli.** Sono sottoscritti allo stesso topic e applicano un filtro **locale**
di pertinenza: "questa stazione è la mia prossima o quella in cui sono fermo?"
(`TrainGateway.java:103`). Chi non è interessato ignora il messaggio; chi lo è cambia stato senza
chiedere niente alla Centrale. Il punto è questo: la decisione la prende il nodo, non il server —
altrimenti a rete caduta nessuno si fermerebbe.

**(4) B e C pubblicano il derivato.** Questo è il pezzo che mancava. Il nodo che ha reagito dichiara
il proprio nuovo stato **e la causa**: `BLOCCATO_GUASTO_STAZIONE perché il guasto G-123 della
stazione MI`. Non è un guasto e non è telemetria: è un terzo tipo di messaggio.

Un derivato può essere letto come primario da un altro nodo, e non è un caso limite: è N01. Quando la
stazione diventa impercorribile per colpa di un convoglio guasto, ripubblica il fatto come `GUASTO`
ereditando la catena — per i convogli in avvicinamento quello è un fatto primario come un altro, si
fermano senza chiedersi chi l'abbia causato, mentre la catena dice alla Centrale che è sempre la
stessa avaria. A impedire che il giro si ripeta all'infinito è la regola del paragrafo 4.2, non il
divieto di un secondo passo.

**(5) La Centrale gestisce il derivato in modo derivato.** Non apre niente in `Guasti_*`. Aggiorna lo
stato del nodo in cache e su DB e scrive la riga di storico **con dentro il riferimento alla causa**,
così a posteriori dal database si legge non solo *che* il treno si è fermato ma *perché*.

## 3. Il formato del messaggio derivato

Sul canale condiviso il campo discriminante è già `tipoEvento` (`TrainGateway.java:65`), quindi basta
aggiungere un valore nuovo e non si tocca niente di quello che funziona:

```json
{
  "tipoEvento":   "REAZIONE",
  "sorgenteTipo": "TRENO",
  "sorgenteId":   "Mario",
  "nuovoStato":   "BLOCCATO_GUASTO_STAZIONE",
  "statoPrecedente": "IN_VIAGGIO",
  "causaTipo":    "STAZIONE",
  "causaId":      "MI",
  "catenaId":     "MI-1755438667123",
  "attiva":       true,
  "motivo":       "Trattenuto: la stazione MI non e' percorribile",
  "timestamp":    "2026-08-17T14:31:07Z"
}
```

Le quattro proprietà che contano:

- **`causaTipo` + `causaId` + `catenaId`** sono ciò che distingue il derivato dal primario. Se manca
  la causa il messaggio è indistinguibile da un guasto e la Centrale non ha modo di sapere che non
  deve aprirne uno.
- **`catenaId` è l'identità del fatto, non del messaggio.** È l'identificativo del guasto primario da
  cui tutto discende e viene **ereditato** da ogni messaggio che ne consegue. Lo conia il nodo che
  dichiara il primario (`<idNodo>-<epoch>`) e resta lo stesso per tutto l'episodio, perché una
  stazione guasta manda un alert per ogni treno che entra: se ogni alert portasse una catena nuova,
  chi legge non riconoscerebbe che è sempre la stessa avaria.
- **`nuovoStato` è nel vocabolario del nodo, non della Centrale**: `BLOCCATO_GUASTO_STAZIONE` è una
  fase del digital twin. La traduzione verso i valori ammessi dal `CHECK` su `Treni.stato` la fa la
  Centrale (`VocabolarioEventi.normalizzaStatoTreno`), perché è lei che conosce il proprio schema.
- **`statoPrecedente`** viaggia nel messaggio perché la riga di storico deve raccontare un
  cambiamento, non una fotografia (è la stessa scelta di `salvaStatoTreno`,
  `IngestionService.java:231-247`).

Il campo **`attiva`** dice se il nodo *entra* nella catena (si blocca) o se ne *esce* (il guasto è
stato risolto). Serve perché la regola contro i doppioni vale solo per l'ingresso: se valesse anche
per l'uscita, il nodo resterebbe registrato per sempre su una catena ormai chiusa. La chiusura
simmetrica è quindi lo stesso messaggio con `attiva: false`, scatenato dal `RESOLVED` della causa.

## 4. Le regole che tengono in piedi lo schema

Non sono invenzioni: quattro su cinque sono già nel codice per il canale degli alert, e vanno
semplicemente riconosciute come parte del pattern.

1. **Anti-eco.** Il topic è condiviso e la Centrale è sottoscritta a sé stessa: senza un marcatore si
   riascolterebbe da sola. Esiste già `ORIGINE_CENTRALE` (`IngestionService.java:65`) con il
   controllo a `IngestionService.java:564`. Un derivato pubblicato dalla Centrale (per esempio la
   soppressione decisa dall'operatore) va marcato allo stesso modo.
2. **Terminazione.** La regola è una sola: **un nodo reagisce al massimo una volta per ogni catena**.
   I nodi sono un insieme finito, quindi una catena produce al massimo un messaggio per nodo e si
   esaurisce da sé: niente contatore di salti, niente soglia arbitraria da giustificare. La stessa
   regola taglia i cicli — il convoglio che si ferma per la stazione, la stazione che si guasterebbe
   di nuovo per il convoglio fermo — perché al secondo passaggio il nodo ha già reagito a quella
   catena. Lato Centrale la applica `RegistroCatene`, lato campo gli insiemi `cateneReagite` dei nodi.
   *(Prima qui c'era la regola più forte "un derivato non è mai causa di un altro derivato": tagliava
   via anche i casi che servono, a partire da N01, dove è proprio il secondo passo — la stazione resa
   impercorribile — a fermare gli altri convogli.)*
3. **Deduplica.** La stazione guasta manda un alert per **ogni** treno che entra
   (`StationGateway.java:145`): la deduplica sui guasti aperti esiste già
   (`IngestionService.java:576-580`). Per i derivati serve la stessa cosa in versione più semplice:
   se il nodo è già in quello stato per quella causa, il messaggio si ignora.
4. **Solo i cambiamenti.** Vale la regola di RF02.7 — si registrano i cambiamenti, non i
   campionamenti. Un derivato che non cambia niente non produce righe.
5. **L'ordine e la rete che cade.** Un derivato che parte mentre la rete è giù deve finire nel buffer
   locale e rientrare **in testa** alla coda come fanno gli altri eventi della stazione
   (RF02.1.1.1.2, `LocalBuffer`), altrimenti si legge lo sblocco prima del blocco.

## 5. Che cosa cambia rispetto a oggi

Oggi il sistema fa i passi (1), (2) e (3) e **si ferma lì**. La reazione del convoglio non viene
dichiarata a nessuno: resta dentro `trainDB.faseViaggio`, che il treno espone solo sulla propria API
locale (`TrainIngestion.java:77`) e che **non è nemmeno nel frame di telemetria**
(`TrainElab.java:68-80`: partono `stato`, posizione, velocità, ritardo, passeggeri, direzione — la
fase no). Alla Centrale arriva quindi un treno `FERMO` con velocità 0, indistinguibile da uno fermo
in sosta regolare.

| passo | prima | adesso |
|---|---|---|
| primario stazione → Centrale | c'è (`onAlert`) | uguale, in più eredita e propaga `catenaId` |
| reazione del convoglio | c'è (`bloccaPerGuastoStazione`) | uguale, e viene dichiarata |
| la Centrale sa **perché** il treno è fermo | no: vedeva solo `FERMO` dalla telemetria | sì, dal derivato |
| lo storico registra la causa | no: `Storico_Stato_Treni` aveva `stato`/`stato_precedente` e basta | sì: `causa_tipo`, `causa_id`, `catena_id` |
| il passaggio a `GUASTA` finisce nello storico | **no**: `marcaSorgenteGuasta` toccava solo la cache e il WebSocket, e al battito successivo `statoCambiato` era già falso, quindi la riga non la scriveva nessuno | sì, il ramo STAZIONE storicizza come già faceva quello TRENO |
| il frontend distingue "fermo" da "trattenuto" | no | i campi ci sono nell'evento WebSocket, l'interfaccia deve solo leggerli |

La penultima riga è quella che mi convince di più che lo schema serva davvero: il buco sullo storico
delle stazioni non nasceva da una dimenticanza, nasceva dal fatto che **un cambio di stato causato da
un evento entrava da una porta diversa** rispetto a un cambio di stato dichiarato dal nodo, e quella
porta non passava dalla storicizzazione.

## 6. Dov'è nel codice

Lo schema è implementato: quello che segue è la mappa di dove sta ogni pezzo. I **casi particolari**
del paragrafo 8 sono stati scritti dopo, e sono venuti come previsto — una manciata di righe nel nodo
giusto, non pezzi di architettura: la ricetta passo per passo è nel paragrafo 9.

**Centrale** — pacchetto nuovo `it.uni.reti2.eventi`:

| classe | che cosa fa |
|---|---|
| `VocabolarioEventi` | costanti del canale (`REAZIONE`, `catenaId`, `causaTipo`…) e le due traduzioni fra il vocabolario dei nodi e quello del database, che prima erano metodi privati di `IngestionService` |
| `CausaEvento` | il record `(tipo, id, catenaId)`: perché un nodo è cambiato |
| `EventoDerivato` | il messaggio derivato letto dal canale, con il suo parser; senza catena non è applicabile |
| `RegistroCatene` | chi sta reagendo a quale catena: `entra` (una volta sola), `esce`, `chiudi` |
| `GestoreReazioni` | l'**unica** porta per i cambiamenti causati da eventi altrui: cache + database + storico con la causa + WebSocket |

più `IngestionService`, che instrada le `REAZIONE` al gestore prima del filtro sui `GUASTO`, eredita
`catenaId` dagli alert in arrivo, lo rimette in quelli che pubblica e chiude la catena nel registro
quando pubblica il `RESOLVED`.

**Treni** — `PubblicatoreReazioni` (classe a sé per non creare un anello fra gateway e motore di
viaggio) pubblica la dichiarazione; `TrainJourneyEngine.bloccaPerGuastoStazione(stazione, catena)` e
`sbloccaDaGuastoStazione()` la emettono in ingresso e in uscita; `TrainDB.cateneReagite` applica la
regola di terminazione lato campo e `trainDB.stazioniGuaste` è diventata una mappa stazione → catena,
così anche il controllo di ripartenza sa dire per colpa di quale guasto il convoglio resta fermo.

**Stazioni** — `StationGateway.dichiaraNonPercorribile(causaTipo, causaId, catena, descrizione)` è il
punto di innesto per N01: cambia lo stato locale, lo dichiara come derivato e ripubblica il fatto come
`GUASTO` **ereditando la catena**, perché i convogli reagiscono agli stati delle stazioni, non alle
reazioni altrui. `tornaPercorribile()` fa il contrario. Tutto passa dal buffer locale, quindi vale
anche qui l'ordine garantito di RF02.1.1.1.2.

**Test** — `EventiDominoTest` fissa le due proprietà da cui dipende il resto: la causa finisce a
database, e lo stesso nodo non reagisce due volte alla stessa catena. Più tre casi aggiunti con N02 e
la chiusura: la reazione su una tratta, e le due direzioni della chiusura (risolvere la causa chiude
le conseguenze, risolvere una conseguenza non tocca la causa). Il comando di fine intervento sta in
`MemoriaStoricaTest`, dove c'era già la riga dell'intervento.

### 6.1 Quello che i tre casi hanno aggiunto

Il resto della mappa vale ancora; qui ci sono solo i pezzi nati con N01, N02 e N04.

| dove | che cosa |
|---|---|
| `DBLocale.cateneImpercorribili` | l'insieme delle catene che tengono ferma la stazione: torna `ONLINE` solo quando si svuota |
| `StationGateway.riceviAlert`, `case "GUASTO"` | N01: il convoglio guasto è fra i miei treni presenti, quindi non sono più percorribile |
| `Tratta.stato` + `TrafficLogicEngine` | la percorribilità di un arco, che prima non esisteva: `@Transient`, come lo stato delle stazioni |
| `StoricoStatoTratta` / `Storico_Stato_Tratte` | dove finisce il cambiamento, con le tre colonne di causa |
| `GestoreReazioni.applicaSuTratta` | il ramo `TRATTA` dello switch, cioè il "tipo di nodo nuovo" del 9.3 |
| `TrainJourneyEngine.dichiaraTrattaNonPercorribile` | N02: sono guasto e non sono in stazione, quindi l'arco che occupo è chiuso |
| `TrainJourneyEngine.bloccaPerGuastoTratta` + `TrainDB.tratteGuaste` | l'altra metà: chi doveva imboccarlo si ferma, fase `BLOCCATO_GUASTO_TRATTA` |
| `PubblicatoreReazioni.pubblicaPerNodo` | serve perché qui il soggetto della reazione non è chi la pubblica |
| `GestoreReazioni.applicaDallaCentrale` + `IngestionService.pubblicaReazioneSuMqtt` | N04: la porta unica anche per i primari che nascono da un comando dell'operatore |
| `POST /api/stazioni/{id}/manutenzione/conclusa` | N04: la fine dell'intervento, che prima non era un fatto separato |

**Una nota su N02**, perché è l'unico dei tre che non segue il passo 1 alla lettera. La regola dice
che decide il nodo che cambia stato: qui il nodo che cambia stato è la tratta, e la tratta non è un
processo — non ha un gateway, non ascolta e non può accorgersi di niente. A dichiarare per lei è il
convoglio che ci si è guastato sopra. Non è uno strappo, è la stessa regola applicata dove porta:
fra i due, il nodo che **osserva** il fatto è il convoglio, ed è l'unico a sapere dove si trovava
quando si è rotto (la Centrale, al più, lo dedurrebbe dall'ultima posizione ricevuta). Il segno che
la scelta tiene è che il resto non cambia di una riga: la reazione ha `sorgenteTipo: TRATTA` e
`causaTipo: TRENO`, e la Centrale la applica come tutte le altre.

**Una nota su N04**, che è il caso in cui la Centrale reagisce al posto del nodo. `MANUTENZIONE` non
è nel vocabolario della stazione, che di sé dichiara solo `ONLINE` o `GUASTA` (RF02.3.4): non è una
dimenticanza, è che il nodo non può sapere di avere una squadra addosso. Quindi la reazione la
applica la Centrale — passando comunque da `GestoreReazioni`, così finisce a storico con dentro chi
l'ha decisa — e la pubblica sul canale marcata `ORIGINE_CENTRALE`, come dice la regola 4.1. Una
conseguenza c'era da prevederla: il battito della stazione non deve sovrascrivere quello stato,
altrimenti il primo heartbeat utile cancella la manutenzione e si torna al punto di partenza per
un'altra strada.

## 7. Il database

Tre colonne in fondo agli storici di stato e una sulle due tabelle dei guasti, con lo stesso criterio
già scritto in `schema.sql` (sezione degli storici): **niente chiavi esterne verso lo stato
corrente**, sono riferimenti logici.

```sql
ALTER TABLE Storico_Stato_Treni    ADD COLUMN causa_tipo VARCHAR(20),  -- STAZIONE / TRENO / TRATTA / OPERATORE
                                   ADD COLUMN causa_id   VARCHAR(50),  -- riferimento logico, NIENTE FK
                                   ADD COLUMN catena_id  VARCHAR(50);
ALTER TABLE Storico_Stato_Stazioni ADD COLUMN causa_tipo VARCHAR(20),
                                   ADD COLUMN causa_id   VARCHAR(50),
                                   ADD COLUMN catena_id  VARCHAR(50);
ALTER TABLE Guasti_Pervenuti_da_treni_o_Staz ADD COLUMN catena_id VARCHAR(50);
ALTER TABLE Storico_Guasti                   ADD COLUMN catena_id VARCHAR(50);
```

Sul Postgres di lavoro non serve lanciarle a mano: `quarkus.hibernate-orm.database.generation=update`
aggiunge le colonne al primo avvio dopo questa modifica, e sono tutte annullabili quindi le righe che
c'erano già restano valide. Lo `schema.sql` di riferimento è aggiornato. C'è anche
`ServeCentraleOperativa/src/main/resources/migrazione_eventi_domino.sql`, facoltativo: serve solo a
riempire la catena sulle righe dei guasti che esistono già (un guasto senza catena è primario, quindi
`catena_id = id_Guasto`), così le query non devono trattare a parte il caso della colonna vuota.

La catena sui **guasti** serve perché un guasto può essere la conseguenza di un altro (la stazione
resa impercorribile da un convoglio guasto è un guasto vero, che l'operatore deve risolvere, ma non è
un'avaria in più): con `catena_id` si sa che è lo stesso fatto. Per un guasto primario vale il proprio
identificativo, così la colonna non è mai vuota e le query non devono trattare due casi.

Con queste colonne una domanda che prima non aveva risposta ne ha una con una sola query: *quanto è
costato in rete il guasto G-123?*

```sql
SELECT id_convoglio, stato, stato_precedente, causa_tipo, causa_id, ts_storicizzazione
FROM   Storico_Stato_Treni
WHERE  catena_id = 'MI-1755438667123'
ORDER  BY ts_storicizzazione;
```

È anche il tipo di interrogazione che giustifica gli storici in una relazione, molto più del semplice
"tengo traccia".

## 8. Perché generalizzare conviene: gli altri casi che ci ricadono

| caso | primario (chi lo dichiara) | chi reagisce e come | oggi |
|---|---|---|---|
| stazione guasta | stazione | i convogli in arrivo si fermano | **completo**: reazione dichiarata, causa a storico |
| convoglio guasto in stazione (RF02.1.2.2.1) | convoglio | la stazione diventa non percorribile, e da lì gli altri convogli si fermano | **completo** (N01): un `case` in `StationGateway.riceviAlert`, esattamente il paragrafo 9.4 |
| convoglio guasto in tratta (RF02.1.2.2.2) | convoglio | la tratta diventa non percorribile | **completo** (N02): è servito prima dare uno stato alle tratte, come diceva il 9.3 |
| squadra di manutenzione inviata (RF01.4.1) | operatore, dalla Centrale | la stazione va in `MANUTENZIONE` | **completo** (N04): il ritorno a `ONLINE` è un comando a sé |
| corsa soppressa (RF01.4.3) | operatore, dalla Centrale | il convoglio si ferma e libera l'itinerario | c'è a metà (N06), ma quello che manca è la precondizione, non la catena |
| itinerario modificato (RF02.5.2) | operatore, dalla Centrale | il convoglio ricarica e prosegue | c'è, ma riparte dal capolinea (N03): manca il calcolo del punto di ripresa, non la catena |

Le ultime tre righe mostrano che lo schema copre anche gli eventi che partono **dalla Centrale**: lì
il primario non nasce da un sensore ma da un comando dell'operatore, e il resto della catena è
identico. È il motivo per cui parlo di *nodo* e non di *stazione* nei passi del paragrafo 2.

## 9. Come si aggiunge un nuovo evento domino

Questo è il capitolo operativo: la ricetta da seguire quando c'è una conseguenza nuova da modellare,
per esempio uno dei casi del paragrafo 8 che oggi mancano. L'infrastruttura c'è già, quindi quello che
resta da scrivere è **la decisione**: chi si accorge della condizione e che cosa diventa. Sono dieci o
quindici righe nel nodo giusto, non un pezzo di architettura.

### 9.1 Prima domanda: è davvero un evento domino?

Tre domande, in quest'ordine. Se anche una sola risposta non torna, non è una reazione: è un guasto
normale e va pubblicato come `GUASTO`.

1. **Il fatto lo osserva il nodo su di sé, o gliel'ha detto un altro evento?** Un sensore che tace lo
   osserva la stazione: primario. Una stazione che diventa inagibile perché un convoglio si è rotto sui
   suoi binari non ha osservato niente di suo: derivato.
2. **C'è un'avaria in più da riparare?** È la domanda che risolve i casi dubbi: se l'operatore, per
   sistemare tutto, deve fare **due interventi distinti** allora sono due guasti; se l'intervento è
   **uno solo** (riparare il convoglio) allora è una catena, e il secondo allarme sarebbe solo rumore
   nella lista da smaltire.
3. **Il nodo cambia stato?** Se non cambia niente non c'è nulla da dichiarare: la reazione è un
   cambiamento di stato, non una notifica.

| esempio | che cos'è | perché |
|---|---|---|
| sensore di binario silente | primario | la stazione lo rileva da sé, ed è un guasto da riparare |
| convoglio guasto sui binari di MI → MI impercorribile | **derivato** | l'intervento è uno: riparare il convoglio |
| convoglio che si ferma perché MI è impercorribile | **derivato** | il convoglio sta benissimo |
| stazione che perde l'heartbeat | primario | la Centrale lo deduce come fatto a sé (fail-stop) |
| convoglio soppresso dall'operatore | derivato, con causa OPERATORE | non è un'avaria, è una decisione |

### 9.2 La ricetta, cinque passi

**Passo 1 — decidere chi decide: il nodo che cambia stato, mai la Centrale.** È la scelta che tiene in
piedi RF02.1.1.2: la reazione deve avvenire anche mentre la rete è giù. La Centrale registra e
racconta, non comanda. In pratica: la condizione si riconosce nel listener del canale del nodo
(`StationGateway.riceviAlert`, `TrainGateway.riceviAlert`), dove il nodo ha sotto mano il proprio
stato locale (i treni sui binari, la propria rotta).

**Passo 2 — chiamare il metodo di reazione del nodo, non `inviaGuasto`.**

| nodo | metodo da chiamare |
|---|---|
| stazione | `StationGateway.dichiaraNonPercorribile(causaTipo, causaId, catenaId, descrizione)` |
| convoglio | `TrainJourneyEngine.bloccaPerGuastoStazione(stazioneId, catenaId)`, o direttamente `PubblicatoreReazioni.pubblica(...)` per una reazione di tipo nuovo |

**Passo 3 — ereditare la catena, mai coniarne una nuova.** L'identificativo sta nel campo `catenaId`
dell'alert che ha innescato la reazione (nei due listener è già estratto in una variabile locale). Una
catena nuova la si conia solo per un evento **primario**, e lo fanno già `inviaGuasto` di entrambi i
nodi. Se la reazione arriva con `catenaId` vuoto non va pubblicata: senza catena il messaggio verrebbe
riapplicato a ogni ripetizione del broker.

**Passo 4 — se il fatto deve far reagire altri nodi di campo, va ripubblicato come `GUASTO`.** I
convogli reagiscono agli **stati delle stazioni**, non alle reazioni altrui: per loro quello è un
primario come un altro. Ci pensa già `dichiaraNonPercorribile`, che pubblica la reazione *e* il guasto
ereditato. Se invece la conseguenza non riguarda nessun altro nodo (il convoglio che si ferma è
l'ultimo anello) basta la sola reazione.

**Passo 5 — chiudere il cerchio.** La reazione ha due versi: `attiva: true` quando il nodo entra nella
catena, `attiva: false` quando ne esce. L'uscita la scatena il `RESOLVED` della catena, che la Centrale
pubblica quando l'operatore risolve il guasto primario. Nei due nodi lo srotolamento per catena è già
scritto, quindi di norma **non c'è niente da aggiungere**: va solo verificato che il nodo esca davvero
(`tornaPercorribile`, `sbloccaDaGuastoStazione`).

**Quello che non serve scrivere.** Lato Centrale, niente: `IngestionService` instrada le `REAZIONE` e
`GestoreReazioni` aggiorna cache, database, storico con la causa e WebSocket. Nessuna riga in
`Guasti_Pervenuti_da_treni_o_Staz`, nessuna colonna nuova, nessun endpoint.

### 9.3 I due soli casi in cui la Centrale va toccata

1. **Un tipo di nodo nuovo.** Oggi `GestoreReazioni` conosce TRENO e STAZIONE. Per le tratte (è N02)
   servono un ramo in più nello `switch` di `applica` e una tabella di storico con le tre colonne di
   causa, perché lo stato di percorribilità delle tratte nello schema non esiste ancora.
2. **Un vocabolario di stato nuovo.** Se il nodo dichiara uno stato che il `CHECK` del database non
   conosce, la traduzione va aggiunta in `VocabolarioEventi.normalizzaStatoTreno`. Senza, lo stato
   diventa "fermo" e il *perché* resta comunque salvo nelle colonne di causa — quindi non è un errore,
   è una perdita di dettaglio.

### 9.4 Esempio guidato: N01, il convoglio guasto rende la stazione impercorribile

Enunciato (RF02.1.2.2.1): se il convoglio si guasta mentre è fermo in una stazione, quella stazione
risulta non percorribile dagli altri convogli.

**Chi decide.** La stazione, non la Centrale. Sa quali treni ha sui binari (`dbLocale.treniPresenti`,
riempita dai passaggi) ed è già sottoscritta a `railway/alerts`, quindi vede l'avaria del convoglio nel
momento in cui viene dichiarata. Il `todo` che sta in `IngestionService` sul marcare la stazione
diventa così superfluo: quella decisione non appartiene alla Centrale.

**Il codice.** Un `case` in più nello `switch` di `StationGateway.riceviAlert`, dove `catenaId` è già
estratto dal payload:

```java
case "GUASTO":
    // Un convoglio si guasta mentre e' fermo sui miei binari: la stazione non e' piu'
    // percorribile (RF02.1.2.2.1). La decisione e' mia e non della Centrale, cosi' vale
    // anche mentre la rete e' giu'.
    if ("TRENO".equals(json.path("sorgenteTipo").asText())
            && "CRITICAL".equalsIgnoreCase(json.path("severita").asText("CRITICAL"))
            && dbLocale.treniPresenti.containsKey(sorgenteId)) {
        dichiaraNonPercorribile("TRENO", sorgenteId, catenaId,
                String.format("Stazione %s non percorribile: convoglio %s guasto in stazione",
                        dbLocale.stazioneId, sorgenteId));
    }
    break;
```

**Che cosa succede, in ordine.**

1. Il convoglio T pubblica il proprio `GUASTO` con `catenaId = "T-1755438667123"` (lo conia lui,
   `TrainGateway.inviaGuasto`).
2. La Centrale apre il guasto di T con quella catena, marca T come `rotto` e ne storicizza il
   cambiamento con la causa.
3. MI riceve lo stesso alert, vede T fra i propri treni presenti e chiama
   `dichiaraNonPercorribile`: passa a `GUASTA`, si registra sulla catena (una volta sola), **dichiara**
   la reazione e **ripubblica** il fatto come `GUASTO` con la catena ereditata.
4. La Centrale applica la reazione di MI: stato in cache, riga in `Storico_Stato_Stazioni` con
   `causa_tipo = TRENO`, `causa_id = T`, `catena_id = T-1755…`, evento WebSocket con la causa. Il
   `GUASTO` di MI apre un secondo allarme — che è giusto, l'operatore deve vedere che MI è chiusa — ma
   con la **stessa** catena, quindi si sa che non è una seconda avaria.
5. I convogli U e V, diretti a MI, leggono il `GUASTO` di MI come un primario qualunque e si fermano,
   dichiarando a loro volta la propria reazione sulla stessa catena. È il secondo passo di domino, e
   funziona perché il fatto è stato ripubblicato nel formato che i convogli già ascoltano.
6. La catena si ferma qui: MI e i convogli hanno tutti reagito una volta, e un ulteriore anello della
   stessa catena non produce niente.
7. L'operatore risolve il guasto di T. La Centrale pubblica il `RESOLVED` con la catena e chiude la
   catena nel registro; MI torna `ONLINE` e lo dichiara, U e V ripartono e lo dichiarano. **Nessuna
   riga di codice in più**: lo srotolamento per catena è già scritto nei due nodi.

**Fatto.** Il `case` è quello qui sopra, parola per parola. Della domanda che restava aperta — se la
stazione debba tornare percorribile con guasti propri ancora aperti — la risposta è no, ed è scritta
in `DBLocale.cateneImpercorribili`: la stazione tiene l'insieme delle catene che la stanno tenendo
ferma e torna `ONLINE` solo quando si svuota. Con il solo `catenaAttiva` il ripristino di un guasto
qualunque la rimetteva in servizio anche con l'altro ancora aperto, e i convogli ci entravano dentro.

Un pezzo in più è saltato fuori provando il giro per intero: il `RESOLVED` pubblicato dall'endpoint
REST dell'operatore non portava il `catenaId` e non chiudeva la catena nel registro (lo faceva solo
quello dell'ingestione). Senza catena lo srotolamento non funziona proprio nel caso di N01, dove il
guasto che si chiude è del convoglio mentre chi aspetta è fermo per la stazione: adesso l'endpoint
delega a `IngestionService.pubblicaRisoluzioneSuMqtt`, che le due cose le fa già. E risolvendo la
causa si chiudono anche i guasti *derivati* della stessa catena aperti dopo di lei — non i più
vecchi, che sono la causa e non la conseguenza — altrimenti riparato il convoglio restava in elenco
un allarme sulla stazione che nessuno avrebbe più chiuso.

### 9.5 Checklist prima di considerarlo finito

- [ ] la condizione la riconosce **il nodo**, nel proprio listener, con dati locali;
- [ ] la reazione **non** apre un guasto (niente `inviaGuasto` per dichiarare una conseguenza);
- [ ] la catena è **ereditata**, non coniata;
- [ ] se altri nodi di campo devono reagire, il fatto è ripubblicato come `GUASTO` con la catena;
- [ ] l'uscita dalla catena esiste ed è `attiva: false`;
- [ ] la riga di storico ha `causa_tipo`, `causa_id` e `catena_id` valorizzate (si controlla con una
      query, non a occhio);
- [ ] rimettere lo stesso alert due volte non produce una seconda riga di storico;
- [ ] nessun altro punto del codice cambia quello stato di nascosto: la porta è una sola.

### 9.6 Come si prova

**Con un test**, nello stile di `EventiDominoTest`: si costruisce il payload della reazione, si chiama
`gestoreReazioni.applica(EventoDerivato.daJson(...))` e si contano le righe di
`Storico_Stato_Treni`/`Storico_Stato_Stazioni`. Le due asserzioni che contano sono sempre le stesse: la
causa c'è, e il doppione non lascia una seconda riga.

**A mano sul sistema acceso**, pubblicando l'alert sul broker e guardando cosa fa la rete. Attenzione
che è una prova vera: apre un guasto sui dati di lavoro e ferma davvero i convogli interessati, quindi
va chiusa dall'interfaccia come qualunque altro allarme.



```sh
mosquitto_pub -h localhost -t railway/alerts -m '{
  "tipoEvento":"GUASTO","sorgenteTipo":"TRENO","sorgenteId":"Mario",
  "severita":"CRITICAL","messaggio":"prova avaria di bordo",
  "catenaId":"Mario-999","timestamp":"2026-08-17T15:00:00Z" }'
```

e poi, sul database:

```sql
SELECT id_convoglio AS nodo, stato, stato_precedente, causa_tipo, causa_id, ts_storicizzazione
  FROM Storico_Stato_Treni    WHERE catena_id = 'Mario-999'
UNION ALL
SELECT id_stazione,           stato, stato_precedente, causa_tipo, causa_id, ts_storicizzazione
  FROM Storico_Stato_Stazioni WHERE catena_id = 'Mario-999'
ORDER BY ts_storicizzazione;
```

Il risultato è la catena per intero, un nodo per riga e in ordine di tempo: se qualcosa non ha
reagito, o ha reagito due volte, si vede qui.

## 10. I limiti che dichiarerei

Onestamente, prima che me li chiedano.

- **La tempesta.** Un guasto in una stazione di transito con dieci convogli in avvicinamento produce
  dieci derivati in pochi secondi. Non è un problema di correttezza (la deduplica regge) ma di
  rumore: nel frontend andrebbero raggruppati per causa, non elencati uno per uno.
- **L'ordine fra nodi diversi.** MQTT garantisce l'ordine per topic e per client, non fra client
  diversi: il derivato di B e quello di C possono arrivare invertiti. Per lo storico non cambia
  niente (ognuno racconta il proprio cambiamento e ha il suo timestamp), ma non ci si può costruire
  sopra un ragionamento del tipo "B si è fermato prima di C".
- **La consegna at-least-once.** Con QoS 1 un derivato può arrivare due volte: è la deduplica a
  reggere, non il broker.
- **Il nodo che non ha sentito.** Se un convoglio è acceso dopo l'alert non sa niente della stazione
  guasta e non produce nessun derivato: è lo stesso limite già dichiarato per RF02.1.1.2.3 (N07). Lo
  schema non lo risolve — servirebbe una risincronizzazione all'avvio, cioè il nodo che all'accensione
  chiede alla Centrale l'elenco dei guasti aperti.
- **Catene lunghe quanto la rete, ma larghe una volta sola.** Con la regola del paragrafo 4.2 la
  catena può attraversare quanti nodi vuole, ma ogni nodo la vede una volta: un convoglio già fermo
  per la catena X non si rifermerà per un altro anello della stessa catena. È la scelta che rende la
  terminazione dimostrabile in due righe, e in cambio il sistema non modella le conseguenze di
  secondo giro sullo stesso nodo (la coincidenza persa dallo stesso convoglio tre stazioni dopo):
  quelle si ricostruiscono a posteriori dallo storico, dove timestamp e `catena_id` ci sono tutti.
- **Chiudere una conseguenza non ripara la causa.** La chiusura guarda in una direzione sola: risolto
  un guasto, si chiudono quelli della stessa catena aperti dopo di lui. Se l'operatore chiude
  l'allarme sbagliato — quello sulla stazione impercorribile invece che sul convoglio guasto che ce
  l'ha messa — la stazione torna percorribile mentre il convoglio è ancora rotto sui binari. È una
  scelta: l'alternativa era chiudere tutta la catena da qualunque anello, e mi sembra peggio,
  perché vorrebbe dire che chiudere l'allarme di una conseguenza dichiara riparata un'avaria che
  nessuno ha toccato.
- **La percorribilità delle tratte riparte pulita.** Vive in memoria come il resto dello stato
  corrente, quindi al riavvio della Centrale ogni arco risulta percorribile finché qualcuno non
  dichiara il contrario. Per le stazioni il limite è lo stesso, ma lì c'è l'heartbeat a rimettere le
  cose a posto in trenta secondi; per le tratte no, e a ridichiarare l'arco chiuso sarebbe il
  convoglio guasto, che nel frattempo però potrebbe essersi spento anche lui. È lo stesso buco di
  N07 visto da un'altra parte: si chiude con la risincronizzazione all'avvio, non con questo schema.
- **La catena la conia il campo.** L'identificativo se lo genera il nodo che dichiara il primario e
  la Centrale se lo fida, come già si fida del resto dell'alert. Un nodo bacato che riusasse la
  catena di un altro attaccherebbe le proprie conseguenze all'avaria sbagliata: è lo stesso modello
  di fiducia del canale condiviso, dove chiunque può già pubblicare qualunque allarme.
