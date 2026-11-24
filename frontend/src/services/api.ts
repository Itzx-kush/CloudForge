import type { BackendHealthPayload, HealthProbeSnapshot } from '../types/health';
import type {
  ApiErrorPayload,
  AuditEvent,
  AuthConfig,
  AuthMePayload,
  CreateProjectInput,
  GitHubActionsSyncResponse,
  GitHubInstallUrlResponse,
  GitHubInstallationsResponse,
  GitHubSyncResponse,
  GitHubWorkflow,
  GitHubWorkflowRun,
  LinkedRepository,
  PagedResponse,
  PipelineOverview,
  ProjectDetail,
  ProjectEnvironment,
  ProjectStatus,
  ProjectSummary,
  RepositoryConnectionStatus,
  UpdateEnvironmentInput,
  UpdateProjectInput,
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
    signal?: AbortSignal;
  } = {}
): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = {
    Accept: 'application/json',
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

export { HEALTH_ENDPOINT };
