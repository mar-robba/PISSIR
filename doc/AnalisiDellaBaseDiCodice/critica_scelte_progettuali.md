# Critica delle scelte architetturali, di progettazione e implementative

**Data:** 05/08/2026
**Oggetto:** revisione critica del progetto "Monitoraggio e Gestione del Traffico Ferroviario"
**Scopo:** capire, a progetto finito, cosa è stato deciso bene e cosa andrebbe deciso
diversamente la prossima volta che si progetta un sistema gestionale distribuito.

> Questo documento **non** è la lista dei bug (quella è in [diagnosi_bug.md](diagnosi_bug.md))
> né il confronto con le richieste del prof (quello è in [gap_analysis.md](gap_analysis.md)).
> Qui si guarda più in alto: le *decisioni*. Un bug si corregge in dieci minuti, una decisione
> sbagliata te la trascini per tutto il progetto — e infatti diversi bug elencati nell'altro
> documento sono solo il sintomo delle decisioni criticate qui.

---

## 0. Quadro di sintesi

| Area | Giudizio | In una riga |
|------|----------|-------------|
| Decomposizione in processi/microservizi | 🟢 Ottima | Ogni agente è un processo vero, non un thread finto: il requisito più importante del PDF è centrato |
| Digital twin del treno | 🟢 Ottima | La macchina a stati è la parte più solida e più difendibile del progetto |
| Pattern di affidabilità (heartbeat, buffer, watchdog) | 🟢 Buona | I pattern giusti, scelti al posto giusto |
| Design dei topic MQTT | 🟡 Migliorabile | Gerarchia dei topic sensata, ma comandi ed eventi mescolati sullo stesso topic |
| Flusso degli eventi di transito | 🔴 Da rifare | Due percorsi diversi per lo stesso evento fisico: la causa vera del bug B004 |
| Modello dati e naming del DB | 🔴 Da rifare | Nomi presi dall'analisi e mai normalizzati, tabelle scritte in tre stili diversi |
| Entità JPA usate anche come cache e come DTO | 🔴 Da rifare | Una classe con tre mestieri: è il difetto strutturale principale |
| Rappresentazione degli stati | 🔴 Da rifare | Stringhe magiche e tre vocabolari paralleli invece di enum |
| Serializzazione dei messaggi | 🟡 Migliorabile | JSON costruito con `String.format`: veloce da scrivere, fragile da mantenere |
| Gestione degli errori nei flussi reattivi | 🔴 Da rifare | Nessun `retry`: un errore transitorio spegne un componente per sempre |
| Sicurezza | 🔴 Da rifare | Autenticazione presente ma non collegata a niente |
| Configurazione e profili | 🟢 Buona | Il profilo `%tls` è una soluzione elegante per gestire la variante del prof |
| Igiene del repository | 🔴 Da rifare | 19.000 file su 19.550 sono dipendenze e artefatti di build committati |
| Testing | 🔴 Insufficiente | 5 test, nessuno sulla logica che conta davvero |
| Documentazione | 🟢 Ottima | Cartella `doc/` ricca, diagrammi, scelte spiegate: raro e prezioso |

---

## 1. Architettura di sistema

### 1.1 🟢 La decomposizione in processi indipendenti è la scelta giusta

Il PDF è esplicito: *"la simulazione non deve essere un unico blocco di codice, ma un
ecosistema di agenti"*, e lo lega alla sufficienza. Qui ogni treno e ogni stazione sono un
processo Quarkus separato, con l'ID passato da `args[0]`, la propria memoria (`TrainDB`,
`DBLocale`) e il proprio ciclo di vita. Non è una simulazione con un `for` sui treni dentro
il server: è davvero un sistema distribuito, con tutti i problemi veri di un sistema
distribuito (ordinamento, perdita di messaggi, nodi che cadono).

**Perché è la decisione più importante:** tutto il resto — heartbeat, buffer locale, guasti,
validazione dell'ID — ha senso *solo* perché i processi sono separati. Se avessi fatto un
monolite avresti dovuto simulare i guasti di rete con degli `if`, e sarebbe stato evidente.

**Da portarsi dietro:** quando il requisito parla di "agenti", "nodi", "dispositivi",
la separazione in processi non è un vezzo architetturale, è ciò che rende il problema
quello vero. Costa di più (deploy, configurazione, porte) ma è l'unica strada onesta.

### 1.2 🟡 Il topic condiviso `railway/alerts` mescola eventi e comandi

Su `railway/alerts` viaggiano insieme cose di natura opposta:

* **eventi** che salgono dal campo verso la Centrale: `GUASTO` da stazioni e treni;
* **comandi** che scendono dalla Centrale verso il campo: `STOP`, `RESOLVED`,
  `MAINTENANCE_DISPATCHED`, `ITINERARIO_AGGIORNATO`.

Tutti pubblicano e tutti sono sottoscritti allo stesso topic, e ciascuno filtra con uno
`switch` sul campo `tipoEvento`. Funziona, ed è anche documentato bene — ma ha tre effetti
collaterali:

1. **Ognuno riceve l'eco di sé stesso.** La Centrale è sottoscritta al topic su cui
   pubblica: `IngestionService.onAlert()` deve scartare esplicitamente tutto ciò che non è
   `GUASTO`, altrimenti creerebbe guasti a partire dai propri `RESOLVED`. Il filtro c'è, ma
   è una difesa da un problema che non doveva esistere.
2. **Ogni nodo elabora messaggi che non lo riguardano.** Ogni treno riceve i guasti di
   tutti gli altri treni e di tutte le stazioni; ogni stazione riceve i guasti dei treni.
   Con 5 nodi è irrilevante, con 500 è traffico inutile su ogni singolo nodo.
3. **Il filtraggio è a carico del destinatario**, cioè nel punto in cui è più facile
   sbagliare: infatti il bug B007 (il treno ignora i guasti `warning`) e la logica di
   `target` vs `sorgenteId` vivono esattamente lì.

**Come lo rifarei:** separare per *direzione* e, dove serve, per destinatario:

```
railway/events/alerts            (campo  -> centrale)   eventi di guasto
railway/commands/train/{id}      (centrale -> treno)    STOP, ITINERARIO_AGGIORNATO
railway/commands/station/{id}    (centrale -> stazione) MAINTENANCE_DISPATCHED, RESOLVED
railway/broadcast/network        (centrale -> tutti)    stato della rete, stazioni guaste
```

Con i topic per destinatario il broker fa il lavoro di routing al posto tuo, e i tuoi
`switch` si accorciano. La regola generale: **il topic è il routing; se ti trovi a scrivere
un `if` per capire se un messaggio è tuo, quel `if` andava messo nel topic.**

### 1.3 🔴 Due percorsi diversi per lo stesso evento fisico

Questo è l'errore architetturale più costoso del progetto. Quando un treno entra in
stazione succedono **due cose in parallelo**:

```
treno --(railway/train/{id}/passaggio)--> Centrale.onPassaggio()  --> broadcastTransit()
                                    \
                                     --> Stazione.riceviPassaggio()
                                              |
                                              --(railway/station/{id}/transit)--> Centrale.onTransit() --> broadcastTransit()
```

La Centrale riceve **due volte** la notizia dello stesso passaggio, per due strade diverse,
e reagisce a entrambe. Il bug B004 (transiti duplicati nell'interfaccia) è solo il sintomo;
la causa è che non è mai stata presa la decisione **"chi è il proprietario dell'evento
transito?"**.

E la cosa peggiore è che i due percorsi non fanno neanche la stessa cosa: solo `onTransit`
(quello che passa dalla stazione) scrive i record `Transito`/`StoricoTransito` sul DB,
mentre solo `onPassaggio` aggiorna la posizione del treno sulla tratta. Le due metà della
stessa verità stanno in due gestori diversi, e se la stazione è spenta ne perdi una senza
accorgertene.

**Come lo rifarei:** un evento fisico = un proprietario = un percorso. Nella storia
raccontata dal PDF il proprietario naturale è **la stazione** (è il sensore che rileva il
transito, come nella realtà). Quindi: il treno emette solo il "ping di prossimità"
destinato ai sensori, la stazione lo trasforma nell'evento ufficiale `transito`, e la
Centrale ascolta solo quello. Il treno non parla mai direttamente allo storico.

**Da portarsi dietro:** in un sistema a eventi, la prima tabella da disegnare non è quella
del database — è quella degli eventi: *nome evento | chi lo produce | chi lo consuma | cosa
cambia quando arriva*. Se una riga ha due produttori, hai un problema.

### 1.4 🟡 La validazione dell'ID: idea giusta, meccanismo sbagliato

L'idea è ottima e non scontata: **un nodo edge non deve poter entrare nel sistema con un ID
inventato**, quindi all'avvio chiede alla Centrale se esiste davvero e, se non esiste,
termina con `return 1`. È un controllo che molti progetti non hanno e vale la pena
raccontarlo all'orale.

L'implementazione però è una **RPC sincrona costruita sopra un bus asincrono**: il nodo
pubblica una richiesta, si sottoscrive con una wildcard alle risposte di *tutti*, filtra
quella con il proprio id, e nel frattempo il thread principale gira in un
`while(true) { ...; Thread.sleep(5000); }`. Tre conseguenze:

* servono due topic, due canali, due classi (`TrainDatabaseValidator`,
  `ExistIdForEdge`) e altrettante gemelle per le stazioni — quattro classi per fare una
  domanda;
* la risposta viene recapitata **a tutti i treni**, che la scartano;
* non c'è un `correlationId`: si filtra per `trenoId`, quindi due richieste consecutive
  dello stesso nodo non sono distinguibili.

E soprattutto: **il treno sa già parlare REST con la Centrale**, lo fa poche righe più in
là per scaricare l'itinerario (`GET /api/treni/{id}/itinerario`). Una `GET /api/treni/{id}`
avrebbe risolto tutto in dieci righe, in modo sincrono, con un codice di stato HTTP
esplicito (200 = esisti, 404 = non esisti) e senza inventare un protocollo.

**Regola:** usa il pub/sub per ciò che è *notifica a molti* e per i flussi continui; usa
richiesta/risposta (REST) quando ti serve **una risposta, per te, adesso**. Forzare una RPC
dentro un broker si può fare, ma devi avere un motivo (per esempio: il nodo edge non deve
poter aprire connessioni HTTP verso il core). Se non hai quel motivo, non farlo.

### 1.5 🟡 Nessun modulo condiviso: tre volte lo stesso codice

`SecureHttpClient` esiste identico in `Treni` e in `Stazioni`; `main.java` è quasi identico
nei tre moduli; il formato dei messaggi JSON è riscritto a mano in ogni modulo con
`String.format`. Le conseguenze si vedono: la versione del Treno di `SecureHttpClient` è
stata corretta (fallback quando manca la CA), quella della Stazione no (B032). **Le copie
divergono sempre.**

Detto questo, la scelta è *parzialmente* difendibile: in un'architettura a microservizi la
condivisione di codice crea accoppiamento, e i puristi preferiscono duplicare che
accoppiare. Ma c'è una cosa che va sempre condivisa: **il contratto**, cioè i nomi dei
topic e la forma dei messaggi.

**Come lo rifarei:** un modulo Maven `railway-contract` con le costanti dei topic, gli enum
degli stati e le classi (record) dei payload, dipendenza di tutti e tre i servizi. Non
condivide *logica*, condivide *linguaggio*: è il tipo di accoppiamento buono.

---

## 2. Modello dati e persistenza

### 2.1 🔴 Il naming del database

```sql
CREATE TABLE Guasti_Pervenuti_da_treni_o_Staz (
    id_Guasto                      VARCHAR(50) PRIMARY KEY,
    Stato_RisoltoONO               BOOLEAN NOT NULL DEFAULT FALSE,
    OperatoreCheSeNeStaOccupandoFK VARCHAR(50),
    ...
CREATE TABLE Stazione (          -- singolare
CREATE TABLE Treni (             -- plurale
    PosizioneAttualeTrattaOStazione VARCHAR(50),
    tipoCapolineaPartenzaoNormale   VARCHAR(50),
```

Qui c'è di tutto: singolare e plurale mescolati, `snake_case` e `CamelCase` nella stessa
riga, nomi che descrivono una frase invece di un concetto (`OperatoreCheSeNeStaOccupandoFK`),
il suffisso `FK` nel nome della colonna, il suffisso `ONO`/`oNormale` che congela nel
nome i valori possibili, e `PosizioneAttualeTrattaOStazione` che dichiara nel nome stesso
di essere ambiguo (è una tratta o una stazione? Nel codice è sempre una `Tratta`).

Non è pignoleria: ogni nome storto diventa un `@Column(name = "...")` da ricopiare, una
query in cui sbagli il case, e un pezzo di documentazione che devi rileggere ogni volta.
E il costo si paga per sempre, perché rinominare una colonna dopo la migrazione è doloroso.

**Come lo rifarei:** i nomi nascono dall'analisi ma **vanno normalizzati prima di diventare
DDL**. Una convenzione qualsiasi, purché una sola: tabelle al plurale, `snake_case`,
niente suffissi di tipo, la chiave primaria si chiama `id`, le esterne `<entita>_id`.
`guasti(id, stato, operatore_id, ...)` dice le stesse cose ed è leggibile a colpo d'occhio.

### 2.2 🔴 Una classe, tre mestieri: entità JPA + cache + DTO dell'API

È il difetto strutturale che genera più conseguenze in tutto il progetto. La classe `Treno`:

```java
@Entity @Table(name = "Treni")
public class Treno extends PanacheEntityBase {
    @Id public String id;              // 1) riga di database
    @Column public String stato;
    @Transient public double velocita; // 2) stato runtime tenuto in RAM dalla cache
    @Transient public int passeggeri;
    ...                                // 3) e viene serializzata così com'è dalle API REST
}
```

fa contemporaneamente da **riga di tabella**, da **oggetto in cache** dentro
`TrafficLogicEngine` e da **DTO JSON** restituito da `GET /api/treni`. Tre ruoli con tre
cicli di vita diversi incollati in un unico oggetto. Da lì discende una catena di problemi:

* **Non si capisce più cosa è persistito.** Metà dei campi sono `@Transient` e metà no, e
  il codice deve tenerlo a mente: in `onTelemetry()` l'oggetto in cache viene aggiornato
  con tutti i valori, ma sul DB finiscono solo `stato` e `nome`, perché gli altri sono
  volatili. Chi legge deve andarsi a controllare le annotazioni per sapere cosa sopravvive
  a un riavvio.
* **Ci sono due oggetti per la stessa entità.** In `onTelemetry` convivono `treno` (dalla
  cache, staccato dalla sessione) e `dbTreno` (gestito da Hibernate): due variabili per lo
  stesso treno, e sbagliare quale scrivere è silenzioso. È esattamente il tipo di codice
  che il prof ti chiede di spiegare riga per riga.
* **Il JSON dell'API è deciso dalle annotazioni JPA.** Le relazioni `EAGER` finiscono
  serializzate: `GET /api/treni` restituisce anche l'itinerario e la tratta annidati, con
  le loro stazioni. L'interfaccia del frontend dipende dal modello di persistenza: cambi il
  fetch type e cambi il contratto dell'API.

**Come lo rifarei** (e non costa quasi niente, sono tre classi invece di una):

```
TrenoEntity   -> solo ciò che sta nel database, nessun @Transient
TrenoRuntime  -> record in memoria: posizione, velocità, ritardo, passeggeri
TrenoDto      -> ciò che l'API espone, con i nomi voluti dal frontend
```

Il pezzo di codice più a rischio (`IngestionService`) diventerebbe leggibile: aggiorno il
runtime, aggiorno l'entità, compongo il DTO. Tre gesti distinti invece di uno ambiguo.

**Da portarsi dietro:** la domanda da farsi è sempre *"chi possiede questo oggetto e per
quanto tempo vive?"*. Se le risposte sono più di una, servono più classi. È la regola più
redditizia che si possa applicare a un gestionale.

### 2.3 🟡 Cache in RAM davanti al database: giusta l'idea, non chiuso il cerchio

`TrafficLogicEngine` come cache dello stato corrente è una buona intuizione: la telemetria
arriva ogni 5 secondi per treno, scriverla tutta su Postgres sarebbe uno spreco, e la
dashboard vuole leggere velocemente. Il compromesso "**dato volatile in RAM, dato storico
su DB**" è esattamente quello che si fa nei sistemi di monitoraggio veri.

Quello che manca è la **chiusura del cerchio**: la cache è popolata solo all'avvio da
`onStart()` e poi vive di vita propria, senza invalidazione, senza scadenza e senza
riallineamento. Da qui il problema di `onStart()` che, non avendo nulla di reale da
mettere, si inventa `stato = "ONLINE"` per tutte le stazioni (bug B029): la cache **deve**
avere un valore anche quando non sa niente, e il valore inventato è peggio di "non so".

**Come lo rifarei:** lo stato runtime nasce vuoto ed è esplicitamente "sconosciuto" finché
non arriva il primo messaggio dal campo (`Optional`, o un enum con il valore `UNKNOWN`).
Un cruscotto che dice "non lo so" è affidabile; uno che dice "tutto ok" per default è
pericoloso — nel dominio ferroviario ancora di più.

### 2.4 🟡 `schema.sql` documentale + `generation=update`

La scelta dichiarata è: il DB lo crea Hibernate con `generation=update`, e `schema.sql`
serve come documentazione dello schema. È un compromesso ragionevole per un progetto
d'esame (niente migrazioni da gestire), ma ha un costo che si è già visto: i due si
disallineano (la tabella `eventi_stazioni` esiste nel codice e non nel DDL, bug B005), e
nessuno se ne accorge perché il DDL non viene mai eseguito.

**Come lo rifarei:** o lo schema è la sorgente di verità e le entità gli si adeguano
(migrazioni con Flyway, `generation=validate` così Hibernate ti *avvisa* se non
corrispondono), o è tutto generato dalle entità e il file `.sql` non esiste proprio. Il
caso peggiore è un file che sembra la verità e non lo è.

### 2.5 🟢 Le tabelle storiche

Aver separato lo stato corrente (`Transiti`, `Guasti`) dallo storico
(`Storico_Transiti`, `Storico_Guasti`, `Storico_Stato_Treni`...) è corretto e mostra che il
modello è stato pensato, non improvvisato. Il criterio però è applicato in modo incoerente:
lo storico dei treni si scrive solo al cambio di stato, quello delle stazioni a ogni
heartbeat (bug B021). Una regola sola, scritta una volta, applicata ovunque: **si
storicizza al cambiamento, non al campionamento.**

---

## 3. Rappresentazione dello stato

### 3.1 🔴 Stringhe magiche e tre vocabolari paralleli

Lo stato di un treno è scritto in tre lingue diverse a seconda di dove ti trovi:

| Dove | Valori |
|------|--------|
| Digital twin (`TrainDB.stato`) | `FERMO`, `IN_VIAGGIO`, `EMERGENZA`, `SOPPRESSO` |
| Database (`Treni.stato`, con CHECK) | `attivo`, `fermo`, `rotto`, `in manutenzione` |
| Frontend (`TrainStatus`) | `in_viaggio`, `in_stazione`, `guasto`, `soppresso` |

e in mezzo tre funzioni di traduzione sparse in tre file diversi
(`IngestionService.normalizzaStatoTreno`, `RestApiGateway.normalizzaStatoTreno`,
`apiClient.mapBackendStatus`). Come se non bastasse, il treno ha **due** variabili di stato
che devono restare coerenti fra loro (`stato` e `faseViaggio`), più un `stazioneBloccante`
che vale come terza.

Tutto questo è confrontato con `equals` su stringhe letterali sparse ovunque:
`"CRITICAL".equalsIgnoreCase(severita)`, `"ENTRATA".equals(tipo)`,
`"andata".equals(trainDB.direzione)`. Il compilatore non può aiutarti: un `"ENTRATTA"`
scritto male non è un errore, è solo un `if` che non scatta mai. E infatti due bug reali
nascono proprio qui: `warning` vs `CRITICAL` (B007) e `"unknown"` che viola il CHECK (B035).

**Come lo rifarei:**

```java
public enum StatoTreno { FERMO, IN_VIAGGIO, EMERGENZA, SOPPRESSO }
public enum TipoPassaggio { ENTRATA, USCITA }
public enum Severita { WARNING, CRITICAL }
```

con **una sola** classe di mapping verso il formato del DB e verso quello del frontend.
E se davvero servono tre vocabolari (a volte è inevitabile, per esempio se il DB è preesistente),
la traduzione deve stare in **un solo punto attraversabile**, non in tre file.

**Da portarsi dietro:** in un gestionale gli stati sono il cuore del dominio. Ogni stato
scritto come stringa è un errore che il compilatore non ti segnalerà. Enum sempre, e la
macchina a stati (quali transizioni sono lecite) scritta in un posto solo.

---

## 4. Comunicazione e contratti

### 4.1 🟡 JSON costruito a mano

Tutti i messaggi in uscita sono costruiti così:

```java
String alertJson = String.format(Locale.US,
    "{\"tipoEvento\":\"GUASTO\",\"sorgenteTipo\":\"STAZIONE\",\"sorgenteId\":\"%s\","
  + "\"severita\":\"%s\",\"messaggio\":\"%s\",\"timestamp\":\"%s\"}", ...);
```

Va detto che c'è del bene: l'uso di `Locale.US` è **consapevole e corretto** (senza, in
locale italiano `%.5f` produrrebbe `44,912` e romperebbe il JSON — tanto che in Centrale
esiste una pezza apposta, `normalizeDecimalComma`). Però:

* **non c'è escaping**: `StationIngestion.rilevaGuasto` prende la `descrizione` dal body
  HTTP e la infila tale e quale nella stringa. Una virgoletta nella descrizione produce un
  JSON non parsabile e il messaggio viene scartato in silenzio dalla Centrale. (Nel Treno
  la classe `Sensori` un `escapeJson()` ce l'ha: di nuovo, due copie divergenti.)
* **il formato non è dichiarato da nessuna parte nel codice**: è nella documentazione e
  nella testa di chi ha scritto i due lati. Se aggiungi un campo, devi ricordarti tutti i
  punti in cui quel messaggio viene costruito e letto.

**Come lo rifarei:** un `record` per ogni tipo di messaggio nel modulo condiviso del §1.5,
serializzato con l'`ObjectMapper` che è già iniettato ovunque:

```java
public record AlertGuasto(String tipoEvento, String sorgenteTipo, String sorgenteId,
                          Severita severita, String messaggio, Instant timestamp) {}
```

Il contratto diventa codice, l'escaping è gratis, il `Locale` non è più un problema e la
pezza `normalizeDecimalComma` sparisce insieme al bug latente che si porta dietro (B013).

### 4.2 🟡 Niente versione, niente id di correlazione

I payload non hanno un campo `version` né un `messageId`. Per un progetto d'esame va bene,
ma è utile sapere perché nei sistemi veri ci sono: la versione ti permette di far convivere
un nodo vecchio e uno nuovo durante un aggiornamento; l'id ti permette di riconoscere i
duplicati (MQTT QoS 1 consegna *almeno* una volta, quindi i duplicati sono normali) e di
seguire un evento nei log di tre servizi diversi quando devi capire cosa è successo.

Un `messageId` avrebbe anche reso banale risolvere il doppio evento di transito del §1.3:
stesso id ⇒ stesso evento ⇒ lo ignoro.

### 4.3 🟢 La gerarchia dei topic

`railway/{tipo}/{id}/{funzione}` è una gerarchia pulita e sfrutta bene le wildcard: la
Centrale ascolta `railway/train/+/telemetry` e riceve tutti i treni senza sapere quanti
sono, cosa che rende il sistema **scalabile senza riconfigurazione**. È il modo giusto di
usare MQTT, ed è un punto da far notare all'orale.

---

## 5. Il digital twin: il pezzo migliore, con due riserve

`TrainJourneyEngine` è la parte del progetto che vale di più: una macchina a stati
esplicita (`IN_STAZIONE` → `IN_VIAGGIO` → `IN_STAZIONE`, più `BLOCCATO_GUASTO_STAZIONE`,
`EMERGENZA`, `SOPPRESSO`), inversione al capolinea, interpolazione della posizione,
accumulo del ritardo, congelamento del tempo quando il treno è bloccato. Il fattore di
accelerazione configurabile (`viaggio.fattore.accelerazione`) è un'ottima idea pratica: la
stessa simulazione è dimostrabile in 90 secondi o in tempo reale, cambiando una property.

**Riserva 1 — "eventi discreti" è un termine impegnativo.** Il PDF chiede un *motore basato
su eventi discreti*; qui c'è un **tick temporizzato** (`Multi.ticks().every(1s)`) che a ogni
scatto ricalcola dove si trova il treno. È una simulazione *a passo fisso*, non a eventi
discreti: in un motore a eventi discreti c'è una coda di eventi futuri ordinati per tempo
(`arrivo(T+90s)`), si salta direttamente al prossimo evento e non esiste il concetto di
"tick". La scelta a tick è più semplice, più che sufficiente qui, e ha un vantaggio reale
(la posizione GPS interpolata va aggiornata di continuo comunque) — ma va **chiamata con il
suo nome**, altrimenti all'orale è una domanda facile a cui rispondi male.

**Riserva 2 — due variabili per un solo stato.** `stato` e `faseViaggio` devono restare
coerenti e la coerenza è mantenuta a mano, ricordandosi di aggiornare entrambe in ogni
transizione (più `fasePrimaDelBlocco` per ricordare da dove si riprende). È fragile: il bug
B034 nasce proprio da una transizione fatta a metà (il treno è trattenuto senza entrare
nella fase di blocco, quindi non accumula ritardo). **Uno stato solo, e le transizioni in un
solo metodo** (`transizione(nuovoStato)`) che logga e valida: da lì in poi è impossibile
avere combinazioni impossibili.

---

## 6. Robustezza: il punto più debole del progetto

I pattern di affidabilità richiesti ci sono tutti (heartbeat, store-and-forward, watchdog
lato Centrale), ma la **gestione degli errori dentro i pattern** non è stata pensata. Tre
esempi che condividono la stessa radice:

```java
.onFailure().invoke(e -> { LOG.error("..."); dbLocale.connessioneCentrale = false; });
```

`invoke` esegue un effetto collaterale ma **non recupera lo stream**: dopo il primo errore
il flusso di heartbeat è chiuso per sempre (B026). Manca `retry()`.

```java
public CompletionStage<Void> interrogaIlDPerValidazione(Message<byte[]> m) throws JsonProcessingException {
```

Un payload malformato fa fallire il canale, che smette di consumare (B025).

```java
@Incoming("telemetry-in") @Transactional
public CompletionStage<Void> onTelemetry(...) { try { ... } catch (Exception e) { LOG.error(...); } }
```

Il `try` copre il corpo, ma il commit avviene **fuori** dal metodo: l'eccezione di vincolo
violato non viene catturata da quel `catch`.

Il filo comune è che l'errore è stato trattato come **qualcosa da loggare**, non come
**qualcosa da cui riprendersi**. In un sistema distribuito gli errori transitori (broker
che riparte, rete che sfarfalla, messaggio storto) non sono l'eccezione: sono il
funzionamento normale.

**Da portarsi dietro — tre domande da farsi su ogni componente che parla con l'esterno:**

1. *Se questa chiamata fallisce una volta, cosa succede?* → serve un retry con backoff.
2. *Se fallisce per sempre, cosa succede?* → serve uno stato degradato dichiarato
   (il buffer locale, qui, è la risposta giusta a questa domanda: infatti quel pezzo è
   progettato bene).
3. *Se il messaggio è spazzatura, cosa succede?* → si scarta e si va avanti; non deve mai
   poter fermare il flusso (dead letter, o quantomeno ack + log).

---

## 7. Sicurezza

Il livello è quello di un progetto didattico, ed è accettabile — ma alcune scelte sono
sbagliate anche per un progetto didattico, e vale la pena saperlo:

* **L'autenticazione non è collegata a nulla.** Il login verifica la password e genera un
  token `UUID.randomUUID()` che **non viene salvato né mai verificato**, e il frontend non
  lo invia. Non è "sicurezza semplificata", è sicurezza *finta*: la differenza tra tecnico
  e amministratore esiste solo nei menù della SPA. Fare un login serio (token in memoria +
  un filtro JAX-RS) sarebbe costato mezza giornata.
* **Password in chiaro** nella tabella `Utenti` e nel seed. Anche in un progetto d'esame,
  un `BCrypt` è tre righe e mostra che sai che il problema esiste.
* **CORS `origins=*`** su un'API senza autenticazione: qualunque pagina web aperta nel
  browser può pilotare la Centrale.
* **Broker con `allow_anonymous true`**, anche sul listener TLS: il TLS cifra il canale ma
  non identifica chi si connette, quindi chiunque può pubblicare su `railway/alerts` e
  sopprimere un treno. TLS ≠ autenticazione: è una distinzione concettuale che vale la pena
  scrivere in relazione, perché mostra che hai capito cosa stai proteggendo e da cosa.

**Da portarsi dietro:** decidere *esplicitamente* il livello di sicurezza (anche
"nessuna, è una demo") e scriverlo. Il male peggiore è la sicurezza apparente: un token che
sembra proteggere e non protegge è peggio di nessun token, perché chi legge il codice si
fida.

---

## 8. Configurazione, build e repository

### 8.1 🟢 I profili Quarkus

Usare il profilo `%tls` per attivare TLS su MQTT e HTTPS sulla Centrale è la soluzione
migliore possibile per il problema "il prof ammette la variante senza TLS": una sola base di
codice, due configurazioni, si dimostrano entrambe cambiando un flag. Da rifare identico.

L'errore, semmai, è di **completezza**: quando si aggiunge un canale nuovo bisogna ricordarsi
di aggiungere anche le sue tre righe `%tls` — e infatti i canali di validazione, aggiunti per
ultimi, sono rimasti indietro (bug B023, il più grave del progetto). Con una property
condivisa (`%tls.mp.messaging.*.ssl=true` non esiste, ma un `truststore` comune impostato una
volta sola sì) il problema non si sarebbe posto.

### 8.2 🟡 Porte fisse

`quarkus.http.port=8082` per i treni e `8081` per le stazioni sono cablate nel file: il
secondo treno sulla stessa macchina non parte, a meno di passare `-Dquarkus.http.port`.
In un sistema il cui punto centrale è "N istanze indipendenti", la porta doveva essere
derivata dall'ID o assegnata dinamicamente (`quarkus.http.port=0`) fin dall'inizio.

### 8.3 🔴 Il repository contiene 19.000 file di dipendenze

```
file tracciati da git:      19.550
di cui node_modules/:       18.992   (97%)
di cui target/ (build):        354
.gitignore alla radice:     una riga (BrokerMosquitto/data/)
```

`node_modules`, i `.class`, i `.jar` di Quarkus e i `dist/` del frontend sono committati.
Conseguenze concrete, non teoriche:

* il repo è enorme e lento, e ogni `git status` mostra decine di artefatti "modificati" che
  nascondono le modifiche vere (si vede anche nel diff attuale: i `.jar` compaiono in mezzo
  ai sorgenti);
* **ogni build produce un diff**, quindi la cronologia dei commit non racconta più cosa hai
  cambiato davvero;
* le dipendenze committate contengono migliaia di file di terzi: con un professore che
  dichiara di usare strumenti antiplagio, è rumore che non ti conviene avere nel repo;
* per un progetto d'esame è anche un segnale di sciatteria che si nota in cinque secondi.

**Da portarsi dietro:** il `.gitignore` è la **prima** cosa da scrivere, prima ancora del
primo commit — `target/`, `node_modules/`, `dist/`, `.idea/`, `*.log`, e i certificati/chiavi
private (`tls/certs/*.key`, che qui sono committati e in un progetto vero sarebbero un
incidente di sicurezza). Regola: **nel repository ci va solo ciò che scrivi tu a mano.**

---

## 9. Testing

Cinque file di test in tutto il progetto: due `ExampleTest` generati dall'archetipo, uno
smoke test, un test su `Guasto` e uno sulle API di amministrazione. Zero test su:

* la macchina a stati del viaggio — la logica più complessa e più fragile del progetto,
  quella che il prof ti chiederà di spiegare;
* il calcolo della prossima stazione e l'inversione al capolinea;
* il buffer locale e il suo flush (comportamento richiesto esplicitamente dal PDF);
* il parsing dei messaggi MQTT.

Il paradosso è che il pezzo più testabile del progetto è proprio quello non testato:
`TrainJourneyEngine` è quasi puro (dipende da `TrainDB`, dal tempo e da un gateway), e con
tre stub avresti potuto verificare in pochi minuti che dopo N tick il treno è dove deve
essere, che al capolinea inverte e che bloccato accumula ritardo. Test del genere ti
avrebbero trovato B034 da soli.

**Da portarsi dietro:** non serve testare tutto. Serve testare **il pezzo che contiene le
regole del dominio** — quello che, se sbaglia, produce un risultato *plausibile ma falso*.
Gli errori di configurazione li vedi subito; una regola di business sbagliata no.

---

## 10. Frontend

Scelte buone: separazione `apiClient` / `websocketClient` / store, tipi TypeScript per i
payload dell'API, riconnessione automatica della WebSocket, funzioni di mapping centralizzate
tra vocabolario backend e frontend.

Critiche:

* **URL cablati** (`http://localhost:8781`, `ws://localhost:8781`): con il profilo TLS attivo
  il browser continua a parlare in chiaro. Dovevano essere variabili d'ambiente Vite
  (`import.meta.env.VITE_API_URL`), che è anche il modo standard di farlo.
* **Nessuna deduplica degli eventi**: il frontend si fida di ciò che arriva dalla WebSocket e
  usa `tr-${Date.now()}` come chiave quando manca l'id. Un client realtime deve considerare
  normale ricevere due volte lo stesso evento (§4.2).
* **Nessun rilevamento di stato stantìo**: se la WebSocket cade, i dati restano quelli
  dell'ultimo aggiornamento e la dashboard sembra viva. Un pannello di monitoraggio deve
  dire *"dati fermi da 30 secondi"*, altrimenti mente all'operatore. È lo stesso errore
  concettuale del §2.3 (l'ottimismo per default).

---

## 11. Documentazione e stile del codice

🟢 **La documentazione è il punto di forza inaspettato.** Una cartella `doc/` con
architettura, casi d'uso, diagrammi di stato e dei componenti, note sui topic MQTT, gap
analysis: molti progetti d'esame non hanno niente di tutto questo, e per un'interrogazione
in cui devi spiegare le tue scelte vale moltissimo. I Javadoc sulle classi principali sono
scritti bene e spiegano il *perché*, non solo il *cosa* — che è la parte difficile.

🟡 **Ma i commenti sono anche un diario di lavoro.** Nel codice restano appunti come
`// ?`, `// boh`, `// ma come cazzo lo abbiamo fatto sto modello ??`,
`// ma che cazz di controllo è ?? è davvero necessario`, `// TODO: controlla un po' meglio`,
`// ATTENZIOONE !!!! RACE CONDITON`. Sono utili mentre lavori, ma nella versione consegnata
diventano due cose insieme: un segnale di codice non finito, e — soprattutto — **una mappa
delle domande da farti**, che consegni al professore. Quel `// ATTENZIONE RACE CONDITION`
sopra il `@Scheduled` è un invito esplicito a chiederti conto della concorrenza: se ce lo
lasci, devi sapere rispondere.

**Da portarsi dietro:** i dubbi vanno tracciati (in un `TODO.md`, nelle issue, dove vuoi),
ma prima della consegna il codice va ripulito e ogni `?` va trasformato in una risposta —
o in una riga di documentazione che dice "questa cosa non è gestita, ecco perché".

🟡 **Convenzioni Java disattese.** Le classi si chiamano `main` (minuscolo) invece di `Main`;
i tre moduli usano lo stesso package `it.uni.reti2` con tre classi `main` diverse; nei moduli
edge tutte le classi stanno in un unico package piatto mentre la Centrale è stratificata
(`entity`, `gateway`, `ingestion`, `elaboration`). Nessuna di queste è un bug, ma sono le
cose che un revisore nota per prime.

---

## 12. Le lezioni da portare al prossimo gestionale

Una checklist operativa, in ordine di quanto ti fanno risparmiare:

1. **Disegna prima la tabella degli eventi, non quella del database.** Per ogni evento:
   chi lo produce, chi lo consuma, cosa cambia. Se ha due produttori, fermati (§1.3).
2. **Una classe = un mestiere.** Entità di persistenza, oggetto runtime e DTO dell'API sono
   tre cose diverse anche quando hanno gli stessi campi (§2.2).
3. **Gli stati sono enum, mai stringhe.** E la traduzione tra vocabolari sta in un punto
   solo (§3.1).
4. **Il contratto dei messaggi è codice condiviso**, non documentazione: record + costanti
   dei topic in un modulo a parte (§1.5, §4.1).
5. **Pub/sub per le notifiche a molti, REST per le domande dirette.** Non costruire RPC
   sopra un broker senza un motivo forte (§1.4).
6. **Il topic è il routing.** Se scrivi un `if` per capire se un messaggio ti riguarda,
   quel `if` andava messo nel nome del topic (§1.2).
7. **Per ogni chiamata verso l'esterno rispondi a tre domande:** cosa succede se fallisce
   una volta, cosa se fallisce sempre, cosa se il dato è spazzatura (§6).
8. **Lo stato sconosciuto va rappresentato.** Mai un default ottimista in un sistema di
   monitoraggio: "non lo so" è un'informazione, "tutto ok" inventato è una bugia (§2.3, §10).
9. **`.gitignore` prima del primo commit.** Nel repo solo ciò che scrivi a mano (§8.3).
10. **Testa la logica di dominio**, cioè la parte che può produrre risultati plausibili ma
    sbagliati. Gli errori di configurazione si vedono da soli (§9).
11. **Niente sicurezza finta.** O la fai, o dichiari che non c'è (§7).
12. **Chiama le cose con il loro nome.** "Motore a eventi discreti" e "simulazione a passo
    fisso" sono due cose diverse: usare il termine giusto ti protegge all'orale e ti
    chiarisce le idee mentre progetti (§5).

---

## 13. Se il prof chiede "cosa rifaresti diverso?"

Una risposta breve, onesta e che mostra padronanza — vale più di qualunque difesa:

> «Tre cose. La prima: userei un'unica strada per l'evento di transito, oggi ne ho due
> perché non ho deciso all'inizio chi fosse il proprietario dell'evento, e questo mi genera
> notifiche duplicate. La seconda: separerei l'entità JPA dall'oggetto in cache e dal DTO
> dell'API — averle unite mi ha costretto a riempire le entità di campi `@Transient` e rende
> ambiguo capire cosa viene davvero persistito. La terza: userei enum al posto delle stringhe
> per gli stati, perché oggi ho tre vocabolari diversi tradotti in tre punti del codice, e un
> paio di bug nascono esattamente lì. Quello che rifarei uguale è la separazione in processi
> indipendenti, il digital twin come macchina a stati e il buffer locale per lo
> store-and-forward.»

---

*Documento scritto dopo la lettura integrale dei sorgenti dei tre microservizi, della
configurazione MQTT/TLS, dello schema del database e del frontend.*
