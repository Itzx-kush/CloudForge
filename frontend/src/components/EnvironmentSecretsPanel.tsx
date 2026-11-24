import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  AlertCircle,
  Ban,
  Eye,
  EyeOff,
  History,
  KeyRound,
  Lock,
  Plus,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { DashboardCard } from './DashboardCard';
import { DataTable, type TableColumn } from './DataTable';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';
import { Modal } from './Modal';
import { SearchField } from './SearchField';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import { Toast } from './Toast';
import { ValidatedForm } from './ValidatedForm';
import {
  ApiClientError,
  createEnvironmentSecret,
  deleteEnvironmentSecret,
  fetchEnvironmentSecrets,
  fetchEnvironmentSecretVersions,
  fetchSecretSubsystemStatus,
  revokeEnvironmentSecret,
  rotateEnvironmentSecret,
} from '../services/api';
import type {
  EnvironmentSecretSummary,
  EnvironmentSecretVersion,
  ProjectEnvironment,
  SecretLifecycleStatus,
  SecretSubsystemStatus,
  WorkspaceRole,
} from '../types/platform';
import './EnvironmentSecretsPanel.css';

interface EnvironmentSecretsPanelProps {
  workspaceId: string;
  projectId: string;
  environment: ProjectEnvironment;
  isOwner: boolean;
  activeRole: WorkspaceRole | null;
}

const SECRET_STATUS_META: Record<SecretLifecycleStatus, { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: 'Active', tone: 'healthy' },
  REVOKED: { label: 'Revoked', tone: 'error' },
  DELETED: { label: 'Deleted', tone: 'neutral' },
};

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/**
 * Write-only Encrypted Environment Secrets management panel.
 *
 * Security invariants enforced in UI state:
 * - Secret values are never stored in URL params, localStorage, or sessionStorage.
 * - Secret value inputs are masked by default and cleared immediately on submit or close.
 * - Backend endpoints never return plaintext secret values; this panel displays metadata only.
 */
export function EnvironmentSecretsPanel({
  workspaceId,
  projectId,
  environment,
  isOwner,
  activeRole,
}: EnvironmentSecretsPanelProps) {
  const [status, setStatus] = useState<SecretSubsystemStatus | null>(null);
  const [secrets, setSecrets] = useState<EnvironmentSecretSummary[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; tone: 'success' | 'info' | 'error' } | null>(
    null
  );

  // Create Secret Modal state (cleared strictly on submit/close/env-change)
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createValue, setCreateValue] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [showCreateValue, setShowCreateValue] = useState(false);
  const [isSubmittingCreate, setIsSubmittingCreate] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Rotate Secret Modal state (cleared strictly on submit/close/env-change)
  const [rotatingSecret, setRotatingSecret] = useState<EnvironmentSecretSummary | null>(null);
  const [rotateValue, setRotateValue] = useState('');
  const [rotateDescription, setRotateDescription] = useState('');
  const [showRotateValue, setShowRotateValue] = useState(false);
  const [isSubmittingRotate, setIsSubmittingRotate] = useState(false);
  const [rotateError, setRotateError] = useState<string | null>(null);

  // Version History Modal state
  const [historySecret, setHistorySecret] = useState<EnvironmentSecretSummary | null>(null);
  const [versions, setVersions] = useState<EnvironmentSecretVersion[]>([]);
  const [isLoadingVersions, setIsLoadingVersions] = useState(false);
  const [versionsError, setVersionsError] = useState<string | null>(null);

  // Revoke & Delete confirmation modals
  const [revokingSecret, setRevokingSecret] = useState<{
    secret: EnvironmentSecretSummary;
    versionNumber?: number;
  } | null>(null);
  const [revokeReason, setRevokeReason] = useState('');
  const [isSubmittingRevoke, setIsSubmittingRevoke] = useState(false);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const [deletingSecret, setDeletingSecret] = useState<EnvironmentSecretSummary | null>(null);
  const [isSubmittingDelete, setIsSubmittingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const clearSensitiveDrafts = useCallback(() => {
    setCreateValue('');
    setShowCreateValue(false);
    setRotateValue('');
    setShowRotateValue(false);
  }, []);

  const loadSecretsData = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const subsys = await fetchSecretSubsystemStatus(workspaceId);
      setStatus(subsys);

      if (isOwner) {
        const res = await fetchEnvironmentSecrets(workspaceId, projectId, environment.id, {
          q: searchQuery || undefined,
          page: 0,
          size: 100,
        });
        setSecrets(Array.isArray(res.items) ? res.items : []);
      } else {
        setSecrets([]);
      }
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to load encrypted secret metadata.'
      );
    } finally {
      setIsLoading(false);
    }
  }, [workspaceId, projectId, environment.id, isOwner, searchQuery]);

  useEffect(() => {
    clearSensitiveDrafts();
    setIsCreateOpen(false);
    setRotatingSecret(null);
    setHistorySecret(null);
    setRevokingSecret(null);
    setDeletingSecret(null);
    void loadSecretsData();
  }, [environment.id, loadSecretsData, clearSensitiveDrafts]);

  const closeCreateModal = () => {
    clearSensitiveDrafts();
    setCreateName('');
    setCreateDescription('');
    setCreateError(null);
    setIsCreateOpen(false);
  };

  const openRotateModal = (secret: EnvironmentSecretSummary) => {
    clearSensitiveDrafts();
    setRotatingSecret(secret);
    setRotateDescription(secret.description ?? '');
    setRotateError(null);
  };

  const closeRotateModal = () => {
    clearSensitiveDrafts();
    setRotateDescription('');
    setRotateError(null);
    setRotatingSecret(null);
  };

  const openVersionHistory = async (secret: EnvironmentSecretSummary) => {
    setHistorySecret(secret);
    setVersions([]);
    setVersionsError(null);
    setIsLoadingVersions(true);
    try {
      const items = await fetchEnvironmentSecretVersions(
        workspaceId,
        projectId,
        environment.id,
        secret.id
      );
      setVersions(items);
    } catch (error) {
      setVersionsError(
        error instanceof Error ? error.message : 'Failed to load secret version history.'
      );
    } finally {
      setIsLoadingVersions(false);
    }
  };

  const handleCreateSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!isOwner || isSubmittingCreate) return;
    setIsSubmittingCreate(true);
    setCreateError(null);

    const payloadValue = createValue;
    try {
      const created = await createEnvironmentSecret(workspaceId, projectId, environment.id, {
        name: createName.trim(),
        value: payloadValue,
        description: createDescription.trim() || undefined,
      });
      closeCreateModal();
      setToast({
        message: `Encrypted secret "${created.name}" v${created.currentVersionNumber} saved (${environment.name}).`,
        tone: 'success',
      });
      await loadSecretsData();
    } catch (error) {
      setCreateError(
        error instanceof ApiClientError || error instanceof Error
          ? error.message
          : 'Failed to create encrypted secret.'
      );
    } finally {
      setIsSubmittingCreate(false);
    }
  };

  const handleRotateSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!isOwner || !rotatingSecret || isSubmittingRotate) return;
    setIsSubmittingRotate(true);
    setRotateError(null);

    const target = rotatingSecret;
    const payloadValue = rotateValue;
    try {
      const updated = await rotateEnvironmentSecret(
        workspaceId,
        projectId,
        environment.id,
        target.id,
        {
          value: payloadValue,
          description: rotateDescription.trim() || undefined,
        }
      );
      closeRotateModal();
      setToast({
        message: `Rotated secret "${updated.name}" to v${updated.currentVersionNumber}.`,
        tone: 'success',
      });
      await loadSecretsData();
    } catch (error) {
      setRotateError(
        error instanceof ApiClientError || error instanceof Error
          ? error.message
          : 'Failed to rotate encrypted secret.'
      );
    } finally {
      setIsSubmittingRotate(false);
    }
  };

  const handleConfirmRevoke = async () => {
    if (!isOwner || !revokingSecret || isSubmittingRevoke) return;
    setIsSubmittingRevoke(true);
    setRevokeError(null);
    const { secret, versionNumber } = revokingSecret;
    try {
      await revokeEnvironmentSecret(workspaceId, projectId, environment.id, secret.id, {
        versionNumber,
        reason: revokeReason.trim() || undefined,
      });
      setRevokingSecret(null);
      setRevokeReason('');
      setToast({
        message:
          versionNumber !== undefined
            ? `Revoked v${versionNumber} of secret "${secret.name}".`
            : `Revoked secret "${secret.name}". Bound active pipeline deliveries are now blocked.`,
        tone: 'info',
      });
      if (historySecret && historySecret.id === secret.id) {
        void openVersionHistory(secret);
      }
      await loadSecretsData();
    } catch (error) {
      setRevokeError(error instanceof Error ? error.message : 'Failed to revoke secret.');
    } finally {
      setIsSubmittingRevoke(false);
    }
  };

  const handleConfirmDelete = async () => {
    if (!isOwner || !deletingSecret || isSubmittingDelete) return;
    setIsSubmittingDelete(true);
    setDeleteError(null);
    const target = deletingSecret;
    try {
      await deleteEnvironmentSecret(workspaceId, projectId, environment.id, target.id);
      setDeletingSecret(null);
      setToast({
        message: `Deleted secret "${target.name}" from ${environment.name}.`,
        tone: 'info',
      });
      await loadSecretsData();
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : 'Failed to delete secret.');
    } finally {
      setIsSubmittingDelete(false);
    }
  };

  const canEncrypt = Boolean(status?.enabled && status?.available);

  const columns: TableColumn<EnvironmentSecretSummary>[] = [
    {
      key: 'name',
      header: 'Secret Name (Write-Only)',
      render: (row) => (
        <div>
          <strong className="mono">{row.name}</strong>
          {row.description && (
            <div className="cf-muted" style={{ fontSize: '12px', marginTop: '2px' }}>
              {row.description}
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Lifecycle',
      render: (row) => {
        const meta = SECRET_STATUS_META[row.status];
        return <StatusBadge label={meta.label} tone={meta.tone} size="sm" />;
      },
    },
    {
      key: 'crypto',
      header: 'Version & Encryption',
      render: (row) => (
        <div className="mono" style={{ fontSize: '12px' }}>
          <span>v{row.currentVersionNumber}</span>
          {row.currentKekId && <span className="cf-muted"> · {row.currentKekId}</span>}
          <div className="cf-muted" style={{ fontSize: '11px' }}>
            {row.encryptionAlgorithm} · Value never returned
          </div>
        </div>
      ),
    },
    {
      key: 'rotated',
      header: 'Updated / Rotated',
      render: (row) => (
        <div style={{ fontSize: '12px' }}>
          <div className="mono">{formatDateTime(row.lastRotatedAt ?? row.updatedAt)}</div>
          {row.updatedByUsername && (
            <span className="cf-muted mono">by @{row.updatedByUsername}</span>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <div className="cf-inline-actions cf-inline-actions--end">
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => void openVersionHistory(row)}
            aria-label={`Version history for ${row.name}`}
          >
            <History size={12} aria-hidden="true" />
            <span>Versions</span>
          </button>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!canEncrypt || row.status !== 'ACTIVE'}
            title={
              !canEncrypt
                ? 'Encryption provider unavailable'
                : row.status !== 'ACTIVE'
                  ? 'Revoked secrets cannot be rotated'
                  : `Rotate ${row.name}`
            }
            onClick={() => openRotateModal(row)}
          >
            <RefreshCw size={12} aria-hidden="true" />
            <span>Rotate</span>
          </button>
          {row.status === 'ACTIVE' && (
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => {
                setRevokingSecret({ secret: row });
                setRevokeReason('');
                setRevokeError(null);
              }}
            >
              <Ban size={12} aria-hidden="true" />
              <span>Revoke</span>
            </button>
          )}
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => {
              setDeletingSecret(row);
              setDeleteError(null);
            }}
          >
            <Trash2 size={12} aria-hidden="true" />
            <span>Delete</span>
          </button>
        </div>
      ),
    },
  ];

  return (
    <DashboardCard
      title={`Encrypted Secrets — ${environment.name}`}
      subtitle="AES-256-GCM envelope-encrypted secrets scoped strictly to this environment. Write-only: plaintext values are never returned to the browser."
      icon={<KeyRound size={17} />}
      headerAction={
        isOwner ? (
          <button
            type="button"
            className="btn btn-sm btn-primary"
            disabled={!canEncrypt}
            onClick={() => {
              clearSensitiveDrafts();
              setCreateError(null);
              setIsCreateOpen(true);
            }}
            title={
              !canEncrypt
                ? 'Secret management is disabled or keyring unavailable'
                : `Add encrypted secret to ${environment.name}`
            }
          >
            <Plus size={13} aria-hidden="true" />
            <span>Add Secret</span>
          </button>
        ) : (
          <StatusBadge label="Owner Only" tone="neutral" size="sm" />
        )
      }
    >
      {toast && (
        <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />
      )}

      {status && (
        <div className="cf-secrets-status-bar" data-testid="secret-subsystem-status">
          <div className="cf-secrets-status-bar__meta">
            <StatusBadge
              label={
                status.enabled && status.available
                  ? 'Encryption Active'
                  : status.enabled
                    ? 'Key Provider Unavailable (Fail-Closed)'
                    : 'Secret Management Disabled'
              }
              tone={status.enabled && status.available ? 'healthy' : 'degraded'}
              size="sm"
            />
            <span className="mono">Provider: {status.providerType}</span>
            {status.activeKeyId && <span className="mono">Active KEK: {status.activeKeyId}</span>}
            <span className="mono">
              Worker Delivery: {status.workerDeliveryEnabled ? 'Enabled (Lease-Bound)' : 'Disabled'}
            </span>
          </div>
          {status.statusDiagnostic && (
            <span className="cf-muted" style={{ fontSize: '12px' }}>
              {status.statusDiagnostic}
            </span>
          )}
        </div>
      )}

      {!isOwner ? (
        <div className="cf-notice cf-notice--info" data-testid="secrets-rbac-notice">
          <div className="cf-notice__icon" aria-hidden="true">
            <Lock size={17} />
          </div>
          <div>
            <p className="cf-notice__title">
              Encrypted secrets are restricted for role {activeRole ?? 'MEMBER'}
            </p>
            <p className="cf-notice__text">
              Only workspace OWNERs can create, rotate, revoke, delete, or view metadata for
              encrypted environment secrets. Non-secret configuration variables above remain
              accessible according to your environment role.
            </p>
          </div>
        </div>
      ) : isLoading ? (
        <LoadingState
          title={`Loading ${environment.name} secret metadata...`}
          description="Querying environment secret metadata (plaintext values are never returned)..."
        />
      ) : errorMessage ? (
        <ErrorState
          title="Unable to load secret metadata"
          message={errorMessage}
          onRetry={() => void loadSecretsData()}
        />
      ) : (
        <>
          {!canEncrypt && (
            <div className="cf-notice cf-notice--warning" style={{ marginBottom: '14px' }}>
              <div className="cf-notice__icon" aria-hidden="true">
                <ShieldAlert size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Fail-closed encryption guard active</p>
                <p className="cf-notice__text">
                  Creating or rotating secrets is disabled because no valid external AES-256 keyring
                  is configured or active. Existing secret metadata can still be audited, revoked,
                  or deleted safely.
                </p>
              </div>
            </div>
          )}

          <div className="cf-secrets-toolbar">
            <SearchField
              label="Filter secret names"
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder="Filter secret names..."
            />
            <span className="cf-muted mono" style={{ fontSize: '12px' }}>
              {secrets.length} secret{secrets.length === 1 ? '' : 's'} in {environment.name}
            </span>
          </div>

          <DataTable<EnvironmentSecretSummary>
            columns={columns}
            rows={secrets}
            getRowKey={(row) => row.id}
            emptyMessage={`No encrypted secrets in ${environment.name}. Add write-only envelope-encrypted secrets for approved worker stages.`}
          />
        </>
      )}

      {/* Write-Only Add Secret Modal */}
      <Modal
        isOpen={isCreateOpen}
        title={`Add Encrypted Secret — ${environment.name}`}
        subtitle="Encrypted immediately with AES-256-GCM before storage. Value cannot be read back later."
        onClose={closeCreateModal}
      >
        <ValidatedForm className="cf-form" onSubmit={(e) => void handleCreateSubmit(e)}>
          {createError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertCircle size={17} />
              </div>
              <p className="cf-notice__text">{createError}</p>
            </div>
          )}

          <div className="cf-field">
            <label htmlFor="cf-secret-name" className="cf-field__label">
              Secret Name (ENV Style) *
            </label>
            <input
              id="cf-secret-name"
              type="text"
              className="cf-input mono"
              required
              pattern="^[A-Z][A-Z0-9_]{0,127}$"
              placeholder="e.g. DEPLOY_API_KEY"
              autoComplete="off"
              spellCheck={false}
              value={createName}
              onChange={(e) => setCreateName(e.target.value.toUpperCase())}
            />
          </div>

          <div className="cf-field">
            <label htmlFor="cf-secret-value" className="cf-field__label">
              Secret Value (Write-Only) *
            </label>
            <div className="cf-secret-input-wrap">
              <input
                id="cf-secret-value"
                type={showCreateValue ? 'text' : 'password'}
                className="cf-input mono"
                required
                placeholder="Enter secret value (never echoed back after save)"
                autoComplete="new-password"
                spellCheck={false}
                value={createValue}
                onChange={(e) => setCreateValue(e.target.value)}
              />
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setShowCreateValue((v) => !v)}
                aria-label={showCreateValue ? 'Hide secret value' : 'Show secret value while typing'}
              >
                {showCreateValue ? <EyeOff size={14} /> : <Eye size={14} />}
              </button>
            </div>
          </div>

          <div className="cf-field">
            <label htmlFor="cf-secret-desc" className="cf-field__label">
              Description (Optional Metadata)
            </label>
            <input
              id="cf-secret-desc"
              type="text"
              className="cf-input"
              maxLength={500}
              placeholder="Non-sensitive purpose note (e.g. Staging smoke verification token)"
              value={createDescription}
              onChange={(e) => setCreateDescription(e.target.value)}
            />
          </div>

          <div className="cf-inline-actions cf-inline-actions--end">
            <button type="button" className="btn" onClick={closeCreateModal} disabled={isSubmittingCreate}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSubmittingCreate}>
              <ShieldCheck size={14} aria-hidden="true" />
              <span>{isSubmittingCreate ? 'Encrypting...' : 'Encrypt & Store Secret'}</span>
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Write-Only Rotate Secret Modal */}
      <Modal
        isOpen={rotatingSecret !== null}
        title={rotatingSecret ? `Rotate Secret — ${rotatingSecret.name}` : 'Rotate Secret'}
        subtitle={
          rotatingSecret
            ? `Creates encrypted v${rotatingSecret.currentVersionNumber + 1}. Already-queued runs stay pinned to their queue-time version unless revoked.`
            : undefined
        }
        onClose={closeRotateModal}
      >
        {rotatingSecret && (
          <ValidatedForm className="cf-form" onSubmit={(e) => void handleRotateSubmit(e)}>
            {rotateError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertCircle size={17} />
                </div>
                <p className="cf-notice__text">{rotateError}</p>
              </div>
            )}

            <div className="cf-field">
              <label htmlFor="cf-rotate-secret-value" className="cf-field__label">
                New Secret Value (Write-Only) *
              </label>
              <div className="cf-secret-input-wrap">
                <input
                  id="cf-rotate-secret-value"
                  type={showRotateValue ? 'text' : 'password'}
                  className="cf-input mono"
                  required
                  placeholder={`Enter new value for ${rotatingSecret.name}`}
                  autoComplete="new-password"
                  spellCheck={false}
                  value={rotateValue}
                  onChange={(e) => setRotateValue(e.target.value)}
                />
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => setShowRotateValue((v) => !v)}
                  aria-label={showRotateValue ? 'Hide new secret value' : 'Show new secret value while typing'}
                >
                  {showRotateValue ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </div>

            <div className="cf-field">
              <label htmlFor="cf-rotate-secret-desc" className="cf-field__label">
                Updated Description (Optional)
              </label>
              <input
                id="cf-rotate-secret-desc"
                type="text"
                className="cf-input"
                maxLength={500}
                value={rotateDescription}
                onChange={(e) => setRotateDescription(e.target.value)}
              />
            </div>

            <div className="cf-inline-actions cf-inline-actions--end">
              <button type="button" className="btn" onClick={closeRotateModal} disabled={isSubmittingRotate}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isSubmittingRotate}>
                <RefreshCw size={14} aria-hidden="true" />
                <span>
                  {isSubmittingRotate
                    ? 'Encrypting...'
                    : `Rotate to v${rotatingSecret.currentVersionNumber + 1}`}
                </span>
              </button>
            </div>
          </ValidatedForm>
        )}
      </Modal>

      {/* Version History Modal (Metadata Only) */}
      <Modal
        isOpen={historySecret !== null}
        title={historySecret ? `Secret Versions — ${historySecret.name}` : 'Secret Versions'}
        subtitle="Cryptographic metadata history only — ciphertext and plaintext values are never exposed."
        onClose={() => setHistorySecret(null)}
      >
        {historySecret && (
          <div className="cf-form">
            {isLoadingVersions ? (
              <LoadingState
                title="Loading version history..."
                description="Fetching immutable secret version metadata..."
              />
            ) : versionsError ? (
              <ErrorState
                title="Unable to load secret versions"
                message={versionsError}
                onRetry={() => void openVersionHistory(historySecret)}
              />
            ) : (
              <div className="cf-secret-versions-list">
                {versions.map((ver) => (
                  <div key={ver.id} className="cf-secret-version-row">
                    <div className="cf-secret-version-row__main">
                      <div className="cf-secret-version-row__title">
                        <span className="mono">v{ver.versionNumber}</span>
                        {ver.current && <StatusBadge label="Current" tone="info" size="sm" />}
                        <StatusBadge
                          label={ver.status}
                          tone={ver.status === 'ACTIVE' ? 'healthy' : 'error'}
                          size="sm"
                        />
                        <span className="cf-stage-pill">{ver.kekId}</span>
                      </div>
                      <div className="cf-secret-version-row__sub">
                        <span className="mono">{ver.encryptionAlgorithm}</span> · Created{' '}
                        {formatDateTime(ver.createdAt)}
                        {ver.createdByUsername ? ` by @${ver.createdByUsername}` : ''}
                        {ver.revokedAt ? ` · Revoked ${formatDateTime(ver.revokedAt)}` : ''}
                      </div>
                    </div>
                    {ver.status === 'ACTIVE' && (
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => {
                          setRevokingSecret({
                            secret: historySecret,
                            versionNumber: ver.versionNumber,
                          });
                          setRevokeReason('');
                          setRevokeError(null);
                        }}
                      >
                        <Ban size={12} aria-hidden="true" />
                        <span>Revoke v{ver.versionNumber}</span>
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Revoke Confirmation Modal */}
      <Modal
        isOpen={revokingSecret !== null}
        title={
          revokingSecret
            ? revokingSecret.versionNumber !== undefined
              ? `Revoke ${revokingSecret.secret.name} v${revokingSecret.versionNumber}`
              : `Revoke Secret ${revokingSecret.secret.name}`
            : 'Revoke Secret'
        }
        subtitle="Immediate effect: any active worker secret delivery for revoked versions is denied."
        onClose={() => setRevokingSecret(null)}
      >
        {revokingSecret && (
          <div className="cf-form">
            {revokeError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <p className="cf-notice__text">{revokeError}</p>
              </div>
            )}
            <div className="cf-field">
              <label htmlFor="cf-revoke-reason" className="cf-field__label">
                Audit Reason (Optional)
              </label>
              <input
                id="cf-revoke-reason"
                type="text"
                className="cf-input"
                maxLength={300}
                placeholder="e.g. Suspected exposure or planned decommission"
                value={revokeReason}
                onChange={(e) => setRevokeReason(e.target.value)}
              />
            </div>
            <div className="cf-inline-actions cf-inline-actions--end">
              <button
                type="button"
                className="btn"
                onClick={() => setRevokingSecret(null)}
                disabled={isSubmittingRevoke}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => void handleConfirmRevoke()}
                disabled={isSubmittingRevoke}
              >
                <Ban size={13} aria-hidden="true" />
                <span>{isSubmittingRevoke ? 'Revoking...' : 'Confirm Revoke'}</span>
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Delete Confirmation Modal */}
      <Modal
        isOpen={deletingSecret !== null}
        title={deletingSecret ? `Delete Secret ${deletingSecret.name}` : 'Delete Secret'}
        subtitle="Soft-deletes the secret and revokes active versions while preserving immutable audit references."
        onClose={() => setDeletingSecret(null)}
      >
        {deletingSecret && (
          <div className="cf-form">
            {deleteError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <p className="cf-notice__text">{deleteError}</p>
              </div>
            )}
            <div className="cf-inline-actions cf-inline-actions--end">
              <button
                type="button"
                className="btn"
                onClick={() => setDeletingSecret(null)}
                disabled={isSubmittingDelete}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => void handleConfirmDelete()}
                disabled={isSubmittingDelete}
              >
                <Trash2 size={13} aria-hidden="true" />
                <span>{isSubmittingDelete ? 'Deleting...' : 'Delete Secret'}</span>
              </button>
            </div>
          </div>
        )}
      </Modal>
    </DashboardCard>
  );
}
