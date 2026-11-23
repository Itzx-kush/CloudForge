import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import type { BadgeTone } from './StatusBadge';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import { useAuth } from '../context/AuthContext';
import './Layout.css';

const PAGE_TITLES: Record<string, string> = {
  '/': 'Dashboard',
  '/projects': 'Projects',
  '/repositories': 'Repositories',
  '/deployments': 'Deployments',
  '/pipelines': 'Pipelines',
  '/monitoring': 'Monitoring',
  '/settings': 'Settings',
};

export interface LayoutProps {
  healthState: BackendHealthState;
}

function deriveHeaderStatus(healthState: BackendHealthState): {
  label: string;
  tone: BadgeTone;
} {
  if (healthState.isLoading && !healthState.snapshot) {
    return { label: 'Checking', tone: 'info' };
  }

  const snapshot = healthState.snapshot;
  if (!snapshot || snapshot.networkError || !snapshot.payload) {
    return { label: 'Unreachable', tone: 'error' };
  }

  if (
    snapshot.payload.status === 'UP' &&
    snapshot.payload.database.status === 'UP'
  ) {
    return { label: 'Connected', tone: 'healthy' };
  }

  return { label: 'Degraded', tone: 'degraded' };
}

function resolvePageTitle(pathname: string): string {
  if (PAGE_TITLES[pathname]) {
    return PAGE_TITLES[pathname];
  }
  if (pathname.startsWith('/projects/')) {
    return 'Project Detail';
  }
  return 'Dashboard';
}

export function Layout({ healthState }: LayoutProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const location = useLocation();
  const {
    user,
    workspaces,
    activeWorkspace,
    activeRole,
    setActiveWorkspaceId,
    logout,
    isLoggingOut,
  } = useAuth();

  const pageTitle = resolvePageTitle(location.pathname);
  const apiStatus = deriveHeaderStatus(healthState);

  return (
    <div className="cf-shell">
      <Sidebar
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        apiStatusLabel={apiStatus.label}
        apiStatusTone={apiStatus.tone}
        workspaces={workspaces}
        activeWorkspace={activeWorkspace}
        onSelectWorkspace={setActiveWorkspaceId}
      />
      <div className="cf-shell__main">
        <Header
          pageTitle={pageTitle}
          onMenuToggle={() => setSidebarOpen((open) => !open)}
          apiStatusLabel={apiStatus.label}
          apiStatusTone={apiStatus.tone}
          isRefreshing={healthState.isRefreshing}
          onRefresh={() => {
            void healthState.refresh();
          }}
          user={user}
          activeRole={activeRole}
          isLoggingOut={isLoggingOut}
          onLogout={() => {
            void logout();
          }}
        />
        <main className="cf-shell__content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
