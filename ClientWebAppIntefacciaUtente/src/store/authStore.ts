import { create } from 'zustand';
import type { User } from '../types';
import { apiClient, setAuthToken } from '../api/apiClient';
import {
  avviaLogin,
  completaLogin,
  logoutKeycloak,
  pulisciSessione,
  rinnovaToken,
  tokenSalvato,
  type TokenKeycloak,
} from '../api/keycloak';

/**
 * Stato dell'autenticazione della web app.
 *
 * Con Keycloak il giro e' cambiato: qui dentro non passano piu' username e
 * password (le raccoglie la pagina di login dell'Identity Provider), ma solo i
 * token e il profilo che la Centrale restituisce da /api/auth/me.
 */
interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  /**
   * Diventa true quando si e' finito di controllare se esisteva gia' una sessione
   * valida. Serve a non sbattere fuori l'utente per un istante a ogni F5, prima
   * che il rinnovo del token sia andato a buon fine.
   */
  sessionePronta: boolean;
  loginError: string | null;
  /** Manda il browser sulla pagina di login di Keycloak. */
  login: () => Promise<void>;
  /** Chiude il flusso: scambia il codice di autorizzazione e carica il profilo. */
  completaAccesso: (code: string, state: string | null) => Promise<boolean>;
  /** Al boot dell'app: recupera la sessione se il refresh token e' ancora buono. */
  ripristinaSessione: () => Promise<void>;
  logout: () => void;
}

/**
 * Timer del rinnovo automatico. L'access token dura mezz'ora: senza rinnovo
 * l'utente si vedrebbe comparire dei 401 mentre sta lavorando sulla dashboard.
 */
let timerRinnovo: ReturnType<typeof setTimeout> | null = null;

const fermaRinnovo = (): void => {
  if (timerRinnovo) {
    clearTimeout(timerRinnovo);
    timerRinnovo = null;
  }
};

export const useAuthStore = create<AuthState>((set, get) => {
  /**
   * Registra il token per le chiamate REST e programma il rinnovo poco prima
   * della scadenza. Si riprogramma da solo a ogni giro finche' la sessione vive.
   */
  const attivaToken = (token: TokenKeycloak): void => {
    setAuthToken(token.accessToken);
    fermaRinnovo();

    // Almeno 5 secondi: se il token fosse gia' scaduto un timeout negativo
    // farebbe partire il rinnovo all'infinito.
    const attesa = Math.max(token.scadenza - Date.now(), 5000);
    timerRinnovo = setTimeout(async () => {
      const nuovo = await rinnovaToken();
      if (nuovo) {
        attivaToken(nuovo);
      } else {
        // Refresh token scaduto o sessione chiusa da Keycloak: si torna al login.
        setAuthToken(null);
        set({ user: null, isAuthenticated: false });
      }
    }, attesa);
  };

  return {
    user: null,
    isAuthenticated: false,
    sessionePronta: false,
    loginError: null,

    login: async (): Promise<void> => {
      try {
        await avviaLogin();
      } catch {
        set({ loginError: 'Impossibile contattare Keycloak. Verifica che il container sia acceso.' });
      }
    },

    completaAccesso: async (code: string, state: string | null): Promise<boolean> => {
      try {
        const token = await completaLogin(code, state);
        attivaToken(token);
        // Il profilo lo dice la Centrale, non il token: cosi' l'id utente e' quello
        // della tabella Utenti, che e' quello a cui puntano i guasti.
        const profilo = await apiClient.getProfilo();
        set({ user: profilo, isAuthenticated: true, sessionePronta: true, loginError: null });
        return true;
      } catch (err: unknown) {
        pulisciSessione();
        setAuthToken(null);
        const messaggio = err instanceof Error ? err.message : '';
        set({
          user: null,
          isAuthenticated: false,
          sessionePronta: true,
          loginError: messaggio || 'Accesso non riuscito. Riprova.',
        });
        return false;
      }
    },

    ripristinaSessione: async (): Promise<void> => {
      // Gia' fatto (per esempio si e' appena tornati dal login): non si rifa'.
      if (get().sessionePronta) return;

      const salvato = tokenSalvato();
      if (!salvato) {
        set({ sessionePronta: true });
        return;
      }

      // Se l'access token e' ancora valido lo si riusa, altrimenti si prova il
      // refresh token: e' quello che permette di ricaricare la pagina senza
      // ripassare dalla schermata di Keycloak.
      const token = salvato.scadenza > Date.now() ? salvato : await rinnovaToken();
      if (!token) {
        set({ user: null, isAuthenticated: false, sessionePronta: true });
        return;
      }

      attivaToken(token);
      try {
        const profilo = await apiClient.getProfilo();
        set({ user: profilo, isAuthenticated: true, sessionePronta: true });
      } catch {
        // Token rifiutato dalla Centrale (per esempio realm ricreato): si riparte
        // da capo con un login pulito.
        fermaRinnovo();
        pulisciSessione();
        setAuthToken(null);
        set({ user: null, isAuthenticated: false, sessionePronta: true });
      }
    },

    logout: (): void => {
      fermaRinnovo();
      setAuthToken(null);
      set({ user: null, isAuthenticated: false, loginError: null });
      // Ultima cosa: porta via il browser verso l'endpoint di logout di Keycloak,
      // che chiude anche la sessione SSO e riporta poi su /login.
      logoutKeycloak();
    },
  };
});
