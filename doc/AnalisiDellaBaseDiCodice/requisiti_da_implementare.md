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
| **RF02.7 — Memoria storica** *(parzialmente implementato)*: transiti, cambi di stato e guasti vengono storicizzati davvero; per itinerari percorsi (`Storico_Itinerari`, `Storico_Itinerari_Tratte`), assegnazioni degli operatori (`Storico_Assegnazioni_Guasti`) e interventi di manutenzione (`Storico_Interventi_Manutenzione`) esistono le entita` JPA e le tabelle, ma nessun punto del codice ci scrive una riga. | **Parzialmente inerente** | Lo storico dei transiti e` richiesto esplicitamente. La conservazione degli itinerari effettivamente percorsi e delle assegnazioni degli operatori migliora audit e tracciabilita`, ma non e` menzionata nel PDF. |
| **RF04.3 — Protezione dei canali** *(parzialmente implementato)*: i canali interni sono protetti nel profilo dedicato, mentre Keycloak gira in `start-dev` e la centrale lo raggiunge su `http://localhost:8180`, quindi il canale su cui passano le credenziali resta in chiaro. | **Inerente** | Il PDF dichiara che non implementare TLS comporta una penalita` di 5 punti; la protezione del canale che trasporta credenziali e` pertanto direttamente pertinente alla consegna. |


