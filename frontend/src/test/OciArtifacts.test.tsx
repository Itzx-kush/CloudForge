import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type {
  OciBuildRecord,
  OciImageArtifact,
  OciImageArtifactDetail,
  OciRegistryConnection,
  OciSubsystemStatus,
  WorkspaceRole,
} from '../types/platform';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'req-oci-test' },
  });
}

const SAMPLE_DIGEST =
  'sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

const SAMPLE_STATUS: OciSubsystemStatus = {
  ociEnabled: true,
  allowLocalDevRegistry: true,
  allowedRegistryHosts: ['127.0.0.1:5000', 'ghcr.io'],
  allowedPlatforms: ['linux/amd64'],
  activeRegistryConnections: 1,
  totalBuilds: 1,
  succeededBuilds: 1,
  verifiedArtifacts: 1,
  activeBuildWorkers: 1,
};

const SAMPLE_REGISTRY: OciRegistryConnection = {
  id: 'reg-1',
  workspaceId: 'ws-1',
  name: 'Local OCI Registry',
  slug: 'local-oci-registry',
  registryHost: '127.0.0.1:5000',
  scheme: 'http',
  registryType: 'LOCAL_DEV',
  allowedNamespace: 'cloudforge',
  credentialProjectId: 'proj-1',
  credentialProjectName: 'Payments API',
  credentialEnvironmentId: 'env-stg',
  credentialEnvironmentName: 'Staging',
  usernameSecretId: 'sec-user-1',
  usernameSecretName: 'OCI_REG_USER',
  passwordSecretId: 'sec-pass-1',
  passwordSecretName: 'OCI_REG_PASS',
  credentialsConfigured: true,
  allowInsecureLocal: true,
  status: 'ACTIVE',
  description: 'Disposable local verification registry',
  createdByUsername: 'kush-dev',
  updatedByUsername: 'kush-dev',
  createdAt: '2026-11-24T10:00:00Z',
  updatedAt: '2026-11-24T10:00:00Z',
};

const SAMPLE_BUILD: OciBuildRecord = {
  id: 'build-1',
  workspaceId: 'ws-1',
  projectId: 'proj-1',
  projectName: 'Payments API',
  pipelineRunId: 'run-1',
  runNumber: 12,
  stageRunId: 'stage-build-1',
  attemptId: 'att-build-1',
  githubRepositoryId: 4001,
  repositoryFullName: 'kush-dev/payments-service',
  commitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  sourceChecksumSha256: 'b'.repeat(64),
  registryConnectionId: 'reg-1',
  registryHost: '127.0.0.1:5000',
  dockerfilePath: 'Dockerfile',
  buildContextPath: '.',
  targetImageName: 'cloudforge/payments',
  targetTag: 'v1.0.0',
  targetPlatform: 'linux/amd64',
  buildMode: 'LOCAL_TRUSTED_BUILDKIT',
  configChecksumSha256: 'c'.repeat(64),
  status: 'SUCCEEDED',
  builtManifestDigest: SAMPLE_DIGEST,
  imageMediaType: 'application/vnd.oci.image.manifest.v1+json',
  imageSizeBytes: 2457600,
  workerId: 'oci-worker-1',
  failureCategory: null,
  errorSummary: null,
  startedAt: '2026-11-24T10:01:00Z',
  completedAt: '2026-11-24T10:01:20Z',
  createdAt: '2026-11-24T10:01:00Z',
};

const SAMPLE_ARTIFACT: OciImageArtifact = {
  id: 'art-1',
  workspaceId: 'ws-1',
  projectId: 'proj-1',
  projectName: 'Payments API',
  pipelineRunId: 'run-1',
  runNumber: 12,
  stageRunId: 'stage-pub-1',
  attemptId: 'att-pub-1',
  buildRecordId: 'build-1',
  registryConnectionId: 'reg-1',
  githubRepositoryId: 4001,
  repositoryFullName: 'kush-dev/payments-service',
  commitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  registryHost: '127.0.0.1:5000',
  imageRepository: 'cloudforge/payments',
  imageTag: 'v1.0.0',
  manifestDigest: SAMPLE_DIGEST,
  immutableReference: `127.0.0.1:5000/cloudforge/payments@${SAMPLE_DIGEST}`,
  imageMediaType: 'application/vnd.oci.image.manifest.v1+json',
  platform: 'linux/amd64',
  imageSizeBytes: 2457600,
  verificationStatus: 'VERIFIED',
  retentionPolicy: 'RETAIN_METADATA',
  verifiedAt: '2026-11-24T10:01:28Z',
  publishedAt: '2026-11-24T10:01:26Z',
  errorSummary: null,
  createdAt: '2026-11-24T10:01:26Z',
};

const SAMPLE_ARTIFACT_DETAIL: OciImageArtifactDetail = {
  artifact: SAMPLE_ARTIFACT,
  buildRecord: SAMPLE_BUILD,
  publishAttempts: [
    {
      id: 'pub-1',
      workspaceId: 'ws-1',
      projectId: 'proj-1',
      pipelineRunId: 'run-1',
      stageRunId: 'stage-pub-1',
      attemptId: 'att-pub-1',
      buildRecordId: 'build-1',
      registryConnectionId: 'reg-1',
      artifactId: 'art-1',
      registryHost: '127.0.0.1:5000',
      imageRepository: 'cloudforge/payments',
      imageTag: 'v1.0.0',
      expectedDigest: SAMPLE_DIGEST,
      reportedDigest: SAMPLE_DIGEST,
      verifiedDigest: SAMPLE_DIGEST,
      status: 'VERIFIED',
      workerId: 'oci-worker-1',
      failureCategory: null,
      errorSummary: null,
      startedAt: '2026-11-24T10:01:22Z',
      completedAt: '2026-11-24T10:01:28Z',
    },
  ],
  events: [
    {
      id: 'ev-1',
      pipelineRunId: 'run-1',
      buildRecordId: 'build-1',
      artifactId: 'art-1',
      publishAttemptId: 'pub-1',
      eventType: 'OCI_ARTIFACT_VERIFIED',
      actorLabel: 'worker:oci-worker-1',
      message: 'Verified pushed manifest digest independently via OCI Distribution API',
      metadata: { verifiedDigest: SAMPLE_DIGEST },
      occurredAt: '2026-11-24T10:01:28Z',
    },
  ],
};

function installMockApi(options?: {
  role?: WorkspaceRole;
  status?: OciSubsystemStatus;
  onCreateRegistry?: (body: unknown, headers: Headers) => Response;
}) {
  const role = options?.role ?? 'OWNER';
  const registries: OciRegistryConnection[] = [structuredClone(SAMPLE_REGISTRY)];

  const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = new Headers(init?.headers);
    const body = typeof init?.body === 'string' && init.body.length > 0 ? JSON.parse(init.body) : null;

    if (url.endsWith('/api/health')) {
      return jsonResponse({
        application: 'CloudForge Backend',
        version: '0.3.5b',
        phase: 'Phase 3.5B',
        status: 'UP',
        database: { status: 'UP', engine: 'PostgreSQL 17', responseTimeMs: 2, message: 'OK' },
        timestamp: '2026-11-24T10:00:00Z',
      });
    }
    if (url.startsWith('/api/auth/csrf')) {
      return jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-oci-token',
      });
    }
    if (url.endsWith('/api/auth/me')) {
      return jsonResponse({
        authenticated: true,
        csrfToken: 'csrf-oci-token',
        csrfHeaderName: 'X-XSRF-TOKEN',
        githubOAuthConfigured: true,
        githubAppConfigured: true,
        user: {
          id: 'u-1',
          githubId: 42,
          username: 'kush-dev',
          displayName: 'Kush',
          email: 'kush@example.com',
          avatarUrl: null,
          profileUrl: null,
          lastLoginAt: '2026-11-24T10:00:00Z',
          createdAt: '2026-11-20T10:00:00Z',
        },
        workspaces: [
          {
            id: 'ws-1',
            name: 'CloudForge Lab',
            slug: 'cloudforge-lab',
            description: 'Lab',
            personal: true,
            role,
            memberCount: 2,
            projectCount: 1,
            connectedRepositoryCount: 1,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T10:00:00Z',
          },
        ],
      });
    }
    if (url.includes('/oci/status') && method === 'GET') {
      return jsonResponse(options?.status ?? SAMPLE_STATUS);
    }
    if (url.includes('/registries') && url.endsWith('/disable') && method === 'POST') {
      registries[0] = { ...registries[0], status: 'DISABLED' };
      return jsonResponse(registries[0]);
    }
    if (url.includes('/registries') && method === 'GET') {
      return jsonResponse(registries);
    }
    if (url.includes('/registries') && method === 'POST') {
      if (options?.onCreateRegistry) {
        return options.onCreateRegistry(body, headers);
      }
      const req = body as { name: string; registryHost: string; allowedNamespace: string };
      const created: OciRegistryConnection = {
        ...SAMPLE_REGISTRY,
        id: 'reg-2',
        name: req.name,
        slug: 'ghcr-prod',
        registryHost: req.registryHost,
        scheme: 'https',
        registryType: 'GHCR',
        allowedNamespace: req.allowedNamespace,
        allowInsecureLocal: false,
      };
      registries.unshift(created);
      return jsonResponse(created, 201);
    }
    if (url.includes('/artifacts/art-1') && method === 'GET') {
      return jsonResponse(SAMPLE_ARTIFACT_DETAIL);
    }
    if (url.includes('/artifacts') && method === 'GET') {
      return jsonResponse({
        items: [SAMPLE_ARTIFACT],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (url.includes('/builds') && method === 'GET') {
      return jsonResponse({
        items: [SAMPLE_BUILD],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (url.includes('/projects/proj-1') && !url.includes('/secrets')) {
      return jsonResponse({
        id: 'proj-1',
        workspaceId: 'ws-1',
        name: 'Payments API',
        slug: 'payments-api',
        description: 'Payments',
        defaultBranch: 'main',
        status: 'ACTIVE',
        linkedRepository: null,
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
            updatedAt: '2026-11-24T10:00:00Z',
          },
        ],
        createdAt: '2026-11-20T09:00:00Z',
        updatedAt: '2026-11-24T10:00:00Z',
      });
    }
    if (url.includes('/environments/env-stg/secrets')) {
      return jsonResponse({
        items: [
          {
            id: 'sec-user-1',
            workspaceId: 'ws-1',
            projectId: 'proj-1',
            environmentId: 'env-stg',
            environmentName: 'Staging',
            environmentType: 'STAGING',
            name: 'OCI_REG_USER',
            description: 'User',
            status: 'ACTIVE',
            currentVersionNumber: 1,
            currentVersionId: 'v1',
            currentKekId: 'kek-v1',
            encryptionAlgorithm: 'AES-256-GCM',
            encryptionFormatVersion: 1,
            createdByUsername: 'kush-dev',
            updatedByUsername: 'kush-dev',
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T10:00:00Z',
            lastRotatedAt: null,
            revokedAt: null,
            revokedByUsername: null,
          },
          {
            id: 'sec-pass-1',
            workspaceId: 'ws-1',
            projectId: 'proj-1',
            environmentId: 'env-stg',
            environmentType: 'STAGING',
            environmentName: 'Staging',
            name: 'OCI_REG_PASS',
            description: 'Pass',
            status: 'ACTIVE',
            currentVersionNumber: 1,
            currentVersionId: 'v2',
            currentKekId: 'kek-v1',
            encryptionAlgorithm: 'AES-256-GCM',
            encryptionFormatVersion: 1,
            createdByUsername: 'kush-dev',
            updatedByUsername: 'kush-dev',
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T10:00:00Z',
            lastRotatedAt: null,
            revokedAt: null,
            revokedByUsername: null,
          },
        ],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
      });
    }
    if (url.includes('/projects') && method === 'GET') {
      return jsonResponse({
        items: [
          {
            id: 'proj-1',
            workspaceId: 'ws-1',
            name: 'Payments API',
            slug: 'payments-api',
            description: 'Payments',
            defaultBranch: 'main',
            status: 'ACTIVE',
            linkedRepository: null,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T10:00:00Z',
          },
        ],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (url.endsWith('/pipelines') && method === 'GET') {
      return jsonResponse({
        workspaceId: 'ws-1',
        githubAppConfigured: true,
        connectedRepositoryCount: 0,
        repositoriesWithWorkflowsCount: 0,
        workflowCount: 0,
        recentRunCount: 0,
        activeRunCount: 0,
        failedRunCount: 0,
        succeededRunCount: 0,
        lastSyncedAt: null,
        repositories: [],
      });
    }
    return jsonResponse({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });
  return { fetchMock };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('CloudForge Phase 3.5B OCI Builds, Registries & Immutable Artifacts UI', () => {
  it('renders OCI subsystem status, verified immutable artifacts, artifact details modal, and build history', async () => {
    installMockApi({ role: 'OWNER' });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/pipelines?view=artifacts']}>
        <AppRoutes />
      </MemoryRouter>
    );

    expect(await screen.findByText(/OCI BuildKit & Registry Plane/i)).toBeInTheDocument();
    expect(screen.getByText(/Verified Container Image Artifacts/i)).toBeInTheDocument();
    expect(screen.getByText(/OCI Build History/i)).toBeInTheDocument();
    expect(screen.getAllByText(/cloudforge\/payments/i).length).toBeGreaterThan(0);

    // Open Artifact Details modal
    await user.click(screen.getByRole('button', { name: /Details/i }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(`127.0.0.1:5000/cloudforge/payments@${SAMPLE_DIGEST}`)).toBeInTheDocument();
    expect(within(dialog).getByText(/OCI_ARTIFACT_VERIFIED/i)).toBeInTheDocument();
  });

  it('allows OWNER to add a registry connection using encrypted secret references with CSRF', async () => {
    const capturedRequests: Array<{ body: Record<string, unknown>; headers: Headers }> = [];
    installMockApi({
      role: 'OWNER',
      onCreateRegistry: (body, headers) => {
        capturedRequests.push({ body: body as Record<string, unknown>, headers });
        return jsonResponse(
          {
            ...SAMPLE_REGISTRY,
            id: 'reg-new',
            name: 'GHCR Production',
            registryHost: 'ghcr.io',
            scheme: 'https',
            registryType: 'GHCR',
            allowedNamespace: 'kush-dev',
            allowInsecureLocal: false,
          },
          201
        );
      },
    });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/pipelines?view=artifacts']}>
        <AppRoutes />
      </MemoryRouter>
    );

    const addBtn = await screen.findByRole('button', { name: /Add Registry Connection/i });
    await user.click(addBtn);

    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText(/GHCR Production/i), 'GHCR Production');
    await user.type(within(dialog).getByPlaceholderText(/ghcr.io or 127.0.0.1:5000/i), 'ghcr.io');
    await user.type(within(dialog).getByPlaceholderText(/my-org or cloudforge-dev/i), 'kush-dev');

    // Select project -> environment -> encrypted secret references
    await user.selectOptions(within(dialog).getByLabelText(/Secret Project/i), 'proj-1');
    await waitFor(() => {
      expect(within(dialog).getByText(/Staging \(STAGING\)/i)).toBeInTheDocument();
    });
    await user.selectOptions(within(dialog).getByLabelText(/Secret Environment/i), 'env-stg');
    await waitFor(() => {
      expect(within(dialog).getAllByText(/OCI_REG_PASS/i).length).toBeGreaterThan(0);
    });
    await user.selectOptions(within(dialog).getByLabelText(/Username Secret/i), 'sec-user-1');
    await user.selectOptions(within(dialog).getByLabelText(/Password \/ Token Secret/i), 'sec-pass-1');

    await user.click(within(dialog).getByRole('button', { name: /Save Registry Connection/i }));

    await waitFor(() => {
      expect(capturedRequests).toHaveLength(1);
    });
    expect(capturedRequests[0].headers.get('X-XSRF-TOKEN')).toBe('csrf-oci-token');
    expect(capturedRequests[0].body.passwordSecretId).toBe('sec-pass-1');
    expect(capturedRequests[0].body.usernameSecretId).toBe('sec-user-1');
    expect(capturedRequests[0].body).not.toHaveProperty('password');
  });

  it('enforces read-only VIEWER UI and shows honest disabled notice when OCI execution is disabled', async () => {
    installMockApi({
      role: 'VIEWER',
      status: {
        ...SAMPLE_STATUS,
        ociEnabled: false,
        activeBuildWorkers: 0,
      },
    });

    render(
      <MemoryRouter initialEntries={['/pipelines?view=artifacts']}>
        <AppRoutes />
      </MemoryRouter>
    );

    expect(await screen.findByText(/OCI BuildKit & Registry Plane/i)).toBeInTheDocument();
    expect(screen.getByText(/Build Execution Disabled by Default/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add Registry Connection/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Disable/i })).not.toBeInTheDocument();
  });
});
