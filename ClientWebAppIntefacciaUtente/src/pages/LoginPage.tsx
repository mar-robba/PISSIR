import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Train, LogIn, ShieldAlert } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import './LoginPage.css';

export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const { login, loginError } = useAuthStore();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    const success = await login(username, password);
    setIsLoading(false);
    
    if (success) {
      navigate('/');
    }
  };

  const autofill = (role: 'tecnico' | 'admin') => {
    setUsername(role === 'tecnico' ? 'tecnico1' : 'admin1');
    setPassword('password');
  };

  return (
    <div className="login-container">
      <div className="login-background"></div>
      
      <div className="login-card glass-panel animate-fade-in">
        <div className="login-header">
          <div className="logo-container">
            <Train size={48} color="var(--primary)" />
          </div>
          <h1>RailControl</h1>
          <p>Sistema di Gestione Traffico Ferroviario</p>
        </div>

        {loginError && (
          <div className="login-error">
            <ShieldAlert size={20} />
            <span>{loginError}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="login-form">
          <div className="input-group">
            <label className="input-label" htmlFor="username">Username</label>
            <input
              id="username"
              type="text"
              className="input-field"
              placeholder="Inserisci username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </div>
          
          <div className="input-group">
            <label className="input-label" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              className="input-field"
              placeholder="Inserisci password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>

          <button 
            type="submit" 
            className="btn btn-primary w-full mt-4 login-submit-btn"
            disabled={isLoading}
          >
            {isLoading ? 'Autenticazione...' : (
              <>
                <LogIn size={20} />
                <span>Accedi al Sistema</span>
              </>
            )}
          </button>
        </form>

        <div className="login-demo-actions">
          <p className="text-sm text-muted mb-2 text-center">Credenziali Demo Rapide</p>
          <div className="flex gap-2">
            <button type="button" onClick={() => autofill('tecnico')} className="btn btn-outline flex-1 text-xs">
              Usa Tecnico
            </button>
            <button type="button" onClick={() => autofill('admin')} className="btn btn-outline flex-1 text-xs">
              Usa Admin
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
