# Critica delle scelte architetturali, di progettazione e implementative

**Prima stesura:** 05/08/2026
**Revisione:** 06/08/2026 — rivista riga per riga sul codice **dopo** le correzioni di
[correzioni_applicate.md](correzioni_applicate.md)
**Oggetto:** revisione critica del progetto "Monitoraggio e Gestione del Traffico Ferroviario"
**Scopo:** capire, a progetto finito, cosa è stato deciso bene e cosa andrebbe deciso
diversamente la prossima volta che si progetta un sistema gestionale distribuito.

> Questo documento **non** è la lista dei bug (quella è in [diagnosi_bug.md](diagnosi_bug.md))
> né il confronto con le richieste del prof (quello è in [gap_analysis.md](gap_analysis.md)).
> Qui si guarda più in alto: le *decisioni*. Un bug si corregge in dieci minuti, una decisione
> sbagliata te la trascini per tutto il progetto — e infatti diversi bug elencati nell'altro
> documento sono solo il sintomo delle decisioni criticate qui.

> **Perché questa revisione.** La prima stesura è stata scritta prima dell'intervento di
> correzione, e `correzioni_applicate.md` dichiara esplicitamente di non aver toccato questo
> file. Risultato: per un giorno i due documenti si sono contraddetti, e questo qui accusava
> il progetto di difetti che nel frattempo erano stati chiusi — a partire dall'autenticazione,
> che qui era descritta come "finta" e oggi è invece verificata lato server a ogni chiamata.
> Consegnare una critica che sbaglia il bersaglio è peggio che non averla: ogni affermazione
> di questa versione è stata **riverificata sul codice**, e quelle superate sono segnate come
> tali invece di essere cancellate, perché la storia di come un difetto è stato chiuso vale
> quanto il difetto.

---

## 0. Quadro di sintesi

Legenda dello stato: **APERTO** = vale ancora oggi · **CHIUSO** = corretto dopo la prima
stesura · **PARZIALE** = il sintomo è stato tolto, la decisione che lo causava è ancora lì.

| § | Area | Giudizio | Stato | In una riga |
|---|------|----------|-------|-------------|
| 1.1 | Decomposizione in processi | 🟢 Ottima | — | Ogni agente è un processo vero, non un thread finto: il requisito più importante del PDF è centrato |
| 1.2 | Design dei topic MQTT | 🟡 Migliorabile | APERTO | Gerarchia sensata, ma comandi ed eventi mescolati sullo stesso topic |
| 1.3 | Flusso degli eventi di transito | 🔴 Da rifare | PARZIALE | I duplicati sono spariti scegliendo una sorgente, ma i due percorsi esistono ancora |
| 1.4 | Validazione dell'ID via MQTT | 🟡 Migliorabile | APERTO | Idea giusta, ma è una RPC costruita sopra un bus asincrono |
| 1.5 | Nessun modulo condiviso | 🔴 Da rifare | APERTO | `SecureHttpClient` è duplicato e le due copie differiscono di 19 righe |
| 2.1 | Naming del database | 🔴 Da rifare | APERTO | Nomi presi dall'analisi e mai normalizzati, tre stili nella stessa riga |
| 2.2 | Entità JPA = cache = DTO | 🔴 Da rifare | APERTO | Una classe con tre mestieri: è il difetto strutturale principale |
| 2.3 | Cache in RAM davanti al DB | 🟡 Migliorabile | PARZIALE | Il default ottimista è stato tolto, l'invalidazione resta manuale |
| 2.4 | `schema.sql` + `generation=update` | 🟡 Migliorabile | APERTO | Il disallineamento noto è chiuso, ma il meccanismo che lo produce no |
| 2.5 | Tabelle storiche | 🟢 Buona | CHIUSO | La regola "si storicizza al cambiamento" ora è applicata ovunque |
| 3.1 | Rappresentazione degli stati | 🔴 Da rifare | APERTO | Stringhe magiche e tre vocabolari paralleli invece di enum |
| 4.1 | Serializzazione dei messaggi | 🟡 Migliorabile | APERTO | 13 righe di JSON scritto a mano, con l'escaping presente in un modulo su tre |
| 4.2 | Niente versione né id di messaggio | 🟡 Migliorabile | APERTO | La deduplica è stata risolta a livello applicativo invece che di protocollo |
| 4.3 | Gerarchia dei topic | 🟢 Ottima | — | `railway/{tipo}/{id}/{funzione}` + wildcard: scalabile senza riconfigurazione |
| 5 | Digital twin | 🟢 Ottima | — | La parte più solida del progetto, con due riserve da saper difendere |
| 6 | Errori nei flussi reattivi | 🟢 Buona | CHIUSO | Era il punto più debole del progetto, oggi è uno dei più curati |
| 7 | Sicurezza | 🟡 Migliorabile | PARZIALE | L'autorizzazione è vera; restano password in chiaro, WebSocket aperta, broker anonimo |
| 8.1 | Profili Quarkus | 🟢 Ottima | CHIUSO | Il profilo `%tls` è elegante, e ora è completo su tutti i canali |
| 8.2 | Porte fisse | 🟡 Migliorabile | APERTO | Due istanze dello stesso tipo di nodo non partono senza override |
| 8.3 | Igiene del repository | 🔴 Da rifare | APERTO | 233 file scritti a mano su 19.586 tracciati, e 3 chiavi private committate |
| 9 | Testing | 🔴 Insufficiente | APERTO | 6 test in tutto, nessuno sulla logica di dominio |
| 10 | Frontend | 🟡 Migliorabile | PARZIALE | Gli URL sono configurabili, ma la dashboard non sa dire "dati fermi" |
| 11 | Documentazione | 🟢 Ottima | — | Cartella `doc/` ricca, diagrammi, scelte spiegate: raro e prezioso |

---

## 0.1 Cosa è cambiato dalla prima stesura

Queste critiche **non valgono più**. Sono elencate perché è la prima cosa da sapere prima di
usare questo documento all'orale: se le ripeti, ti accusi di difetti che non hai.

| Critica della prima stesura | Cosa dice il codice oggi |
|---|---|
| «L'autenticazione non è collegata a nulla: il token non viene salvato né mai verificato, e il frontend non lo invia» | `SessioniAttive` registra token e ruolo, `FiltroAutorizzazione` li verifica su ogni `/api`, `apiClient.ts:41` manda `Authorization: Bearer` |
| «Manca `retry()`: dopo il primo errore il flusso di heartbeat è chiuso per sempre» | `TrainElab:92` e `HeartbeatGenerator:137` hanno `retry().withBackOff(1s, 10s).indefinitely()` |
| «Un payload malformato fa fallire il canale, che smette di consumare» | I due validatori hanno try/catch sull'intero corpo e `ack()` incondizionato |
| «Il `try` copre il corpo ma il commit avviene fuori dal metodo» | Le scritture girano in `QuarkusTransaction.requiringNew()` aperta **dentro** il try |
| «`onStart()` si inventa `stato = "ONLINE"` per tutte le stazioni» | Restano `OFFLINE` con `ultimoHeartbeat` nullo, e il watchdog salta chi non ha mai battuto |
| «Lo storico delle stazioni si scrive a ogni heartbeat» | Si scrive solo se `statoCambiato` |
| «Uno stato treno sconosciuto viola il CHECK» | `normalizzaStatoTreno` ripiega su `"fermo"` per qualunque valore non riconosciuto |
| «I canali di validazione sono rimasti senza `%tls`» | Tutti e quattro presenti, in Centrale e nel Treno, con un commento che spiega perché |
| «`eventi_stazioni` esiste nel codice e non nel DDL» | La tabella è in `schema.sql` |
| «Transiti duplicati nell'interfaccia» | `onPassaggio` non fa più broadcast: la sorgente per la UI è solo la stazione |
| «La pezza `normalizeDecimalComma` e il suo bug latente» | Il metodo non esiste più |
| «URL cablati nel frontend» | `VITE_API_BASE_URL` e `VITE_WS_URL`, con il valore attuale come fallback |
| «CORS `*` su un'API senza autenticazione: qualunque pagina può pilotare la Centrale» | Con un token `Bearer` (non un cookie) non c'è autorità ambientale: una pagina ostile non può usare le tue credenziali. `origins=*` resta da stringere, ma non è più quel rischio |

Dodici critiche su ventitré sono state chiuse in un solo intervento. Vale la pena notare
*quali*: quasi tutte quelle di **robustezza**, cioè le più facili da correggere una volta
individuate. Quelle rimaste aperte sono quasi tutte **strutturali** — il modello dati, i
vocabolari, la duplicazione — e infatti nessuna si corregge in mezza giornata. È la
differenza fra un difetto e una decisione, ed è esattamente il punto di questo documento.

---

## 0.2 Le correzioni come sintomo: dove si è messa una pezza invece di cambiare la decisione

Questa sezione è la più utile del documento, ed è nuova. Se si guardano le correzioni
applicate una accanto all'altra, cinque di esse hanno la stessa forma: **codice applicativo
che compensa una decisione di progettazione**. Funzionano, sono ben scritte e commentate, ma
sono cerotti — e sapere di cosa sono il cerotto è ciò che distingue chi ha corretto un bug da
chi ha capito il proprio sistema.

| La pezza | Cosa compensa |
|---|---|
| Il campo `origine: CENTRALE` sugli alert, per non riascoltarsi da soli | Il topic condiviso su cui viaggiano insieme eventi e comandi (§1.2). Con topic separati per direzione, l'eco non esisterebbe e il campo sarebbe inutile |
| La deduplica dei guasti per coppia `(sorgenteId, tipo)` | L'assenza di un `messageId` nel protocollo (§4.2). Con un id di messaggio la deduplica sarebbe una riga sola e varrebbe per *tutti* i messaggi, non solo per i guasti |
| Il prefisso `"Heartbeat assente:"` per riconoscere i guasti aperti dalla Centrale | L'assenza di un campo che dica chi possiede il guasto (§3.1). La proprietà di un record è codificata dentro un messaggio pensato per essere letto da un umano: basta riscrivere quel testo e la riconciliazione del fail-stop smette di funzionare, in silenzio |
| L'allineamento della cache RAM ripetuto a mano dopo ogni commit, endpoint per endpoint | L'entità che fa da riga di DB, da oggetto in cache e da DTO (§2.2). Ogni endpoint nuovo deve ricordarsene, e chi dimentica non se ne accorge |
| La scelta «il broadcast dei transiti lo fa solo la stazione» | La mancanza di un proprietario dell'evento (§1.3). È la decisione giusta presa nel punto sbagliato: a valle, nel gestore, invece che a monte, nel disegno degli eventi |

**Da portarsi dietro:** quando ti accorgi che una correzione consiste nell'aggiungere un
campo per distinguere i tuoi messaggi dai messaggi altrui, o un `if` per riconoscere la roba
tua, la domanda giusta non è "dove metto il campo" ma **"perché sto ricevendo roba che non è
mia?"**. Quasi sempre la risposta sta nel disegno dei canali, non nel codice.

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

C'è anche una prova indiretta che la scelta era quella giusta: i bug più interessanti del
progetto (fail-stop non propagato, doppio evento di transito, canale che muore in silenzio)
sono **bug da sistema distribuito**. In un monolite non sarebbero mai esistiti — ma non
avresti nemmeno imparato niente.

**Da portarsi dietro:** quando il requisito parla di "agenti", "nodi", "dispositivi",
la separazione in processi non è un vezzo architetturale, è ciò che rende il problema
quello vero. Costa di più (deploy, configurazione, porte) ma è l'unica strada onesta.

### 1.2 🟡 Il topic condiviso `railway/alerts` mescola eventi e comandi — APERTO

Su `railway/alerts` viaggiano insieme cose di natura opposta:

* **eventi** che salgono dal campo verso la Centrale: `GUASTO` da stazioni e treni;
* **comandi** che scendono dalla Centrale verso il campo: `STOP`, `RESOLVED`,
  `MAINTENANCE_DISPATCHED`, `ITINERARIO_AGGIORNATO`.

Tutti pubblicano e tutti sono sottoscritti allo stesso topic, e ciascuno filtra sul campo
`tipoEvento`. Funziona, ed è anche documentato bene — ma ha tre effetti collaterali:

1. **Ognuno riceve l'eco di sé stesso.** La Centrale è sottoscritta al topic su cui pubblica,
   quindi `onAlert()` deve scartare due volte: prima tutto ciò che non è `GUASTO`, poi anche
   i `GUASTO` che ha pubblicato lei stessa, riconosciuti dal campo `origine`. Due filtri
   difensivi per un problema che con topic separati non esisterebbe.
2. **Ogni nodo elabora messaggi che non lo riguardano.** Ogni treno riceve i guasti di tutti
   gli altri treni e di tutte le stazioni. Con 5 nodi è irrilevante, con 500 è traffico
   inutile su ogni singolo nodo.
3. **Il filtraggio è a carico del destinatario**, cioè nel punto in cui è più facile
   sbagliare: infatti la distinzione `warning`/`CRITICAL` e quella fra `target` e
   `sorgenteId` vivono esattamente lì, e da lì sono venuti due bug.

**Come lo rifarei:** separare per *direzione* e, dove serve, per destinatario:

```
railway/events/alerts            (campo  -> centrale)   eventi di guasto
railway/commands/train/{id}      (centrale -> treno)    STOP, ITINERARIO_AGGIORNATO
railway/commands/station/{id}    (centrale -> stazione) MAINTENANCE_DISPATCHED, RESOLVED
railway/broadcast/network        (centrale -> tutti)    stato della rete, stazioni guaste
```

Con i topic per destinatario il broker fa il routing al posto tuo, i tuoi `switch` si
accorciano e il campo `origine` sparisce. La regola generale: **il topic è il routing; se ti
trovi a scrivere un `if` per capire se un messaggio è tuo, quel `if` andava messo nel topic.**

### 1.3 🔴 Due percorsi diversi per lo stesso evento fisico — PARZIALE

Questo resta l'errore architetturale più costoso del progetto, anche se il suo sintomo più
visibile è stato tolto. Quando un treno entra in stazione succedono **due cose in parallelo**:

```
treno --(railway/train/{id}/passaggio)--> Centrale.onPassaggio()   [posizione sulla tratta]
                                    \
                                     --> Stazione.riceviPassaggio()
                                              |
                                              --(railway/station/{id}/transit)--> Centrale.onTransit()   [record Transito + broadcast]
```

La Centrale riceve **due volte** la notizia dello stesso passaggio, per due strade diverse.
La correzione applicata è stata scegliere una sorgente per la dashboard: solo `onTransit` fa
il broadcast, e nel codice c'è il commento che lo spiega. È la decisione giusta, e i transiti
doppi nell'interfaccia sono spariti.

**Ma la decisione è stata presa a valle, non a monte,** e infatti la metà strutturale del
problema è ancora tutta lì: i due gestori continuano a fare **metà del lavoro ciascuno**.
`onTransit` scrive i record `Transito`/`StoricoTransito`, `onPassaggio` aggiorna la posizione
del treno sulla tratta e la prossima stazione. Le due metà della stessa verità stanno in due
gestori diversi, alimentati da due nodi diversi: **se il processo della stazione è spento, il
treno continua a muoversi ma nessun transito viene registrato** — ed è scritto nero su bianco
fra i limiti dichiarati in `correzioni_applicate.md`. È un limite accettato consapevolmente,
il che è molto meglio di un bug ignorato, ma resta la conseguenza di una decisione mancata.

**Come lo rifarei:** un evento fisico = un proprietario = un percorso. Nella storia raccontata
dal PDF il proprietario naturale è **la stazione** (è il sensore che rileva il transito, come
nella realtà). Quindi: il treno emette solo il "ping di prossimità" destinato ai sensori, la
stazione lo trasforma nell'evento ufficiale `transito`, e la Centrale ascolta solo quello,
ricavando *da quell'unico evento* sia lo storico sia la posizione sulla tratta. Un gestore
solo, una verità sola.

**Da portarsi dietro:** in un sistema a eventi, la prima tabella da disegnare non è quella
del database — è quella degli eventi: *nome evento | chi lo produce | chi lo consuma | cosa
cambia quando arriva*. Se una riga ha due produttori, fermati lì.

### 1.4 🟡 La validazione dell'ID: idea giusta, meccanismo sbagliato — APERTO

L'idea è ottima e non scontata: **un nodo edge non deve poter entrare nel sistema con un ID
inventato**, quindi all'avvio chiede alla Centrale se esiste davvero e, se non esiste,
termina con `return 1`. È un controllo che molti progetti non hanno e vale la pena
raccontarlo all'orale — tanto più che la correzione B024 ha chiuso anche la scappatoia per
cui la telemetria di un ID inventato si creava la riga da sola, auto-validandosi.

L'implementazione però è una **RPC sincrona costruita sopra un bus asincrono**, e il codice
lo mostra con precisione:

```properties
mp.messaging.incoming.validation-response-in.topic=railway/train/+/validation-response
```

```java
// TrainDatabaseValidator.gestisciRispostaValidazione
if (trenoIdRisposta != null && trenoIdRisposta.equals(trenoIdCorrente)) { ... }
```

Il nodo si sottoscrive con una **wildcard alle risposte di tutti** e poi scarta in codice
quelle che non sono sue. Conseguenze:

* servono due topic, due canali e due classi (`TrainDatabaseValidator`, `ExistIdForEdge`) più
  altrettante gemelle per le stazioni: **quattro classi per fare una domanda**;
* la risposta viene recapitata a tutti i treni, che la buttano via;
* non c'è un `correlationId`: si filtra per `trenoId`, quindi due richieste consecutive dello
  stesso nodo non sono distinguibili;
* la sincronizzazione è ottenuta con un `while(true) { ...; Thread.sleep(5000); }` nel main e
  un throttle a 15 secondi nel validatore: due timer che devono andare d'accordo.

E soprattutto: **il treno sa già parlare REST con la Centrale**, lo fa poche righe più in là
per scaricare l'itinerario (`GET /api/treni/{id}/itinerario`). Una `GET /api/treni/{id}`
avrebbe risolto tutto in dieci righe, in modo sincrono, con un codice di stato HTTP esplicito
(200 = esisti, 404 = no) e senza inventare un protocollo.

C'è però un contro-argomento onesto, da tenere pronto all'orale: la validazione via MQTT
funziona **anche se la Centrale non è ancora salita**, perché il messaggio resta sul broker e
il nodo riprova; e non obbliga i nodi di campo ad aprire connessioni HTTP verso il core, che
in una rete industriale vera è spesso proprio ciò che si vuole evitare. Se difendi la scelta,
difendila con questo — non con "così è tutto uniforme".

**Regola:** usa il pub/sub per ciò che è *notifica a molti* e per i flussi continui; usa
richiesta/risposta (REST) quando ti serve **una risposta, per te, adesso**. Forzare una RPC
dentro un broker si può fare, ma devi avere un motivo e saperlo dire.

### 1.5 🔴 Nessun modulo condiviso: tre volte lo stesso codice — APERTO

`SecureHttpClient` esiste in `Treni` e in `Stazioni`, `main.java` è quasi identico nei tre
moduli, il formato dei messaggi JSON è riscritto a mano in ognuno. E la previsione della
prima stesura si è avverata mentre il documento veniva scritto: la copia della Stazione aveva
un difetto che quella del Treno non aveva (B032, il bean esplodeva senza CA), è stata
corretta — e oggi **le due copie differiscono di 19 righe**. Non sono più lo stesso file.

Stessa storia per l'escaping del JSON: `escapeJson()` esiste **solo** in
`Treni/Sensori.java`. In `Stazioni` non c'è, e infatti la descrizione di un guasto che
contenga una virgoletta produce un messaggio non parsabile che la Centrale scarta in
silenzio. **Le copie divergono sempre, e divergono proprio sulle correzioni.**

Detto questo, la scelta è *parzialmente* difendibile: in un'architettura a microservizi la
condivisione di codice crea accoppiamento, e i puristi preferiscono duplicare che accoppiare.
Ma c'è una cosa che va sempre condivisa: **il contratto**, cioè i nomi dei topic e la forma
dei messaggi.

**Come lo rifarei:** un modulo Maven `railway-contract` con le costanti dei topic, gli enum
degli stati e i `record` dei payload, dipendenza di tutti e tre i servizi. Non condivide
*logica*, condivide *linguaggio*: è il tipo di accoppiamento buono. Da solo avrebbe chiuso
anche §3.1 e §4.1.

---

## 2. Modello dati e persistenza

### 2.1 🔴 Il naming del database — APERTO

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
il suffisso `FK` dentro il nome della colonna, il suffisso `ONO`/`oNormale` che congela nel
nome i valori possibili, e `PosizioneAttualeTrattaOStazione` che **dichiara nel nome stesso
di essere ambiguo** (è una tratta o una stazione? Nel codice è sempre una `Tratta`).

Non è pignoleria: ogni nome storto diventa un `@Column(name = "...")` da ricopiare, una query
in cui sbagli il case, e un pezzo di documentazione da rileggere ogni volta. E il costo si
paga per sempre, perché rinominare una colonna dopo la migrazione è doloroso — motivo per cui
questa critica resta aperta e probabilmente resterà tale: **è già troppo tardi per questo
progetto**, ed è proprio questa la lezione.

**Come lo rifarei:** i nomi nascono dall'analisi ma **vanno normalizzati prima di diventare
DDL**. Una convenzione qualsiasi, purché una sola: tabelle al plurale, `snake_case`, niente
suffissi di tipo, la chiave primaria si chiama `id`, le esterne `<entita>_id`.
`guasti(id, stato, operatore_id, ...)` dice le stesse cose ed è leggibile a colpo d'occhio.

### 2.2 🔴 Una classe, tre mestieri: entità JPA + cache + DTO — APERTO

È il difetto strutturale che genera più conseguenze in tutto il progetto, ed è rimasto
intatto. La classe `Treno`:

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
`TrafficLogicEngine` e da **DTO JSON** restituito da `GET /api/treni`. Tre ruoli con tre cicli
di vita diversi incollati in un unico oggetto. Da lì discende una catena di problemi:

* **Non si capisce più cosa è persistito.** Metà dei campi sono `@Transient` e metà no, e il
  codice deve tenerlo a mente. Chi legge deve andarsi a controllare le annotazioni per sapere
  cosa sopravvive a un riavvio.
* **Ci sono due oggetti per la stessa entità.** In `onTelemetry` convivono `treno` (dalla
  cache, staccato dalla sessione) e `dbTreno` (gestito da Hibernate): due variabili per lo
  stesso treno, e sbagliare quale scrivere è silenzioso.
* **Il JSON dell'API è deciso dalle annotazioni JPA.** Le relazioni `EAGER` finiscono
  serializzate: cambi il fetch type e cambi il contratto dell'interfaccia.

**La prova provata, trovata in questa revisione.** Il comando "sopprimi una corsa" scrive
**due valori diversi per lo stesso stato**:

```java
treno.stato = "SOPPRESSO";        // copia in cache -> è questa che le API restituiscono
dbTreno.stato = "in manutenzione"; // riga di database -> è questo il valore ammesso dal CHECK
```

Per qualche secondo, finché non arriva il frame di telemetria successivo che li riallinea, il
database e la dashboard dicono due cose diverse sullo stesso treno. Non è un bug grave e non
si vede nella demo, ma è **esattamente il difetto di §2.2 che si manifesta**: con tre classi
distinte la domanda "che valore ci metto?" avrebbe avuto una risposta ovvia per ciascuna,
invece di due risposte contemporanee sullo stesso campo.

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

### 2.3 🟡 Cache in RAM davanti al database — PARZIALE

`TrafficLogicEngine` come cache dello stato corrente è una buona intuizione: la telemetria
arriva ogni 5 secondi per treno, scriverla tutta su Postgres sarebbe uno spreco, e la
dashboard vuole leggere velocemente. Il compromesso "**dato volatile in RAM, dato storico su
DB**" è esattamente quello che si fa nei sistemi di monitoraggio veri.

La critica più grave della prima stesura è stata accolta: `onStart()` non si inventa più
`ONLINE`, le stazioni partono `OFFLINE` con `ultimoHeartbeat` nullo e il watchdog salta chi
non ha mai battuto. Il default ottimista, che in un dominio ferroviario è la cosa peggiore,
non c'è più.

Quel che resta aperto è **l'invalidazione**. La cache non ha scadenza né riallineamento
periodico: viene tenuta in pari a mano, endpoint per endpoint, con l'accortezza — giusta, e
ben commentata — di aggiornarla **dopo** il commit, così un rollback non lascia in memoria
valori mai scritti. Ma è disciplina, non struttura: ogni endpoint nuovo deve ricordarsene, e
chi dimentica non se ne accorge, perché il sintomo è un dato sbagliato, non un errore.

**Come lo rifarei:** un solo punto di scrittura che aggiorna DB e cache insieme (un metodo,
non una convenzione), e uno stato runtime che nasce esplicitamente "sconosciuto" (`Optional`
o enum con `UNKNOWN`) finché non arriva il primo messaggio dal campo. Un cruscotto che dice
"non lo so" è affidabile; uno che dice "tutto ok" per default è pericoloso.

### 2.4 🟡 `schema.sql` documentale + `generation=update` — APERTO

La scelta dichiarata è: il DB lo crea Hibernate con `generation=update`, e `schema.sql` serve
come documentazione dello schema. È un compromesso ragionevole per un progetto d'esame, ma
il costo si è già visto una volta: la tabella `eventi_stazioni` esisteva nel codice e non nel
DDL. È stata aggiunta, quindi oggi i due file coincidono — **ma il meccanismo che li fa
divergere è ancora lì**, perché il DDL non viene mai eseguito e quindi nessuno se ne accorge
finché non lo legge un umano.

**Come lo rifarei:** o lo schema è la sorgente di verità e le entità gli si adeguano
(migrazioni con Flyway, `generation=validate` così Hibernate ti *avvisa* se non corrispondono),
o è tutto generato dalle entità e il file `.sql` non esiste proprio. Il caso peggiore è un
file che sembra la verità e non lo è: con `validate` questa critica si chiuderebbe con una
riga di configurazione.

### 2.5 🟢 Le tabelle storiche — CHIUSO

Aver separato lo stato corrente (`Transiti`, `Guasti`) dallo storico (`Storico_Transiti`,
`Storico_Guasti`, `Storico_Stato_Treni`, ...) è corretto e mostra che il modello è stato
pensato, non improvvisato: 15 tabelle di cui 6 di storicizzazione.

Il criterio era applicato in modo incoerente — treni al cambio di stato, stazioni a ogni
battito — e adesso non lo è più: entrambe storicizzano solo quando lo stato cambia davvero.
La regola vale la pena scriverla in relazione perché è generale: **si storicizza al
cambiamento, non al campionamento.** Con 10 stazioni e un battito ogni 10 secondi, la
differenza è fra ~86.000 righe al giorno e qualche decina.

---

## 3. Rappresentazione dello stato

### 3.1 🔴 Stringhe magiche e tre vocabolari paralleli — APERTO

Lo stato di un treno è scritto in tre lingue diverse a seconda di dove ti trovi:

| Dove | Valori |
|------|--------|
| Digital twin (`TrainDB.stato`) | `FERMO`, `IN_VIAGGIO`, `EMERGENZA`, `SOPPRESSO` |
| Database (`Treni.stato`, con CHECK) | `attivo`, `fermo`, `rotto`, `in manutenzione` |
| Frontend (`TrainStatus`) | `in_viaggio`, `in_stazione`, `guasto`, `soppresso` |

e in mezzo tre funzioni di traduzione sparse in tre file diversi
(`IngestionService.normalizzaStatoTreno`, `RestApiGateway.normalizzaStatoTreno`,
`apiClient.mapBackendStatus`). Come se non bastasse, il treno ha **due** variabili di stato
che devono restare coerenti fra loro (`TrainDB.stato` e `TrainDB.faseViaggio`), più un
`fasePrimaDelBlocco` che serve a ricordare da dove riprendere.

Tutto questo è confrontato con `equals` su stringhe letterali sparse ovunque:
`"CRITICAL".equalsIgnoreCase(severita)`, `"ENTRATA".equals(tipo)`,
`"andata".equals(direzione)`, `messaggio.startsWith("Heartbeat assente:")`. Il compilatore
non può aiutarti: un `"ENTRATTA"` scritto male non è un errore, è solo un `if` che non scatta
mai. Due bug reali sono nati esattamente qui (`warning` vs `CRITICAL`, e `"unknown"` che
violava il CHECK), e sono stati corretti — ma **corretti nei valori, non nel meccanismo**:
oggi il codice è giusto e resta altrettanto fragile di ieri.

Gli unici `enum` del progetto sono `EsitoVerifica` nei due validatori. Sono la prova che
sapevi come si fa: sono stati usati dove il dominio era piccolo e ovvio, e non dove serviva.

L'esempio peggiore è quello nuovo di §0.2: la **proprietà** di un guasto — informazione
strutturale, che decide chi ha il diritto di chiuderlo — è codificata nel prefisso di un
messaggio in italiano destinato agli operatori. Basta riscrivere quella frase per rompere la
riconciliazione del fail-stop, e nessun test e nessun compilatore te lo direbbe.

**Come lo rifarei:**

```java
public enum StatoTreno   { FERMO, IN_VIAGGIO, EMERGENZA, SOPPRESSO }
public enum TipoPassaggio { ENTRATA, USCITA }
public enum Severita      { WARNING, CRITICAL }
public enum OrigineGuasto { CAMPO, CENTRALE }   // al posto del prefisso nel messaggio
```

con **una sola** classe di mapping verso il DB e verso il frontend, nel modulo condiviso di
§1.5. E se davvero servono tre vocabolari (a volte è inevitabile, per esempio con un DB
preesistente), la traduzione deve stare in **un solo punto attraversabile**, non in tre file.

**Da portarsi dietro:** in un gestionale gli stati sono il cuore del dominio. Ogni stato
scritto come stringa è un errore che il compilatore non ti segnalerà. Enum sempre, e la
macchina a stati (quali transizioni sono lecite) scritta in un posto solo.

---

## 4. Comunicazione e contratti

### 4.1 🟡 JSON costruito a mano — APERTO

Restano **13 righe** di JSON scritto come stringa letterale (5 in Centrale, 5 nel Treno, 3
nella Stazione), tutte della forma:

```java
String alertJson = String.format(
    "{\"tipoEvento\":\"STOP\",\"target\":\"%s\",\"motivo\":\"%s\",\"timestamp\":\"%s\"}", ...);
```

Va detto che c'è del bene: l'uso di `Locale.US` nei moduli edge è **consapevole e corretto**
(senza, in locale italiano `%.5f` produrrebbe `44,912` e romperebbe il JSON), e la pezza
lato Centrale che rattoppava le virgole decimali è stata eliminata. Però:

* **l'escaping c'è in un modulo su tre** (§1.5): una virgoletta nella descrizione di un
  guasto inviato dalla Stazione produce un JSON non parsabile, scartato in silenzio;
* **il formato non è dichiarato da nessuna parte nel codice**: vive nella documentazione e
  nella testa di chi ha scritto i due lati. Se aggiungi un campo devi ricordarti tutti i
  punti in cui quel messaggio viene costruito e letto.

Il paradosso è che `ObjectMapper` è **già iniettato in tutte le classi che ne avrebbero
bisogno**, e in ingresso viene usato sempre. La costruzione a mano è rimasta solo in uscita,
per inerzia.

**Come lo rifarei:** un `record` per ogni tipo di messaggio nel modulo condiviso di §1.5,
serializzato con l'`ObjectMapper` che c'è già:

```java
public record AlertGuasto(String tipoEvento, OrigineGuasto origine, String sorgenteTipo,
                          String sorgenteId, Severita severita, String messaggio,
                          Instant timestamp) {}
```

Il contratto diventa codice, l'escaping è gratis, il `Locale` non è più un problema e i campi
aggiunti a mano (`origine`, `guastoId`) smettono di essere convenzioni non scritte.

### 4.2 🟡 Niente versione, niente id di correlazione — APERTO

I payload non hanno un campo `version` né un `messageId`. Per un progetto d'esame va bene, ma
è utile sapere perché nei sistemi veri ci sono: la versione permette di far convivere un nodo
vecchio e uno nuovo durante un aggiornamento; l'id permette di riconoscere i duplicati (MQTT
QoS 1 consegna *almeno* una volta, quindi i duplicati sono normali) e di seguire un evento
nei log di tre servizi diversi quando devi capire cosa è successo.

Che l'assenza si faccia sentire non è teoria: **la deduplica dei guasti è stata implementata
a mano** sulla coppia `(sorgenteId, tipo)` perché una stazione guasta manda un alert per ogni
treno che entra. Con un `messageId` sarebbe stata una riga sola, valida per tutti i tipi di
messaggio invece che per i soli guasti. Stessa cosa per il doppio evento di transito di §1.3:
stesso id ⇒ stesso evento ⇒ lo ignoro, e la scelta della sorgente diventava superflua.

### 4.3 🟢 La gerarchia dei topic

`railway/{tipo}/{id}/{funzione}` è una gerarchia pulita e sfrutta bene le wildcard: la
Centrale ascolta `railway/train/+/telemetry` e riceve tutti i treni senza sapere quanti sono,
cosa che rende il sistema **scalabile senza riconfigurazione**. È il modo giusto di usare
MQTT, ed è un punto da far notare all'orale — anche perché è la stessa proprietà che rende
disciplinato il resto: i topic per nodo li scrive la configurazione, non il codice.

---

## 5. Il digital twin: il pezzo migliore, con due riserve

`TrainJourneyEngine` è la parte del progetto che vale di più: una macchina a stati esplicita
(`IN_STAZIONE` → `IN_VIAGGIO` → `IN_STAZIONE`, più `BLOCCATO_GUASTO_STAZIONE`, `EMERGENZA`,
`SOPPRESSO`), inversione al capolinea, interpolazione della posizione, accumulo del ritardo,
congelamento del tempo quando il treno è bloccato. Il fattore di accelerazione configurabile
(`viaggio.fattore.accelerazione=10`) è un'ottima idea pratica: la stessa simulazione è
dimostrabile in 90 secondi o in tempo reale cambiando una property. Anche
`.onOverflow().drop()` sul flusso dei tick è una scelta giusta e non banale: se un tick è
lento non si accumulano arretrati che poi verrebbero eseguiti tutti insieme.

**Riserva 1 — "eventi discreti" è un termine impegnativo.** Il PDF chiede un *motore basato su
eventi discreti*; qui c'è un **tick temporizzato**:

```java
Multi.createFrom().ticks().every(Duration.ofSeconds(1))
```

che a ogni scatto ricalcola dove si trova il treno. È una simulazione *a passo fisso*, non a
eventi discreti: in un motore a eventi discreti c'è una coda di eventi futuri ordinati per
tempo (`arrivo(T+90s)`), si salta direttamente al prossimo evento e non esiste il concetto di
"tick". La scelta a tick è più semplice, più che sufficiente qui, e ha un vantaggio reale (la
posizione GPS interpolata va comunque aggiornata di continuo) — ma va **chiamata con il suo
nome**, perché all'orale è una domanda facile a cui si può rispondere male. La risposta buona
è: *"è a passo fisso, e l'ho scelto perché devo interpolare la posizione a ogni secondo
comunque; a eventi discreti avrei guadagnato solo se avessi dovuto simulare ore di orario in
pochi secondi"*.

**Riserva 2 — due variabili per un solo stato.** `stato` e `faseViaggio` devono restare
coerenti e la coerenza è mantenuta a mano, ricordandosi di aggiornare entrambe in ogni
transizione (più `fasePrimaDelBlocco` per ricordare da dove si riprende). È fragile, e un bug
è nato proprio da una transizione fatta a metà: il treno veniva trattenuto senza entrare nella
fase di blocco, quindi non accumulava ritardo. Il bug è stato corretto, **le due variabili
sono ancora due**. Uno stato solo, e le transizioni concentrate in un solo metodo
(`transizione(nuovoStato)`) che logga e valida: da lì in poi le combinazioni impossibili
diventano impossibili, invece che sconsigliate.

---

## 6. Robustezza: era il punto più debole, oggi è uno dei più solidi — CHIUSO

Questa sezione, nella prima stesura, era la più severa del documento: i pattern di
affidabilità c'erano tutti (heartbeat, store-and-forward, watchdog) ma la gestione degli
errori *dentro* i pattern non era stata pensata. Tre esempi condividevano la stessa radice —
l'errore trattato come qualcosa da loggare, non come qualcosa da cui riprendersi. Oggi tutti
e tre sono chiusi:

```java
// prima: .onFailure().invoke(...)  -> effetto collaterale, stream chiuso per sempre
.onFailure().invoke(e -> { LOG.error(...); dbLocale.connessioneCentrale = false; })
.onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely();
```

Il flusso si riprende da solo con backoff esponenziale; i consumer MQTT hanno try/catch
sull'intero corpo e `ack()` incondizionato, quindi un payload spazzatura non può più
spegnere un canale; e la transazione si apre **dentro** il try, così una violazione di
vincolo resta catturabile invece di risalire al connettore.

Vale la pena capire perché quest'area si è chiusa tutta in una volta mentre le altre no:
erano **difetti locali**. Ogni correzione riguarda una manciata di righe in un solo metodo e
non ha effetti su nient'altro. Le critiche ancora aperte (§2.2, §3.1, §1.5) sono invece
distribuite su decine di file, e per questo costano.

**Da portarsi dietro — tre domande da farsi su ogni componente che parla con l'esterno:**

1. *Se questa chiamata fallisce una volta, cosa succede?* → retry con backoff.
2. *Se fallisce per sempre, cosa succede?* → uno stato degradato dichiarato (il buffer locale
   della Stazione è esattamente la risposta giusta a questa domanda: infatti quel pezzo era
   progettato bene fin dall'inizio).
3. *Se il messaggio è spazzatura, cosa succede?* → si scarta e si va avanti; non deve mai
   poter fermare il flusso.

**Quel che manca ancora**, ed è una scelta di progettazione più che un difetto: il fail-stop
è rilevato con un watchdog a polling che ogni 10 secondi cerca chi non batte da 30. MQTT ha
per questo un meccanismo nativo, il **Last Will and Testament**: il nodo dichiara alla
connessione un messaggio che il broker pubblicherà *per suo conto* se la connessione cade, e
la Centrale lo riceve in millisecondi invece che in decine di secondi, senza scandire nulla.
La versione a watchdog funziona, è più esplicita da spiegare e copre anche il caso "il
processo è vivo ma non manda più dati", che la LWT non vede. È difendibile — ma va detto che
si conosceva l'alternativa, altrimenti sembra che non la si conoscesse.

---

## 7. Sicurezza — PARZIALE

La critica principale della prima stesura è stata **chiusa e va tolta dalla bocca**:
l'autorizzazione oggi è vera. Il token generato dal login viene registrato in `SessioniAttive`
insieme al ruolo, `FiltroAutorizzazione` lo verifica su ogni chiamata sotto `/api`, il
frontend lo manda in `Authorization: Bearer`, le letture sono aperte a entrambi i ruoli, i
tre comandi operativi del tecnico sono permessi a entrambi e le CRUD sono riservate
all'amministratore. Le due `GET` usate dai nodi di campo restano aperte per scelta dichiarata,
perché il digital twin non fa login: si autentica con la validazione dell'ID via MQTT.

Restano aperte cinque cose, in ordine di quanto costa sistemarle:

* **Password in chiaro.** `Utenti.password` è confrontata con `equals` e il seed contiene
  letteralmente `'password'`. È dichiarata come semplificazione della variante 2 del PDF, ed è
  accettabile — ma `BCrypt` sono tre righe e una dipendenza, e mostrano che sai che il
  problema esiste. È il difetto che qualunque esaminatore vede in dieci secondi.
* **WebSocket non autenticata.** `/ws/realtime` accetta chiunque: `@OnOpen` aggiunge la
  sessione al registro e basta. Prima era coerente con il resto (non era protetto niente);
  **ora che le REST sono blindate è un'incoerenza**, ed è la più facile da far notare: gli
  stessi identici dati che `GET /api/treni` protegge con un token viaggiano in chiaro e senza
  credenziali sulla WebSocket, in push, verso chiunque apra la porta 8781. Chiuderla costa
  poco: il token si passa come query param o come sottoprotocollo e si valida in `@OnOpen`
  con lo stesso `SessioniAttive`.
* **Sessioni senza scadenza.** `SessioniAttive` è una `ConcurrentHashMap` senza TTL né
  pulizia: un token vale finché la Centrale è viva. Il riavvio è l'unica scadenza.
* **Broker con `allow_anonymous true`**, anche sul listener TLS 8883: il TLS cifra il canale e
  autentica *il broker verso i client*, ma non identifica chi si connette, quindi chiunque
  raggiunga la porta può pubblicare su `railway/alerts` e sopprimere un treno. **TLS ≠
  autenticazione**: è una distinzione concettuale che vale la pena scrivere in relazione,
  perché mostra che hai capito cosa stai proteggendo e da cosa. Mosquitto supporta
  `password_file` e l'autenticazione a certificati client, e con i certificati li avresti già
  quasi tutti.
* **Tre chiavi private committate** (`ca.key`, `server.key`, `server-centrale.key`). Per una
  demo con una CA usa e getta non è un incidente, ma è un'abitudine da non prendere: vedi §8.3.
* **CORS `origins=*`.** Va ricalibrato rispetto alla prima stesura: con un token `Bearer` (che
  il browser **non** invia da solo, a differenza di un cookie) una pagina ostile non può usare
  le tue credenziali, quindi lo scenario "qualunque sito pilota la Centrale" non c'è più.
  Resta comunque da stringere all'origine del frontend, ed è una riga.

**Da portarsi dietro:** decidere *esplicitamente* il livello di sicurezza — anche "nessuna, è
una demo" — e scriverlo. Il male peggiore è la sicurezza apparente: un token che sembra
proteggere e non protegge è peggio di nessun token, perché chi legge il codice si fida. Questo
progetto ha attraversato tutte e tre le fasi in una settimana (niente → apparente → vera), e
raccontarlo così, in questi termini, è un'ottima risposta all'orale.

---

## 8. Configurazione, build e repository

### 8.1 🟢 I profili Quarkus — CHIUSO

Usare il profilo `%tls` per attivare TLS su MQTT e HTTPS sulla Centrale è la soluzione
migliore possibile per il problema "il prof ammette la variante senza TLS": una sola base di
codice, due configurazioni, si dimostrano entrambe cambiando un flag. Da rifare identico.

L'errore era di **completezza**: i canali di validazione, aggiunti per ultimi, erano rimasti
in chiaro, e sotto profilo `tls` aprivano una socket non cifrata su un listener TLS —
handshake fallito, nodo bloccato nel loop di avvio, e nessun errore comprensibile. Oggi sono
tutti coperti, in tutti e tre i moduli, e sopra l'elenco c'è un commento che spiega la trappola
a chi aggiungerà il prossimo canale. **Il commento vale quanto la correzione**: è la differenza
fra aver riparato un bug e aver evitato il prossimo.

Resta il fatto che la configurazione è ripetitiva per costruzione: tre righe per canale, per
dieci canali, moltiplicato per tre moduli. Non c'è un modo pulito di fattorizzarle in
MicroProfile Config, quindi è un limite dello strumento più che una scelta — ma è la ragione
per cui l'errore era possibile.

### 8.2 🟡 Porte fisse — APERTO

`quarkus.http.port=8082` per i treni e `8081` per le stazioni sono cablate nel file: il
secondo treno sulla stessa macchina non parte, a meno di passare `-Dquarkus.http.port`. In un
sistema il cui punto centrale è "N istanze indipendenti", la porta doveva essere derivata
dall'ID o assegnata dinamicamente (`quarkus.http.port=0`) fin dall'inizio. È un dettaglio, ma
è il dettaglio che si nota **proprio durante la demo**, cioè nel momento peggiore: se
l'esaminatore chiede "fammene partire tre", devi ricordarti l'override a memoria.

### 8.3 🔴 Il repository contiene 19.000 file che non hai scritto — APERTO

```
file tracciati da git:   19.586
  node_modules/:         18.992   (97,0%)
  target/ (build):          358
  scritti a mano:           233   (1,2%)
.gitignore alla radice:  una riga (BrokerMosquitto/data/)
chiavi private:          3 file .key
```

`node_modules`, i `.class`, i `.jar` di Quarkus e le chiavi TLS sono committati. Conseguenze
concrete, non teoriche:

* il repo è enorme e lento, e ogni `git status` mostra decine di artefatti "modificati" che
  nascondono le modifiche vere — si vede anche nel diff attuale, dove i `.class` compaiono in
  mezzo ai sorgenti;
* **ogni build produce un diff**, quindi la cronologia dei commit non racconta più cosa hai
  cambiato davvero;
* le dipendenze committate contengono migliaia di file di terzi: con un professore che dichiara
  di usare strumenti antiplagio, è rumore che non conviene avere nel repo;
* per un progetto d'esame è un segnale di sciatteria che si nota in cinque secondi, e stona
  parecchio con la cura messa nella documentazione.

Il numero da tenere a mente è **233**: sono i file che hai scritto tu. Tutto il resto è
rumore che li nasconde.

**Da portarsi dietro:** il `.gitignore` è la **prima** cosa da scrivere, prima ancora del primo
commit — `target/`, `node_modules/`, `dist/`, `.idea/`, `*.log`, e le chiavi private
(`tls/certs/*.key`). Regola: **nel repository ci va solo ciò che scrivi tu a mano.** Ripulirlo
adesso è comunque possibile (`git rm -r --cached` più un `.gitignore` serio) e costa dieci
minuti: la storia resta sporca, ma da lì in avanti i diff tornano leggibili.

---

## 9. Testing — APERTO

Sei file di test in tutto il progetto: due `ExampleTest` generati dall'archetipo, uno smoke
test, un test su `Guasto`, uno sulle API di amministrazione e un `Card.test.tsx` sul frontend.
Zero test su:

* la macchina a stati del viaggio — la logica più complessa e più fragile del progetto, quella
  che il prof ti chiederà di spiegare;
* il calcolo della prossima stazione e l'inversione al capolinea;
* il ciclo fail-stop: caduta della stazione, apertura del guasto, blocco dei treni, ritorno del
  battito, chiusura del guasto. È **il pezzo di cui vai più fiero** ed è verificato solo a mano,
  a sistema acceso;
* il buffer locale e il suo flush (comportamento richiesto esplicitamente dal PDF);
* il parsing dei messaggi MQTT.

Il paradosso è che il pezzo più testabile del progetto è proprio quello non testato:
`TrainJourneyEngine` è quasi puro (dipende da `TrainDB`, dal tempo e da un gateway), e con tre
stub avresti verificato in pochi minuti che dopo N tick il treno è dove deve essere, che al
capolinea inverte e che bloccato accumula ritardo. Un test del genere avrebbe trovato da solo
il bug del ritardo non accumulato.

C'è anche una ragione pratica, non solo di igiene: le verifiche del fail-stop sono state fatte
a sistema acceso, con i log alla mano, e sono documentate bene in `correzioni_applicate.md` §5.
Ma sono **irripetibili senza rifare tutto a mano**: alla prossima modifica non sai se hai rotto
qualcosa, e la parte che si rompe è quella che non si vede.

**Da portarsi dietro:** non serve testare tutto. Serve testare **il pezzo che contiene le regole
del dominio** — quello che, se sbaglia, produce un risultato *plausibile ma falso*. Gli errori
di configurazione li vedi subito; una regola di business sbagliata no.

---

## 10. Frontend — PARZIALE

Scelte buone: separazione `apiClient` / `websocketClient` / store, tipi TypeScript per i
payload dell'API, riconnessione automatica della WebSocket, funzioni di mapping centralizzate
tra vocabolario backend e frontend. E gli URL non sono più cablati:
`import.meta.env.VITE_API_BASE_URL` e `VITE_WS_URL` con il valore di sviluppo come fallback,
che è il modo standard di farlo con Vite e permette di puntare la Centrale in HTTPS senza
ricompilare.

Restano due critiche, ed è la stessa critica vista da due lati:

* **Nessuna deduplica degli eventi.** Il frontend si fida di ciò che arriva dalla WebSocket e
  usa `Date.now()` come chiave quando manca l'id. Un client realtime deve considerare normale
  ricevere due volte lo stesso evento (§4.2).
* **Nessun rilevamento di stato stantìo.** `lastUpdate` e `lastHeartbeat` sono mappati e
  vengono anche mostrati come orario nelle pagine di dettaglio, ma **non sono usati per
  giudicare**: se la WebSocket cade, i dati restano quelli dell'ultimo aggiornamento e la
  dashboard continua a sembrare viva. Un pannello di monitoraggio deve dire *"dati fermi da 30
  secondi"*, altrimenti mente all'operatore — ed è lo stesso errore concettuale che è stato
  corretto lato Centrale in §2.3, l'ottimismo per default. Il dato per farlo c'è già: manca il
  confronto con l'orologio e un badge rosso.

C'è un'ironia utile da notare: lato server la lezione "non inventare uno stato che non conosci"
è stata imparata e applicata; lato client lo stesso errore è ancora lì. Le lezioni non
attraversano da sole i confini dei moduli.

---

## 11. Documentazione e stile del codice

🟢 **La documentazione è il punto di forza inaspettato.** Una cartella `doc/` con architettura,
casi d'uso, diagrammi di stato (compresa la serie di 13 macchine della Centrale), diagramma dei
componenti, note sui topic MQTT, gap analysis, diagnosi dei bug e registro delle correzioni:
molti progetti d'esame non hanno niente di tutto questo, e per un'interrogazione in cui devi
spiegare le tue scelte vale moltissimo. I Javadoc sulle classi principali spiegano il *perché*
e non solo il *cosa* — che è la parte difficile — e in più raccontano i bug che hanno motivato
il codice attuale, il che rende il progetto **manutenibile da qualcun altro**, cosa rara a
questo livello.

🟡 **Ma i commenti sono anche un diario di lavoro.** Nel codice restano appunti come `// ?`,
`// boh`, `// ma come cazzo lo abbiamo fatto sto modello ??`,
`// ma che cazz di controllo è ?? è davvero necessario`,
`// ATTENZIOONE !!!! RACE CONDITON`. Sono utili mentre lavori, ma nella versione consegnata
diventano due cose insieme: un segnale di codice non finito e — soprattutto — **una mappa
delle domande da farti**, che consegni al professore. Quel `// ATTENZIONE RACE CONDITION`
sopra il `@Scheduled` è un invito esplicito a chiederti conto della concorrenza: se lo lasci,
devi sapere rispondere (e la risposta buona esiste: `concurrentExecution = SKIP` più il
suffisso casuale sulle chiavi dei guasti; ma allora **scrivila lì**, al posto del punto
esclamativo).

Lo stesso vale per i nomi fuori dal codice: un'immagine della documentazione si chiama
`diobastardo.png` ed è linkata dal file `.org` che genera il PDF, e i messaggi di commit sono
sulla stessa linea. Sono cose da cinque minuti, e sono le prime che si vedono.

**Da portarsi dietro:** i dubbi vanno tracciati (in un `TODO.md`, nelle issue, dove vuoi), ma
prima della consegna il codice va ripulito e ogni `?` va trasformato in una risposta — o in una
riga di documentazione che dice "questa cosa non è gestita, ecco perché".

🟡 **Convenzioni Java disattese.** Le classi si chiamano `main` (minuscolo) invece di `Main`; i
tre moduli usano lo stesso package `it.uni.reti2` con tre classi `main` diverse; nei moduli edge
tutte le classi stanno in un package piatto mentre la Centrale è stratificata (`entity`,
`gateway`, `ingestion`, `elaboration`, `DbValidator` — e quest'ultimo con l'iniziale maiuscola).
Nessuna di queste è un bug, ma sono le cose che un revisore nota per prime, e la stratificazione
della Centrale dimostra che sapevi come si fa.

---

## 12. Le lezioni da portare al prossimo gestionale

Una checklist operativa, in ordine di quanto ti fanno risparmiare:

1. **Disegna prima la tabella degli eventi, non quella del database.** Per ogni evento: chi lo
   produce, chi lo consuma, cosa cambia. Se ha due produttori, fermati (§1.3).
2. **Una classe = un mestiere.** Entità di persistenza, oggetto runtime e DTO dell'API sono tre
   cose diverse anche quando hanno gli stessi campi (§2.2).
3. **Gli stati sono enum, mai stringhe** — e questo vale anche per le informazioni *strutturali*
   nascoste dentro un testo, come il prefisso che dice chi possiede un guasto (§3.1).
4. **Il contratto dei messaggi è codice condiviso**, non documentazione: record + costanti dei
   topic in un modulo a parte (§1.5, §4.1).
5. **Pub/sub per le notifiche a molti, REST per le domande dirette.** Non costruire RPC sopra un
   broker senza un motivo forte, e se ce l'hai, sappilo dire (§1.4).
6. **Il topic è il routing.** Se scrivi un `if` per capire se un messaggio ti riguarda, quel `if`
   andava messo nel nome del topic (§1.2).
7. **Per ogni chiamata verso l'esterno rispondi a tre domande:** cosa succede se fallisce una
   volta, cosa se fallisce sempre, cosa se il dato è spazzatura (§6). *Questa lezione il
   progetto l'ha già imparata: è l'unica sezione passata da 🔴 a 🟢.*
8. **Lo stato sconosciuto va rappresentato.** Mai un default ottimista in un sistema di
   monitoraggio: "non lo so" è un'informazione, "tutto ok" inventato è una bugia (§2.3, §10).
9. **`.gitignore` prima del primo commit.** Nel repo solo ciò che scrivi a mano (§8.3).
10. **Testa la logica di dominio**, cioè la parte che può produrre risultati plausibili ma
    sbagliati. Gli errori di configurazione si vedono da soli (§9).
11. **Niente sicurezza finta.** O la fai, o dichiari che non c'è — e se la fai, falla su *tutti*
    i canali, non solo su quelli comodi (§7).
12. **Chiama le cose con il loro nome.** "Motore a eventi discreti" e "simulazione a passo fisso"
    sono due cose diverse: il termine giusto ti protegge all'orale e ti chiarisce le idee mentre
    progetti (§5).
13. **Quando correggi un bug, chiediti di quale decisione è il sintomo** (§0.2). Se la risposta
    è "di nessuna", è un bug e basta. Se invece la correzione consiste nell'aggiungere un campo
    per distinguere i tuoi messaggi da quelli altrui, hai appena messo un cerotto: mettilo, ma
    scrivi da qualche parte di cosa è il cerotto.

---

## 13. Se il prof chiede "cosa rifaresti diverso?"

⚠️ **La risposta della prima stesura non va più bene:** citava le notifiche duplicate dei
transiti, che sono state corrette. Confessare all'orale un bug che non hai più è il modo
peggiore di usare questo documento. Versione aggiornata:

> «Tre cose, in ordine di quanto mi sono costate.
>
> La prima: separerei l'entità JPA dall'oggetto in cache e dal DTO dell'API. Averle unite in
> una classe sola mi ha costretto a riempire le entità di campi `@Transient`, rende ambiguo
> capire cosa viene davvero persistito, e mi lascia ancora oggi un punto in cui lo stesso treno
> ha uno stato diverso a database e in memoria per qualche secondo.
>
> La seconda: userei enum al posto delle stringhe per gli stati, con un modulo condiviso per il
> contratto dei messaggi. Oggi ho tre vocabolari tradotti in tre punti del codice, e — il caso
> peggiore — la proprietà di un guasto è codificata nel prefisso di un messaggio in italiano:
> se qualcuno riscrive quella frase, la chiusura automatica dei guasti smette di funzionare
> senza che nessuno se ne accorga.
>
> La terza: deciderei a monte chi è il proprietario dell'evento di transito. L'ho deciso a
> valle, scegliendo la stazione come unica sorgente per la dashboard, e questo mi ha tolto i
> duplicati; ma i due percorsi esistono ancora e fanno metà del lavoro ciascuno, quindi se il
> processo di una stazione è spento perdo i suoi transiti.
>
> Quello che rifarei uguale è la separazione in processi indipendenti, il digital twin come
> macchina a stati, il buffer locale per lo store-and-forward e il profilo Quarkus per
> dimostrare la variante con e senza TLS.»

E se la domanda è **"cosa hai imparato?"**, la risposta migliore non è nell'elenco dei difetti
ma in §6: il progetto è partito trattando gli errori come qualcosa da loggare ed è arrivato a
trattarli come qualcosa da cui riprendersi. In un sistema distribuito gli errori transitori —
broker che riparte, rete che sfarfalla, messaggio storto — non sono l'eccezione: sono il
funzionamento normale. È l'unica sezione di questo documento passata da "da rifare" a "buona",
ed è successo perché quei difetti erano locali: bastava saperli vedere. Le critiche rimaste
aperte sono tutte strutturali, e quelle si pagano al momento della progettazione o non si
pagano più.

---

*Revisione del 06/08/2026: ogni affermazione è stata riverificata sul codice attuale — i tre
moduli Java, la configurazione MQTT/TLS dei tre `application.properties`, lo schema del
database, la configurazione del broker, il frontend e lo stato del repository git. Le critiche
superate dalle correzioni del 05/08 sono segnate come chiuse in §0.1 anziché rimosse, e le
correzioni che compensano una decisione invece di cambiarla sono raccolte in §0.2.*
