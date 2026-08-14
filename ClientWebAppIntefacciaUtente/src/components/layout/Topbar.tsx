import { LogOut } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useLocation } from 'react-router-dom';
import './Topbar.css';

export default function Topbar() {
  const { logout } = useAuthStore();
  const location = useLocation();

  // Non serve piu' navigare a mano su /login: il logout porta il browser
  // sull'endpoint di fine sessione di Keycloak, che chiude anche il Single Sign-On
  // e poi rimanda lui stesso alla pagina di accesso (post_logout_redirect_uri).
  const handleLogout = () => {
    logout();
  };

  const getPageTitle = () => {
    switch (location.pathname) {
      case '/': return 'Dashboard Traffico';
      case '/map': return 'Mappa Live';
      case '/routes': return 'Gestione Tratte';
      case '/stations': return 'Stato Stazioni';
      case '/alerts': return 'Centro Allarmi';
      case '/admin': return 'Pannello Amministratore';
      default: return 'RailControl';
    }
  };

  return (
    <header className="topbar glass-panel">
      <div className="topbar-title">
        <h1>{getPageTitle()}</h1>
      </div>

      <div className="topbar-actions">

        <button onClick={handleLogout} className="btn btn-outline logout-btn">
          <LogOut size={18} />
          <span>Logout</span>
        </button>
      </div>
    </header>
  );
}
