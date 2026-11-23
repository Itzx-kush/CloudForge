import { Info, Shield, Workflow } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import './Pages.css';

interface PipelineTemplate {
  id: string;
  stage: string;
  runner: string;
  phase: string;
  purpose: string;
}

const PLANNED_PIPELINE_STAGES: PipelineTemplate[] = [
  {
    id: 'ci',
    stage: 'Repository CI (Build & Test)',
    runner: 'GitHub Actions',
    phase: 'Phase 1 (Platform CI) / Phase 3 (User Apps)',
    purpose: 'Compile, run unit tests, and validate pull requests automatically.',
  },
  {
    id: 'security',
    stage: 'DevSecOps Verification',
    runner: 'Isolated Scanner Worker',
    phase: 'Phase 4',
    purpose: 'SAST, dependency CVE audit, secret detection, and OCI image SBOM generation.',
  },
  {
    id: 'package',
    stage: 'Container Packaging',
    runner: 'Rootless Build Worker',
    phase: 'Phase 3',
    purpose: 'Deterministic multi-stage container builds with CPU/RAM/time limits.',
  },
  {
    id: 'cd',
    stage: 'Progressive Delivery',
    runner: 'CloudForge Deployer',
    phase: 'Phase 3',
    purpose: 'Promote signed images to Kubernetes namespaces with automated health checks.',
  },
];

const PIPELINE_COLUMNS: TableColumn<PipelineTemplate>[] = [
  {
    key: 'stage',
    header: 'Pipeline Stage',
    render: (row) => <strong>{row.stage}</strong>,
  },
  {
    key: 'runner',
    header: 'Execution Engine',
    render: (row) => <span className="mono">{row.runner}</span>,
  },
  {
    key: 'phase',
    header: 'Target Phase',
    render: (row) => <StatusBadge label={row.phase} tone="planned" size="sm" />,
  },
  {
    key: 'purpose',
    header: 'Responsibility',
    render: (row) => <span className="cf-muted">{row.purpose}</span>,
  },
];

export function PipelinesPage() {
  return (
    <PageContainer
      title="Pipelines"
      description="CI/CD workflow orchestration combining GitHub Actions status telemetry and isolated CloudForge build runners."
      phaseBadge={{ label: 'Phase 1 UI Foundation · Phase 3 CI/CD', tone: 'planned' }}
    >
      <div className="cf-notice cf-notice--planned">
        <div className="cf-notice__icon" aria-hidden="true">
          <Info size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Demonstration State — 0 Live Pipeline Runs</p>
          <p className="cf-notice__text">
            CloudForge&apos;s own repository CI workflow lives in{' '}
            <span className="mono">.github/workflows/ci.yml</span>. Tenant application
            pipeline execution and real-time build log streaming will be delivered in Phase 3
            using sandboxed workers.
          </p>
        </div>
      </div>

      <DashboardCard
        title="Planned CI/CD & DevSecOps Stage Architecture"
        subtitle="Stage-by-stage design for controlled build and release pipelines"
        icon={<Workflow size={17} />}
        headerAction={<StatusBadge label="Architecture Preview" tone="planned" size="sm" />}
      >
        <DataTable
          columns={PIPELINE_COLUMNS}
          rows={PLANNED_PIPELINE_STAGES}
          getRowKey={(row) => row.id}
          caption="Planned CI/CD pipeline stages"
        />
      </DashboardCard>

      <DashboardCard
        title="DevSecOps Guardrails (Phase 4)"
        subtitle="Planned policy enforcement before container promotion"
        icon={<Shield size={17} />}
        headerAction={<StatusBadge label="Planned" tone="planned" size="sm" />}
      >
        <div className="cf-steps">
          <div className="cf-step">
            <span className="cf-step__index">Gate 01</span>
            <h3 className="cf-step__title">Secret Scanning</h3>
            <p className="cf-step__desc">
              Block pipeline progression if accidental credentials or private keys are detected.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Gate 02</span>
            <h3 className="cf-step__title">Dependency Audit</h3>
            <p className="cf-step__desc">
              Inspect Maven and npm dependency graphs against known CVE advisories.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Gate 03</span>
            <h3 className="cf-step__title">Container CVE Scan</h3>
            <p className="cf-step__desc">
              Scan built OCI layers with Trivy and attach CycloneDX/SPDX SBOM metadata.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Gate 04</span>
            <h3 className="cf-step__title">Least-Privilege Manifests</h3>
            <p className="cf-step__desc">
              Enforce non-root security contexts, dropped Linux capabilities, and resource limits.
            </p>
          </div>
        </div>
      </DashboardCard>
    </PageContainer>
  );
}
