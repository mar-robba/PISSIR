// ============================================================
// Client OAuth2 / OpenID Connect verso Keycloak
//
// Qui dentro c'e' tutto il dialogo fra la web app e l'Identity Provider.
// Il flusso e' l'Authorization Code con PKCE, cioe' quello che lo standard
// raccomanda per le Single Page Application:
//
//   1. la SPA genera un segreto usa e getta (code_verifier) e ne manda a
//      Keycloak solo l'impronta SHA-256 (code_challenge);
//   2. il browser viene mandato sulla pagina di login di Keycloak. La password
//      viene digitata LI', non nella nostra pagina: la web app non la vede mai;
//   3. Keycloak rimanda il browser su /login?code=... con un codice monouso;
//   4. la SPA scambia quel codice per i token, mostrando il code_verifier
//      originale. Chi avesse rubato il codice dagli URL non potrebbe usarlo,
//      perche' il verifier non e' mai passato in chiaro sulla rete.
//
// Prima di Keycloak la LoginPage mandava username e password alla Centrale, che
// le confrontava con una colonna della tabella Utenti: ora la Centrale non
// conosce nessuna password e si limita a verificare la firma del token.
// ============================================================

/** Indirizzo di Keycloak (container railway-keycloak del docker-compose). */
const KEYCLOAK_URL: string = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180';
/** Realm che contiene utenti, ruoli e client del progetto. */
const REALM: string = import.meta.env.VITE_KEYCLOAK_REALM ?? 'railway';
/** Client pubblico della SPA, dichiarato in Keycloak/realm-railway.json. */
const CLIENT_ID: string = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'railway-webapp';

/** Endpoint standard OIDC del realm. */
const BASE_OIDC = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect`;

/**
 * Dove Keycloak rimanda il browser dopo il login. Deve combaciare con uno dei
 * redirectUris registrati nel client, altrimenti Keycloak rifiuta la richiesta.
 * Si ricava dall'origin corrente cosi' funziona sia su localhost sia su 127.0.0.1.
 */
const redirectUri = (): string => `${window.location.origin}/login`;

// Chiavi usate nel sessionStorage. Si usa il sessionStorage e non il localStorage
// perche' i token muoiono con la scheda del browser: e' un compromesso, la
// soluzione davvero robusta sarebbe tenerli in un cookie HttpOnly gestito da un
// backend-for-frontend, ma richiederebbe un altro servizio.
const CHIAVE_VERIFIER = 'kc_code_verifier';
const CHIAVE_STATE = 'kc_state';
const CHIAVE_TOKEN = 'kc_token';

/** I token restituiti da Keycloak, piu' l'istante in cui l'access token scade. */
export interface TokenKeycloak {
  accessToken: string;
  refreshToken: string;
  idToken: string;
  /** Epoch in millisecondi: serve a programmare il rinnovo prima della scadenza. */
  scadenza: number;
}

/** Risposta grezza dell'endpoint /token di Keycloak. */
interface RispostaToken {
  access_token: string;
  refresh_token: string;
  id_token: string;
  expires_in: number;
}

/**
 * Converte byte grezzi in base64url (base64 senza '+', '/' e '='): e' la codifica
 * che lo standard PKCE impone, perche' il valore finisce dentro una URL.
 */
const base64url = (byte: Uint8Array): string => {
  let binario = '';
  byte.forEach((b) => { binario += String.fromCharCode(b); });
  return btoa(binario).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

/** Stringa casuale usata come code_verifier e come state anti-CSRF. */
const casuale = (byte = 48): string => base64url(crypto.getRandomValues(new Uint8Array(byte)));

/**
 * Calcola il code_challenge: SHA-256 del verifier, in base64url.
 * crypto.subtle esiste solo nei "secure context", cioe' HTTPS oppure localhost:
 * in sviluppo con Vite su localhost va benissimo.
 */
const sfida = async (verifier: string): Promise<string> => {
  const impronta = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64url(new Uint8Array(impronta));
};

/** Salva i token nel sessionStorage cosi' un refresh della pagina non butta fuori. */
const salva = (token: TokenKeycloak): void => {
  sessionStorage.setItem(CHIAVE_TOKEN, JSON.stringify(token));
};

/** Rilegge i token salvati, se ci sono. */
export const tokenSalvato = (): TokenKeycloak | null => {
  const grezzo = sessionStorage.getItem(CHIAVE_TOKEN);
  if (!grezzo) return null;
  try {
    return JSON.parse(grezzo) as TokenKeycloak;
  } catch {
    return null;
  }
};

/** Cancella ogni traccia della sessione dal browser. */
export const pulisciSessione = (): void => {
  sessionStorage.removeItem(CHIAVE_TOKEN);
  sessionStorage.removeItem(CHIAVE_VERIFIER);
  sessionStorage.removeItem(CHIAVE_STATE);
};

/** Trasforma la risposta di Keycloak nella struttura usata dall'app. */
const daRisposta = (dati: RispostaToken): TokenKeycloak => ({
  accessToken: dati.access_token,
  refreshToken: dati.refresh_token,
  idToken: dati.id_token,
  // 5 secondi di margine: meglio rinnovare un attimo prima che ritrovarsi
  // con un token gia' scaduto in mano.
  scadenza: Date.now() + (dati.expires_in - 5) * 1000,
});

/**
 * Passo 1 e 2: prepara il PKCE e porta il browser sulla pagina di login di Keycloak.
 * La funzione non ritorna mai davvero, perche' finisce con un cambio di pagina.
 */
export const avviaLogin = async (): Promise<void> => {
  const verifier = casuale();
  const state = casuale(16);

  // Il verifier va conservato: servira' fra due passaggi, quando torneremo qui
  // con il codice di autorizzazione. Lo state serve invece a riconoscere che la
  // risposta e' figlia della richiesta partita da noi (protezione CSRF).
  sessionStorage.setItem(CHIAVE_VERIFIER, verifier);
  sessionStorage.setItem(CHIAVE_STATE, state);

  const parametri = new URLSearchParams({
    client_id: CLIENT_ID,
    response_type: 'code',
    scope: 'openid profile email',
    redirect_uri: redirectUri(),
    state,
    code_challenge: await sfida(verifier),
    code_challenge_method: 'S256',
  });

  window.location.assign(`${BASE_OIDC}/auth?${parametri.toString()}`);
};

/**
 * Passo 4: scambia il codice di autorizzazione con i token veri.
 *
 * @param code  Codice monouso letto dalla query string del redirect.
 * @param state Valore state tornato da Keycloak, confrontato con quello salvato.
 * @returns I token ottenuti.
 */
export const completaLogin = async (code: string, state: string | null): Promise<TokenKeycloak> => {
  const verifier = sessionStorage.getItem(CHIAVE_VERIFIER);
  const statePrevisto = sessionStorage.getItem(CHIAVE_STATE);

  if (!verifier) {
    throw new Error('Sessione di login non trovata: riprovare ad accedere.');
  }
  if (!state || state !== statePrevisto) {
    // Non e' la risposta alla NOSTRA richiesta: potrebbe essere un tentativo di
    // agganciare la sessione dell'utente a un login deciso da qualcun altro.
    throw new Error('Parametro state non corrispondente: login annullato.');
  }

  const corpo = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: CLIENT_ID,
    code,
    redirect_uri: redirectUri(),
    code_verifier: verifier,
  });

  const risposta = await fetch(`${BASE_OIDC}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: corpo.toString(),
  });
  if (!risposta.ok) {
    throw new Error('Keycloak ha rifiutato lo scambio del codice di autorizzazione.');
  }

  // Il verifier e' monouso: consumato lo si butta.
  sessionStorage.removeItem(CHIAVE_VERIFIER);
  sessionStorage.removeItem(CHIAVE_STATE);

  const token = daRisposta(await risposta.json() as RispostaToken);
  salva(token);
  return token;
};

/**
 * Rinnova l'access token usando il refresh token, senza far rivedere all'utente
 * la pagina di login. Serve sia allo scadere del token, sia al ricaricamento
 * della pagina per capire se la sessione e' ancora buona.
 *
 * @returns I nuovi token, oppure null se il refresh non e' piu' valido.
 */
export const rinnovaToken = async (): Promise<TokenKeycloak | null> => {
  const attuale = tokenSalvato();
  if (!attuale?.refreshToken) return null;

  const corpo = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: CLIENT_ID,
    refresh_token: attuale.refreshToken,
  });

  try {
    const risposta = await fetch(`${BASE_OIDC}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: corpo.toString(),
    });
    if (!risposta.ok) {
      // Sessione chiusa lato Keycloak (logout da un'altra scheda, timeout SSO...).
      pulisciSessione();
      return null;
    }
    const token = daRisposta(await risposta.json() as RispostaToken);
    salva(token);
    return token;
  } catch {
    // Keycloak irraggiungibile: si tiene quello che si ha e si riprovera' dopo.
    return null;
  }
};

/**
 * Chiude la sessione. Non basta buttare via i token locali: finche' non si passa
 * dall'endpoint di logout, Keycloak considera l'utente ancora collegato e al
 * login successivo lo rifarebbe entrare senza chiedere la password (Single Sign-On).
 */
export const logoutKeycloak = (): void => {
  const token = tokenSalvato();
  pulisciSessione();

  const parametri = new URLSearchParams({
    client_id: CLIENT_ID,
    post_logout_redirect_uri: `${window.location.origin}/login`,
  });
  // id_token_hint dice a Keycloak QUALE sessione chiudere, cosi' non serve
  // chiedere conferma all'utente con una schermata in piu'.
  if (token?.idToken) {
    parametri.set('id_token_hint', token.idToken);
  }

  window.location.assign(`${BASE_OIDC}/logout?${parametri.toString()}`);
};
