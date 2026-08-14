import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Train, LogIn, ShieldAlert } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import './LoginPage.css';

/**
 * Pagina di accesso.
 *
 * Non c'e' piu' nessun campo password: da quando l'autenticazione e' passata a
 * Keycloak questa pagina fa due cose sole.
 *   - Se ci si arriva normalmente, mostra il pulsante che manda il browser sulla
 *     pagina di login dell'Identity Provider (flusso Authorization Code + PKCE).
 *   - Se ci si arriva di ritorno da Keycloak, cioe' con "?code=..." nella query
 *     string, scambia quel codice con i token e entra nell'applicazione.
 * Il redirect_uri registrato nel client railway-webapp punta proprio qui.
 */
export default function LoginPage() {
  const [isLoading, setIsLoading] = useState(false);
  const { login, completaAccesso, loginError, isAuthenticated } = useAuthStore();
  const [parametri, setParametri] = useSearchParams();
  const navigate = useNavigate();

  // In sviluppo React monta i componenti due volte (StrictMode): senza questa
  // guardia si proverebbe a spendere lo stesso codice di autorizzazione due volte
  // e la seconda Keycloak risponderebbe con un errore.
  const scambioAvviato = useRef(false);

  const code = parametri.get('code');
  const state = parametri.get('state');
  // Keycloak segnala qui gli errori del flusso (per esempio redirect_uri sbagliata).
  const errore = parametri.get('error_description') ?? parametri.get('error');

  useEffect(() => {
    if (!code || scambioAvviato.current) return;
    scambioAvviato.current = true;

    (async () => {
      setIsLoading(true);
      const ok = await completaAccesso(code, state);
      // Il codice e' monouso: si ripulisce la barra degli indirizzi comunque,
      // sia per non lasciarlo nella cronologia sia per non riprovarci a un F5.
      setParametri({}, { replace: true });
      setIsLoading(false);
      if (ok) navigate('/', { replace: true });
    })();
  }, [code, state, completaAccesso, navigate, setParametri]);

  // Sessione gia' aperta (per esempio si e' tornati su /login a mano): si rientra.
  useEffect(() => {
    if (isAuthenticated && !code) navigate('/', { replace: true });
  }, [isAuthenticated, code, navigate]);

  const handleLogin = async () => {
    setIsLoading(true);
    // Da qui in poi la pagina viene sostituita da quella di Keycloak.
    await login();
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

        {(loginError || errore) && (
          <div className="login-error">
            <ShieldAlert size={20} />
            <span>{loginError ?? errore}</span>
          </div>
        )}

        <button
          type="button"
          onClick={handleLogin}
          className="btn btn-primary w-full mt-4 login-submit-btn"
          disabled={isLoading}
        >
          {isLoading ? 'Autenticazione in corso...' : (
            <>
              <LogIn size={20} />
              <span>Accedi con Keycloak</span>
            </>
          )}
        </button>

        <div className="login-demo-actions">
          <p className="text-sm text-muted mb-2 text-center">
            Le credenziali si inseriscono sulla pagina di Keycloak, non qui.
          </p>
          <p className="text-sm text-muted text-center">
            Utenti di prova: <strong>mat001</strong> (amministratore),
            {' '}<strong>mat003</strong> (tecnico) &mdash; password <strong>password</strong>
          </p>
        </div>
      </div>
    </div>
  );
}
