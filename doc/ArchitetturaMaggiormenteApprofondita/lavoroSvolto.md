# Lavoro Svolto — riepilogo completo delle modifiche (sessione 2026-07-19 Claude code)


Questo documento riassume TUTTO ciò che è stato fatto sul progetto in questa sessione:
i bug trovati, le funzionalità aggiunte, i file toccati e lo stato di verifica.
Documenti collegati:

- `doc/ArchitetturaMaggiormenteApprofondita/architettura.md` — architettura implementata,
  diagrammi a stati (stazione e treno), tabella requisiti del prof → codice
- `BrokerMosquitto/doc/alberoDeiTopic.md` — topic MQTT e payload definitivi (riscritto)
- `doc/TLS.md` — variante TLS (certificati, profilo `%tls`)

---

## 1. Frontend — sezione Amministratore (prima parte della sessione)

Implementata l'area esclusiva admin con tutte le capacità richieste:

- **Nuovi componenti**: `TrainEditorModal.tsx` (crea/modifica treno: convoglio, tratta,
  stato, passeggeri, ritardo) e `StationEditorModal.tsx` (crea/modifica stazione: codice,
  nome, stato, binari, coordinate; codice bloccato in modifica, unicità verificata).
- **`AdminPage.tsx`**: terza tab "Gestione Stazioni"; pulsanti Nuovo/Modifica/Elimina per
  treni e stazioni (sostituito il vecchio `alert('Mock: ...')` di UC8); l'eliminazione di
  una stazione avvisa se è usata da tratte.
- **`apiClient.ts`**: nuove chiamate `createTrain/updateTrain/deleteTrain`,
  `createStation/updateStation/deleteStation` (POST/PUT/DELETE su `/api/treni` e
  `/api/stazioni`).
- **`railwayStore.ts`**: azioni `adminCreate/Update/Delete...` persistite via API.
- **Bug gravi corretti nel frontend**: `addRoute`/`updateRoute`/`deleteRoute` e
  `acknowledgeAlert` modificavano solo lo stato locale senza MAI chiamare le API (che già
  esistevano): al refresh spariva tutto. Ora chiamano il backend.

## 2. Analisi dei gap (lettura integrale di codice e documentazione)

Letti: PDF del prof (`doc/MaterialeProf/`), considerazioni aggiuntive, piani in `doc/`,
tutto il codice dei 3 microservizi + frontend + infrastruttura. Bug bloccanti trovati:

| # | Bug | Effetto |
|---|---|---|
| 1 | Topic guasti stazione = `railwayerts` (typo in application.properties) | i guasti stazione non arrivavano MAI alla centrale |
| 2 | Login senza `{token,user}`, password mai verificata, ruoli DB≠frontend | ruolo admin inutilizzabile dalla UI |
| 3 | Dashboard con nomi campi sbagliati (`treniTotali` vs `totalTrains`) | dashboard vuota |
| 4 | Eventi WebSocket con `trenoId/stazioneId` ma frontend legge `trainId/stationId` | aggiornamenti realtime ignorati |
| 5 | `onPassaggio` della centrale VUOTO | topic `railway/train/+/passaggio` scartato |
| 6 | RESOLVED pubblicato con l'id dell'allarme, stazione cerca il proprio id | il ripristino guasti non funzionava mai |
| 7 | Buffer store-and-forward della stazione reinviava solo a log | perdita dati (requisito chiave del prof) |
| 8 | CRUD tratte creava solo la riga `Itinerari` (niente tratte componenti); binding JSON rotto | creazione tratte dalla UI fallita |
| 9 | Nessun rilevamento fail-stop, nessun allarme automatico, nessun calcolo ritardi, treno con random-walk GPS senza itinerario | requisiti centrali del prof assenti |

## 3. Contratto di integrazione

Scritto un contratto unico (topic MQTT, payload JSON esatti, API REST, estensioni DB,
macchine a stati) usato come fonte di verità da tutta l'implementazione. È riportato in
forma definitiva in `architettura.md` e `alberoDeiTopic.md`. Punti chiave:

- alert su `railway/alerts` con discriminante `tipoEvento`
  (GUASTO/RESOLVED/STOP/MAINTENANCE_DISPATCHED/ITINERARIO_AGGIORNATO)
- le stazioni si sottoscrivono a `railway/train/+/passaggio` e filtrano il proprio id
  (il treno non conosce host/porta delle stazioni)
- transito ufficiale storicizzato = quello della stazione (protetto dal buffer)

## 4. Implementazione per componente

### ServeCentraleOperativa
- **Entità**: `Stazione` +latitudine/longitudine/binari persistiti (prima la mappa era
  tutta a 0,0); `Tratta` +tempoPercorrenzaMinuti; `Guasto` con
  tipo/severita/sorgenteTipo/sorgenteId/messaggio/ts_apertura/ts_risoluzione persistiti
  (indispensabile per il RESOLVED indirizzabile); `Utente` +password (con `@JsonIgnore`);
  `Treno` +campi transient per la UI (ritardo, passeggeri, progresso, stazioneCorrente,
  prossimaStazione, direzione).
- **IngestionService**: transiti con apertura/chiusura ENTRATA/USCITA e casi limite
  (partenza = "esce senza entrare", capolinea = transito aperto); `onPassaggio`
  implementato (posizione/tratta corrente del treno); telemetria estesa con
  storicizzazione stato SOLO ai cambi; alert nel nuovo formato; TUTTI gli eventi
  WebSocket con i campi che il frontend legge davvero.
- **FaultMonitor (nuovo)**, tre job `@Scheduled` ogni 10 s: heartbeat mancante →
  OFFLINE + guasto automatico senza duplicati (fail-stop); treni fermi fuori stazione →
  allarme "treno fermo"; snapshot KPI via WebSocket (il "polling ogni 10 s" della specifica).
- **RestApiGateway**: dashboard col contratto frontend; CRUD tratte reale con DTO
  (crea/riusa le `Tratte` per coppie di stazioni, `Itinerario_Tratta` ordinate, assegna i
  treni, DELETE a cascata pulita); CRUD treni e stazioni nuovi; `GET /api/treni/{id}/itinerario`
  (bootstrap del twin); `GET /api/prossima-stazione?treno&stazione&direzione` (requisito
  prof); risoluzione allarmi e manutenzione stazione che pubblicano RESOLVED con la
  sorgente reale; soppressione treno con STOP mirato.
- **AuthController**: `{token, user}`, verifica password, ruoli `admin→amministratore`.
- **Seed**: `import.sql` e `populate_db.sql` idempotenti con coordinate reali, binari,
  tempi di percorrenza e password.

### Stazioni
- Fix topic `railwayerts` → `railway/alerts`.
- Buffer store-and-forward VERO: elementi `{canale, payload}` e `flush()` che reinvia
  sugli emitter corretti; con la connessione simulata giù l'heartbeat viene soppresso
  (così la centrale rileva anche il fail-stop in demo).
- Sottoscrizione ai passaggi treno (filtro sul proprio id → transit verso la centrale;
  se guasta e un treno ENTRA → alert che lo trattiene).
- Keepalive sensori: `POST /stazione/sensore/heartbeat`, timeout → guasto WARNING
  una-tantum alla centrale.
- Endpoint demo: `POST /stazione/rete/offline|online`, `GET /stazione/treni`.
- `main` non esce più senza argomento (fallback su property: il dev mode non passa args).

### Treni — digital twin (requisito per la sufficienza)
- **`TrainJourneyEngine` (nuovo)**: itinerario scaricato dalla centrale via REST (retry
  15 s), macchina a stati IN_STAZIONE→USCITA→IN_VIAGGIO→ENTRATA, tempi scalati
  (`viaggio.fattore.accelerazione`), GPS interpolato tra le coordinate delle stazioni,
  inversione al capolinea (sosta doppia), prima partenza con sola USCITA, passeggeri
  random a ogni fermata, blocco su stazione guasta con accumulo ritardo e ripartenza
  sul RESOLVED, soppressione definitiva su STOP.
- Telemetria realistica dal twin (niente più random-walk), payload completo.
- Fix bug preesistente: il guasto treno impostava lo stato a `"Grave"` → ora EMERGENZA.

### TLS (variante 6 del prof: −5 punti se assente)
- `BrokerMosquitto/tls/gen-certs.sh` eseguito: CA self-signed + cert broker (SAN
  localhost/127.0.0.1) + cert HTTPS centrale, verificati con `openssl verify`.
- Mosquitto: listener TLS 8883 (1883 mantenuto); docker-compose aggiornato.
- Profilo `%tls` nei tre servizi (MQTT su 8883 con truststore PEM; HTTPS centrale :8444).
- Attivazione: `-Dquarkus.profile=tls` (vedi `doc/TLS.md`).

## 5. Verifica incrociata (revisori) e fix applicati

Due revisori adversariali hanno controllato producer/consumer campo per campo. 10
finding, tutti corretti:

1. **[bloccante]** Stati treno UI in MAIUSCOLO rifiutati dal CHECK del DB → frontend ora
   invia i valori canonici minuscoli e il backend li normalizza comunque.
2. Stati stazione: mappatura esplicita nei due sensi (`ONLINE↔operativa`, ecc.) — prima
   il load iniziale mostrava tutte le stazioni come guaste.
3. `GET /api/allarmi` ora espone `sorgenteTipo`: il frontend non assegna più l'id di un
   treno a `stationId` (e viceversa).
4. Password mai serializzata nelle risposte (`@JsonIgnore`) — prima usciva in chiaro
   dentro `POST /allarmi/{id}/risolvi`.
5. ID transiti/guasti con suffisso random: il flush del buffer a raffica non causa più
   violazioni di chiave primaria (due eventi nello stesso millisecondo).
6. Solo i guasti **CRITICAL** bloccano i treni e marcano la stazione GUASTA: i WARNING
   (keepalive sensore) restano informativi (prima un sensore muto fermava i treni e
   faceva "flappare" lo stato della stazione).
7. ID di default allineati al seed: `stazione.id=S1`, `treno.id=TRN001` (prima
   `alessandria`/`REG-1234` non esistevano nel DB e gli eventi venivano scartati).
8. `createRoute/updateRoute` frontend ora riportano nello store il DTO reale del server.

## 6. Migrazione DB (nota operativa)

Il DB `railway` esistente aveva righe create prima delle colonne nuove → NULL su campi
primitivi = crash all'avvio. Risolto in due mosse:

- entity null-safe (`Integer`/`Double` con default al posto dei primitivi);
- `UPDATE` mirati sulle righe pre-esistenti (password, coordinate, binari, tempi) —
  già eseguiti sul DB locale. Su un DB vergine non serve nulla: ci pensano
  Hibernate (`generation=update`) e `import.sql`.

## 7. Stato di verifica

| Verifica | Esito |
|---|---|
| Compilazione ServeCentraleOperativa / Stazioni / Treni | ✅ verdi |
| Type-check frontend (`tsc --noEmit`) | ✅ verde |
| Avvio Centrale (dev mode) su DB reale | ✅ su, in esecuzione |
| `GET /api/dashboard` (nomi contratto frontend) | ✅ |
| `POST /api/auth/login` (password verificata, `{token,user}`, ruolo amministratore) | ✅ |
| `GET /api/treni/TRN001/itinerario` (coordinate + tempi per il twin) | ✅ |
| `GET /api/tratte` (stazioni + travelTimes + treni) | ✅ |
| `GET /api/prossima-stazione?treno=TRN001&stazione=S2&direzione=A` → S3 | ✅ |
| Test end-to-end completo (stazione+treno vivi, passaggi, guasto→blocco→ripristino, buffer offline) | ⏳ da eseguire (vedi sotto) |
| Avvio con profilo `%tls` | ⏳ da provare |

### Come completare il test end-to-end (5 minuti)

```bash
# broker e postgres sono già su (docker)
cd ServeCentraleOperativa && mvn quarkus:dev                     # porta 8781 (già in esecuzione)
cd Stazioni && ./mvnw quarkus:dev                                # stazione S1, porta 8081
cd Treni   && ./mvnw quarkus:dev -Dviaggio.fattore.accelerazione=100   # treno TRN001, porta 8082

# il twin parte da S1: dopo ~20s pubblica USCITA, la centrale registra il transito
curl http://localhost:8781/api/transiti
curl http://localhost:8082/treno/viaggio          # stato del twin

# demo guasto → blocco treno → operatori → ripartenza
curl -X POST http://localhost:8081/stazione/sensore/guasto -H 'Content-Type: application/json' -d '{"descrizione":"Rottura binario 3"}'
curl http://localhost:8781/api/allarmi
curl -X POST http://localhost:8781/api/stazioni/S1/manutenzione   # risolve + RESOLVED via MQTT

# demo caching offline
curl -X POST http://localhost:8081/stazione/rete/offline
curl -X POST http://localhost:8081/stazione/sensore/treno -H 'Content-Type: application/json' -d '{"trenoId":"TRN001","tipo":"ENTRATA"}'
curl http://localhost:8081/stazione/buffer                        # 1 evento in coda
curl -X POST http://localhost:8081/stazione/rete/online           # flush → arriva alla centrale

# frontend
cd ClientWebAppIntefacciaUtente && npm run dev                    # login: MAT001 / password
```
