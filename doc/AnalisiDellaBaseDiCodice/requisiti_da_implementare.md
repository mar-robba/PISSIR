# Requisiti non completati: riscontro con la consegna

Questo documento confronta i requisiti individuati nell'analisi del codice con
la specifica del docente, [`Progetto finale 2025_26.pdf`](../Progetto%20finale%202025_26.pdf).

Legenda:

- **Inerente**: richiesto esplicitamente dal PDF, oppure necessario per realizzare un suo comportamento esplicito.
- **Inerente, ma opzionale**: il PDF lo ammette espressamente come funzionalita` eventuale.
- **Parzialmente inerente**: il nucleo e` previsto dal PDF, mentre alcuni dettagli del requisito sono una scelta progettuale aggiuntiva.
- **Non inerente (estensione)**: non e` richiesto dalla consegna; puo` comunque essere un miglioramento valido.

## Requisiti Inerenti

| Requisito / stato rilevato | Riscontro con il PDF | Motivazione breve |
|---|---|---|
| **RF01.4.1 — Invio della squadra di manutenzione** *(parzialmente implementato)*: la stazione passa per `MANUTENZIONE`, ma torna `ONLINE` nella stessa chiamata; lo stato non rimane osservabile. | **Inerente** | Per un guasto permanente la centrale deve poter inviare operatori e la stazione deve restare inutilizzabile fino al ripristino. Rendere osservabile lo stato di manutenzione e` quindi coerente con la procedura richiesta. |
| **RF01.4.2 — Presa in carico e chiusura di un allarme** *(parzialmente implementato)*: chiusura e ripresa della circolazione avvengono, ma non sono registrati operatore incaricato e momento della presa in carico. | **Parzialmente inerente** | Il PDF richiede il ripristino da parte di operatori inviati da un tecnico; chiusura del guasto e ripresa della circolazione sono quindi pertinenti. La registrazione formale di assegnatario e timestamp della presa in carico non e` invece prescritta. |
| **RF01.4.3 — Soppressione di un convoglio fermo in stazione** *(implementato)*: il convoglio viene soppresso solo se si verifica che è fermo in una stazione. | **Inerente, ma opzionale** | Il PDF afferma che l'amministratore puo` **eventualmente** sopprimere treni fermi in stazione. Se la funzione viene offerta, la precondizione e` parte del suo significato; tuttavia l'intera funzione non e` obbligatoria. |
| **RF02.1.1.2.3 — Blocco del convoglio gia` fermo nella stazione guasta** *(parzialmente implementato)*: il convoglio e` trattenuto solo se ha ricevuto l'avviso, non in base affidabile alla non percorribilita` della stazione. | **Inerente** | La specifica dice che, durante il guasto, la stazione lo comunica a ogni treno entrante e il treno non riparte finche` il guasto non viene ripristinato. |
| **RF02.1.2.2.1 — Guasto di un convoglio in stazione** *(implementato)*: se il convoglio si guasta in una stazione, la stazione diventa `GUASTA`/non percorribile e gli altri treni vengono bloccati. | **Parzialmente inerente** | Il PDF cita esplicitamente un treno deragliato che inibisce l'uso di una stazione. La trasformazione automatica in stato `GUASTA`/non percorribile e` una concreta modellazione progettuale, non un vincolo testuale. |
| **RF02.1.3 — Ciclo di vita del guasto** *(parzialmente implementato)*: esistono soltanto gli stati aperto e chiuso; mancano presa in carico, assegnatario e traccia storica. | **Parzialmente inerente** | Rilevare, segnalare, riparare e ripristinare un guasto e` previsto dal PDF. Stati intermedi, assegnatario e storico dettagliato del ciclo sono invece scelte di progettazione non richieste esplicitamente. |
| **RF02.5.2 — Convoglio ancora assegnato dopo la modifica dell'itinerario** *(non realizzato)*: dopo l'aggiornamento il convoglio riparte dalla prima tappa anziche` dalla propria posizione. | **Inerente** | L'amministratore puo` modificare le stazioni attraversate dai treni e la centrale deve gestire il viaggio di ciascun convoglio. Conservare il punto corrente evita di contraddire tale gestione quando l'itinerario viene modificato a caldo. |
| **RF02.7 — Memoria storica** *(parzialmente implementato)*: sono storicizzati transiti, cambi di stato e guasti, ma non itinerari percorsi ne` assegnazioni degli operatori. | **Parzialmente inerente** | Lo storico dei transiti e` richiesto esplicitamente. La conservazione degli itinerari effettivamente percorsi e delle assegnazioni degli operatori migliora audit e tracciabilita`, ma non e` menzionata nel PDF. |
| **RF04.3 — Protezione dei canali** *(parzialmente implementato)*: i canali interni sono protetti nel profilo dedicato, mentre il canale verso il servizio d'identita`, sul quale passano credenziali, resta in chiaro. | **Inerente** | Il PDF dichiara che non implementare TLS comporta una penalita` di 5 punti; la protezione del canale che trasporta credenziali e` pertanto direttamente pertinente alla consegna. |

## Requisiti Non Inerenti

| Requisito / stato rilevato | Riscontro con il PDF | Motivazione breve |
|---|---|---|
| **RF02.1.2.2.2 — Guasto di un convoglio in tratta** *(non realizzato)*: il guasto non rende non percorribile la tratta e non trattiene gli altri convogli. | **Non inerente (estensione)** | Il PDF contempla guasti alle linee nello stato della centrale e un treno fermo tra stazioni come anomalia, ma non impone la propagazione automatica di un guasto di bordo al blocco della tratta ne` il trattenimento degli altri treni. |

## Sintesi

Da implementare per aderire alla specifica: **RF01.4.1**, la parte operativa di
**RF01.4.2**, **RF02.1.1.2.3**,
**RF02.5.2**, lo storico dei transiti di **RF02.7** e **RF04.3**.

**RF02.1.2.2.1** e` implementato: il guasto di un convoglio in stazione rende la
stazione non percorribile.

**RF01.4.3** è implementato. I dettagli di assegnazione/storicizzazione
degli operatori e il ciclo di vita esteso dei guasti sono miglioramenti sensati,
ma non sono un obbligo esplicito. **RF02.1.2.2.2** e` un'estensione del modello
richiesto, non una funzionalita` prescritta dal PDF.
