import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useEffect } from 'react';
import { useAuthStore } from './store/authStore';
import { useRailwayStore } from './store/railwayStore';
import { useRealtimeUpdates } from './hooks/useRealtimeUpdates';

// Layout
import MainLayout from './components/layout/MainLayout';

// Pages
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import TrafficMapPage from './pages/TrafficMapPage';
import RoutesPage from './pages/RoutesPage';
import TrackSegmentsPage from './pages/TrackSegmentsPage';
import StationsPage from './pages/StationsPage';
import TrainsPage from './pages/TrainsPage';
import AlertsPage from './pages/AlertsPage';
import AdminPage from './pages/AdminPage';

// Protected Route Wrapper
const ProtectedRoute = ({ children, requireAdmin = false }: { children: React.ReactNode; requireAdmin?: boolean }) => {
  const { isAuthenticated, user, sessionePronta } = useAuthStore();

  // Con Keycloak il token vive nel sessionStorage e a ogni ricaricamento va
  // rinnovato: finché quel controllo non è finito non si decide niente, altrimenti
  // un banale F5 sbatterebbe fuori un utente che è ancora autenticato.
  if (!sessionePronta) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh' }}>
        <span className="text-muted">Verifica della sessione in corso...</span>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requireAdmin && user?.role !== 'amministratore') {
    return <Navigate to="/" replace />;
  }

  return <>{children}</>;
};

// Main App Logic (Simulator initializer)
const RailwayApp = () => {
  // Selettore e non lo store intero: questo componente contiene TUTTE le rotte, quindi
  // sottoscriverlo a ogni cambiamento dello store voleva dire ridisegnare l'intera
  // applicazione a ogni frame di telemetria (vedi anche useRealtimeUpdates).
  const initialize = useRailwayStore((s) => s.initialize);

  // Initialize mock data once
  useEffect(() => {
    initialize();
  }, [initialize]);

  // Start realtime updates engine
  useRealtimeUpdates();

  return (
    <Routes>
      <Route path="/" element={<MainLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="map" element={<TrafficMapPage />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="tratte" element={<TrackSegmentsPage />} />
        <Route path="stations" element={<StationsPage />} />
        <Route path="trains" element={<TrainsPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="admin" element={
          <ProtectedRoute requireAdmin>
            <AdminPage />
          </ProtectedRoute>
        } />
      </Route>
    </Routes>
  );
};

function App() {
  const ripristinaSessione = useAuthStore((stato) => stato.ripristinaSessione);

  // Al primo avvio si controlla se nel sessionStorage c'è ancora un refresh token
  // buono: in quel caso si rientra senza ripassare dalla pagina di Keycloak.
  useEffect(() => {
    void ripristinaSessione();
  }, [ripristinaSessione]);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/*" element={
          <ProtectedRoute>
            <RailwayApp />
          </ProtectedRoute>
        } />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
