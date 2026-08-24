# Riscontro della documentazione finale con lo schema della relazione

Questo documento confronta
[`DocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario.org`](../documentazioneFinale/DocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario.org)
con [`Schema del rapporto finale.pdf`](../../Schema%20del%20rapporto%20finale.pdf), la guida del
docente su come deve essere strutturato un rapporto tecnico. Lo schema chiede sei capitoli; qui si
controlla, uno per uno, se e dove ciascuno e` coperto.

Aggiornato il 24 agosto 2026, terza passata della giornata. Questa passata ha allineato il `.org` al
**codice**, non allo schema: il capolinea degli itinerari, la sparizione del topic
`railway/train/{id}/passaggio` e le classi dei sensori di binario (vedi la sezione *Allineamento al
codice* in fondo). Sullo schema del rapporto la situazione e` tornata quella del primo riscontro, ed
e` bene dirlo chiaramente:

> I due capitoli `* Analisi delle scelte tecnologiche disponibili` e `* Scelta dell'approccio
> tecnologico`, che la seconda passata aveva creato, **nel file non ci sono piu`**. Il `.org` sul
> disco ha tre capitoli di primo livello (`* Specifica`, `* Progettazione`, `* Validazione`) e il
> materiale di quei due vive dentro `** Scelte progettuali e giustifiacazioni`, riorganizzato a mano
> in `*** Metodi di interfacciamento tra microservizzi`, `*** Sul metodo di repository` e
> `*** Sul sistema di validazione`. Nel passaggio si e` perso il confronto *"Perche' MQTT e non AMQP
> o Kafka"*, che adesso non e` scritto da nessuna parte.

Il motivo e` quasi certamente il salvataggio del buffer Emacs sopra le modifiche fatte da CLI (il
`.org` sta aperto nell'editor). **Prima di toccare il file da riga di comando serve `M-x
revert-buffer`**, altrimenti succede di nuovo.

## Sintesi

| # | capitolo richiesto | stato |
|---|---|---|
| 1 | Specifiche funzionali | coperto, meno la premessa sulle due classi di funzionalita` |
| 2 | Analisi delle scelte tecnologiche disponibili | parziale: il materiale c'e`, ma sparso e senza un capitolo suo |
| 3 | Scelta dell'approccio tecnologico | parziale: c'e` il perche' del broker, non il confronto con le alternative |
| 4 | Architettura del software | coperto, esagoni compresi |
| 5 | Descrizione dell'implementazione | parziale (manca l'interfaccia utente) |
| 6 | Validazione del software | coperto e allineato al codice (86 test) |

## 1. Specifiche funzionali — coperto, meno la premessa

`* Specifica` copre casi d'uso, glossario, RF01-RF04, RNF01-06 e gli scenari di verifica SV01-SV06.

Manca la premessa che lo schema chiede esplicitamente, cioe` le due classi di funzionalita` che
distinguono il progetto (*"controllo locale tramite sensori ed attuatori"* e *"accessibilita` via
Internet di un sistema in rete privata"*): era stata scritta nella seconda passata come apertura del
capitolo 2, ed e` sparita con lui. Sono due paragrafi, e il posto naturale e` l'apertura di
`* Specifica`.

## 2. Analisi delle scelte tecnologiche disponibili — parziale

Lo schema chiede tre cose. Dove stanno oggi:

- **vincoli di rete** (*"reti private senza configurazioni sull'apparato di rete"*): il ragionamento
  su NAT e nodi non raggiungibili da fuori e` dentro `** Scelte progettuali e giustifiacazioni`,
  sezione `*** Metodi di interfacciamento tra microservizzi`, come premessa alla scelta del broker.
  Non e` un capitolo e non copre il lato opposto, cioe` la web app raggiungibile dalla rete pubblica
  alla pari di quella interna: quello non e` verificato da nessuna parte;
- **caratteristiche del traffico dati**: i circa 2.000 messaggi al secondo su diecimila treni e la
  dimensione del payload compaiono in `*** Sul metodo di repository`, come argomento di supporto alla
  cache in RAM, non come caratterizzazione del traffico;
- **vincoli di tempo reale**: sono la tabella `** Parametri temporali` (`P_tel`, `P_bat`, `T_sil`,
  `T_fer`...) dentro `* Progettazione`, con i vincoli fra i parametri.

C'e` quindi tutto il materiale, ma disposto per argomentare le scelte del progetto e non per
rispondere alla domanda dello schema. Serve un capitolo che raccolga le tre voci prima di
`* Progettazione`.

## 3. Scelta dell'approccio tecnologico — parziale

Lo schema chiede: *"Quali vantaggi offre a vostro parere questa scelta [il message broker]? Come la
giudicate rispetto ad altre che avete analizzato nel capitolo precedente?"*.

La prima meta` c'e`: `*** Metodi di interfacciamento tra microservizzi` argomenta il broker contro le
chiamate REST periodiche su tre punti (NAT e nodi non raggiungibili, disaccoppiamento nel tempo con
`LocalBuffer` e QoS 1, fan-out uno-a-molti per RNF05) e chiude con REST, WebSocket, SQLite contro
PostgreSQL e Keycloak.

La seconda meta` no: il confronto con AMQP e Kafka (header di 2 byte contro le intestazioni HTTP,
exchange che qui non servono, Kafka sovradimensionato per una rete di sensori) e` stato cancellato
nella riorganizzazione a mano e adesso non c'e` piu`. Se ne trova il testo nel `.tex` esportato prima
del 24 agosto, oppure nella cronologia git del `.org`.

## 4. Architettura del software — coperto

Il documento ha 68 blocchi PlantUML: 38 diagrammi degli stati (35 macchine piu` le tre mappe), 19 di
sequenza (18 piu` la mappa), 8 delle classi (7 viste piu` la mappa), 2 delle componenti e 1 dei casi
d'uso. Quello che lo schema chiede per l'architettura — moduli, distribuzione della funzionalita`,
attivita` (thread) e come comunicano — c'e` tutto: `** Diagramma delle componenti` per i moduli e le
interfacce, `*** Il componente ... (C1-C6)` per la distribuzione della funzionalita`, i 19 diagrammi
di sequenza per la comunicazione fra componenti con topic e payload reali. I thread e le strutture
condivise sono documentati ma sparsi nelle note dei diagrammi (es. `stazioniGuaste` come `Set`
concorrente, i tre job del `FaultMonitor`, il registro sessioni thread-safe): andrebbero raccolti in
una tabella riassuntiva, ma non e` materiale mancante.

Lo scarto di notazione che i riscontri precedenti segnalavano — `Progetto finale 2025_26.pdf` riga
186, *"completare gli esagoni con le opportune interfacce"* — **e` stato chiuso**: `** Diagramma dei
microservizzi` e` un C4 di livello 2 disegnato con C4-PlantUML in cui i contenitori sono esagoni
(`$baseShape="hexagon"`), con database e coda del broker nella loro forma canonica come nella figura
della consegna. Il diagramma delle componenti in notazione UML classica resta accanto, e va bene
cosi`: sono due letture diverse dello stesso sistema.

## 5. Descrizione dell'implementazione — parziale

Le classi sono documentate bene: `** Diagrammi delle classi` copre C1-C6 con la tabella di riepilogo
requisiti e quella "riepilogo classi - macchine a stati" che incrocia classi e macchine a stati.

Manca pero` la seconda meta` che lo schema chiede esplicitamente: *"Descrizione dell'interfaccia
utente"*. Nel documento `interfaccia utente` compare una sola volta, dentro la tabella dello stack
tecnologico (`React 18, TypeScript, Vite, zustand`). Non c'e` nessuna descrizione delle pagine, di
cosa mostra ciascuna, di come si naviga o di cosa vede in piu` l'amministratore rispetto al
tecnico. Nella cartella delle immagini
(`immaginiDocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario/`) non c'e` nessuno
screenshot dell'app, solo diagrammi PlantUML compilati.

## 6. Validazione — coperto e allineato al codice

Rieseguito `./mvnw test` dalla radice e `npx vitest run` nella web app, per verificare la tabella
contro il codice attuale invece di fidarsi del testo. Risultato: **86 test, tutti superati** (55
Centrale, 11 Treni, 5 Stazioni, 15 web app). La tabella e la frase di apertura in `** Test
automatici` sono state riscritte con questo numero e con la data di oggi, aggiungendo le classi che
la tabella non citava: `ChiusuraGuastiTest`, `PrevisioneArriviTest` e `AlertRitardoTest` nella
Centrale, `RitardoComunicatoTest` nei Treni, `SensoriFailStopTest` nelle Stazioni. I due test in piu`
rispetto alla passata precedente (84) sono quelli del capolinea in `RiaggancioItinerarioTest`.

Nota sul metodo: contare `grep -c "@Test"` nel codice da` numeri quasi doppi (es. `AdminApiTest` 26
invece di 13), perche' il grep prende anche le righe `@TestSecurity(...)`. I numeri della tabella
vengono dall'esecuzione reale (`Tests run: N` dei report Surefire), non dal grep.

E` stato corretto anche il limite dichiarato *"il nodo Stazione non ha test propri"*: non e` piu`
vero, `SensoriFailStopTest` ha 4 test reali (id dei sensori, keepalive, fail-stop e ritorno in
servizio). Riscritto in *"resta il meno coperto"*, restringendo il limite a quello che e` ancora
vero: `LocalBuffer` (la coda locale e il suo svuotamento durante un'interruzione di rete, SV02) non
ha un test proprio.

Restano gli unici due residui gia` segnalati: gli scenari SV01-SV06 e i requisiti non funzionali sono
descritti con "come si provoca" e "dove si osserva" ma non ancora con l'esito osservato, e vanno
eseguiti a mano sul sistema acceso con la colonna dell'esito da compilare.

## Allineamento al codice (24 agosto 2026)

Non riguarda lo schema del rapporto ma la corrispondenza fra documento e sorgenti, ed e` il grosso di
questa passata. Tre cose erano cambiate nel codice e non nel `.org`:

1. **Il capolinea degli itinerari.** Un itinerario puo` marcare un estremo come capolinea: chi ci
   arriva chiude la corsa (stato `capolinea`, fase `FERMO_CAPOLINEA`) e riparte solo con un
   itinerario nuovo. Toccati: glossario, RF01.3.4, RF02.2.3, RF02.2.4, SV06, vocabolario degli stati,
   `** Modello` (colonna `Itinerari.capolinea`, CHECK di `Treni.stato`, script
   `migrazione_capolinea.sql`), macchine T3, T5, T6, T7 e M13, sequenze SQ03 e SQ16, classi C1, C3,
   C4b e C6.
2. **Il topic `railway/train/{id}/passaggio` non esiste piu`.** Il treno annuncia il passaggio ai
   sensori di binario su `railway/treno-simulatoriSensori/{id}`; e` la stazione, dopo che il sensore
   l'ha rilevato via REST, a mandare il transito ufficiale alla Centrale. In Centrale sono spariti il
   canale `passaggio-in`, il consumatore `onPassaggio` e il record `PassaggioMsg`. Toccati: RF03.3
   (con il limite dichiarato sulla direzione, che ora si conosce solo dalla telemetria), `** Topic`,
   macchine S8 e M5, sequenza SQ06 (riscritta), classi C4a e C4b e le tabelle di riepilogo.
3. **Le classi dei sensori di stazione**, comparse col commit degli eventi domino e mai documentate:
   `SimulatoreSensori`, `SensoreBinario`, `MonitorSensori`, `ConsoleSensori`, `LogSuFile`. Aggiunte a
   C2 e usate per riscrivere S7, che attribuiva ancora la sorveglianza dei keepalive a
   `HeartbeatGenerator` e descriveva il vecchio comportamento (sensore cancellato dalla mappa, quindi
   nessun ritorno automatico in servizio).

E` stata anche ricucita una riga rimasta a meta` in `*** Sul sistema di validazione` (il paragrafo sul
database H2 dei test finiva con "artiva affatto").
