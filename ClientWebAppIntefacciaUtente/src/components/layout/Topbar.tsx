import { LogOut, Search } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useLocation, useNavigate } from 'react-router-dom';
import './Topbar.css';

export default function Topbar() {
  const { logout } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
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
        <div className="search-bar">
          <Search size={18} className="search-icon" />
          <input type="text" placeholder="Cerca treno, stazione..." className="search-input" />
        </div>
        
        <button onClick={handleLogout} className="btn btn-outline logout-btn">
          <LogOut size={18} />
          <span>Logout</span>
        </button>
      </div>
    </header>
  );
}
