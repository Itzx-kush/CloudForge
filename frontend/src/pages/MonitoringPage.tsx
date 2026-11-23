import { Activity, BarChart3, LineChart, RefreshCw, Terminal } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import './Pages.css';

export interface MonitoringPageProps {
  healthState: BackendHealthState;
}

export function MonitoringPage({ healthState }: MonitoringPageProps) {
  const { snapshot, isLoading, isRefreshing, refresh } = healthState;
  const payload = snapshot?.payload ?? null;

  return (
    <PageContainer
      title="Monitoring"
      description="Live backend & PostgreSQL health inspection and planned Phase 4 Prometheus & Grafana observability stack."
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
                : 'Unreachable'
            }
            tone={
              payload
                ? payload.status === 'UP'
                  ? 'healthy'
                  : 'degraded'
                : 'error'
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

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Prometheus Metrics Scraping"
          subtitle="Planned Phase 4 time-series collection"
          icon={<LineChart size={17} />}
          headerAction={<StatusBadge label="Planned · Phase 4" tone="planned" size="sm" />}
        >
          <ul className="cf-subtle-list">
            <li>
              <div>
                <div className="cf-subtle-list__title">Spring Boot Micrometer Exporter</div>
                <div className="cf-subtle-list__desc">
                  Expose JVM, HTTP latency percentiles, and HikariCP connection pool metrics via{' '}
                  <span className="mono">/actuator/prometheus</span>.
                </div>
              </div>
              <StatusBadge label="Planned" tone="planned" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">PostgreSQL &amp; Cluster Exporters</div>
                <div className="cf-subtle-list__desc">
                  Track query saturation, container CPU/memory throttling, and pod restarts.
                </div>
              </div>
              <StatusBadge label="Planned" tone="planned" size="sm" />
            </li>
          </ul>
        </DashboardCard>

        <DashboardCard
          title="Grafana Dashboards & Alerting"
          subtitle="Planned Phase 4 visualization and SLO alerts"
          icon={<BarChart3 size={17} />}
          headerAction={<StatusBadge label="Planned · Phase 4" tone="planned" size="sm" />}
        >
          <ul className="cf-subtle-list">
            <li>
              <div>
                <div className="cf-subtle-list__title">Provisioned Infrastructure Dashboards</div>
                <div className="cf-subtle-list__desc">
                  Version-controlled dashboard JSON definitions stored in{' '}
                  <span className="mono">infrastructure/monitoring/</span>.
                </div>
              </div>
              <StatusBadge label="Planned" tone="planned" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">Degradation &amp; Rollout Alerts</div>
                <div className="cf-subtle-list__desc">
                  Automated alerting on failed health checks, database disconnects, and error spikes.
                </div>
              </div>
              <StatusBadge label="Planned" tone="planned" size="sm" />
            </li>
          </ul>
        </DashboardCard>
      </div>

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
