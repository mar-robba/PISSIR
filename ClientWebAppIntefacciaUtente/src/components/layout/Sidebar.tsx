import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Map,
  Route as RouteIcon,
  Building2,
  Bell,
  Settings,
  Train
} from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useRailwayStore } from '../../store/railwayStore';
import './Sidebar.css'; // We'll create specific styles if needed, or use index.css

export default function Sidebar() {
  const { user } = useAuthStore();
  // Selettore: alla sidebar servono solo gli allarmi, non tutto lo stato della rete.
  const alerts = useRailwayStore((s) => s.alerts);

  const activeAlertsCount = alerts.filter(a => !a.acknowledged).length;

  return (
    <aside className="sidebar glass-panel">
      <div className="sidebar-header">
        <Train size={32} color="var(--primary)" />
        <h2>RailControl</h2>
      </div>

      <nav className="sidebar-nav">
        <NavLink to="/" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'} end>
          <LayoutDashboard size={20} />
          <span>Dashboard</span>
        </NavLink>
        
        <NavLink to="/map" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <Map size={20} />
          <span>Mappa Live</span>
        </NavLink>
        
        <NavLink to="/routes" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <RouteIcon size={20} />
          <span>Itinerari</span>
        </NavLink>
        <NavLink to="/tratte" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <RouteIcon size={20} />
          <span>Tratte fisiche</span>
        </NavLink>
        
        <NavLink to="/stations" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <Building2 size={20} />
          <span>Stazioni</span>
        </NavLink>

        <NavLink to="/trains" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <Train size={20} />
          <span>Treni</span>
        </NavLink>

        <NavLink to="/alerts" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
          <div className="alert-icon-wrapper">
            <Bell size={20} />
            {activeAlertsCount > 0 && (
              <span className="alert-badge">{activeAlertsCount}</span>
            )}
          </div>
          <span>Allarmi</span>
        </NavLink>

        {user?.role === 'amministratore' && (
          <>
            <div className="nav-divider"></div>
            <NavLink to="/admin" className={({ isActive }) => isActive ? 'nav-item active' : 'nav-item'}>
              <Settings size={20} />
              <span>Amministrazione</span>
            </NavLink>
          </>
        )}
      </nav>

      <div className="sidebar-footer">
        <div className="user-info">
          <div className="avatar">{user?.avatarInitials}</div>
          <div className="user-details">
            <div className="user-name">{user?.displayName}</div>
            <div className="user-role">{user?.role}</div>
          </div>
        </div>
      </div>
    </aside>
  );
}
