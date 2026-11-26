import { Activity, RefreshCw, Terminal } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import { WorkspaceOperations } from './WorkspaceOperations';
import { useAuth } from '../context/AuthContext';
import './Pages.css';

export interface MonitoringPageProps {
  healthState: BackendHealthState;
}

export function MonitoringPage({ healthState }: MonitoringPageProps) {
  const { activeWorkspace } = useAuth();
  const { snapshot, isLoading, isRefreshing, refresh } = healthState;
  const payload = snapshot?.payload ?? null;

  return (
    <PageContainer
      title="Monitoring"
      description="Backend health, worker activity and signed artifact assessments."
      phaseBadge={{ label: 'Live Health Probe + Phase 4 Telemetry', tone: 'info' }}
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
          <span>{isRefreshing ? 'Probing...' : 'Run Live Health Probe'}</span>
        </button>
      }
    >
      {isLoading && !snapshot ? (
        <LoadingState
          title="Running health probe"
          description="Requesting live status from GET /api/health..."
        />
      ) : snapshot?.networkError ? (
        <ErrorState
          title="Live probe failed — backend unreachable"
          message={snapshot.networkError}
          onRetry={() => {
            void refresh();
          }}
          isRetrying={isRefreshing}
        />
      ) : null}

      <DashboardCard
        title="Live API & Database Probe Inspector"
        subtitle="Raw JSON response returned directly by GET /api/health"
        icon={<Terminal size={17} />}
        highlight={Boolean(payload && payload.status === 'UP')}
        headerAction={
          <StatusBadge
            label={
              payload
                ? `${payload.status} · HTTP ${snapshot?.httpStatus ?? 200}`
                : isLoading ? 'Checking' : snapshot?.networkError ? 'Unreachable' : 'Unknown'
            }
            tone={
              payload
                ? payload.status === 'UP'
                  ? 'healthy'
                  : 'degraded'
                : isLoading ? 'info' : snapshot?.networkError ? 'error' : 'neutral'
            }
            pulse={Boolean(payload && payload.status === 'UP')}
            size="sm"
          />
        }
      >
        <div className="cf-grid cf-grid--3" style={{ marginBottom: '16px' }}>
          <div className="cf-kv-item">
            <dt>Probe Target</dt>
            <dd className="mono">GET /api/health</dd>
          </div>
          <div className="cf-kv-item">
            <dt>Client Round-Trip</dt>
            <dd className="mono">
              {snapshot?.clientLatencyMs !== null && snapshot?.clientLatencyMs !== undefined
                ? `${snapshot.clientLatencyMs} ms`
                : '—'}
            </dd>
          </div>
          <div className="cf-kv-item">
            <dt>Database Probe Latency</dt>
            <dd className="mono">
              {payload ? `${payload.database.responseTimeMs} ms` : '—'}
            </dd>
          </div>
        </div>

        <pre className="cf-code-block" aria-label="Raw health endpoint JSON payload">
          {payload
            ? JSON.stringify(payload, null, 2)
            : JSON.stringify(
                {
                  error: snapshot?.networkError ?? 'No response received',
                  checkedAt: snapshot?.checkedAt ?? null,
                },
                null,
                2
              )}
        </pre>
      </DashboardCard>

      {activeWorkspace && <WorkspaceOperations key={activeWorkspace.id} workspace={activeWorkspace.id} />}

      <DashboardCard
        title="Spring Boot Actuator Endpoints (Active in Phase 1)"
        subtitle="Standard operational health endpoints available on the backend"
        icon={<Activity size={17} />}
        headerAction={<StatusBadge label="Active" tone="healthy" size="sm" />}
      >
        <dl className="cf-kv-list">
          <div className="cf-kv-item">
            <dt>Primary Platform Health API</dt>
            <dd className="mono">GET /api/health</dd>
          </div>
          <div className="cf-kv-item">
            <dt>Actuator Health Probe</dt>
            <dd className="mono">GET /actuator/health</dd>
          </div>
          <div className="cf-kv-item">
            <dt>Actuator Application Info</dt>
            <dd className="mono">GET /actuator/info</dd>
          </div>
          <div className="cf-kv-item">
            <dt>Structured Correlation Header</dt>
            <dd className="mono">X-Request-Id (propagated to SLF4J MDC)</dd>
          </div>
        </dl>
      </DashboardCard>
    </PageContainer>
  );
}
