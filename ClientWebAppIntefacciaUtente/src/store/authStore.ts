import { create } from 'zustand';
import type { User } from '../types';
import { apiClient, setAuthToken } from '../api/apiClient';

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  loginError: string | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  loginError: null,

  login: async (username: string, password: string): Promise<boolean> => {
    try {
      const result = await apiClient.login(username, password);
      // Senza questa riga tutte le chiamate successive tornerebbero 401: è il token
      // che la Centrale verifica per capire chi siamo e cosa possiamo fare.
      setAuthToken(result.token);
      set({ user: result.user, isAuthenticated: true, loginError: null });
      return true;
    } catch (err: any) {
      if (err.message === 'Invalid credentials') {
        set({ loginError: 'Credenziali non valide. Riprova.' });
      } else {
        set({ loginError: 'Impossibile connettersi al server. Verifica che il backend sia in esecuzione.' });
      }
      return false;
    }
  },

  logout: () => {
    // Chiude la sessione anche lato Centrale (il token viene invalidato lì).
    // È "fire and forget": se la Centrale non risponde si esce comunque.
    void apiClient.logout();
    setAuthToken(null);
    set({ user: null, isAuthenticated: false, loginError: null });
  },
}));
