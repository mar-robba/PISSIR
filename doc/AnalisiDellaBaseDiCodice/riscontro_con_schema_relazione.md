# Riscontro della documentazione finale con lo schema della relazione

Questo documento confronta
[`DocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario.org`](../documentazioneFinale/DocumentazioneDelSistemaMonitoraggioEGestioneDelTrafficoFerroviario.org)
con [`Schema del rapporto finale.pdf`](../../Schema%20del%20rapporto%20finale.pdf), la guida del
docente su come deve essere strutturato un rapporto tecnico. Lo schema chiede sei capitoli; qui si
controlla, uno per uno, se e dove ciascuno e` coperto.

## Sintesi

| # | capitolo richiesto | stato |
|---|---|---|
| 1 | Specifiche funzionali | coperto |
| 2 | Analisi delle scelte tecnologiche disponibili | **mancante** |
| 3 | Scelta dell'approccio tecnologico | **mancante** |
| 4 | Architettura del software | coperto (un diagramma da ridisegnare) |
| 5 | Descrizione dell'implementazione | parziale (manca l'interfaccia utente) |
| 6 | Validazione del software | coperto (aggiunto il 21 agosto 2026) |

## 1. Specifiche funzionali — coperto

`* Specifica` copre casi d'uso, glossario, RF01-RF04, RNF01-06 e gli scenari di verifica SV01-SV06.
Manca solo la premessa che lo schema chiede esplicitamente, cioe` le due classi di funzionalita`
che distinguono il progetto (*"controllo locale tramite sensori ed attuatori"* e
*"accessibilita` via Internet di un sistema in rete privata"*): nel testo attuale non e` mai
dichiarato in questi termini, anche se poi tutta l'architettura la rispetta.

## 2. Analisi della tecnologia — mancante

Lo schema chiede tre cose specifiche, e nel documento non c'e` traccia di nessuna delle tre in un
capitolo dedicato:

- i vincoli di rete (*"reti private senza configurazioni sull'apparato di rete"*,
  *"accessibile dalla rete pubblica senza differenze rispetto a quella interna"*): `Internet` non
  compare mai nel testo, ne` `rete privata`, ne` `firewall`;
- le caratteristiche del traffico dati da sostenere;
- i vincoli di tempo reale.

Il materiale per gli ultimi due punti in realta` esiste gia`, solo che sta nel capitolo sbagliato:

- il conteggio del traffico (*"circa 2.000 aggiornamenti al secondo su una flotta di diecimila
  treni"*) sta in `** Scelte progettuali`, sezione *Lo stato corrente della rete sta in RAM*;
- i vincoli di tempo reale sono la tabella `** Parametri temporali` (`P_tel`, `P_bat`, `T_sil`,
  `T_fer`...) con i vincoli fra i parametri, e stanno in `* Progettazione`.

Manca completamente un confronto fra approcci di rete alternativi (es. polling REST periodico,
protocolli diversi da MQTT) che lo schema chiede come premessa al capitolo successivo.

## 3. Scelta dell'approccio — mancante

Lo schema chiede letteralmente: *"Quali vantaggi offre a vostro parere questa scelta [il message
broker]? Come la giudicate rispetto ad altre che avete analizzato nel capitolo precedente?"*.
`broker` compare 89 volte nel documento, sempre in senso descrittivo (dove sta, come si chiama, su
quale porta); la parola `vantagg` non compare mai. Non c'e` un solo paragrafo che argomenti la
scelta del broker rispetto ad alternative (polling, connessioni dirette, altri protocolli).

Il ragionamento in parte esiste gia`, ma fuori dalla relazione finale: in
[`doc/scheletroDoc/scheletrodoc.md`](../scheletroDoc/scheletrodoc.md) c'e` la nota sul perche` i
sensori parlano con la stazione via REST e non via MQTT — e` materiale di questo capitolo, va
recuperato e argomentato.

## 4. Architettura del software — coperto

Il documento ha 66 diagrammi PlantUML: 38 diagrammi degli stati, 19 di sequenza, 7 delle classi, 1
delle componenti, 1 dei casi d'uso. Quello che lo schema chiede per l'architettura — moduli,
distribuzione della funzionalita`, attivita` (thread) e come comunicano — c'e` tutto:
`** Diagramma delle componenti` per i moduli e le interfacce, `*** Il componente ... (C1-C6)` per
la distribuzione della funzionalita`, i 19 diagrammi di sequenza per la comunicazione fra
componenti con topic e payload reali. I thread e le strutture condivise sono documentati ma sparsi
nelle note dei diagrammi (es. `stazioniGuaste` come `Set` concorrente, i tre job del
`FaultMonitor`, il registro sessioni thread-safe): andrebbero raccolti in una tabella riassuntiva,
ma non e` materiale mancante.

L'unico scarto reale e` di notazione, non di contenuto. `Progetto finale 2025_26.pdf` (riga 186)
dice: *"completare gli esagoni con le opportune interfacce"*. Il diagramma delle componenti usa la
notazione UML classica (`component` + `interface` a lecca-lecca), non gli esagoni che il docente
nomina esplicitamente. Il contenuto e` corretto e completo (`IApiOperatore`, `ITopicTree`,
`ISensoriStazione`, porte e topic reali presi dal codice), va solo ridisegnato nel formalismo
richiesto.

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

## 6. Validazione — coperto (aggiunto il 21 agosto 2026)

Fino al 21 agosto 2026 questo capitolo non esisteva: gli scenari SV01-SV06 (specifica del
comportamento atteso) e i 57 test automatici del codice non comparivano mai nella relazione. E`
stato aggiunto `* Validazione` in fondo al documento, con:

- la strategia su tre livelli (test automatici, esecuzione degli scenari, requisiti non
  funzionali);
- la tabella dei 57 test automatici per classe, con esito reale dell'esecuzione (0 fallimenti);
- il paragrafo sulle prove di regressione (`RegressioniBugTest`, sette test che chiudono altrettanti
  bug trovati nel codice);
- la procedura per provocare ciascuno scenario SV01-SV06 con gli endpoint REST veri dei nodi di
  campo;
- la tabella di misura per RNF01-06;
- i limiti dichiarati (i test si fermano al confine del nodo, `Stazioni` non ha test propri, gli
  scenari si eseguono a mano).

Restano da fare, a differenza degli altri capitoli, non di scrittura ma di esecuzione: gli scenari
SV01-SV06 e gli RNF sono descritti con "come si provoca" e "dove si osserva", ma non ancora con
l'esito osservato — vanno eseguiti a mano sul sistema acceso e la colonna dell'esito va compilata.
