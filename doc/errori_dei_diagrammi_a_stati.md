# Errori dei diagrammi a stati — revisione del 20/11/2026

Confronto delle 38 macchine del capitolo *Diagrammi degli stati* del `.org`
(`doc/documentazioneFinale/DocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario.org`:
T0–T11, S0–S11, M0–M13) con il codice dei moduli. Fatto leggendo i sorgenti, senza eseguire
niente. Il `.org` non è stato modificato.

Il criterio è quello deciso il 19/11: un diagramma descrive il codice com'è, errori compresi. Un
difetto del codice quindi non si corregge nel disegno: si disegna, con una nota, e si corregge a
parte. Qui sono errori dei diagrammi solo i punti in cui il disegno, le etichette o le note dicono
qualcosa che il codice non fa, oppure tacciono un percorso che il codice ha.

I codici sono `E01`, `E02`, ...: le lettere A, B, C e D le hanno già usate i report precedenti
(le D sono di `errori_del_codice.md`). I numeri di riga del `.org` sono quelli del file al 20/11.

Gravità:
- **alta**: il diagramma racconta un comportamento diverso da quello del codice, oppure manca un
  percorso che cambia quello che succede al sistema;
- **bassa**: un'etichetta imprecisa, un arco secondario mancante, una frase di una nota.

## Esito

Il nucleo è giusto. Corrispondono al codice arco per arco T5 (controllata ogni fase con ogni
evento), T6, T7, T11, S2–S11, M3, M4, M6, M8 e M11, compresi i difetti del codice che il 19/11 si è
deciso di disegnare com'erano (il riaggancio da BLOCCATO_IN_STAZIONE che salta il capolinea,
OFFLINE che nel treno sovrascrive GUASTA/MANUTENZIONE, il RESOLVED automatico che rimette ONLINE la
stazione). Restano cinque errori di gravità alta e venti di gravità bassa.

## Perimetro

Letti per intero e confrontati con i diagrammi:
- Treni: `main`, `TrainDatabaseValidator`, `ClientMqtt`, `CodaPubblicazioni`, `Orologio`,
  `CaricatoreItinerario`, `GeneratoreEventiTempo`, `GestoreEventi`, `AutomaViaggio`,
  `AutomaCondizione`, `AutomaStazioniNote`, `TrainDB`, `Percorso`, `TrainElab`, `TrainGateway`,
  `TrainIngestion`, `ServerRest`, `PubblicatorePassaggi`, `PubblicatoreAlert`, `EventoTreno`,
  `CampiJson`, il menu di `Sensori`;
- Stazioni: `main`, `StationDatabaseValidator`, `HeartbeatGenerator`, `AutomaConnessione`,
  `ConsumatoreBuffer`, `LocalBuffer`, `Orologio`, `DBLocale`, `PubblicatoreAlert`,
  `PubblicatoreTransiti`, `GestoreEventi`, `AutomaStatoStazione`, `AutomaSensori`,
  `AutomaTreniPresenti`, `StationIngestion`, `ServerRest`, `StationGateway`, `SimulatoreSensori`;
- Centrale: `main`, `ExistIdForEdge`, `ExistIdForEdgeStazione`, `IngestionService`,
  `GestoreEventi`, `AutomaStazione`, `AutomaTreno`, `AutomaGuasto`, `StatiCanonici`, `AlertMsg`,
  `GeneratoreEventiTempo`, `FiltroAutorizzazione`, `RealtimeWebSocket`, `TrafficLogicEngine.carica`;
- web app: il rinnovo del token in `api/keycloak.ts` e il timer in `store/authStore.ts`.

Guardati solo nei punti che i diagrammi citano: `RestApiGateway` (CRUD di stazioni e treni,
comandi, codici di risposta), il `PubblicatoreAlert` della Centrale (campi degli alert),
`SicurezzaKeycloak`, `NotificatoreRealtime`, `RailwayRepository`, `Database`, `PersistenzaLocale`,
`schema.sql`, `api/apiClient.ts`.

Non controllati a fondo, quindi lì un errore può esserci ancora:
- `PrevisioneArrivi`, `NavigazioneItinerario`, `PosizioneConvoglio`: le frasi di M3 e M5 su
  ritardo, tratta dedotta e posizione certificata sono verificate solo dove quelle classi vengono
  chiamate;
- le note di M10 sugli itinerari (ITINERARIO_AGGIORNATO per POST, PUT e DELETE, l'assenza di
  `treniIds`, i controlli 409 e 400 sulle tratte);
- la metà A di M9 (il login PKCE) a parte il rinnovo del token.

## Riepilogo

| Codice | Gravità | Diagramma | Errore |
|--------|---------|-----------|--------|
| E01 | alta  | M7      | il guasto dedotto subisce anche GUASTO e RESOLVED del campo con lo stesso tipo |
| E02 | alta  | M12     | manca OFFLINE → ONLINE con "risolvi" (o con il RESOLVED del campo) |
| E03 | alta  | M13     | la telemetria copia lo stato da qualunque stato: mancano archi da rotto, in manutenzione, capolinea |
| E04 | alta  | T2, T8  | il motivo dato per il filtro su `trenoRiconosciuto` è falso, e M3 dice il contrario |
| E05 | alta  | M1      | nessuna uscita d'errore (database spento, collegamento MQTT fallito) |
| E06 | bassa | T1      | eccezioni dell'avvio non disegnate |
| E07 | bassa | T3      | fra le cause di RIPROVA manca la risposta illeggibile |
| E08 | bassa | T5      | manca SENZA_ITINERARIO → SENZA_ITINERARIO su ITINERARIO_REVOCATO |
| E09 | bassa | T5      | "le partenze sono sempre precedute dalla ENTRATA": falso dopo il riaggancio da capo |
| E10 | bassa | T7      | "sosta doppia al capolinea": è all'estremo dove si inverte la marcia |
| E11 | bassa | T9      | il ritardo non porta l'ora della misura ma quella di arrivo dell'alert |
| E12 | bassa | T9      | "la Centrale annuncia lo stato prima della chiusura" non vale sempre |
| E13 | bassa | T10     | corpo vuoto o campo di tipo sbagliato danno 500, non 400 |
| E14 | bassa | T11     | "chi era trattenuto resta fermo" è falso per il caricamento dell'itinerario |
| E15 | bassa | T11     | la nota contraddice gli archi verso "Chiusa da tutti e due" |
| E16 | bassa | S1      | ordine invertito fra sensori attesi e causa del guasto |
| E17 | bassa | S5      | se la persistenza non scrive, l'evento non sopravvive al riavvio |
| E18 | bassa | S0, S1, S4, S5, S10, S11, M0, M1, M2, M3, M9, M13 | le testate citano file `.puml` che non esistono |
| E19 | bassa | M2      | per le stazioni non c'è una `SELECT COUNT(*)` |
| E20 | bassa | M5      | manca la seconda ENTRATA; sull'uscita senza transito aperto manca il contatore |
| E21 | bassa | M7      | manca Risolto → Risolto (un guasto chiuso si può risolvere di nuovo) |
| E22 | bassa | M9      | dal rinnovo si esce anche con Keycloak irraggiungibile |
| E23 | bassa | M10     | manca STATO_STAZIONE MANUTENZIONE; POST del treno con stato sbagliato = 500; altri 404 |
| E24 | bassa | M12     | avvio in MANUTENZIONE, PUT con qualunque valore, eliminazione da qualunque stato |
| E25 | bassa | M13     | creazione con uno stato, eliminazione da qualunque stato, autoanello del watchdog |

---

## Errori di gravità alta

### E01 — M7: il guasto dedotto subisce anche gli eventi del campo dello stesso tipo (alta)

**Dove**
- `.org` righe 5104–5112 (gli archi di M7) e 5132–5137 (nota sotto Risolto).
- `ServeCentraleOperativa/.../GestoreEventi.java:895-896` e `:964-965`: il watchdog apre i suoi
  guasti con tipo `sensore_offline` e `treno_fermo`. `sensore_offline` è anche il tipo dei sensori
  muti della stazione, e `treno_fermo` quello dell'avaria di bordo, perché il treno non dichiara
  `tipoGuasto` e `AutomaGuasto.java:60-61` gli dà quel tipo.
- `GestoreEventi.java:487-490`, `:518-519` e `:528-529`: un GUASTO del campo con la stessa sorgente
  e lo stesso tipo di un guasto aperto non ne apre uno nuovo, sostituisce il messaggio di quello.
- `GestoreEventi.java:612-638`: un RESOLVED del campo chiude il guasto aperto di quel tipo,
  qualunque sia la sua origine.
- `Guasto.java:70-72`: le chiusure automatiche di M3 e M4 riconoscono i guasti dedotti dal prefisso
  del messaggio.

**Che cosa non torna.** Il diagramma disegna l'autoanello "altro alert uguale / aggiorna solo il
messaggio" e la chiusura "il nodo dichiara rientrata la condizione" solo su *dichiarato dal campo*.
Nel codice valgono anche per *rilevato dalla Centrale*. Un GUASTO del campo riscrive il messaggio
del guasto dedotto: il prefisso "Heartbeat assente:" o "Convoglio fermo:" sparisce e M3/M4 non lo
chiudono più, mentre il guasto resta "dedotto" e WARNING. Un RESOLVED del campo invece lo chiude.
La nota sotto Risolto dice che il prefisso "è l'unico modo che hanno per non toccare quelli veri",
come se i due guasti potessero stare aperti insieme: con la deduplica per sorgente e tipo non
succede mai, il secondo si fonde nel primo. È il D03 di `errori_del_codice.md` visto dal lato del
diagramma.

**Correzione nel diagramma.** Su *rilevato dalla Centrale* l'autoanello "M6 - GUASTO del campo con
la stessa sorgente e lo stesso tipo / sostituisce il messaggio: il prefisso si perde e M3/M4 non lo
chiudono più", e sull'arco verso Risolto, con "oppure", "M6 - RESOLVED del campo dello stesso
tipo". Nella nota va tolto il "non toccare quelli veri". Se D03 si corregge nel codice dando tipi
propri ai guasti del watchdog questi archi spariscono, quindi conviene decidere le due cose insieme.

### E02 — M12: manca OFFLINE → ONLINE con "risolvi" (alta)

**Dove**
- `.org` riga 5715 (OFFLINE → ONLINE, oggi solo con il battito) e righe 5800–5803 (nota sotto
  OFFLINE).
- `ServeCentraleOperativa/.../GestoreEventi.java:1137-1151`: `stazioneCheTornaOnline` con
  `soloSeGuasta = false` non guarda lo stato della stazione, controlla solo che non le restino guasti
  aperti. La chiamano così `suRisolviGuasto` (`:680`) e `suGuastoRientrato` (`:626`).
- `GestoreEventi.java:744-746` e `AutomaStazione.java:132-135`: la stazione va ONLINE con
  `ultimoHeartbeat` = adesso. `GestoreEventi.java:753`: al campo parte STATO_STAZIONE ONLINE.

**Che cosa non torna.** Se l'operatore risolve il guasto "Heartbeat assente:" di una stazione ancora
spenta, la stazione passa da OFFLINE a ONLINE e ai treni arriva STATO_STAZIONE ONLINE, cioè il
messaggio che li rimette in marcia. Dopo 30 secondi senza battito il watchdog la rimette OFFLINE con
un guasto nuovo. Lo stesso succede con un RESOLVED del campo che chiude l'ultimo guasto aperto. La
nota sotto OFFLINE descrive proprio questo caso ("altrimenti un nodo in realtà spento resterebbe
ONLINE per sempre"), ma nel disegno l'arco non c'è.

Il comportamento non è fra gli errori di `errori_del_codice.md`, ma probabilmente lo è: dice ai
treni che una stazione spenta è agibile.

**Correzione nel diagramma.** Sull'arco OFFLINE → ONLINE, con "oppure", "M10 - l'operatore risolve
l'ultimo guasto aperto, anche 'Heartbeat assente:' / ultimoHeartbeat = adesso, STATO_STAZIONE
ONLINE al campo; oppure M6 - RESOLVED del campo che chiude l'ultimo guasto".

### E03 — M13: la telemetria copia lo stato da qualunque stato (alta)

**Dove**
- `.org` righe 5872–5896 (gli archi di M13).
- `ServeCentraleOperativa/.../AutomaTreno.java:119`: `treno.stato = msg.stato()`, senza guardie.
  `GestoreEventi.java:1030-1042`: `scriviStatoTreno` scrive la riga e lo storico ogni volta che lo
  stato del frame è diverso da quello a database.
- `GestoreEventi.java:497-499`, `:524-526` e `:536-538`: un GUASTO CRITICAL di un treno lo porta a
  "rotto" qualunque sia il suo stato.
- T6 (`.org` riga 2456): da SOPPRESSO il treno pubblica lo stesso il guasto di bordo.

**Che cosa non torna.** Nel diagramma da "rotto" si esce solo con la risoluzione (M10) e da "in
manutenzione" solo con l'eliminazione. Nel codice M3 porta il treno nello stato che il frame
dichiara, da qualunque stato. Casi reali che mancano:
- treno soppresso che segnala un guasto di bordo: M6 lo porta a "rotto", e il frame successivo
  (SOPPRESSO) lo riporta a "in manutenzione";
- treno al capolinea che si guasta: capolinea → rotto, con M6 e poi con la telemetria EMERGENZA;
- processo del treno riavviato: il twin riparte REGOLARE e SENZA_ITINERARIO, quindi la prima
  telemetria porta "rotto" o "in manutenzione" a "fermo", con il guasto di bordo ancora aperto in
  Centrale. È il caso "treno SOPPRESSO che torna in servizio se si riavvia il suo processo" della
  tabella dei già noti di `errori_del_codice.md`.

**Correzione nel diagramma.** Aggiungere manutenzione → rotto (M6), rotto → manutenzione (M3,
telemetria SOPPRESSO), capolinea → rotto (M6 oppure M3, telemetria EMERGENZA) e manutenzione →
fermo (M3, processo del treno riavviato); sull'arco rotto → fermo che c'è già, con "oppure", "M3 -
telemetria FERMO (processo del treno riavviato)". Nella nota una riga che dica che la telemetria non
ha guardie.

### E04 — T2 e T8: il motivo del filtro su `trenoRiconosciuto` è falso (alta)

**Dove**
- `.org` righe 1960–1964 (nota sotto *Riconosciuto* di T2) e 2688–2693 (nota a sinistra di *In
  attesa del tick* di T8).
- `ServeCentraleOperativa/.../GestoreEventi.java:199-204`: la telemetria di un treno che non è in
  cache viene scartata. `.org` righe 4600–4605 (nota di M3): "La telemetria non crea il treno".
- `Treni/.../TrainElab.java:36-39`: lo stesso motivo nel javadoc.

**Che cosa non torna.** Le due note dicono che senza il filtro il primo frame "si farebbe creare la
riga a database" e il treno si auto-validerebbe. Con il codice attuale non può succedere: la
Centrale scarta i frame dei treni che non ha in anagrafica, e M3 lo dice. Due diagrammi si
contraddicono, e la frase di T2 e T8 racconta un comportamento della Centrale che non esiste.

**Correzione nel diagramma.** Riscrivere le due note al presente: il filtro tiene zitto un treno non
ancora confermato, e comunque la Centrale scarta i frame di un treno che non conosce (M3). Va
corretto anche il javadoc di `TrainElab`.

### E05 — M1: nessuna uscita d'errore (alta)

**Dove**
- `.org` righe 4401–4405 (gli archi di M1).
- `ServeCentraleOperativa/.../main.java:54` e `persistence/Database.java:146-147`: con Postgres
  spento `creaTabelleMancanti()` lancia una RuntimeException che nessuno prende. Lo stesso fa
  `statoRete.carica()` (`main.java:156`). In quel momento non c'è ancora nessun thread non daemon,
  quindi il processo termina.
- `main.java:169-176`: se `clientMqtt.connetti()` lancia un'eccezione, il thread
  `collegamento-mqtt` la scrive nel log e finisce. La Centrale resta accesa senza ricevere niente
  dal campo.

**Che cosa non torna.** M1 ha solo il percorso buono. T1 e S1 hanno lo stato "Terminazione con
errore", M1 no, e la domanda "che cosa succede se il database è spento?" nel diagramma non ha
risposta.

**Correzione nel diagramma.** Uno stato "Terminazione con errore" con gli archi da *Avvio* e da
*Caricamento della cache* ("database non raggiungibile / eccezione non gestita: il processo
termina"), e una nota, o un arco dentro *Servizio operativo*, per il collegamento MQTT fallito.

---

## Errori di gravità bassa

### Treno

**E06 — T1: eccezioni dell'avvio non disegnate.** `.org` righe 1846–1848. In
`Treni/.../ClientMqtt.java:124` (il socket TLS) e `:138` (le sottoscrizioni) un errore che non sia
una MqttException esce da `connetti()`, chiamata in `main.java:133`. Il main muore, ma il server
REST acceso alla riga 124 del main tiene viva la JVM: resta un processo con le sole API REST, mai
validato. Succede solo con una configurazione sbagliata, per esempio la CA illeggibile sotto tls.

**E07 — T3: fra le cause di RIPROVA manca la risposta illeggibile.** `.org` riga 2025. Un JSON
malformato, o con la forma sbagliata, finisce nel `catch` generico di
`CaricatoreItinerario.java:152-156` ed è un RIPROVA come gli altri.

**E08 — T5: manca SENZA_ITINERARIO → SENZA_ITINERARIO su ITINERARIO_REVOCATO.** `.org` righe
2286–2306: il 404 è disegnato solo come uscita da *Con un itinerario*. `Treni/.../GestoreEventi.java:352-367`
gestisce l'evento in ogni fase, ed è proprio il caso più frequente: il 404 che arriva ogni 15
secondi al treno non assegnato.

**E09 — T5: "le partenze successive sono sempre precedute dalla ENTRATA".** `.org` righe
2348–2349. È falso dopo un riaggancio "da capo" (`GestoreEventi.java:215-219` e `:313-316`): il
treno viene messo alla prima tappa del percorso nuovo senza nessuna ENTRATA lì, e la partenza da
quella tappa pubblica una USCITA da una stazione in cui non è mai entrato.

**E10 — T7: "la sosta doppia al capolinea".** `.org` riga 2588. La sosta raddoppia all'estremo dove
il treno inverte la marcia (`AutomaViaggio.java:234-238`). Al capolinea marcato (FERMO_CAPOLINEA,
`:226-229`) non si inverte e la sosta è normale, e "capolinea" nel documento vuol dire proprio
questo.

**E11 — T9: il ritardo non porta "l'ora della misura".** `.org` riga 2758. `TrainGateway.java:114`
salva l'istante in cui l'alert arriva al treno (`Instant.now()`), non quello della misura. La stessa
frase sta nel javadoc di `TrainIngestion.getViaggio`.

**E12 — T9: "la Centrale annuncia lo stato prima di pubblicare la chiusura".** `.org` righe
2823–2827. Vale per "risolvi" (`ServeCentraleOperativa/.../GestoreEventi.java:753-754`), ma non per
il ritorno del battito (M4: `:455` il RESOLVED, poi `:457` lo STATO_STAZIONE) né per i guasti
lasciati da un convoglio (`:749-751`, prima dello stato). La conclusione della nota resta vera: i
due messaggi arrivano nell'ordine che capita.

**E13 — T10: corpo vuoto o campo di tipo sbagliato danno 500.** `.org` riga 2896, l'arco verso
"Richiesta rifiutata (400)". Con un corpo vuoto Gson restituisce null e `TrainIngestion` fa una
NullPointerException (`:120-121`, `:140-142`, `:162-163`). Con un campo del tipo sbagliato (per
esempio `"descrizione": 5`) fallisce il cast a String. In tutti e due i casi Javalin risponde 500.
Il 400 vale solo per un corpo che non è un oggetto JSON e per la POST `/sensore/passaggio` senza i
due campi. È il D11 esteso alle altre due POST; nel diagramma manca l'esito 500.

**E14 — T11: "chi era trattenuto resta fermo" sull'arco Chiusa dalla Centrale → Agibile.** `.org`
riga 3006. Non vale per la strada T3 quando il treno è BLOCCATO_IN_STAZIONE e le fermate sono
cambiate: il riaggancio ricontrolla la chiusura con gli stati appena arrivati
(`AutomaViaggio.java:286-296` e `:322-330`) e, se la stazione arriva ONLINE o OFFLINE e non è
nell'insieme dei guasti, il treno torna IN_STAZIONE. Lo dicono T5 (l'arco BLOCCATO_IN_STAZIONE →
IN_STAZIONE) e la nota sotto *Impercorribile*; l'arco no.

**E15 — T11: la nota contraddice gli archi verso "Chiusa da tutti e due".** `.org` righe
3016–3018: "un GUASTO_STAZIONE o uno STATO_STAZIONE GUASTA/MANUTENZIONE che arriva per una stazione
già chiusa non cambia stato qui". Gli archi alle righe 3001 e 3002 lo cambiano (da *Chiusa dal
campo* o da *Chiusa dalla Centrale* a *Chiusa da tutti e due*). La frase è vera solo per una
stazione già chiusa per lo stesso motivo.

### Stazione

**E16 — S1: ordine invertito fra sensori attesi e causa del guasto.** `.org` righe 3224 e 3247:
la causa del guasto sta nello stato *Ripristino* e i sensori attesi sull'arco che ne esce. Nel
codice è al contrario: `Stazioni/.../main.java:87` mette i sensori sotto osservazione, `:93`
ricostruisce la causa e `:94-97` segna muti i sensori.

**E17 — S5: se la persistenza non scrive, l'evento non sopravvive al riavvio.** `.org` righe 3607,
3616–3618 e 3647–3650. Se la scrittura su `bufferChache` fallisce, `PersistenzaLocale` scrive solo
un log (`:527-530`); con `stazione.db.abilitata=false` non si scrive niente (`:82-86`). In tutti e
due i casi gli archi "riavvio del nodo / ripristinaDaDisco, stesso ordine" non valgono: l'evento è
solo in RAM e al riavvio si perde (D10).

**E18 — Testate che citano file `.puml` inesistenti.** Righe 3067–3083 (S0), 3197 (S1), 3488
(S4), 3582 (S5), 4073 (S10), 4152 (S11), 4226–4238 (M0), 4357 (M1), 4465–4466 (M2), 4548 (M3),
5362 (M9) e 5851–5852 (M13). Sono commenti `'` e nel PDF non escono. Il punto era già aperto il
19/11 per la stazione; c'è anche nelle macchine della Centrale.

### Centrale

**E19 — M2: per le stazioni non c'è una `SELECT COUNT(*)`.** `.org` riga 4493.
`RailwayRepository.java:71-73`: `esisteStazione` legge la riga con `trovaStazione(id)` e guarda se
è null. La `COUNT(*)` c'è solo per i treni (`:163-165`).

**E20 — M5: seconda ENTRATA e contatore dell'uscita.** `.org` righe 4845–4848. Una seconda ENTRATA
dello stesso treno nella stessa stazione (S11 la disegna, *Presente → Presente*) apre una seconda
riga e lascia aperta la prima (`ServeCentraleOperativa/.../GestoreEventi.java:370-374`): manca
l'arco da *Transito aperto*. Sull'arco "USCITA senza transito aperto" manca il treniInStazione − 1,
che il codice fa sempre (`:342-347`, il contatore non scende sotto zero).

**E21 — M7: manca Risolto → Risolto.** `.org` righe 5110–5114. `RailwayRepository.java:530-533`
cerca il guasto senza guardare se è già risolto, e `suRisolviGuasto` lo richiude (javadoc a
`GestoreEventi.java:664-665`): nuovo istante di chiusura e RESOLVED ripubblicato. Per S9 quel
RESOLVED basta a rimettere ONLINE la stazione (`Stazioni/.../GestoreEventi.java:274-289`), anche
se è GUASTA per un altro guasto.

**E22 — M9: dal rinnovo si esce anche con Keycloak irraggiungibile.** `.org` riga 5399 ("refresh
rifiutato"). `ClientWebAppIntefacciaUtente/src/api/keycloak.ts:227-230` restituisce null anche
quando la richiesta non arriva a Keycloak, e `store/authStore.ts:65-74` in quel caso fa uscire
l'utente. È il D05.

**E23 — M10: manutenzione, POST del treno e 404.**
- Nota a destra di *Comando operativo* (`.org` righe 5535–5537): fra gli effetti di "manutenzione"
  manca STATO_STAZIONE MANUTENZIONE (`GestoreEventi.java:823`), che è il messaggio che ferma i treni
  (T9, T11).
- Arco *Scrittura CRUD* (riga 5520): `POST /api/treni` accetta un campo `stato` e non lo controlla
  (`RestApiGateway.java:366`). Un valore fuori vocabolario fallisce sul CHECK di `Treni.stato`
  (`schema.sql:72`) e la risposta è 500, non 400.
- Arco *Lettura* (riga 5518): rispondono 404 anche `GET /api/stazioni/{id}/treni-attesi` per una
  stazione sconosciuta (`RestApiGateway.java:308-311`) e `GET /api/prossima-stazione` (`:906`).

**E24 — M12: avvio, PUT ed eliminazione.**
- Riga 5708: all'avvio una stazione può partire anche in MANUTENZIONE
  (`TrafficLogicEngine.java:129-141`, `riprendiInterventiAperti`). Oggi lo dice solo la nota sopra
  MANUTENZIONE: manca l'arco `[*] → MANUTENZIONE`.
- Righe 5735–5737: la PUT non porta la stazione "in uno qualsiasi dei quattro stati" ma in
  qualunque valore. `StatiCanonici.stazioneDallOperatore` (`:81-85`) toglie solo gli spazi e mette
  in maiuscolo, `AutomaStazione.imponiDallOperatore` (`:141-143`) lo scrive e la Centrale lo
  pubblica ai treni. Il difetto del codice è già fra i noti di `errori_del_codice.md`; la nota lo
  nasconde.
- Riga 5732: l'eliminazione parte da qualunque stato (`RestApiGateway.java:272-294` non guarda lo
  stato), non solo da OFFLINE.

**E25 — M13: creazione, eliminazione e autoanello del watchdog.**
- Riga 5872: `POST /api/treni` può creare il treno nello stato scritto nel corpo
  (`RestApiGateway.java:366`). La web app non lo manda, quindi di solito è "fermo".
- Righe 5895–5896: l'eliminazione vale da qualunque stato (`RestApiGateway.java:474-492`), e
  cancella i transiti vivi e pubblica lo STOP sempre, non solo da *in manutenzione*.
- Riga 5893: l'autoanello del watchdog sta su *attivo*, ma il caso tipico è un treno *fermo* in
  tratta (trattenuto da una stazione chiusa, dichiara FERMO a velocità zero). La nota lo dice,
  l'arco no.

---

## Contraddizioni fuori dai diagrammi

Non sono errori dei diagrammi, ma toccano le stesse cose e all'orale portano alla stessa domanda.

- T9 (righe 2768–2778), S3 (righe 3438–3441) e M8 (righe 5280–5287) dicono che il guasto del
  watchdog è WARNING apposta e che i treni non si fermano davanti a una stazione OFFLINE, ed è quello
  che fa il codice. RF02.6.1 (righe 731–732) e il javadoc di `suControlloHeartbeat`
  (`ServeCentraleOperativa/.../GestoreEventi.java:881-883`) dicono invece che i convogli diretti alla
  stazione caduta si fermano. È il D01: qui hanno ragione i diagrammi.
- La riga "requisiti coperti" di T4 (riga 2120) dice che RF02.1.2.2.1 resta scoperto; RF02.1.2.2.1
  stesso (righe 517–523) e M6 (riga 4929) lo danno completo.
- Le righe "requisiti coperti" di T5 (riga 2235) e T11 (riga 2947) danno completi RF02.1.1.2.1 e
  RF02.1.1.2.2, ma con il D02 (RESOLVED parziale) un treno può entrare in una stazione ancora
  GUASTA. T11 disegna bene quello che fa il codice: è la riga dei requisiti a essere ottimista.

## Che cosa fare

Le correzioni si fanno solo nel `.org`, disegnando il codice com'è. Una parte dipende però da
difetti del codice già elencati in `errori_del_codice.md`: E01 (D03), E13 (D11), E17 (D10), E22
(D05), il secondo punto di E24 (la PUT senza validazione) e il terzo caso di E03 (il treno
soppresso che torna in servizio). Se quei difetti si correggono nel codice, i diagrammi relativi
vanno ridisegnati un'altra volta, quindi conviene decidere prima che cosa cambiare nel codice.
