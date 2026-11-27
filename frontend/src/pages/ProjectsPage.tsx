import { SearchField } from '../components/SearchField';
import { Toast } from '../components/Toast';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  ExternalLink,
  FolderKanban,
  Pencil,
  Plus,
  Trash2,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { Modal } from '../components/Modal';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import { useAuth } from '../context/AuthContext';
import {
  ApiClientError,
  createProject,
  deleteProject,
  fetchProjects,
  fetchRepositories,
  updateProject,
} from '../services/api';
import type {
  LinkedRepository,
  ProjectStatus,
  ProjectSummary,
} from '../types/platform';
import './Pages.css';

interface ProjectFormState {
  name: string;
  description: string;
  linkedRepositoryId: string;
  defaultBranch: string;
  status: ProjectStatus;
}

const INITIAL_FORM: ProjectFormState = {
  name: '',
  description: '',
  linkedRepositoryId: '',
  defaultBranch: 'main',
  status: 'ACTIVE',
};

export function ProjectsPage() {
  const { activeWorkspace, activeRole, refreshSession } = useAuth();
  const canWrite = activeRole === 'OWNER' || activeRole === 'DEVELOPER';
  const canDelete = activeRole === 'OWNER';

  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<ProjectStatus | ''>('');

  const [repositories, setRepositories] = useState<LinkedRepository[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Create / Edit modal state
  const [modalMode, setModalMode] = useState<'create' | 'edit' | null>(null);
  const [editingProject, setEditingProject] = useState<ProjectSummary | null>(null);
  const [form, setForm] = useState<ProjectFormState>(INITIAL_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isSaving, setIsSaving] = useState(false);

  // Delete confirmation modal state
  const [deletingProject, setDeletingProject] = useState<ProjectSummary | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const loadProjects = useCallback(async () => {
    if (!activeWorkspace) {
      setProjects([]);
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const response = await fetchProjects(activeWorkspace.id, {
        q: search,
        status: statusFilter,
        page,
        size: 15,
      });
      setProjects(response.items);
      setTotalElements(response.totalElements);
      setTotalPages(response.totalPages);
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to load workspace projects.'
      );
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace, page, search, statusFilter]);

  useEffect(() => {
    void loadProjects();
  }, [loadProjects]);

  useEffect(() => {
    if (!activeWorkspace) {
      setRepositories([]);
      return;
    }
    fetchRepositories(activeWorkspace.id, { status: 'CONNECTED', page: 0, size: 100 })
      .then((res) => setRepositories(res.items))
      .catch(() => setRepositories([]));
  }, [activeWorkspace]);

  const openCreateModal = () => {
    setEditingProject(null);
    setForm(INITIAL_FORM);
    setFormError(null);
    setFieldErrors({});
    setModalMode('create');
  };

  const openEditModal = (project: ProjectSummary) => {
    setEditingProject(project);
    setForm({
      name: project.name,
      description: project.description ?? '',
      linkedRepositoryId: project.linkedRepository?.id ?? '',
      defaultBranch: project.defaultBranch,
      status: project.status,
    });
    setFormError(null);
    setFieldErrors({});
    setModalMode('edit');
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace) return;

    const trimmedName = form.name.trim();
    if (trimmedName.length < 2) {
      document.getElementById('cf-proj-name')?.focus();
      setFieldErrors({ name: 'Project name must be at least 2 characters' });
      return;
    }

    if (!form.defaultBranch.trim()) {
      setFieldErrors({ defaultBranch: 'Enter a default branch.' });
      document.getElementById('cf-proj-branch')?.focus();
      return;
    }

    setIsSaving(true);
    setFormError(null);
    setFieldErrors({});
    setSuccessMessage(null);

    try {
      if (modalMode === 'create') {
        await createProject(activeWorkspace.id, {
          name: trimmedName,
          description: form.description.trim() || undefined,
          linkedRepositoryId: form.linkedRepositoryId || null,
          defaultBranch: form.defaultBranch.trim() || 'main',
          status: form.status,
        });
        setSuccessMessage(`Project "${trimmedName}" created successfully.`);
      } else if (modalMode === 'edit' && editingProject) {
        await updateProject(activeWorkspace.id, editingProject.id, {
          name: trimmedName,
          description: form.description.trim() || undefined,
          linkedRepositoryId: form.linkedRepositoryId || null,
          defaultBranch: form.defaultBranch.trim() || 'main',
          status: form.status,
        });
        setSuccessMessage(`Project "${trimmedName}" updated successfully.`);
      }

      setModalMode(null);
      await Promise.all([loadProjects(), refreshSession()]);
    } catch (error) {
      if (error instanceof ApiClientError) {
        setFormError(error.message);
        setFieldErrors(error.fieldErrors);
      } else {
        setFormError('Unable to save project changes.');
      }
    } finally {
      setIsSaving(false);
    }
  };

  const handleConfirmDelete = async () => {
    if (!activeWorkspace || !deletingProject) return;
    setIsDeleting(true);
    setErrorMessage(null);
    setSuccessMessage(null);

    try {
      await deleteProject(activeWorkspace.id, deletingProject.id);
      setSuccessMessage(`Project "${deletingProject.name}" was permanently deleted.`);
      setDeletingProject(null);
      await Promise.all([loadProjects(), refreshSession()]);
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to delete project.'
      );
      setDeletingProject(null);
    } finally {
      setIsDeleting(false);
    }
  };

  const columns: TableColumn<ProjectSummary>[] = [
    {
      key: 'name',
      header: 'Project',
      render: (row) => (
        <div>
          <Link to={`/projects/${row.id}`} className="cf-link-accent">
            {row.name}
          </Link>
          <div className="mono cf-muted" style={{ fontSize: '11.5px' }}>
            {row.slug}
          </div>
        </div>
      ),
    },
    {
      key: 'repo',
      header: 'Linked Repository',
      render: (row) =>
        row.linkedRepository ? (
          <span className="mono">{row.linkedRepository.fullName}</span>
        ) : (
          <span className="cf-muted">Unlinked</span>
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
    {
      key: 'updated',
      header: 'Updated',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {new Date(row.updatedAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <div className="cf-inline-actions cf-inline-actions--end">
          <Link to={`/projects/${row.id}`} className="btn btn-sm">
            <ExternalLink size={12} aria-hidden="true" />
            <span>Details</span>
          </Link>
          {canWrite && (
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => openEditModal(row)}
              aria-label={`Edit ${row.name}`}
            >
              <Pencil size={12} aria-hidden="true" />
              <span>Edit</span>
            </button>
          )}
          {canDelete && (
            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={() => setDeletingProject(row)}
              aria-label={`Delete ${row.name}`}
            >
              <Trash2 size={12} aria-hidden="true" />
              <span>Delete</span>
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <PageContainer
      title="Projects"
      description={
        activeWorkspace
          ? `Persistent project registry for workspace "${activeWorkspace.name}".`
          : 'Self-service workspace project registry.'
      }
      phaseBadge={{ label: 'PostgreSQL Persistence', tone: 'healthy' }}
      actions={
        <button
          type="button"
          className="btn btn-primary"
          onClick={openCreateModal}
          disabled={!canWrite || !activeWorkspace}
          title={
            canWrite
              ? 'Create a new persistent project in this workspace'
              : 'VIEWER role cannot create projects'
          }
        >
          <Plus size={15} aria-hidden="true" />
          <span>New Project</span>
        </button>
      }
    >
      {successMessage && <Toast message={successMessage} onDismiss={() => setSuccessMessage(null)} />}

      {errorMessage && (
        <ErrorState
          title="Could not complete projects request"
          message={errorMessage}
          onRetry={() => {
            void loadProjects();
          }}
        />
      )}

      <DashboardCard
        title="Workspace Projects"
        subtitle="Backed by PostgreSQL with Flyway-managed schemas and workspace RBAC"
        icon={<FolderKanban size={17} />}
        headerAction={
          <StatusBadge
            label={`${totalElements} ${totalElements === 1 ? 'Project' : 'Projects'}`}
            tone="info"
            size="sm"
          />
        }
      >
        <div className="cf-toolbar">
          <SearchField label="Search projects" placeholder="Search projects by name, slug, or description..." value={search}
            onChange={value => { setPage(0); setSearch(value); }} />
          <div className="cf-filter-group">
            <label htmlFor="cf-project-status-filter" className="sr-only">
              Filter by status
            </label>
            <select
              id="cf-project-status-filter"
              className="cf-filter-select"
              value={statusFilter}
              onChange={(event) => {
                setPage(0);
                setStatusFilter(event.target.value as ProjectStatus | '');
              }}
            >
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="PAUSED">Paused</option>
              <option value="ARCHIVED">Archived</option>
            </select>
          </div>
        </div>

        {isLoading ? (
          <LoadingState
            title="Loading workspace projects"
            description="Fetching persistent project records from Spring Boot..."
          />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={projects}
              getRowKey={(row) => row.id}
              emptyMessage={
                search || statusFilter
                  ? 'No projects match your current search or filter.'
                  : 'No projects exist in this workspace yet. Click "New Project" to create one.'
              }
              caption="Workspace projects"
            />
            {totalPages > 1 && (
              <div className="cf-pagination">
                <span>
                  Page {page + 1} of {totalPages} ({totalElements} total)
                </span>
                <div className="cf-inline-actions">
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={page === 0}
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                  >
                    Previous
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={page + 1 >= totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </DashboardCard>

      <Modal
        isOpen={modalMode !== null}
        title={modalMode === 'edit' ? 'Edit Project' : 'Create Project'}
        subtitle={
          modalMode === 'edit'
            ? 'Update project metadata, default branch, or repository link.'
            : 'Create a persistent project in PostgreSQL with default Development, Staging, and Production environments.'
        }
        onClose={() => setModalMode(null)}
      >
        <form noValidate className="cf-form" onSubmit={(e) => void handleSubmit(e)}>
          {formError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertCircle size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Validation error</p>
                <p className="cf-notice__text">{formError}</p>
              </div>
            </div>
          )}

          <div className="cf-field">
            <label htmlFor="cf-proj-name" className="cf-field__label">
              Project Name *
            </label>
            <input
              id="cf-proj-name"
              aria-invalid={Boolean(fieldErrors.name)}
              aria-describedby={fieldErrors.name ? 'cf-proj-name-error' : undefined}
              type="text"
              className="cf-input"
              required
              minLength={2}
              maxLength={120}
              placeholder="e.g. billing-api"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
            {fieldErrors.name && (
              <span id="cf-proj-name-error" className="cf-field__error">{fieldErrors.name}</span>
            )}
          </div>

          <div className="cf-field">
            <label htmlFor="cf-proj-desc" className="cf-field__label">
              Description
            </label>
            <textarea
              id="cf-proj-desc"
              className="cf-textarea resize-none" rows={4}
              maxLength={1000}
              placeholder="Briefly describe this service or application..."
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />
          </div>

          <div className="cf-field">
            <label htmlFor="cf-proj-repo" className="cf-field__label">
              Linked GitHub Repository
            </label>
            <select
              id="cf-proj-repo"
              className="cf-select"
              value={form.linkedRepositoryId}
              onChange={(e) => {
                const selectedId = e.target.value;
                const matched = repositories.find((r) => r.id === selectedId);
                setForm({
                  ...form,
                  linkedRepositoryId: selectedId,
                  defaultBranch: matched ? matched.defaultBranch : form.defaultBranch,
                });
              }}
            >
              <option value="">No repository linked</option>
              {repositories.map((repo) => (
                <option key={repo.id} value={repo.id}>
                  {repo.fullName} ({repo.defaultBranch})
                </option>
              ))}
            </select>
            <span className="cf-field__hint">
              Only authorized repositories synced from this workspace&apos;s GitHub App
              installations are listed.
            </span>
          </div>

          <div className="cf-grid cf-grid--2">
            <div className="cf-field">
              <label htmlFor="cf-proj-branch" className="cf-field__label">
                Default Branch *
              </label>
              <input
                id="cf-proj-branch"
              aria-invalid={Boolean(fieldErrors.defaultBranch)}
              aria-describedby={fieldErrors.defaultBranch ? 'cf-proj-branch-error' : undefined}
                type="text"
                className="cf-input mono"
                required
                value={form.defaultBranch}
                onChange={(e) => setForm({ ...form, defaultBranch: e.target.value })}
              />
              {fieldErrors.defaultBranch && (
                <span id="cf-proj-branch-error" className="cf-field__error">{fieldErrors.defaultBranch}</span>
              )}
            </div>

            <div className="cf-field">
              <label htmlFor="cf-proj-status" className="cf-field__label">
                Project Status
              </label>
              <select
                id="cf-proj-status"
                className="cf-select"
                value={form.status}
                onChange={(e) =>
                  setForm({ ...form, status: e.target.value as ProjectStatus })
                }
              >
                <option value="ACTIVE">ACTIVE</option>
                <option value="PAUSED">PAUSED</option>
                <option value="ARCHIVED">ARCHIVED</option>
              </select>
            </div>
          </div>

          <div className="cf-inline-actions cf-inline-actions--end" style={{ marginTop: '6px' }}>
            <button
              type="button"
              className="btn"
              onClick={() => setModalMode(null)}
              disabled={isSaving}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSaving}>
              {isSaving
                ? 'Saving...'
                : modalMode === 'edit'
                  ? 'Save Changes'
                  : 'Create Project'}
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={deletingProject !== null}
        title="Delete Project"
        subtitle="This action permanently deletes the project and all of its environment configurations."
        onClose={() => setDeletingProject(null)}
        footer={
          <>
            <button
              type="button"
              className="btn"
              onClick={() => setDeletingProject(null)}
              disabled={isDeleting}
            >
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => void handleConfirmDelete()}
              disabled={isDeleting}
            >
              {isDeleting ? 'Deleting...' : 'Confirm Delete'}
            </button>
          </>
        }
      >
        <p style={{ margin: 0, color: 'var(--text-secondary)' }}>
          Are you sure you want to delete project{' '}
          <strong style={{ color: 'var(--text-primary)' }}>
            {deletingProject?.name}
          </strong>
          ? This action is recorded in the workspace audit log and cannot be undone.
        </p>
      </Modal>
    </PageContainer>
  );
}
