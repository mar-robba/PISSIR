# Migrazione dell'autenticazione a Keycloak (OAuth2 / OpenID Connect)

**Data intervento:** 10/08/2026
**Punto di partenza:** login tradizionale (variante 2 del PDF) — `AuthController` + `SessioniAttive` + `FiltroAutorizzazione`
**Punto di arrivo:** architettura base del PDF — OAuth2/OIDC con Keycloak, flusso Authorization Code + PKCE

Questo file racconta cosa è cambiato, perché, come l'ho provato e — ultima sezione, la più
importante da leggere prima dell'esame — **dove sono rimasto insicuro**.

---

## 1. Sommario in una tabella

| | Prima (variante 2) | Adesso (architettura base) |
| --- | --- | --- |
| Chi verifica la password | La Centrale, confrontandola con `Utenti.password` in chiaro | Keycloak, sulla sua pagina di login |
| Cos'è il token | Un UUID generato dalla Centrale, tenuto in una `ConcurrentHashMap` | Un JWT firmato RS256 da Keycloak, verificato con le chiavi pubbliche del realm |
| Dove stanno i ruoli | Colonna `Utenti.tipo`, mappata a mano (`admin` → `amministratore`) | Ruoli di realm dentro il claim `realm_access.roles` |
| Il frontend | Form username/password che POSTa a `/api/auth/login` | Redirect a Keycloak, ritorno con `?code=`, scambio PKCE |
| Sessioni al riavvio | Perse (stavano in RAM) | Sopravvivono: la sessione vive in Keycloak, non nella Centrale |
| Password nel DB centrale | Sì, in chiaro | **Nessuna**: la colonna è sparita |
| Penalità del PDF | 0 (variante ammessa) | 0, e per di più si implementa l'architettura **base**, non la semplificazione |

Il guadagno vero non è "un punto in più": è che **la Centrale non conosce più nessuna
password**. Prima un `SELECT * FROM Utenti` dava tutte le credenziali del sistema.

---

## 2. Il flusso nuovo, passo per passo

```
  Browser (SPA React)                Keycloak :8180              Centrale :8781
        |                                  |                            |
   [click "Accedi con Keycloak"]           |                            |
        |                                  |                            |
   genera code_verifier (casuale)          |                            |
   code_challenge = SHA256(verifier)       |                            |
        |                                  |                            |
        |--- GET /auth?...&code_challenge->|                            |
        |                            [pagina di login,                  |
        |                             la password si                    |
        |                             digita QUI]                       |
        |<-- 302 /login?code=xyz&state=----|                            |
        |                                  |                            |
        |--- POST /token (code + code_verifier) ->                      |
        |<-- { access_token, refresh_token, id_token } -                |
        |                                  |                            |
        |--- GET /api/auth/me  Authorization: Bearer <JWT> ------------>|
        |                                  |<-- JWKS (chiavi pubbliche)-|
        |                                  |    (una volta, poi cache)  |
        |<-- { id, username, role, displayName, avatarInitials } -------|
        |                                  |                            |
   [dashboard; ogni chiamata REST porta il Bearer token]                |
```

**Perché PKCE.** Il `code` che torna nella URL passa dalla barra degli indirizzi, dalla
cronologia e dai log del browser. Con PKCE quel codice da solo non basta: per trasformarlo
in token bisogna anche esibire il `code_verifier`, che non è mai uscito dalla memoria della
SPA. È il motivo per cui una Single Page Application (che non può tenere segreti, il suo
sorgente è pubblico) può usare l'Authorization Code senza `client_secret`.

**Perché non il grant "password".** Sarebbe stato più comodo (si teneva la form attuale e
si mandavano le credenziali a Keycloak), ma in quel modo la password continuerebbe a
passare dalla web app: è il grant deprecato in OAuth 2.1 e assomiglia ancora alla
variante 2. Nel realm l'ho lasciato **disattivato apposta** sul client `railway-webapp`.

---

## 3. Cosa ho toccato, file per file

### 3.1 Nuovi file

| File | Cosa contiene |
| --- | --- |
| `Keycloak/realm-railway.json` | Il realm completo: 2 ruoli, 2 client, 4 utenti, i protocol mapper. Importato in automatico al primo avvio |
| `Keycloak/README.txt` | Come si avvia, cosa contiene il realm, come si rigenera |
| `ClientWebAppIntefacciaUtente/src/api/keycloak.ts` | Tutto il dialogo OIDC: PKCE, scambio del codice, refresh, logout |

### 3.2 `docker-compose.yml`

Aggiunto il servizio `keycloak` (immagine `quay.io/keycloak/keycloak:26.0`, comando
`start-dev --import-realm`), con il realm montato in sola lettura.

**Porta 8180 e non 8080**: la 8080 è la porta di default di Quarkus, quella che Stazioni o
Treni prenderebbero se si lanciassero dimenticando `-Dquarkus.http.port`. Con Keycloak lì
sopra si finirebbe con un "Address already in use" difficile da capire.

### 3.3 Backend — `ServeCentraleOperativa`

**`pom.xml`** — aggiunte `quarkus-oidc` e (in scope test) `quarkus-test-security`.

**`application.properties`** — nuova sezione OIDC:

```properties
quarkus.oidc.auth-server-url=http://localhost:8180/realms/railway
quarkus.oidc.client-id=railway-centrale
quarkus.oidc.application-type=service
quarkus.oidc.roles.role-claim-path=realm_access/roles
quarkus.oidc.token.audience=railway-centrale
quarkus.oidc.connection-delay=30S
quarkus.oidc.connection-retry-count=5
%test.quarkus.oidc.enabled=false
```

Riga per riga, perché ognuna serve a qualcosa:

* `application-type=service` — la Centrale è un *resource server*: accetta token e basta,
  non fa lei il redirect verso la pagina di login. Con `web-app` proverebbe a fare da sola
  il flusso Authorization Code, che qui lo fa la SPA.
* `roles.role-claim-path=realm_access/roles` — **senza questa riga non funziona niente**:
  Quarkus di default cerca i ruoli nel claim `groups`, mentre Keycloak li mette in
  `realm_access.roles`. Il risultato sarebbe un `SecurityIdentity` autenticato ma senza
  ruoli, quindi 403 su tutto.
* `token.audience` — il token deve essere stato emesso *per queste API*. È l'audience
  mapper del client `railway-webapp` che aggiunge `railway-centrale` al claim `aud`.
* `connection-delay` / `connection-retry-count` — all'avvio Quarkus scarica la
  configurazione del realm; se Keycloak non è ancora pronto, invece di morire riprova.

**`SessioniAttive.java` — cancellato.** Non serve più: la sessione non vive più nella RAM
della Centrale, vive in Keycloak.

**`AuthController.java` — riscritto.** Sono spariti `POST /login` e `POST /logout`. Resta
un solo endpoint, `GET /api/auth/me`, che restituisce **lo stesso payload di prima**
(`id`, `username`, `role`, `displayName`, `avatarInitials`) così il frontend non ha dovuto
cambiare struttura dati. I valori adesso vengono metà dal token e metà dalla tabella
`Utenti`, agganciata tramite la matricola.

**`FiltroAutorizzazione.java` — riscritto dentro, uguale fuori.** Le regole di chi-può-fare-cosa
sono rimaste identiche (letture a entrambi, i tre comandi operativi a entrambi, il resto
delle scritture solo all'amministratore, le due GET dei nodi di campo aperte). È cambiata
la fonte della verità: non più `sessioni.trova(token)` ma `identita.hasRole(...)`.

La divisione delle responsabilità adesso è questa, ed è la cosa da saper spiegare:

* **che il token sia valido** (firma, scadenza, emittente, audience) lo stabilisce
  `quarkus-oidc`, prima ancora che la richiesta arrivi al filtro;
* **chi può fare cosa** lo decide il filtro, leggendo i ruoli.

Aggiunto anche un controllo che prima non aveva senso: se un utente di Keycloak non ha
nessuno dei due ruoli del realm, si becca 403 (esiste come utente ma non è abilitato a
questa applicazione).

**`entity/Utente.java`** — tolto il campo `password`. La tabella resta come **anagrafica**
degli operatori, perché `Guasto` e `StoricoAssegnazioneGuasto` hanno una chiave esterna
verso `id_utente`: senza quella riga non si potrebbe più dire quale operatore sta seguendo
un guasto.

**`import.sql`, `populate_db.sql`, `schema.sql`** — tolta la colonna `password` da DDL e
seed. Era obbligatorio, non cosmetico: con `hibernate generation=update` su un database
nuovo la tabella viene creata dall'entità (senza `password`) e la vecchia INSERT sarebbe
fallita.

**`AdminApiTest.java`** — i test non fanno più il login vero (l'endpoint non c'è più). Usano
`@TestSecurity(user = "...", roles = {"..."})`, che inietta direttamente l'identità che il
token avrebbe portato; nel profilo di test l'estensione OIDC è spenta, così i test girano
senza dover tenere acceso un container. Aggiunti tre test nuovi: tecnico che prova a creare
una stazione (403), tecnico che legge (200), e `/api/auth/me`.

### 3.4 Frontend — `ClientWebAppIntefacciaUtente`

**`src/api/keycloak.ts` (nuovo)** — PKCE scritto a mano con le API native del browser
(`crypto.getRandomValues`, `crypto.subtle.digest('SHA-256')`, `btoa`), **nessuna dipendenza
npm aggiunta**. Espone `avviaLogin`, `completaLogin`, `rinnovaToken`, `logoutKeycloak`.
Include il controllo del parametro `state` (anti-CSRF sul redirect).

**`src/store/authStore.ts`** — `login()` non prende più username e password: manda il
browser su Keycloak. Aggiunti `completaAccesso(code, state)`, `ripristinaSessione()` e il
**timer di rinnovo automatico**: l'access token dura 30 minuti e senza rinnovo l'utente si
vedrebbe comparire dei 401 mentre lavora sulla dashboard.

**`src/pages/LoginPage.tsx`** — via la form e via l'auto-login hardcodato su MAT001 che
c'era prima. Adesso: un pulsante "Accedi con Keycloak" e la gestione del ritorno con
`?code=`. C'è una guardia con `useRef` perché lo StrictMode di React monta i componenti due
volte in sviluppo e il codice di autorizzazione è monouso: senza guardia il secondo
tentativo fallirebbe.

**`src/App.tsx`** — nuovo stato `sessionePronta`: finché non si è finito di controllare se
esiste ancora una sessione valida, `ProtectedRoute` non decide. Senza, un banale F5
sbatterebbe fuori un utente ancora autenticato.

**`src/api/apiClient.ts`** — via `login()` e `logout()`, dentro `getProfilo()`.

**`src/components/layout/Topbar.tsx`** — il logout non naviga più a mano su `/login`: passa
dall'endpoint di fine sessione di Keycloak, che chiude anche il Single Sign-On e rimanda
lui alla pagina di accesso. Senza quel passaggio, il login successivo rientrerebbe **senza
chiedere la password** (Keycloak considererebbe l'utente ancora collegato).

**`.env.example`** — aggiunte `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`,
`VITE_KEYCLOAK_CLIENT_ID`.

### 3.5 Bug preesistente trovato per strada: il CORS era spento

All'avvio la Centrale scriveva:

```
WARN: Unrecognized configuration key "quarkus.http.cors" was provided; it will be ignored
```

Su Quarkus 3.x la chiave giusta è `quarkus.http.cors.enabled`. Scritta come stava, veniva
**ignorata** e il filtro CORS restava spento. Provato prima della correzione:

```
$ curl -X OPTIONS http://localhost:8781/api/treni -H "Origin: http://localhost:5173" ...
HTTP/1.1 200 OK          <- e nessun header Access-Control-*
```

Non è un problema nato con Keycloak, c'era già; ma siccome la web app deve per forza
chiamare la Centrale da un'altra origine, senza questa correzione la migrazione non
funzionava. Corretto e riprovato:

```
HTTP/1.1 200 OK
access-control-allow-origin: http://localhost:5173
access-control-allow-methods: GET,POST,PUT,DELETE,OPTIONS
access-control-allow-headers: Content-Type,Accept,Authorization
```

---

## 4. Prove fatte (comandi veri, risultati veri)

Tutto provato con Postgres, Keycloak, Mosquitto e la Centrale accesi.

**4.1 Il realm si importa da solo**

```
$ docker-compose up -d
$ curl http://localhost:8180/realms/railway/.well-known/openid-configuration
{"issuer":"http://localhost:8180/realms/railway", ...}
```

**4.2 Il flusso Authorization Code + PKCE, simulato con curl**

Ho rifatto con `curl` esattamente i passaggi che fa la SPA (richiesta di autorizzazione con
`code_challenge`, invio credenziali, scambio del codice con il `code_verifier`). Claim
dentro l'access token di `mat001`:

```json
{
  "iss": "http://localhost:8180/realms/railway",
  "aud": "railway-centrale",
  "azp": "railway-webapp",
  "preferred_username": "mat001",
  "matricola": "MAT001",
  "given_name": "Mario", "family_name": "Rossi",
  "roles": ["amministratore"]
}
```

Confermato che i due protocol mapper funzionano (`matricola` e l'audience) e che i ruoli di
realm ci sono.

**4.3 PKCE è obbligatorio lato server**, non solo una gentilezza del client:

```
$ curl -D - ".../auth?client_id=railway-webapp&response_type=code&..."   # senza PKCE
Location: http://localhost:5173/login?error=invalid_request
          &error_description=Missing+parameter%3A+code_challenge_method
```

**4.4 Le API con token veri di Keycloak**

| Chiamata | Chi | Atteso | Ottenuto |
| --- | --- | --- | --- |
| `GET /api/treni` | nessun token | 401 | **401** |
| `GET /api/treni` | token fasullo | 401 | **401** |
| `GET /api/treni` | amministratore | 200 | **200** |
| `GET /api/stazioni` | tecnico | 200 | **200** |
| `POST /api/stazioni` | tecnico | 403 | **403** |
| `POST /api/stazioni` | amministratore | 201 | **201** |
| `DELETE /api/stazioni/{id}` | amministratore | 204 | **204** |
| `GET /api/treni/{id}/itinerario` | nessun token (nodo di campo) | passa il filtro | **passa** (404 applicativo, non 401) |

`GET /api/auth/me` con il token del tecnico:

```json
{"role":"tecnico","displayName":"Giovanni Bianchi","avatarInitials":"GB","id":"U3","username":"MAT003"}
```

Da notare `"id":"U3"`: è l'`id_utente` **della tabella Utenti**, ritrovato partendo dal
claim `matricola` del token. È la prova che l'aggancio Keycloak ↔ anagrafica funziona, ed è
quello che serve perché l'assegnazione dei guasti continui a valere.

**4.5 Compilazione e test**

```
$ cd ServeCentraleOperativa && ./mvnw test
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ cd ClientWebAppIntefacciaUtente && npx tsc --noEmit    # pulito
$ npm run build                                          # ✓ built in 1.43s
$ npx vitest run                                          # 2 passed
```

---

## 5. ⚠️ Le parti dove sono rimasto insicuro

Questa è la sezione da leggere due volte. Sono punti dove ho preso una decisione ma
**non ho la certezza che sia la migliore**, o dove ho verificato meno di quanto vorrei.

### 5.1 Non ho provato il login dentro un browser vero — è l'incertezza più grossa

Ho verificato **ogni singolo passaggio HTTP** del flusso con `curl` (§4.2), e le API con
token veri (§4.4). Ma i pezzi che vivono solo nel browser li ho scritti e non li ho visti
girare:

* `crypto.subtle.digest` per il `code_challenge` (esiste solo nei "secure context": su
  `localhost` c'è, ma se un giorno si serve la SPA da un IP di rete in HTTP **sparisce** e
  il login smette di funzionare senza un messaggio chiaro);
* la guardia `useRef` contro il doppio montaggio dello StrictMode;
* il salvataggio nel `sessionStorage` e il rientro dopo un F5;
* il redirect di logout.

**Cosa fare:** aprire `http://localhost:5173/login`, cliccare "Accedi con Keycloak", entrare
come `mat001`/`password`, poi provare un F5 (deve restare dentro) e il logout (deve tornare
al login e **richiedere di nuovo la password**). Se qualcosa non va, la console del browser
dice quasi sempre cosa.

### 5.2 Dove tengo i token: `sessionStorage`

Li ho messi nel `sessionStorage` (muoiono con la scheda). È il compromesso standard per una
SPA senza backend dedicato, ma **non è la soluzione giusta in assoluto**: qualunque script
iniettato nella pagina (XSS) potrebbe leggerli. La soluzione seria è il pattern
*backend-for-frontend*, con i token tenuti da un servizio server-side e un cookie `HttpOnly`
verso il browser — ma sarebbe un altro microservizio da scrivere e documentare.

Se il prof chiede "e se qualcuno ti ruba il token dal browser?", la risposta onesta è
questa: lo sa, è un limite dichiarato, la mitigazione scelta è la durata breve del token e
il fatto che non ci sia mai la password in ballo.

### 5.3 Due anagrafiche da tenere allineate a mano

Gli utenti adesso stanno in **due posti**: in Keycloak (credenziali e ruoli) e nella tabella
`Utenti` (nome, cognome, matricola, per le chiavi esterne dei guasti). Il collante è la
matricola, e nessuno lo controlla.

Se in sede d'esame si crea un utente nuovo dalla console di Keycloak **senza** aggiungere la
riga in `Utenti`, `/api/auth/me` risponde lo stesso (ho messo dei fallback sui claim del
token), ma restituisce come `id` la matricola invece di un `id_utente` vero, e
l'assegnazione di un guasto a quell'operatore fallirebbe sulla chiave esterna.

Non ho risolto perché ogni soluzione ha un costo: o si crea la riga al volo al primo accesso
(ma allora il DB si popola da solo di utenti), o si rifiuta l'accesso a chi non è in
anagrafica (ma allora Keycloak non basta più a dare accesso). **Non so quale delle due il
prof considererebbe più corretta**: l'ho lasciata come limite dichiarato.

### 5.4 Filtro sui percorsi invece delle annotazioni `@RolesAllowed`

Ho tenuto il `FiltroAutorizzazione` che ragiona sui percorsi (`percorso.endsWith("/sopprimi")`
e compagnia) invece di annotare ogni endpoint con `@RolesAllowed("amministratore")`, che è il
modo *idiomatico* in Quarkus.

Il motivo è che così le regole sono rimaste identiche a prima, in un posto solo, e la
documentazione già scritta continua a valere. Ma è una scelta discutibile: le annotazioni
sono più difficili da sbagliare (un endpoint nuovo senza annotazione è chiuso per
default, mentre qui un percorso nuovo che non finisce per `/sopprimi` finisce
automaticamente nella regola "solo amministratore" — che per fortuna è il default sicuro,
ma è fragile). **Se il prof chiede perché non ho usato le annotazioni standard, questa è la
risposta, e non ho una difesa fortissima.**

### 5.5 L'audience obbligatoria

Ho attivato `quarkus.oidc.token.audience=railway-centrale`, che è più sicuro (un token preso
per un altro servizio dello stesso realm qui non vale) e adesso funziona (§4.2). Ma dipende
dall'audience mapper del client `railway-webapp`: **se si modifica il client dalla console e
si perde quel mapper, tutte le API rispondono 401** e la causa è tutt'altro che ovvia.

Se durante la demo succede, la toppa veloce è commentare quella riga in
`application.properties`.

### 5.6 Porta 8180 invece della 8080

Tutti i tutorial di Keycloak usano la 8080. Io ho scelto la 8180 per non litigare con la
porta di default di Quarkus. È una scelta difendibile, ma **si discosta da quello che il
prof si aspetta di vedere** e va detta subito, altrimenti sembra un errore.

### 5.7 Keycloak in `start-dev`, senza volume

Il container gira in modalità sviluppo (HTTP in chiaro, database H2 interno) e **non ho
messo un volume**: se si fa `docker-compose rm keycloak`, tutto quello che si è modificato a
mano dalla console si perde e il realm torna com'è nel file JSON. È voluto (il realm resta
sempre riproducibile da git), ma se si prepara qualcosa a mano prima dell'esame va
riesportato nel file.

Il rovescio: `start-dev` non è una configurazione da produzione e Keycloak lo dice a video
all'avvio. Per il progetto va bene, ma è un'altra cosa da dichiarare prima che la chiedano.

### 5.8 Keycloak resta in HTTP anche sotto il profilo TLS

Con `-Dquarkus.profile=tls` la Centrale espone HTTPS sulla 8444 e MQTT cifrato sulla 8883,
ma **Keycloak resta in HTTP sulla 8180**. Il flusso funziona lo stesso (sono redirect di
prima parte, il browser non li blocca), ma la variante 6 "comunicazione cifrata" adesso ha
un buco che prima non aveva, perché prima il login viaggiava sullo stesso canale della
Centrale. **Questa combinazione non l'ho provata.**

Sistemarla vorrebbe dire generare un certificato per Keycloak con la CA del progetto,
passare a `start` invece di `start-dev` e far fidare il browser: fattibile, ma è mezza
giornata di lavoro e altri modi per rompere la demo.

### 5.9 I test non provano più la verifica del token

Con `@TestSecurity` e `%test.quarkus.oidc.enabled=false`, i test verificano **le regole di
ruolo**, non la validazione della firma del JWT. Quella l'ho provata solo a mano con curl
(§4.4, token fasullo → 401). Un test che accende un Keycloak vero (Dev Services /
Testcontainers) sarebbe più completo, ma legherebbe `./mvnw test` alla presenza di Docker e
al download di un'immagine: **ho preferito test che girano sempre**, ma è un compromesso.

### 5.10 I nodi di campo sono rimasti fuori (scelta concordata, ma ora stona di più)

`GET /api/prossima-stazione` e `GET /api/treni/{id}/itinerario` restano **aperte senza
token**, come prima: i processi Treno e Stazione non fanno login, la loro "autenticazione" è
la validazione dell'ID via MQTT all'avvio.

Era già un limite dichiarato, ma in un sistema che adesso è OAuth2 ovunque stona di più. La
strada pulita sarebbe un client `railway-edge` con grant `client_credentials` (un service
account per i nodi di campo). L'abbiamo lasciata fuori per non toccare anche `Treni/` e
`Stazioni/`, ma **è la prima domanda che mi aspetterei** dopo aver mostrato Keycloak.

### 5.11 La WebSocket `/ws/realtime` non è autenticata

Non l'ho toccata: chiunque può collegarcisi e ricevere la telemetria in broadcast. Era così
anche prima ed è già dichiarato in `correzioni_applicate.md`, ma vale lo stesso discorso del
punto precedente — adesso è l'unica porta senza controllo.

Autenticare una WebSocket è scomodo perché il browser non lascia mettere header sulla
handshake: si passa il token in query string o con il sottoprotocollo. **Non l'ho fatto e
non l'ho provato.**

### 5.12 Ho toccato il CORS, che non era nel perimetro

La correzione di §3.5 è fuori dal tema "autenticazione". L'ho fatta perché senza quella la
web app non parla con la Centrale e la migrazione non si poteva nemmeno provare — ma è un
file di configurazione modificato per un motivo diverso da quello per cui è partito il
lavoro, ed è giusto saperlo.

---

## 6. Cosa NON è cambiato

* Le regole di autorizzazione (chi può fare cosa) sono **identiche** a prima.
* Il payload che il frontend riceve per l'utente è **identico** (`id`, `username`, `role`,
  `displayName`, `avatarInitials`): nessuna pagina oltre a login e Topbar è stata toccata.
* MQTT, il broker, i digital twin dei treni, le stazioni, il FaultMonitor, la logica dei
  guasti: nessuna modifica.
* La tabella `Utenti` esiste ancora, con le stesse righe (meno la password).

---

## 7. Documenti del repo da aggiornare

La migrazione rende **false** alcune affermazioni scritte in giro. Da sistemare prima della
consegna:

| Documento | Cosa dice adesso | Cosa dovrebbe dire |
| --- | --- | --- |
| `gap_analysis.md` (riga ~142) | "Variante 2 adottata — **Keycloak non serve**" | Variante 2 **non più adottata**: implementata l'architettura base con OAuth2/Keycloak |
| `correzioni_applicate.md` (§ limiti, punti 1 e 2) | "Password in chiaro", "sessioni in RAM" | Non più veri: entrambi risolti dalla migrazione |
| `critica_scelte_progettuali.md` | Riferimenti al login tradizionale | Da rileggere e aggiornare nei punti che parlano di autenticazione |
| `SpecificaMaggiormenteApprofondita/01_elenco_requisiti_funzionali.tex` (RF01.6, RF04.1) | "variante 2 al posto di OAuth2" | RF04.1 ora è soddisfatto dall'architettura base |
| `Stati_diagram/ServerCentrale/09_macchina_sessione_autorizzazione.puml` (**M9**) | Modella `AuthController` + `SessioniAttive`, cita la variante 2 | Va **rifatto**: gli stati non sono più "sessione aperta/chiusa" ma il ciclo del token (assente → valido → scaduto → rinnovato) |
| `Stati_diagram/ServerCentrale/00_mappa_delle_macchine.puml` | Descrizione di M9 | Da allineare a M9 rifatto |
| `documentazioneFinale/*.org/.tex` | Sezione autenticazione | Da riscrivere sulla base di questo file |

Non li ho modificati io: sono la tua analisi e il tuo racconto del progetto, e riscriverli
al posto tuo avrebbe senso solo dopo che hai deciso come vuoi presentare la scelta.

---

## 8. Domande probabili all'esame e cosa rispondere

**"Perché OAuth2 e non il login normale, visto che la variante 2 era ammessa?"**
Perché la variante è una semplificazione: implementando Keycloak si fa l'architettura base
del PDF. E soprattutto perché così la Centrale non custodisce più nessuna password.

**"Cos'è PKCE e perché serve a te?"**
Vedi §2. In una parola: permette a un client pubblico (la SPA, il cui sorgente è visibile a
tutti e che quindi non può avere un `client_secret`) di usare l'Authorization Code in
sicurezza, legando il codice di autorizzazione a un segreto usa-e-getta.

**"La Centrale come fa a sapere che il token è buono?"**
Scarica una volta le chiavi pubbliche del realm (endpoint JWKS, trovato da solo con la
discovery su `.well-known/openid-configuration`) e verifica la firma RS256 in locale.
**Non chiama Keycloak a ogni richiesta**: è per questo che regge il carico.

**"E se Keycloak si spegne?"**
Chi ha già un token continua a lavorare finché non scade (30 minuti), perché la verifica è
locale. Chi deve entrare non può. Sono le due facce della stessa medaglia.

**"Dove sono i ruoli?"**
Nel token, claim `realm_access.roles`, messi da Keycloak. La colonna `Utenti.tipo` non
decide più niente.

**"Perché il logout non è solo cancellare il token?"**
Perché Keycloak terrebbe viva la sessione SSO e il login successivo non chiederebbe la
password. Bisogna passare dall'`end_session_endpoint` — vedi `logoutKeycloak()`.
