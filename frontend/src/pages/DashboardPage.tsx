import { ForgeStack } from '../components/ForgeStack';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  Database,
  FolderKanban,
  GitBranch,
  Layers,
  RefreshCw,
  Server,
  ShieldCheck,
  UserCheck,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';
import { useAuth } from '../context/AuthContext';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import { fetchProjects } from '../services/api';
import type { ProjectSummary } from '../types/platform';
import './Pages.css';

interface PlatformCapability {
  id: string;
  module: string;
  targetPhase: string;
  statusLabel: string;
  tone: BadgeTone;
  details: string;
}

const PLATFORM_CAPABILITIES: PlatformCapability[] = [
  {
    id: 'health-api',
    module: 'Spring Boot Backend & Live PostgreSQL Health API',
    targetPhase: 'Phase 1',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'Live GET /api/health endpoint with real JDBC PostgreSQL 17 verification.',
  },
  {
    id: 'auth-rbac',
    module: 'GitHub OAuth 2.0 Sessions, Persistent Users & Workspace RBAC',
    targetPhase: 'Phase 2',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'HTTP-only sessions, CSRF protection, Flyway migrations, OWNER/DEVELOPER/VIEWER roles, and audit logs.',
  },
  {
    id: 'github-app-projects',
    module: 'GitHub App Integration, Projects CRUD & Environments',
    targetPhase: 'Phase 2',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'Short-lived GitHub App tokens, HMAC-SHA256 webhooks, repository sync, projects, and non-secret environments.',
  },
  {
    id: 'build-k8s',
    module: 'Isolated Builds, Container Deployments & Kubernetes',
    targetPhase: 'Phase 3',
    statusLabel: 'Planned',
    tone: 'planned',
    details: 'Sandboxed build workers, image scanning, Kubernetes rollouts, and rollback.',
  },
  {
    id: 'observability',
    module: 'Prometheus Metrics, Grafana & DevSecOps Gates',
    targetPhase: 'Phase 4',
    statusLabel: 'Planned',
    tone: 'planned',
    details: 'Cluster telemetry, alerting dashboards, encrypted secrets KMS, SBOM generation, and policy checks.',
  },
];

const CAPABILITY_COLUMNS: TableColumn<PlatformCapability>[] = [
  {
    key: 'module',
    header: 'Platform Component',
    render: (row) => <strong>{row.module}</strong>,
  },
  {
    key: 'phase',
    header: 'Milestone',
    render: (row) => <span className="mono">{row.targetPhase}</span>,
  },
  {
    key: 'status',
    header: 'State',
    render: (row) => (
      <StatusBadge label={row.statusLabel} tone={row.tone} size="sm" />
    ),
  },
  {
    key: 'details',
    header: 'Scope',
    render: (row) => <span className="cf-muted">{row.details}</span>,
  },
];

function formatTimestamp(isoString: string | null | undefined): string {
  if (!isoString) {
    return '—';
  }
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) {
    return isoString;
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export interface DashboardPageProps {
  healthState: BackendHealthState;
}

export function DashboardPage({ healthState }: DashboardPageProps) {
  const { user, activeWorkspace, activeRole, authConfig, refreshSession } = useAuth();
  const { snapshot, isLoading, isRefreshing, refresh } = healthState;
  const payload = snapshot?.payload ?? null;
  const backendHealthy = payload?.status === 'UP';
  const dbHealthy = payload?.database.status === 'UP';

  const [recentProjects, setRecentProjects] = useState<ProjectSummary[]>([]);
  const [projectsError, setProjectsError] = useState(false);
  const [projectsLoading, setProjectsLoading] = useState(false);

  useEffect(() => {
    setProjectsError(false);
    if (!activeWorkspace) {
      setRecentProjects([]);
      return;
    }
    let cancelled = false;
    setProjectsLoading(true);
    fetchProjects(activeWorkspace.id, { page: 0, size: 5 })
      .then((res) => {
        if (!cancelled) {
          setRecentProjects(res.items);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setProjectsError(true);
          setRecentProjects([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setProjectsLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [activeWorkspace]);

  const handleRefreshAll = async () => {
    await Promise.all([refresh(), refreshSession()]);
  };

  return (
    <PageContainer
      title="Platform Overview"
      description={
        activeWorkspace
          ? `Projects, connections and service health for ${activeWorkspace.name}.`
          : 'Live CloudForge control-plane overview and backend connectivity.'
      }
      actions={
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => {
            void handleRefreshAll();
          }}
          disabled={isRefreshing}
        >
          <RefreshCw
            size={14}
            className={isRefreshing ? 'cf-spin' : ''}
            aria-hidden="true"
          />
          <span>{isRefreshing ? 'Refreshing...' : 'Probe Backend Health'}</span>
        </button>
      }
    >
      {isLoading && !snapshot ? (
        <LoadingState />
      ) : snapshot?.networkError ? (
        <ErrorState
          title="Spring Boot backend is unreachable"
          message={snapshot.networkError}
          detail={`Endpoint: GET /api/health · Checked at ${formatTimestamp(snapshot.checkedAt)}`}
          onRetry={() => {
            void refresh();
          }}
          isRetrying={isRefreshing}
        />
      ) : null}

      {payload && !dbHealthy && (
        <ErrorState
          title="Backend is running in degraded mode (database unavailable)"
          message={payload.database.message}
          detail={`HTTP ${snapshot?.httpStatus ?? 503} · Application status: ${payload.status} · Database status: ${payload.database.status}`}
          onRetry={() => {
            void refresh();
          }}
          isRetrying={isRefreshing}
        />
      )}

      <section className="cf-probe" aria-label="Control plane health">
        <div className="cf-probe__readout">
          <div className="cf-probe__label"><Server size={14} /> Control plane</div>
          <h2>{isLoading && !snapshot ? 'Checking services' : snapshot?.networkError ? 'Connection unavailable' : backendHealthy && dbHealthy ? 'Services operational' : payload ? 'Service degradation' : 'Awaiting health probe'}</h2>
          <p className="mono">GET /api/health</p>
          <div className="cf-probe__services">
        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Backend API</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Server size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value">
            {payload ? payload.status : snapshot?.networkError ? 'OFFLINE' : 'CHECKING'}
          </div>
          <div>
            <StatusBadge
              label={
                payload
                  ? backendHealthy
                    ? 'Healthy (HTTP 200)'
                    : `Degraded (HTTP ${snapshot?.httpStatus ?? 503})`
                  : snapshot?.networkError
                    ? 'Unreachable'
                    : 'Probing'
              }
              tone={
                payload
                  ? backendHealthy
                    ? 'healthy'
                    : 'degraded'
                  : snapshot?.networkError
                    ? 'error'
                    : 'info'
              }
              pulse={Boolean(payload && backendHealthy)}
              size="sm"
            />
          </div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">PostgreSQL Database</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Database size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value">
            {payload ? payload.database.status : 'UNKNOWN'}
          </div>
          <div className="cf-stat-tile__meta mono">
            {payload
              ? `${payload.database.engine} · ${payload.database.responseTimeMs} ms probe`
              : 'Waiting for backend response'}
          </div>
        </div>
          </div>
        </div>
        <ForgeStack />
      </section>
      <div className="cf-inventory" aria-label="Workspace and infrastructure telemetry">
        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Workspace Projects</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <FolderKanban size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="dashboard-project-count">
            {activeWorkspace ? activeWorkspace.projectCount : 0}
          </div>
          <div className="cf-stat-tile__meta">
            In this workspace ·{' '}
            <Link to="/projects" className="cf-link-accent">
              Manage
            </Link>
          </div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Connected Repositories</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <GitBranch size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="dashboard-repo-count">
            {activeWorkspace ? activeWorkspace.connectedRepositoryCount : 0}
          </div>
          <div className="cf-stat-tile__meta">
            {authConfig?.githubAppConfigured ? 'GitHub App active' : 'GitHub App setup required'} ·{' '}
            <Link to="/repositories" className="cf-link-accent">
              Explore
            </Link>
          </div>
        </div>

      </div>
      <DashboardCard
        title="Recent Workspace Projects"
        subtitle="Recently updated projects in this workspace"
        icon={<FolderKanban size={17} />}
        headerAction={
          <Link to="/projects" className="btn btn-sm">
            <span>View all projects</span>
            <ArrowRight size={13} aria-hidden="true" />
          </Link>
        }
      >
        {projectsLoading ? (
          <LoadingState
            title="Loading workspace projects"
            description="Querying persistent project records..."
          />
        ) : projectsError ? (
          <ErrorState title="Recent projects unavailable" message="Open Projects to retry loading this workspace." />
        ) : (
          <DataTable
            columns={[
              {
                key: 'name',
                header: 'Project',
                render: (row) => (
                  <Link to={`/projects/${row.id}`} className="cf-link-accent">
                    {row.name}
                  </Link>
                ),
              },
              {
                key: 'repo',
                header: 'Linked Repository',
                render: (row) =>
                  row.linkedRepository ? (
                    <span className="mono">{row.linkedRepository.fullName}</span>
                  ) : (
                    <span className="cf-muted">Not linked</span>
                  ),
              },
              {
                key: 'branch',
                header: 'Default Branch',
                render: (row) => <span className="mono">{row.defaultBranch}</span>,
              },
              {
                key: 'status',
                header: 'Status',
                render: (row) => (
                  <StatusBadge
                    label={row.status}
                    tone={
                      row.status === 'ACTIVE'
                        ? 'healthy'
                        : row.status === 'PAUSED'
                          ? 'degraded'
                          : 'neutral'
                    }
                    size="sm"
                  />
                ),
              },
            ]}
            rows={recentProjects}
            getRowKey={(row) => row.id}
            emptyMessage="No projects created in this workspace yet. Head to Projects to create your first project."
            caption="Recent workspace projects"
          />
        )}
      </DashboardCard>

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Authenticated Session & Workspace"
          subtitle="Verified server-side GitHub OAuth session and role-based access"
          icon={<UserCheck size={17} />}
          headerAction={
            activeRole ? (
              <StatusBadge label={`Role: ${activeRole}`} tone="info" size="sm" />
            ) : undefined
          }
        >
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Signed-In User</dt>
              <dd>
                <strong>{user?.displayName || user?.username}</strong>{' '}
                <span className="mono cf-muted">(@{user?.username})</span>
              </dd>
            </div>
            <div className="cf-kv-item">
              <dt>Active Workspace</dt>
              <dd>{activeWorkspace?.name ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Workspace Members</dt>
              <dd className="mono">{activeWorkspace?.memberCount ?? 0}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Last Login</dt>
              <dd className="mono">{formatTimestamp(user?.lastLoginAt)}</dd>
            </div>
          </dl>
        </DashboardCard>

        <DashboardCard
          title="Live Backend Connectivity Details"
          subtitle="Latest response from the workspace health endpoint"
          icon={<Server size={17} />}
          highlight={Boolean(payload && backendHealthy)}
          headerAction={
            <StatusBadge
              label={payload ? `HTTP ${snapshot?.httpStatus ?? 200}` : 'No Response'}
              tone={
                payload
                  ? backendHealthy
                    ? 'healthy'
                    : 'degraded'
                  : 'error'
              }
              size="sm"
            />
          }
        >
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Application Name</dt>
              <dd className="mono">{payload?.application ?? 'Unavailable'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Version</dt>
              <dd className="mono">{payload?.version ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Database Check</dt>
              <dd>{payload?.database.message ?? 'Could not contact backend'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Database Engine</dt>
              <dd className="mono">{payload?.database.engine ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Correlation Request ID</dt>
              <dd className="mono">{snapshot?.requestId ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Server ISO-8601 Timestamp</dt>
              <dd className="mono">{payload?.timestamp ?? '—'}</dd>
            </div>
          </dl>
        </DashboardCard>
      </div>

      <details className="cf-roadmap"><summary>Platform capabilities and roadmap</summary>
      <div className="cf-notice cf-notice--warning">
        <div className="cf-notice__icon" aria-hidden="true">
          <ShieldCheck size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Phase 2 Security Boundary: Builds &amp; Deployments Remain Disabled</p>
          <p className="cf-notice__text">
            Phase 2 delivers real authentication, workspace RBAC, GitHub App repository discovery,
            persistent projects, non-secret environments, and verified webhooks. Container builds,
            secret vaults, and Kubernetes rollouts are intentionally deferred to Phases 3 and 4.
          </p>
        </div>
      </div>

      <DashboardCard
        title="Platform Implementation Matrix"
        subtitle="Completed Phases 1–2 capabilities alongside upcoming Phases 3–4 milestones"
        icon={<Layers size={17} />}
        headerAction={
          <StatusBadge label="Roadmap Alignment" tone="info" size="sm" />
        }
      >
        <DataTable
          columns={CAPABILITY_COLUMNS}
          rows={PLATFORM_CAPABILITIES}
          getRowKey={(row) => row.id}
          caption="CloudForge implemented and planned capabilities"
        />
      </DashboardCard>
      </details>
    </PageContainer>
  );
}
