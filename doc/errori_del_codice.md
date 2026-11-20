# Errori del codice — revisione del 20/11/2026

Revisione fatta leggendo i sorgenti, senza eseguire niente: nessun test lanciato, nessun nodo
avviato. Ogni errore qui sotto è stato verificato sul codice (file e righe indicati) seguendo il
percorso del messaggio da chi lo produce a chi lo consuma. Dove la conseguenza dipende da un ordine
di arrivo o da un dato particolare lo scenario lo dice.

I codici sono `D01`, `D02`, ...: le lettere A, B e C le hanno già usate i report precedenti.

Gravità:
- **alta**: in uso normale il sistema fa una cosa diversa da quella che dichiara (stato sbagliato,
  treno che entra dove non deve);
- **media**: caso realistico ma non quotidiano, oppure falla di configurazione;
- **bassa**: robustezza, casi limite, commenti che non dicono più il vero.

## Perimetro

Letti per intero o quasi:
- Centrale: `GestoreEventi` (heartbeat, guasti dal campo, chiusura degli allarmi, watchdog),
  `AutomaGuasto`, `IngestionService`, `PubblicatoreAlert`, `ExistIdForEdge`, `ServerRest`,
  il pezzo di `TrafficLogicEngine` che cerca i guasti aperti;
- Treni: `TrainGateway`, `GestoreEventi` (alert delle stazioni, guasto di bordo, soppressione),
  `AutomaStazioniNote`, `TrainDB` (controllo di percorribilità), `TrainElab`, `TrainIngestion`,
  `TrainDatabaseValidator`, `fermataDiRiaggancio`;
- Stazioni: `GestoreEventi`, `AutomaStatoStazione`, `AutomaSensori`, `LocalBuffer`,
  `ConsumatoreBuffer`, `ClientMqtt`, `StationGateway`, `main`, le scritture di `PersistenzaLocale`;
- web app: `websocketClient.ts`, l'avvio di `useRealtimeUpdates.ts`, il rinnovo del token in
  `authStore.ts` e `keycloak.ts`;
- configurazione: `docker-compose.yml`, `mosquitto.conf`, `gen-certs.sh` (certificati validi fino
  a ottobre 2028 – gennaio 2029), `stazioni.conf`.

Guardati solo con ricerche mirate (confronti fra stringhe, campi JSON letti senza controllo,
collezioni condivise fra thread, connessioni JDBC): `RestApiGateway`, `RailwayRepository`,
`PrevisioneArrivi`, `AutomaTreno`, `AutomaViaggio` (a parte il riaggancio), `NotificatoreRealtime`,
le pagine della web app. Su questi file la revisione non è completa: un errore lì dentro può
esserci ancora.

## Riepilogo

| Codice | Gravità | Modulo              | Errore                                                                                       |
|--------|---------|---------------------|----------------------------------------------------------------------------------------------|
| D01    | alta    | Centrale + Treni    | i treni entrano in una stazione OFFLINE: il guasto del watchdog è WARNING e il treno lo ignora |
| D02    | alta    | Treni (+ Stazioni, Centrale) | un RESOLVED parziale fa dimenticare al treno una stazione ancora GUASTA                |
| D03    | media   | Centrale            | guasti dedotti e guasti segnalati con lo stesso tipo si fondono in uno solo                  |
| D04    | media   | Centrale + broker   | sotto `tls` restano aperte la 8781 in chiaro e la 1883 anonima                              |
| D05    | media   | web app             | se Keycloak non risponde al rinnovo del token l'utente viene buttato fuori                  |
| D06    | bassa   | Centrale            | chiudere il guasto di un convoglio chiude i derivati di chi ha un id che lo contiene         |
| D07    | bassa   | web app             | `disconnect()` della WebSocket non ferma la riconnessione; `connect()` può aprirne due       |
| D08    | bassa   | Treni               | il frame di telemetria si legge mentre il gestore scrive, e la velocità si scrive fuori dal gestore |
| D09    | bassa   | Treni               | telemetria scritta a mano con `String.format`                                                |
| D10    | bassa   | Stazioni            | se il db locale non scrive, l'evento resta solo in RAM e il guasto parte con id -1           |
| D11    | bassa   | Treni               | `POST /treno/tratta` risponde 500 su corpi non previsti                                      |
| D12    | bassa   | tutti               | commenti e configurazione che parlano della versione precedente o di cose che non esistono   |

---

## Errori nuovi

### D01 — I treni entrano in una stazione OFFLINE (alta)

**Dove**
- `ServeCentraleOperativa/.../elaboration/GestoreEventi.java:895-899`: il watchdog apre il guasto
  "heartbeat assente" con severità `"WARNING"`, e alla riga 923 lo pubblica sul campo.
- `Treni/.../TrainGateway.java:77-82`: il treno considera un GUASTO di stazione solo se
  `"CRITICAL".equalsIgnoreCase(severita)`; il commento dice proprio "i WARNING sono informativi e la
  stazione resta agibile".
- `Treni/.../TrainDB.java:265-267`: OFFLINE non è fra gli stati che chiudono i binari, e comunque il
  watchdog non pubblica nessuno `STATO_STAZIONE` (righe 914-925).

**Che cosa succede.** Il processo della stazione X si ferma (o perde il broker). Dopo `T_sil` la
Centrale la mette OFFLINE e pubblica il guasto dedotto, WARNING. Il treno lo scarta, quindi X non
entra in `stazioniGuaste` e il controllo alla partenza (`stazioneImpercorribile`) dice che X è
percorribile: il treno parte, arriva e attraversa una stazione di cui nessuno sa niente.

**Perché è un errore.** Il codice stesso dichiara il contrario in tre punti: la javadoc di
`suControlloHeartbeat` (righe 881-883: "il guasto si pubblica anche sul campo, così i treni sanno
della stazione caduta e non ci entrano"), `StatiCanonici.stazioneTrattieneITreni` (righe 93-95: "se
è davvero caduta ci pensa il watchdog ad aprirle un guasto dedotto") e RF02.6.1 nel `.org` ("il
guasto viene pubblicato verso il campo, quindi i convogli diretti alla stazione caduta si
fermano", dato come completo).

**Correzione possibile.** Pubblicare il guasto del watchdog come CRITICAL (è la scelta più
piccola, il treno ha già tutta la logica), oppure annunciare `STATO_STAZIONE` OFFLINE e far
trattenere i treni anche da OFFLINE. Se invece il comportamento attuale è voluto, vanno corretti i
due commenti e RF02.6.1.

### D02 — Un RESOLVED parziale fa dimenticare al treno una stazione ancora GUASTA (alta)

**Dove**
- `Treni/.../TrainGateway.java:94-96`: ogni RESOLVED con `sorgenteTipo` STAZIONE diventa
  `GUASTO_STAZIONE_RISOLTO`, chiunque l'abbia mandato e qualunque guasto chiuda.
- `Treni/.../GestoreEventi.java:403-415`: `suGuastoStazioneRisolto` lascia fermo il treno già
  bloccato (giusto), ma toglie la stazione da `stazioniGuaste` (riga 408).
- `Treni/.../TrainDB.java:245-253`: la partenza guarda solo `stazioniGuaste` e `statoStazioni`.
- `ServeCentraleOperativa/.../GestoreEventi.java:556-561`: per un GUASTO venuto dal campo la Centrale
  pubblica `STATO_STAZIONE` solo nel caso MANUTENZIONE, quindi in `statoStazioni` del treno la
  stazione non risulta GUASTA.

**Da dove arrivano i RESOLVED parziali**
- dalla stazione stessa: `Stazioni/.../GestoreEventi.java:123-126` manda il RESOLVED
  `sensore_offline` quando tornano i sensori anche se resta GUASTA per un altro guasto (il log
  della riga 120 lo dice);
- dalla Centrale: `suRisolviGuasto` (riga 754) pubblica il RESOLVED del singolo allarme chiuso
  dall'operatore, anche se la stazione ne ha altri aperti; `annunciaChiusure` (riga 1157) fa lo
  stesso con il guasto "heartbeat assente".

**Scenario.** La stazione X ha due guasti aperti: scambi (`POST /stazione/sensore/guasto`) e
sensori muti. Il treno T è due fermate prima di X: riceve i due GUASTO, X non è né la corrente né
la prossima, quindi li annota e basta. Tornano i sensori: X pubblica il RESOLVED dei sensori e
resta GUASTA. T toglie X da `stazioniGuaste`. Quando T arriva nella fermata prima di X, il
controllo alla partenza dice che X è percorribile e T ci entra. Solo all'ENTRATA la stazione lo
trattiene con l'allarme del treno trattenuto: RF02.1.1.2.1 ("il convoglio non vi entra") non è
rispettato.

**Perché è un errore.** Il commento di `suGuastoStazioneRisolto` dice "un guasto chiuso NON vuol
dire binari liberi ... a saperlo è solo la Centrale, che lo dice con STATO_STAZIONE", ma la
`dimenticaGuasto` fa esattamente quello che il commento esclude, per tutti i treni non ancora
bloccati.

**Correzione possibile.** In `suGuastoStazioneRisolto` non toccare `stazioniGuaste`: a
cancellarla resta solo `STATO_STAZIONE` ONLINE (riga 447), che arriva quando la Centrale non ha più
guasti aperti per quella stazione. In alternativa la Centrale annuncia `STATO_STAZIONE` GUASTA
anche per i GUASTO del campo.

### D03 — Guasti dedotti e guasti segnalati con lo stesso tipo si fondono (media)

**Dove**
- `ServeCentraleOperativa/.../GestoreEventi.java:895`: il guasto "heartbeat assente" ha tipo
  `sensore_offline`, lo stesso che la stazione usa per i sensori muti
  (`AutomaStatoStazione.TIPO_GUASTO_SENSORE`).
- `GestoreEventi.java:964`: il "convoglio fermo" del watchdog ha tipo `treno_fermo`, lo stesso che
  `AutomaGuasto.tipoPerFrontend` dà all'avaria di bordo (il treno non dichiara `tipoGuasto`).
- `TrafficLogicEngine.java:287-296`: `getGuastoApertoPerSorgente` cerca per sorgente e tipo, non
  per origine.
- `GestoreEventi.java:487-490` e `519`: se c'è già un guasto con quella coppia, quello nuovo non
  si apre e si sostituisce solo il messaggio.

**Che cosa succede.**
1. Stazione con i sensori muti (guasto CRITICAL aperto) che smette di battere: il watchdog trova
   il guasto dei sensori, non apre quello del fail-stop, e la caduta non compare negli allarmi
   (RF02.6.1 "apre un guasto su di essa").
2. Stazione isolata (leva `POST /stazione/rete/offline` o rete giù): il watchdog apre il guasto
   WARNING dedotto. Intanto un sensore si ferma e il GUASTO CRITICAL resta nel buffer. Se alla
   riconnessione arriva prima del battito, la Centrale riscrive il messaggio del guasto del
   watchdog: l'allarme resta WARNING e "dedotto dalla Centrale" (RF01.2.2 sbagliato) e perde il
   prefisso `Heartbeat assente:` che serve a riconoscerlo.
3. Stesso schema sul treno: fermo in tratta oltre `T_fer` (guasto WARNING dedotto) e poi avaria di
   bordo CRITICAL; il treno diventa "rotto", ma l'allarme mostrato resta WARNING e dedotto.

**Correzione possibile.** Dare ai guasti del watchdog tipi propri (per esempio
`heartbeat_assente`, `convoglio_fermo`, da aggiungere a `TIPI_NOTI` e alla web app), oppure far
cercare `apertoPer` anche per origine.

### D04 — Sotto `tls` restano aperti i canali in chiaro (media)

**Dove**
- `ServeCentraleOperativa/.../gateway/ServerRest.java:63`: `ssl.insecurePort = porta`, quindi la
  8781 in HTTP resta in ascolto accanto alla 8444 (anche `application.properties:137` lo dice).
  Treni e Stazioni sotto `tls` invece spengono l'HTTP (`ssl.insecure = false`).
- `BrokerMosquitto/config/mosquitto.conf:1-2`: la 1883 in chiaro resta sempre aperta con
  `allow_anonymous true`, e nemmeno la 8883 chiede credenziali o certificato del client.

**Che cosa succede.** Con il profilo `tls` acceso chiunque raggiunga il broker può pubblicare su
`railway/alerts` dalla 1883 un messaggio con `"origine":"CENTRALE"`: un `STOP` sopprime un treno, un
`STATO_STAZIONE` ONLINE fa ripartire i treni trattenuti, un `RESOLVED` rimette ONLINE una stazione
(i nodi si fidano del campo `origine`, che scrive chi pubblica). Sulla 8781 le API rispondono in
chiaro, compresa la lettura dell'itinerario che i treni fanno senza token.

**Perché è un errore.** RF04.3 è dato come completo con l'esito "o tutti i canali sono protetti, o
la garanzia non vale".

**Correzione possibile.** Sotto `tls` spegnere la 8781 come già fanno i nodi; nel broker
`per_listener_settings true` con la 1883 chiusa (o legata a `127.0.0.1`) e, se si vuole davvero
impedire i messaggi falsi, utenti e password (`password_file`) o certificati client.

### D05 — Se Keycloak non risponde al rinnovo, l'utente viene buttato fuori (media)

**Dove.** `ClientWebAppIntefacciaUtente/src/api/keycloak.ts:227-230` e
`src/store/authStore.ts:65-73`.

**Che cosa succede.** `rinnovaToken` restituisce `null` in due casi diversi: refresh token
rifiutato (righe 219-222) e rete giù (riga 229, con il commento "si tiene quello che si ha e si
riproverà dopo"). Il timer di `attivaToken` tratta tutti e due allo stesso modo: azzera il token e
mette `isAuthenticated: false`. Basta che Keycloak sia irraggiungibile per qualche secondo nel
momento del rinnovo (riavvio del container) perché l'operatore si ritrovi sulla pagina di login, e
nessuno riprova.

**Correzione possibile.** Distinguere i due casi (per esempio far lanciare un'eccezione al
`catch`) e, con la rete giù, riprogrammare il timer dopo qualche secondo invece di chiudere la
sessione.

### D06 — Chiudere il guasto di un convoglio chiude i derivati di altri convogli (bassa)

**Dove.** `ServeCentraleOperativa/.../GestoreEventi.java:693`:
`derivato.messaggio.contains(guasto.sorgenteId)`.

**Che cosa succede.** I guasti "convoglio guasto in stazione" si riconoscono cercando l'id del treno
dentro il messaggio `"Convoglio <treno> guasto in stazione <stazione>: ..."`. Con id che si
contengono a vicenda (`TR1` e `TR10`) o un treno che ha lo stesso id di una stazione, chiudere il
guasto di uno chiude anche il derivato dell'altro e riapre la sua stazione con un convoglio ancora
rotto sui binari. Con i dati demo (nomi di vini) non succede, ma l'anagrafica accetta qualunque id.

**Correzione possibile.** Confrontare con il prefisso esatto `"Convoglio " + id + " "`, o meglio
salvare il treno che ha causato il derivato in un campo suo.

### D07 — La WebSocket della web app non si ferma e può aprirsi due volte (bassa)

**Dove.** `ClientWebAppIntefacciaUtente/src/api/websocketClient.ts:68`, `101-104`, `132-138`.

**Che cosa succede.**
- `disconnect()` cancella il timer e poi chiama `close()`: l'`onclose` arriva dopo e programma una
  nuova connessione fra 5 secondi. La socket chiusa a mano si riapre da sola.
- `connect()` esce solo se la socket è `OPEN`: chiamata mentre è `CONNECTING` ne crea una seconda e
  la prima resta viva, con i messaggi gestiti due volte.
- Con `React.StrictMode` (`main.tsx`) l'effetto parte, si smonta e riparte. Se in quel momento la
  Centrale è spenta, i timer dei due giri diventano due catene di riconnessione parallele e alla
  riaccensione restano aperte due socket: notifiche e allarmi doppi (succede col dev server).

**Correzione possibile.** Un flag `chiusaDaMe` messo da `disconnect()` e controllato in `onclose`;
in `connect()` uscire anche se la socket è `CONNECTING`.

### D08 — Frame di telemetria letto a metà e velocità scritta fuori dal gestore (bassa)

**Dove.** `Treni/.../TrainElab.java:49-54` e `61-75`; `Orologio` ha due thread, quindi tick e
telemetria girano in parallelo.

**Che cosa succede.** Il frame legge una decina di campi di `TrainDB` senza il lock del gestore:
se il tick sta facendo un arrivo, il frame può avere la `faseViaggio` nuova con la
`stazioneCorrente` o il `progressPercent` vecchi. In più `velocita` la scrive anche `TrainElab`:
legge `stato()` = IN_VIAGGIO, il gestore blocca il treno e mette velocità 0, poi `TrainElab`
riscrive una velocità fra 80 e 160. Per un secondo la Centrale vede un treno bloccato che corre;
il frame dopo si sistema, ma la Centrale usa la velocità per il watchdog del treno fermo e il
progresso per il ritardo in corso.

**Correzione possibile.** Costruire il frame dentro un metodo `synchronized` sullo stesso
monitor del gestore (o farselo dare dal gestore), e calcolare la velocità solo lì.

### D09 — Telemetria scritta a mano con `String.format` (bassa)

**Dove.** `Treni/.../TrainElab.java:61-75`.

**Che cosa succede.** Il JSON è composto a mano. Con un id di treno o di stazione che contiene una
virgoletta il frame non è più JSON e la Centrale lo scarta (`IngestionService.onTelemetry`). È lo
stesso problema già corretto in `PubblicatoreAlert` passando a `JsonObject` di Gson; qui è
rimasto.

### D10 — Db locale che non scrive: l'evento resta solo in RAM (bassa)

**Dove.** `Stazioni/.../PersistenzaLocale.java:508-531` (`salvaEventoBuffer`) e `364-383`
(`registraGuasto`); `LocalBuffer.java:101-106`.

**Che cosa succede.** Se la scrittura su SQLite fallisce (disco pieno, file bloccato) c'è solo un
WARNING nel log: l'evento resta nella coda in memoria e un riavvio lo perde, contro RF02.1.1.1.1
("nessun dato perduto durante l'interruzione"). `registraGuasto` nello stesso caso restituisce -1,
e l'allarme parte per la Centrale con `guastoId` -1.

**Correzione possibile.** Almeno un SEVERE e uno stato visibile in `GET /stazione/buffer`; con
`registraGuasto` a -1 mandare l'allarme senza id.

### D11 — `POST /treno/tratta` risponde 500 su corpi non previsti (bassa)

**Dove.** `Treni/.../TrainIngestion.java:100-129`.

**Che cosa succede.** `{"stazioni": null}` mette `stazioniTratta` a null e la `getTratta` che
segue fallisce dentro `Map.of` (non accetta null): 500, e da lì in poi anche `GET /treno/tratta`
risponde 500. `{"prossimaStazione": 5}` dà una `ClassCastException`. Lo stesso cast senza
controllo c'è in `rilevaGuasto` sulla `descrizione`. (Che questa rotta scriva `prossimaStazione`
fuori dal gestore è un'eccezione già nota, vedi sotto.)

### D12 — Commenti e configurazione non allineati al codice attuale (bassa)

Frasi che raccontano la versione precedente o descrivono cose che non ci sono più:
- `pom.xml:9`: "`cd Stazioni && ./mvnw quarkus:dev` continua a funzionare come prima" (Quarkus non
  c'è più);
- `ServeCentraleOperativa/pom.xml:126`, `Stazioni/pom.xml:96`, `Treni/pom.xml:81`: "quarkus-run.jar
  di prima";
- `ServerRest.java:19` (Centrale) "come prima"; `GestoreEventi.java:665` "come faceva la Centrale
  prima degli automi"; `GestoreEventi.java:872` "come prima"; `main.java:163` "(come prima)";
  `application.properties:137` "come prima";
- `Stazioni/.../ConsumatoreBuffer.java:65` "come faceva il vecchio flush";
  `Stazioni/src/main/resources/application.properties:27` "come prima di avere il db";
- `ClientWebAppIntefacciaUtente/src/pages/TrafficMapPage.tsx:528` "come prima";
  `popola_db.sh:15` "come prima";
- `BrokerMosquitto/config/mosquitto.conf`: "vedi qos=1 sui canali alerts-* nei tre
  application.properties" (i canali `alerts-*` erano di Quarkus, oggi il QoS sta nelle chiamate a
  `ascolta`/`metti`);
- `websocketClient.ts`, in testa: un paragrafo sul caching HTTP e le CDN che non spiega niente del
  file.

---

## Già noti e ancora aperti

Segnalati nelle revisioni precedenti e non corretti. Ricontrollati oggi sul codice: ci sono ancora.

| Errore | Dove | Nota |
|--------|------|------|
| il RESOLVED automatico della Centrale rimette ONLINE una stazione GUASTA per un altro motivo | `Stazioni/.../GestoreEventi.java:274` (`suRipristino` non guarda il `guastoId`) | con D03 caso 2 succede anche con il guasto del fail-stop |
| l'allarme del treno trattenuto non dichiara `tipoGuasto` | `Stazioni/.../GestoreEventi.java:209-210` | la Centrale apre un secondo allarme `stazione_guasta` che il RESOLVED dei sensori non chiude |
| transiti a QoS 0 con `queue_qos0_messages false`: persi se la Centrale è spenta e il broker acceso | `Stazioni/.../ConsumatoreBuffer.java:134`, `mosquitto.conf` | il buffer garantisce la consegna al broker, non alla Centrale |
| dopo `azzeraMuti` il sensore ancora muto riscatta al giro dopo | `Stazioni/.../AutomaSensori.java` (`azzeraMuti` non tocca `sensoriUltimoBattito`) | |
| `registraBattito` accetta qualunque id: un sensore con un id sbagliato entra fra i sorvegliati e, se smette, guasta la stazione | `Stazioni/.../AutomaSensori.java` (`registraBattito`) | |
| il riaggancio da BLOCCATO_IN_STAZIONE salta il controllo del capolinea | `Treni/.../AutomaViaggio.java:286-297` (`else if`) | |
| uno `STATO_STAZIONE` OFFLINE sovrascrive GUASTA/MANUTENZIONE in `statoStazioni` | `Treni/.../GestoreEventi.java:445` (`annotaStato`) | una stazione in MANUTENZIONE che poi va OFFLINE diventa percorribile per i treni; peggiora D01 e D02 |
| PUT `/api/stazioni/{id}` senza validazione dello stato e senza `ultimoHeartbeat` | `StatiCanonici.java:81-85`, `GestoreEventi.java:836-850` (Centrale) | uno stato ONLINE messo a mano con guasti aperti fa ripartire i treni; il 500 con corpo vuoto non l'ho ricontrollato |
| scritture del `TrainDB` fuori dal gestore: `POST /treno/tratta`, la velocità in `TrainElab`, il ritardo in `TrainGateway` | `TrainIngestion.java:119-129`, `TrainElab.java:49-54`, `TrainGateway.java:112-114` | eccezioni note; la velocità ha l'effetto descritto in D08 |
| chiave privata della CA committata | `BrokerMosquitto/tls/certs/ca.key` (tracciata da git) | |
| commenti del `docker-compose.yml` sbagliati | `KC_HOSTNAME` ("quello che la Centrale confronta con `keycloak.url`": l'issuer viene dal `.well-known`), porta 9001 "quella che usa la web app" (nessun listener nel broker, la web app non usa MQTT) | |
| treno SOPPRESSO che torna in servizio se si riavvia il suo processo | Treni (`condizione` solo in memoria) e Centrale (la telemetria riscrive lo stato) | non ricontrollato oggi |

## Già noti e ora risolti

- `stazioni.conf` con TO due volte: adesso c'è una riga sola.

## Esclusi perché limiti dichiarati nel `.org`

Non sono errori del codice, sono scelte o mancanze già scritte nella documentazione:
- i tre comandi operativi concessi a tecnico e amministratore (RF04.2);
- WebSocket senza autenticazione e senza filtro per destinatario;
- nessuna schermata per lo storico dei transiti (RF01.5);
- la tratta resta percorribile se il convoglio si guasta in tratta (RF02.1.2.2.2);
- presa in carico e assegnatario del guasto mai scritti, guasto con due soli stati (RF02.1.3);
- storico non scritto per OFFLINE del watchdog, GUASTA da CRITICAL, PUT di stazione e treno,
  itinerari percorsi (RF02.7);
- token del browser nel `sessionStorage`;
- ripartenza dal capolinea se la stazione di aggancio non è più nell'itinerario, e itinerario nuovo
  installato all'arrivo se il treno è a metà tratta (RF02.5.2).
