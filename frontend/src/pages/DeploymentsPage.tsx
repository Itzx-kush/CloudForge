import { History, Info, Rocket } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable } from '../components/DataTable';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import './Pages.css';

const LIFECYCLE_STAGES = [
  {
    step: 'Stage 01',
    title: 'Immutable Image Build',
    desc: 'Build OCI container images inside isolated workers and push immutable digests to a controlled registry.',
  },
  {
    step: 'Stage 02',
    title: 'Policy & Security Gate',
    desc: 'Verify image digest, vulnerability thresholds, and namespace resource quotas prior to rollout.',
  },
  {
    step: 'Stage 03',
    title: 'Kubernetes Rollout',
    desc: 'Apply declarative manifests with readiness probes and progressive rolling updates in controlled clusters.',
  },
  {
    step: 'Stage 04',
    title: 'History & Instant Rollback',
    desc: 'Record every release revision in PostgreSQL so engineers can revert to the last known healthy digest.',
  },
];

export function DeploymentsPage() {
  return (
    <PageContainer
      title="Deployments"
      description="Container release orchestration, deployment revision ledger, and controlled rollback workflows."
      phaseBadge={{ label: 'Future Capability · Phase 3 Execution', tone: 'planned' }}
      actions={
        <button
          type="button"
          className="btn btn-primary"
          disabled
          title="Real deployment orchestration will be built in Phase 3."
        >
          <Rocket size={15} aria-hidden="true" />
          <span>Trigger Deployment (Phase 3)</span>
        </button>
      }
    >
      <div className="cf-notice cf-notice--planned">
        <div className="cf-notice__icon" aria-hidden="true">
          <Info size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Demonstration State — No Live Deployments Triggered</p>
          <p className="cf-notice__text">
            CloudForge intentionally avoids simulating fake deployments. Real container
            deployment history, Kubernetes rollout progress, and one-click rollback will be
            connected to live cluster APIs in Phase 3.
          </p>
        </div>
      </div>

      <DashboardCard
        title="Planned Deployment Lifecycle"
        subtitle="Target end-to-end delivery workflow documented in docs/ARCHITECTURE.md"
        icon={<Rocket size={17} />}
        headerAction={<StatusBadge label="Phase 3 Target" tone="planned" size="sm" />}
      >
        <div className="cf-steps">
          {LIFECYCLE_STAGES.map((stage) => (
            <div key={stage.step} className="cf-step">
              <span className="cf-step__index">{stage.step}</span>
              <h3 className="cf-step__title">{stage.title}</h3>
              <p className="cf-step__desc">{stage.desc}</p>
            </div>
          ))}
        </div>
      </DashboardCard>

      <DashboardCard
        title="Deployment History Ledger"
        subtitle="Auditable release log for rollouts and rollbacks"
        icon={<History size={17} />}
        headerAction={<StatusBadge label="0 Releases" tone="neutral" size="sm" />}
      >
        <DataTable
          columns={[
            { key: 'release', header: 'Revision', render: () => null },
            { key: 'project', header: 'Project', render: () => null },
            { key: 'image', header: 'Image Digest', render: () => null },
            { key: 'target', header: 'Cluster / Namespace', render: () => null },
            { key: 'state', header: 'Status', render: () => null },
          ]}
          rows={[]}
          getRowKey={() => 'empty'}
          emptyMessage="No deployment records exist yet. Deployment execution, history persistence, and rollback are scheduled for Phase 3."
          caption="Deployment history table"
        />
      </DashboardCard>
    </PageContainer>
  );
}
