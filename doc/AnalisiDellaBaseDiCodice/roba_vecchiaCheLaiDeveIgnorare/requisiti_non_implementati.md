# Requisiti non ancora implementati — 17/08/2026

Elenco dei requisiti che oggi il sistema **non** soddisfa per intero. La lista parte dai requisiti
scritti nella relazione (capitolo *Requisiti*), ma non è una copia dei loro campi «stato di
implementazione»: ogni voce l'ho riaperta sul codice per verificare che il buco ci sia davvero e per
scrivere **dove** sta e **cosa servirebbe** per chiuderlo.

Il criterio è quello della relazione stessa: *parziale* vuol dire che una parte del requisito è
soddisfatta e una no; *non realizzato* vuol dire che nessuna parte del sistema lo soddisfa. I
requisiti di raggruppamento (RF01, RF02.1, …) non hanno una voce propria: sono parziali per
decomposizione, cioè perché lo è una delle foglie sotto di loro, e li riassumo in fondo.

## Riepilogo

| #   | requisito     | stato          | in due parole                                                                     |
|-----|---------------|----------------|-----------------------------------------------------------------------------------|
| N01 | RF02.1.2.2.1  | non realizzato | Il convoglio che si guasta in stazione non rende la stazione non percorribile      |
| N02 | RF02.1.2.2.2  | non realizzato | Il convoglio che si guasta in tratta non rende la tratta non percorribile          |
| N03 | RF02.5.2      | non realizzato | Dopo la modifica dell'itinerario il convoglio riparte dal capolinea, non da dov'era |
| N04 | RF01.4.1      | parziale       | La stazione passa in MANUTENZIONE e torna ONLINE nella stessa chiamata              |
| N05 | RF01.2.2      | parziale       | L'operatore non distingue il guasto segnalato dal guasto dedotto                    |
| N06 | RF01.4.3      | parziale       | La soppressione non verifica che il convoglio sia fermo in stazione                 |
| N07 | RF02.1.1.2.3  | parziale       | Il convoglio già in sosta è trattenuto dal controllo alla ripartenza, non dalla stazione |
| N08 | RF04.3        | parziale       | Il canale verso Keycloak resta in chiaro                                            |

I primi tre sono quelli veri: sono funzioni che mancano. Dal quarto in poi la funzione c'è e a
mancare è un pezzo del suo comportamento.

---

## N01 — RF02.1.2.2.1: il guasto di bordo in stazione non contagia la stazione

**Enunciato**: se il convoglio si guasta mentre è fermo in una stazione, quella stazione risulta
guasta (cioè non percorribile dagli altri convogli).

**Cosa c'è.** L'allarme arriva, viene registrato e il convoglio si congela: `IngestionService.onAlert`
apre il `Guasto` con `sorgenteTipo = TRENO` e poi chiama `marcaSorgenteGuasta`.

**Cosa manca.** `marcaSorgenteGuasta` (`ServeCentraleOperativa/.../ingestion/IngestionService.java:632`)
marca **solo la sorgente**: se è una STAZIONE la porta a `GUASTA`, se è un TRENO tocca il treno e
basta. Nessuno guarda *dove si trova* il convoglio che si è guastato. Il buco è già annotato nel
codice con un TODO a `IngestionService.java:609`.

Conseguenza osservabile: dopo un guasto di bordo la stazione resta `ONLINE`, gli altri convogli ci
entrano tranquillamente e il punto 1 dello scenario SV03 non si verifica.

**Cosa servirebbe.** Dentro `marcaSorgenteGuasta`, nel ramo `TRENO` e solo sui `CRITICAL`: leggere la
posizione del convoglio dalla cache (`statoRete.getTreno(sorgenteId)`), e se il convoglio è fermo in
una stazione portare quella stazione a `GUASTA`, con lo stesso trattamento che riceve oggi un guasto
dichiarato dalla stazione (broadcast dello stato + avviso ai convogli, così i treni in arrivo si
fermano). Serve poi decidere la chiusura: quando il guasto del convoglio si risolve, la stazione
deve tornare percorribile solo se non ha guasti propri aperti.

---

## N02 — RF02.1.2.2.2: il guasto in tratta non rende la tratta non percorribile

**Enunciato**: se il convoglio si guasta fra due stazioni, è la tratta elementare che sta percorrendo
a risultare non percorribile; nessuna stazione cambia stato.

**Cosa manca.** Qui il problema è più a monte di N01: **la tratta non ha nessuno stato di
percorribilità**. In `ServeCentraleOperativa/src/main/resources/schema.sql:19-26` la tabella `Tratte`
ha solo partenza, arrivo e tempo di percorrenza; non c'è una colonna da cambiare, e infatti non c'è
nessun punto del codice che la cambierebbe. Il convoglio bloccato resta lì, la tratta continua a
risultare libera e nessun altro viene fermato.

Vale anche per l'allarme che la centrale deduce da sola quando un convoglio è fermo troppo a lungo
in tratta (RF02.6.2): l'allarme si apre, ma non ha conseguenze sull'infrastruttura.

**Cosa servirebbe.** Tre pezzi, in ordine:

1. uno stato sulla tratta (colonna `percorribile` o `stato` su `Tratte`, più il campo in cache);
2. la propagazione in `marcaSorgenteGuasta`: se il convoglio guasto è in tratta, marcare quella
   tratta invece della stazione (è il ramo `else` di N01);
3. il controllo lato convoglio, che è la parte che oggi non esiste per niente: il digital twin sa
   fermarsi per una **stazione** guasta (`TrainJourneyEngine.bloccaPerGuastoStazione`, riga 513, con
   l'elenco `trainDB.stazioniGuaste`) ma non ha l'equivalente per una tratta. Andrebbe aggiunto un
   `trainDB.tratteGuaste` con lo stesso meccanismo e il controllo prima di impegnare la tratta.

Da fare insieme a N01: condividono il punto (2) e la logica di chiusura.

---

## N03 — RF02.5.2: il convoglio riparte dal capolinea invece che da dove si trovava

**Enunciato**: se dopo la modifica dell'itinerario il convoglio è ancora assegnato, prosegue sul
nuovo itinerario **a partire dal punto in cui la modifica lo ha colto**, conservando direzione e
ritardo accumulato. La posizione mostrata non deve compiere salti.

**Cosa c'è.** L'avviso arriva davvero: la centrale pubblica `ITINERARIO_AGGIORNATO`, il gateway di
bordo chiama `richiediRicaricaItinerario()` (`Treni/.../TrainGateway.java:129`) e al tick successivo
l'itinerario nuovo viene scaricato.

**Cosa manca.** La ricarica **azzera il viaggio**. In `TrainJourneyEngine.tick()`, righe 159-167:

```java
trainDB.itinerario = new ArrayList<>();
trainDB.itinerarioId = null;
viaggioAvviato = false;
```

con `viaggioAvviato = false` il tick successivo passa da `avviaViaggio()` (riga 322), che rimette
`indiceStazione = 0`, `direzione = "andata"` e la posizione sulla prima tappa. Il ritardo accumulato
sopravvive perché sta in un altro campo, la posizione no: sulla mappa il convoglio salta al
capolinea, che è esattamente ciò che il requisito voleva evitare. In pratica il sistema si comporta
**sempre** come nel caso limite previsto dalla specifica (stazione di ripresa non più presente),
anche quando un punto di ripresa sensato ci sarebbe.

**Cosa servirebbe.** Dopo aver ricaricato l'itinerario, invece di far ripartire il viaggio da zero:
cercare nella nuova sequenza la stazione da cui il convoglio è appena passato
(`trainDB.stazioneCorrente`); se c'è, riposizionare `indiceStazione` su quel punto conservando
`direzione`, fase e progresso; solo se non c'è ricadere sull'`avviaViaggio()` di adesso, che a quel
punto è il comportamento giusto e non più il ripiego universale.

Nota: accanto a quel codice c'è già un commento del progetto (riga 271) che segnala che sulla
ricarica manca anche l'attesa che l'itinerario nuovo sia effettivamente disponibile.

---

## N04 — RF01.4.1: la manutenzione non dura

**Enunciato**: l'operatore manda la squadra su una stazione guasta; la stazione **risulta** in
MANUTENZIONE e i nodi interessati ne sono informati.

**Cosa c'è.** `RestApiGateway.dispacciaManutenzione` (riga 943) fa quasi tutto: porta la stazione a
`MANUTENZIONE`, avvisa il campo con `MAINTENANCE_DISPATCHED`, chiude i guasti aperti della stazione e
scrive la riga in `Storico_Interventi_Manutenzione`.

**Cosa manca.** Il rientro in servizio sta **dentro la stessa chiamata**, una trentina di righe più
sotto:

```java
stazione.stato = "ONLINE";
```

Lo stato `MANUTENZIONE` attraversa il canale ma dura i millisecondi del metodo: nessuno che guardi le
schermate vede mai la stazione in manutenzione. Il commento nel codice lo dichiara come limite noto,
non come dimenticanza — ma per il requisito resta un pezzo mancante, e il punto 5 di SV01 non è
verificabile.

**Cosa servirebbe.** Staccare il rientro dal comando: il `POST /stazioni/{id}/manutenzione` lascia la
stazione in `MANUTENZIONE` e si chiude lì; il ritorno a `ONLINE` lo fa un secondo comando (fine
intervento) oppure il nodo di campo quando torna a battere. La riga di
`Storico_Interventi_Manutenzione` è già predisposta per questo: ha `ts_invio` e `ts_rientro`
separati, e oggi vengono scritti insieme.

---

## N05 — RF01.2.2: guasto segnalato e guasto dedotto sono indistinguibili per l'operatore

**Enunciato**: l'elenco distingue il guasto *segnalato dal campo* da quello *dedotto dalla centrale*,
perché sono due condizioni con cause diverse e reazioni diverse (un guasto dedotto può essere solo un
problema di collegamento).

**Cosa c'è.** In centrale la distinzione esiste di fatto: i guasti segnalati nascono in
`IngestionService.onAlert`, quelli dedotti in `FaultMonitor.creaGuastoAutomatico` (riga 258).

**Cosa manca.** Due cose, e la seconda peggiora la prima:

1. il DTO di `GET /api/allarmi` (`RestApiGateway.java:764-786`) non ha **nessun campo sull'origine**:
   manda `id, tipo, severita, messaggio, sorgenteId, sorgenteTipo, timestamp, risolto,
   timestampRisoluzione, operatore`. Il dato non esiste proprio, lato schermata;
2. neanche il `tipo` supplisce, perché `tipoAllarmePerFrontend` (riga 1299) collassa i casi: il
   fail-stop dedotto su una stazione e il sensore guasto segnalato *dalla* stazione escono con lo
   stesso valore.

**Cosa servirebbe.** Poco: una colonna `origine` (`SEGNALATO` / `DEDOTTO`) sul guasto, valorizzata nei
due punti di creazione, esposta nel DTO e mostrata come etichetta nella pagina Allarmi. È il gap con
il rapporto costo/valore migliore degli otto, e chiude anche il punto 4 di SV03 e il punto 2 di SV04.

---

## N06 — RF01.4.3: la soppressione non verifica la precondizione

**Enunciato**: l'operatore annulla una corsa e il convoglio passa in SOPPRESSO,
**a condizione che sia fermo in una stazione**.

**Cosa manca.** `RestApiGateway.sopprimiTreno` (riga 905) controlla solo che il treno esista: nessuna
verifica sulla fase del viaggio. Il comando sopprime anche un convoglio in mezzo a una tratta, e la
sopprimibilità resta affidata al buon senso di chi preme il bottone.

**Cosa servirebbe.** Un controllo sulla posizione dalla cache prima di procedere, e un `409 Conflict`
con la spiegazione se il convoglio è in tratta — lo stesso trattamento che l'API dà già ai rifiuti di
RF01.3.5. Volendo, il bottone si disabilita anche lato schermata, ma il controllo deve stare
sull'API: è lì che il requisito si verifica.

*Osservazione emersa leggendo il metodo, fuori dal requisito*: sulla cache lo stato scritto è
`"SOPPRESSO"`, sul database `"in manutenzione"` (righe 911 e 919). I due stati non coincidono, e il
valore persistito non dice quello che è successo.

---

## N07 — RF02.1.1.2.3: il convoglio in sosta è trattenuto per la strada sbagliata

**Enunciato**: un convoglio già in sosta nella stazione che si guasta non riparte finché il guasto è
aperto.

**Cosa c'è.** L'effetto si ottiene: il convoglio non riparte. Ma per una strada diversa da quella del
requisito — il convoglio tiene un elenco locale delle stazioni che ha *sentito* dichiararsi guaste
(`trainDB.stazioniGuaste`) e lo ricontrolla quando sta per ripartire
(`TrainJourneyEngine.java:369-383`).

**Cosa manca.** È il controllo alla ripartenza a tenerlo fermo, non la stazione che smette di essere
percorribile. La differenza si vede quando l'avviso non gli arriva: un convoglio che ha perso il
messaggio riparte da una stazione guasta senza che niente glielo impedisca. La proprietà richiesta è
dell'infrastruttura, la realizzazione è del convoglio.

**Cosa servirebbe.** È lo stesso lavoro di N01/N02 visto dall'altro lato: se la percorribilità
diventa uno stato della stazione/tratta che il convoglio deve **interrogare** (o che gli viene
riconfermato periodicamente e non una volta sola), il caso del messaggio perso si chiude da solo.
Finché resta un elenco locale riempito dagli avvisi, la garanzia vale solo se nessun avviso si perde.

---

## N08 — RF04.3: il canale verso il servizio di identità resta in chiaro

**Enunciato**: il sistema può funzionare con **tutti** i canali protetti; o lo sono tutti, o la
garanzia non vale.

**Cosa c'è.** Con il profilo apposta, MQTT gira sulla porta cifrata (compresi i canali con cui i nodi
si fanno riconoscere) e la centrale è raggiunta in HTTPS.

**Cosa manca.** Keycloak è avviato in `start-dev`, cioè HTTP in chiaro sulla 8080 del container
(`docker-compose.yml:22-33`), e la centrale lo interroga così:

```
quarkus.oidc.auth-server-url=http://localhost:8180/realms/railway
```

(`ServeCentraleOperativa/src/main/resources/application.properties:23`). È l'unico tratto in chiaro
quando tutti gli altri sono protetti, ed è proprio quello su cui viaggiano le credenziali.

**Cosa servirebbe.** Avviare Keycloak con un certificato (`start` con `KC_HTTPS_*` al posto di
`start-dev`) e spostare `auth-server-url` su `https`, insieme al redirect della schermata di login.
È già dichiarato come limite noto in relazione: qui va detto solo che il requisito, così, non è
chiuso.

---

## I rami che risultano parziali per decomposizione

Non sono buchi in più: sono gli stessi otto di sopra visti dall'alto. Li elenco perché nella
relazione compaiono con uno stato proprio e sarebbe strano non ritrovarli qui.

| ramo         | è parziale a causa di            |
|--------------|----------------------------------|
| RF01         | RF01.2 e RF01.4                  |
| RF01.2       | N05 (RF01.2.2)                   |
| RF01.4       | N04 (RF01.4.1), N06 (RF01.4.3)   |
| RF01.3.4     | RF02.5, cioè N03                 |
| RF02         | RF02.1 e RF02.5                  |
| RF02.1       | N07, N01, N02                    |
| RF02.1.1     | N07 (RF02.1.1.2.3)               |
| RF02.1.1.2   | N07                              |
| RF02.1.2     | N01 e N02                        |
| RF02.1.2.2   | N01 e N02 (non realizzato)       |
| RF02.5       | N03 (RF02.5.2)                   |
| RF04         | N08 (RF04.3)                     |

Tutto il resto — RF01.1, RF01.3 (tranne il rimando qui sopra), RF01.6, RF02.2, RF02.3, RF02.4,
RF02.6, RF02.7, RF03 per intero, RF04.1 e RF04.2 — risulta completo, e la lettura del codice non ha
fatto emergere requisiti dichiarati completi che invece non lo sono.

## Se si dovesse scegliere cosa chiudere

Nell'ordine, per rapporto fra lavoro e requisiti che si chiudono:

1. **N05** — mezza giornata: una colonna, due assegnazioni, un campo nel DTO, un'etichetta. Chiude
   RF01.2 per intero e sblocca due scenari di verifica.
2. **N06** — un `if` e un `409`. Chiude RF01.4.3.
3. **N04** — separare il rientro dal comando. Chiude RF01.4.1, e con il 2 chiude tutto RF01.4 e
   quindi RF01.
4. **N01 + N02 + N07** — vanno fatti insieme: sono la propagazione del guasto all'infrastruttura, e
   N02 chiede prima uno stato sulla tratta che oggi non esiste. È il blocco più grosso, ma chiude
   RF02.1 per intero.
5. **N03** — ripresa dal punto in cui il convoglio si trova. Da solo chiude RF02.5.
6. **N08** — è configurazione, non codice, e va fatta quando si prepara la consegna con il profilo
   protetto.
