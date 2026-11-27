import { Toast } from '../components/Toast';
import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  AlertCircle,
  ArrowLeft,
  FolderKanban,
  GitBranch,
  Plus,
  Save,
  ShieldAlert,
  Sliders,
  Trash2,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { EnvironmentSecretsPanel } from '../components/EnvironmentSecretsPanel';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import { useAuth } from '../context/AuthContext';
import {
  ApiClientError,
  fetchProjectDetail,
  updateProjectEnvironment,
} from '../services/api';
import type {
  EnvironmentType,
  ProjectDetail,
  ProjectEnvironment,
} from '../types/platform';
import './Pages.css';

interface VariableEntry {
  key: string;
  value: string;
}

export function ProjectDetailPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { activeWorkspace, activeRole } = useAuth();

  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [selectedEnvId, setSelectedEnvId] = useState<string | null>(null);
  const [envName, setEnvName] = useState('');
  const [branchOverride, setBranchOverride] = useState('');
  const [notes, setNotes] = useState('');
  const [variables, setVariables] = useState<VariableEntry[]>([]);
  const [isSavingEnv, setIsSavingEnv] = useState(false);
  const [envSaveError, setEnvSaveError] = useState<string | null>(null);
  const [envFieldErrors, setEnvFieldErrors] = useState<Record<string, string>>({});
  const [envSaveSuccess, setEnvSaveSuccess] = useState<string | null>(null);

  const hydrateEnvForm = useCallback((env: ProjectEnvironment) => {
    setSelectedEnvId(env.id);
    setEnvName(env.name);
    setBranchOverride(env.branchOverride ?? '');
    setNotes(env.notes ?? '');
    setVariables(
      Object.entries(env.configVariables).map(([key, value]) => ({ key, value }))
    );
    setEnvSaveError(null);
    setEnvFieldErrors({});
    setEnvSaveSuccess(null);
  }, []);

  const loadDetail = useCallback(async () => {
    if (!activeWorkspace || !projectId) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const detail = await fetchProjectDetail(activeWorkspace.id, projectId);
      setProject(detail);
      if (detail.environments.length > 0) {
        const current =
          detail.environments.find((e) => e.id === selectedEnvId) ??
          detail.environments[0];
        hydrateEnvForm(current);
      }
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to load project detail.'
      );
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace, projectId, selectedEnvId, hydrateEnvForm]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  const selectedEnv =
    project?.environments.find((e) => e.id === selectedEnvId) ?? null;

  const canEditEnvironment = (envType: EnvironmentType | undefined): boolean => {
    if (!envType) return false;
    if (activeRole === 'OWNER') return true;
    return activeRole === 'DEVELOPER' && envType === 'DEVELOPMENT';
  };

  const editable = canEditEnvironment(selectedEnv?.environmentType);

  const handleSaveEnvironment = async () => {
    if (!activeWorkspace || !project || !selectedEnv) return;
    setIsSavingEnv(true);
    setEnvSaveError(null);
    setEnvFieldErrors({});
    setEnvSaveSuccess(null);

    const configMap: Record<string, string> = {};
    for (const row of variables) {
      const k = row.key.trim();
      if (k) {
        configMap[k] = row.value;
      }
    }

    try {
      const updated = await updateProjectEnvironment(
        activeWorkspace.id,
        project.id,
        selectedEnv.id,
        {
          name: envName.trim() || selectedEnv.name,
          branchOverride: branchOverride.trim() || null,
          configVariables: configMap,
          notes: notes.trim() || null,
        }
      );

      setProject((prev) =>
        prev
          ? {
              ...prev,
              environments: prev.environments.map((env) =>
                env.id === updated.id ? updated : env
              ),
            }
          : prev
      );
      hydrateEnvForm(updated);
      setEnvSaveSuccess(
        `${updated.name} environment configuration saved (no deployment triggered).`
      );
    } catch (error) {
      if (error instanceof ApiClientError) {
        setEnvSaveError(error.message);
        setEnvFieldErrors(error.fieldErrors);
      } else {
        setEnvSaveError('Failed to save environment configuration.');
      }
    } finally {
      setIsSavingEnv(false);
    }
  };

  if (isLoading) {
    return (
      <PageContainer title="Project Detail" description="Loading project details...">
        <LoadingState
          title="Loading project"
          description="Fetching project metadata and environment configurations..."
        />
      </PageContainer>
    );
  }

  if (errorMessage || !project) {
    return (
      <PageContainer title="Project Detail" description="Unable to load project.">
        <ErrorState
          title="Project could not be loaded"
          message={errorMessage ?? 'Project was not found in your active workspace.'}
          onRetry={() => {
            void loadDetail();
          }}
        />
        <div>
          <button
            type="button"
            className="btn"
            onClick={() => navigate('/projects')}
          >
            <ArrowLeft size={14} aria-hidden="true" />
            <span>Back to Projects</span>
          </button>
        </div>
      </PageContainer>
    );
  }

  return (
    <PageContainer
      title={project.name}
      description={project.description || `Project slug: ${project.slug}`}
      phaseBadge={{ label: project.status, tone: project.status === 'ACTIVE' ? 'healthy' : 'neutral' }}
      actions={
        <Link to="/projects" className="btn">
          <ArrowLeft size={14} aria-hidden="true" />
          <span>Back to Projects</span>
        </Link>
      }
    >
      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="Project Metadata"
          subtitle="Persistent workspace project definition"
          icon={<FolderKanban size={17} />}
        >
          <dl className="cf-kv-list">
            <div className="cf-kv-item">
              <dt>Workspace</dt>
              <dd>{project.workspaceName}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Slug</dt>
              <dd className="mono">{project.slug}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Default Branch</dt>
              <dd className="mono">{project.defaultBranch}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Created By</dt>
              <dd className="mono">
                {project.createdByUsername ? `@${project.createdByUsername}` : '—'}
              </dd>
            </div>
            <div className="cf-kv-item">
              <dt>Created At</dt>
              <dd className="mono">{new Date(project.createdAt).toLocaleString()}</dd>
            </div>
            <div className="cf-kv-item">
              <dt>Last Updated</dt>
              <dd className="mono">{new Date(project.updatedAt).toLocaleString()}</dd>
            </div>
          </dl>
        </DashboardCard>

        <DashboardCard
          title="Linked GitHub Repository"
          subtitle="Authorized GitHub App source binding"
          icon={<GitBranch size={17} />}
          headerAction={
            project.linkedRepository ? (
              <StatusBadge
                label={project.linkedRepository.connectionStatus}
                tone={
                  project.linkedRepository.connectionStatus === 'CONNECTED'
                    ? 'healthy'
                    : 'degraded'
                }
                size="sm"
              />
            ) : (
              <StatusBadge label="Unlinked" tone="neutral" size="sm" />
            )
          }
        >
          {project.linkedRepository ? (
            <dl className="cf-kv-list">
              <div className="cf-kv-item">
                <dt>Repository</dt>
                <dd className="mono">{project.linkedRepository.fullName}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Visibility</dt>
                <dd className="mono">{project.linkedRepository.visibility}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Default Branch</dt>
                <dd className="mono">{project.linkedRepository.defaultBranch}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Last Synced</dt>
                <dd className="mono">
                  {new Date(project.linkedRepository.lastSyncedAt).toLocaleString()}
                </dd>
              </div>
            </dl>
          ) : (
            <p className="cf-muted" style={{ margin: 0 }}>
              No GitHub repository is linked to this project yet. Visit{' '}
              <Link to="/repositories" className="cf-link-accent">
                Repositories
              </Link>{' '}
              or edit this project to associate an authorized repository.
            </p>
          )}
        </DashboardCard>
      </div>

      <DashboardCard
        title="Persistent Environment Configurations"
        subtitle="Manage non-secret key/value settings per environment (Development, Staging, Production)"
        icon={<Sliders size={17} />}
        headerAction={
          <StatusBadge label="Non-Secret Config Only" tone="info" size="sm" />
        }
      >
        <div className="cf-notice cf-notice--warning" style={{ marginBottom: '16px' }}>
          <div className="cf-notice__icon" aria-hidden="true">
            <ShieldAlert size={18} />
          </div>
          <div>
            <p className="cf-notice__title">
              Plaintext secrets are blocked here — use Encrypted Environment Secrets below
            </p>
            <p className="cf-notice__text">
              Do not store passwords, API keys, or private tokens in plain config variables.
              The backend rejects secret-like keys and credential values here; store sensitive
              credentials in the AES-256-GCM Encrypted Secrets vault below.
            </p>
          </div>
        </div>

        <div className="cf-tabs" role="tablist" aria-label="Project environments">
          {project.environments.map((env) => (
            <button
              key={env.id}
              type="button"
              role="tab"
              aria-selected={env.id === selectedEnvId}
              className={`cf-tab ${env.id === selectedEnvId ? 'cf-tab--active' : ''}`}
              onClick={() => hydrateEnvForm(env)}
            >
              <span>{env.name}</span>
              <StatusBadge
                label={`${Object.keys(env.configVariables).length} vars`}
                tone="neutral"
                size="sm"
              />
            </button>
          ))}
        </div>

        {selectedEnv && (
          <div className="cf-form" style={{ marginTop: '16px' }}>
            {!editable && (
              <div className="cf-notice cf-notice--info">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertCircle size={17} />
                </div>
                <div>
                  <p className="cf-notice__title">Read-only view for role {activeRole}</p>
                  <p className="cf-notice__text">
                    {activeRole === 'DEVELOPER'
                      ? 'DEVELOPER members can edit Development environments; Staging and Production require OWNER authorization.'
                      : 'VIEWER members have read-only access to project environments.'}
                  </p>
                </div>
              </div>
            )}

            {envSaveSuccess && <Toast message={envSaveSuccess} onDismiss={() => setEnvSaveSuccess(null)} />}

            {envSaveError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertCircle size={17} />
                </div>
                <div>
                  <p className="cf-notice__title">{envSaveError}</p>
                  {Object.keys(envFieldErrors).length > 0 && (
                    <ul style={{ margin: '6px 0 0', paddingLeft: '18px' }}>
                      {Object.entries(envFieldErrors).map(([k, v]) => (
                        <li key={k} className="cf-notice__text">
                          <span className="mono">{k}</span>: {v}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            )}

            <div className="cf-grid cf-grid--2">
              <div className="cf-field">
                <label htmlFor="cf-env-name" className="cf-field__label">
                  Environment Display Name
                </label>
                <input
                  id="cf-env-name"
                  type="text"
                  className="cf-input"
                  value={envName}
                  disabled={!editable}
                  onChange={(e) => setEnvName(e.target.value)}
                />
              </div>
              <div className="cf-field">
                <label htmlFor="cf-env-branch" className="cf-field__label">
                  Branch Override (optional)
                </label>
                <input
                  id="cf-env-branch"
                  type="text"
                  className="cf-input mono"
                  placeholder={`Defaults to ${project.defaultBranch}`}
                  value={branchOverride}
                  disabled={!editable}
                  onChange={(e) => setBranchOverride(e.target.value)}
                />
              </div>
            </div>

            <div className="cf-field">
              <label htmlFor="cf-env-notes" className="cf-field__label">
                Operational Notes
              </label>
              <input
                id="cf-env-notes"
                type="text"
                className="cf-input"
                placeholder="Non-sensitive runtime notes (e.g. target region, replica intent)"
                value={notes}
                disabled={!editable}
                onChange={(e) => setNotes(e.target.value)}
              />
            </div>

            <div className="cf-field">
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <span className="cf-field__label">Non-Secret Configuration Variables</span>
                {editable && (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setVariables((prev) => [...prev, { key: '', value: '' }])}
                  >
                    <Plus size={13} aria-hidden="true" />
                    <span>Add Variable</span>
                  </button>
                )}
              </div>

              {variables.length === 0 ? (
                <p className="cf-muted" style={{ margin: '4px 0', fontSize: '12.5px' }}>
                  No configuration variables defined for {selectedEnv.name} yet.
                </p>
              ) : (
                <div className="cf-form" style={{ gap: '8px' }}>
                  {variables.map((entry, idx) => (
                    <div key={idx} className="cf-env-var-row">
                      <input
                        type="text"
                        className="cf-input mono"
                        placeholder="KEY_NAME (e.g. LOG_LEVEL)"
                        aria-label={`Variable key ${idx + 1}`}
                        value={entry.key}
                        disabled={!editable}
                        onChange={(e) =>
                          setVariables((prev) =>
                            prev.map((item, i) =>
                              i === idx ? { ...item, key: e.target.value } : item
                            )
                          )
                        }
                      />
                      <input
                        type="text"
                        className="cf-input mono"
                        placeholder="Non-secret value"
                        aria-label={`Variable value ${idx + 1}`}
                        value={entry.value}
                        disabled={!editable}
                        onChange={(e) =>
                          setVariables((prev) =>
                            prev.map((item, i) =>
                              i === idx ? { ...item, value: e.target.value } : item
                            )
                          )
                        }
                      />
                      {editable && (
                        <button
                          type="button"
                          className="btn btn-sm btn-danger"
                          aria-label={`Remove variable ${idx + 1}`}
                          onClick={() =>
                            setVariables((prev) => prev.filter((_, i) => i !== idx))
                          }
                        >
                          <Trash2 size={13} aria-hidden="true" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {editable && (
              <div className="cf-inline-actions cf-inline-actions--end">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => void handleSaveEnvironment()}
                  disabled={isSavingEnv}
                >
                  <Save size={14} aria-hidden="true" />
                  <span>{isSavingEnv ? 'Saving...' : `Save ${selectedEnv.name} Config`}</span>
                </button>
              </div>
            )}
          </div>
        )}
      </DashboardCard>

      {selectedEnv && activeWorkspace && (
        <EnvironmentSecretsPanel
          workspaceId={activeWorkspace.id}
          projectId={project.id}
          environment={selectedEnv}
          isOwner={activeRole === 'OWNER'}
          activeRole={activeRole}
        />
      )}
    </PageContainer>
  );
}
