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
  const { isAuthenticated, user } = useAuthStore();

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
  const { initialize } = useRailwayStore();
  
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
