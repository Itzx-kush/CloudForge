import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type { AuthConfig } from '../types/platform';

function jsonResponse(body: unknown, status = 200, requestId = 'req-test-1'): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': requestId,
    },
  });
}

const HEALTH_UP = {
  application: 'CloudForge Backend',
  version: '0.2.0-phase2',
  phase: 'Phase 2 — Identity, GitHub Integration & Persistent Workspaces',
  status: 'UP',
  database: {
    status: 'UP',
    engine: 'PostgreSQL 17.4',
    responseTimeMs: 4,
    message: 'Database connection verified',
  },
  timestamp: '2026-11-23T06:00:00Z',
};

const AUTH_CONFIG_READY: AuthConfig = {
  githubOAuthConfigured: true,
  githubAppConfigured: true,
  githubAppSlug: 'cloudforge-dev',
  loginUrl: '/oauth2/authorization/github',
};

const AUTH_ME_OWNER = {
  user: {
    id: 'user-1',
    githubUserId: 1001,
    username: 'kush-dev',
    displayName: 'Kush Dev',
    email: 'kush@example.com',
    avatarUrl: null,
    profileUrl: 'https://github.com/kush-dev',
    lastLoginAt: '2026-11-23T06:00:00Z',
    createdAt: '2026-11-20T06:00:00Z',
  },
  workspaces: [
    {
      id: 'ws-1',
      name: "kush-dev's Workspace",
      slug: 'kush-dev',
      description: 'Personal workspace',
      personal: true,
      role: 'OWNER',
      memberCount: 2,
      projectCount: 1,
      connectedRepositoryCount: 1,
      createdAt: '2026-11-20T06:00:00Z',
      updatedAt: '2026-11-23T06:00:00Z',
    },
  ],
};

const SAMPLE_REPO = {
  id: 'repo-1',
  workspaceId: 'ws-1',
  installationId: 'inst-1',
  githubInstallationId: 9001,
  githubRepoId: 8801,
  ownerLogin: 'kush-dev',
  name: 'payments-service',
  fullName: 'kush-dev/payments-service',
  defaultBranch: 'main',
  visibility: 'private',
  htmlUrl: 'https://github.com/kush-dev/payments-service',
  description: 'Payments microservice',
  archived: false,
  connectionStatus: 'CONNECTED',
  githubUpdatedAt: '2026-11-22T12:00:00Z',
  lastSyncedAt: '2026-11-23T06:00:00Z',
};

const SAMPLE_PROJECT = {
  id: 'proj-1',
  workspaceId: 'ws-1',
  name: 'Payments Service',
  slug: 'payments-service',
  description: 'Core billing API',
  linkedRepository: SAMPLE_REPO,
  defaultBranch: 'main',
  status: 'ACTIVE',
  createdAt: '2026-11-21T06:00:00Z',
  updatedAt: '2026-11-23T06:00:00Z',
};

function createAuthenticatedFetchMock(overrides?: {
  health?: Response | (() => Promise<Response>);
  authConfig?: typeof AUTH_CONFIG_READY;
  projectsList?: Array<typeof SAMPLE_PROJECT>;
  reposList?: Array<typeof SAMPLE_REPO>;
  onPostProject?: (body: unknown) => Response;
  onLogout?: () => Response;
}) {
  const projects = [...(overrides?.projectsList ?? [SAMPLE_PROJECT])];
  const repos = [...(overrides?.reposList ?? [SAMPLE_REPO])];
  let loggedOut = false;

  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();

    if (url.endsWith('/security/assessments')) return jsonResponse([]);
    if (url.startsWith('/api/health')) {
      if (typeof overrides?.health === 'function') {
        return overrides.health();
      }
      return overrides?.health ?? jsonResponse(HEALTH_UP);
    }
    if (url.startsWith('/api/auth/config')) {
      return jsonResponse(overrides?.authConfig ?? AUTH_CONFIG_READY);
    }
    if (url.startsWith('/api/auth/csrf')) {
      return jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-token-test',
      });
    }
    if (url.startsWith('/api/auth/me')) {
      if (loggedOut) {
        return jsonResponse({ status: 401, error: 'Unauthorized', message: 'Not authenticated' }, 401);
      }
      return jsonResponse({
        ...AUTH_ME_OWNER,
        workspaces: [
          {
            ...AUTH_ME_OWNER.workspaces[0],
            projectCount: projects.length,
            connectedRepositoryCount: repos.length,
          },
        ],
      });
    }
    if (url.startsWith('/api/auth/logout') && method === 'POST') {
      loggedOut = true;
      return overrides?.onLogout
        ? overrides.onLogout()
        : jsonResponse({ loggedOut: true, message: 'Signed out' });
    }
    if (url.includes('/projects/') && method === 'DELETE') {
      const deletedId = url.split('/projects/')[1];
      const index = projects.findIndex((p) => p.id === deletedId);
      if (index >= 0) {
        projects.splice(index, 1);
      }
      return jsonResponse(null, 204);
    }
    if (url.includes('/projects') && method === 'GET') {
      return jsonResponse({
        items: projects,
        page: 0,
        size: 15,
        totalElements: projects.length,
        totalPages: 1,
      });
    }
    if (url.includes('/projects') && method === 'POST') {
      const body = init?.body ? JSON.parse(String(init.body)) : {};
      if (overrides?.onPostProject) {
        return overrides.onPostProject(body);
      }
      const created = {
        ...SAMPLE_PROJECT,
        id: `proj-${projects.length + 1}`,
        name: body.name,
        slug: String(body.name).toLowerCase().replace(/\s+/g, '-'),
        description: body.description ?? null,
        defaultBranch: body.defaultBranch ?? 'main',
        workspaceName: AUTH_ME_OWNER.workspaces[0].name,
        createdByUsername: 'kush-dev',
        environments: [],
      };
      projects.unshift(created);
      return jsonResponse(created, 201);
    }
    if (url.includes('/github/installations')) {
      return jsonResponse({
        githubAppConfigured: (overrides?.authConfig ?? AUTH_CONFIG_READY).githubAppConfigured,
        githubAppSlug: (overrides?.authConfig ?? AUTH_CONFIG_READY).githubAppSlug,
        installations: [
          {
            id: 'inst-1',
            workspaceId: 'ws-1',
            githubInstallationId: 9001,
            accountLogin: 'kush-dev',
            accountType: 'User',
            accountAvatarUrl: null,
            repositorySelection: 'selected',
            status: 'ACTIVE',
            lastSyncedAt: '2026-11-23T06:00:00Z',
            createdAt: '2026-11-20T06:00:00Z',
          },
        ],
      });
    }
    if (url.includes('/repositories')) {
      return jsonResponse({
        items: repos,
        page: 0,
        size: 20,
        totalElements: repos.length,
        totalPages: 1,
      });
    }
    if (url.includes('/members')) {
      return jsonResponse([
        {
          id: 'mem-1',
          userId: 'user-1',
          username: 'kush-dev',
          displayName: 'Kush Dev',
          email: 'kush@example.com',
          avatarUrl: null,
          role: 'OWNER',
          joinedAt: '2026-11-20T06:00:00Z',
        },
      ]);
    }
    if (url.includes('/audit-events')) {
      return jsonResponse({
        items: [
          {
            id: 'aud-1',
            workspaceId: 'ws-1',
            actorUsername: 'kush-dev',
            action: 'USER_LOGIN',
            resourceType: 'USER',
            resourceId: 'user-1',
            summary: 'Authenticated via GitHub OAuth as @kush-dev',
            metadata: {},
            requestId: 'req-1',
            createdAt: '2026-11-23T06:00:00Z',
          },
        ],
        page: 0,
        size: 15,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (url.includes('/kubernetes/status')) {
      return jsonResponse({
        kubernetesEnabled: false,
        allowLocalDevClusters: false,
        allowProductionDeployments: false,
        allowedApiHosts: [],
        maxReplicas: 20,
        maxDeploymentTimeoutSeconds: 600,
        configuredClusters: 0,
        validatedClusters: 0,
        activeNamespaceBindings: 0,
        totalDeployments: 0,
        runningDeployments: 0,
        succeededDeployments: 0,
        failedDeployments: 0,
        activeDeployWorkers: 0,
      });
    }
    if (url.includes('/clusters') || url.includes('/namespaces') || url.includes('/deployment-configs')) {
      return jsonResponse([]);
    }
    if (url.includes('/deployments') || url.includes('/artifacts')) {
      return jsonResponse({
        items: [],
        page: 0,
        size: 25,
        totalElements: 0,
        totalPages: 0,
      });
    }
    return jsonResponse({}, 200);
  });
}

describe('CloudForge Phase 2 Web Console', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('redirects unauthenticated visitors from protected routes to the GitHub OAuth login UI', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url.startsWith('/api/health')) return jsonResponse(HEALTH_UP);
      if (url.startsWith('/api/auth/config')) return jsonResponse(AUTH_CONFIG_READY);
      if (url.startsWith('/api/auth/me')) {
        return jsonResponse({ status: 401, error: 'Unauthorized', message: 'Login required' }, 401);
      }
      return jsonResponse({}, 401);
    });

    render(
      <MemoryRouter initialEntries={['/projects']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Sign in to your developer workspace')).toBeInTheDocument();
    });

    const loginCta = screen.getByRole('link', { name: /Continue with GitHub/i });
    expect(loginCta).toHaveAttribute('href', '/oauth2/authorization/github');
    expect(screen.getByText('API & DB Healthy')).toBeInTheDocument();
  });

  it('shows setup-required notice and auth error banner on login page when OAuth is unconfigured', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url.startsWith('/api/health')) return jsonResponse(HEALTH_UP);
      if (url.startsWith('/api/auth/config')) {
        return jsonResponse({
          githubOAuthConfigured: false,
          githubAppConfigured: false,
          githubAppSlug: null,
          loginUrl: '/oauth2/authorization/github',
        });
      }
      return jsonResponse({ status: 401, error: 'Unauthorized', message: 'Login required' }, 401);
    });

    render(
      <MemoryRouter initialEntries={['/login?error=oauth_failed']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('GitHub OAuth Setup Required')).toBeInTheDocument();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/GitHub authentication could not be completed/i);
    expect(screen.getByRole('button', { name: /Setup Required/i })).toBeDisabled();
  });

  it('displays live dashboard metrics, PostgreSQL health, and navigates across all views when authenticated', async () => {
    const user = userEvent.setup();
    createAuthenticatedFetchMock();

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('CloudForge Backend')).toBeInTheDocument();
    });

    expect(screen.getByTestId('dashboard-project-count')).toHaveTextContent('1');
    expect(screen.getByTestId('dashboard-repo-count')).toHaveTextContent('1');
    expect(screen.getByText('PostgreSQL 17.4')).toBeInTheDocument();
    expect(screen.getByText('Healthy (HTTP 200)')).toBeInTheDocument();

    const routes: Array<[string, string]> = [
      ['Projects', 'Projects'],
      ['Repositories', 'Repositories'],
      ['Deployments', 'Deployments'],
      ['Pipelines', 'Pipelines'],
      ['Monitoring', 'Monitoring'],
      ['Settings', 'Settings'],
    ];

    for (const [linkName, headingName] of routes) {
      await user.click(screen.getByRole('link', { name: linkName }));
      expect(
        screen.getByRole('heading', { name: headingName, level: 1 })
      ).toBeInTheDocument();
    }
  });

  it('shows degraded alert on Dashboard when /api/health returns HTTP 503', async () => {
    createAuthenticatedFetchMock({
      health: jsonResponse(
        {
          ...HEALTH_UP,
          status: 'DEGRADED',
          database: {
            status: 'DOWN',
            engine: 'PostgreSQL',
            responseTimeMs: 3001,
            message: 'Database connectivity check failed',
          },
        },
        503
      ),
    });

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(
        screen.getByText('Backend is running in degraded mode (database unavailable)')
      ).toBeInTheDocument();
    });
    expect(screen.getByText('Degraded (HTTP 503)')).toBeInTheDocument();
  });

  it('supports creating a persistent project and surfaces backend API validation errors cleanly', async () => {
    const user = userEvent.setup();
    let failFirst = true;

    createAuthenticatedFetchMock({
      onPostProject: () => {
        if (failFirst) {
          failFirst = false;
          return jsonResponse(
            {
              status: 400,
              error: 'Bad Request',
              message: 'Request validation failed',
              fieldErrors: { defaultBranch: 'Branch name is invalid' },
            },
            400
          );
        }
        return jsonResponse(
          {
            ...SAMPLE_PROJECT,
            id: 'proj-2',
            name: 'Orders API',
            slug: 'orders-api',
            workspaceName: AUTH_ME_OWNER.workspaces[0].name,
            createdByUsername: 'kush-dev',
            environments: [],
          },
          201
        );
      },
    });

    render(
      <MemoryRouter initialEntries={['/projects']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Payments Service')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /New Project/i }));
    await user.type(screen.getByLabelText(/Project Name/i), 'Orders API');
    await user.click(screen.getByRole('button', { name: /^Create Project$/i }));

    // Surfaces API error cleanly
    await waitFor(() => {
      expect(screen.getByText('Branch name is invalid')).toBeInTheDocument();
    });

    // Retry succeeds
    await user.click(screen.getByRole('button', { name: /^Create Project$/i }));
    await waitFor(() => {
      expect(screen.getByText(/Project "Orders API" created successfully/i)).toBeInTheDocument();
    });
  });

  it('loads authorized repositories and shows setup-required state when GitHub App is unconfigured', async () => {
    createAuthenticatedFetchMock({
      authConfig: {
        ...AUTH_CONFIG_READY,
        githubAppConfigured: false,
        githubAppSlug: null,
      },
      reposList: [],
    });

    render(
      <MemoryRouter initialEntries={['/repositories']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('GitHub App Configuration Required')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /Connect GitHub App/i })).toBeDisabled();
  });

  it('terminates session on Sign out and returns user to login screen', async () => {
    const user = userEvent.setup();
    const fetchSpy = createAuthenticatedFetchMock();

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Sign out/i })).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Sign out/i }));

    await waitFor(() => {
      expect(screen.getByText('Sign in to your developer workspace')).toBeInTheDocument();
    });

    const logoutCall = fetchSpy.mock.calls.find(
      ([url, init]) => String(url).includes('/api/auth/logout') && init?.method === 'POST'
    );
    expect(logoutCall).toBeDefined();
    expect((logoutCall?.[1]?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe(
      'csrf-token-test'
    );
  });

  it('deletes a project only after explicit confirmation', async () => {
    const user = userEvent.setup();
    const fetchSpy = createAuthenticatedFetchMock();

    render(
      <MemoryRouter initialEntries={['/projects']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Payments Service')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Delete Payments Service/i }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Confirm Delete/i }));

    await waitFor(() => {
      expect(
        screen.getByText(/Project "Payments Service" was permanently deleted/i)
      ).toBeInTheDocument();
    });

    const deleteCall = fetchSpy.mock.calls.find(
      ([url, init]) => String(url).includes('/projects/proj-1') && init?.method === 'DELETE'
    );
    expect(deleteCall).toBeDefined();
  });

  it('displays authorized repositories synced from GitHub App installations', async () => {
    createAuthenticatedFetchMock();

    render(
      <MemoryRouter initialEntries={['/repositories']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('kush-dev/payments-service')).toBeInTheDocument();
    });
    expect(screen.getByText('CONNECTED')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Link to Project/i })).toBeInTheDocument();
  });
});
