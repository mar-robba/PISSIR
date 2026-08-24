# Requisiti non completati: riscontro con la consegna

Questo documento confronta i requisiti individuati nell'analisi del codice con
la specifica del docente, [`Progetto finale 2025_26.pdf`](../Progetto%20finale%202025_26.pdf).

In tabella restano solo i requisiti che il codice **non** soddisfa per intero:
quelli parziali, con accanto il pezzo che manca, e quelli non realizzati. I
requisiti verificati come completi sono stati tolti e sono elencati nella
sintesi in fondo.

Legenda:

- **Inerente**: richiesto esplicitamente dal PDF, oppure necessario per realizzare un suo comportamento esplicito.
- **Inerente, ma opzionale**: il PDF lo ammette espressamente come funzionalita` eventuale.
- **Parzialmente inerente**: il nucleo e` previsto dal PDF, mentre alcuni dettagli del requisito sono una scelta progettuale aggiuntiva.
- **Non inerente (estensione)**: non e` richiesto dalla consegna; puo` comunque essere un miglioramento valido.

## Requisiti Inerenti

| Requisito / stato rilevato | Riscontro con il PDF | Motivazione breve |
|---|---|---|
| **RF01.4.1 — Invio della squadra di manutenzione** *(parzialmente implementato)*: `dispacciaManutenzione` porta la stazione a `MANUTENZIONE`, chiude i guasti aperti e la riporta a `ONLINE` nella stessa chiamata; lo stato non rimane osservabile e non viene storicizzato. | **Inerente** | Per un guasto permanente la centrale deve poter inviare operatori e la stazione deve restare inutilizzabile fino al ripristino. Rendere osservabile lo stato di manutenzione e` quindi coerente con la procedura richiesta. |
| **RF01.4.2 — Presa in carico e chiusura di un allarme** *(parzialmente implementato)*: c'e` solo `POST /allarmi/{id}/risolvi`, che chiude e fa riprendere la circolazione; non esiste un comando di presa in carico e il campo `Guasto.operatore` non viene mai valorizzato. | **Parzialmente inerente** | Il PDF richiede il ripristino da parte di operatori inviati da un tecnico; chiusura del guasto e ripresa della circolazione sono quindi pertinenti. La registrazione formale di assegnatario e timestamp della presa in carico non e` invece prescritta. |
| **RF02.1.1.2.3 — Blocco del convoglio gia` fermo nella stazione guasta** *(parzialmente implementato)*: il convoglio si trattiene se la stazione e` in `trainDB.stazioniGuaste`, insieme riempito dagli alert MQTT; un convoglio avviato dopo l'allarme, o che quell'alert non l'ha ricevuto, non ha modo di sapere che la stazione non e` percorribile, perche` all'avvio non risincronizza lo stato con la centrale. | **Inerente** | La specifica dice che, durante il guasto, la stazione lo comunica a ogni treno entrante e il treno non riparte finche` il guasto non viene ripristinato. |
| **RF02.1.3 — Ciclo di vita del guasto** *(parzialmente implementato)*: `Guasto` ha il solo booleano `risolto`, quindi esistono soltanto aperto e chiuso; mancano lo stato intermedio di presa in carico e l'assegnatario. | **Parzialmente inerente** | Rilevare, segnalare, riparare e ripristinare un guasto e` previsto dal PDF. Stati intermedi, assegnatario e storico dettagliato del ciclo sono invece scelte di progettazione non richieste esplicitamente. |
| **RF02.5.2 — Convoglio ancora assegnato dopo la modifica dell'itinerario** *(non realizzato)*: all'arrivo di `ITINERARIO_AGGIORNATO` il motore di viaggio azzera `viaggioAvviato` e `avviaViaggio` riparte da `indiceStazione = 0`, quindi il convoglio ricomincia dalla prima tappa anziche` dalla propria posizione. | **Inerente** | L'amministratore puo` modificare le stazioni attraversate dai treni e la centrale deve gestire il viaggio di ciascun convoglio. Conservare il punto corrente evita di contraddire tale gestione quando l'itinerario viene modificato a caldo. |

## Requisiti completati dopo l'analisi

- **Heartbeat e fail-stop dei sensori di binario** *(completato il 21 agosto 2026)*: il
  PDF chiede il keepalive fra i sensori e la stazione, e che la stazione che non lo
  riceve piu` si dichiari guasta alla Centrale. Il codice aveva solo meta` del giro:
  l'endpoint `POST /stazione/sensore/heartbeat` e un controllo periodico dentro
  `HeartbeatGenerator`, ma nessun sensore che battesse davvero, quindi la mappa dei
  keepalive restava vuota e il controllo non scattava mai; e comunque, allo scadere del
  timeout, il sensore veniva cancellato dalla mappa, per cui il ritorno dei battiti non
  poteva piu` essere notato. Adesso:
  - `SensoreBinario` + `SimulatoreSensori` sono i due dispositivi del binario (ingresso e
    uscita, un binario solo per ora). Parlano con la stazione in REST, come da scelta di
    progetto per i sensori, e sono loro a rilevare i transiti: un sensore in fail-stop non
    manda il keepalive e non vede passare niente.
  - `MonitorSensori` e` il lato stazione: registra i sensori attesi all'avvio, li mette in
    timeout se tacciono (guasto `sensore_offline` CRITICAL, stazione `GUASTA`) e la rimette
    operativa al ritorno del primo battito, distinguendo pero` i guasti causati dai sensori
    da quelli dichiarati dalla Centrale, che non deve chiudere lui.
  - la Centrale accetta ora anche i `RESOLVED` che arrivano dal campo
    (`IngestionService.chiudiGuastoRientratoDalCampo`), cosi` l'allarme si chiude da solo
    quando la condizione rientra, come gia` succedeva per i guasti dedotti dal watchdog;
    se sulla stazione c'e` una squadra di manutenzione non si chiude niente.
  - la console dei sensori della stazione permette di spegnere e riaccendere l'heartbeat
    di un sensore per provare il meccanismo (`POST /stazione/sensori/{id}/stop|start`).
  Le prove stanno in `Stazioni/src/test/java/it/uni/reti2/SensoriFailStopTest.java`.

- **RF04.3 — Protezione dei canali** *(completato il 21 agosto 2026)*: mancava il
  canale delle credenziali, l'unico rimasto in chiaro. Keycloak non gira piu` in
  `start-dev` ma in modalita` di produzione con il solo listener HTTPS
  (`KC_HTTP_ENABLED=false`, porta 8543, certificato firmato dalla CA privata del
  progetto): la vecchia 8180 non risponde piu`. La centrale lo raggiunge su
  `https://localhost:8543/realms/railway` e ne valida il certificato con un
  truststore PEM (`quarkus.tls.keycloak.trust-store.pem.certs`), il realm pretende
  SSL (`sslRequired: "all"`) e la web app e` servita in https dal dev server di
  Vite, cosi` anche la pagina da cui parte il login e quella su cui torna il
  codice di autorizzazione viaggiano cifrate.
