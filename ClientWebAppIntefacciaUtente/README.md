# Web app (interfaccia utente)

SPA React + TypeScript servita da Vite: e' la console con cui tecnico e
amministratore guardano il traffico e mandano i comandi. Istruzioni generali di
avvio nel `README.md` della radice.

## Avvio

```bash
npm install
npm run dev      # https://localhost:5173
npm run build    # compilazione
npm test         # test con vitest (vedi TESTS.md)
```

Il dev server e' in **https** (certificato da `BrokerMosquitto/tls/gen-certs.sh`):
se i certificati non ci sono Vite riparte in chiaro ma il login non funziona,
perche' Keycloak accetta solo redirect verso `https://localhost:5173`.

Per lo stesso motivo la Centrale va avviata con `-Dquarkus.profile=tls`: da una
pagina https il browser blocca le chiamate verso la 8781 in chiaro.

## Dove punta

| cosa | default | override |
|---|---|---|
| REST della Centrale | `https://localhost:8444/api` | `VITE_API_BASE_URL` |
| WebSocket realtime | `wss://localhost:8444/ws/realtime` | `VITE_WS_URL` |
| Keycloak | `https://localhost:8543` | `VITE_KEYCLOAK_URL` |

Il login e' Authorization Code + PKCE: la password si digita sulla pagina di
Keycloak e la web app vede solo il token. Utenti di prova in
`Keycloak/README.txt` (username minuscolo, `mat001`).

## Struttura

- `src/pages/` — una pagina per schermata: dashboard, mappa, treni, stazioni,
  tratte, transiti, allarmi, pannello admin
- `src/api/` — client REST, client WebSocket e flusso OIDC
- `src/store/` — stato globale con zustand (`authStore`, `railwayStore`)
- `src/components/` — pezzi riusabili, divisi per area
