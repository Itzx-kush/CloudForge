import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  ChevronRight,
  Clock,
  CloudDownload,
  ExternalLink,
  Filter,
  GitBranch,
  GitCommitHorizontal,
  History,
  Info,
  Layers,
  RefreshCw,
  ShieldAlert,
  Workflow,
  X,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { Modal } from '../components/Modal';
import { PageContainer } from '../components/PageContainer';
import { SearchField } from '../components/SearchField';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';
import { Toast } from '../components/Toast';
import { useAuth } from '../context/AuthContext';
import {
  ApiClientError,
  fetchPipelineOverview,
  fetchRepositoryWorkflowRuns,
  fetchRepositoryWorkflows,
  fetchWorkflowRunJobs,
  fetchWorkspaceWorkflowRuns,
  syncRepositoryWorkflows,
} from '../services/api';
import type {
  GitHubWorkflow,
  GitHubWorkflowJob,
  GitHubWorkflowRun,
  PipelineOverview,
  PipelineRepositorySummary,
  WorkflowRunNormalizedStatus,
} from '../types/platform';
import './Pages.css';
import './PipelinesPage.css';

type ActionErrorCategory =
  | 'permission'
  | 'rate_limit'
  | 'installation'
  | 'not_found'
  | 'backend';

interface ClassifiedActionError {
  category: ActionErrorCategory;
  title: string;
  message: string;
  retryAfterSeconds: number | null;
}

const ALL_REPOSITORIES_VALUE = '__all__';

const STATUS_META: Record<
  WorkflowRunNormalizedStatus,
  { label: string; tone: BadgeTone; pulse?: boolean }
> = {
  QUEUED: { label: 'Queued', tone: 'degraded', pulse: true },
  IN_PROGRESS: { label: 'In progress', tone: 'info', pulse: true },
  SUCCESS: { label: 'Succeeded', tone: 'healthy' },
  FAILURE: { label: 'Failed', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'degraded' },
  TIMED_OUT: { label: 'Timed out', tone: 'error' },
  SKIPPED: { label: 'Skipped', tone: 'neutral' },
  UNKNOWN: { label: 'Unknown', tone: 'neutral' },
};

const WORKFLOW_STATE_TONES: Record<string, BadgeTone> = {
  active: 'healthy',
  disabled_manually: 'degraded',
  disabled_inactivity: 'degraded',
  disabled_fork: 'degraded',
  deleted: 'neutral',
};

function classifyError(error: unknown, fallbackTitle: string): ClassifiedActionError {
  if (error instanceof ApiClientError) {
    const lower = error.message.toLowerCase();
    if (
      error.status === 403 &&
      (lower.includes('actions: read') || lower.includes('actions (read'))
    ) {
      return {
        category: 'permission',
        title: 'GitHub App Missing Actions: Read Permission',
        message: error.message,
        retryAfterSeconds: null,
      };
    }
    if (error.status === 429 || lower.includes('rate limit')) {
      return {
        category: 'rate_limit',
        title: 'GitHub API Rate Limit Reached',
        message: error.message,
        retryAfterSeconds: error.retryAfterSeconds,
      };
    }
    if (
      error.status === 409 &&
      (lower.includes('revoked') || lower.includes('suspended') || lower.includes('disconnected'))
    ) {
      return {
        category: 'installation',
        title: 'GitHub App Installation Requires Attention',
        message: error.message,
        retryAfterSeconds: null,
      };
    }
    if (error.status === 404) {
      return {
        category: 'not_found',
        title: 'Repository or Run Not Found on GitHub',
        message: error.message,
        retryAfterSeconds: null,
      };
    }
    return {
      category: 'backend',
      title: fallbackTitle,
      message: error.message,
      retryAfterSeconds: error.retryAfterSeconds,
    };
  }

  return {
    category: 'backend',
    title: fallbackTitle,
    message:
      error instanceof Error
        ? error.message
        : 'Unable to communicate with the CloudForge backend API.',
    retryAfterSeconds: null,
  };
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString();
}

function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0) return '—';
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  const remSec = seconds % 60;
  if (mins < 60) return remSec > 0 ? `${mins}m ${remSec}s` : `${mins}m`;
  const hrs = Math.floor(mins / 60);
  const remMin = mins % 60;
  return `${hrs}h ${remMin}m`;
}

function formatWorkflowState(state: string): string {
  return state.replace(/_/g, ' ');
}

function renderRunStatusBadge(
  status: WorkflowRunNormalizedStatus,
  conclusion?: string | null
) {
  const meta = STATUS_META[status] ?? STATUS_META.UNKNOWN;
  const label =
    status === 'UNKNOWN' && conclusion
      ? `Unknown (${conclusion.replace(/_/g, ' ')})`
      : meta.label;
  return (
    <StatusBadge
      label={label}
      tone={meta.tone}
      pulse={Boolean(meta.pulse)}
      size="sm"
    />
  );
}

export function PipelinesPage() {
  const { activeWorkspace, activeRole } = useAuth();
  const canSyncFromGitHub = activeRole === 'OWNER' || activeRole === 'DEVELOPER';

  const [overview, setOverview] = useState<PipelineOverview | null>(null);
  const [selectedRepoId, setSelectedRepoId] = useState<string>(ALL_REPOSITORIES_VALUE);
  const [repoFilterQuery, setRepoFilterQuery] = useState('');

  // Workflows state (when a specific repository is selected)
  const [workflows, setWorkflows] = useState<GitHubWorkflow[]>([]);
  const [totalWorkflows, setTotalWorkflows] = useState(0);
  const [workflowSearch, setWorkflowSearch] = useState('');
  const [workflowStateFilter, setWorkflowStateFilter] = useState('');
  const [workflowPage, setWorkflowPage] = useState(0);
  const [workflowTotalPages, setWorkflowTotalPages] = useState(0);

  // Workflow runs state
  const [runs, setRuns] = useState<GitHubWorkflowRun[]>([]);
  const [totalRuns, setTotalRuns] = useState(0);
  const [runSearch, setRunSearch] = useState('');
  const [runStatusFilter, setRunStatusFilter] = useState<WorkflowRunNormalizedStatus | ''>('');
  const [selectedWorkflowFilter, setSelectedWorkflowFilter] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [runPage, setRunPage] = useState(0);
  const [runTotalPages, setRunTotalPages] = useState(0);

  // Loading & feedback states
  const [isInitialLoading, setIsInitialLoading] = useState(true);
  const [isRefreshingLocal, setIsRefreshingLocal] = useState(false);
  const [isSyncingGitHub, setIsSyncingGitHub] = useState(false);
  const [lastLocalRefreshAt, setLastLocalRefreshAt] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<ClassifiedActionError | null>(null);
  const [syncError, setSyncError] = useState<ClassifiedActionError | null>(null);
  const [toast, setToast] = useState<{
    message: string;
    tone: 'success' | 'info' | 'error';
  } | null>(null);

  // Run Details Modal & Jobs state
  const [selectedRun, setSelectedRun] = useState<GitHubWorkflowRun | null>(null);
  const [runJobs, setRunJobs] = useState<GitHubWorkflowJob[]>([]);
  const [jobsFetchedAt, setJobsFetchedAt] = useState<string | null>(null);
  const [isLoadingJobs, setIsLoadingJobs] = useState(false);
  const [jobsError, setJobsError] = useState<ClassifiedActionError | null>(null);
  const [expandedJobIds, setExpandedJobIds] = useState<Record<number, boolean>>({});

  const initializedWorkspaceRef = useRef<string | null>(null);

  const repositories = useMemo<PipelineRepositorySummary[]>(
    () => overview?.repositories ?? [],
    [overview]
  );

  const filteredRepositories = useMemo(() => {
    const q = repoFilterQuery.trim().toLowerCase();
    if (!q) return repositories;
    return repositories.filter(
      (repo) =>
        repo.fullName.toLowerCase().includes(q) ||
        repo.name.toLowerCase().includes(q) ||
        repo.ownerLogin.toLowerCase().includes(q)
    );
  }, [repositories, repoFilterQuery]);

  const selectedRepo = useMemo(
    () => repositories.find((repo) => repo.repositoryId === selectedRepoId) ?? null,
    [repositories, selectedRepoId]
  );

  const loadOverview = useCallback(async (): Promise<PipelineOverview | null> => {
    if (!activeWorkspace) {
      setOverview(null);
      return null;
    }
    const data = await fetchPipelineOverview(activeWorkspace.id);
    const normalized: PipelineOverview = {
      workspaceId: data.workspaceId ?? activeWorkspace.id,
      githubAppConfigured: Boolean(data.githubAppConfigured),
      connectedRepositoryCount: data.connectedRepositoryCount ?? 0,
      repositoriesWithWorkflowsCount: data.repositoriesWithWorkflowsCount ?? 0,
      workflowCount: data.workflowCount ?? 0,
      recentRunCount: data.recentRunCount ?? 0,
      activeRunCount: data.activeRunCount ?? 0,
      failedRunCount: data.failedRunCount ?? 0,
      succeededRunCount: data.succeededRunCount ?? 0,
      lastSyncedAt: data.lastSyncedAt ?? null,
      repositories: Array.isArray(data.repositories) ? data.repositories : [],
    };
    setOverview(normalized);

    // Choose a helpful default repository once per workspace load.
    if (initializedWorkspaceRef.current !== activeWorkspace.id) {
      initializedWorkspaceRef.current = activeWorkspace.id;
      const preferred =
        normalized.repositories.find((r) => r.workflowCount > 0) ??
        normalized.repositories.find((r) => r.connectionStatus === 'CONNECTED') ??
        normalized.repositories[0];
      setSelectedRepoId(preferred ? preferred.repositoryId : ALL_REPOSITORIES_VALUE);
      setSelectedWorkflowFilter(null);
      setWorkflowPage(0);
      setRunPage(0);
    } else if (
      selectedRepoId !== ALL_REPOSITORIES_VALUE &&
      !normalized.repositories.some((r) => r.repositoryId === selectedRepoId)
    ) {
      setSelectedRepoId(ALL_REPOSITORIES_VALUE);
      setSelectedWorkflowFilter(null);
    }

    return normalized;
  }, [activeWorkspace, selectedRepoId]);

  const loadStoredPipelineData = useCallback(
    async (repoIdOverride?: string) => {
      if (!activeWorkspace) {
        setWorkflows([]);
        setRuns([]);
        setIsInitialLoading(false);
        setIsRefreshingLocal(false);
        return;
      }

      const targetRepoId = repoIdOverride ?? selectedRepoId;
      const isSpecificRepo =
        targetRepoId !== '' && targetRepoId !== ALL_REPOSITORIES_VALUE;

      try {
        const workflowsPromise = isSpecificRepo
          ? fetchRepositoryWorkflows(activeWorkspace.id, targetRepoId, {
              q: workflowSearch,
              state: workflowStateFilter,
              page: workflowPage,
              size: 15,
            })
          : Promise.resolve(null);

        const runsPromise = isSpecificRepo
          ? fetchRepositoryWorkflowRuns(activeWorkspace.id, targetRepoId, {
              workflowId: selectedWorkflowFilter?.id,
              status: runStatusFilter,
              q: runSearch,
              page: runPage,
              size: 15,
            })
          : fetchWorkspaceWorkflowRuns(activeWorkspace.id, {
              status: runStatusFilter,
              q: runSearch,
              page: runPage,
              size: 15,
            });

        const [wfRes, runRes] = await Promise.all([workflowsPromise, runsPromise]);

        if (wfRes) {
          setWorkflows(Array.isArray(wfRes.items) ? wfRes.items : []);
          setTotalWorkflows(wfRes.totalElements ?? 0);
          setWorkflowTotalPages(wfRes.totalPages ?? 0);
        } else {
          setWorkflows([]);
          setTotalWorkflows(0);
          setWorkflowTotalPages(0);
        }

        setRuns(Array.isArray(runRes.items) ? runRes.items : []);
        setTotalRuns(runRes.totalElements ?? 0);
        setRunTotalPages(runRes.totalPages ?? 0);
        setLastLocalRefreshAt(new Date().toISOString());
        setLoadError(null);
      } catch (error) {
        setLoadError(classifyError(error, 'Failed to load pipeline data'));
      }
    },
    [
      activeWorkspace,
      runPage,
      runSearch,
      runStatusFilter,
      selectedRepoId,
      selectedWorkflowFilter,
      workflowPage,
      workflowSearch,
      workflowStateFilter,
    ]
  );

  // Initial workspace load
  useEffect(() => {
    let active = true;
    setIsInitialLoading(true);
    setLoadError(null);
    setSyncError(null);

    void (async () => {
      try {
        const loadedOverview = await loadOverview();
        if (!active) return;
        const preferred =
          loadedOverview?.repositories.find((r) => r.workflowCount > 0) ??
          loadedOverview?.repositories.find((r) => r.connectionStatus === 'CONNECTED') ??
          loadedOverview?.repositories[0];
        const initialRepoId = preferred ? preferred.repositoryId : ALL_REPOSITORIES_VALUE;
        await loadStoredPipelineData(initialRepoId);
      } catch (error) {
        if (active) {
          setLoadError(classifyError(error, 'Unable to load Pipelines dashboard'));
        }
      } finally {
        if (active) {
          setIsInitialLoading(false);
        }
      }
    })();

    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeWorkspace?.id]);

  const hasOverview = overview !== null;

  // Reload stored workflows & runs whenever selection/filters/pagination change
  useEffect(() => {
    if (isInitialLoading || !activeWorkspace || !hasOverview) return;
    let active = true;
    setIsRefreshingLocal(true);

    void loadStoredPipelineData().finally(() => {
      if (active) {
        setIsRefreshingLocal(false);
      }
    });

    return () => {
      active = false;
    };
  }, [
    activeWorkspace,
    hasOverview,
    isInitialLoading,
    loadStoredPipelineData,
  ]);

  const handleRefreshLocalData = async () => {
    if (!activeWorkspace || isRefreshingLocal) return;
    setIsRefreshingLocal(true);
    setLoadError(null);
    try {
      await loadOverview();
      await loadStoredPipelineData();
      setToast({
        message: 'Refreshed locally stored pipeline data from CloudForge.',
        tone: 'info',
      });
    } catch (error) {
      setLoadError(classifyError(error, 'Failed to refresh stored pipeline data'));
    } finally {
      setIsRefreshingLocal(false);
    }
  };

  const handleSyncFromGitHub = async (repositoryToSync?: PipelineRepositorySummary | null) => {
    const target = repositoryToSync ?? selectedRepo;
    if (!activeWorkspace || !target || isSyncingGitHub) return;

    setIsSyncingGitHub(true);
    setSyncError(null);
    try {
      const result = await syncRepositoryWorkflows(activeWorkspace.id, target.repositoryId);
      setToast({
        message: result.message,
        tone: 'success',
      });
      await loadOverview();
      await loadStoredPipelineData(target.repositoryId);
    } catch (error) {
      const classified = classifyError(
        error,
        `GitHub Actions synchronization failed for ${target.fullName}`
      );
      setSyncError(classified);
      // Reload overview in case a revoked/suspended/missing state updated PostgreSQL
      if (classified.category === 'installation' || classified.category === 'not_found') {
        void loadOverview();
      }
    } finally {
      setIsSyncingGitHub(false);
    }
  };

  const loadJobsForRun = useCallback(
    async (run: GitHubWorkflowRun) => {
      if (!activeWorkspace) return;
      setIsLoadingJobs(true);
      setJobsError(null);
      try {
        const response = await fetchWorkflowRunJobs(activeWorkspace.id, run.id);
        const jobs = Array.isArray(response.jobs) ? response.jobs : [];
        setRunJobs(jobs);
        setJobsFetchedAt(response.fetchedAt ?? new Date().toISOString());
        // Auto-expand the first failed or active job, or first job if only one exists
        const initialExpanded: Record<number, boolean> = {};
        const focusJob =
          jobs.find((j) => j.normalizedStatus === 'FAILURE' || j.normalizedStatus === 'TIMED_OUT') ??
          jobs.find((j) => j.normalizedStatus === 'IN_PROGRESS') ??
          (jobs.length === 1 ? jobs[0] : undefined);
        if (focusJob) {
          initialExpanded[focusJob.githubJobId] = true;
        }
        setExpandedJobIds(initialExpanded);
      } catch (error) {
        setRunJobs([]);
        setJobsError(classifyError(error, 'Unable to load workflow run jobs from GitHub'));
      } finally {
        setIsLoadingJobs(false);
      }
    },
    [activeWorkspace]
  );

  const openRunDetails = (run: GitHubWorkflowRun) => {
    setSelectedRun(run);
    setRunJobs([]);
    setJobsFetchedAt(null);
    setJobsError(null);
    setExpandedJobIds({});
    void loadJobsForRun(run);
  };

  const toggleJobExpanded = (jobId: number) => {
    setExpandedJobIds((prev) => ({ ...prev, [jobId]: !prev[jobId] }));
  };

  const handleSelectRepository = (nextRepoId: string) => {
    setSelectedRepoId(nextRepoId);
    setSelectedWorkflowFilter(null);
    setWorkflowPage(0);
    setRunPage(0);
    setSyncError(null);
  };

  const canSyncSelectedRepo =
    canSyncFromGitHub &&
    Boolean(overview?.githubAppConfigured) &&
    selectedRepo !== null &&
    selectedRepo.connectionStatus === 'CONNECTED' &&
    selectedRepo.installationStatus === 'ACTIVE';

  const syncButtonTitle = !canSyncFromGitHub
    ? 'Only workspace OWNERs and DEVELOPERs can synchronize workflows from GitHub'
    : !overview?.githubAppConfigured
      ? 'Configure GitHub App environment variables first (see docs/GITHUB_INTEGRATION.md)'
      : !selectedRepo
        ? 'Select a specific connected repository to synchronize from GitHub'
        : selectedRepo.installationStatus !== 'ACTIVE' || selectedRepo.connectionStatus !== 'CONNECTED'
          ? 'Repository installation must be ACTIVE and CONNECTED to synchronize from GitHub'
          : `Fetch latest GitHub Actions workflows and runs for ${selectedRepo.fullName}`;

  const workflowColumns: TableColumn<GitHubWorkflow>[] = [
    {
      key: 'name',
      header: 'Workflow',
      render: (row) => (
        <div>
          <button
            type="button"
            className="cf-pipelines-link-btn"
            onClick={() => {
              setSelectedWorkflowFilter((current) =>
                current?.id === row.id ? null : { id: row.id, name: row.name }
              );
              setRunPage(0);
            }}
          >
            <span>{row.name}</span>
          </button>
          <span className="cf-pipelines-subtext mono">{row.path}</span>
        </div>
      ),
    },
    {
      key: 'repository',
      header: 'Repository',
      render: (row) => <span className="mono">{row.repositoryFullName}</span>,
    },
    {
      key: 'state',
      header: 'State',
      render: (row) => (
        <StatusBadge
          label={formatWorkflowState(row.state)}
          tone={WORKFLOW_STATE_TONES[row.state.toLowerCase()] ?? 'neutral'}
          size="sm"
        />
      ),
    },
    {
      key: 'latestRun',
      header: 'Recent Execution',
      render: (row) =>
        row.latestRunStatus ? (
          <div className="cf-inline-actions">
            {renderRunStatusBadge(row.latestRunStatus, row.latestRunConclusion)}
            {row.latestRunNumber !== null && (
              <span className="mono cf-muted" style={{ fontSize: '12px' }}>
                #{row.latestRunNumber}
              </span>
            )}
          </div>
        ) : (
          <StatusBadge label="No runs yet" tone="neutral" size="sm" />
        ),
    },
    {
      key: 'lastRunAt',
      header: 'Last Execution',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {formatTimestamp(row.latestRunAt)}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => {
        const isFiltered = selectedWorkflowFilter?.id === row.id;
        return (
          <div className="cf-inline-actions cf-inline-actions--end">
            <button
              type="button"
              className={`btn btn-sm ${isFiltered ? 'btn-primary' : ''}`}
              onClick={() => {
                setSelectedWorkflowFilter(isFiltered ? null : { id: row.id, name: row.name });
                setRunPage(0);
              }}
            >
              <Filter size={12} aria-hidden="true" />
              <span>{isFiltered ? 'Filtered' : 'Filter runs'}</span>
            </button>
            {row.htmlUrl && (
              <a
                href={row.htmlUrl}
                target="_blank"
                rel="noreferrer noopener"
                className="btn btn-sm"
                aria-label={`Open ${row.name} workflow on GitHub`}
              >
                <ExternalLink size={12} aria-hidden="true" />
                <span>GitHub</span>
              </a>
            )}
          </div>
        );
      },
    },
  ];

  const runColumns: TableColumn<GitHubWorkflowRun>[] = [
    {
      key: 'workflow',
      header: 'Workflow Run',
      render: (row) => (
        <div>
          <button
            type="button"
            className="cf-pipelines-link-btn"
            onClick={() => openRunDetails(row)}
          >
            <span>
              {row.workflowName} <span className="mono">#{row.runNumber}</span>
              {row.runAttempt > 1 && (
                <span className="cf-attempt-pill">Attempt #{row.runAttempt}</span>
              )}
            </span>
          </button>
          <span className="cf-pipelines-subtext">
            {row.displayTitle || row.repositoryFullName}
            {selectedRepoId === ALL_REPOSITORIES_VALUE ? ` · ${row.repositoryFullName}` : ''}
          </span>
        </div>
      ),
    },
    {
      key: 'branchSha',
      header: 'Branch & Commit',
      render: (row) => (
        <div className="cf-run-sha-branch">
          <span className="cf-run-branch">
            <GitBranch size={12} aria-hidden="true" />
            <span>{row.headBranch ?? '—'}</span>
          </span>
          {row.headSha && (
            <span className="cf-run-sha" title={row.headSha}>
              <GitCommitHorizontal size={12} aria-hidden="true" />
              <span>{row.headSha.slice(0, 7)}</span>
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'event',
      header: 'Trigger',
      render: (row) => <span className="cf-event-pill">{row.event}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <div>
          {renderRunStatusBadge(row.normalizedStatus, row.githubConclusion)}
          {row.githubConclusion && row.normalizedStatus !== 'UNKNOWN' && (
            <span className="cf-pipelines-subtext mono" style={{ marginTop: '3px' }}>
              {row.githubStatus} · {row.githubConclusion}
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'timing',
      header: 'Started & Duration',
      render: (row) => (
        <div>
          <div className="mono" style={{ fontSize: '12px' }}>
            {formatTimestamp(row.runStartedAt ?? row.githubCreatedAt)}
          </div>
          <span className="cf-pipelines-subtext mono">
            {row.durationSeconds !== null ? `Duration: ${formatDuration(row.durationSeconds)}` : 'Duration: —'}
          </span>
        </div>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <div className="cf-inline-actions cf-inline-actions--end">
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => openRunDetails(row)}
          >
            <Layers size={12} aria-hidden="true" />
            <span>Inspect</span>
          </button>
          {row.htmlUrl && (
            <a
              href={row.htmlUrl}
              target="_blank"
              rel="noreferrer noopener"
              className="btn btn-sm"
              aria-label={`Open run #${row.runNumber} on GitHub`}
            >
              <ExternalLink size={12} aria-hidden="true" />
            </a>
          )}
        </div>
      ),
    },
  ];

  const renderClassifiedNotice = (err: ClassifiedActionError, onDismiss?: () => void) => {
    if (err.category === 'permission') {
      return (
        <div className="cf-notice cf-notice--warning" role="alert">
          <div className="cf-notice__icon" aria-hidden="true">
            <ShieldAlert size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="cf-notice__title">{err.title}</p>
            <p className="cf-notice__text">{err.message}</p>
            <p className="cf-notice__text">
              Next step: In GitHub App settings under <strong>Repository permissions</strong>, set{' '}
              <span className="mono">Actions</span> to <strong>Read-only</strong> (keep{' '}
              <span className="mono">Metadata: Read-only</span>), then approve the permission update on
              the GitHub installation before syncing again.
            </p>
            <div className="cf-inline-actions" style={{ marginTop: '10px' }}>
              <Link to="/repositories" className="btn btn-sm">
                Review GitHub Installations
              </Link>
              {onDismiss && (
                <button type="button" className="btn btn-sm btn-ghost" onClick={onDismiss}>
                  Dismiss
                </button>
              )}
            </div>
          </div>
        </div>
      );
    }

    if (err.category === 'rate_limit') {
      return (
        <div className="cf-notice cf-notice--warning" role="alert">
          <div className="cf-notice__icon" aria-hidden="true">
            <Clock size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="cf-notice__title">{err.title}</p>
            <p className="cf-notice__text">
              {err.message}
              {err.retryAfterSeconds
                ? ` GitHub requested waiting ${err.retryAfterSeconds}s before retrying.`
                : ''}
            </p>
            {onDismiss && (
              <div className="cf-inline-actions" style={{ marginTop: '8px' }}>
                <button type="button" className="btn btn-sm btn-ghost" onClick={onDismiss}>
                  Dismiss
                </button>
              </div>
            )}
          </div>
        </div>
      );
    }

    if (err.category === 'installation') {
      return (
        <div className="cf-notice cf-notice--error" role="alert">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertTriangle size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="cf-notice__title">{err.title}</p>
            <p className="cf-notice__text">{err.message}</p>
            <div className="cf-inline-actions" style={{ marginTop: '10px' }}>
              <Link to="/repositories" className="btn btn-sm btn-primary">
                Reconnect on Repositories
              </Link>
              {onDismiss && (
                <button type="button" className="btn btn-sm btn-ghost" onClick={onDismiss}>
                  Dismiss
                </button>
              )}
            </div>
          </div>
        </div>
      );
    }

    return (
      <ErrorState
        title={err.title}
        message={err.message}
        onRetry={
          err.category === 'backend'
            ? () => {
                void handleRefreshLocalData();
              }
            : onDismiss
        }
      />
    );
  };

  return (
    <PageContainer
      title="Pipelines"
      description="Read-only GitHub Actions workflow discovery, execution history, and live job inspection for authorized workspace repositories."
      phaseBadge={{
        label: overview?.githubAppConfigured
          ? 'Phase 3.1 · GitHub Actions Read-Only'
          : 'GitHub App Setup Required',
        tone: overview?.githubAppConfigured ? 'healthy' : 'degraded',
      }}
      actions={
        <div className="cf-inline-actions">
          <button
            type="button"
            className="btn"
            onClick={() => void handleRefreshLocalData()}
            disabled={isInitialLoading || isRefreshingLocal}
            title="Reload stored workflow and run records from CloudForge PostgreSQL"
          >
            <RefreshCw
              size={14}
              className={isRefreshingLocal ? 'cf-spin' : ''}
              aria-hidden="true"
            />
            <span>{isRefreshingLocal ? 'Refreshing...' : 'Refresh Stored Data'}</span>
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void handleSyncFromGitHub()}
            disabled={!canSyncSelectedRepo || isSyncingGitHub}
            title={syncButtonTitle}
          >
            <CloudDownload
              size={15}
              className={isSyncingGitHub ? 'cf-spin' : ''}
              aria-hidden="true"
            />
            <span>{isSyncingGitHub ? 'Syncing GitHub...' : 'Sync from GitHub'}</span>
          </button>
        </div>
      }
    >
      {toast && (
        <Toast
          message={toast.message}
          tone={toast.tone}
          onDismiss={() => setToast(null)}
        />
      )}

      {/* Clear distinction between local refresh and remote GitHub synchronization */}
      <div className="cf-pipelines-meta-bar" role="status">
        <div className="cf-pipelines-meta-bar__group">
          <span className="cf-pipelines-meta-bar__item">
            <CloudDownload size={14} aria-hidden="true" />
            <span>
              Last GitHub sync:{' '}
              <strong className="mono">
                {formatTimestamp(selectedRepo?.lastWorkflowSyncAt ?? overview?.lastSyncedAt)}
              </strong>
            </span>
          </span>
          <span className="cf-pipelines-meta-bar__item">
            <History size={14} aria-hidden="true" />
            <span>
              Stored view refreshed:{' '}
              <strong className="mono">{formatTimestamp(lastLocalRefreshAt)}</strong>
            </span>
          </span>
        </div>
        <span className="cf-muted" style={{ fontSize: '12px' }}>
          Refresh reads CloudForge storage · Sync fetches fresh telemetry from GitHub Actions
        </span>
      </div>

      {loadError && renderClassifiedNotice(loadError, () => setLoadError(null))}
      {syncError && renderClassifiedNotice(syncError, () => setSyncError(null))}

      {!isInitialLoading && overview && !overview.githubAppConfigured && (
        <div className="cf-notice cf-notice--warning" role="status">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertTriangle size={18} />
          </div>
          <div>
            <p className="cf-notice__title">GitHub App Configuration Required for Live Sync</p>
            <p className="cf-notice__text">
              Configure <span className="mono">GITHUB_APP_ID</span>,{' '}
              <span className="mono">GITHUB_APP_SLUG</span>, and{' '}
              <span className="mono">GITHUB_APP_PRIVATE_KEY</span> with{' '}
              <span className="mono">Metadata: Read</span> and{' '}
              <span className="mono">Actions: Read</span> permissions to synchronize GitHub Actions
              workflows. Stored pipeline records remain visible below.
            </p>
          </div>
        </div>
      )}

      {/* A. Pipeline Overview Telemetry Strip */}
      <section
        aria-label="Pipeline overview summary"
        className="cf-grid cf-grid--4 cf-pipelines-stats"
      >
        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Repositories w/ Workflows</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <GitBranch size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="pipelines-repo-count">
            {isInitialLoading ? '—' : (overview?.repositoriesWithWorkflowsCount ?? 0)}
          </div>
          <div className="cf-stat-tile__meta">
            {isInitialLoading
              ? 'Loading workspace inventory...'
              : `${overview?.connectedRepositoryCount ?? 0} connected repositor${
                  (overview?.connectedRepositoryCount ?? 0) === 1 ? 'y' : 'ies'
                }`}
          </div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Discovered Workflows</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Workflow size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="pipelines-workflow-count">
            {isInitialLoading ? '—' : (overview?.workflowCount ?? 0)}
          </div>
          <div className="cf-stat-tile__meta">Synced from .github/workflows</div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Recent Runs</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Activity size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="pipelines-run-count">
            {isInitialLoading ? '—' : (overview?.recentRunCount ?? 0)}
          </div>
          <div className="cf-stat-tile__meta">
            {isInitialLoading
              ? 'Loading run history...'
              : `${overview?.succeededRunCount ?? 0} succeeded`}
          </div>
        </div>

        <div
          className={`cf-stat-tile ${
            (overview?.failedRunCount ?? 0) > 0
              ? 'cf-stat-tile--alert'
              : (overview?.activeRunCount ?? 0) > 0
                ? 'cf-stat-tile--active'
                : ''
          }`}
        >
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Active / Failed Runs</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <AlertTriangle size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__split">
            <span className="cf-stat-tile__value" data-testid="pipelines-active-count">
              {isInitialLoading ? '—' : (overview?.activeRunCount ?? 0)}
            </span>
            <span className="cf-muted" style={{ fontSize: '12.5px' }}>
              active
            </span>
            <span className="cf-muted">·</span>
            <span
              className="cf-stat-tile__value"
              data-testid="pipelines-failed-count"
              style={
                (overview?.failedRunCount ?? 0) > 0
                  ? { color: 'var(--status-error-fg)' }
                  : undefined
              }
            >
              {isInitialLoading ? '—' : (overview?.failedRunCount ?? 0)}
            </span>
            <span className="cf-muted" style={{ fontSize: '12.5px' }}>
              failed
            </span>
          </div>
          <div className="cf-stat-tile__meta">Queued, in-progress, failed &amp; timed out</div>
        </div>
      </section>

      {isInitialLoading ? (
        <DashboardCard
          title="Loading Pipelines"
          subtitle="Retrieving authorized repositories and stored GitHub Actions telemetry..."
          icon={<Workflow size={17} />}
        >
          <LoadingState
            title="Loading GitHub Actions dashboard"
            description="Checking authorized repositories, workflows, and recent executions..."
          />
        </DashboardCard>
      ) : repositories.length === 0 ? (
        <DashboardCard
          title="No Connected Repositories"
          subtitle="Connect a GitHub App installation before discovering GitHub Actions workflows"
          icon={<GitBranch size={17} />}
        >
          <div className="cf-empty-panel">
            <h3 className="cf-empty-panel__title">Connect a repository to get started</h3>
            <p className="cf-empty-panel__desc">
              CloudForge discovers GitHub Actions workflows exclusively from repositories authorized
              through your workspace&apos;s GitHub App installation. Connect and synchronize your
              repositories first.
            </p>
            <Link to="/repositories" className="btn btn-primary">
              Go to Repositories
            </Link>
          </div>
        </DashboardCard>
      ) : (
        <>
          {/* B. Repository Selection */}
          <DashboardCard
            title="Repository Scope"
            subtitle="Select an authorized workspace repository to inspect workflows or synchronize fresh runs from GitHub"
            icon={<GitBranch size={17} />}
            headerAction={
              <StatusBadge
                label={`${repositories.length} Authorized ${
                  repositories.length === 1 ? 'Repo' : 'Repos'
                }`}
                tone="info"
                size="sm"
              />
            }
          >
            <div className="cf-repo-selector">
              <div className="cf-repo-selector__controls">
                {repositories.length > 3 && (
                  <SearchField
                    label="Filter repository list"
                    placeholder="Filter repositories by owner/name..."
                    value={repoFilterQuery}
                    onChange={setRepoFilterQuery}
                  />
                )}
                <div className="cf-repo-selector__select-wrap">
                  <label htmlFor="cf-pipeline-repo-select" className="sr-only">
                    Select repository
                  </label>
                  <select
                    id="cf-pipeline-repo-select"
                    aria-label="Select repository"
                    className="cf-repo-selector__select"
                    value={selectedRepoId}
                    onChange={(e) => handleSelectRepository(e.target.value)}
                  >
                    <option value={ALL_REPOSITORIES_VALUE}>
                      All workspace repositories ({repositories.length})
                    </option>
                    {filteredRepositories.map((repo) => (
                      <option key={repo.repositoryId} value={repo.repositoryId}>
                        {repo.fullName} — {repo.workflowCount}{' '}
                        {repo.workflowCount === 1 ? 'workflow' : 'workflows'} ({repo.connectionStatus})
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {selectedRepo && (
                <div className="cf-repo-summary-strip">
                  <div className="cf-repo-summary-strip__info">
                    <div className="cf-repo-summary-strip__title">
                      <span className="mono">{selectedRepo.fullName}</span>
                      <StatusBadge
                        label={selectedRepo.connectionStatus}
                        tone={
                          selectedRepo.connectionStatus === 'CONNECTED'
                            ? 'healthy'
                            : selectedRepo.connectionStatus === 'SUSPENDED'
                              ? 'degraded'
                              : 'error'
                        }
                        size="sm"
                      />
                      {selectedRepo.installationStatus !== 'ACTIVE' && (
                        <StatusBadge
                          label={`Installation ${selectedRepo.installationStatus}`}
                          tone="error"
                          size="sm"
                        />
                      )}
                    </div>
                    <div className="cf-repo-summary-strip__details">
                      <span>
                        Default branch:{' '}
                        <span className="mono">{selectedRepo.defaultBranch}</span>
                      </span>
                      <span>
                        Workflows: <strong className="mono">{selectedRepo.workflowCount}</strong>
                      </span>
                      <span>
                        Stored runs: <strong className="mono">{selectedRepo.runCount}</strong>
                      </span>
                      <span>
                        Last synced:{' '}
                        <span className="mono">
                          {selectedRepo.lastWorkflowSyncAt
                            ? formatTimestamp(selectedRepo.lastWorkflowSyncAt)
                            : 'Never'}
                        </span>
                      </span>
                    </div>
                  </div>
                  <div className="cf-inline-actions">
                    {selectedRepo.htmlUrl && (
                      <a
                        href={`${selectedRepo.htmlUrl}/actions`}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="btn btn-sm"
                      >
                        <ExternalLink size={12} aria-hidden="true" />
                        <span>GitHub Actions</span>
                      </a>
                    )}
                  </div>
                </div>
              )}

              {selectedRepo &&
                (selectedRepo.installationStatus !== 'ACTIVE' ||
                  selectedRepo.connectionStatus !== 'CONNECTED') && (
                  <div className="cf-notice cf-notice--warning" role="status">
                    <div className="cf-notice__icon" aria-hidden="true">
                      <AlertTriangle size={18} />
                    </div>
                    <div>
                      <p className="cf-notice__title">
                        Repository Synchronization Unavailable ({selectedRepo.connectionStatus})
                      </p>
                      <p className="cf-notice__text">
                        This repository&apos;s GitHub App installation status is{' '}
                        <span className="mono">{selectedRepo.installationStatus}</span>. Previously
                        stored workflow history remains readable, or visit{' '}
                        <Link to="/repositories" className="cf-link-accent">
                          Repositories
                        </Link>{' '}
                        to reconnect or re-sync the installation.
                      </p>
                    </div>
                  </div>
                )}
            </div>
          </DashboardCard>

          {/* C. Workflow List (shown for selected repository) */}
          {selectedRepo && (
            <DashboardCard
              title="GitHub Actions Workflows"
              subtitle={`Discovered workflow definitions for ${selectedRepo.fullName}`}
              icon={<Workflow size={17} />}
              headerAction={
                <StatusBadge
                  label={`${totalWorkflows} ${totalWorkflows === 1 ? 'Workflow' : 'Workflows'}`}
                  tone={totalWorkflows > 0 ? 'info' : 'neutral'}
                  size="sm"
                />
              }
            >
              <div className="cf-toolbar">
                <SearchField
                  label="Search workflows"
                  placeholder="Search workflows by name or file path..."
                  value={workflowSearch}
                  onChange={(value) => {
                    setWorkflowPage(0);
                    setWorkflowSearch(value);
                  }}
                />
                <div className="cf-filter-group">
                  <select
                    className="cf-filter-select"
                    aria-label="Filter workflows by state"
                    value={workflowStateFilter}
                    onChange={(e) => {
                      setWorkflowPage(0);
                      setWorkflowStateFilter(e.target.value);
                    }}
                  >
                    <option value="">All states</option>
                    <option value="active">Active</option>
                    <option value="disabled_manually">Disabled manually</option>
                    <option value="disabled_inactivity">Disabled inactivity</option>
                    <option value="deleted">Deleted</option>
                  </select>
                </div>
              </div>

              {workflows.length === 0 && !workflowSearch && !workflowStateFilter ? (
                <div className="cf-empty-panel">
                  <h3 className="cf-empty-panel__title">
                    {selectedRepo.lastWorkflowSyncAt
                      ? 'No GitHub Actions workflows found'
                      : 'Workflows have not been synchronized yet'}
                  </h3>
                  <p className="cf-empty-panel__desc">
                    {selectedRepo.lastWorkflowSyncAt
                      ? `CloudForge synchronized ${selectedRepo.fullName} and found no workflow files under .github/workflows/.`
                      : `Run a GitHub sync for ${selectedRepo.fullName} to discover .github/workflows definitions and recent runs.`}
                  </p>
                  {canSyncSelectedRepo && (
                    <button
                      type="button"
                      className="btn btn-primary btn-sm"
                      onClick={() => void handleSyncFromGitHub(selectedRepo)}
                      disabled={isSyncingGitHub}
                    >
                      <CloudDownload size={13} aria-hidden="true" />
                      <span>
                        {isSyncingGitHub ? 'Syncing GitHub...' : 'Sync Workflows from GitHub'}
                      </span>
                    </button>
                  )}
                </div>
              ) : (
                <div className={isRefreshingLocal ? 'cf-table-loading' : undefined}>
                  <DataTable
                    columns={workflowColumns}
                    rows={workflows}
                    getRowKey={(row) => row.id}
                    emptyMessage="No workflows match your current filter."
                    caption={`GitHub Actions workflows for ${selectedRepo.fullName}`}
                  />
                  {workflowTotalPages > 1 && (
                    <div className="cf-pagination">
                      <span>
                        Page {workflowPage + 1} of {workflowTotalPages} ({totalWorkflows} total)
                      </span>
                      <div className="cf-inline-actions">
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={workflowPage === 0}
                          onClick={() => setWorkflowPage((p) => Math.max(0, p - 1))}
                        >
                          Previous
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={workflowPage + 1 >= workflowTotalPages}
                          onClick={() => setWorkflowPage((p) => p + 1)}
                        >
                          Next
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </DashboardCard>
          )}

          {/* D. Workflow Runs */}
          <DashboardCard
            title="Workflow Runs"
            subtitle={
              selectedRepo
                ? `Recent GitHub Actions executions for ${selectedRepo.fullName}`
                : 'Recent GitHub Actions executions across all authorized workspace repositories'
            }
            icon={<Activity size={17} />}
            headerAction={
              <StatusBadge
                label={`${totalRuns} ${totalRuns === 1 ? 'Run' : 'Runs'}`}
                tone={totalRuns > 0 ? 'info' : 'neutral'}
                size="sm"
              />
            }
          >
            <div className="cf-toolbar">
              <SearchField
                label="Search workflow runs"
                placeholder="Search by workflow, branch, commit SHA, or actor..."
                value={runSearch}
                onChange={(value) => {
                  setRunPage(0);
                  setRunSearch(value);
                }}
              />
              <div className="cf-filter-group">
                <select
                  className="cf-filter-select"
                  aria-label="Filter runs by status"
                  value={runStatusFilter}
                  onChange={(e) => {
                    setRunPage(0);
                    setRunStatusFilter(e.target.value as WorkflowRunNormalizedStatus | '');
                  }}
                >
                  <option value="">All statuses</option>
                  <option value="QUEUED">Queued</option>
                  <option value="IN_PROGRESS">In progress</option>
                  <option value="SUCCESS">Succeeded</option>
                  <option value="FAILURE">Failed</option>
                  <option value="CANCELLED">Cancelled</option>
                  <option value="TIMED_OUT">Timed out</option>
                  <option value="SKIPPED">Skipped</option>
                  <option value="UNKNOWN">Unknown</option>
                </select>
              </div>
            </div>

            {(selectedWorkflowFilter || runStatusFilter || runSearch) && (
              <div className="cf-filter-chips" aria-label="Active run filters">
                {selectedWorkflowFilter && (
                  <span className="cf-chip">
                    <span>Workflow: {selectedWorkflowFilter.name}</span>
                    <button
                      type="button"
                      className="cf-chip__clear"
                      aria-label={`Clear workflow filter ${selectedWorkflowFilter.name}`}
                      onClick={() => {
                        setSelectedWorkflowFilter(null);
                        setRunPage(0);
                      }}
                    >
                      <X size={12} />
                    </button>
                  </span>
                )}
                {runStatusFilter && (
                  <span className="cf-chip">
                    <span>Status: {STATUS_META[runStatusFilter].label}</span>
                    <button
                      type="button"
                      className="cf-chip__clear"
                      aria-label="Clear status filter"
                      onClick={() => {
                        setRunStatusFilter('');
                        setRunPage(0);
                      }}
                    >
                      <X size={12} />
                    </button>
                  </span>
                )}
              </div>
            )}

            <div className={isRefreshingLocal ? 'cf-table-loading' : undefined}>
              <DataTable
                columns={runColumns}
                rows={runs}
                getRowKey={(row) => row.id}
                emptyMessage={
                  runSearch || runStatusFilter || selectedWorkflowFilter
                    ? 'No workflow runs match your current filters.'
                    : 'No workflow runs recorded yet. Synchronize a connected repository from GitHub to populate recent executions.'
                }
                caption="GitHub Actions workflow runs"
              />

              {runTotalPages > 1 && (
                <div className="cf-pagination">
                  <span>
                    Page {runPage + 1} of {runTotalPages} ({totalRuns} total)
                  </span>
                  <div className="cf-inline-actions">
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={runPage === 0}
                      onClick={() => setRunPage((p) => Math.max(0, p - 1))}
                    >
                      Previous
                    </button>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={runPage + 1 >= runTotalPages}
                      onClick={() => setRunPage((p) => p + 1)}
                    >
                      Next
                    </button>
                  </div>
                </div>
              )}
            </div>
          </DashboardCard>
        </>
      )}

      {/* Read-only Phase 3.1 boundary notice */}
      <div className="cf-notice cf-notice--info">
        <div className="cf-notice__icon" aria-hidden="true">
          <Info size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Phase 3.1 Read-Only GitHub Actions Integration</p>
          <p className="cf-notice__text">
            CloudForge inspects GitHub Actions workflows, executions, and jobs strictly read-only
            using least-privilege <span className="mono">Actions: Read</span> installation tokens.
            Pipeline orchestration, isolated container build workers, encrypted secrets, and
            Kubernetes deployments remain deferred to Phases 3.2–3.7.
          </p>
        </div>
      </div>

      {/* E. Workflow Run Details & Live Jobs/Steps Modal */}
      <Modal
        isOpen={selectedRun !== null}
        title={
          selectedRun
            ? `${selectedRun.workflowName} #${selectedRun.runNumber}`
            : 'Workflow Run Details'
        }
        subtitle={
          selectedRun
            ? `${selectedRun.repositoryFullName}${
                selectedRun.runAttempt > 1 ? ` · Attempt #${selectedRun.runAttempt}` : ''
              }`
            : undefined
        }
        onClose={() => setSelectedRun(null)}
        footer={
          selectedRun && (
            <>
              {selectedRun.htmlUrl && (
                <a
                  href={selectedRun.htmlUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="btn"
                >
                  <ExternalLink size={14} aria-hidden="true" />
                  <span>Open Run on GitHub</span>
                </a>
              )}
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => setSelectedRun(null)}
              >
                Close
              </button>
            </>
          )
        }
      >
        {selectedRun && (
          <div className="cf-run-detail-section">
            <div className="cf-run-detail-header-strip">
              <div className="cf-inline-actions">
                {renderRunStatusBadge(selectedRun.normalizedStatus, selectedRun.githubConclusion)}
                <span className="cf-event-pill">{selectedRun.event}</span>
                {selectedRun.runAttempt > 1 && (
                  <span className="cf-attempt-pill">Attempt #{selectedRun.runAttempt}</span>
                )}
              </div>
              <span className="mono cf-muted" style={{ fontSize: '12px' }}>
                GitHub Run ID: {selectedRun.githubRunId}
              </span>
            </div>

            <dl className="cf-kv-list">
              <div className="cf-kv-item">
                <dt>Repository</dt>
                <dd className="mono">{selectedRun.repositoryFullName}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Branch</dt>
                <dd className="mono">{selectedRun.headBranch ?? '—'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Commit SHA</dt>
                <dd className="mono">{selectedRun.headSha ?? '—'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Triggered By</dt>
                <dd className="mono">
                  {selectedRun.actorLogin ? `@${selectedRun.actorLogin}` : '—'}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>GitHub Status / Conclusion</dt>
                <dd className="mono">
                  {selectedRun.githubStatus}
                  {selectedRun.githubConclusion ? ` / ${selectedRun.githubConclusion}` : ''}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>Duration</dt>
                <dd className="mono">{formatDuration(selectedRun.durationSeconds)}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Started</dt>
                <dd className="mono">
                  {formatTimestamp(selectedRun.runStartedAt ?? selectedRun.githubCreatedAt)}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>Last Updated</dt>
                <dd className="mono">{formatTimestamp(selectedRun.githubUpdatedAt)}</dd>
              </div>
            </dl>

            <div className="cf-jobs-section">
              <div className="cf-jobs-section__header">
                <h3 className="cf-jobs-section__title">
                  Workflow Jobs &amp; Steps{' '}
                  {!isLoadingJobs && !jobsError ? `(${runJobs.length})` : ''}
                </h3>
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={isLoadingJobs}
                  onClick={() => void loadJobsForRun(selectedRun)}
                >
                  <RefreshCw
                    size={12}
                    className={isLoadingJobs ? 'cf-spin' : ''}
                    aria-hidden="true"
                  />
                  <span>{isLoadingJobs ? 'Loading Jobs...' : 'Reload Jobs'}</span>
                </button>
              </div>

              {jobsFetchedAt && !isLoadingJobs && !jobsError && (
                <div className="cf-muted mono" style={{ fontSize: '11.5px' }}>
                  Live read-only job snapshot fetched at {formatTimestamp(jobsFetchedAt)}
                </div>
              )}

              {isLoadingJobs ? (
                <LoadingState
                  title="Fetching workflow jobs"
                  description="Requesting read-only job and step telemetry from GitHub Actions..."
                />
              ) : jobsError ? (
                renderClassifiedNotice(jobsError)
              ) : runJobs.length === 0 ? (
                <div className="cf-empty-panel">
                  <p className="cf-empty-panel__desc">
                    No job details have been reported by GitHub for this workflow run yet.
                  </p>
                </div>
              ) : (
                <div className="cf-job-list">
                  {runJobs.map((job) => {
                    const isExpanded = Boolean(expandedJobIds[job.githubJobId]);
                    return (
                      <div
                        key={job.githubJobId}
                        className={`cf-job-card ${isExpanded ? 'cf-job-card--expanded' : ''}`}
                      >
                        <div className="cf-job-card__header">
                          <button
                            type="button"
                            className="cf-job-card__toggle"
                            aria-expanded={isExpanded}
                            onClick={() => toggleJobExpanded(job.githubJobId)}
                          >
                            <ChevronRight
                              size={15}
                              className="cf-job-card__chevron"
                              aria-hidden="true"
                            />
                            <span>{job.name}</span>
                          </button>
                          <div className="cf-job-card__meta">
                            {job.runnerName && (
                              <span className="cf-event-pill">{job.runnerName}</span>
                            )}
                            <span className="mono cf-muted" style={{ fontSize: '12px' }}>
                              {formatDuration(job.durationSeconds)}
                            </span>
                            {renderRunStatusBadge(job.normalizedStatus, job.githubConclusion)}
                            {job.htmlUrl && (
                              <a
                                href={job.htmlUrl}
                                target="_blank"
                                rel="noreferrer noopener"
                                className="btn btn-sm"
                                aria-label={`Open job ${job.name} on GitHub`}
                              >
                                <ExternalLink size={12} aria-hidden="true" />
                              </a>
                            )}
                          </div>
                        </div>

                        {isExpanded && (
                          <div className="cf-job-steps">
                            {job.steps.length === 0 ? (
                              <p className="cf-muted" style={{ margin: '4px 0', fontSize: '12.5px' }}>
                                No step breakdown available for this job.
                              </p>
                            ) : (
                              <ol className="cf-job-steps__list">
                                {job.steps.map((step) => (
                                  <li key={`${job.githubJobId}-${step.number}`} className="cf-job-step">
                                    <div className="cf-job-step__left">
                                      <span className="cf-job-step__num">
                                        {String(step.number).padStart(2, '0')}
                                      </span>
                                      <span className="cf-job-step__name">{step.name}</span>
                                    </div>
                                    <div className="cf-job-step__right">
                                      <span>{formatDuration(step.durationSeconds)}</span>
                                      {renderRunStatusBadge(
                                        step.normalizedStatus,
                                        step.githubConclusion
                                      )}
                                    </div>
                                  </li>
                                ))}
                              </ol>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
