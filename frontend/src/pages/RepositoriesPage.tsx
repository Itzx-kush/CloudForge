import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  FolderPlus,
  GitBranch,
  Github,
  Link2,
  RefreshCw,
  ShieldAlert,
  Unlink,
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
  createGitHubInstallUrl,
  createProject,
  disconnectGitHubInstallation,
  fetchGitHubInstallations,
  fetchProjects,
  fetchRepositories,
  syncGitHubInstallation,
  updateProject,
} from '../services/api';
import type {
  GitHubInstallation,
  LinkedRepository,
  ProjectSummary,
  RepositoryConnectionStatus,
} from '../types/platform';
import './Pages.css';

export function RepositoriesPage() {
  const { activeWorkspace, activeRole, refreshSession } = useAuth();
  const isOwner = activeRole === 'OWNER';
  const canLinkProject = activeRole === 'OWNER' || activeRole === 'DEVELOPER';

  const [searchParams] = useSearchParams();
  const installQueryStatus = searchParams.get('installation');
  const installQueryError = searchParams.get('installation_error');

  const [githubAppConfigured, setGithubAppConfigured] = useState(false);
  const [githubAppSlug, setGithubAppSlug] = useState<string | null>(null);
  const [installations, setInstallations] = useState<GitHubInstallation[]>([]);
  const [repositories, setRepositories] = useState<LinkedRepository[]>([]);
  const [totalRepos, setTotalRepos] = useState(0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [search, setSearch] = useState('');
  const [visibilityFilter, setVisibilityFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<RepositoryConnectionStatus | ''>('');

  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [syncingId, setSyncingId] = useState<string | null>(null);
  const [isInitiatingInstall, setIsInitiatingInstall] = useState(false);

  // Selected repository detail modal
  const [selectedRepo, setSelectedRepo] = useState<LinkedRepository | null>(null);

  // Link repository to project modal
  const [linkingRepo, setLinkingRepo] = useState<LinkedRepository | null>(null);
  const [workspaceProjects, setWorkspaceProjects] = useState<ProjectSummary[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [newProjectName, setNewProjectName] = useState('');
  const [linkMode, setLinkMode] = useState<'existing' | 'new'>('existing');
  const [isLinking, setIsLinking] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (!activeWorkspace) {
      setInstallations([]);
      setRepositories([]);
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const [installRes, repoRes] = await Promise.all([
        fetchGitHubInstallations(activeWorkspace.id),
        fetchRepositories(activeWorkspace.id, {
          q: search,
          visibility: visibilityFilter,
          status: statusFilter,
          page,
          size: 20,
        }),
      ]);
      setGithubAppConfigured(installRes.githubAppConfigured);
      setGithubAppSlug(installRes.githubAppSlug);
      setInstallations(installRes.installations);
      setRepositories(repoRes.items);
      setTotalRepos(repoRes.totalElements);
      setTotalPages(repoRes.totalPages);
    } catch (error) {
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Failed to load GitHub installations and repositories.'
      );
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace, page, search, statusFilter, visibilityFilter]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const handleConnectGitHubApp = async () => {
    if (!activeWorkspace) return;
    setIsInitiatingInstall(true);
    setErrorMessage(null);
    try {
      const { installUrl } = await createGitHubInstallUrl(activeWorkspace.id);
      window.location.assign(installUrl);
    } catch (error) {
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to initiate GitHub App installation.'
      );
      setIsInitiatingInstall(false);
    }
  };

  const handleSync = async (installation: GitHubInstallation) => {
    if (!activeWorkspace) return;
    setSyncingId(installation.id);
    setErrorMessage(null);
    setActionNotice(null);
    try {
      const res = await syncGitHubInstallation(activeWorkspace.id, installation.id);
      setActionNotice(res.message);
      await Promise.all([loadData(), refreshSession()]);
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to synchronize repositories.'
      );
    } finally {
      setSyncingId(null);
    }
  };

  const handleDisconnect = async (installation: GitHubInstallation) => {
    if (!activeWorkspace) return;
    setErrorMessage(null);
    setActionNotice(null);
    try {
      await disconnectGitHubInstallation(activeWorkspace.id, installation.id);
      setActionNotice(
        `Disconnected GitHub installation for @${installation.accountLogin}.`
      );
      await Promise.all([loadData(), refreshSession()]);
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to disconnect installation.'
      );
    }
  };

  const openLinkModal = async (repo: LinkedRepository) => {
    if (!activeWorkspace) return;
    setLinkingRepo(repo);
    setLinkError(null);
    setNewProjectName(repo.name);
    try {
      const projRes = await fetchProjects(activeWorkspace.id, { page: 0, size: 100 });
      setWorkspaceProjects(projRes.items);
      if (projRes.items.length > 0) {
        setLinkMode('existing');
        setSelectedProjectId(projRes.items[0].id);
      } else {
        setLinkMode('new');
        setSelectedProjectId('');
      }
    } catch {
      setWorkspaceProjects([]);
      setLinkMode('new');
    }
  };

  const handleLinkSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || !linkingRepo) return;
    setIsLinking(true);
    setLinkError(null);

    try {
      if (linkMode === 'existing') {
        const target = workspaceProjects.find((p) => p.id === selectedProjectId);
        if (!target) {
          setLinkError('Please select a target project.');
          setIsLinking(false);
          return;
        }
        await updateProject(activeWorkspace.id, target.id, {
          name: target.name,
          description: target.description ?? undefined,
          linkedRepositoryId: linkingRepo.id,
          defaultBranch: linkingRepo.defaultBranch,
          status: target.status,
        });
        setActionNotice(
          `Linked ${linkingRepo.fullName} to project "${target.name}".`
        );
      } else {
        const trimmedName = newProjectName.trim();
        if (trimmedName.length < 2) {
          setLinkError('Project name must be at least 2 characters.');
          setIsLinking(false);
          return;
        }
        await createProject(activeWorkspace.id, {
          name: trimmedName,
          description: linkingRepo.description ?? undefined,
          linkedRepositoryId: linkingRepo.id,
          defaultBranch: linkingRepo.defaultBranch,
          status: 'ACTIVE',
        });
        setActionNotice(
          `Created project "${trimmedName}" linked to ${linkingRepo.fullName}.`
        );
      }
      setLinkingRepo(null);
      await refreshSession();
    } catch (error) {
      setLinkError(
        error instanceof Error ? error.message : 'Unable to link repository.'
      );
    } finally {
      setIsLinking(false);
    }
  };

  const repoColumns: TableColumn<LinkedRepository>[] = [
    {
      key: 'fullName',
      header: 'Repository',
      render: (row) => (
        <div>
          <button
            type="button"
            className="btn-ghost"
            style={{ padding: 0, textAlign: 'left', color: 'var(--accent-primary)', fontWeight: 600 }}
            onClick={() => setSelectedRepo(row)}
          >
            <span className="mono">{row.fullName}</span>
          </button>
          {row.description && (
            <div className="cf-muted" style={{ fontSize: '12px', marginTop: '2px' }}>
              {row.description}
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'branch',
      header: 'Default Branch',
      render: (row) => <span className="mono">{row.defaultBranch}</span>,
    },
    {
      key: 'visibility',
      header: 'Visibility',
      render: (row) => (
        <StatusBadge
          label={row.visibility}
          tone={row.visibility === 'public' ? 'info' : 'neutral'}
          size="sm"
        />
      ),
    },
    {
      key: 'status',
      header: 'Connection',
      render: (row) => (
        <StatusBadge
          label={row.connectionStatus}
          tone={
            row.connectionStatus === 'CONNECTED'
              ? 'healthy'
              : row.connectionStatus === 'SUSPENDED'
                ? 'degraded'
                : 'error'
          }
          size="sm"
        />
      ),
    },
    {
      key: 'updated',
      header: 'Last Updated',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {row.githubUpdatedAt
            ? new Date(row.githubUpdatedAt).toLocaleDateString()
            : new Date(row.lastSyncedAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <div className="cf-inline-actions cf-inline-actions--end">
          {canLinkProject && row.connectionStatus === 'CONNECTED' && (
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => void openLinkModal(row)}
            >
              <Link2 size={12} aria-hidden="true" />
              <span>Link to Project</span>
            </button>
          )}
          {row.htmlUrl && (
            <a
              href={row.htmlUrl}
              target="_blank"
              rel="noreferrer"
              className="btn btn-sm"
              aria-label={`Open ${row.fullName} on GitHub`}
            >
              <ExternalLink size={12} aria-hidden="true" />
            </a>
          )}
        </div>
      ),
    },
  ];

  return (
    <PageContainer
      title="Repositories"
      description="Least-privilege GitHub App installations, authorized repository discovery, and project linking."
      phaseBadge={{
        label: githubAppConfigured ? 'GitHub App Configured' : 'GitHub App Setup Required',
        tone: githubAppConfigured ? 'healthy' : 'degraded',
      }}
      actions={
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => void handleConnectGitHubApp()}
          disabled={!isOwner || !githubAppConfigured || isInitiatingInstall}
          title={
            !githubAppConfigured
              ? 'Configure GitHub App environment variables first (see docs/GITHUB_INTEGRATION.md)'
              : !isOwner
                ? 'Only workspace OWNERs can connect GitHub App installations'
                : 'Install or connect the CloudForge GitHub App'
          }
        >
          <Github size={15} aria-hidden="true" />
          <span>{isInitiatingInstall ? 'Redirecting...' : 'Connect GitHub App'}</span>
        </button>
      }
    >
      {installQueryStatus === 'connected' && (
        <div className="cf-notice cf-notice--success" role="status">
          <div className="cf-notice__icon" aria-hidden="true">
            <CheckCircle2 size={18} />
          </div>
          <div>
            <p className="cf-notice__title">GitHub App connected</p>
            <p className="cf-notice__text">
              Installation verified and authorized repositories have been synchronized.
            </p>
          </div>
        </div>
      )}

      {installQueryStatus === 'pending_approval' && (
        <div className="cf-notice cf-notice--info" role="status">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertTriangle size={18} />
          </div>
          <div>
            <p className="cf-notice__title">Organization approval requested</p>
            <p className="cf-notice__text">
              A GitHub organization administrator must approve the installation request
              before repositories appear here.
            </p>
          </div>
        </div>
      )}

      {installQueryError && (
        <div className="cf-notice cf-notice--error" role="alert">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertTriangle size={18} />
          </div>
          <div>
            <p className="cf-notice__title">GitHub App connection could not be completed</p>
            <p className="cf-notice__text">
              Callback failed with status code <span className="mono">{installQueryError}</span>.
              Verify installation ownership and workspace OWNER permissions, then try again.
            </p>
          </div>
        </div>
      )}

      {actionNotice && (
        <div className="cf-notice cf-notice--success" role="status">
          <div className="cf-notice__icon" aria-hidden="true">
            <CheckCircle2 size={18} />
          </div>
          <div>
            <p className="cf-notice__title">Updated</p>
            <p className="cf-notice__text">{actionNotice}</p>
          </div>
        </div>
      )}

      {errorMessage && (
        <ErrorState
          title="GitHub repository operation failed"
          message={errorMessage}
          onRetry={() => {
            void loadData();
          }}
        />
      )}

      {!isLoading && !githubAppConfigured && (
        <div className="cf-notice cf-notice--warning" role="status">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertTriangle size={18} />
          </div>
          <div>
            <p className="cf-notice__title">GitHub App Configuration Required</p>
            <p className="cf-notice__text">
              No GitHub App credentials are configured on this server yet. To discover and
              sync live repositories, set <span className="mono">GITHUB_APP_ID</span>,{' '}
              <span className="mono">GITHUB_APP_SLUG</span>,{' '}
              <span className="mono">GITHUB_APP_PRIVATE_KEY</span> (or{' '}
              <span className="mono">GITHUB_APP_PRIVATE_KEY_PATH</span>), and{' '}
              <span className="mono">GITHUB_WEBHOOK_SECRET</span> in your ignored local{' '}
              <span className="mono">.env</span> file. Follow{' '}
              <span className="mono">docs/GITHUB_INTEGRATION.md</span>.
            </p>
          </div>
        </div>
      )}

      <DashboardCard
        title="Connected GitHub App Installations"
        subtitle={
          githubAppSlug
            ? `GitHub App slug: ${githubAppSlug} · Short-lived installation access tokens`
            : 'Workspace-scoped GitHub App installations'
        }
        icon={<Github size={17} />}
        headerAction={
          <StatusBadge
            label={`${installations.length} ${installations.length === 1 ? 'Installation' : 'Installations'}`}
            tone={installations.length > 0 ? 'healthy' : 'neutral'}
            size="sm"
          />
        }
      >
        {isLoading ? (
          <LoadingState
            title="Loading GitHub installations"
            description="Checking authorized GitHub App installations for this workspace..."
          />
        ) : installations.length === 0 ? (
          <p className="cf-muted" style={{ margin: 0 }}>
            No GitHub App installations are connected to this workspace yet.
            {isOwner && githubAppConfigured
              ? ' Click "Connect GitHub App" above to authorize repositories.'
              : ''}
          </p>
        ) : (
          <ul className="cf-subtle-list">
            {installations.map((inst) => (
              <li key={inst.id}>
                <div>
                  <div className="cf-subtle-list__title">
                    @{inst.accountLogin}{' '}
                    <span className="mono cf-muted" style={{ fontWeight: 400, fontSize: '12px' }}>
                      ({inst.accountType} · #{inst.githubInstallationId})
                    </span>
                  </div>
                  <div className="cf-subtle-list__desc">
                    Repository scope: <span className="mono">{inst.repositorySelection}</span> ·
                    Last synced:{' '}
                    <span className="mono">
                      {inst.lastSyncedAt
                        ? new Date(inst.lastSyncedAt).toLocaleString()
                        : 'Never'}
                    </span>
                  </div>
                </div>
                <div className="cf-inline-actions">
                  <StatusBadge
                    label={inst.status}
                    tone={
                      inst.status === 'ACTIVE'
                        ? 'healthy'
                        : inst.status === 'SUSPENDED'
                          ? 'degraded'
                          : 'error'
                    }
                    size="sm"
                  />
                  {isOwner && inst.status !== 'REVOKED' && (
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => void handleSync(inst)}
                      disabled={syncingId === inst.id || !githubAppConfigured}
                    >
                      <RefreshCw
                        size={12}
                        className={syncingId === inst.id ? 'cf-spin' : ''}
                        aria-hidden="true"
                      />
                      <span>{syncingId === inst.id ? 'Syncing...' : 'Sync'}</span>
                    </button>
                  )}
                  {isOwner && inst.status !== 'REVOKED' && (
                    <button
                      type="button"
                      className="btn btn-sm btn-danger"
                      onClick={() => void handleDisconnect(inst)}
                    >
                      <Unlink size={12} aria-hidden="true" />
                      <span>Disconnect</span>
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </DashboardCard>

      <DashboardCard
        title="Authorized Workspace Repositories"
        subtitle="Synchronized strictly from connected GitHub App installations — zero fabricated data"
        icon={<GitBranch size={17} />}
        headerAction={
          <StatusBadge
            label={`${totalRepos} ${totalRepos === 1 ? 'Repository' : 'Repositories'}`}
            tone="info"
            size="sm"
          />
        }
      >
        <div className="cf-toolbar">
          <input
            type="search"
            className="cf-search-input"
            placeholder="Search repositories by owner/name or description..."
            value={search}
            onChange={(e) => {
              setPage(0);
              setSearch(e.target.value);
            }}
            aria-label="Search repositories"
          />
          <div className="cf-filter-group">
            <select
              className="cf-filter-select"
              aria-label="Filter by visibility"
              value={visibilityFilter}
              onChange={(e) => {
                setPage(0);
                setVisibilityFilter(e.target.value);
              }}
            >
              <option value="">All visibility</option>
              <option value="public">Public</option>
              <option value="private">Private</option>
            </select>
            <select
              className="cf-filter-select"
              aria-label="Filter by connection status"
              value={statusFilter}
              onChange={(e) => {
                setPage(0);
                setStatusFilter(e.target.value as RepositoryConnectionStatus | '');
              }}
            >
              <option value="">All statuses</option>
              <option value="CONNECTED">Connected</option>
              <option value="SUSPENDED">Suspended</option>
              <option value="DISCONNECTED">Disconnected</option>
            </select>
          </div>
        </div>

        {isLoading ? (
          <LoadingState
            title="Loading authorized repositories"
            description="Fetching synchronized repository inventory..."
          />
        ) : (
          <>
            <DataTable
              columns={repoColumns}
              rows={repositories}
              getRowKey={(row) => row.id}
              emptyMessage={
                search || visibilityFilter || statusFilter
                  ? 'No repositories match your current filter.'
                  : 'No authorized repositories synced yet. Connect a GitHub App installation and sync to populate this list.'
              }
              caption="Authorized workspace repositories"
            />
            {totalPages > 1 && (
              <div className="cf-pagination">
                <span>
                  Page {page + 1} of {totalPages} ({totalRepos} total)
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

      <div className="cf-notice cf-notice--info">
        <div className="cf-notice__icon" aria-hidden="true">
          <ShieldAlert size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Phase 2 Least-Privilege Boundary</p>
          <p className="cf-notice__text">
            CloudForge requests read-only repository metadata through short-lived GitHub App
            tokens and verifies webhooks via HMAC-SHA256. Repository code execution, container
            builds, and deployment triggers remain disabled until Phase 3 isolated workers.
          </p>
        </div>
      </div>

      <Modal
        isOpen={selectedRepo !== null}
        title={selectedRepo?.fullName ?? 'Repository Details'}
        subtitle="Synchronized GitHub repository metadata"
        onClose={() => setSelectedRepo(null)}
      >
        {selectedRepo && (
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Owner</dt>
              <dd className="mono">{selectedRepo.ownerLogin}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Repository Name</dt>
              <dd className="mono">{selectedRepo.name}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Default Branch</dt>
              <dd className="mono">{selectedRepo.defaultBranch}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Visibility</dt>
              <dd className="mono">{selectedRepo.visibility}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Connection Status</dt>
              <dd className="mono">{selectedRepo.connectionStatus}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Archived</dt>
              <dd>{selectedRepo.archived ? 'Yes' : 'No'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Last GitHub Update</dt>
              <dd className="mono">
                {selectedRepo.githubUpdatedAt
                  ? new Date(selectedRepo.githubUpdatedAt).toLocaleString()
                  : '—'}
              </dd>
            </div>
            <div className="cf-kv-item">
              <dt>Last Synced</dt>
              <dd className="mono">
                {new Date(selectedRepo.lastSyncedAt).toLocaleString()}
              </dd>
            </div>
          </dl>
        )}
      </Modal>

      <Modal
        isOpen={linkingRepo !== null}
        title="Link Repository to Project"
        subtitle={
          linkingRepo
            ? `Associate ${linkingRepo.fullName} with a project in your active workspace.`
            : undefined
        }
        onClose={() => setLinkingRepo(null)}
      >
        <form className="cf-form" onSubmit={(e) => void handleLinkSubmit(e)}>
          {linkError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertTriangle size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Could not link repository</p>
                <p className="cf-notice__text">{linkError}</p>
              </div>
            </div>
          )}

          <div className="cf-inline-actions">
            <button
              type="button"
              className={`btn btn-sm ${linkMode === 'existing' ? 'btn-primary' : ''}`}
              disabled={workspaceProjects.length === 0}
              onClick={() => setLinkMode('existing')}
            >
              <Link2 size={13} aria-hidden="true" />
              <span>Existing Project</span>
            </button>
            <button
              type="button"
              className={`btn btn-sm ${linkMode === 'new' ? 'btn-primary' : ''}`}
              onClick={() => setLinkMode('new')}
            >
              <FolderPlus size={13} aria-hidden="true" />
              <span>Create New Project</span>
            </button>
          </div>

          {linkMode === 'existing' ? (
            <div className="cf-field">
              <label htmlFor="cf-link-project-select" className="cf-field__label">
                Select Workspace Project
              </label>
              <select
                id="cf-link-project-select"
                className="cf-select"
                value={selectedProjectId}
                onChange={(e) => setSelectedProjectId(e.target.value)}
              >
                {workspaceProjects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.slug})
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div className="cf-field">
              <label htmlFor="cf-link-new-project-name" className="cf-field__label">
                New Project Name *
              </label>
              <input
                id="cf-link-new-project-name"
                type="text"
                className="cf-input"
                required
                minLength={2}
                value={newProjectName}
                onChange={(e) => setNewProjectName(e.target.value)}
              />
            </div>
          )}

          <div className="cf-inline-actions cf-inline-actions--end">
            <button
              type="button"
              className="btn"
              onClick={() => setLinkingRepo(null)}
              disabled={isLinking}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isLinking}>
              {isLinking ? 'Linking...' : 'Confirm Link'}
            </button>
          </div>
        </form>
      </Modal>
    </PageContainer>
  );
}
