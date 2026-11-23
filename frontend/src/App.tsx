import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { useBackendHealth } from './hooks/useBackendHealth';
import { DashboardPage } from './pages/DashboardPage';
import { DeploymentsPage } from './pages/DeploymentsPage';
import { MonitoringPage } from './pages/MonitoringPage';
import { PipelinesPage } from './pages/PipelinesPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { SettingsPage } from './pages/SettingsPage';

export function AppRoutes() {
  const healthState = useBackendHealth();

  return (
    <Routes>
      <Route element={<Layout healthState={healthState} />}>
        <Route index element={<DashboardPage healthState={healthState} />} />
        <Route path="projects" element={<ProjectsPage />} />
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
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  );
}
