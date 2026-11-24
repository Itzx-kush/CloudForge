import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type {
  LinkedRepository,
  PipelineDefinition,
  PipelineOverview,
  PipelineRun,
  PipelineRunEvent,
  ProjectSummary,
  StageExecutionAttempt,
  StageExecutionLog,
  WorkerPoolStatus,
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
      'X-Request-Id': 'req-orch-test',
      ...extraHeaders,
    },
  });
}

const HEALTH_UP = {
  application: 'CloudForge Backend',
  version: '0.3.2-phase3.2',
  phase: 'Phase 3.2 — Pipeline Orchestration Engine',
  status: 'UP',
  database: {
    status: 'UP',
    engine: 'PostgreSQL 17.4',
    responseTimeMs: 3,
    message: 'Database connection verified',
  },
  timestamp: '2026-11-24T10:00:00Z',
};

const EMPTY_GH_OVERVIEW: PipelineOverview = {
  workspaceId: 'ws-1',
  githubAppConfigured: true,
  connectedRepositoryCount: 1,
  repositoriesWithWorkflowsCount: 1,
  workflowCount: 1,
  recentRunCount: 0,
  activeRunCount: 0,
  failedRunCount: 0,
  succeededRunCount: 0,
  lastSyncedAt: '2026-11-24T09:30:00Z',
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
      workflowCount: 1,
      runCount: 0,
      activeRunCount: 0,
      failedRunCount: 0,
      lastWorkflowSyncAt: '2026-11-24T09:30:00Z',
      latestRunStatus: null,
      latestRunAt: null,
    },
  ],
};

const SAMPLE_REPO_1: LinkedRepository = {
  id: 'repo-1',
  workspaceId: 'ws-1',
  installationId: 'inst-1',
  githubInstallationId: 77001,
  githubRepoId: 4001,
  ownerLogin: 'kush-dev',
  name: 'payments-service',
  fullName: 'kush-dev/payments-service',
  defaultBranch: 'main',
  visibility: 'private',
  htmlUrl: 'https://github.com/kush-dev/payments-service',
  description: 'Core billing service',
  archived: false,
  connectionStatus: 'CONNECTED',
  githubUpdatedAt: '2026-11-24T09:00:00Z',
  lastSyncedAt: '2026-11-24T09:30:00Z',
};

const SAMPLE_REPOSITORIES: LinkedRepository[] = [SAMPLE_REPO_1];

const SAMPLE_PROJECTS: ProjectSummary[] = [
  {
    id: 'proj-1',
    workspaceId: 'ws-1',
    name: 'Payments API',
    slug: 'payments-api',
    description: 'Core billing service',
    defaultBranch: 'main',
    status: 'ACTIVE',
    linkedRepository: SAMPLE_REPO_1,
    createdAt: '2026-11-20T09:00:00Z',
    updatedAt: '2026-11-24T09:00:00Z',
  },
];

const SAMPLE_DEFINITIONS: PipelineDefinition[] = [
  {
    id: 'def-1',
    workspaceId: 'ws-1',
    projectId: 'proj-1',
    projectName: 'Payments API',
    linkedRepositoryId: 'repo-1',
    repositoryFullName: 'kush-dev/payments-service',
    name: 'Production Release DAG',
    slug: 'production-release-dag',
    description: 'Source prep, build, security scan, and deployment intent',
    enabled: true,
    definitionVersion: 2,
    rowVersion: 1,
    createdByUsername: 'kush-dev',
    updatedByUsername: 'kush-dev',
    stages: [
      {
        id: 'st-def-1',
        stageKey: 'source',
        displayName: 'Source Preparation',
        stageType: 'SOURCE_PREP',
        executionOrder: 0,
        dependsOn: [],
        timeoutSeconds: 300,
        maxRetries: 0,
        allowFailure: false,
        metadata: {},
      },
      {
        id: 'st-def-2',
        stageKey: 'build',
        displayName: 'Application Build',
        stageType: 'BUILD',
        executionOrder: 1,
        dependsOn: ['source'],
        timeoutSeconds: 600,
        maxRetries: 1,
        allowFailure: false,
        metadata: {},
      },
      {
        id: 'st-def-3',
        stageKey: 'security',
        displayName: 'Security Scan',
        stageType: 'SECURITY_SCAN',
        executionOrder: 2,
        dependsOn: ['build'],
        timeoutSeconds: 450,
        maxRetries: 0,
        allowFailure: false,
        metadata: {},
      },
    ],
    createdAt: '2026-11-24T08:00:00Z',
    updatedAt: '2026-11-24T09:00:00Z',
  },
];

const SAMPLE_RUN_QUEUED: PipelineRun = {
  id: 'run-orch-1',
  workspaceId: 'ws-1',
  projectId: 'proj-1',
  projectName: 'Payments API',
  definitionId: 'def-1',
  definitionName: 'Production Release DAG',
  definitionVersion: 2,
  linkedRepositoryId: 'repo-1',
  repositoryFullName: 'kush-dev/payments-service',
  runNumber: 1,
  triggerType: 'MANUAL',
  targetBranch: 'main',
  commitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  status: 'QUEUED',
  statusReason: 'Queued — Awaiting execution infrastructure (Phase 3.3 isolated build workers)',
  requestedByUsername: 'kush-dev',
  cancelledByUsername: null,
  queuedAt: '2026-11-24T09:40:00Z',
  startedAt: null,
  completedAt: null,
  cancelledAt: null,
  rowVersion: 0,
  stages: [
    {
      id: 'st-run-1',
      stageKey: 'source',
      displayName: 'Source Preparation',
      stageType: 'SOURCE_PREP',
      executionOrder: 0,
      dependsOn: [],
      timeoutSeconds: 300,
      maxRetries: 0,
      attemptNumber: 1,
      allowFailure: false,
      status: 'READY',
      statusReason: 'All upstream dependencies satisfied; ready for Phase 3.3 worker claim',
      readyAt: '2026-11-24T09:40:00Z',
      startedAt: null,
      completedAt: null,
    },
    {
      id: 'st-run-2',
      stageKey: 'build',
      displayName: 'Application Build',
      stageType: 'BUILD',
      executionOrder: 1,
      dependsOn: ['source'],
      timeoutSeconds: 600,
      maxRetries: 1,
      attemptNumber: 1,
      allowFailure: false,
      status: 'PENDING',
      statusReason: 'Waiting for upstream dependencies',
      readyAt: null,
      startedAt: null,
      completedAt: null,
    },
    {
      id: 'st-run-3',
      stageKey: 'security',
      displayName: 'Security Scan',
      stageType: 'SECURITY_SCAN',
      executionOrder: 2,
      dependsOn: ['build'],
      timeoutSeconds: 450,
      maxRetries: 0,
      attemptNumber: 1,
      allowFailure: false,
      status: 'PENDING',
      statusReason: 'Waiting for upstream dependencies',
      readyAt: null,
      startedAt: null,
      completedAt: null,
    },
  ],
  createdAt: '2026-11-24T09:40:00Z',
  updatedAt: '2026-11-24T09:40:00Z',
};

const SAMPLE_EVENTS: PipelineRunEvent[] = [
  {
    id: 'ev-1',
    pipelineRunId: 'run-orch-1',
    stageRunId: null,
    stageKey: null,
    eventType: 'RUN_QUEUED',
    fromStatus: null,
    toStatus: 'QUEUED',
    actorUsername: 'kush-dev',
    message: 'Queued — Awaiting execution infrastructure (Phase 3.3 isolated build workers)',
    metadata: { definitionVersion: 2, stageCount: 3 },
    occurredAt: '2026-11-24T09:40:00Z',
  },
  {
    id: 'ev-2',
    pipelineRunId: 'run-orch-1',
    stageRunId: 'st-run-1',
    stageKey: 'source',
    eventType: 'STAGE_READY',
    fromStatus: 'PENDING',
    toStatus: 'READY',
    actorUsername: 'kush-dev',
    message: 'All upstream dependencies satisfied; ready for Phase 3.3 worker claim',
    metadata: { stageType: 'SOURCE_PREP' },
    occurredAt: '2026-11-24T09:40:01Z',
  },
];

function mockOrchestrationApi(options?: {
  role?: WorkspaceRole;
  projects?: ProjectSummary[];
  definitions?: PipelineDefinition[];
  runs?: PipelineRun[];
  eventsByRunId?: Record<string, PipelineRunEvent[]>;
  workerPool?: WorkerPoolStatus;
  attemptsByStageId?: Record<string, StageExecutionAttempt[]>;
  logsByStageId?: Record<string, StageExecutionLog[]>;
  definitionsError?: Response;
  onCreateDefinition?: (body: unknown, headers: Record<string, string>) => Response;
  onCreateRun?: (body: unknown, headers: Record<string, string>) => Response;
  onCancelRun?: (runId: string, body: unknown, headers: Record<string, string>) => Response;
}) {
  const role: WorkspaceRole = options?.role ?? 'OWNER';
  const projects = options?.projects ?? [...SAMPLE_PROJECTS];
  const definitions = options?.definitions ? [...options.definitions] : [...SAMPLE_DEFINITIONS];
  const runs = options?.runs ? [...options.runs] : [structuredClone(SAMPLE_RUN_QUEUED)];
  const eventsByRunId: Record<string, PipelineRunEvent[]> = options?.eventsByRunId
    ? structuredClone(options.eventsByRunId)
    : { 'run-orch-1': structuredClone(SAMPLE_EVENTS) };

  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;

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
        token: 'csrf-orch-token',
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
          lastLoginAt: '2026-11-24T09:00:00Z',
          createdAt: '2026-11-20T09:00:00Z',
        },
        workspaces: [
          {
            id: 'ws-1',
            name: "kush-dev's Workspace",
            slug: 'kush-dev',
            description: 'Personal workspace',
            personal: true,
            role,
            memberCount: 3,
            projectCount: projects.length,
            connectedRepositoryCount: 1,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T09:00:00Z',
          },
        ],
      });
    }

    // Phase 3.1 GitHub Actions endpoints (loaded on initial PipelinesPage render)
    if (url.endsWith('/pipelines') && method === 'GET') {
      return jsonResponse(EMPTY_GH_OVERVIEW);
    }
    if (url.includes('/repositories/') && url.includes('/workflows')) {
      return jsonResponse({ items: [], page: 0, size: 15, totalElements: 0, totalPages: 0 });
    }
    if (url.includes('/workflow-runs')) {
      return jsonResponse({ items: [], page: 0, size: 15, totalElements: 0, totalPages: 0 });
    }

    // Projects & repositories used by CloudForgePipelinesPanel
    if (url.includes('/projects/proj-1') && !url.includes('/secrets') && method === 'GET') {
      return jsonResponse({
        ...SAMPLE_PROJECTS[0],
        environments: [
          {
            id: 'env-stg',
            projectId: 'proj-1',
            name: 'Staging',
            environmentType: 'STAGING',
            branchOverride: null,
            configVariables: {},
            notes: null,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T09:00:00Z',
          },
        ],
      });
    }
    if (url.includes('/environments/env-stg/secrets') && method === 'GET') {
      return jsonResponse({
        items: [
          {
            id: 'sec-stg-1',
            workspaceId: 'ws-1',
            projectId: 'proj-1',
            environmentId: 'env-stg',
            environmentName: 'Staging',
            environmentType: 'STAGING',
            name: 'STAGING_DEPLOY_KEY',
            description: 'Staging token',
            status: 'ACTIVE',
            currentVersionNumber: 3,
            currentVersionId: 'ver-3',
            currentKekId: 'kek-v1',
            encryptionAlgorithm: 'AES-256-GCM',
            encryptionFormatVersion: 1,
            createdByUsername: 'kush-dev',
            updatedByUsername: 'kush-dev',
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T09:00:00Z',
            lastRotatedAt: null,
            revokedAt: null,
            revokedByUsername: null,
          },
        ],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (url.includes('/projects') && method === 'GET') {
      return jsonResponse({
        items: projects,
        page: 0,
        size: 100,
        totalElements: projects.length,
        totalPages: projects.length > 0 ? 1 : 0,
      });
    }
    if (url.includes('/repositories') && method === 'GET') {
      return jsonResponse({
        items: SAMPLE_REPOSITORIES,
        page: 0,
        size: 100,
        totalElements: SAMPLE_REPOSITORIES.length,
        totalPages: 1,
      });
    }

    // Phase 3.2 Orchestration Definitions
    if (url.includes('/pipelines/definitions') && method === 'GET') {
      if (options?.definitionsError) {
        return options.definitionsError.clone();
      }
      return jsonResponse({
        items: definitions,
        page: 0,
        size: 25,
        totalElements: definitions.length,
        totalPages: definitions.length > 0 ? 1 : 0,
      });
    }
    if (url.includes('/pipelines/definitions') && method === 'POST') {
      if (options?.onCreateDefinition) {
        return options.onCreateDefinition(body, headers);
      }
      const req = body as {
        projectId: string;
        name: string;
        description?: string;
        enabled?: boolean;
        stages: Array<{
          stageKey: string;
          displayName: string;
          stageType: PipelineDefinition['stages'][0]['stageType'];
          dependsOn: string[];
          timeoutSeconds?: number;
          maxRetries?: number;
          allowFailure?: boolean;
        }>;
      };
      const created: PipelineDefinition = {
        id: `def-${definitions.length + 1}`,
        workspaceId: 'ws-1',
        projectId: req.projectId,
        projectName: projects.find((p) => p.id === req.projectId)?.name ?? 'Payments API',
        linkedRepositoryId: 'repo-1',
        repositoryFullName: 'kush-dev/payments-service',
        name: req.name,
        slug: req.name.toLowerCase().replace(/[^a-z0-9]+/g, '-'),
        description: req.description ?? null,
        enabled: req.enabled ?? true,
        definitionVersion: 1,
        rowVersion: 0,
        createdByUsername: 'kush-dev',
        updatedByUsername: 'kush-dev',
        stages: req.stages.map((s, idx) => ({
          id: `st-new-${idx + 1}`,
          stageKey: s.stageKey,
          displayName: s.displayName,
          stageType: s.stageType,
          executionOrder: idx,
          dependsOn: s.dependsOn ?? [],
          timeoutSeconds: s.timeoutSeconds ?? 600,
          maxRetries: s.maxRetries ?? 0,
          allowFailure: s.allowFailure ?? false,
          metadata: {},
        })),
        createdAt: '2026-11-24T10:05:00Z',
        updatedAt: '2026-11-24T10:05:00Z',
      };
      definitions.unshift(created);
      return jsonResponse(created, 201);
    }

    // Phase 3.3 Worker Pool Status, Attempts & Execution Logs
    if (url.includes('/pipelines/workers/status') && method === 'GET') {
      if (options?.workerPool) {
        return jsonResponse(options.workerPool);
      }
      return jsonResponse({
        workerPlaneEnabled: false,
        schedulerEnabled: false,
        registeredWorkers: 0,
        availableWorkers: 0,
        busyWorkers: 0,
        drainingWorkers: 0,
        offlineWorkers: 0,
        activeAssignments: 0,
        workspaceReadyStages: 1,
        workspaceRunningStages: 0,
        workspaceFailedAttempts: 0,
        supportedOperations: ['ENV_VALIDATION', 'CONTROLLED_SMOKE_TEST', 'WORKER_DIAGNOSTICS'],
        workers: [],
        sampledAt: '2026-11-24T10:00:00Z',
      });
    }
    if (url.includes('/stages/') && url.endsWith('/attempts') && method === 'GET') {
      const stageId = url.split('/stages/')[1].replace('/attempts', '');
      return jsonResponse(options?.attemptsByStageId?.[stageId] ?? []);
    }
    if (url.includes('/stages/') && url.includes('/logs') && method === 'GET') {
      const stageId = url.split('/stages/')[1].split('/logs')[0];
      const items = options?.logsByStageId?.[stageId] ?? [];
      return jsonResponse({
        items,
        page: 0,
        size: 150,
        totalElements: items.length,
        totalPages: items.length > 0 ? 1 : 0,
      });
    }

    // Phase 3.2 Orchestration Run Events
    if (url.includes('/pipelines/runs/') && url.endsWith('/events') && method === 'GET') {
      const runId = url.split('/pipelines/runs/')[1].replace('/events', '');
      return jsonResponse(eventsByRunId[runId] ?? []);
    }

    // Phase 3.2 Orchestration Run Cancel
    if (url.includes('/pipelines/runs/') && url.endsWith('/cancel') && method === 'POST') {
      const runId = url.split('/pipelines/runs/')[1].replace('/cancel', '');
      if (options?.onCancelRun) {
        return options.onCancelRun(runId, body, headers);
      }
      const idx = runs.findIndex((r) => r.id === runId);
      if (idx >= 0) {
        const updated: PipelineRun = {
          ...runs[idx],
          status: 'CANCELLED',
          statusReason: (body as { reason?: string })?.reason || 'Cancelled by operator',
          cancelledByUsername: 'kush-dev',
          cancelledAt: '2026-11-24T10:06:00Z',
          completedAt: '2026-11-24T10:06:00Z',
          stages: runs[idx].stages.map((st) => ({
            ...st,
            status: 'CANCELLED',
            statusReason: 'Cancelled because parent pipeline run was cancelled',
          })),
        };
        runs[idx] = updated;
        eventsByRunId[runId] = [
          ...(eventsByRunId[runId] ?? []),
          {
            id: `ev-cancel-${Date.now()}`,
            pipelineRunId: runId,
            stageRunId: null,
            stageKey: null,
            eventType: 'RUN_CANCELLED',
            fromStatus: 'QUEUED',
            toStatus: 'CANCELLED',
            actorUsername: 'kush-dev',
            message: updated.statusReason ?? 'Cancelled',
            metadata: { cancelledBeforeWorkerExecution: true },
            occurredAt: '2026-11-24T10:06:00Z',
          },
        ];
        return jsonResponse(updated, 200);
      }
      return jsonResponse({ status: 404, message: 'Run not found' }, 404);
    }

    // Phase 3.2 Orchestration Runs List & Create
    if (url.includes('/pipelines/runs') && method === 'GET') {
      return jsonResponse({
        items: runs,
        page: 0,
        size: 25,
        totalElements: runs.length,
        totalPages: runs.length > 0 ? 1 : 0,
      });
    }
    if (url.includes('/pipelines/runs') && method === 'POST') {
      if (options?.onCreateRun) {
        return options.onCreateRun(body, headers);
      }
      const req = body as {
        definitionId: string;
        targetBranch?: string;
        commitSha?: string;
      };
      const def = definitions.find((d) => d.id === req.definitionId) ?? definitions[0];
      const nextNum = runs.length + 1;
      const newRun: PipelineRun = {
        ...structuredClone(SAMPLE_RUN_QUEUED),
        id: `run-orch-${nextNum}`,
        runNumber: nextNum,
        definitionId: def.id,
        definitionName: def.name,
        definitionVersion: def.definitionVersion,
        targetBranch: req.targetBranch || 'main',
        commitSha: req.commitSha || null,
        status: 'QUEUED',
        statusReason:
          'Queued — Awaiting execution infrastructure (Phase 3.3 isolated build workers)',
      };
      runs.unshift(newRun);
      eventsByRunId[newRun.id] = [
        {
          id: `ev-queued-${nextNum}`,
          pipelineRunId: newRun.id,
          stageRunId: null,
          stageKey: null,
          eventType: 'RUN_QUEUED',
          fromStatus: null,
          toStatus: 'QUEUED',
          actorUsername: 'kush-dev',
          message: newRun.statusReason ?? 'Queued',
          metadata: {},
          occurredAt: '2026-11-24T10:05:30Z',
        },
      ];
      return jsonResponse(newRun, 201);
    }

    return jsonResponse({}, 200);
  });
}

async function openCloudForgePipelinesTab(user: ReturnType<typeof userEvent.setup>) {
  const tab = await screen.findByRole('tab', { name: /CloudForge Pipelines/i });
  await user.click(tab);
  await waitFor(() => {
    expect(screen.getByText('CloudForge Pipeline Definitions')).toBeInTheDocument();
    expect(screen.getByTestId('orch-def-count')).not.toHaveTextContent('—');
  });
}

describe('CloudForge Phase 3.2 Pipeline Orchestration UI', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
    delete document.documentElement.dataset.theme;
    delete document.documentElement.dataset.accent;
    delete document.documentElement.dataset.motion;
  });

  it('clearly separates GitHub Actions from CloudForge Pipelines, lists definitions & stages, and displays honest queued state', async () => {
    const user = userEvent.setup();
    mockOrchestrationApi();

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    const ghTab = await screen.findByRole('tab', { name: /GitHub Actions/i });
    const cfTab = screen.getByRole('tab', { name: /CloudForge Pipelines/i });
    expect(ghTab).toHaveAttribute('aria-selected', 'true');
    expect(cfTab).toHaveAttribute('aria-selected', 'false');

    await openCloudForgePipelinesTab(user);
    expect(cfTab).toHaveAttribute('aria-selected', 'true');

    expect(screen.getByTestId('orch-def-count')).toHaveTextContent('1');
    expect(screen.getByTestId('orch-run-count')).toHaveTextContent('1');
    expect(screen.getByTestId('orch-queued-count')).toHaveTextContent('1');

    const defsTable = screen.getByRole('table', { name: 'CloudForge pipeline definitions' });
    expect(within(defsTable).getByText('Production Release DAG')).toBeInTheDocument();
    expect(within(defsTable).getByText('source')).toBeInTheDocument();
    expect(within(defsTable).getByText('build')).toBeInTheDocument();
    expect(within(defsTable).getByText('security')).toBeInTheDocument();

    const runsTable = screen.getByRole('table', { name: 'CloudForge pipeline runs' });
    expect(
      within(runsTable).getByText('Queued — Awaiting execution infrastructure')
    ).toBeInTheDocument();
    expect(within(runsTable).queryByText(/Build running/i)).not.toBeInTheDocument();
    expect(within(runsTable).queryByText(/Pipeline successful/i)).not.toBeInTheDocument();
  });

  it('creates a new pipeline definition via CSRF POST and surfaces DAG validation errors cleanly', async () => {
    const user = userEvent.setup();
    let rejectNext = true;
    const fetchSpy = mockOrchestrationApi({
      onCreateDefinition: (body, headers) => {
        expect(headers['X-XSRF-TOKEN']).toBe('csrf-orch-token');
        if (rejectNext) {
          rejectNext = false;
          return jsonResponse(
            {
              status: 400,
              error: 'Bad Request',
              message: 'Pipeline stage dependency graph contains a cycle',
              fieldErrors: {
                'stages.dependsOn': 'Cyclic dependency detected involving: build, test',
              },
            },
            400
          );
        }
        const req = body as { name: string };
        return jsonResponse(
          {
            ...SAMPLE_DEFINITIONS[0],
            id: 'def-created-2',
            name: req.name,
            definitionVersion: 1,
          },
          201
        );
      },
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);

    const newDefBtn = screen.getByRole('button', { name: /New Pipeline Definition/i });
    await waitFor(() => expect(newDefBtn).toBeEnabled());
    await user.click(newDefBtn);

    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Pipeline Name/i), 'Staging DAG');
    await user.click(within(dialog).getByRole('button', { name: /Create Pipeline Definition/i }));

    await waitFor(() => {
      expect(within(dialog).getByText('Pipeline definition validation failed')).toBeInTheDocument();
    });
    expect(
      within(dialog).getByText(/Cyclic dependency detected involving: build, test/i)
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Pipeline Name/i)).toHaveValue('Staging DAG');

    await user.click(within(dialog).getByRole('button', { name: /Create Pipeline Definition/i }));
    await waitFor(() => {
      expect(screen.getByText(/Created pipeline definition "Staging DAG"/i)).toBeInTheDocument();
    });
    expect(
      fetchSpy.mock.calls.some(
        ([url, init]) => String(url).includes('/pipelines/definitions') && init?.method === 'POST'
      )
    ).toBe(true);
  }, 15000);

  it('queues a pipeline run with Idempotency-Key and CSRF header and confirms honest queued state', async () => {
    const user = userEvent.setup();
    const capturedHeaders: Record<string, string>[] = [];
    mockOrchestrationApi({
      onCreateRun: (body, headers) => {
        capturedHeaders.push(headers);
        const req = body as { targetBranch?: string; commitSha?: string };
        return jsonResponse(
          {
            ...structuredClone(SAMPLE_RUN_QUEUED),
            id: 'run-orch-2',
            runNumber: 2,
            targetBranch: req.targetBranch || 'main',
            commitSha: req.commitSha || null,
          },
          201
        );
      },
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);

    const defsTable = screen.getByRole('table', { name: 'CloudForge pipeline definitions' });
    await user.click(within(defsTable).getByRole('button', { name: /Queue Run/i }));

    const queueDialog = await screen.findByRole('dialog');
    expect(
      within(queueDialog).getByText('Queued — Awaiting execution infrastructure')
    ).toBeInTheDocument();
    await user.type(within(queueDialog).getByLabelText(/Target Branch/i), 'release/v1');
    const idemInput = within(queueDialog).getByLabelText(/Idempotency Key/i) as HTMLInputElement;
    expect(idemInput.value).toMatch(/^cf-run-/);

    await user.click(within(queueDialog).getByRole('button', { name: /Confirm Queue Run/i }));
    await waitFor(() => {
      expect(
        screen.getByText(
          /Queued pipeline run #2 for "Production Release DAG" — awaiting execution infrastructure/i
        )
      ).toBeInTheDocument();
    });
    expect(capturedHeaders).toHaveLength(1);
    expect(capturedHeaders[0]['X-XSRF-TOKEN']).toBe('csrf-orch-token');
    expect(capturedHeaders[0]['Idempotency-Key']).toMatch(/^cf-run-/);
  }, 15000);

  it('inspects run stage states and transition event history, and cancels a queued run after confirmation', async () => {
    const user = userEvent.setup();
    mockOrchestrationApi();

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);

    const runsTable = screen.getByRole('table', { name: 'CloudForge pipeline runs' });
    await user.click(within(runsTable).getByRole('button', { name: /Inspect/i }));

    const detailsDialog = await screen.findByRole('dialog');
    expect(within(detailsDialog).getByText('Production Release DAG #1')).toBeInTheDocument();
    expect(within(detailsDialog).getByText('Ready (Awaiting Worker)')).toBeInTheDocument();
    expect(
      within(detailsDialog).getAllByText('Pending (Waiting on Predecessors)').length
    ).toBe(2);
    await waitFor(() => {
      expect(within(detailsDialog).getByText(/RUN_QUEUED/i)).toBeInTheDocument();
      expect(within(detailsDialog).getByText(/STAGE_READY/i)).toBeInTheDocument();
    });

    await user.click(within(detailsDialog).getByRole('button', { name: /^Close$/i }));
    await user.click(within(runsTable).getByRole('button', { name: /^Cancel$/i }));

    const cancelDialog = await screen.findByRole('dialog');
    await user.type(within(cancelDialog).getByLabelText(/Cancellation Reason/i), 'Superseded');
    await user.click(within(cancelDialog).getByRole('button', { name: /Confirm Cancel Run/i }));

    await waitFor(() => {
      expect(
        screen.getByText(/Cancelled orchestration run #1 for "Production Release DAG"/i)
      ).toBeInTheDocument();
    });
    expect(within(runsTable).getByText('Cancelled')).toBeInTheDocument();
  }, 15000);

  it('enforces RBAC UI restrictions for VIEWER and DEVELOPER roles', async () => {
    const user = userEvent.setup();

    mockOrchestrationApi({ role: 'VIEWER' });
    const viewerRender = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);
    expect(screen.getByRole('button', { name: /New Pipeline Definition/i })).toBeDisabled();

    const defsTable = screen.getByRole('table', { name: 'CloudForge pipeline definitions' });
    expect(within(defsTable).getByRole('button', { name: /Queue Run/i })).toBeDisabled();
    expect(within(defsTable).queryByRole('button', { name: /^Edit$/i })).not.toBeInTheDocument();

    const runsTable = screen.getByRole('table', { name: 'CloudForge pipeline runs' });
    expect(within(runsTable).queryByRole('button', { name: /^Cancel$/i })).not.toBeInTheDocument();
    expect(within(runsTable).getByRole('button', { name: /Inspect/i })).toBeEnabled();

    viewerRender.unmount();
    vi.restoreAllMocks();

    mockOrchestrationApi({ role: 'DEVELOPER' });
    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);
    expect(screen.getByRole('button', { name: /New Pipeline Definition/i })).toBeDisabled();
    const devDefsTable = screen.getByRole('table', { name: 'CloudForge pipeline definitions' });
    expect(within(devDefsTable).getByRole('button', { name: /Queue Run/i })).toBeEnabled();
    expect(within(devDefsTable).queryByRole('button', { name: /^Edit$/i })).not.toBeInTheDocument();

    const devRunsTable = screen.getByRole('table', { name: 'CloudForge pipeline runs' });
    expect(within(devRunsTable).getByRole('button', { name: /^Cancel$/i })).toBeEnabled();
  });

  it('renders clear empty states, backend error state with retry, and preserves theme & responsive shell controls', async () => {
    const user = userEvent.setup();
    localStorage.setItem(
      'cloudforge.appearance.v1',
      JSON.stringify({
        theme: 'dark',
        accent: 'violet',
        density: 'comfortable',
        motion: 'reduced',
        sidebar: 'expanded',
      })
    );

    mockOrchestrationApi({ projects: [], definitions: [], runs: [] });
    const emptyRender = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);
    expect(
      screen.getByText(
        /Create a project on the Projects page before creating a CloudForge pipeline definition/i
      )
    ).toBeInTheDocument();
    expect(screen.getByText(/No CloudForge pipeline runs queued yet/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New Pipeline Definition/i })).toBeDisabled();

    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.dataset.accent).toBe('violet');
    expect(document.documentElement.dataset.motion).toBe('reduced');
    expect(screen.getByLabelText('Open navigation menu')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Collapse sidebar/i })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Color theme'), 'light');
    expect(document.documentElement.dataset.theme).toBe('light');

    emptyRender.unmount();
    vi.restoreAllMocks();

    mockOrchestrationApi({
      definitionsError: jsonResponse(
        {
          status: 503,
          error: 'Service Unavailable',
          message: 'Orchestration database temporarily unavailable.',
        },
        503
      ),
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);
    await waitFor(() => {
      expect(screen.getByText('CloudForge pipeline orchestration unavailable')).toBeInTheDocument();
    });
    expect(
      screen.getByText('Orchestration database temporarily unavailable.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/i })).toBeInTheDocument();
  });

  it('displays live Phase 3.3 isolated worker pool telemetry, unsupported capability notes, attempts, and safe sanitized logs', async () => {
    const user = userEvent.setup();

    const executedRun: PipelineRun = {
      ...structuredClone(SAMPLE_RUN_QUEUED),
      id: 'run-exec-33',
      runNumber: 5,
      status: 'SUCCEEDED',
      statusReason: 'All required pipeline stages completed successfully',
      startedAt: '2026-11-24T10:10:00Z',
      completedAt: '2026-11-24T10:10:12Z',
      stages: [
        {
          ...structuredClone(SAMPLE_RUN_QUEUED.stages[0]),
          id: 'st-exec-1',
          stageKey: 'source',
          status: 'SUCCEEDED',
          attemptNumber: 1,
          allowedOperation: 'ENV_VALIDATION',
          executionSupported: true,
          capabilityMessage: null,
          assignedWorkerId: 'local-docker-worker-1',
          startedAt: '2026-11-24T10:10:01Z',
          completedAt: '2026-11-24T10:10:05Z',
        },
        {
          ...structuredClone(SAMPLE_RUN_QUEUED.stages[1]),
          id: 'st-exec-2',
          stageKey: 'build',
          status: 'READY',
          attemptNumber: 0,
          allowedOperation: null,
          executionSupported: false,
          capabilityMessage:
            'Unsupported in Phase 3.3 — arbitrary repository container builds require hardened sandbox isolation.',
          assignedWorkerId: null,
        },
      ],
    };

    mockOrchestrationApi({
      runs: [executedRun],
      workerPool: {
        workerPlaneEnabled: true,
        schedulerEnabled: true,
        registeredWorkers: 2,
        availableWorkers: 1,
        busyWorkers: 1,
        drainingWorkers: 0,
        offlineWorkers: 0,
        activeAssignments: 1,
        workspaceReadyStages: 1,
        workspaceRunningStages: 0,
        workspaceFailedAttempts: 0,
        supportedOperations: ['ENV_VALIDATION', 'CONTROLLED_SMOKE_TEST', 'WORKER_DIAGNOSTICS'],
        workers: [
          {
            workerId: 'local-docker-worker-1',
            displayName: 'Local Docker Worker',
            adapterType: 'DOCKER_ISOLATED',
            status: 'AVAILABLE',
            supportedOperations: ['ENV_VALIDATION', 'CONTROLLED_SMOKE_TEST'],
            maxConcurrency: 1,
            activeAssignments: 0,
            hostLabel: 'dev-host',
            agentVersion: '0.3.3-phase3.3',
            registeredAt: '2026-11-24T10:00:00Z',
            lastHeartbeatAt: '2026-11-24T10:10:10Z',
            offlineAt: null,
          },
        ],
        sampledAt: '2026-11-24T10:10:12Z',
      },
      attemptsByStageId: {
        'st-exec-1': [
          {
            id: 'att-1',
            stageRunId: 'st-exec-1',
            workerId: 'local-docker-worker-1',
            attemptNumber: 1,
            fencingGeneration: 1,
            allowedOperation: 'ENV_VALIDATION',
            status: 'SUCCEEDED',
            failureCategory: 'NONE',
            exitCode: 0,
            summary: 'Allowlisted operation ENV_VALIDATION succeeded in isolated container',
            resourceUsage: { cpuLimit: '0.5', memoryLimit: '128m' },
            leaseStartedAt: '2026-11-24T10:10:00Z',
            leaseExpiresAt: '2026-11-24T10:11:00Z',
            lastHeartbeatAt: '2026-11-24T10:10:05Z',
            startedAt: '2026-11-24T10:10:01Z',
            completedAt: '2026-11-24T10:10:05Z',
            cancellationRequestedAt: null,
            terminationConfirmed: true,
            recoveredAt: null,
          },
        ],
      },
      logsByStageId: {
        'st-exec-1': [
          {
            id: 'log-1',
            attemptId: 'att-1',
            attemptNumber: 1,
            workerId: 'local-docker-worker-1',
            sequenceNumber: 1,
            streamType: 'STDOUT',
            message: '[ENV_VALIDATION] uid=65534 Read-only root filesystem verified <script>alert(1)</script>',
            truncated: false,
            occurredAt: '2026-11-24T10:10:02Z',
          },
        ],
      },
    });

    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);

    // Worker pool summary card displays real persisted counts and registered worker node
    expect(screen.getByText('Isolated Worker Execution Plane')).toBeInTheDocument();
    expect(screen.getByTestId('worker-registered-count')).toHaveTextContent('2');
    expect(screen.getByTestId('worker-available-busy')).toHaveTextContent('1 / 1');
    expect(screen.getByText('local-docker-worker-1')).toBeInTheDocument();

    // Open run details modal and inspect stage capability boundary + attempts & sanitized logs
    const runsTable = screen.getByRole('table', { name: 'CloudForge pipeline runs' });
    await user.click(within(runsTable).getByRole('button', { name: /Inspect/i }));

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(/Unsupported in Phase 3\.3 — arbitrary repository container builds/i)
    ).toBeInTheDocument();

    const logButtons = within(dialog).getAllByRole('button', { name: /View Attempts & Logs/i });
    await user.click(logButtons[0]);

    const drawer = await within(dialog).findByTestId('stage-exec-drawer-source');
    await waitFor(() => {
      expect(within(drawer).getByText(/Execution Attempts \(1\)/i)).toBeInTheDocument();
      expect(within(drawer).getByText(/Sanitized Execution Logs \(1\)/i)).toBeInTheDocument();
    });
    // Safe text rendering: HTML script tags are rendered literally, never executed as DOM <script>
    expect(
      within(drawer).getByText(/uid=65534 Read-only root filesystem verified <script>alert\(1\)<\/script>/i)
    ).toBeInTheDocument();
    expect(drawer.querySelector('script')).toBeNull();
  }, 15000);

  it('supports owner-only secret bindings selector on approved stages and blocks DEVELOPER queueing of secret-bearing pipelines', async () => {
    const user = userEvent.setup();
    const secretBoundDef: PipelineDefinition = {
      ...SAMPLE_DEFINITIONS[0],
      id: 'def-sec-1',
      name: 'Secret Verified Pipeline',
      targetEnvironmentId: 'env-stg',
      targetEnvironmentName: 'Staging',
      targetEnvironmentType: 'STAGING',
      hasSecretBindings: true,
      stages: [
        {
          id: 'st-sec-1',
          stageKey: 'source',
          displayName: 'Source Validation',
          stageType: 'SOURCE_PREP',
          executionOrder: 0,
          dependsOn: [],
          timeoutSeconds: 300,
          maxRetries: 0,
          allowFailure: false,
          metadata: { operation: 'ENV_VALIDATION' },
          secretBindings: [
            {
              secretId: 'sec-stg-1',
              secretName: 'STAGING_DEPLOY_KEY',
              deliveryAlias: 'RUNTIME_API_KEY',
              environmentId: 'env-stg',
              pinnedVersionNumber: null,
            },
          ],
        },
      ],
    };

    const capturedPayloads: unknown[] = [];
    mockOrchestrationApi({
      role: 'OWNER',
      definitions: [secretBoundDef],
      onCreateDefinition: (body) => {
        capturedPayloads.push(body);
        return jsonResponse(secretBoundDef, 201);
      },
    });

    const { unmount } = render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await openCloudForgePipelinesTab(user);
    expect(screen.getByText('Staging Secrets')).toBeInTheDocument();

    // Open New Pipeline Definition modal and bind an authorized environment secret reference
    await user.click(screen.getByRole('button', { name: /New Pipeline Definition/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Pipeline Name/i), 'Secret Bound Pipeline');
    await user.selectOptions(within(dialog).getByLabelText(/Target Secret Environment/i), 'env-stg');

    const bindButtons = await within(dialog).findAllByRole('button', { name: /Bind Secret Reference/i });
    await user.click(bindButtons[0]);
    expect(await within(dialog).findByLabelText(/Bound secret 1/i)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Create Pipeline Definition/i }));
    await waitFor(() => {
      expect(capturedPayloads).toHaveLength(1);
    });
    const sent = capturedPayloads[0] as { targetEnvironmentId?: string; stages: Array<{ secretBindings?: unknown[] }> };
    expect(sent.targetEnvironmentId).toBe('env-stg');
    expect(sent.stages[0].secretBindings?.length).toBe(1);

    unmount();
    vi.restoreAllMocks();

    // DEVELOPER cannot queue a secret-bearing pipeline
    mockOrchestrationApi({ role: 'DEVELOPER', definitions: [secretBoundDef] });
    render(
      <MemoryRouter initialEntries={['/pipelines']}>
        <AppRoutes />
      </MemoryRouter>
    );
    await openCloudForgePipelinesTab(user);
    const defsTable = screen.getByRole('table', { name: 'CloudForge pipeline definitions' });
    const queueBtn = within(defsTable).getByRole('button', { name: /Queue Run/i });
    expect(queueBtn).toBeDisabled();
  }, 15000);
});
