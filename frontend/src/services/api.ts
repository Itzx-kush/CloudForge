import type { BackendHealthPayload, HealthProbeSnapshot } from '../types/health';
import type {
  ApiErrorPayload,
  AuditEvent,
  AuthConfig,
  AuthMePayload,
  CreateClusterConnectionInput,
  CreateDeploymentInput,
  CreateEnvironmentSecretInput,
  CreateNamespaceBindingInput,
  CreatePipelineDefinitionInput,
  CreatePipelineRunInput,
  CreateProjectInput,
  CreateRegistryConnectionInput,
  EnvironmentSecretDetail,
  EnvironmentSecretSummary,
  EnvironmentSecretVersion,
  GitHubActionsSyncResponse,
  GitHubInstallUrlResponse,
  GitHubInstallationsResponse,
  GitHubSyncResponse,
  GitHubWorkflow,
  GitHubWorkflowRun,
  KubernetesClusterConnection,
  KubernetesDeploymentConfig,
  KubernetesDeploymentEvent,
  KubernetesDeploymentOperation,
  KubernetesDeploymentStatus,
  KubernetesDeploymentStatusSnapshot,
  KubernetesRelease,
  ReleaseComparison,
  RollbackPreview,
  KubernetesNamespaceBinding,
  KubernetesSubsystemStatus,
  LinkedRepository,
  OciArtifactVerificationStatus,
  OciBuildRecord,
  OciBuildStatus,
  OciImageArtifact,
  OciImageArtifactDetail,
  OciRegistryConnection,
  OciSubsystemStatus,
  PagedResponse,
  PipelineDefinition,
  PipelineOverview,
  PipelineRun,
  PipelineRunArtifacts,
  PipelineRunEvent,
  PipelineRunStatus,
  ProjectDetail,
  ProjectEnvironment,
  ProjectStatus,
  ProjectSummary,
  RepositoryConnectionStatus,
  RevokeEnvironmentSecretInput,
  RotateEnvironmentSecretInput,
  SecretLifecycleStatus,
  SecretSubsystemStatus,
  StageExecutionAttempt,
  StageExecutionLog,
  UpdateEnvironmentInput,
  UpdatePipelineDefinitionInput,
  UpdateProjectInput,
  WorkerPoolStatus,
  WorkflowRunJobsResponse,
  WorkflowRunNormalizedStatus,
  WorkspaceMember,
  WorkspaceRole,
  WorkspaceSummary,
} from '../types/platform';

const HEALTH_ENDPOINT = '/api/health';
const DEFAULT_TIMEOUT_MS = 6000;
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export class ApiClientError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;
  readonly requestId: string | null;
  readonly retryAfterSeconds: number | null;

  constructor(
    status: number,
    message: string,
    fieldErrors: Record<string, string> = {},
    requestId: string | null = null,
    retryAfterSeconds: number | null = null
  ) {
    super(message);
    this.name = 'ApiClientError';
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.requestId = requestId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined' || !document.cookie) {
    return null;
  }
  const encodedName = `${encodeURIComponent(name)}=`;
  const parts = document.cookie.split(';');
  for (const part of parts) {
    const trimmed = part.trim();
    if (trimmed.startsWith(encodedName)) {
      return decodeURIComponent(trimmed.substring(encodedName.length));
    }
  }
  return null;
}

async function ensureCsrfToken(): Promise<string | null> {
  const existing = readCookie('XSRF-TOKEN');
  if (existing) {
    return existing;
  }
  try {
    const response = await fetch('/api/auth/csrf', {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      return readCookie('XSRF-TOKEN');
    }
    const data = (await response.json()) as { token?: string };
    return readCookie('XSRF-TOKEN') ?? data.token ?? null;
  } catch {
    return null;
  }
}

async function apiRequest<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    headers?: Record<string, string>;
    signal?: AbortSignal;
  } = {}
): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(options.headers ?? {}),
  };

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  if (MUTATING_METHODS.has(method)) {
    const csrfToken = await ensureCsrfToken();
    if (csrfToken) {
      headers['X-XSRF-TOKEN'] = csrfToken;
    }
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'include',
      cache: 'no-store',
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch {
    throw new ApiClientError(
      0,
      'Unable to reach the CloudForge backend API. Verify that Spring Boot is running and retry.'
    );
  }

  const requestId = response.headers.get('X-Request-Id');

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get('content-type') ?? '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    const retryAfterHeader = response.headers.get('Retry-After');
    const parsedRetryAfter = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : Number.NaN;
    const retryAfterSeconds = Number.isFinite(parsedRetryAfter) && parsedRetryAfter > 0 ? parsedRetryAfter : null;

    if (isJson) {
      const payload = (await response.json()) as Partial<ApiErrorPayload>;
      throw new ApiClientError(
        response.status,
        payload.message || `Request failed with HTTP ${response.status}`,
        payload.fieldErrors ?? {},
        payload.requestId ?? requestId,
        retryAfterSeconds
      );
    }
    throw new ApiClientError(
      response.status,
      `Request failed with HTTP ${response.status}`,
      {},
      requestId,
      retryAfterSeconds
    );
  }

  if (!isJson) {
    throw new ApiClientError(
      response.status,
      `Unexpected non-JSON response from ${path}`,
      {},
      requestId
    );
  }

  return (await response.json()) as T;
}

export async function fetchBackendHealth(
  signal?: AbortSignal
): Promise<HealthProbeSnapshot> {
  const startedAt = performance.now();
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);

  const onExternalAbort = () => controller.abort();
  signal?.addEventListener('abort', onExternalAbort, { once: true });

  try {
    const response = await fetch(HEALTH_ENDPOINT, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
      cache: 'no-store',
      signal: controller.signal,
    });

    const clientLatencyMs = Math.max(0, Math.round(performance.now() - startedAt));
    const requestId = response.headers.get('X-Request-Id');
    const checkedAt = new Date().toISOString();

    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('application/json')) {
      return {
        payload: null,
        httpStatus: response.status,
        requestId,
        clientLatencyMs,
        checkedAt,
        networkError: `Backend returned non-JSON response (HTTP ${response.status}). Ensure Spring Boot is running on the configured proxy target.`,
      };
    }

    const payload = (await response.json()) as BackendHealthPayload;
    return {
      payload,
      httpStatus: response.status,
      requestId,
      clientLatencyMs,
      checkedAt,
      networkError: null,
    };
  } catch (error) {
    const clientLatencyMs = Math.max(0, Math.round(performance.now() - startedAt));
    const isAbort = error instanceof DOMException && error.name === 'AbortError';

    return {
      payload: null,
      httpStatus: null,
      requestId: null,
      clientLatencyMs,
      checkedAt: new Date().toISOString(),
      networkError: isAbort
        ? 'Health check timed out while contacting /api/health. Verify that the Spring Boot backend is running.'
        : 'Unable to reach the Spring Boot backend via /api/health. Start the backend with Docker Compose or Maven and retry.',
    };
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener('abort', onExternalAbort);
  }
}

// --- Auth API ---

export function fetchAuthConfig(): Promise<AuthConfig> {
  return apiRequest<AuthConfig>('/api/auth/config');
}

export function fetchCurrentUser(): Promise<AuthMePayload> {
  return apiRequest<AuthMePayload>('/api/auth/me');
}

export function logoutSession(): Promise<{ loggedOut: boolean; message: string }> {
  return apiRequest<{ loggedOut: boolean; message: string }>('/api/auth/logout', {
    method: 'POST',
  });
}

// --- Workspaces & RBAC API ---

export function fetchWorkspaces(): Promise<WorkspaceSummary[]> {
  return apiRequest<WorkspaceSummary[]>('/api/workspaces');
}

export function createWorkspace(input: {
  name: string;
  description?: string;
}): Promise<WorkspaceSummary> {
  return apiRequest<WorkspaceSummary>('/api/workspaces', {
    method: 'POST',
    body: input,
  });
}

export function updateWorkspace(
  workspaceId: string,
  input: { name: string; description?: string }
): Promise<WorkspaceSummary> {
  return apiRequest<WorkspaceSummary>(`/api/workspaces/${workspaceId}`, {
    method: 'PATCH',
    body: input,
  });
}

export function fetchWorkspaceMembers(workspaceId: string): Promise<WorkspaceMember[]> {
  return apiRequest<WorkspaceMember[]>(`/api/workspaces/${workspaceId}/members`);
}

export function addWorkspaceMember(
  workspaceId: string,
  input: { username: string; role: WorkspaceRole }
): Promise<WorkspaceMember> {
  return apiRequest<WorkspaceMember>(`/api/workspaces/${workspaceId}/members`, {
    method: 'POST',
    body: input,
  });
}

export function updateWorkspaceMemberRole(
  workspaceId: string,
  memberId: string,
  role: WorkspaceRole
): Promise<WorkspaceMember> {
  return apiRequest<WorkspaceMember>(
    `/api/workspaces/${workspaceId}/members/${memberId}`,
    {
      method: 'PATCH',
      body: { role },
    }
  );
}

export function removeWorkspaceMember(
  workspaceId: string,
  memberId: string
): Promise<void> {
  return apiRequest<void>(`/api/workspaces/${workspaceId}/members/${memberId}`, {
    method: 'DELETE',
  });
}

// --- Projects & Environments API ---

export function fetchProjects(
  workspaceId: string,
  params: { q?: string; status?: ProjectStatus | ''; page?: number; size?: number } = {}
): Promise<PagedResponse<ProjectSummary>> {
  const search = new URLSearchParams();
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.status) search.set('status', params.status);
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<ProjectSummary>>(
    `/api/workspaces/${workspaceId}/projects${qs ? `?${qs}` : ''}`
  );
}

export function fetchProjectDetail(
  workspaceId: string,
  projectId: string
): Promise<ProjectDetail> {
  return apiRequest<ProjectDetail>(`/api/workspaces/${workspaceId}/projects/${projectId}`);
}

export function createProject(
  workspaceId: string,
  input: CreateProjectInput
): Promise<ProjectDetail> {
  return apiRequest<ProjectDetail>(`/api/workspaces/${workspaceId}/projects`, {
    method: 'POST',
    body: input,
  });
}

export function updateProject(
  workspaceId: string,
  projectId: string,
  input: UpdateProjectInput
): Promise<ProjectDetail> {
  return apiRequest<ProjectDetail>(`/api/workspaces/${workspaceId}/projects/${projectId}`, {
    method: 'PUT',
    body: input,
  });
}

export function deleteProject(workspaceId: string, projectId: string): Promise<void> {
  return apiRequest<void>(`/api/workspaces/${workspaceId}/projects/${projectId}`, {
    method: 'DELETE',
  });
}

export function updateProjectEnvironment(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  input: UpdateEnvironmentInput
): Promise<ProjectEnvironment> {
  return apiRequest<ProjectEnvironment>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}`,
    {
      method: 'PUT',
      body: input,
    }
  );
}

// --- GitHub App & Repositories API ---

export function fetchGitHubInstallations(
  workspaceId: string
): Promise<GitHubInstallationsResponse> {
  return apiRequest<GitHubInstallationsResponse>(
    `/api/workspaces/${workspaceId}/github/installations`
  );
}

export function createGitHubInstallUrl(
  workspaceId: string
): Promise<GitHubInstallUrlResponse> {
  return apiRequest<GitHubInstallUrlResponse>(
    `/api/workspaces/${workspaceId}/github/install-url`,
    { method: 'POST' }
  );
}

export function syncGitHubInstallation(
  workspaceId: string,
  installationId: string
): Promise<GitHubSyncResponse> {
  return apiRequest<GitHubSyncResponse>(
    `/api/workspaces/${workspaceId}/github/installations/${installationId}/sync`,
    { method: 'POST' }
  );
}

export function disconnectGitHubInstallation(
  workspaceId: string,
  installationId: string
): Promise<void> {
  return apiRequest<void>(
    `/api/workspaces/${workspaceId}/github/installations/${installationId}`,
    { method: 'DELETE' }
  );
}

export function fetchRepositories(
  workspaceId: string,
  params: {
    q?: string;
    visibility?: string;
    status?: RepositoryConnectionStatus | '';
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<LinkedRepository>> {
  const search = new URLSearchParams();
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.visibility) search.set('visibility', params.visibility);
  if (params.status) search.set('status', params.status);
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<LinkedRepository>>(
    `/api/workspaces/${workspaceId}/repositories${qs ? `?${qs}` : ''}`
  );
}

// --- Phase 3.1: GitHub Actions Pipelines API ---

export function fetchPipelineOverview(workspaceId: string): Promise<PipelineOverview> {
  return apiRequest<PipelineOverview>(`/api/workspaces/${workspaceId}/pipelines`);
}

export function fetchRepositoryWorkflows(
  workspaceId: string,
  repositoryId: string,
  params: { q?: string; state?: string; page?: number; size?: number } = {}
): Promise<PagedResponse<GitHubWorkflow>> {
  const search = new URLSearchParams();
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.state && params.state.trim()) search.set('state', params.state.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<GitHubWorkflow>>(
    `/api/workspaces/${workspaceId}/repositories/${repositoryId}/workflows${qs ? `?${qs}` : ''}`
  );
}

export function fetchRepositoryWorkflowRuns(
  workspaceId: string,
  repositoryId: string,
  params: {
    workflowId?: string;
    status?: WorkflowRunNormalizedStatus | '';
    branch?: string;
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<GitHubWorkflowRun>> {
  const search = new URLSearchParams();
  if (params.workflowId) search.set('workflowId', params.workflowId);
  if (params.status) search.set('status', params.status);
  if (params.branch && params.branch.trim()) search.set('branch', params.branch.trim());
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<GitHubWorkflowRun>>(
    `/api/workspaces/${workspaceId}/repositories/${repositoryId}/workflow-runs${qs ? `?${qs}` : ''}`
  );
}

export function fetchWorkspaceWorkflowRuns(
  workspaceId: string,
  params: {
    repositoryId?: string;
    workflowId?: string;
    status?: WorkflowRunNormalizedStatus | '';
    branch?: string;
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<GitHubWorkflowRun>> {
  const search = new URLSearchParams();
  if (params.repositoryId) search.set('repositoryId', params.repositoryId);
  if (params.workflowId) search.set('workflowId', params.workflowId);
  if (params.status) search.set('status', params.status);
  if (params.branch && params.branch.trim()) search.set('branch', params.branch.trim());
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<GitHubWorkflowRun>>(
    `/api/workspaces/${workspaceId}/workflow-runs${qs ? `?${qs}` : ''}`
  );
}

export function fetchWorkflowRunDetail(
  workspaceId: string,
  runId: string
): Promise<GitHubWorkflowRun> {
  return apiRequest<GitHubWorkflowRun>(
    `/api/workspaces/${workspaceId}/workflow-runs/${runId}`
  );
}

export function fetchWorkflowRunJobs(
  workspaceId: string,
  runId: string
): Promise<WorkflowRunJobsResponse> {
  return apiRequest<WorkflowRunJobsResponse>(
    `/api/workspaces/${workspaceId}/workflow-runs/${runId}/jobs`
  );
}

export function syncRepositoryWorkflows(
  workspaceId: string,
  repositoryId: string
): Promise<GitHubActionsSyncResponse> {
  return apiRequest<GitHubActionsSyncResponse>(
    `/api/workspaces/${workspaceId}/repositories/${repositoryId}/workflows/sync`,
    { method: 'POST' }
  );
}

// --- Phase 3.2: CloudForge Pipeline Orchestration API ---

export function fetchPipelineDefinitions(
  workspaceId: string,
  params: { projectId?: string; enabled?: boolean | ''; q?: string; page?: number; size?: number } = {}
): Promise<PagedResponse<PipelineDefinition>> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.enabled !== undefined && params.enabled !== '') {
    search.set('enabled', String(params.enabled));
  }
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<PipelineDefinition>>(
    `/api/workspaces/${workspaceId}/pipelines/definitions${qs ? `?${qs}` : ''}`
  );
}

export function fetchPipelineDefinition(
  workspaceId: string,
  definitionId: string
): Promise<PipelineDefinition> {
  return apiRequest<PipelineDefinition>(
    `/api/workspaces/${workspaceId}/pipelines/definitions/${definitionId}`
  );
}

export function createPipelineDefinition(
  workspaceId: string,
  input: CreatePipelineDefinitionInput
): Promise<PipelineDefinition> {
  return apiRequest<PipelineDefinition>(
    `/api/workspaces/${workspaceId}/pipelines/definitions`,
    { method: 'POST', body: input }
  );
}

export function updatePipelineDefinition(
  workspaceId: string,
  definitionId: string,
  input: UpdatePipelineDefinitionInput
): Promise<PipelineDefinition> {
  return apiRequest<PipelineDefinition>(
    `/api/workspaces/${workspaceId}/pipelines/definitions/${definitionId}`,
    { method: 'PUT', body: input }
  );
}

export function fetchPipelineRuns(
  workspaceId: string,
  params: {
    projectId?: string;
    definitionId?: string;
    status?: PipelineRunStatus | '';
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<PipelineRun>> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.definitionId) search.set('definitionId', params.definitionId);
  if (params.status) search.set('status', params.status);
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<PipelineRun>>(
    `/api/workspaces/${workspaceId}/pipelines/runs${qs ? `?${qs}` : ''}`
  );
}

export function fetchPipelineRun(workspaceId: string, runId: string): Promise<PipelineRun> {
  return apiRequest<PipelineRun>(`/api/workspaces/${workspaceId}/pipelines/runs/${runId}`);
}

export function createPipelineRun(
  workspaceId: string,
  input: CreatePipelineRunInput,
  idempotencyKey?: string
): Promise<PipelineRun> {
  const headers: Record<string, string> = {};
  if (idempotencyKey && idempotencyKey.trim()) {
    headers['Idempotency-Key'] = idempotencyKey.trim();
  }
  return apiRequest<PipelineRun>(`/api/workspaces/${workspaceId}/pipelines/runs`, {
    method: 'POST',
    body: input,
    headers,
  });
}

export function cancelPipelineRun(
  workspaceId: string,
  runId: string,
  reason?: string
): Promise<PipelineRun> {
  return apiRequest<PipelineRun>(
    `/api/workspaces/${workspaceId}/pipelines/runs/${runId}/cancel`,
    {
      method: 'POST',
      body: reason && reason.trim() ? { reason: reason.trim() } : {},
    }
  );
}

export function fetchPipelineRunEvents(
  workspaceId: string,
  runId: string
): Promise<PipelineRunEvent[]> {
  return apiRequest<PipelineRunEvent[]>(
    `/api/workspaces/${workspaceId}/pipelines/runs/${runId}/events`
  );
}

// --- Phase 3.3: Isolated Worker Telemetry, Attempts & Sanitized Execution Logs ---

export function fetchWorkerPoolStatus(workspaceId: string): Promise<WorkerPoolStatus> {
  return apiRequest<WorkerPoolStatus>(
    `/api/workspaces/${workspaceId}/pipelines/workers/status`
  );
}

export function fetchStageExecutionAttempts(
  workspaceId: string,
  runId: string,
  stageRunId: string
): Promise<StageExecutionAttempt[]> {
  return apiRequest<StageExecutionAttempt[]>(
    `/api/workspaces/${workspaceId}/pipelines/runs/${runId}/stages/${stageRunId}/attempts`
  );
}

export function fetchStageExecutionLogs(
  workspaceId: string,
  runId: string,
  stageRunId: string,
  params: { attemptNumber?: number; page?: number; size?: number } = {}
): Promise<PagedResponse<StageExecutionLog>> {
  const search = new URLSearchParams();
  if (params.attemptNumber !== undefined) {
    search.set('attemptNumber', String(params.attemptNumber));
  }
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<StageExecutionLog>>(
    `/api/workspaces/${workspaceId}/pipelines/runs/${runId}/stages/${stageRunId}/logs${qs ? `?${qs}` : ''}`
  );
}

// --- Phase 3.4: Encrypted Environment Secrets API (Write-Only, Metadata Responses) ---

export function fetchSecretSubsystemStatus(
  workspaceId: string
): Promise<SecretSubsystemStatus> {
  return apiRequest<SecretSubsystemStatus>(`/api/workspaces/${workspaceId}/secrets/status`);
}

export function fetchEnvironmentSecrets(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  params: { status?: SecretLifecycleStatus | ''; q?: string; page?: number; size?: number } = {}
): Promise<PagedResponse<EnvironmentSecretSummary>> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<EnvironmentSecretSummary>>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets${qs ? `?${qs}` : ''}`
  );
}

export function createEnvironmentSecret(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  payload: CreateEnvironmentSecretInput
): Promise<EnvironmentSecretSummary> {
  return apiRequest<EnvironmentSecretSummary>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets`,
    { method: 'POST', body: payload }
  );
}

export function fetchEnvironmentSecretDetail(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  secretId: string
): Promise<EnvironmentSecretDetail> {
  return apiRequest<EnvironmentSecretDetail>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets/${secretId}`
  );
}

export function fetchEnvironmentSecretVersions(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  secretId: string
): Promise<EnvironmentSecretVersion[]> {
  return apiRequest<EnvironmentSecretVersion[]>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets/${secretId}/versions`
  );
}

export function rotateEnvironmentSecret(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  secretId: string,
  payload: RotateEnvironmentSecretInput
): Promise<EnvironmentSecretSummary> {
  return apiRequest<EnvironmentSecretSummary>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets/${secretId}/rotate`,
    { method: 'POST', body: payload }
  );
}

export function revokeEnvironmentSecret(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  secretId: string,
  payload: RevokeEnvironmentSecretInput = {}
): Promise<EnvironmentSecretSummary> {
  return apiRequest<EnvironmentSecretSummary>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets/${secretId}/revoke`,
    { method: 'POST', body: payload }
  );
}

export function deleteEnvironmentSecret(
  workspaceId: string,
  projectId: string,
  environmentId: string,
  secretId: string
): Promise<void> {
  return apiRequest<void>(
    `/api/workspaces/${workspaceId}/projects/${projectId}/environments/${environmentId}/secrets/${secretId}`,
    { method: 'DELETE' }
  );
}

// --- Audit Events API ---

export function fetchAuditEvents(
  workspaceId: string,
  params: { action?: string; page?: number; size?: number } = {}
): Promise<PagedResponse<AuditEvent>> {
  const search = new URLSearchParams();
  if (params.action && params.action.trim()) search.set('action', params.action.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<AuditEvent>>(
    `/api/workspaces/${workspaceId}/audit-events${qs ? `?${qs}` : ''}`
  );
}

// --- Phase 3.5B: OCI Registries, Container Builds & Immutable Image Artifacts ---

export function fetchOciSubsystemStatus(workspaceId: string): Promise<OciSubsystemStatus> {
  return apiRequest<OciSubsystemStatus>(`/api/workspaces/${workspaceId}/oci/status`);
}

export function fetchOciRegistries(workspaceId: string): Promise<OciRegistryConnection[]> {
  return apiRequest<OciRegistryConnection[]>(`/api/workspaces/${workspaceId}/registries`);
}

export function createOciRegistry(
  workspaceId: string,
  input: CreateRegistryConnectionInput
): Promise<OciRegistryConnection> {
  return apiRequest<OciRegistryConnection>(`/api/workspaces/${workspaceId}/registries`, {
    method: 'POST',
    body: input,
  });
}

export function disableOciRegistry(
  workspaceId: string,
  registryId: string
): Promise<OciRegistryConnection> {
  return apiRequest<OciRegistryConnection>(
    `/api/workspaces/${workspaceId}/registries/${registryId}/disable`,
    { method: 'POST' }
  );
}

export function fetchOciBuilds(
  workspaceId: string,
  params: {
    projectId?: string;
    status?: OciBuildStatus | '';
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<OciBuildRecord>> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.status) search.set('status', params.status);
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<OciBuildRecord>>(
    `/api/workspaces/${workspaceId}/builds${qs ? `?${qs}` : ''}`
  );
}

export function fetchOciArtifacts(
  workspaceId: string,
  params: {
    projectId?: string;
    verificationStatus?: OciArtifactVerificationStatus | '';
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<OciImageArtifact>> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.verificationStatus) search.set('verificationStatus', params.verificationStatus);
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<OciImageArtifact>>(
    `/api/workspaces/${workspaceId}/artifacts${qs ? `?${qs}` : ''}`
  );
}

export function fetchOciArtifactDetail(
  workspaceId: string,
  artifactId: string
): Promise<OciImageArtifactDetail> {
  return apiRequest<OciImageArtifactDetail>(
    `/api/workspaces/${workspaceId}/artifacts/${artifactId}`
  );
}

export function fetchPipelineRunArtifacts(
  workspaceId: string,
  runId: string
): Promise<PipelineRunArtifacts> {
  return apiRequest<PipelineRunArtifacts>(
    `/api/workspaces/${workspaceId}/pipelines/runs/${runId}/artifacts`
  );
}

// --- Phase 3.6: Secure Kubernetes Deployment Engine API ---

export function fetchKubernetesSubsystemStatus(
  workspaceId: string
): Promise<KubernetesSubsystemStatus> {
  return apiRequest<KubernetesSubsystemStatus>(
    `/api/workspaces/${workspaceId}/kubernetes/status`
  );
}

export function fetchKubernetesClusters(
  workspaceId: string
): Promise<KubernetesClusterConnection[]> {
  return apiRequest<KubernetesClusterConnection[]>(
    `/api/workspaces/${workspaceId}/clusters`
  );
}

export function createKubernetesCluster(
  workspaceId: string,
  input: CreateClusterConnectionInput
): Promise<KubernetesClusterConnection> {
  return apiRequest<KubernetesClusterConnection>(
    `/api/workspaces/${workspaceId}/clusters`,
    { method: 'POST', body: input }
  );
}

export function validateKubernetesCluster(
  workspaceId: string,
  clusterId: string
): Promise<KubernetesClusterConnection> {
  return apiRequest<KubernetesClusterConnection>(
    `/api/workspaces/${workspaceId}/clusters/${clusterId}/validate`,
    { method: 'POST' }
  );
}

export function disableKubernetesCluster(
  workspaceId: string,
  clusterId: string
): Promise<KubernetesClusterConnection> {
  return apiRequest<KubernetesClusterConnection>(
    `/api/workspaces/${workspaceId}/clusters/${clusterId}/disable`,
    { method: 'POST' }
  );
}

export function fetchWorkspaceNamespaces(
  workspaceId: string
): Promise<KubernetesNamespaceBinding[]> {
  return apiRequest<KubernetesNamespaceBinding[]>(
    `/api/workspaces/${workspaceId}/namespaces`
  );
}

export function createNamespaceBinding(
  workspaceId: string,
  clusterId: string,
  input: CreateNamespaceBindingInput
): Promise<KubernetesNamespaceBinding> {
  return apiRequest<KubernetesNamespaceBinding>(
    `/api/workspaces/${workspaceId}/clusters/${clusterId}/namespaces`,
    { method: 'POST', body: input }
  );
}

export function fetchDeploymentConfigs(
  workspaceId: string
): Promise<KubernetesDeploymentConfig[]> {
  return apiRequest<KubernetesDeploymentConfig[]>(
    `/api/workspaces/${workspaceId}/deployment-configs`
  );
}

export function fetchKubernetesDeployments(
  workspaceId: string,
  params: {
    projectId?: string;
    environmentId?: string;
    clusterId?: string;
    status?: KubernetesDeploymentStatus | '';
    q?: string;
    page?: number;
    size?: number;
  } = {}
): Promise<PagedResponse<KubernetesDeploymentOperation>> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.environmentId) search.set('environmentId', params.environmentId);
  if (params.clusterId) search.set('clusterId', params.clusterId);
  if (params.status) search.set('status', params.status);
  if (params.q && params.q.trim()) search.set('q', params.q.trim());
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const qs = search.toString();
  return apiRequest<PagedResponse<KubernetesDeploymentOperation>>(
    `/api/workspaces/${workspaceId}/deployments${qs ? `?${qs}` : ''}`
  );
}

export function createKubernetesDeployment(
  workspaceId: string,
  input: CreateDeploymentInput,
  idempotencyKey?: string
): Promise<KubernetesDeploymentOperation> {
  const headers: Record<string, string> = {};
  if (idempotencyKey && idempotencyKey.trim()) {
    headers['Idempotency-Key'] = idempotencyKey.trim();
  }
  return apiRequest<KubernetesDeploymentOperation>(
    `/api/workspaces/${workspaceId}/deployments`,
    { method: 'POST', body: input, headers }
  );
}

export function fetchKubernetesDeploymentDetail(
  workspaceId: string,
  deploymentId: string
): Promise<KubernetesDeploymentOperation> {
  return apiRequest<KubernetesDeploymentOperation>(
    `/api/workspaces/${workspaceId}/deployments/${deploymentId}`
  );
}

export function fetchKubernetesDeploymentStatus(
  workspaceId: string,
  deploymentId: string
): Promise<KubernetesDeploymentStatusSnapshot> {
  return apiRequest<KubernetesDeploymentStatusSnapshot>(
    `/api/workspaces/${workspaceId}/deployments/${deploymentId}/status`
  );
}

export function fetchKubernetesDeploymentEvents(
  workspaceId: string,
  deploymentId: string
): Promise<KubernetesDeploymentEvent[]> {
  return apiRequest<KubernetesDeploymentEvent[]>(
    `/api/workspaces/${workspaceId}/deployments/${deploymentId}/events`
  );
}

export function cancelKubernetesDeployment(
  workspaceId: string,
  deploymentId: string,
  reason?: string
): Promise<KubernetesDeploymentOperation> {
  return apiRequest<KubernetesDeploymentOperation>(
    `/api/workspaces/${workspaceId}/deployments/${deploymentId}/cancel`,
    {
      method: 'POST',
      body: reason && reason.trim() ? { reason: reason.trim() } : {},
    }
  );
}

export { HEALTH_ENDPOINT };

export function fetchReleaseHistory(workspace: string, filters: Record<string, string> = {}) {
  return apiRequest<PagedResponse<KubernetesRelease>>(
    `/api/workspaces/${workspace}/releases?${new URLSearchParams(filters)}`
  );
}

export function fetchRelease(workspace: string, id: string) {
  return apiRequest<KubernetesRelease>(`/api/workspaces/${workspace}/releases/${id}`);
}

export function compareReleases(workspace: string, first: string, second: string) {
  return apiRequest<ReleaseComparison>(
    `/api/workspaces/${workspace}/releases/compare?${new URLSearchParams({ first, second })}`
  );
}

export function previewReleaseRollback(workspace: string, id: string) {
  return apiRequest<RollbackPreview>(`/api/workspaces/${workspace}/releases/${id}/rollback-preview`, {
    method: 'POST',
  });
}

export function requestReleaseRollback(
  workspace: string, id: string, expectedOperationId: string, reason: string, key: string
) {
  return apiRequest<KubernetesRelease>(`/api/workspaces/${workspace}/releases/${id}/rollback`, {
    method: 'POST',
    body: { expectedOperationId, reason, confirmed: true },
    headers: { 'Idempotency-Key': key },
  });
}

export function reconcileRelease(workspace: string, id: string, candidateReleaseId: string, key: string) {
  return apiRequest<KubernetesRelease>(`/api/workspaces/${workspace}/deployments/${id}/reconcile`, {
    method: 'POST',
    body: { candidateReleaseId },
    headers: { 'Idempotency-Key': key },
  });
}

export interface SecurityAssessment {
  artifact: string;
  assessedAt: number;
  critical: number;
  high: number;
  sbomSha256: string;
}

export function fetchSecurityAssessments(workspace: string) {
  return apiRequest<SecurityAssessment[]>(`/api/workspaces/${workspace}/security/assessments`);
}
