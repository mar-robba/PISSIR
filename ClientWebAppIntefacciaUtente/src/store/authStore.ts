import { create } from 'zustand';
import type { User } from '../types';
import { apiClient } from '../api/apiClient';

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
    set({ user: null, isAuthenticated: false, loginError: null });
  },
}));
