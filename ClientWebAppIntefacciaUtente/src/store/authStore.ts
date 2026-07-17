import { create } from 'zustand';
import type { User } from '../types';
import { MOCK_USERS } from '../api/mockData';

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

  login: async (username: string, _password: string): Promise<boolean> => {
    // Simulazione chiamata REST API: POST /api/auth/login
    await new Promise((r) => setTimeout(r, 800));

    const found = MOCK_USERS.find((u) => u.username === username);
    if (found) {
      set({ user: found, isAuthenticated: true, loginError: null });
      return true;
    } else {
      set({ loginError: 'Credenziali non valide. Riprova.' });
      return false;
    }
  },

  logout: () => {
    set({ user: null, isAuthenticated: false, loginError: null });
  },
}));
