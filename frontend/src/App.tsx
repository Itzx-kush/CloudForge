import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AuthProvider } from './context/AuthContext';
import { useBackendHealth } from './hooks/useBackendHealth';
import { DashboardPage } from './pages/DashboardPage';
import { DeploymentsPage } from './pages/DeploymentsPage';
import { LoginPage } from './pages/LoginPage';
import { MonitoringPage } from './pages/MonitoringPage';
import { PipelinesPage } from './pages/PipelinesPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { SettingsPage } from './pages/SettingsPage';

export function AppRoutes() {
  const healthState = useBackendHealth();

  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage healthState={healthState} />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout healthState={healthState} />}>
            <Route index element={<DashboardPage healthState={healthState} />} />
            <Route path="projects" element={<ProjectsPage />} />
            <Route path="projects/:projectId" element={<ProjectDetailPage />} />
            <Route path="repositories" element={<RepositoriesPage />} />
            <Route path="deployments" element={<DeploymentsPage />} />
            <Route path="pipelines" element={<PipelinesPage />} />
            <Route
              path="monitoring"
              element={<MonitoringPage healthState={healthState} />}
            />
            <Route
              path="settings"
              element={<SettingsPage healthState={healthState} />}
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  );
}
