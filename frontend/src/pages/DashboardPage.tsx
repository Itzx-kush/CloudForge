import {
  Activity,
  Clock,
  Database,
  Layers,
  RefreshCw,
  Server,
  ShieldCheck,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';
import type { BackendHealthState } from '../hooks/useBackendHealth';
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
    module: 'Spring Boot Backend & Health API',
    targetPhase: 'Phase 1',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'Live GET /api/health endpoint with real JDBC PostgreSQL verification.',
  },
  {
    id: 'postgres-compose',
    module: 'PostgreSQL 17 & Docker Compose',
    targetPhase: 'Phase 1',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'Persistent local volume, health checks, and localhost-only port binding.',
  },
  {
    id: 'react-console',
    module: 'React + TypeScript Web Console',
    targetPhase: 'Phase 1',
    statusLabel: 'Implemented',
    tone: 'healthy',
    details: 'Responsive dark dashboard with Vite dev proxy and 7 navigation views.',
  },
  {
    id: 'auth-projects',
    module: 'Authentication, GitHub OAuth & Projects CRUD',
    targetPhase: 'Phase 2',
    statusLabel: 'Planned',
    tone: 'planned',
    details: 'User auth, GitHub App repository linking, and persisted project workspaces.',
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
    details: 'Cluster telemetry, alerting dashboards, SBOM generation, and policy checks.',
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
  const { snapshot, isLoading, isRefreshing, refresh } = healthState;
  const payload = snapshot?.payload ?? null;
  const backendHealthy = payload?.status === 'UP';
  const dbHealthy = payload?.database.status === 'UP';

  return (
    <PageContainer
      title="Platform Overview"
      description="Live Phase 1 control-plane connectivity between the React frontend, Spring Boot API, and PostgreSQL 17 database."
      phaseBadge={{ label: 'Phase 1 Active', tone: 'info' }}
      actions={
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => {
            void refresh();
          }}
          disabled={isRefreshing}
        >
          <RefreshCw
            size={14}
            className={isRefreshing ? 'cf-spin' : ''}
            aria-hidden="true"
          />
          <span>{isRefreshing ? 'Probing API...' : 'Probe Backend Health'}</span>
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

      <div className="cf-grid cf-grid--4" aria-label="Live connectivity metrics">
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

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">API Round-Trip</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Activity size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value mono">
            {snapshot?.clientLatencyMs !== null && snapshot?.clientLatencyMs !== undefined
              ? `${snapshot.clientLatencyMs} ms`
              : '—'}
          </div>
          <div className="cf-stat-tile__meta mono">
            GET /api/health via Vite proxy
          </div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Last Server Timestamp</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Clock size={17} />
            </span>
          </div>
          <div className="cf-stat-tile__value" style={{ fontSize: '16px' }}>
            {formatTimestamp(payload?.timestamp ?? snapshot?.checkedAt)}
          </div>
          <div className="cf-stat-tile__meta mono">
            {payload ? `Version ${payload.version}` : 'No live response yet'}
          </div>
        </div>
      </div>

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Live Backend Connectivity Details"
          subtitle="Verified directly from GET /api/health — no mocked health status"
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
              <dt>Application Status</dt>
              <dd className="mono">{payload?.status ?? 'UNREACHABLE'}</dd>
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

        <DashboardCard
          title="Phase 1 Scope & Security Boundary"
          subtitle="Transparent separation of live foundation features vs. planned automation"
          icon={<ShieldCheck size={17} />}
          headerAction={
            <StatusBadge label="Demonstration Notice" tone="planned" size="sm" />
          }
        >
          <div className="cf-notice cf-notice--info" style={{ marginBottom: '14px' }}>
            <div className="cf-notice__icon" aria-hidden="true">
              <Layers size={18} />
            </div>
            <div>
              <p className="cf-notice__title">No fabricated deployment statistics</p>
              <p className="cf-notice__text">
                Phase 1 establishes the verified full-stack foundation (React, Spring Boot,
                PostgreSQL 17, Docker Compose). Project records, GitHub repositories, CI
                pipelines, and Kubernetes deployments will be backed by real infrastructure
                starting in Phases 2 and 3.
              </p>
            </div>
          </div>

          <div className="cf-notice cf-notice--warning">
            <div className="cf-notice__icon" aria-hidden="true">
              <ShieldCheck size={18} />
            </div>
            <div>
              <p className="cf-notice__title">Repository execution is intentionally disabled</p>
              <p className="cf-notice__text">
                CloudForge does not execute arbitrary repository code in Phase 1. Future
                builds will run inside isolated, resource-limited workers with least-privilege
                credentials and controlled deployment targets.
              </p>
            </div>
          </div>
        </DashboardCard>
      </div>

      <DashboardCard
        title="Platform Implementation Matrix"
        subtitle="Current Phase 1 deliverables and upcoming multi-phase architecture milestones"
        icon={<Layers size={17} />}
        headerAction={
          <StatusBadge label="Roadmap Alignment" tone="info" size="sm" />
        }
      >
        <DataTable
          columns={CAPABILITY_COLUMNS}
          rows={PLATFORM_CAPABILITIES}
          getRowKey={(row) => row.id}
          caption="CloudForge Phase 1 and planned capabilities"
        />
      </DashboardCard>
    </PageContainer>
  );
}
