# Documentazione Frontend - Monitoraggio e Gestione Traffico Ferroviario

Questo documento contiene la documentazione tecnica e le nozioni fondamentali per comprendere e lavorare sul progetto frontend. È stato scritto pensando a sviluppatori che non hanno familiarità con TypeScript e le librerie utilizzate nel progetto.

## Indice
1. [Panoramica del Progetto](#1-panoramica-del-progetto)
2. [Stack Tecnologico](#2-stack-tecnologico)
3. [Struttura delle Cartelle](#3-struttura-delle-cartelle)
4. [Diagramma delle Classi (Modelli di Dati)](#4-diagramma-delle-classi)
5. [Guida alle Tecnologie per Principianti](#5-guida-alle-tecnologie-per-principianti)
   - [TypeScript](#typescript)
   - [React (con Vite)](#react-con-vite)
   - [React Router (Routing)](#react-router)
   - [Zustand (State Management)](#zustand)
   - [Librerie di Supporto (Recharts, Framer Motion, Lucide)](#librerie-di-supporto)
6. [Come avviare il progetto](#6-come-avviare-il-progetto)

---

## 1. Panoramica del Progetto

Il progetto è una Single Page Application (SPA) per il monitoraggio in tempo reale e la gestione del traffico ferroviario. Funge da dashboard per visualizzare treni, stazioni, tratte, allerte in tempo reale, statistiche e invio di squadre tecniche.

---

## 2. Stack Tecnologico

- **Linguaggio**: [TypeScript](https://www.typescriptlang.org/) (JavaScript con tipi statici)
- **Libreria UI**: [React](https://react.dev/)
- **Build Tool**: [Vite](https://vitejs.dev/) (estremamente veloce rispetto a Webpack/CRA)
- **Gestione Stato (State Management)**: [Zustand](https://github.com/pmndrs/zustand)
- **Routing**: [React Router v6](https://reactrouter.com/)
- **Grafici**: [Recharts](https://recharts.org/)
- **Animazioni**: [Framer Motion](https://www.framer.com/motion/)
- **Icone**: [Lucide React](https://lucide.dev/)
- **Stile**: CSS Vanilla (file `index.css` globale con variabili CSS strutturate).

---

## 3. Struttura delle Cartelle

```text
src/
├── api/          # Mock dei dati e servizi API (predisposti per connessione al backend)
├── components/   # Componenti UI riutilizzabili (pulsanti, card, navbar, ecc.)
├── hooks/        # Custom React Hooks (funzioni riutilizzabili che usano lo stato)
├── pages/        # Componenti che rappresentano le intere pagine dell'app
├── store/        # Gestione dello stato globale con Zustand
├── types/        # Definizioni dei tipi TypeScript (interfacce per Stazioni, Treni, ecc.)
├── App.tsx       # Componente radice dell'app e configurazione delle rotte
├── main.tsx      # Entry point dell'applicazione (dove React si attacca all'HTML)
└── index.css     # Stili globali dell'applicazione
```

---

## 4. Diagramma delle Classi

Il diagramma delle classi completo (in formato PlantUML) è disponibile nel file `doc/class_diagram.plantuml`. Il sistema definisce le seguenti entità principali (modelli di dati) nel file `src/types/index.ts`:

- **User**: Dati dell'utente loggato (ruolo: tecnico, amministratore).
- **Station**: Informazioni sulle stazioni e il loro stato operativo (operativa, guasta, ecc.).
- **Train**: Rappresentazione di un convoglio, il suo stato (in viaggio, in stazione), ritardo, e la tratta percorsa.
- **Route**: La tratta percorsa da un treno.
- **Alert**: Avvisi generati dal sistema per notificare problemi (es. treno in ritardo, stazione offline).
- **OperatorDispatch**: Tracciamento di squadre di operatori inviati in una stazione per manutenzione o per risolvere guasti.

---

## 5. Guida alle Tecnologie per Principianti

Questa sezione mira a dare i concetti base per chiunque non abbia mai usato queste librerie prima d'ora.

### TypeScript
**Cos'è?** È un "superset" di JavaScript. Significa che tutto il codice JavaScript valido è anche TypeScript valido, ma TS aggiunge i **tipi statici**.
**A cosa serve?** Aiuta lo sviluppatore a evitare errori (come cercare di accedere a una proprietà di un oggetto che non esiste) poiché il compilatore segnala l'errore in tempo reale nell'editor, prima ancora di avviare il codice.

**Concetti chiave:**
- `interface`: Definisce la "forma" di un oggetto. Dice quali chiavi deve avere e di che tipo sono.
  ```typescript
  interface Treno {
    id: string;
    velocita: number;
    inServizio: boolean;
  }
  ```
- `type`: Molto simile alle interfacce, ma usato anche per unire vari tipi permessi (es. `type Stato = "attivo" | "inattivo"`).
- **Estensioni File**: Un file `.ts` contiene codice logico/tipi. Un file `.tsx` contiene codice React (HTML misto a JS/TS).

### React (con Vite)
**Cos'è?** Una libreria sviluppata per creare interfacce utente componibili. L'interfaccia viene divisa in "Componenti" indipendenti e riutilizzabili.
**Vite** non fa parte di React: è uno strumento per avviare il server di sviluppo molto velocemente e "impacchettare" (build) i file per la produzione.

**Concetti chiave:**
- **Componenti**: Sono normali funzioni JavaScript che restituiscono una sintassi molto simile all'HTML (chiamata JSX o TSX).
  ```tsx
  function Bottone() {
    return <button className="btn">Cliccami</button>;
  }
  ```
- **JSX/TSX**: Per usare del codice JavaScript all'interno del codice HTML, si mettono le parentesi graffe `{}`. Esempio: `<h1>Benvenuto, {utente.nome}</h1>`.
- **Props**: Parametri passati dall'esterno a un componente, simili agli attributi HTML (`<Bottone colore="rosso" />`).
- **State (`useState`)**: Se una variabile dentro un componente deve cambiare e l'interfaccia grafica deve aggiornarsi di conseguenza, si usa lo "Stato". React ri-disegna (re-render) in automatico ciò che cambia.
- **Effect (`useEffect`)**: Una funzione per eseguire azioni speciali nel momento in cui un componente viene caricato (ad esempio, effettuare il fetch dei dati iniziali da un'API).

### React Router
**Cos'è?** Permette la navigazione. Poiché l'applicazione è una Single Page Application (il file HTML caricato dal browser è sempre e solo `index.html`), non ci sono vere "pagine" per il browser. React Router simula il cambio di pagina.

**Concetti chiave:**
- **Link e Navigate**: Invece di usare `<a href="/pagina">`, si usa `<Link to="/pagina">`. Questo permette di cambiare la visualizzazione senza ricaricare tutta l'app da zero.
- **Parametri URL**: Permette rotte dinamiche, come `/treno/123`, dove `123` è l'ID di un treno che il componente leggerà per mostrare i dettagli giusti usando l'hook `useParams()`.

### Zustand
**Cos'è?** È uno strumento per il **Gestione dello Stato Globale** (State Management).
In React, usare `useState` va bene per informazioni locali (es. capire se un menu a tendina è aperto o chiuso). Ma per dati complessi o condivisi da tante pagine (come la lista di tutti i treni o i dati dell'utente autenticato), si usano gli "Store" globali.

**Come si usa qui?**
Troverai i file dentro `src/store/` (es. `railwayStore.ts`). Questi file mantengono i dati dell'applicazione.
In qualsiasi componente React, si può leggere lo stato globale così:
```typescript
const treni = useRailwayStore(state => state.trains);
```
Quando il backend invierà nuovi dati e `useRailwayStore` sarà aggiornato, **tutti** i componenti che stanno leggendo `state.trains` si aggiorneranno all'istante sullo schermo.

### Librerie di Supporto
- **Recharts**: Crea grafici (linee, barre) fornendo semplicemente dei tag stile HTML (es. `<LineChart />`, `<BarChart />`) e un array di dati JSON.
- **Framer Motion**: Serve per animazioni fluide. Cambiando ad esempio un tag `<div>` in `<motion.div>`, puoi aggiungere facilmente un attributo `animate={{ opacity: 1 }}` per farlo apparire gradualmente (fade-in) senza usare CSS complicati.
- **Lucide React**: Fornisce un vasto set di icone da usare direttamente come componenti (es. `<AlertCircle />`, `<TrainFront />`).

---

## 6. Come avviare il progetto

1. Aprire un terminale posizionandosi nella root del progetto (dove si trova il file `package.json`).
2. Se è la prima volta che si apre il progetto, è necessario scaricare le dipendenze:
   ```bash
   npm install
   ```
3. Avviare il server di sviluppo in locale:
   ```bash
   npm run dev
   ```
4. Aprire nel browser il link indicato dal terminale (solitamente `http://localhost:5173`).
