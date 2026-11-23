import { ValidatedForm } from '../components/ValidatedForm';
import { Appearance } from '../components/Appearance';
import { Toast } from '../components/Toast';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  AlertCircle,
  Building2,
  ClipboardList,
  Github,
  Plus,
  ServerCog,
  ShieldCheck,
  Trash2,
  User,
  UserPlus,
  Users,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import { useAuth } from '../context/AuthContext';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import {
  addWorkspaceMember,
  createWorkspace,
  fetchAuditEvents,
  fetchWorkspaceMembers,
  removeWorkspaceMember,
  updateWorkspace,
  updateWorkspaceMemberRole,
} from '../services/api';
import type {
  AuditEvent,
  WorkspaceMember,
  WorkspaceRole,
} from '../types/platform';
import './Pages.css';

export interface SettingsPageProps {
  healthState: BackendHealthState;
}

export function SettingsPage({ healthState }: SettingsPageProps) {
  const {
    user,
    authConfig,
    activeWorkspace,
    activeRole,
    setActiveWorkspaceId,
    refreshSession,
  } = useAuth();
  const isOwner = activeRole === 'OWNER';
  const payload = healthState.snapshot?.payload ?? null;

  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [membersLoading, setMembersLoading] = useState(false);

  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditPage, setAuditPage] = useState(0);
  const [auditTotalPages, setAuditTotalPages] = useState(0);
  const [auditActionFilter, setAuditActionFilter] = useState('');

  // Workspace update form
  const [wsName, setWsName] = useState('');
  const [wsDescription, setWsDescription] = useState('');
  const [isSavingWs, setIsSavingWs] = useState(false);

  // Create new workspace form
  const [newWsName, setNewWsName] = useState('');
  const [newWsDesc, setNewWsDesc] = useState('');
  const [isCreatingWs, setIsCreatingWs] = useState(false);

  // Add member form
  const [inviteUsername, setInviteUsername] = useState('');
  const [inviteRole, setInviteRole] = useState<WorkspaceRole>('DEVELOPER');
  const [isInviting, setIsInviting] = useState(false);

  const [notice, setNotice] = useState<string | null>(null);
  const [errorBanner, setErrorBanner] = useState<string | null>(null);

  useEffect(() => {
    if (activeWorkspace) {
      setWsName(activeWorkspace.name);
      setWsDescription(activeWorkspace.description ?? '');
    }
  }, [activeWorkspace]);

  const loadMembers = useCallback(async () => {
    if (!activeWorkspace) {
      setMembers([]);
      return;
    }
    setMembersLoading(true);
    try {
      const list = await fetchWorkspaceMembers(activeWorkspace.id);
      setMembers(list);
    } catch {
      setMembers([]);
    } finally {
      setMembersLoading(false);
    }
  }, [activeWorkspace]);

  const loadAudit = useCallback(async () => {
    if (!activeWorkspace || !isOwner) {
      setAuditEvents([]);
      return;
    }
    setAuditLoading(true);
    try {
      const res = await fetchAuditEvents(activeWorkspace.id, {
        action: auditActionFilter || undefined,
        page: auditPage,
        size: 15,
      });
      setAuditEvents(res.items);
      setAuditTotalPages(res.totalPages);
    } catch {
      setAuditEvents([]);
    } finally {
      setAuditLoading(false);
    }
  }, [activeWorkspace, isOwner, auditActionFilter, auditPage]);

  useEffect(() => {
    void loadMembers();
  }, [loadMembers]);

  useEffect(() => {
    void loadAudit();
  }, [loadAudit]);

  const handleUpdateWorkspace = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace) return;
    setIsSavingWs(true);
    setNotice(null);
    setErrorBanner(null);
    try {
      await updateWorkspace(activeWorkspace.id, {
        name: wsName.trim(),
        description: wsDescription.trim() || undefined,
      });
      setNotice('Workspace settings updated.');
      await Promise.all([refreshSession(), loadAudit()]);
    } catch (error) {
      setErrorBanner(
        error instanceof Error ? error.message : 'Failed to update workspace.'
      );
    } finally {
      setIsSavingWs(false);
    }
  };

  const handleCreateWorkspace = async (event: FormEvent) => {
    event.preventDefault();
    if (newWsName.trim().length < 2) return;
    setIsCreatingWs(true);
    setNotice(null);
    setErrorBanner(null);
    try {
      const created = await createWorkspace({
        name: newWsName.trim(),
        description: newWsDesc.trim() || undefined,
      });
      setNewWsName('');
      setNewWsDesc('');
      await refreshSession();
      setActiveWorkspaceId(created.id);
      setNotice(`Created and switched to workspace "${created.name}".`);
    } catch (error) {
      setErrorBanner(
        error instanceof Error ? error.message : 'Failed to create workspace.'
      );
    } finally {
      setIsCreatingWs(false);
    }
  };

  const handleAddMember = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || !inviteUsername.trim()) return;
    setIsInviting(true);
    setNotice(null);
    setErrorBanner(null);
    try {
      const added = await addWorkspaceMember(activeWorkspace.id, {
        username: inviteUsername.trim(),
        role: inviteRole,
      });
      setInviteUsername('');
      setNotice(`Added @${added.username} as ${added.role}.`);
      await Promise.all([loadMembers(), loadAudit(), refreshSession()]);
    } catch (error) {
      setErrorBanner(
        error instanceof Error ? error.message : 'Failed to add workspace member.'
      );
    } finally {
      setIsInviting(false);
    }
  };

  const handleRoleChange = async (member: WorkspaceMember, newRole: WorkspaceRole) => {
    if (!activeWorkspace) return;
    setNotice(null);
    setErrorBanner(null);
    try {
      await updateWorkspaceMemberRole(activeWorkspace.id, member.id, newRole);
      setNotice(`Updated @${member.username} role to ${newRole}.`);
      await Promise.all([loadMembers(), loadAudit(), refreshSession()]);
    } catch (error) {
      setErrorBanner(
        error instanceof Error ? error.message : 'Failed to change member role.'
      );
    }
  };

  const handleRemoveMember = async (member: WorkspaceMember) => {
    if (!activeWorkspace) return;
    setNotice(null);
    setErrorBanner(null);
    try {
      await removeWorkspaceMember(activeWorkspace.id, member.id);
      setNotice(`Removed @${member.username} from workspace.`);
      await Promise.all([loadMembers(), loadAudit(), refreshSession()]);
    } catch (error) {
      setErrorBanner(
        error instanceof Error ? error.message : 'Failed to remove workspace member.'
      );
    }
  };

  const memberColumns: TableColumn<WorkspaceMember>[] = [
    {
      key: 'user',
      header: 'Member',
      render: (row) => (
        <div>
          <strong>{row.displayName || row.username}</strong>
          <div className="mono cf-muted" style={{ fontSize: '11.5px' }}>
            @{row.username}
          </div>
        </div>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      render: (row) =>
        isOwner ? (
          <select
            className="cf-filter-select"
            aria-label={`Role for ${row.username}`}
            value={row.role}
            onChange={(e) =>
              void handleRoleChange(row, e.target.value as WorkspaceRole)
            }
          >
            <option value="OWNER">OWNER</option>
            <option value="DEVELOPER">DEVELOPER</option>
            <option value="VIEWER">VIEWER</option>
          </select>
        ) : (
          <StatusBadge label={row.role} tone="info" size="sm" />
        ),
    },
    {
      key: 'joined',
      header: 'Joined',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {new Date(row.joinedAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) =>
        isOwner ? (
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => void handleRemoveMember(row)}
            aria-label={`Remove ${row.username}`}
          >
            <Trash2 size={12} aria-hidden="true" />
            <span>Remove</span>
          </button>
        ) : (
          <span className="cf-muted">—</span>
        ),
    },
  ];

  const auditColumns: TableColumn<AuditEvent>[] = [
    {
      key: 'time',
      header: 'Timestamp',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '11.5px' }}>
          {new Date(row.createdAt).toLocaleString()}
        </span>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) => <StatusBadge label={row.action} tone="info" size="sm" />,
    },
    {
      key: 'actor',
      header: 'Actor',
      render: (row) => (
        <span className="mono">
          {row.actorUsername ? `@${row.actorUsername}` : 'system'}
        </span>
      ),
    },
    {
      key: 'summary',
      header: 'Summary',
      render: (row) => <span>{row.summary}</span>,
    },
  ];

  const initials = (user?.displayName || user?.username || 'U')
    .slice(0, 2)
    .toUpperCase();

  return (
    <PageContainer
      title="Settings"
      description="Account profile, workspace RBAC membership, GitHub integration status, and workspace audit history."
      phaseBadge={{ label: 'Phase 2 Identity & RBAC', tone: 'healthy' }}
    >
      <Appearance />
      {notice && <Toast message={notice} onDismiss={() => setNotice(null)} />}

      {errorBanner && (
        <div className="cf-notice cf-notice--error" role="alert">
          <div className="cf-notice__icon" aria-hidden="true">
            <AlertCircle size={18} />
          </div>
          <div>
            <p className="cf-notice__title">Action failed</p>
            <p className="cf-notice__text">{errorBanner}</p>
          </div>
        </div>
      )}

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Authenticated Profile"
          subtitle="Persisted GitHub OAuth identity (no browser tokens)"
          icon={<User size={17} />}
          headerAction={<StatusBadge label="GitHub OAuth" tone="healthy" size="sm" />}
        >
          <div className="cf-profile-banner" style={{ marginBottom: '14px' }}>
            {user?.avatarUrl ? (
              <img
                src={user.avatarUrl}
                alt={`@${user.username}`}
                className="cf-avatar"
              />
            ) : (
              <span className="cf-avatar-fallback" aria-hidden="true">
                {initials}
              </span>
            )}
            <div>
              <div style={{ fontWeight: 600, fontSize: '15px' }}>
                {user?.displayName || user?.username}
              </div>
              <div className="mono cf-muted" style={{ fontSize: '12.5px' }}>
                @{user?.username} · GitHub ID #{user?.githubUserId}
              </div>
            </div>
          </div>

          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Email</dt>
              <dd className="mono">{user?.email || 'Private / not provided'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Last Login</dt>
              <dd className="mono">
                {user?.lastLoginAt
                  ? new Date(user.lastLoginAt).toLocaleString()
                  : '—'}
              </dd>
            </div>
          </dl>
        </DashboardCard>

        <DashboardCard
          title="GitHub & Runtime Integration Status"
          subtitle="Non-sensitive authentication and platform runtime status"
          icon={<Github size={17} />}
          headerAction={<StatusBadge label="Security Enforced" tone="info" size="sm" />}
        >
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>GitHub OAuth Login</dt>
              <dd>
                <StatusBadge
                  label={authConfig?.githubOAuthConfigured ? 'Configured' : 'Setup Required'}
                  tone={authConfig?.githubOAuthConfigured ? 'healthy' : 'degraded'}
                  size="sm"
                />
              </dd>
            </div>
            <div className="cf-kv-item">
              <dt>GitHub App Integration</dt>
              <dd>
                <StatusBadge
                  label={authConfig?.githubAppConfigured ? 'Configured' : 'Setup Required'}
                  tone={authConfig?.githubAppConfigured ? 'healthy' : 'degraded'}
                  size="sm"
                />
              </dd>
            </div>
            <div className="cf-kv-item">
              <dt>Backend Version</dt>
              <dd className="mono">{payload?.version ?? '—'}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Session &amp; CSRF</dt>
              <dd className="mono">HttpOnly Cookie + X-XSRF-TOKEN</dd>
            </div>
          </dl>
        </DashboardCard>
      </div>

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Active Workspace Settings"
          subtitle={
            isOwner
              ? 'Update active workspace name and description'
              : 'Read-only (OWNER authorization required to edit workspace settings)'
          }
          icon={<Building2 size={17} />}
          headerAction={
            activeWorkspace ? (
              <StatusBadge label={`Role: ${activeWorkspace.role}`} tone="info" size="sm" />
            ) : undefined
          }
        >
          <ValidatedForm className="cf-form" onSubmit={(e) => void handleUpdateWorkspace(e)}>
            <div className="cf-field">
              <label htmlFor="cf-ws-name" className="cf-field__label">
                Workspace Name
              </label>
              <input
                id="cf-ws-name"
                type="text"
                className="cf-input"
                required
                minLength={2}
                disabled={!isOwner}
                value={wsName}
                onChange={(e) => setWsName(e.target.value)}
              />
            </div>
            <div className="cf-field">
              <label htmlFor="cf-ws-desc" className="cf-field__label">
                Description
              </label>
              <input
                id="cf-ws-desc"
                type="text"
                className="cf-input"
                disabled={!isOwner}
                value={wsDescription}
                onChange={(e) => setWsDescription(e.target.value)}
              />
            </div>
            {isOwner && (
              <div className="cf-inline-actions cf-inline-actions--end">
                <button type="submit" className="btn btn-primary" disabled={isSavingWs}>
                  {isSavingWs ? 'Saving...' : 'Save Workspace'}
                </button>
              </div>
            )}
          </ValidatedForm>
        </DashboardCard>

        <DashboardCard
          title="Create New Workspace"
          subtitle="Provision a dedicated team workspace where you start as OWNER"
          icon={<Plus size={17} />}
        >
          <ValidatedForm className="cf-form" onSubmit={(e) => void handleCreateWorkspace(e)}>
            <div className="cf-field">
              <label htmlFor="cf-new-ws-name" className="cf-field__label">
                New Workspace Name *
              </label>
              <input
                id="cf-new-ws-name"
                type="text"
                className="cf-input"
                required
                minLength={2}
                placeholder="e.g. Platform Engineering"
                value={newWsName}
                onChange={(e) => setNewWsName(e.target.value)}
              />
            </div>
            <div className="cf-field">
              <label htmlFor="cf-new-ws-desc" className="cf-field__label">
                Description
              </label>
              <input
                id="cf-new-ws-desc"
                type="text"
                className="cf-input"
                placeholder="Shared services and cloud infrastructure"
                value={newWsDesc}
                onChange={(e) => setNewWsDesc(e.target.value)}
              />
            </div>
            <div className="cf-inline-actions cf-inline-actions--end">
              <button type="submit" className="btn btn-primary" disabled={isCreatingWs}>
                <Plus size={14} aria-hidden="true" />
                <span>{isCreatingWs ? 'Creating...' : 'Create Workspace'}</span>
              </button>
            </div>
          </ValidatedForm>
        </DashboardCard>
      </div>

      <DashboardCard
        title="Workspace Members & RBAC Roles"
        subtitle="Backend-enforced authorization: OWNER (full admin), DEVELOPER (projects & dev config), VIEWER (read-only)"
        icon={<Users size={17} />}
        headerAction={
          <StatusBadge label={`${members.length} Members`} tone="info" size="sm" />
        }
      >
        {isOwner && (
          <ValidatedForm
            className="cf-toolbar"
            onSubmit={(e) => void handleAddMember(e)}
            style={{ marginBottom: '16px' }}
          >
            <input
              type="text"
              className="cf-search-input"
              placeholder="GitHub username of existing CloudForge user..."
              aria-label="Member GitHub username"
              value={inviteUsername}
              onChange={(e) => setInviteUsername(e.target.value)}
              required
            />
            <div className="cf-filter-group">
              <select
                className="cf-filter-select"
                aria-label="New member role"
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as WorkspaceRole)}
              >
                <option value="DEVELOPER">DEVELOPER</option>
                <option value="VIEWER">VIEWER</option>
                <option value="OWNER">OWNER</option>
              </select>
              <button type="submit" className="btn btn-primary" disabled={isInviting}>
                <UserPlus size={14} aria-hidden="true" />
                <span>{isInviting ? 'Adding...' : 'Add Member'}</span>
              </button>
            </div>
          </ValidatedForm>
        )}

        {membersLoading ? (
          <LoadingState
            title="Loading workspace members"
            description="Fetching role assignments..."
          />
        ) : (
          <DataTable
            columns={memberColumns}
            rows={members}
            getRowKey={(row) => row.id}
            emptyMessage="No members found in this workspace."
            caption="Workspace members"
          />
        )}
      </DashboardCard>

      <DashboardCard
        title="Workspace Audit History"
        subtitle="Immutable PostgreSQL audit trail of authentication, RBAC, project, environment, and GitHub events"
        icon={<ClipboardList size={17} />}
        headerAction={
          <StatusBadge
            label={isOwner ? 'Owner Access' : 'Owner Only'}
            tone={isOwner ? 'healthy' : 'neutral'}
            size="sm"
          />
        }
      >
        {!isOwner ? (
          <div className="cf-notice cf-notice--info">
            <div className="cf-notice__icon" aria-hidden="true">
              <ShieldCheck size={18} />
            </div>
            <div>
              <p className="cf-notice__title">OWNER Role Required</p>
              <p className="cf-notice__text">
                Workspace audit logs are restricted to OWNER members and enforced on the
                backend (`GET /api/workspaces/{'{workspaceId}'}/audit-events`).
              </p>
            </div>
          </div>
        ) : (
          <>
            <div className="cf-toolbar">
              <select
                className="cf-filter-select"
                aria-label="Filter audit events by action"
                value={auditActionFilter}
                onChange={(e) => {
                  setAuditPage(0);
                  setAuditActionFilter(e.target.value);
                }}
              >
                <option value="">All audit actions</option>
                <option value="USER_LOGIN">USER_LOGIN</option>
                <option value="USER_REGISTERED">USER_REGISTERED</option>
                <option value="USER_LOGOUT">USER_LOGOUT</option>
                <option value="PROJECT_CREATED">PROJECT_CREATED</option>
                <option value="PROJECT_UPDATED">PROJECT_UPDATED</option>
                <option value="PROJECT_DELETED">PROJECT_DELETED</option>
                <option value="ENVIRONMENT_UPDATED">ENVIRONMENT_UPDATED</option>
                <option value="MEMBER_ADDED">MEMBER_ADDED</option>
                <option value="MEMBER_ROLE_UPDATED">MEMBER_ROLE_UPDATED</option>
                <option value="GITHUB_INSTALLATION_LINKED">GITHUB_INSTALLATION_LINKED</option>
                <option value="GITHUB_INSTALLATION_SYNCED">GITHUB_INSTALLATION_SYNCED</option>
              </select>
            </div>

            {auditLoading ? (
              <LoadingState
                title="Loading audit events"
                description="Querying workspace audit log..."
              />
            ) : (
              <>
                <DataTable
                  columns={auditColumns}
                  rows={auditEvents}
                  getRowKey={(row) => row.id}
                  emptyMessage="No audit events match this filter yet."
                  caption="Workspace audit events"
                />
                {auditTotalPages > 1 && (
                  <div className="cf-pagination">
                    <span>
                      Page {auditPage + 1} of {auditTotalPages}
                    </span>
                    <div className="cf-inline-actions">
                      <button
                        type="button"
                        className="btn btn-sm"
                        disabled={auditPage === 0}
                        onClick={() => setAuditPage((p) => Math.max(0, p - 1))}
                      >
                        Previous
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm"
                        disabled={auditPage + 1 >= auditTotalPages}
                        onClick={() => setAuditPage((p) => p + 1)}
                      >
                        Next
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
          </>
        )}
      </DashboardCard>

      <DashboardCard
        title="Deferred Platform Capabilities (Phases 3–4)"
        subtitle="Explicitly deferred until isolated build workers and KMS integrations are built"
        icon={<ServerCog size={17} />}
        headerAction={<StatusBadge label="Phases 3–4" tone="planned" size="sm" />}
      >
        <div className="cf-grid cf-grid--2">
          <div className="cf-step">
            <span className="cf-step__index">Phase 3</span>
            <h3 className="cf-step__title">Encrypted Secret Store &amp; Clusters</h3>
            <p className="cf-step__desc">
              KMS / External Secrets Manager integration and namespace-scoped Kubernetes deployment targets.
            </p>
          </div>
          <div className="cf-step">
            <span className="cf-step__index">Phase 4</span>
            <h3 className="cf-step__title">Observability &amp; DevSecOps Policy</h3>
            <p className="cf-step__desc">
              Prometheus/Grafana alerting channels, Trivy CVE policy gates, and SBOM retention rules.
            </p>
          </div>
        </div>
      </DashboardCard>
    </PageContainer>
  );
}
