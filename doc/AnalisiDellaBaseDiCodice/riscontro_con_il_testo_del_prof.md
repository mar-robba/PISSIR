# Da far modellare all'ai come set di RF da mettere nel .org


# Riscontro fra il testo del docente e il codice

Verifica del 21 agosto 2026: rilettura di
[`Progetto finale 2025_26.pdf`](../Progetto%20finale%202025_26.pdf) frase per frase, con
riscontro diretto sul codice. Qui sotto stanno solo i punti in cui il sistema **non**
corrispondeva a quello che il PDF chiede; tutto il resto e` coperto ed e` elencato in fondo.

**Aggiornamento dello stesso giorno**: i tre buchi veri (1.1, 1.2, 1.3) sono stati chiusi,
e con loro la 1.4 (il ritardo comunicato al convoglio), che era la conseguenza diretta di
avere spostato il calcolo in Centrale. Dopo di loro sono stati implementati anche i sensori
di binario che mandano il keepalive da soli (era la vecchia 2.1) e il guasto circoscritto a
una via della stazione (`binario_guasto`, era meta` della vecchia 2.2). La sezione 1 racconta
come, e con quali limiti dichiarati.

**Aggiornamento del 24 agosto 2026**: chiusa anche la 2.2, il capolinea che non era mai un fine
corsa (voce 1.7 qui sotto). Nella stessa giornata e` cambiata la strada del passaggio in stazione:
il treno non lo dichiara piu` alla Centrale su un topic suo, lo annuncia ai sensori di binario ed
e` la stazione a certificarlo. Resta aperta la sola voce 2.1, i guasti alle linee.

A differenza di [`requisiti_da_implementare.md`](requisiti_da_implementare.md), che parte dai
requisiti RFxx ricavati dall'analisi del codice, questo documento parte dal testo della consegna:
serve a rispondere alla domanda "che cosa chiede il prof che io non ho fatto".

## Sintesi

| # | richiesta del PDF | stato | dove |
|---|---|---|---|
| 1.1 | previsioni di arrivo (Processing dell'OCC) | **fatto**: le calcola la Centrale e viaggiano su REST, SNAPSHOT e WebSocket | `PrevisioneArrivi.java`, `Treno.arrivoPrevisto` |
| 1.2 | calcolo dei ritardi (Processing dell'OCC) | **fatto**: spostato sulla Centrale, il convoglio non ne tiene piu` copia | `PrevisioneArrivi.registraArrivo`, `MonitoraggioTreni.registraPassaggio` |
| 1.3 | treni attesi in ingresso/uscita in una stazione | **fatto**: endpoint della Centrale calcolato sull'itinerario | `GET /api/stazioni/{id}/treni-attesi` |
| 1.4 | il treno riceve guasti *o ritardi* dalla centrale | **fatto**: alert `RITARDO` con target il convoglio, pubblicato quando la misura cambia | `IngestionService.pubblicaRitardoSuMqtt`, `TrainGateway.riceviAlert` |
| 1.5 | keepalive dei sensori verso la stazione | **fatto**: i due sensori di binario sono simulati come dispositivi a se`, battono da soli e si spengono dalla CLI | `SimulatoreSensori.java`, `MonitorSensori.java`, `ConsoleSensori.java` |
| 1.6 | guasto a una particolare via della stazione | **fatto**: tipo `binario_guasto`, dichiarato da chi lo segnala | `StationIngestion./sensore/guasto`, `ConsoleSensori.java:150` |
| 1.7 | il treno puo` entrare in stazione senza uscirvi (capolinea) | **fatto** il 24 agosto 2026: l'itinerario puo` marcare un estremo come capolinea, e chi ci arriva chiude la corsa | `Itinerario.capolineaId`, `TrainJourneyEngine.java:752` |
| 2.1 | guasti alle linee (alle tratte fra due stazioni) | non esistono: la sorgente di un guasto e` solo una stazione o un treno | `Guasto.sorgenteTipo` |
| — | resto della consegna (ingestion, caching locale, heartbeat stazione, T_fer/T_sil, guasto permanente, prossima stazione in entrambe le varianti, storico, ruoli tecnico/amministratore, REST+webapp, OAuth2, TLS) | coperto | — |
| — | varianti ammesse usate | solo la 1 (web app anziche` SPA), zero penalita` | — |

Zero buchi reali: i sei punti della prima sezione sono stati implementati il 21 agosto 2026, il
settimo il 24. Resta una sola scelta difendibile ma parziale (2.1); tutto il resto era gia` coperto.

## 1. Buchi chiusi (21 e 24 agosto 2026)

Erano i soli punti della consegna senza implementazione: i primi sei chiusi il 21 agosto, il
settimo (il capolinea) il 24. Restano scritti qui, con il testo del PDF a cui rispondono, perche`
sono le domande piu` probabili in sede di prova.

### 1.1 Previsioni di arrivo — fatto

Il PDF, fra i compiti della Centrale Operativa: *"Processing: Calcolo dei ritardi e
previsioni di arrivo"*.

Il calcolo sta in `ServeCentraleOperativa/src/main/java/it/uni/reti2/elaboration/PrevisioneArrivi.java`.
La Centrale conosce l'itinerario del convoglio e il tempo di percorrenza di ogni tratta, e
la telemetria le dice quanta strada ha gia` fatto (`progressPercent`): sommando la parte di
tratta che resta, i tempi delle tratte successive e la sosta di ogni fermata intermedia si
ottiene quanto manca all'arrivo. Il percorso di ogni itinerario viene letto dal database
una volta sola e tenuto in memoria — la telemetria arriva ogni secondo per ogni treno — e
la copia viene buttata quando l'amministratore tocca tratte o itinerari
(`PrevisioneArrivi.itinerariCambiati()`, chiamata dal `RestApiGateway`).

Il risultato viaggia in due unita` diverse, che e` il punto meno ovvio di tutto il
meccanismo:

- `Treno.arrivoPrevisto` e` un **istante reale**, quello che il browser stampa come ora del
  giorno;
- `Treno.minutiAllArrivo` sono **minuti dell'orario di servizio**, gli stessi in cui e`
  espresso il ritardo.

I due numeri non coincidono perche` la simulazione corre accelerata: con
`viaggio.fattore.accelerazione=100` un minuto di orario dura 0,6 secondi reali. Se si
mostrasse l'orario di servizio come ora del giorno, la dashboard annuncerebbe l'arrivo
"fra 12 minuti" per un treno che entra in stazione dopo nove secondi.

Essendo campi `@Transient` dell'entita`, escono da soli in `GET /api/treni` e nello
SNAPSHOT periodico; sull'evento `TELEMETRY` li scrive `IngestionService.scriviPrevisione()`,
anche quando sono vuoti (per un treno soppresso il browser deve **cancellare** la stima
precedente, non tenersela). Nel frontend `mapApiTrain` non mette piu` la costante
`arrivalTime: null`, quindi i riquadri "ETA" (pagina Stazioni), "Arrivo prev." (pagina
Treni) e "Arrivo previsto" (mappa), che erano codice morto, adesso compaiono davvero.

*Limiti dichiarati*: la previsione guarda avanti fino al capolinea e non oltre
l'inversione di marcia; della sosta in corso, quando il treno e` gia` fermo in stazione,
non si scala la parte gia` trascorsa (la Centrale sa quanto dura una fermata, non da
quanto e` cominciata).

### 1.2 Il ritardo lo calcola la Centrale — fatto

Il PDF mette il calcolo dei ritardi nel Processing dell'OCC. Adesso e` li`, e il conteggio
di bordo e` stato tolto del tutto: `TrainDB.ritardoMinuti` non esiste piu`, non compare nel
payload di telemetria, nel payload dei passaggi ne` in `/treno/viaggio`.

Come funziona: il convoglio esce da una stazione e la Centrale gli fissa l'orario in cui
deve presentarsi alla fermata successiva (`programmaArrivo`, tempo di percorrenza della
tratta a partire dall'ora vera dell'uscita); quando entra, confronta l'orario reale del
passaggio con quello fissato e somma la differenza al ritardo (`registraArrivo`). L'orario
si calcola dall'uscita reale e non da un orario teorico, cosi` il ritardo che il convoglio
si porta dietro non viene contato una seconda volta a ogni tratta: ogni tratta misura solo
il tempo perso li`.

Il tempo perso in banchina si misura allo stesso modo: alla partenza la Centrale confronta
quanto e` durata la sosta (fra l'ENTRATA e l'USCITA, che le arrivano entrambe) con quella
prevista, doppia se la stazione e` un capolinea, e conta l'eccesso come ritardo. Senza
questo pezzo il caso piu` frequente di tutti — il convoglio trattenuto da una stazione
guasta — sarebbe sfuggito: l'orario della tratta successiva viene fissato a partire
dall'uscita reale, quindi un treno fermo mezz'ora e poi ripartito percorrerebbe la tratta
in perfetto orario e risulterebbe puntuale.

Il fenomeno quindi resta lo stesso di prima — un treno trattenuto accumula ritardo — ma
adesso a misurarlo e` chi di dovere. Il twin, dalla sua, si limita a restare fermo
(`tickBloccato` congela il progresso e basta). Una corsa **soppressa** invece non accumula
niente: e` cancellata, non in ritardo, e infatti `sopprimiTreno` butta via il suo orario e
l'ora del suo ingresso in stazione.

Due dettagli che valgono una domanda:

- **la tolleranza di 2 minuti**. Il twin controlla la propria posizione una volta al
  secondo, quindi annuncia l'arrivo con un ritardo di campionamento che arriva fino a un
  tick; con il fattore 100 un secondo reale vale gia` quasi due minuti di servizio. Senza
  tolleranza ogni convoglio accumulerebbe un paio di minuti a ogni fermata pur viaggiando
  in perfetto orario.
- **`direzione` nel payload del passaggio**. All'estremo dell'itinerario il convoglio inverte
  la marcia *prima* di annunciare l'ENTRATA: la Centrale se ne accorge solo col frame di
  telemetria successivo, e nel frattempo cerca la prossima fermata dalla parte sbagliata. Il
  verso viaggiava per questo nel messaggio del passaggio, che il treno mandava alla Centrale.
  **Dal 24 agosto 2026 quel topic non c'e` piu`** (il passaggio e` diretto ai sensori di
  stazione, e la stazione certifica il transito senza il verso), quindi la Centrale legge la
  direzione dalla cache aggiornata dall'ultima telemetria, con "andata" come ripiego: nella
  finestra fra l'inversione e il frame successivo, fino a `P_tel`, il verso e` ancora quello
  vecchio. E` il prezzo dichiarato di aver fatto certificare il passaggio al sensore invece che
  al treno.

*Limite dichiarato*: tabella di marcia e ritardo stanno in RAM (`Treno.ritardo` era gia`
`@Transient`), quindi al riavvio della Centrale il conteggio riparte da zero e il primo
passaggio dopo l'accensione non produce misura, non avendo un orario con cui confrontarsi.

### 1.3 I treni attesi si leggono sull'itinerario — fatto

Il PDF, fra i compiti del tecnico: *"quali treni ci si aspetta in ingresso e in uscita in
una certa stazione"*.

C'e` un endpoint della Centrale, `GET /api/stazioni/{id}/treni-attesi`, che per ogni
convoglio dell'itinerario che tocca quella stazione risponde con il tipo (`in_arrivo` per
chi deve ancora passare, `in_partenza` per chi e` fermo li` e ne uscira`), i minuti
mancanti, l'orario previsto, quante fermate fara` prima e il ritardo che si porta dietro,
il tutto ordinato per tempo mancante.

La differenza con prima e` quella che il PDF chiede: l'elenco lo costruiva il browser
filtrando i convogli la cui posizione istantanea coincideva con la stazione, quindi si
vedeva solo chi stava gia` arrivando dalla tratta adiacente; adesso compare anche il treno
che passera` di li` fra tre fermate, con l'ora in cui e` atteso. La pagina Stazioni
interroga l'endpoint quando si seleziona una stazione e lo rinfresca ogni cinque secondi,
come farebbe un tabellone.

*Limiti dichiarati*: si guarda avanti fino al capolinea (un convoglio che ha appena
superato la stazione la rivedra` dopo l'inversione, ma quel ritorno non viene previsto:
altrimenti l'elenco conterrebbe sempre tutti i treni dell'itinerario, che e` il contrario
di un tabellone degli arrivi); le corse soppresse non compaiono.

### 1.4 Il ritardo arriva anche al convoglio — fatto

Il PDF descrive il treno come *"dotato di un sistema di comunicazione in ingresso, in grado
di ricevere informazioni (guasti o ritardi) dalle stazioni o dalla centrale"*. Su
`railway/alerts` viaggiavano solo guasti: `GUASTO`, `STOP`, `RESOLVED`,
`MAINTENANCE_DISPATCHED`, `ITINERARIO_AGGIORNATO`. Mancava la seconda meta` della frase, ed
era diventata una mancanza vera proprio con la 1.2: da quando il ritardo lo misura la
Centrale, il convoglio non ha piu` nessun modo di conoscerlo se non glielo si dice.

Adesso c'e` un sesto tipo di evento, `RITARDO`. Lo pubblica
`IngestionService.pubblicaRitardoSuMqtt` e porta `target` (il treno, come `STOP` e
`ITINERARIO_AGGIORNATO`), `ritardoMinuti` con il totale, `variazioneMinuti` con quanto e`
cambiato in quel passaggio, `causa` (`ARRIVO_IN_RITARDO` per il tempo perso in linea,
`SOSTA_PROLUNGATA` per quello perso fermi in banchina) e `sorgenteId`, che qui e` la
stazione al cui passaggio la misura e` stata fatta.

Parte da un punto solo, `IngestionService.onPassaggio`, e **solo se il ritardo e`
cambiato**: `PrevisioneArrivi` e` l'unica a scrivere `Treno.ritardo` e la si chiama solo di
li`, quindi basta confrontare il valore prima e dopo la misura. Un messaggio a ogni ingresso
e a ogni uscita di ogni convoglio riempirebbe il canale condiviso di righe che dicono tutte
la stessa cosa. Un treno che recupera il tempo perso riceve lo zero come riceve i ritardi,
con severita` `INFO` invece di `WARNING`: tornare in orario non e` un allarme.

A bordo il numero finisce in `TrainDB.ritardoMinuti`, `ritardoRilevatoA` e
`ritardoAggiornatoIl`, ed esce da `GET /treno/viaggio` e `GET /treno/tratta` (che e`
l'endpoint del display ai passeggeri). Non e` il vecchio contatore di bordo tolto con la
1.2: il treno questo numero non lo calcola e non ci decide niente sopra, lo riceve e lo
mostra. Il governo del traffico resta della Centrale.

*Limite dichiarato*: come il ritardo che lo genera, il valore vive in RAM e non e`
persistente, quindi un convoglio riavviato riparte da zero minuti finche` non passa dalla
prima stazione utile e la Centrale non gli comunica la misura successiva.

### 1.5 I sensori di binario battono da soli — fatto

Il meccanismo di sorveglianza c'era gia` tutto (la stazione tiene l'ultimo battito di ogni
sensore, oltre il timeout apre un guasto `sensore_offline` CRITICAL e passa GUASTA), ma la
mappa dei sensori si popolava solo chiamando a mano la POST: nessun processo produceva i
battiti.

Adesso li produce `Stazioni/src/main/java/it/uni/reti2/SimulatoreSensori.java`: ogni stazione
si crea in casa i due dispositivi del proprio binario (INGRESSO e USCITA) e li fa parlare con
il nodo **via REST**, come farebbe l'elettronica vera — keepalive su
`POST /stazione/sensore/heartbeat`, transiti su `POST /stazione/sensore/treno`. Il pezzo che
sta dall'altro capo del filo, `MonitorSensori`, non sa niente del simulatore: funzionerebbe
identico con sensori veri, controlla i battiti a intervalli (`sensori.controllo.periodo`) e
rimette la stazione ONLINE da sola quando tutti tornano a battere, ma solo se il guasto era
davvero colpa dei sensori.

Lo scenario di fail-stop si innesca dalla CLI (`ConsoleSensori`, gemella di quella del
Treno): spegnere un sensore vuol dire che smette di battere **e** smette di rilevare i
transiti, perche` e` morto il dispositivo intero. La prova automatica sta in
`Stazioni/src/test/java/it/uni/reti2/SensoriFailStopTest.java`.

*Limite dichiarato*: il binario e` uno solo per stazione, quindi i dispositivi sono due; e
i sensori restano simulati dentro il processo della stazione (nel diagramma delle componenti
sono `<<esterno>>`), non sono un eseguibile separato.

### 1.6 Il guasto di una singola via — fatto

Il PDF, fra i guasti non temporanei, cita *"una particolare linea della stazione"*. La
segnalazione che arriva dai sottosistemi locali adesso porta un `tipoGuasto`: manca ed e`
`stazione_guasta` come prima, vale `binario_guasto` quando quello che si e` rotto e` un
binario o uno scambio (`StationIngestion.rilevaGuasto`, scelto dalla CLI in
`ConsoleSensori.java:150`). Il tipo lo dichiara chi segnala, che sa che cosa si e` rotto,
invece di lasciarlo indovinare alla Centrale, che comunque lo valida
(`IngestionService`, riga 960 e dintorni) e lo mostra nel frontend (`types/index.ts:129`).

*Limite dichiarato*: con un binario solo per stazione il guasto di via si comporta come
quello di stazione — trattiene i convogli — perche` non c'e` una seconda via su cui
dirottarli.

### 1.7 Il capolinea come fine corsa — fatto il 24 agosto 2026

Il PDF ammette che un treno possa *"entrare senza uscirvi (capolinea)"*. Prima non succedeva mai:
all'estremo dell'itinerario il convoglio invertiva la direzione e ripartiva, quindi da ogni stazione
prima o poi usciva.

Adesso l'itinerario ha una colonna `capolinea` (`Itinerario.capolineaId`, estensione dello schema):
l'amministratore marca con la bandierina uno dei due **estremi** dell'elenco delle stazioni — una
fermata intermedia il gateway la rifiuta con un 400, perche' spezzerebbe il percorso in due — e quale
dei due cambia la corsa: l'ultima stazione chiude dopo la sola andata, la prima dopo andata e
ritorno. Il capolinea viaggia verso il treno nella stessa risposta dell'itinerario
(`GET /api/treni/{id}/itinerario`, campo `capolineaId`).

Arrivandoci il convoglio pubblica l'ENTRATA — in quella stazione ci e` entrato davvero — e poi si
ferma: stato `CAPOLINEA`, fase `FERMO_CAPOLINEA`, velocita` e passeggeri a zero, nessuna prossima
fermata (`TrainJourneyEngine.java:752`). Quel transito resta aperto per sempre, ed e` esattamente il
caso "entra senza uscire" che il PDF nomina. Lo stato nuovo e` stato aggiunto anche al CHECK di
`Treni.stato` in Centrale (serve la migrazione `migrazione_capolinea.sql` su un database gia` in
uso), le previsioni di arrivo saltano i convogli a fine corsa e la dashboard li mostra come servizio
concluso, non come guasto.

Se ne esce solo assegnando al treno un itinerario diverso, oppure togliendo il capolinea da quello
che ha: la modifica arriva via `ITINERARIO_AGGIORNATO` e il twin si riaggancia
(`TrainJourneyEngine.java:365`). Un convoglio a fine corsa viene riagganciato **sempre**, anche se le
fermate non sono cambiate, perche' quella e` l'unica strada che ha per ripartire; se pero` il
capolinea e` ancora la stazione in cui sta, resta fermo (`TrainJourneyEngine.java:491`), se no
bastava agganciare un altro treno alla tratta per farlo ripartire.

Il caso opposto era gia` coperto: alla prima partenza viene pubblicata solo l'USCITA, senza ENTRATA
inventata.

### Come si verifica

- `ServeCentraleOperativa/src/test/java/it/uni/reti2/elaboration/PrevisioneArriviTest.java`
  monta una linea di tre stazioni e controlla previsione, soste, stazione alle spalle,
  nascita del ritardo in linea e in sosta, tolleranza e tabellone (anche via REST):
  undici casi, che girano su H2 senza bisogno di nessun servizio acceso.
- `ServeCentraleOperativa/src/test/java/it/uni/reti2/ingestion/AlertRitardoTest.java` e
  `Treni/src/test/java/it/uni/reti2/RitardoComunicatoTest.java` prendono l'alert `RITARDO`
  dai due capi: il primo controlla i campi del payload che esce dalla Centrale, il secondo
  che il convoglio li copia a bordo, che lo zero azzeri davvero il ritardo e che l'alert di
  un altro treno venga ignorato (il topic e` condiviso). Nessuno dei due e` un
  `@QuarkusTest`: sono oggetti costruiti a mano, cosi` non toccano il broker vero.
- `Treni/src/test/java/it/uni/reti2/RiaggancioItinerarioTest.java` copre il capolinea con due
  casi in coda ai cinque del riaggancio: togliere il capolinea rimette in marcia il convoglio che
  aveva chiuso la corsa, confermarlo dov'era lo tiene fermo. A mano si prova dalla pagina Tratte,
  con la bandierina su una delle due stazioni estreme.
- I due parametri dell'orario di servizio (`viaggio.fattore.accelerazione`,
  `viaggio.sosta.secondi`) stanno adesso anche in
  `ServeCentraleOperativa/src/main/resources/application.properties` e **devono restare
  uguali** a quelli della flotta: se la Centrale prevedesse con una scala e i treni
  viaggiassero con un'altra, ogni convoglio risulterebbe in ritardo di una quantita`
  costante senza che sia successo niente.

## 2. Parziali e scelte dichiarate

### 2.1 Non esistono guasti di linea

`Guasto.sorgenteTipo` assume solo `"STAZIONE"` e `"TRENO"`. Il PDF cita *"i guasti alle linee"* fra
le cose da memorizzare: un guasto puo` essere circoscritto alla stazione o a una sua via
(`binario_guasto`, sezione 1.6), ma non a una tratta fra due stazioni. In pratica una linea
interrotta si rappresenta guastando la stazione a un capo, che e` piu` grossolano.

Le vie multiple il PDF le dichiara opzionali (*"se volete gestire anche le vie multiple"*), quindi
non e` una penalita`.

## 3. Quello che invece e` coperto

Ingestion via MQTT; caching locale con store and forward (`LocalBuffer`); heartbeat
stazione-Centrale con soglia `T_sil`; allarme automatico per convoglio fermo fra due stazioni oltre
`T_fer` (`FaultMonitor.java:152`); keepalive dei sensori di binario con fail-stop e
rientro automatico; guasto permanente segnalato subito, invio degli operatori e
ripristino; treno trattenuto nella stazione guasta finche` non arriva il `RESOLVED`; digital twin a
eventi discreti con tempo di percorrenza; calcolo dei ritardi e previsioni di arrivo in
Centrale con il tabellone dei treni attesi e il ritardo comunicato al convoglio (sezione 1); **entrambe** le opzioni previste dal PDF per la prossima
stazione (itinerario precaricato e `GET /api/prossima-stazione`); storico dei transiti; itinerari di
andata e ritorno; amministratore (creazione ed eliminazione tratte, modifica delle stazioni
attraversate, soppressione); tecnico (invio della manutenzione); API REST e web app; OAuth2 con
Keycloak; TLS su tutti i canali.

Delle varianti ammesse viene usata solo la numero 1 (web app al posto della SPA), che non comporta
penalita`.

## 4. Nota su `requisiti_da_implementare.md`

Due righe di quel documento sono superate dai fatti:

- **RF02.5.2** (il convoglio ricomincia dal capolinea dopo la modifica dell'itinerario) e` stato
  implementato: il riaggancio nella posizione corrente sta in
  `TrainJourneyEngine.java:329-455`.
- **RF02.1.1.2.3** (il convoglio avviato dopo l'allarme non sa che la stazione e` guasta) di fatto
  si chiude da solo: quando quel convoglio entra, la stazione si accorge di essere GUASTA e
  ripubblica l'alert, e il treno si blocca li` (`StationGateway.registraTransitoRilevato`, ramo
  "stazione guasta", e `TrainGateway.java:95-108`).
