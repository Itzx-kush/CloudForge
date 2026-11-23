import { KeyRound, ServerCog, Settings, ShieldCheck } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import './Pages.css';

export interface SettingsPageProps {
  healthState: BackendHealthState;
}

export function SettingsPage({ healthState }: SettingsPageProps) {
  const payload = healthState.snapshot?.payload ?? null;

  return (
    <PageContainer
      title="Settings"
      description="Phase 1 local runtime configuration overview and planned security, identity, and cluster settings."
      phaseBadge={{ label: 'Phase 1 Read-Only Configuration', tone: 'neutral' }}
    >
      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Active Local Runtime Configuration"
          subtitle="Non-sensitive runtime metadata detected in this session"
          icon={<ServerCog size={17} />}
          headerAction={<StatusBadge label="Local Dev" tone="info" size="sm" />}
        >
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Frontend Proxy Route</dt>
              <dd className="mono">/api → VITE_API_PROXY_TARGET</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Default Backend Origin</dt>
              <dd className="mono">http://localhost:8080</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Backend Service Name</dt>
              <dd className="mono">{payload?.application ?? 'Not connected'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Backend Version</dt>
              <dd className="mono">{payload?.version ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Database Binding Policy</dt>
              <dd className="mono">127.0.0.1:5432 (localhost only)</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Authentication Mode</dt>
              <dd>Disabled in Phase 1 (planned for Phase 2)</dd>
            </div>
          </dl>
        </DashboardCard>

        <DashboardCard
          title="Secret Management Policy"
          subtitle="Security guardrails enforced from Phase 1 onward"
          icon={<ShieldCheck size={17} />}
          headerAction={<StatusBadge label="Enforced" tone="healthy" size="sm" />}
        >
          <ul className="cf-subtle-list">
            <li>
              <div>
                <div className="cf-subtle-list__title">Environment Variable Injection</div>
                <div className="cf-subtle-list__desc">
                  Database credentials are provided via local <span className="mono">.env</span>{' '}
                  (excluded from Git) and never hardcoded in source files.
                </div>
              </div>
              <StatusBadge label="Active" tone="healthy" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">Zero Secrets in Frontend Bundle</div>
                <div className="cf-subtle-list__desc">
                  Browser code communicates exclusively with same-origin{' '}
                  <span className="mono">/api/*</span> endpoints with no embedded credentials.
                </div>
              </div>
              <StatusBadge label="Active" tone="healthy" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">Future Encrypted Secret Store</div>
                <div className="cf-subtle-list__desc">
                  Phase 3+ deployment secrets will use Kubernetes External Secrets / KMS integration.
                </div>
              </div>
              <StatusBadge label="Planned" tone="planned" size="sm" />
            </li>
          </ul>
        </DashboardCard>
      </div>

      <DashboardCard
        title="Planned Platform Administration Modules"
        subtitle="Administrative controls deferred to subsequent phases"
        icon={<Settings size={17} />}
        headerAction={<StatusBadge label="Phase 2–4" tone="planned" size="sm" />}
      >
        <div className="cf-grid cf-grid--3">
          <div className="cf-step">
            <span className="cf-step__index">
              <KeyRound size={13} style={{ verticalAlign: '-2px', marginRight: '5px' }} />
              Phase 2
            </span>
            <h3 className="cf-step__title">Authentication &amp; RBAC</h3>
            <p className="cf-step__desc">
              GitHub OAuth login, workspace roles (Owner, Developer, Viewer), and audit logs.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Phase 3</span>
            <h3 className="cf-step__title">Deployment Targets</h3>
            <p className="cf-step__desc">
              Controlled Kubernetes cluster registration with namespace-scoped service accounts.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Phase 4</span>
            <h3 className="cf-step__title">Observability &amp; Policy</h3>
            <p className="cf-step__desc">
              Alert notification channels, CVE severity gates, and retention policies.
            </p>
          </div>
        </div>
      </DashboardCard>
    </PageContainer>
  );
}
