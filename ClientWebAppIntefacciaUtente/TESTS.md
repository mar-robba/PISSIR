# Eseguire i test (Vitest + Testing Library)

Breve guida per eseguire e sviluppare i test unitari per la web app frontend.

Prerequisiti
- Node.js e npm installati.

Installazione dipendenze (eseguire nella cartella del frontend):

```bash
cd ClientWebAppIntefacciaUtente
npm install
```

Eseguire tutti i test (comando npm presente in `package.json`):

```bash
npm test
# oppure (esecuzione singola, verbose):
npx vitest --run --reporter verbose
```

Modalità watch (sviluppo rapido):

```bash
npx vitest --watch
```

Eseguire un singolo file di test o una singola suite:

```bash
npx vitest src/components/ui/__tests__/Card.test.tsx
npx vitest -t "Card component"
```

Posizione e convenzioni
- I file di test seguono il pattern `src/**/*.test.{ts,tsx}` o `src/**/*.spec.{ts,tsx}`.
- La configurazione di Vitest è in `vitest.config.ts` e il setup in `src/setupTests.ts`.
- Usare `@testing-library/react`, `@testing-library/jest-dom` e `@testing-library/user-event` per test DOM/interaction.

Problemi comuni
- Errore ETARGET su `vitest`: significa che la versione nel `package.json` non è disponibile nel registry. Per risolvere:

```bash
# verifica le versioni disponibili
npm view vitest versions --json
# oppure installa l'ultima stabile
npm install -D vitest@latest
```

Suggerimenti
- Scrivi test piccoli e isolati. Mocka le dipendenze esterne (es. API, WebSocket).
- Usa `userEvent` per simulare interazioni utente realistiche.
- Esegui `npx vitest --coverage` se vuoi generare report di coverage (configurare se necessario).

Se vuoi, posso aggiungere un comando `npm run test:watch` o un badge nel README principale.
