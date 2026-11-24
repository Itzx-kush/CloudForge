import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type {
  GitHubWorkflow,
  GitHubWorkflowJob,
  GitHubWorkflowRun,
  PipelineOverview,
  WorkspaceRole,
} from '../types/platform';

function jsonResponse(
  body: unknown,
  status = 200,
  extraHeaders: Record<string, string> = {}
): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': 'req-pipelines-test',
      ...extraHeaders,
    },
  });
}

const HEALTH_UP = {
  application: 'CloudForge Backend',
  version: '0.3.0-phase3.1',
  phase: 'Phase 3.1 — Read-Only GitHub Actions Integration',
  status: 'UP',
  database: {
    status: 'UP',
    engine: 'PostgreSQL 17.4',
    responseTimeMs: 4,
    message: 'Database connection verified',
  },
  timestamp: '2026-11-24T06:00:00Z',
};

const SAMPLE_OVERVIEW: PipelineOverview = {
  workspaceId: 'ws-1',
  githubAppConfigured: true,
  connectedRepositoryCount: 2,
  repositoriesWithWorkflowsCount: 1,
  workflowCount: 2,
  recentRunCount: 4,
  activeRunCount: 1,
  failedRunCount: 1,
  succeededRunCount: 1,
  lastSyncedAt: '2026-11-24T05:30:00Z',
  repositories: [
    {
      repositoryId: 'repo-1',
      fullName: 'kush-dev/payments-service',
      name: 'payments-service',
      ownerLogin: 'kush-dev',
      defaultBranch: 'main',
      visibility: 'private',
      connectionStatus: 'CONNECTED',
      installationStatus: 'ACTIVE',
      htmlUrl: 'https://github.com/kush-dev/payments-service',
      workflowCount: 2,
      runCount: 4,
      activeRunCount: 1,
      failedRunCount: 1,
      lastWorkflowSyncAt: '2026-11-24T05:30:00Z',
      latestRunStatus: 'SUCCESS',
      latestRunAt: '2026-11-24T05:25:00Z',
    },
    {
      repositoryId: 'repo-2',
      fullName: 'kush-dev/docs-site',
      name: 'docs-site',
      ownerLogin: 'kush-dev',
      defaultBranch: 'main',
      visibility: 'public',
      connectionStatus: 'CONNECTED',
      installationStatus: 'ACTIVE',
      htmlUrl: 'https://github.com/kush-dev/docs-site',
      workflowCount: 0,
      runCount: 0,
      activeRunCount: 0,
      failedRunCount: 0,
      lastWorkflowSyncAt: '2026-11-24T05:20:00Z',
      latestRunStatus: null,
      latestRunAt: null,
    },
  ],
};

const SAMPLE_WORKFLOWS: GitHubWorkflow[] = [
  {
    id: 'wf-1',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    githubWorkflowId: 1001,
    name: 'CloudForge CI',
    path: '.github/workflows/ci.yml',
    state: 'active',
    htmlUrl: 'https://github.com/kush-dev/payments-service/blob/main/.github/workflows/ci.yml',
    githubCreatedAt: '2026-10-01T00:00:00Z',
    githubUpdatedAt: '2026-11-20T00:00:00Z',
    lastSyncedAt: '2026-11-24T05:30:00Z',
    latestRunId: 'run-1',
    latestRunNumber: 42,
    latestRunStatus: 'SUCCESS',
    latestRunGithubStatus: 'completed',
    latestRunConclusion: 'success',
    latestRunAt: '2026-11-24T05:25:00Z',
  },
  {
    id: 'wf-2',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    githubWorkflowId: 1002,
    name: 'Security Audit',
    path: '.github/workflows/security.yml',
    state: 'active',
    htmlUrl: 'https://github.com/kush-dev/payments-service/blob/main/.github/workflows/security.yml',
    githubCreatedAt: '2026-10-02T00:00:00Z',
    githubUpdatedAt: '2026-11-21T00:00:00Z',
    lastSyncedAt: '2026-11-24T05:30:00Z',
    latestRunId: 'run-2',
    latestRunNumber: 19,
    latestRunStatus: 'FAILURE',
    latestRunGithubStatus: 'completed',
    latestRunConclusion: 'failure',
    latestRunAt: '2026-11-24T04:10:00Z',
  },
];

const SAMPLE_RUNS: GitHubWorkflowRun[] = [
  {
    id: 'run-1',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    workflowId: 'wf-1',
    githubRunId: 50001,
    githubWorkflowId: 1001,
    runNumber: 42,
    runAttempt: 2,
    workflowName: 'CloudForge CI',
    displayTitle: 'feat: Phase 3.1 GitHub Actions',
    event: 'pull_request',
    headBranch: 'feat/phase3-1-github-actions',
    headSha: 'abc1234567890def',
    githubStatus: 'completed',
    githubConclusion: 'success',
    normalizedStatus: 'SUCCESS',
    actorLogin: 'kush-dev',
    actorAvatarUrl: null,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50001',
    githubCreatedAt: '2026-11-24T05:20:00Z',
    githubUpdatedAt: '2026-11-24T05:23:15Z',
    runStartedAt: '2026-11-24T05:20:10Z',
    durationSeconds: 185,
    lastSyncedAt: '2026-11-24T05:30:00Z',
  },
  {
    id: 'run-2',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    workflowId: 'wf-2',
    githubRunId: 50002,
    githubWorkflowId: 1002,
    runNumber: 19,
    runAttempt: 1,
    workflowName: 'Security Audit',
    displayTitle: 'Nightly dependency check',
    event: 'schedule',
    headBranch: 'main',
    headSha: '9988776655443322',
    githubStatus: 'completed',
    githubConclusion: 'failure',
    normalizedStatus: 'FAILURE',
    actorLogin: 'kush-dev',
    actorAvatarUrl: null,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50002',
    githubCreatedAt: '2026-11-24T04:05:00Z',
    githubUpdatedAt: '2026-11-24T04:07:00Z',
    runStartedAt: '2026-11-24T04:05:05Z',
    durationSeconds: 115,
    lastSyncedAt: '2026-11-24T05:30:00Z',
  },
  {
    id: 'run-3',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    workflowId: 'wf-1',
    githubRunId: 50003,
    githubWorkflowId: 1001,
    runNumber: 43,
    runAttempt: 1,
    workflowName: 'CloudForge CI',
    displayTitle: 'Running checks',
    event: 'push',
    headBranch: 'main',
    headSha: '1122334455667788',
    githubStatus: 'in_progress',
    githubConclusion: null,
    normalizedStatus: 'IN_PROGRESS',
    actorLogin: 'kush-dev',
    actorAvatarUrl: null,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50003',
    githubCreatedAt: '2026-11-24T05:28:00Z',
    githubUpdatedAt: '2026-11-24T05:29:00Z',
    runStartedAt: '2026-11-24T05:28:05Z',
    durationSeconds: null,
    lastSyncedAt: '2026-11-24T05:30:00Z',
  },
  {
    id: 'run-4',
    workspaceId: 'ws-1',
    repositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    workflowId: 'wf-1',
    githubRunId: 50004,
    githubWorkflowId: 1001,
    runNumber: 40,
    runAttempt: 1,
    workflowName: 'CloudForge CI',
    displayTitle: 'Cancelled superseded commit',
    event: 'push',
    headBranch: 'main',
    headSha: '5544332211009988',
    githubStatus: 'completed',
    githubConclusion: 'cancelled',
    normalizedStatus: 'CANCELLED',
    actorLogin: 'kush-dev',
    actorAvatarUrl: null,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50004',
    githubCreatedAt: '2026-11-24T03:00:00Z',
    githubUpdatedAt: '2026-11-24T03:00:40Z',
    runStartedAt: '2026-11-24T03:00:05Z',
    durationSeconds: 35,
    lastSyncedAt: '2026-11-24T05:30:00Z',
  },
];

const SAMPLE_JOBS: GitHubWorkflowJob[] = [
  {
    githubJobId: 901,
    githubRunId: 50001,
    runAttempt: 2,
    name: 'Backend Test & Package (Java 17)',
    githubStatus: 'completed',
    githubConclusion: 'success',
    normalizedStatus: 'SUCCESS',
    startedAt: '2026-11-24T05:20:15Z',
    completedAt: '2026-11-24T05:21:50Z',
    durationSeconds: 95,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50001/job/901',
    runnerName: 'GitHub Actions 2',
    steps: [
      {
        number: 1,
        name: 'Checkout repository',
        githubStatus: 'completed',
        githubConclusion: 'success',
        normalizedStatus: 'SUCCESS',
        startedAt: '2026-11-24T05:20:15Z',
        completedAt: '2026-11-24T05:20:20Z',
        durationSeconds: 5,
      },
      {
        number: 2,
        name: 'Run Maven unit & integration tests',
        githubStatus: 'completed',
        githubConclusion: 'success',
        normalizedStatus: 'SUCCESS',
        startedAt: '2026-11-24T05:20:20Z',
        completedAt: '2026-11-24T05:21:45Z',
        durationSeconds: 85,
      },
    ],
  },
  {
    githubJobId: 902,
    githubRunId: 50001,
    runAttempt: 2,
    name: 'Frontend Build',
    githubStatus: 'completed',
    githubConclusion: 'success',
    normalizedStatus: 'SUCCESS',
    startedAt: '2026-11-24T05:21:55Z',
    completedAt: '2026-11-24T05:22:30Z',
    durationSeconds: 35,
    htmlUrl: 'https://github.com/kush-dev/payments-service/actions/runs/50001/job/902',
    runnerName: 'GitHub Actions 4',
    steps: [],
  },
];

function mockPipelinesApi(options?: {
  role?: WorkspaceRole;
  overview?: PipelineOverview | Response;
  workflowsByRepo?: Record<string, GitHubWorkflow[]>;
  runsByRepo?: Record<string, GitHubWorkflowRun[]>;
  onSync?: () => Response | Promise<Response>;
  onJobs?: () => Response | Promise<Response>;
}) {
  const role: WorkspaceRole = options?.role ?? 'OWNER';
  const workflowsByRepo = options?.workflowsByRepo ?? {
    'repo-1': SAMPLE_WORKFLOWS,
    'repo-2': [],
  };
  const runsByRepo = options?.runsByRepo ?? {
    'repo-1': SAMPLE_RUNS,
    'repo-2': [],
  };

  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();

    if (url.startsWith('/api/health')) return jsonResponse(HEALTH_UP);
    if (url.startsWith('/api/auth/config')) {
      return jsonResponse({
        githubOAuthConfigured: true,
        githubAppConfigured: true,
        githubAppSlug: 'cloudforge-dev',
        loginUrl: '/oauth2/authorization/github',
      });
    }
    if (url.startsWith('/api/auth/csrf')) {
      return jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-pipelines-token',
      });
    }
    if (url.startsWith('/api/auth/me')) {
      return jsonResponse({
        user: {
          id: 'user-1',
          githubUserId: 1001,
          username: 'kush-dev',
          displayName: 'Kush Dev',
          email: 'kush@example.com',
          avatarUrl: null,
          profileUrl: 'https://github.com/kush-dev',
          lastLoginAt: '2026-11-24T05:00:00Z',
          createdAt: '2026-11-20T05:00:00Z',
        },
        workspaces: [
          {
            id: 'ws-1',
            name: "kush-dev's Workspace",
            slug: 'kush-dev',
            description: 'Personal workspace',
            personal: true,
            role,
            memberCount: 2,
            projectCount: 1,
            connectedRepositoryCount: 2,
            createdAt: '2026-11-20T05:00:00Z',
            updatedAt: '2026-11-24T05:00:00Z',
          },
        ],
      });
    }
    if (url.endsWith('/pipelines') && method === 'GET') {
      if (options?.overview instanceof Response) {
        return options.overview.clone();
      }
      return jsonResponse(options?.overview ?? SAMPLE_OVERVIEW);
    }
    if (url.includes('/workflows/sync') && method === 'POST') {
      if (options?.onSync) {
        return options.onSync();
      }
      return jsonResponse({
        repositoryId: 'repo-1',
        repositoryFullName: 'kush-dev/payments-service',
        workflowsSynced: 2,
        runsSynced: 4,
        runsCreated: 1,
        runsUpdated: 3,
        syncedAt: '2026-11-24T05:45:00Z',
        message: 'Synchronized 2 workflows and 4 recent runs for kush-dev/payments-service.',
      });
    }
    if (url.includes('/repositories/') && url.includes('/workflows')) {
      const repoId = url.split('/repositories/')[1].split('/')[0];
      const items = workflowsByRepo[repoId] ?? [];
      return jsonResponse({
        items,
        page: 0,
        size: 15,
        totalElements: items.length,
        totalPages: items.length > 0 ? 1 : 0,
      });
    }
    if (url.includes('/workflow-runs/') && url.includes('/jobs')) {
      if (options?.onJobs) {
        return options.onJobs();
      }
      return jsonResponse({
        runId: 'run-1',
        githubRunId: 50001,
        repositoryId: 'repo-1',
        repositoryFullName: 'kush-dev/payments-service',
        fetchedAt: '2026-11-24T05:35:00Z',
        jobs: SAMPLE_JOBS,
      });
    }
    if (url.includes('/workflow-runs')) {
      const parsed = new URL(url, 'http://localhost');
      const statusParam = parsed.searchParams.get('status');
      const workflowParam = parsed.searchParams.get('workflowId');
      let items: GitHubWorkflowRun[];
      if (url.includes('/repositories/')) {
        const repoId = url.split('/repositories/')[1].split('/')[0];
        items = [...(runsByRepo[repoId] ?? [])];
      } else {
        items = Object.values(runsByRepo).flat();
      }
      if (statusParam) {
        items = items.filter((r) => r.normalizedStatus === statusParam);
      }
      if (workflowParam) {
        items = items.filter((r) => r.workflowId === workflowParam);
      }
      return jsonResponse({
        items,
        page: 0,
        size: 15,
        totalElements: items.length,
        totalPages: items.length > 0 ? 1 : 0,
      });
    }

    return jsonResponse({}, 200);
  });
}

describe('CloudForge Phase 3.1 Pipelines Dashboard', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
    delete document.documentElement.dataset.theme;
    delete document.documentElement.dataset.accent;
    delete document.documentElement.dataset.motion;
  });

  it('renders real pipeline overview metrics, workflows, run statuses, and inspects live jobs and steps', async () => {
    const user = userEvent.setup();
    mockPipelinesApi();

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pipelines-workflow-count')).toHaveTextContent('2');
    });

    expect(screen.getByTestId('pipelines-repo-count')).toHaveTextContent('1');
    expect(screen.getByTestId('pipelines-run-count')).toHaveTextContent('4');
    expect(screen.getByTestId('pipelines-active-count')).toHaveTextContent('1');
    expect(screen.getByTestId('pipelines-failed-count')).toHaveTextContent('1');

    // Workflows and runs rendered
    expect(screen.getByText('.github/workflows/ci.yml')).toBeInTheDocument();
    expect(screen.getByText('.github/workflows/security.yml')).toBeInTheDocument();
    const runsTable = screen.getByRole('table', { name: 'GitHub Actions workflow runs' });
    expect(within(runsTable).getByText('feat/phase3-1-github-actions')).toBeInTheDocument();
    expect(within(runsTable).getByText('abc1234')).toBeInTheDocument();
    expect(within(runsTable).getByText('Attempt #2')).toBeInTheDocument();
    expect(within(runsTable).getByText('In progress')).toBeInTheDocument();
    expect(within(runsTable).getByText('Cancelled')).toBeInTheDocument();

    // Open run details modal and inspect live jobs & expandable steps
    const inspectButtons = screen.getAllByRole('button', { name: /Inspect/i });
    await user.click(inspectButtons[0]);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('CloudForge CI #42')).toBeInTheDocument();
    expect(within(dialog).getByText('Backend Test & Package (Java 17)')).toBeInTheDocument();
    expect(within(dialog).getByText('Frontend Build')).toBeInTheDocument();

    // Expand first job to verify step breakdown
    await user.click(within(dialog).getByRole('button', { name: /Backend Test & Package/i }));
    expect(within(dialog).getByText('Checkout repository')).toBeInTheDocument();
    expect(within(dialog).getByText('Run Maven unit & integration tests')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: /Open Run on GitHub/i })).toHaveAttribute(
      'href',
      'https://github.com/kush-dev/payments-service/actions/runs/50001'
    );
  });

  it('switches repositories cleanly and displays empty workflow state for synced repo without workflows', async () => {
    const user = userEvent.setup();
    mockPipelinesApi();

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('.github/workflows/ci.yml')).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByLabelText('Select repository'), 'repo-2');

    await waitFor(() => {
      expect(screen.getByText('No GitHub Actions workflows found')).toBeInTheDocument();
    });
    expect(
      screen.getByText(/found no workflow files under \.github\/workflows\//i)
    ).toBeInTheDocument();
  });

  it('distinguishes Refresh Stored Data (GET) from Sync from GitHub (CSRF POST) and restricts VIEWER sync', async () => {
    const user = userEvent.setup();
    const fetchSpy = mockPipelinesApi();

    const { unmount } = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pipelines-workflow-count')).toHaveTextContent('2');
    });

    // 1. Refresh Stored Data uses GET only (no POST /workflows/sync)
    await user.click(screen.getByRole('button', { name: /Refresh Stored Data/i }));
    await waitFor(() => {
      expect(
        screen.getByText(/Refreshed locally stored pipeline data from CloudForge/i)
      ).toBeInTheDocument();
    });
    expect(
      fetchSpy.mock.calls.some(
        ([url, init]) => String(url).includes('/workflows/sync') && init?.method === 'POST'
      )
    ).toBe(false);

    // 2. Sync from GitHub triggers CSRF-protected POST /workflows/sync
    await user.click(screen.getByRole('button', { name: /^Sync from GitHub$/i }));
    await waitFor(() => {
      expect(
        screen.getByText(/Synchronized 2 workflows and 4 recent runs/i)
      ).toBeInTheDocument();
    });

    const syncCall = fetchSpy.mock.calls.find(
      ([url, init]) => String(url).includes('/workflows/sync') && init?.method === 'POST'
    );
    expect(syncCall).toBeDefined();
    expect((syncCall?.[1]?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe(
      'csrf-pipelines-token'
    );

    unmount();
    vi.restoreAllMocks();

    // 3. VIEWER sees Sync from GitHub disabled while Refresh Stored Data remains usable
    mockPipelinesApi({ role: 'VIEWER' });
    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pipelines-workflow-count')).toHaveTextContent('2');
    });
    expect(screen.getByRole('button', { name: /^Sync from GitHub$/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Refresh Stored Data/i })).toBeEnabled();
  });

  it('guides users to Repositories when no connected repositories exist', async () => {
    mockPipelinesApi({
      overview: {
        ...SAMPLE_OVERVIEW,
        connectedRepositoryCount: 0,
        repositoriesWithWorkflowsCount: 0,
        workflowCount: 0,
        recentRunCount: 0,
        activeRunCount: 0,
        failedRunCount: 0,
        succeededRunCount: 0,
        lastSyncedAt: null,
        repositories: [],
      },
      workflowsByRepo: {},
      runsByRepo: {},
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('No Connected Repositories')).toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: /Go to Repositories/i })).toHaveAttribute(
      'href',
      '/repositories'
    );
  });

  it('surfaces Actions: Read permission errors, GitHub rate limits, revoked installations, and backend errors clearly', async () => {
    const user = userEvent.setup();

    // 1. Missing Actions: Read permission (403)
    mockPipelinesApi({
      onSync: () =>
        jsonResponse(
          {
            status: 403,
            error: 'Forbidden',
            message:
              "GitHub App installation does not have 'Actions: Read' permission. Grant Actions (Read-only).",
          },
          403
        ),
    });

    const first = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Sync from GitHub$/i })).toBeEnabled();
    });

    await user.click(screen.getByRole('button', { name: /^Sync from GitHub$/i }));
    await waitFor(() => {
      expect(
        screen.getByText('GitHub App Missing Actions: Read Permission')
      ).toBeInTheDocument();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/Actions/i);
    first.unmount();
    vi.restoreAllMocks();

    // 2. GitHub Rate Limit (429 with Retry-After)
    mockPipelinesApi({
      onSync: () =>
        jsonResponse(
          {
            status: 429,
            error: 'Too Many Requests',
            message: 'GitHub API rate limit exceeded.',
          },
          429,
          { 'Retry-After': '45' }
        ),
    });

    const second = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Sync from GitHub$/i })).toBeEnabled();
    });

    await user.click(screen.getByRole('button', { name: /^Sync from GitHub$/i }));
    await waitFor(() => {
      expect(screen.getByText('GitHub API Rate Limit Reached')).toBeInTheDocument();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/45s/);
    second.unmount();
    vi.restoreAllMocks();

    // 3. Revoked installation (409) shows reconnection guidance
    mockPipelinesApi({
      onSync: () =>
        jsonResponse(
          {
            status: 409,
            error: 'Conflict',
            message:
              'GitHub App installation was revoked on GitHub. Reconnect the GitHub App on the Repositories page.',
          },
          409
        ),
    });

    const third = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Sync from GitHub$/i })).toBeEnabled();
    });
    await user.click(screen.getByRole('button', { name: /^Sync from GitHub$/i }));
    await waitFor(() => {
      expect(
        screen.getByText('GitHub App Installation Requires Attention')
      ).toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: /Reconnect on Repositories/i })).toHaveAttribute(
      'href',
      '/repositories'
    );
    third.unmount();
    vi.restoreAllMocks();

    // 4. Backend unavailable / 503 error state
    mockPipelinesApi({
      overview: jsonResponse(
        {
          status: 503,
          error: 'Service Unavailable',
          message: 'PostgreSQL backend temporarily unreachable.',
        },
        503
      ),
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unable to load Pipelines dashboard')).toBeInTheDocument();
    });
    expect(screen.getByText('PostgreSQL backend temporarily unreachable.')).toBeInTheDocument();
  });

  it('preserves light/dark theme, accent, and reduced-motion preferences on Pipelines page', async () => {
    localStorage.setItem(
      'cloudforge.appearance.v1',
      JSON.stringify({
        theme: 'light',
        accent: 'emerald',
        density: 'compact',
        motion: 'reduced',
        sidebar: 'expanded',
      })
    );
    mockPipelinesApi();

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Pipelines', level: 1 })).toBeInTheDocument();
    });
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.dataset.accent).toBe('emerald');
    expect(document.documentElement.dataset.motion).toBe('reduced');
  });
});
