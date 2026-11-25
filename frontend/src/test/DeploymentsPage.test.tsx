import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type {
  KubernetesClusterConnection,
  KubernetesDeploymentEvent,
  KubernetesDeploymentOperation,
  KubernetesNamespaceBinding,
  KubernetesRelease,
  KubernetesSubsystemStatus,
  OciImageArtifact,
  WorkspaceRole,
} from '../types/platform';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'req-k8s-test' },
  });
}

const SAMPLE_DIGEST =
  'sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789';

const SAMPLE_STATUS: KubernetesSubsystemStatus = {
  kubernetesEnabled: true,
  allowLocalDevClusters: true,
  allowProductionDeployments: false,
  allowedApiHosts: ['127.0.0.1'],
  maxReplicas: 20,
  maxDeploymentTimeoutSeconds: 600,
  configuredClusters: 1,
  validatedClusters: 1,
  activeNamespaceBindings: 1,
  totalDeployments: 1,
  runningDeployments: 0,
  succeededDeployments: 1,
  failedDeployments: 0,
  activeDeployWorkers: 1,
};

const SAMPLE_NS: KubernetesNamespaceBinding = {
  id: 'ns-1',
  workspaceId: 'ws-1',
  clusterConnectionId: 'cluster-1',
  clusterName: 'Local Kind Dev',
  projectId: 'proj-1',
  projectName: 'Payments API',
  environmentId: 'env-dev',
  environmentName: 'Development',
  environmentType: 'DEVELOPMENT',
  namespaceName: 'cf-ws01-payments-dev-a1b2',
  workloadServiceAccountName: 'cloudforge-workload',
  imagePullSecretName: null,
  podSecurityStandard: 'RESTRICTED',
  networkPolicyMode: 'ENFORCED_VERIFIED',
  resourceQuotaConfigured: true,
  limitRangeConfigured: true,
  status: 'ACTIVE',
  verifiedAt: '2026-11-24T12:05:00Z',
  createdAt: '2026-11-24T12:00:00Z',
  updatedAt: '2026-11-24T12:00:00Z',
};

const SAMPLE_CLUSTER: KubernetesClusterConnection = {
  id: 'cluster-1',
  workspaceId: 'ws-1',
  name: 'Local Kind Dev',
  slug: 'local-kind-dev',
  clusterIdentifier: 'kind-cloudforge-dev',
  clusterType: 'KIND_LOCAL',
  environmentCategory: 'DEVELOPMENT',
  apiServerUrl: 'https://127.0.0.1:16443',
  caCertificateFingerprintSha256: '9'.repeat(64),
  credentialProjectId: 'proj-1',
  credentialProjectName: 'Payments API',
  credentialEnvironmentId: 'env-dev',
  credentialEnvironmentName: 'Development',
  bearerTokenSecretId: 'sec-k8s-1',
  bearerTokenSecretName: 'K8S_DEPLOYER_TOKEN',
  serviceAccountName: 'cloudforge-deployer',
  allowLocalLoopback: true,
  productionApprovalRequired: true,
  connectionStatus: 'VALIDATED',
  validationRequestedAt: '2026-11-24T12:04:00Z',
  kubernetesVersion: 'v1.31.2+k3s1',
  supportedCapabilities: { appsV1Available: true, leastPrivilegeVerified: true },
  lastValidatedAt: '2026-11-24T12:05:00Z',
  lastValidationWorkerId: 'k8s-worker-1',
  lastValidationError: null,
  description: 'Disposable local k3s verification cluster',
  namespaceBindings: [SAMPLE_NS],
  createdByUsername: 'kush-dev',
  updatedByUsername: 'kush-dev',
  createdAt: '2026-11-24T12:00:00Z',
  updatedAt: '2026-11-24T12:05:00Z',
};

const SAMPLE_DEPLOYMENT: KubernetesDeploymentOperation = {
  id: 'dep-1',
  workspaceId: 'ws-1',
  projectId: 'proj-1',
  projectName: 'Payments API',
  environmentId: 'env-dev',
  environmentName: 'Development',
  environmentType: 'DEVELOPMENT',
  clusterConnectionId: 'cluster-1',
  clusterName: 'Local Kind Dev',
  clusterIdentifier: 'kind-cloudforge-dev',
  namespaceBindingId: 'ns-1',
  namespaceName: 'cf-ws01-payments-dev-a1b2',
  deploymentConfigId: 'cfg-1',
  ociArtifactId: 'art-1',
  pipelineRunId: 'run-1',
  pipelineRunNumber: 4,
  stageRunId: 'stage-1',
  attemptId: 'att-1',
  deploymentName: 'payments-api',
  serviceName: 'payments-api',
  immutableImageReference: `127.0.0.1:5000/cloudforge/payments@${SAMPLE_DIGEST}`,
  manifestDigest: SAMPLE_DIGEST,
  sourceCommitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  manifestChecksumSha256: '4'.repeat(64),
  configSnapshot: { replicaCount: 2 },
  requestedReplicas: 2,
  status: 'SUCCEEDED',
  applyInitiated: true,
  observedReplicas: 2,
  updatedReplicas: 2,
  readyReplicas: 2,
  availableReplicas: 2,
  observedGeneration: 1,
  k8sDeploymentUid: 'uid-dep-1234',
  k8sServiceUid: 'uid-svc-5678',
  rolloutConditions: [{ type: 'Available', status: 'True', reason: 'MinimumReplicasAvailable' }],
  podDiagnostics: [
    { podName: 'payments-api-abcde', phase: 'Running', ready: true, restarts: 0 },
  ],
  assignedWorkerId: 'k8s-worker-1',
  fencingGeneration: 1,
  requestedByUsername: 'kush-dev',
  cancelledByUsername: null,
  failureCategory: null,
  errorSummary: null,
  cancellationRequestedAt: null,
  startedAt: '2026-11-24T12:10:00Z',
  completedAt: '2026-11-24T12:10:25Z',
  createdAt: '2026-11-24T12:09:58Z',
  updatedAt: '2026-11-24T12:10:25Z',
};

const SAMPLE_EVENTS: KubernetesDeploymentEvent[] = [
  {
    id: 'ev-1',
    deploymentOperationId: 'dep-1',
    eventType: 'DEPLOYMENT_QUEUED',
    fromStatus: null,
    toStatus: 'PENDING',
    actorLabel: 'user:kush-dev',
    message: 'Deployment operation queued with immutable digest and SHA-256 manifest checksum.',
    metadata: {},
    occurredAt: '2026-11-24T12:09:58Z',
  },
  {
    id: 'ev-2',
    deploymentOperationId: 'dep-1',
    eventType: 'ROLLOUT_VERIFIED_HEALTHY',
    fromStatus: 'VERIFYING',
    toStatus: 'SUCCEEDED',
    actorLabel: 'worker:k8s-worker-1',
    message: 'Verified 2/2 ready replicas running immutable digest.',
    metadata: {},
    occurredAt: '2026-11-24T12:10:25Z',
  },
];

const SAMPLE_ARTIFACT: OciImageArtifact = {
  id: 'art-1',
  workspaceId: 'ws-1',
  projectId: 'proj-1',
  projectName: 'Payments API',
  pipelineRunId: 'run-1',
  runNumber: 4,
  stageRunId: 'stage-pub',
  attemptId: 'att-pub',
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
  verifiedAt: '2026-11-24T12:00:00Z',
  publishedAt: '2026-11-24T12:00:00Z',
  errorSummary: null,
  createdAt: '2026-11-24T12:00:00Z',
};

const FIRST_RELEASE: KubernetesRelease = {
  revision: 1, kind: 'DEPLOYMENT', type: 'INITIAL_DEPLOYMENT', targetReleaseId: null,
  expectedOperationId: null, reason: null, historicallyVerified: true, reconciliationRequired: false,
  operation: SAMPLE_DEPLOYMENT,
};

function installMockApi(options?: {
  role?: WorkspaceRole;
  status?: KubernetesSubsystemStatus;
  deployments?: KubernetesDeploymentOperation[];
  onCreateCluster?: (body: unknown, headers: Headers) => Response;
  onCreateDeployment?: (body: unknown, headers: Headers) => Response;
  onCancelDeployment?: (headers: Headers) => Response;
}) {
  const role = options?.role ?? 'OWNER';
  const clusters: KubernetesClusterConnection[] = [structuredClone(SAMPLE_CLUSTER)];
  const deployments: KubernetesDeploymentOperation[] = structuredClone(
    options?.deployments ?? [SAMPLE_DEPLOYMENT]
  );

  let queuedRelease: KubernetesRelease | null = null;
  const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = new Headers(init?.headers);
    const body = typeof init?.body === 'string' && init.body.length > 0 ? JSON.parse(init.body) : null;

    if (url.endsWith('/api/health')) {
      return jsonResponse({
        application: 'CloudForge Backend',
        version: '0.3.6',
        phase: 'Phase 3.6',
        status: 'UP',
        database: { status: 'UP', engine: 'PostgreSQL 17', responseTimeMs: 2, message: 'OK' },
        timestamp: '2026-11-24T12:00:00Z',
      });
    }
    if (url.startsWith('/api/auth/csrf')) {
      return jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'csrf-k8s-token',
      });
    }
    if (url.endsWith('/api/auth/me')) {
      return jsonResponse({
        user: {
          id: 'u-1',
          githubUserId: 42,
          username: 'kush-dev',
          displayName: 'Kush',
          email: 'kush@example.com',
          avatarUrl: null,
          profileUrl: null,
          lastLoginAt: '2026-11-24T12:00:00Z',
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
            updatedAt: '2026-11-24T12:00:00Z',
          },
        ],
      });
    }
    if (url.includes('/kubernetes/status') && method === 'GET') {
      return jsonResponse(options?.status ?? SAMPLE_STATUS);
    }
    if (url.includes('/clusters/') && url.endsWith('/validate') && method === 'POST') {
      clusters[0] = { ...clusters[0], connectionStatus: 'VALIDATING' };
      return jsonResponse(clusters[0], 202);
    }
    if (url.includes('/clusters') && method === 'GET') {
      return jsonResponse(clusters);
    }
    if (url.includes('/clusters') && method === 'POST') {
      if (options?.onCreateCluster) {
        return options.onCreateCluster(body, headers);
      }
      return jsonResponse(SAMPLE_CLUSTER, 201);
    }
    if (url.includes('/namespaces') && method === 'GET') {
      return jsonResponse([SAMPLE_NS]);
    }
    if (url.includes('/releases/dep-1/rollback-preview')) {
      return jsonResponse({
        target: FIRST_RELEASE, current: null, expectedOperationId: 'operation-current',
        eligible: true, blockedReason: null, productionApprovalRequired: false, comparison: null,
        risk: 'Rollout may interrupt service.', reconciliationRequired: false,
      });
    }
    if (url.includes('/releases/dep-1/rollback') && method === 'POST') {
      queuedRelease = {
        ...FIRST_RELEASE, revision: 3, kind: 'ROLLBACK', type: 'ROLLBACK', targetReleaseId: 'dep-1',
        historicallyVerified: false, operation: { ...SAMPLE_DEPLOYMENT, id: 'rollback-3', status: 'PENDING', readyReplicas: 0 },
      };
      return jsonResponse(queuedRelease, 202);
    }
    if (url.includes('/releases/rollback-3')) return jsonResponse(queuedRelease);
    if (url.includes('/releases/dep-1')) return jsonResponse(FIRST_RELEASE);
    if (url.includes('/releases?')) {
      return jsonResponse({ items: [FIRST_RELEASE], page: 0, size: 20, totalPages: 1, totalElements: 1 });
    }
    if (url.includes('/deployments/rollback-3/events')) return jsonResponse([]);
    if (url.includes('/deployments/dep-1/events') && method === 'GET') {
      return jsonResponse(SAMPLE_EVENTS);
    }
    if (url.includes('/deployments/dep-1/status') && method === 'GET') {
      const dep = deployments[0];
      return jsonResponse({
        deploymentId: dep.id,
        deploymentName: dep.deploymentName,
        namespaceName: dep.namespaceName,
        clusterName: dep.clusterName,
        status: dep.status,
        applyInitiated: dep.applyInitiated,
        requestedReplicas: dep.requestedReplicas,
        observedReplicas: dep.observedReplicas,
        updatedReplicas: dep.updatedReplicas,
        readyReplicas: dep.readyReplicas,
        availableReplicas: dep.availableReplicas,
        observedGeneration: dep.observedGeneration,
        k8sDeploymentUid: dep.k8sDeploymentUid,
        k8sServiceUid: dep.k8sServiceUid,
        manifestDigest: dep.manifestDigest,
        rolloutConditions: dep.rolloutConditions,
        podDiagnostics: dep.podDiagnostics,
        failureCategory: dep.failureCategory,
        errorSummary: dep.errorSummary,
        cancellationRequestedAt: dep.cancellationRequestedAt,
        startedAt: dep.startedAt,
        completedAt: dep.completedAt,
        updatedAt: dep.updatedAt,
      });
    }
    if (url.includes('/deployments/dep-1/cancel') && method === 'POST') {
      if (options?.onCancelDeployment) {
        return options.onCancelDeployment(headers);
      }
      deployments[0] = {
        ...deployments[0],
        status: 'RECONCILIATION_REQUIRED',
        cancellationRequestedAt: '2026-11-24T12:12:00Z',
      };
      return jsonResponse(deployments[0]);
    }
    if (url.includes('/deployments/dep-1') && method === 'GET') {
      return jsonResponse(deployments[0]);
    }
    if (url.includes('/deployments') && method === 'GET') {
      return jsonResponse({
        items: deployments,
        page: 0,
        size: 25,
        totalElements: deployments.length,
        totalPages: 1,
      });
    }
    if (url.includes('/deployments') && method === 'POST') {
      if (options?.onCreateDeployment) {
        return options.onCreateDeployment(body, headers);
      }
      return jsonResponse(SAMPLE_DEPLOYMENT, 201);
    }
    if (url.includes('/artifacts') && method === 'GET') {
      return jsonResponse({
        items: [SAMPLE_ARTIFACT],
        page: 0,
        size: 100,
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
            id: 'env-dev',
            projectId: 'proj-1',
            name: 'Development',
            environmentType: 'DEVELOPMENT',
            branchOverride: null,
            configVariables: {},
            notes: null,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T12:00:00Z',
          },
          {
            id: 'env-prod',
            projectId: 'proj-1',
            name: 'Production',
            environmentType: 'PRODUCTION',
            branchOverride: null,
            configVariables: {},
            notes: null,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T12:00:00Z',
          },
        ],
        createdAt: '2026-11-20T09:00:00Z',
        updatedAt: '2026-11-24T12:00:00Z',
      });
    }
    if (url.includes('/environments/env-dev/secrets')) {
      return jsonResponse({
        items: [
          {
            id: 'sec-k8s-1',
            workspaceId: 'ws-1',
            projectId: 'proj-1',
            environmentId: 'env-dev',
            environmentName: 'Development',
            environmentType: 'DEVELOPMENT',
            name: 'K8S_DEPLOYER_TOKEN',
            description: 'Least-privilege SA token',
            status: 'ACTIVE',
            currentVersionNumber: 1,
            currentVersionId: 'ver-1',
            currentKekId: 'kek-v1',
            encryptionAlgorithm: 'AES-256-GCM',
            encryptionFormatVersion: 1,
            createdByUsername: 'kush-dev',
            updatedByUsername: 'kush-dev',
            createdAt: '2026-11-24T11:00:00Z',
            updatedAt: '2026-11-24T11:00:00Z',
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
            updatedAt: '2026-11-24T12:00:00Z',
          },
        ],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      });
    }
    return jsonResponse({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  return { fetchMock };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('CloudForge Phase 3.6 Kubernetes Deployment Console UI', () => {
  it('renders live deployment overview telemetry, immutable digest history, and details modal with events & pod diagnostics', async () => {
    installMockApi({ role: 'OWNER' });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/deployments']}>
        <AppRoutes />
      </MemoryRouter>
    );

    expect(await screen.findByTestId('k8s-cluster-count')).toHaveTextContent('1');
    expect(screen.getByTestId('k8s-total-deployments')).toHaveTextContent('1');
    expect(screen.getByTestId('k8s-succeeded-deployments')).toHaveTextContent('1');
    expect(screen.getByTestId('k8s-worker-count')).toHaveTextContent('1');
    expect(screen.getByText('payments-api')).toBeInTheDocument();
    expect(screen.getByText('2/2 ready')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: /Replica readiness for payments-api/i })).toBeInTheDocument();

    // Inspect deployment details modal
    await user.click(screen.getByRole('button', { name: /Inspect/i }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(`127.0.0.1:5000/cloudforge/payments@${SAMPLE_DIGEST}`)
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/payments-api-abcde/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/ROLLOUT_VERIFIED_HEALTHY/i)).toBeInTheDocument();
  });

  it('allows OWNER to register a cluster using Phase 3.4 encrypted secret reference with CSRF and no plaintext token field', async () => {
    const captured: Array<{ body: Record<string, unknown>; headers: Headers }> = [];
    installMockApi({
      role: 'OWNER',
      onCreateCluster: (body, headers) => {
        captured.push({ body: body as Record<string, unknown>, headers });
        return jsonResponse(
          { ...SAMPLE_CLUSTER, id: 'cluster-2', name: 'Local K3s Cluster', clusterIdentifier: 'k3s-dev' },
          201
        );
      },
    });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/deployments']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await user.click(await screen.findByRole('button', { name: /Register Cluster/i }));
    const dialog = await screen.findByRole('dialog');

    await user.type(within(dialog).getByLabelText(/Display Name/i), 'Local K3s Cluster');
    await user.type(within(dialog).getByLabelText(/Cluster Identifier/i), 'k3s-dev');
    fireEvent.change(within(dialog).getByLabelText(/Cluster CA Certificate PEM/i), {
      target: { value: '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----' },
    });
    await user.selectOptions(within(dialog).getByLabelText(/Secret Project/i), 'proj-1');
    await waitFor(() => {
      expect(within(dialog).getByText(/Development \(DEVELOPMENT\)/i)).toBeInTheDocument();
    });
    await user.selectOptions(within(dialog).getByLabelText(/Secret Environment/i), 'env-dev');
    await waitFor(() => {
      expect(within(dialog).getByText(/K8S_DEPLOYER_TOKEN/i)).toBeInTheDocument();
    });
    await user.selectOptions(within(dialog).getByLabelText(/Bearer Token Secret/i), 'sec-k8s-1');

    await user.click(within(dialog).getByRole('button', { name: /^Register Cluster$/i }));

    await waitFor(() => {
      expect(captured).toHaveLength(1);
    });
    expect(captured[0].headers.get('X-XSRF-TOKEN')).toBe('csrf-k8s-token');
    expect(captured[0].body.bearerTokenSecretId).toBe('sec-k8s-1');
    expect(captured[0].body).not.toHaveProperty('bearerToken');
    expect(captured[0].body).not.toHaveProperty('kubeconfig');
  }, 15000);

  it('allows DEVELOPER to queue an immutable digest deployment with Idempotency-Key & CSRF and enforces production approval prompt', async () => {
    const captured: Array<{ body: Record<string, unknown>; headers: Headers }> = [];
    installMockApi({
      role: 'DEVELOPER',
      onCreateDeployment: (body, headers) => {
        captured.push({ body: body as Record<string, unknown>, headers });
        return jsonResponse({ ...SAMPLE_DEPLOYMENT, id: 'dep-2', status: 'PENDING' }, 201);
      },
    });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/deployments']}>
        <AppRoutes />
      </MemoryRouter>
    );

    // DEVELOPER cannot register clusters, but can queue deployments
    expect(await screen.findByRole('button', { name: /New Deployment/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Register Cluster/i })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /New Deployment/i }));
    const dialog = await screen.findByRole('dialog');

    await user.selectOptions(within(dialog).getByLabelText(/^Project$/i), 'proj-1');
    await waitFor(() => {
      expect(within(dialog).getByText(/Production \(PRODUCTION\)/i)).toBeInTheDocument();
    });

    // Selecting PRODUCTION surfaces explicit production approval gate
    await user.selectOptions(within(dialog).getByLabelText(/Target Environment/i), 'env-prod');
    expect(
      within(dialog).getByText(/Explicit Production Approval Required/i)
    ).toBeInTheDocument();

    // Switch to DEVELOPMENT, pick namespace + verified artifact, and submit
    await user.selectOptions(within(dialog).getByLabelText(/Target Environment/i), 'env-dev');
    await user.selectOptions(within(dialog).getByLabelText(/Approved Namespace Binding/i), 'ns-1');
    await user.selectOptions(within(dialog).getByLabelText(/Verified OCI Image Artifact/i), 'art-1');

    await user.click(within(dialog).getByRole('button', { name: /Queue Deployment/i }));

    await waitFor(() => {
      expect(captured).toHaveLength(1);
    });
    expect(captured[0].headers.get('X-XSRF-TOKEN')).toBe('csrf-k8s-token');
    expect(captured[0].headers.get('Idempotency-Key')).toMatch(/^cf-k8s-/);
    expect(captured[0].body.ociArtifactId).toBe('art-1');
    expect(captured[0].body.namespaceBindingId).toBe('ns-1');
  });

  it('requires explicit confirmation before cancelling an active rollout and explains recovery', async () => {
    const cancelHeaders: Headers[] = [];
    installMockApi({
      role: 'OWNER',
      deployments: [{ ...SAMPLE_DEPLOYMENT, status: 'ROLLING_OUT', readyReplicas: 1, completedAt: null }],
      onCancelDeployment: (headers) => {
        cancelHeaders.push(headers);
        return jsonResponse({
          ...SAMPLE_DEPLOYMENT,
          status: 'RECONCILIATION_REQUIRED',
          cancellationRequestedAt: '2026-11-24T12:12:00Z',
        });
      },
    });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/deployments']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await user.click(await screen.findByRole('button', { name: /Inspect/i }));
    const dialog = await screen.findByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: /Cancel Deployment/i }));
    expect(within(dialog).getByText(/Confirm Deployment Cancellation/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/inspect the live workload from Release History/i)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Confirm Cancel/i }));
    await waitFor(() => {
      expect(cancelHeaders).toHaveLength(1);
    });
    expect(cancelHeaders[0].get('X-XSRF-TOKEN')).toBe('csrf-k8s-token');
  });

  it('enforces read-only VIEWER UI and shows honest safe-default banner when Kubernetes execution is disabled', async () => {
    installMockApi({
      role: 'VIEWER',
      status: {
        ...SAMPLE_STATUS,
        kubernetesEnabled: false,
        activeDeployWorkers: 0,
      },
    });

    render(
      <MemoryRouter initialEntries={['/deployments']}>
        <AppRoutes />
      </MemoryRouter>
    );

    expect(
      await screen.findByText(/Kubernetes Execution Plane Disabled by Default/i)
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Register Cluster/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New Deployment/i })).not.toBeInTheDocument();
  });
});

describe('Release history and rollback', () => {
  it('requires rollback confirmation and reports the queued outcome', async () => {
    const { fetchMock } = installMockApi();
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/deployments']}><AppRoutes /></MemoryRouter>);
    await user.click(await screen.findByRole('tab', { name: 'Release History' }));
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Check rollback eligibility' }));
    const queue = await within(dialog).findByRole('button', { name: 'Queue rollback' });
    expect(queue).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Rollback reason'), 'Restore verified workload');
    await user.click(within(dialog).getByRole('checkbox'));
    await user.click(queue);
    expect(await within(dialog).findByText('Rollback request queued successfully.')).toBeInTheDocument();
    expect(within(dialog).getByText('PENDING')).toBeInTheDocument();
    expect(within(dialog).queryByText('Rollback completed and verified.')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/rollback')
      && new Headers(init?.headers).has('Idempotency-Key') && new Headers(init?.headers).has('X-XSRF-TOKEN'))).toBe(true);
  });
});
