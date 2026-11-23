import { useState } from 'react';
import { FolderKanban, Info, Plus } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import './Pages.css';

interface ProjectSchemaPreview {
  id: string;
  field: string;
  type: string;
  purpose: string;
}

const PLANNED_PROJECT_SCHEMA: ProjectSchemaPreview[] = [
  {
    id: 'name',
    field: 'slug / name',
    type: 'VARCHAR(100)',
    purpose: 'Unique workspace identifier and human-readable project title.',
  },
  {
    id: 'repo',
    field: 'repository_id',
    type: 'UUID (FK)',
    purpose: 'Links project to a verified GitHub App installation repository.',
  },
  {
    id: 'target',
    field: 'deployment_target',
    type: 'ENUM (LOCAL_K8S, CLUSTER)',
    purpose: 'Controlled target namespace and resource quota policy.',
  },
  {
    id: 'branch',
    field: 'default_branch',
    type: 'VARCHAR(120)',
    purpose: 'Branch authorized to trigger automated container builds.',
  },
];

const SCHEMA_COLUMNS: TableColumn<ProjectSchemaPreview>[] = [
  {
    key: 'field',
    header: 'Planned Field',
    render: (row) => <span className="mono">{row.field}</span>,
  },
  {
    key: 'type',
    header: 'PostgreSQL Type',
    render: (row) => <span className="mono cf-muted">{row.type}</span>,
  },
  {
    key: 'purpose',
    header: 'Planned Responsibility',
    render: (row) => <span className="cf-muted">{row.purpose}</span>,
  },
];

export function ProjectsPage() {
  const [filter, setFilter] = useState('');

  const filteredSchema = PLANNED_PROJECT_SCHEMA.filter(
    (item) =>
      item.field.toLowerCase().includes(filter.toLowerCase()) ||
      item.purpose.toLowerCase().includes(filter.toLowerCase())
  );

  return (
    <PageContainer
      title="Projects"
      description="Self-service workspace registry for applications managed by CloudForge."
      phaseBadge={{ label: 'Phase 1 UI Foundation · Phase 2 Persistence', tone: 'planned' }}
      actions={
        <button
          type="button"
          className="btn btn-primary"
          disabled
          title="Project creation will be enabled in Phase 2 once PostgreSQL domain migrations are introduced."
        >
          <Plus size={15} aria-hidden="true" />
          <span>New Project (Phase 2)</span>
        </button>
      }
    >
      <div className="cf-notice cf-notice--planned">
        <div className="cf-notice__icon" aria-hidden="true">
          <Info size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Demonstration State — 0 Live Projects Configured</p>
          <p className="cf-notice__text">
            No fabricated projects or deployment counters are displayed here. In Phase 2,
            Spring Boot REST endpoints and Flyway-managed PostgreSQL tables will persist
            real CloudForge projects with environment quotas and ownership controls.
          </p>
        </div>
      </div>

      <DashboardCard
        title="Active Workspace Projects"
        subtitle="Real project inventory will appear here once Phase 2 CRUD APIs are implemented"
        icon={<FolderKanban size={17} />}
        headerAction={<StatusBadge label="0 Projects" tone="neutral" size="sm" />}
      >
        <DataTable
          columns={[
            { key: 'name', header: 'Project', render: () => null },
            { key: 'repo', header: 'Repository', render: () => null },
            { key: 'env', header: 'Target Environment', render: () => null },
            { key: 'status', header: 'Deployment Status', render: () => null },
          ]}
          rows={[]}
          getRowKey={() => 'empty'}
          emptyMessage="No projects exist in Phase 1. Project creation, RBAC ownership, and PostgreSQL persistence are scheduled for Phase 2."
          caption="Workspace projects table"
        />
      </DashboardCard>

      <DashboardCard
        title="Planned Project Domain Model (Phase 2 Blueprint)"
        subtitle="Preview of the PostgreSQL schema structure prepared on top of BaseAuditableEntity"
        icon={<FolderKanban size={17} />}
        headerAction={<StatusBadge label="Blueprint Preview" tone="planned" size="sm" />}
      >
        <div className="cf-toolbar">
          <input
            type="search"
            className="cf-search-input"
            placeholder="Filter planned schema fields..."
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            aria-label="Filter planned schema fields"
          />
          <span className="cf-muted mono" style={{ fontSize: '12px' }}>
            {filteredSchema.length} of {PLANNED_PROJECT_SCHEMA.length} fields
          </span>
        </div>
        <DataTable
          columns={SCHEMA_COLUMNS}
          rows={filteredSchema}
          getRowKey={(row) => row.id}
          emptyMessage="No planned fields match your filter."
          caption="Planned PostgreSQL schema preview"
        />
      </DashboardCard>
    </PageContainer>
  );
}
