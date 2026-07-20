# Albero dei Topic MQTT (implementato)

Gerarchia: `railway/{entità}/{id}/{evento}`. Il broker è Mosquitto (porta 1883, TLS 8883 —
vedi `doc/TLS.md`). Tutti i payload sono JSON con decimali col punto e timestamp ISO-8601.

## Tabella riepilogativa

| Topic | Publisher | Subscriber | Quando |
|---|---|---|---|
| `railway/station/{id}/heartbeat` | Stazione | Centrale (`railway/station/+/heartbeat`) | ogni 10 s |
| `railway/station/{id}/transit` | Stazione | Centrale (`railway/station/+/transit`) | sensore rileva ENTRATA/USCITA treno |
| `railway/train/{id}/telemetry` | Treno | Centrale (`railway/train/+/telemetry`) | ogni 5 s |
| `railway/train/{id}/passaggio` | Treno | Centrale **e Stazioni** (`railway/train/+/passaggio`) | il treno transita al sensore di una stazione |
| `railway/alerts` | Centrale, Stazioni, Treni | tutti | eventi: guasti, risoluzioni, comandi |

Note di progetto:

- I **sensori NON usano MQTT**: parlano REST col proprio nodo (scelta documentata in
  `doc/scheletroDoc/scheletrodoc.md`). I topic `transit`/`passaggio` sono la traduzione MQTT
  dell'evento del sensore verso il resto del sistema.
- Le stazioni sono sottoscritte a `railway/train/+/passaggio` e **filtrano** i messaggi con il
  proprio `stazioneId`: così il treno non deve conoscere host/porta delle stazioni.
- Il transito ENTRATA/USCITA distingue il tipo con il campo `tipo` nel payload (non con
  sotto-topic `/in` `/out`): meno canali da configurare in SmallRye e stesso potere espressivo.

## Payload

### `railway/station/{id}/heartbeat`
```json
{"stazioneId":"S1","stato":"ONLINE","timestamp":"2026-07-19T21:00:00Z","tipoEvento":"HEARTBEAT","bufferSize":0}
```

### `railway/station/{id}/transit`
```json
{"stazioneId":"S1","trenoId":"TRN001","tipo":"ENTRATA","timestamp":"2026-07-19T21:00:00Z"}
```

### `railway/train/{id}/telemetry`
```json
{"trenoId":"TRN001","stato":"IN_VIAGGIO","latitudine":45.1234,"longitudine":9.5678,
 "velocita":124.0,"progressPercent":42.5,"ritardoMinuti":0,"passeggeri":210,
 "stazioneCorrente":"","prossimaStazione":"S2","direzione":"andata","timestamp":"..."}
```

### `railway/train/{id}/passaggio`
```json
{"trenoId":"TRN001","stazioneId":"S2","tipo":"ENTRATA","timestamp":"...","ritardoMinuti":0}
```

### `railway/alerts` — discriminante `tipoEvento`
```json
{"tipoEvento":"GUASTO","sorgenteTipo":"STAZIONE","sorgenteId":"S2","severita":"CRITICAL","messaggio":"...","timestamp":"..."}
{"tipoEvento":"RESOLVED","sorgenteTipo":"STAZIONE","sorgenteId":"S2","guastoId":"alert-...","timestamp":"..."}
{"tipoEvento":"STOP","target":"TRN001","motivo":"Soppresso da operatore","timestamp":"..."}
{"tipoEvento":"MAINTENANCE_DISPATCHED","sorgenteId":"S2","timestamp":"..."}
{"tipoEvento":"ITINERARIO_AGGIORNATO","target":"TRN001","timestamp":"..."}
```

Regole di reazione:

- **Treno**: `GUASTO` con `sorgenteId` ∈ {prossima stazione, stazione corrente} → si ferma;
  `RESOLVED` della stazione bloccante → riparte; `STOP` con `target` = proprio id →
  SOPPRESSO definitivo; `ITINERARIO_AGGIORNATO` → riscarica l'itinerario dalla centrale.
- **Stazione**: `RESOLVED` con `sorgenteId` = proprio id → torna ONLINE;
  `MAINTENANCE_DISPATCHED` → log operatori in arrivo.
- **Centrale**: `GUASTO` → persistenza + storicizzazione + inoltro WebSocket al frontend;
  ignora gli eventi emessi da sé stessa (il canale è condiviso).
