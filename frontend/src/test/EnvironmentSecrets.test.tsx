import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type {
  EnvironmentSecretSummary,
  EnvironmentSecretVersion,
  ProjectDetail,
  SecretSubsystemStatus,
  WorkspaceRole,
} from '../types/platform';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': 'req-sec-ui-1',
    },
  });
}

const SAMPLE_PROJECT_DETAIL: ProjectDetail = {
  id: 'proj-1',
  workspaceId: 'ws-1',
  workspaceName: 'Workspace One',
  name: 'Payments API',
  slug: 'payments-api',
  description: 'Encrypted secrets test service',
  defaultBranch: 'main',
  status: 'ACTIVE',
  createdByUsername: 'kush-dev',
  linkedRepository: null,
  environments: [
    {
      id: 'env-dev',
      projectId: 'proj-1',
      name: 'Development',
      environmentType: 'DEVELOPMENT',
      branchOverride: null,
      configVariables: { LOG_LEVEL: 'info' },
      notes: null,
      createdAt: '2026-11-20T09:00:00Z',
      updatedAt: '2026-11-24T09:00:00Z',
    },
    {
      id: 'env-stg',
      projectId: 'proj-1',
      name: 'Staging',
      environmentType: 'STAGING',
      branchOverride: null,
      configVariables: { APP_REGION: 'us-east-1' },
      notes: null,
      createdAt: '2026-11-20T09:00:00Z',
      updatedAt: '2026-11-24T09:00:00Z',
    },
  ],
  createdAt: '2026-11-20T09:00:00Z',
  updatedAt: '2026-11-24T09:00:00Z',
};

const STATUS_ENABLED: SecretSubsystemStatus = {
  enabled: true,
  available: true,
  providerType: 'env-keyring',
  activeKeyId: 'kek-v1',
  workerDeliveryEnabled: false,
  allowInsecureLocalDelivery: false,
  operatorRotationEnabled: false,
  maxSecretValueBytes: 16384,
  maxSecretsPerEnvironment: 100,
  maxBindingsPerPipeline: 25,
  statusDiagnostic: 'env-keyring ready (1 active KEK configured)',
};

function setupSecretsUiMocks(options?: {
  role?: WorkspaceRole;
  status?: SecretSubsystemStatus;
  initialSecrets?: EnvironmentSecretSummary[];
  onCreateSecret?: (body: unknown) => void;
}) {
  const role = options?.role ?? 'OWNER';
  const statusPayload = options?.status ?? STATUS_ENABLED;
  let secrets: EnvironmentSecretSummary[] = options?.initialSecrets
    ? structuredClone(options.initialSecrets)
    : [];

  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;

    if (url.startsWith('/api/health')) {
      return jsonResponse({
        application: 'CloudForge Backend',
        version: '0.3.4-phase3.4',
        phase: 'Phase 3.4',
        status: 'UP',
        database: { status: 'UP', engine: 'PostgreSQL 17', responseTimeMs: 2, message: 'ok' },
        timestamp: '2026-11-24T10:00:00Z',
      });
    }
    if (url.startsWith('/api/auth/config')) {
      return jsonResponse({
        githubOAuthConfigured: true,
        githubAppConfigured: true,
        githubAppSlug: 'cloudforge-dev',
        loginUrl: '/oauth2/authorization/github',
      });
    }
    if (url.startsWith('/api/auth/csrf')) {
      return jsonResponse({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-1' });
    }
    if (url.startsWith('/api/auth/me')) {
      return jsonResponse({
        user: {
          id: 'u-1',
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
            name: 'Workspace One',
            slug: 'ws-one',
            description: 'Main workspace',
            personal: true,
            role,
            memberCount: 2,
            projectCount: 1,
            connectedRepositoryCount: 0,
            createdAt: '2026-11-20T09:00:00Z',
            updatedAt: '2026-11-24T09:00:00Z',
          },
        ],
      });
    }
    if (url.includes('/projects/proj-1') && !url.includes('/environments') && method === 'GET') {
      return jsonResponse(SAMPLE_PROJECT_DETAIL);
    }
    if (url.includes('/secrets/status') && method === 'GET') {
      return jsonResponse(statusPayload);
    }
    if (url.includes('/secrets') && url.endsWith('/versions') && method === 'GET') {
      const vers: EnvironmentSecretVersion[] = [
        {
          id: 'ver-2',
          secretId: 'sec-1',
          versionNumber: 2,
          status: 'ACTIVE',
          kekId: 'kek-v1',
          encryptionAlgorithm: 'AES-256-GCM',
          encryptionFormatVersion: 1,
          createdByUsername: 'kush-dev',
          createdAt: '2026-11-24T10:15:00Z',
          revokedAt: null,
          revokedByUsername: null,
          current: true,
        },
        {
          id: 'ver-1',
          secretId: 'sec-1',
          versionNumber: 1,
          status: 'ACTIVE',
          kekId: 'kek-v1',
          encryptionAlgorithm: 'AES-256-GCM',
          encryptionFormatVersion: 1,
          createdByUsername: 'kush-dev',
          createdAt: '2026-11-24T10:00:00Z',
          revokedAt: null,
          revokedByUsername: null,
          current: false,
        },
      ];
      return jsonResponse(vers);
    }
    if (url.includes('/secrets/') && url.endsWith('/rotate') && method === 'POST') {
      secrets = secrets.map((s) =>
        s.id === 'sec-1' ? { ...s, currentVersionNumber: s.currentVersionNumber + 1, lastRotatedAt: '2026-11-24T10:15:00Z' } : s
      );
      return jsonResponse(secrets.find((s) => s.id === 'sec-1'));
    }
    if (url.includes('/secrets/') && url.endsWith('/revoke') && method === 'POST') {
      secrets = secrets.map((s) => (s.id === 'sec-1' ? { ...s, status: 'REVOKED' } : s));
      return jsonResponse(secrets.find((s) => s.id === 'sec-1'));
    }
    if (url.includes('/secrets/sec-1') && method === 'DELETE') {
      secrets = secrets.filter((s) => s.id !== 'sec-1');
      return jsonResponse(null, 204);
    }
    if (url.includes('/secrets') && method === 'POST') {
      options?.onCreateSecret?.(body);
      const created: EnvironmentSecretSummary = {
        id: 'sec-1',
        workspaceId: 'ws-1',
        projectId: 'proj-1',
        environmentId: 'env-dev',
        environmentName: 'Development',
        environmentType: 'DEVELOPMENT',
        name: body.name,
        description: body.description ?? null,
        status: 'ACTIVE',
        currentVersionNumber: 1,
        currentVersionId: 'ver-1',
        currentKekId: 'kek-v1',
        encryptionAlgorithm: 'AES-256-GCM',
        encryptionFormatVersion: 1,
        createdByUsername: 'kush-dev',
        updatedByUsername: 'kush-dev',
        createdAt: '2026-11-24T10:00:00Z',
        updatedAt: '2026-11-24T10:00:00Z',
        lastRotatedAt: null,
        revokedAt: null,
        revokedByUsername: null,
      };
      secrets = [...secrets, created];
      return jsonResponse(created, 201);
    }
    if (url.includes('/secrets') && method === 'GET') {
      return jsonResponse({ items: secrets, page: 0, size: 100, totalElements: secrets.length, totalPages: 1 });
    }
    return jsonResponse({ message: 'Not mocked: ' + url }, 404);
  });
}

describe('CloudForge Phase 3.4 Encrypted Secrets UI', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('clearly separates non-secret variables from encrypted secrets, supports create/rotate/versions/revoke/delete, and clears drafts without persisting plaintext', async () => {
    const user = userEvent.setup();
    const submittedPayloads: unknown[] = [];
    setupSecretsUiMocks({
      role: 'OWNER',
      onCreateSecret: (b) => submittedPayloads.push(b),
    });

    render(
      <MemoryRouter initialEntries={['/projects/proj-1']}>
        <AppRoutes />
      </MemoryRouter>
    );

    expect(await screen.findByText('Persistent Environment Configurations')).toBeInTheDocument();
    expect(await screen.findByText('Encrypted Secrets — Development')).toBeInTheDocument();
    expect(screen.getByTestId('secret-subsystem-status')).toHaveTextContent('Encryption Active');
    expect(screen.getByTestId('secret-subsystem-status')).toHaveTextContent('kek-v1');

    // Add write-only secret
    await user.click(screen.getByRole('button', { name: /Add Secret/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Secret Name/i), 'DEPLOY_API_KEY');
    const secretInput = within(dialog).getByLabelText(/Secret Value \(Write-Only\)/i) as HTMLInputElement;
    expect(secretInput.type).toBe('password');
    await user.type(secretInput, 'ultra-secret-ui-val-123');
    await user.click(within(dialog).getByRole('button', { name: /Show secret value/i }));
    expect(secretInput.type).toBe('text');
    await user.click(within(dialog).getByRole('button', { name: /Encrypt & Store Secret/i }));

    await waitFor(() => {
      expect(screen.getByText('DEPLOY_API_KEY')).toBeInTheDocument();
    });
    expect(submittedPayloads).toHaveLength(1);
    expect(document.body.textContent).not.toContain('ultra-secret-ui-val-123');
    expect(JSON.stringify(window.localStorage)).not.toContain('ultra-secret-ui-val-123');
    expect(JSON.stringify(window.sessionStorage)).not.toContain('ultra-secret-ui-val-123');

    // Re-opening Add Secret modal has completely cleared input
    await user.click(screen.getByRole('button', { name: /Add Secret/i }));
    const reopenedDialog = await screen.findByRole('dialog');
    expect((within(reopenedDialog).getByLabelText(/Secret Value \(Write-Only\)/i) as HTMLInputElement).value).toBe('');
    await user.click(within(reopenedDialog).getByRole('button', { name: /Cancel/i }));

    // Rotate secret -> v2
    await user.click(screen.getByRole('button', { name: /Rotate/i }));
    const rotDialog = await screen.findByRole('dialog');
    await user.type(within(rotDialog).getByLabelText(/New Secret Value \(Write-Only\)/i), 'rotated-secret-ui-v2');
    await user.click(within(rotDialog).getByRole('button', { name: /Rotate to v2/i }));
    await waitFor(() => {
      expect(screen.getByText('v2')).toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain('rotated-secret-ui-v2');

    // View metadata-only version history
    await user.click(screen.getByRole('button', { name: /Version history for DEPLOY_API_KEY/i }));
    const histDialog = await screen.findByRole('dialog');
    await waitFor(() => {
      expect(within(histDialog).getByText('v2')).toBeInTheDocument();
      expect(within(histDialog).getByText('v1')).toBeInTheDocument();
    });
    await user.click(within(histDialog).getByRole('button', { name: /Close dialog/i }));

    // Revoke secret
    await user.click(screen.getByRole('button', { name: /^Revoke$/i }));
    const revokeDialog = await screen.findByRole('dialog');
    await user.click(within(revokeDialog).getByRole('button', { name: /Confirm Revoke/i }));
    await waitFor(() => {
      expect(screen.getByText('Revoked')).toBeInTheDocument();
    });

    // Delete secret
    await user.click(screen.getByRole('button', { name: /^Delete$/i }));
    const delDialog = await screen.findByRole('dialog');
    await user.click(within(delDialog).getByRole('button', { name: /^Delete Secret$/i }));
    await waitFor(() => {
      expect(screen.queryByText('DEPLOY_API_KEY')).not.toBeInTheDocument();
    });
  }, 15000);

  it('enforces fail-closed disabled state and OWNER-only RBAC restrictions cleanly', async () => {
    setupSecretsUiMocks({
      role: 'DEVELOPER',
      status: {
        ...STATUS_ENABLED,
        enabled: false,
        available: false,
        activeKeyId: null,
        statusDiagnostic: 'Encrypted secret management is disabled (CLOUDFORGE_SECRETS_ENABLED=false)',
      },
    });

    render(
      <MemoryRouter initialEntries={['/projects/proj-1']}>
        <AppRoutes />
      </MemoryRouter>
    );

    const notice = await screen.findByTestId('secrets-rbac-notice');
    expect(notice).toHaveTextContent(/restricted for role DEVELOPER/i);
    expect(await screen.findByTestId('secret-subsystem-status')).toHaveTextContent('Secret Management Disabled');
    expect(screen.queryByRole('button', { name: /Add Secret/i })).not.toBeInTheDocument();
  }, 15000);
});
