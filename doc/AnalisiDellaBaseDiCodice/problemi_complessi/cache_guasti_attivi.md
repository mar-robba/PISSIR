# La mappa `guastiAttivi` non viene mai svuotata

**Componente:** `ServeCentraleOperativa` — `elaboration/TrafficLogicEngine.java`
**Natura:** difetto di progetto, non bug funzionale (nessun output è sbagliato, il costo cresce)

## Sintesi

Il `TrafficLogicEngine` tiene tre mappe in RAM. Due (`treni`, `stazioni`) sono dimensionate
dalla topologia della rete e non crescono nel tempo. La terza, `guastiAttivi`, cresce con
l'uptime e non viene mai potata: ogni guasto aperto dall'avvio resta dentro per sempre.

## Il difetto

`TrafficLogicEngine.java:153`

```java
public void risolviGuasto(String id) {
    if (guastiAttivi.containsKey(id)) {
        Guasto g = guastiAttivi.get(id);
        g.risolto = true;
        guastiAttivi.put(id, g);
        // Non lo rimuoviamo subito dalla cache così il frontend ha tempo di vedere che è stato risolto
    }
}
```

Il guasto risolto viene marcato ma non rimosso. Le entry si accumulano, e il `FaultMonitor`
apre guasti automatici in continuazione (stazione silenziosa oltre `T_sil`, convoglio fermo
oltre `T_fer`), quindi in esercizio prolungato la mappa cresce e basta.

**La giustificazione nel commento non regge.** La dashboard non legge da questa mappa: riceve
la transizione dall'evento WebSocket `broadcastAlert(guasto risolto)`, che viene emesso in
tutti i punti di chiusura. Tenere la entry non serve a nessuno.

## Conseguenza vera: il costo della deduplica

La memoria è la parte meno grave (un `Guasto` sta in poche centinaia di byte). Il problema è
che `getGuastoApertoPerSorgente` (`TrafficLogicEngine.java:196`) è una **scansione lineare**
su tutta la mappa, risolti compresi, ed è sul percorso caldo:

| Chiamante | Frequenza |
|---|---|
| `FaultMonitor.controllaHeartbeat` (`:97`) | ogni 10 s, **per ogni stazione** |
| `FaultMonitor.controllaTreniFermi` (`:137`) | ogni 10 s, **per ogni treno** |
| `IngestionService.onAlert` (`:475`) | per ogni allarme dal campo |

Costo per giro di watchdog: `O(|guastiAttivi| × (|stazioni| + |treni|))`, con il primo fattore
che cresce monotonicamente con il tempo di accensione. È l'unico punto del sistema che
degrada da solo, senza che cambi il carico.

Stesso effetto minore su `kpiDashboard` (`:222`), che filtra correttamente i risolti ma li
attraversa comunque, ogni 10 s.

## Cosa **non** è un problema

Verificato, per non inseguire falsi bersagli:

- `getGuastiAttivi()` (`:167`) restituirebbe anche i risolti, ma **non è chiamato da nessuno**:
  è codice morto. Va rimosso o corretto prima che qualcuno lo usi credendo al nome.
- `GET /api/allarmi` non usa la cache: legge da `Guasto.listAll` sul database
  (`RestApiGateway.java:660`). Nessun allarme risolto trapela nell'elenco.
- I KPI sono corretti: `kpiDashboard` filtra su `!g.risolto`.

## Correzione

Rimuovere la entry alla risoluzione:

```java
public void risolviGuasto(String id) {
    Guasto g = guastiAttivi.remove(id);
    if (g != null) g.risolto = true;   // l'oggetto resta valido per il broadcast RESOLVED
}
```

Da fare insieme: eliminare `getGuastiAttivi()` o rinominarlo, visto che nessuno lo usa.

## Problema separato, stessa origine

Lo stato vive **dentro il processo**, quindi la Centrale non è replicabile: due istanze dietro
un bilanciatore avrebbero due fotografie diverse della rete, e la deduplica dei guasti vale
solo all'interno di un processo — il commento su `@Scheduled` (`FaultMonitor.java:80`) lo dice
già: *"Evita sovrapposizioni nello stesso server"*. Un riavvio perde inoltre lo stato runtime,
e la rete riparte OFFLINE finché non arrivano i battiti.

È un limite di scalabilità orizzontale e disponibilità, non di memoria. Superarlo significa
spostare lo stato condiviso fuori dal processo (Redis, o tabelle *unlogged*), non persistere
la telemetria su PostgreSQL così com'è — le ragioni sono nella sezione *Scelte progettuali*
della documentazione finale.
