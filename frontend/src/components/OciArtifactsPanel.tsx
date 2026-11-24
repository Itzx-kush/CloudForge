import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  AlertTriangle,
  Box,
  Check,
  Copy,
  Cpu,
  Database,
  GitCommitHorizontal,
  History,
  Info,
  KeyRound,
  Layers,
  PackageCheck,
  Plus,
  RefreshCw,
  Server,
  ShieldCheck,
  Slash,
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
import { useAuth } from '../context/AuthContext';
import {
  ApiClientError,
  createOciRegistry,
  disableOciRegistry,
  fetchEnvironmentSecrets,
  fetchOciArtifactDetail,
  fetchOciArtifacts,
  fetchOciBuilds,
  fetchOciRegistries,
  fetchOciSubsystemStatus,
  fetchProjectDetail,
  fetchProjects,
} from '../services/api';
import type {
  EnvironmentSecretSummary,
  OciArtifactVerificationStatus,
  OciBuildRecord,
  OciBuildStatus,
  OciImageArtifact,
  OciImageArtifactDetail,
  OciPublishAttemptStatus,
  OciRegistryConnection,
  OciRegistryType,
  OciSubsystemStatus,
  ProjectEnvironment,
  ProjectSummary,
} from '../types/platform';
import './OciArtifactsPanel.css';

const BUILD_STATUS_META: Record<OciBuildStatus, { label: string; tone: BadgeTone; pulse?: boolean }> = {
  PENDING: { label: 'Pending', tone: 'neutral' },
  RUNNING: { label: 'Building', tone: 'info', pulse: true },
  SUCCEEDED: { label: 'Succeeded', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  TIMED_OUT: { label: 'Timed Out', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'degraded' },
};

const VERIFICATION_STATUS_META: Record<
  OciArtifactVerificationStatus,
  { label: string; tone: BadgeTone }
> = {
  PENDING: { label: 'Pending Verification', tone: 'degraded' },
  VERIFIED: { label: 'Digest Verified', tone: 'healthy' },
  VERIFICATION_FAILED: { label: 'Verification Failed', tone: 'error' },
};

const PUBLISH_STATUS_META: Record<OciPublishAttemptStatus, { label: string; tone: BadgeTone }> = {
  STARTED: { label: 'Started', tone: 'info' },
  PUSHED: { label: 'Pushed (Unverified)', tone: 'degraded' },
  VERIFIED: { label: 'Published & Verified', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  RECONCILIATION_REQUIRED: { label: 'Reconciliation Required', tone: 'degraded' },
};

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

export function OciArtifactsPanel() {
  const { activeWorkspace, activeRole } = useAuth();
  const isOwner = activeRole === 'OWNER';

  const [status, setStatus] = useState<OciSubsystemStatus | null>(null);
  const [registries, setRegistries] = useState<OciRegistryConnection[]>([]);
  const [artifacts, setArtifacts] = useState<OciImageArtifact[]>([]);
  const [totalArtifacts, setTotalArtifacts] = useState(0);
  const [builds, setBuilds] = useState<OciBuildRecord[]>([]);
  const [totalBuilds, setTotalBuilds] = useState(0);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);

  // Filters
  const [artifactSearch, setArtifactSearch] = useState('');
  const [artifactStatusFilter, setArtifactStatusFilter] = useState<
    OciArtifactVerificationStatus | ''
  >('');
  const [buildSearch, setBuildSearch] = useState('');
  const [buildStatusFilter, setBuildStatusFilter] = useState<OciBuildStatus | ''>('');

  // Loading & feedback
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [toast, setToast] = useState<{
    message: string;
    tone: 'success' | 'info' | 'error';
  } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // Add Registry modal state (OWNER only, secret references only)
  const [isRegModalOpen, setIsRegModalOpen] = useState(false);
  const [regName, setRegName] = useState('');
  const [regHost, setRegHost] = useState('');
  const [regScheme, setRegScheme] = useState<'https' | 'http'>('https');
  const [regType, setRegType] = useState<OciRegistryType>('OCI_GENERIC');
  const [regNamespace, setRegNamespace] = useState('');
  const [regAllowInsecureLocal, setRegAllowInsecureLocal] = useState(false);
  const [regDescription, setRegDescription] = useState('');
  const [regCredProjectId, setRegCredProjectId] = useState('');
  const [regCredEnvId, setRegCredEnvId] = useState('');
  const [regUserSecretId, setRegUserSecretId] = useState('');
  const [regPassSecretId, setRegPassSecretId] = useState('');
  const [credEnvs, setCredEnvs] = useState<ProjectEnvironment[]>([]);
  const [credSecrets, setCredSecrets] = useState<EnvironmentSecretSummary[]>([]);
  const [isSavingReg, setIsSavingReg] = useState(false);
  const [regFormError, setRegFormError] = useState<string | null>(null);

  // Artifact Detail modal
  const [selectedArtifactId, setSelectedArtifactId] = useState<string | null>(null);
  const [artifactDetail, setArtifactDetail] = useState<OciImageArtifactDetail | null>(null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const loadOciData = useCallback(
    async (silent = false) => {
      if (!activeWorkspace) {
        setStatus(null);
        setRegistries([]);
        setArtifacts([]);
        setBuilds([]);
        setIsLoading(false);
        return;
      }
      if (silent) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setErrorMessage(null);

      try {
        const [statusRes, regRes, artRes, buildRes, projRes] = await Promise.all([
          fetchOciSubsystemStatus(activeWorkspace.id),
          fetchOciRegistries(activeWorkspace.id),
          fetchOciArtifacts(activeWorkspace.id, {
            verificationStatus: artifactStatusFilter || undefined,
            q: artifactSearch || undefined,
            page: 0,
            size: 20,
          }),
          fetchOciBuilds(activeWorkspace.id, {
            status: buildStatusFilter || undefined,
            q: buildSearch || undefined,
            page: 0,
            size: 20,
          }),
          fetchProjects(activeWorkspace.id, { page: 0, size: 100 }),
        ]);

        setStatus(statusRes);
        setRegistries(Array.isArray(regRes) ? regRes : []);
        setArtifacts(Array.isArray(artRes.items) ? artRes.items : []);
        setTotalArtifacts(artRes.totalElements ?? 0);
        setBuilds(Array.isArray(buildRes.items) ? buildRes.items : []);
        setTotalBuilds(buildRes.totalElements ?? 0);
        setProjects(Array.isArray(projRes.items) ? projRes.items : []);
      } catch (error) {
        setErrorMessage(
          error instanceof Error ? error.message : 'Unable to load OCI builds and artifacts.'
        );
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [activeWorkspace, artifactSearch, artifactStatusFilter, buildSearch, buildStatusFilter]
  );

  useEffect(() => {
    void loadOciData(false);
  }, [loadOciData]);

  // Load environments when selecting a credential project in Add Registry modal
  useEffect(() => {
    if (!isRegModalOpen || !isOwner || !activeWorkspace || !regCredProjectId) {
      setCredEnvs([]);
      return;
    }
    let cancelled = false;
    void fetchProjectDetail(activeWorkspace.id, regCredProjectId)
      .then((detail) => {
        if (!cancelled) {
          setCredEnvs(Array.isArray(detail.environments) ? detail.environments : []);
        }
      })
      .catch(() => {
        if (!cancelled) setCredEnvs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isRegModalOpen, isOwner, activeWorkspace, regCredProjectId]);

  // Load active Phase 3.4 encrypted secrets when selecting a credential environment
  useEffect(() => {
    if (!isRegModalOpen || !isOwner || !activeWorkspace || !regCredProjectId || !regCredEnvId) {
      setCredSecrets([]);
      return;
    }
    let cancelled = false;
    void fetchEnvironmentSecrets(activeWorkspace.id, regCredProjectId, regCredEnvId, {
      status: 'ACTIVE',
      size: 100,
    })
      .then((res) => {
        if (!cancelled) setCredSecrets(Array.isArray(res.items) ? res.items : []);
      })
      .catch(() => {
        if (!cancelled) setCredSecrets([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isRegModalOpen, isOwner, activeWorkspace, regCredProjectId, regCredEnvId]);

  const handleCopy = (key: string, value: string) => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(value);
      setCopiedKey(key);
      window.setTimeout(() => setCopiedKey((curr) => (curr === key ? null : curr)), 1800);
    }
  };

  const openCreateRegistryModal = () => {
    setRegName('');
    setRegHost('');
    setRegScheme('https');
    setRegType('OCI_GENERIC');
    setRegNamespace('');
    setRegAllowInsecureLocal(false);
    setRegDescription('');
    setRegCredProjectId('');
    setRegCredEnvId('');
    setRegUserSecretId('');
    setRegPassSecretId('');
    setRegFormError(null);
    setIsRegModalOpen(true);
  };

  const handleCreateRegistry = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || isSavingReg) return;
    setIsSavingReg(true);
    setRegFormError(null);
    try {
      const created = await createOciRegistry(activeWorkspace.id, {
        name: regName.trim(),
        registryHost: regHost.trim(),
        scheme: regScheme,
        registryType: regType,
        allowedNamespace: regNamespace.trim(),
        credentialProjectId: regCredProjectId || null,
        credentialEnvironmentId: regCredEnvId || null,
        usernameSecretId: regUserSecretId || null,
        passwordSecretId: regPassSecretId || null,
        allowInsecureLocal: regAllowInsecureLocal,
        description: regDescription.trim() || undefined,
      });
      setIsRegModalOpen(false);
      setToast({
        message: `Configured OCI registry "${created.name}" (${created.registryHost}/${created.allowedNamespace}).`,
        tone: 'success',
      });
      await loadOciData(true);
    } catch (error) {
      if (error instanceof ApiClientError) {
        setRegFormError(error.message);
      } else {
        setRegFormError(
          error instanceof Error ? error.message : 'Unable to save OCI registry connection.'
        );
      }
    } finally {
      setIsSavingReg(false);
    }
  };

  const handleDisableRegistry = async (reg: OciRegistryConnection) => {
    if (!activeWorkspace) return;
    try {
      await disableOciRegistry(activeWorkspace.id, reg.id);
      setToast({
        message: `Disabled OCI registry "${reg.name}".`,
        tone: 'info',
      });
      await loadOciData(true);
    } catch (error) {
      setToast({
        message: error instanceof Error ? error.message : 'Failed to disable registry connection.',
        tone: 'error',
      });
    }
  };

  const openArtifactDetail = async (artifact: OciImageArtifact) => {
    if (!activeWorkspace) return;
    setSelectedArtifactId(artifact.id);
    setArtifactDetail(null);
    setDetailError(null);
    setIsLoadingDetail(true);
    try {
      const detail = await fetchOciArtifactDetail(activeWorkspace.id, artifact.id);
      setArtifactDetail(detail);
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : 'Unable to load OCI artifact details.'
      );
    } finally {
      setIsLoadingDetail(false);
    }
  };

  const registryColumns: TableColumn<OciRegistryConnection>[] = [
    {
      key: 'name',
      header: 'Registry Connection',
      render: (row) => (
        <div>
          <strong>{row.name}</strong>{' '}
          <span className="cf-attempt-pill">{row.registryType}</span>
          {row.description && <span className="cf-pipelines-subtext">{row.description}</span>}
        </div>
      ),
    },
    {
      key: 'destination',
      header: 'Endpoint & Namespace',
      render: (row) => (
        <div className="mono" style={{ fontSize: '12px' }}>
          <div>
            {row.scheme}://{row.registryHost}
          </div>
          <span className="cf-pipelines-subtext">Namespace: {row.allowedNamespace}/*</span>
        </div>
      ),
    },
    {
      key: 'credentials',
      header: 'Credentials (Encrypted Ref)',
      render: (row) =>
        row.credentialsConfigured ? (
          <span className="cf-secret-ref-pill">
            <KeyRound size={11} aria-hidden="true" />
            <span>
              {row.passwordSecretName ?? 'Encrypted Secret'}
              {row.credentialEnvironmentName ? ` (${row.credentialEnvironmentName})` : ''}
            </span>
          </span>
        ) : (
          <StatusBadge
            label={row.allowInsecureLocal ? 'Anonymous Local Dev' : 'Unconfigured'}
            tone={row.allowInsecureLocal ? 'degraded' : 'neutral'}
            size="sm"
          />
        ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusBadge
          label={row.status === 'ACTIVE' ? 'Active' : 'Disabled'}
          tone={row.status === 'ACTIVE' ? 'healthy' : 'neutral'}
          size="sm"
        />
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) =>
        isOwner && row.status === 'ACTIVE' ? (
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => void handleDisableRegistry(row)}
          >
            <Slash size={12} aria-hidden="true" />
            <span>Disable</span>
          </button>
        ) : (
          <span className="cf-muted">—</span>
        ),
    },
  ];

  const artifactColumns: TableColumn<OciImageArtifact>[] = [
    {
      key: 'image',
      header: 'OCI Image Artifact',
      render: (row) => (
        <div>
          <button
            type="button"
            className="cf-pipelines-link-btn"
            onClick={() => void openArtifactDetail(row)}
          >
            <span className="mono">
              {row.registryHost}/{row.imageRepository}
            </span>
          </button>
          <span className="cf-pipelines-subtext">
            {row.projectName}
            {row.runNumber !== null ? ` · Run #${row.runNumber}` : ''}
            {row.imageTag ? ` · tag: ${row.imageTag}` : ''}
          </span>
        </div>
      ),
    },
    {
      key: 'digest',
      header: 'Immutable Digest',
      render: (row) => (
        <div className="cf-oci-digest-box" title={row.immutableReference}>
          <span className="cf-oci-digest-text">{row.manifestDigest}</span>
          <button
            type="button"
            className="cf-oci-copy-btn"
            aria-label={`Copy immutable reference for ${row.imageRepository}`}
            onClick={() => handleCopy(row.id, row.immutableReference)}
          >
            {copiedKey === row.id ? <Check size={11} /> : <Copy size={11} />}
            <span>{copiedKey === row.id ? 'Copied' : 'Copy'}</span>
          </button>
        </div>
      ),
    },
    {
      key: 'source',
      header: 'Source Commit & Platform',
      render: (row) => (
        <div className="cf-run-sha-branch">
          <span className="cf-run-sha" title={row.commitSha}>
            <GitCommitHorizontal size={12} aria-hidden="true" />
            <span>{row.commitSha.slice(0, 10)}</span>
          </span>
          <span className="cf-stage-pill">{row.platform}</span>
        </div>
      ),
    },
    {
      key: 'verification',
      header: 'Verification',
      render: (row) => {
        const meta = VERIFICATION_STATUS_META[row.verificationStatus];
        return <StatusBadge label={meta.label} tone={meta.tone} size="sm" />;
      },
    },
    {
      key: 'publishedAt',
      header: 'Published',
      render: (row) => (
        <div>
          <div className="mono" style={{ fontSize: '12px' }}>
            {formatTimestamp(row.publishedAt)}
          </div>
          <span className="cf-pipelines-subtext mono">{formatBytes(row.imageSizeBytes)}</span>
        </div>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => void openArtifactDetail(row)}
        >
          <Layers size={12} aria-hidden="true" />
          <span>Details</span>
        </button>
      ),
    },
  ];

  const buildColumns: TableColumn<OciBuildRecord>[] = [
    {
      key: 'target',
      header: 'Build Target',
      render: (row) => (
        <div>
          <strong className="mono">{row.targetImageName}</strong>
          {row.targetTag && <span className="cf-attempt-pill">:{row.targetTag}</span>}
          <span className="cf-pipelines-subtext">
            {row.projectName}
            {row.runNumber !== null ? ` · Run #${row.runNumber}` : ''} · {row.dockerfilePath} (ctx:{' '}
            {row.buildContextPath})
          </span>
        </div>
      ),
    },
    {
      key: 'commit',
      header: 'Commit & Platform',
      render: (row) => (
        <div className="cf-run-sha-branch">
          <span className="cf-run-sha" title={row.commitSha}>
            {row.commitSha.slice(0, 10)}
          </span>
          <span className="cf-stage-pill">{row.targetPlatform}</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Build Status',
      render: (row) => {
        const meta = BUILD_STATUS_META[row.status];
        return (
          <div>
            <StatusBadge
              label={meta.label}
              tone={meta.tone}
              pulse={Boolean(meta.pulse)}
              size="sm"
            />
            {row.errorSummary && (
              <span className="cf-pipelines-subtext">{row.errorSummary}</span>
            )}
          </div>
        );
      },
    },
    {
      key: 'digest',
      header: 'Built Manifest Digest',
      render: (row) =>
        row.builtManifestDigest ? (
          <span className="mono" style={{ fontSize: '11.5px' }} title={row.builtManifestDigest}>
            {row.builtManifestDigest.slice(0, 24)}...
          </span>
        ) : (
          <span className="cf-muted">—</span>
        ),
    },
    {
      key: 'worker',
      header: 'Worker & Time',
      render: (row) => (
        <div>
          <div className="mono" style={{ fontSize: '12px' }}>
            {row.workerId}
          </div>
          <span className="cf-pipelines-subtext mono">
            {formatTimestamp(row.completedAt ?? row.startedAt ?? row.createdAt)}
          </span>
        </div>
      ),
    },
  ];

  if (isLoading) {
    return (
      <LoadingState
        title="Loading OCI builds & artifacts"
        description="Retrieving registry connections, BuildKit history, and verified image digests..."
      />
    );
  }

  return (
    <div className="cf-oci-panel">
      {toast && (
        <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />
      )}

      {errorMessage && (
        <ErrorState
          title="Unable to load OCI builds & artifacts"
          message={errorMessage}
          onRetry={() => void loadOciData(false)}
        />
      )}

      {/* Honest OCI Build & Registry Status Strip */}
      <DashboardCard
        title="OCI BuildKit & Registry Plane"
        subtitle="Control-plane status for secure container builds, encrypted credential references, and digest verification"
        icon={<Cpu size={17} />}
        headerAction={
          <div className="cf-inline-actions">
            <StatusBadge
              label={status?.ociEnabled ? 'OCI Builds Enabled' : 'OCI Builds Disabled (Safe Default)'}
              tone={status?.ociEnabled ? 'healthy' : 'neutral'}
              pulse={Boolean(status?.ociEnabled && (status?.activeBuildWorkers ?? 0) > 0)}
              size="sm"
            />
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => void loadOciData(true)}
              disabled={isRefreshing}
            >
              <RefreshCw size={12} className={isRefreshing ? 'cf-spin' : ''} aria-hidden="true" />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
            </button>
          </div>
        }
      >
        <div className="cf-worker-pool-grid">
          <div className="cf-worker-metric">
            <span className="cf-worker-metric__label">Verified Artifacts</span>
            <span className="cf-worker-metric__value" data-testid="oci-verified-artifact-count">
              {status?.verifiedArtifacts ?? 0}
            </span>
          </div>
          <div className="cf-worker-metric">
            <span className="cf-worker-metric__label">OCI Builds</span>
            <span className="cf-worker-metric__value" data-testid="oci-build-count">
              {status?.totalBuilds ?? 0}
            </span>
          </div>
          <div className="cf-worker-metric">
            <span className="cf-worker-metric__label">Active Registries</span>
            <span className="cf-worker-metric__value" data-testid="oci-registry-count">
              {status?.activeRegistryConnections ?? 0}
            </span>
          </div>
          <div className="cf-worker-metric">
            <span className="cf-worker-metric__label">BuildKit Workers</span>
            <span className="cf-worker-metric__value" data-testid="oci-worker-count">
              {status?.activeBuildWorkers ?? 0}
            </span>
          </div>
        </div>
        {status && !status.ociEnabled && (
          <div className="cf-notice cf-notice--info" style={{ marginTop: '12px' }} role="status">
            <div className="cf-notice__icon" aria-hidden="true">
              <Info size={17} />
            </div>
            <div>
              <p className="cf-notice__title">Build Execution Disabled by Default (Render-Safe)</p>
              <p className="cf-notice__text">
                The CloudForge control plane never invokes Docker or BuildKit directly. Enable{' '}
                <span className="mono">CLOUDFORGE_OCI_ENABLED=true</span> and connect a dedicated{' '}
                <span className="mono">OCI_BUILDKIT</span> worker host to run builds and publish
                verified digests.
              </p>
            </div>
          </div>
        )}
      </DashboardCard>

      {/* Verified Container Image Artifacts */}
      <DashboardCard
        title="Verified Container Image Artifacts"
        subtitle="Immutable, SHA-256 digest-verified OCI images linked to pinned source commits and pipeline runs"
        icon={<PackageCheck size={17} />}
        headerAction={
          <StatusBadge
            label={`${totalArtifacts} ${totalArtifacts === 1 ? 'Artifact' : 'Artifacts'}`}
            tone={totalArtifacts > 0 ? 'healthy' : 'neutral'}
            size="sm"
          />
        }
      >
        <div className="cf-toolbar">
          <SearchField
            label="Search image artifacts"
            placeholder="Search by repository, registry, digest, tag, or commit..."
            value={artifactSearch}
            onChange={setArtifactSearch}
          />
          <div className="cf-filter-group">
            <select
              className="cf-filter-select"
              aria-label="Filter artifacts by verification status"
              value={artifactStatusFilter}
              onChange={(e) =>
                setArtifactStatusFilter(e.target.value as OciArtifactVerificationStatus | '')
              }
            >
              <option value="">All verification states</option>
              <option value="VERIFIED">Digest Verified</option>
              <option value="VERIFICATION_FAILED">Verification Failed</option>
              <option value="PENDING">Pending</option>
            </select>
          </div>
        </div>

        <DataTable
          columns={artifactColumns}
          rows={artifacts}
          getRowKey={(row) => row.id}
          emptyMessage="No published OCI image artifacts recorded yet. Run a pipeline with SOURCE_PREP → BUILD (OCI_IMAGE_BUILD) → CONTAINER_PACKAGE (OCI_IMAGE_PUBLISH) to produce verified immutable artifacts."
          caption="Verified OCI container image artifacts"
        />
      </DashboardCard>

      {/* OCI Registry Connections */}
      <DashboardCard
        title="OCI Registry Connections"
        subtitle="Workspace-scoped, SSRF-protected registry destinations referencing Phase 3.4 encrypted secrets only"
        icon={<Server size={17} />}
        headerAction={
          isOwner ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={openCreateRegistryModal}>
              <Plus size={13} aria-hidden="true" />
              <span>Add Registry Connection</span>
            </button>
          ) : (
            <StatusBadge label="Owner Managed" tone="neutral" size="sm" />
          )
        }
      >
        <DataTable
          columns={registryColumns}
          rows={registries}
          getRowKey={(row) => row.id}
          emptyMessage="No OCI registry connections configured in this workspace yet."
          caption="Workspace OCI registry connections"
        />
      </DashboardCard>

      {/* OCI Build History */}
      <DashboardCard
        title="OCI Build History"
        subtitle="Audit trail of bounded BuildKit execution attempts, Dockerfile/context snapshots, and outcomes"
        icon={<Box size={17} />}
        headerAction={
          <StatusBadge
            label={`${totalBuilds} ${totalBuilds === 1 ? 'Build' : 'Builds'}`}
            tone={totalBuilds > 0 ? 'info' : 'neutral'}
            size="sm"
          />
        }
      >
        <div className="cf-toolbar">
          <SearchField
            label="Search build history"
            placeholder="Search by image name, repository, commit, or digest..."
            value={buildSearch}
            onChange={setBuildSearch}
          />
          <div className="cf-filter-group">
            <select
              className="cf-filter-select"
              aria-label="Filter builds by status"
              value={buildStatusFilter}
              onChange={(e) => setBuildStatusFilter(e.target.value as OciBuildStatus | '')}
            >
              <option value="">All build statuses</option>
              <option value="SUCCEEDED">Succeeded</option>
              <option value="RUNNING">Building</option>
              <option value="FAILED">Failed</option>
              <option value="TIMED_OUT">Timed Out</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
          </div>
        </div>

        <DataTable
          columns={buildColumns}
          rows={builds}
          getRowKey={(row) => row.id}
          emptyMessage="No BuildKit image build attempts recorded yet."
          caption="OCI BuildKit build history"
        />
      </DashboardCard>

      {/* OWNER-only Add Registry Connection Modal */}
      <Modal
        isOpen={isRegModalOpen}
        title="Configure OCI Registry Connection"
        subtitle="Credentials are referenced strictly from Phase 3.4 encrypted environment secrets — never stored in plaintext."
        onClose={() => setIsRegModalOpen(false)}
      >
        <ValidatedForm className="cf-form" onSubmit={(e) => void handleCreateRegistry(e)}>
          {regFormError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertTriangle size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Registry configuration rejected</p>
                <p className="cf-notice__text">{regFormError}</p>
              </div>
            </div>
          )}

          <div className="cf-stage-editor-grid">
            <div className="cf-field">
              <label htmlFor="cf-reg-name" className="cf-field__label">
                Connection Name *
              </label>
              <input
                id="cf-reg-name"
                type="text"
                className="cf-input"
                required
                minLength={2}
                placeholder="e.g. GHCR Production"
                value={regName}
                onChange={(e) => setRegName(e.target.value)}
              />
            </div>

            <div className="cf-field">
              <label htmlFor="cf-reg-type" className="cf-field__label">
                Registry Type
              </label>
              <select
                id="cf-reg-type"
                className="cf-select"
                value={regType}
                onChange={(e) => {
                  const next = e.target.value as OciRegistryType;
                  setRegType(next);
                  if (next !== 'LOCAL_DEV') {
                    setRegScheme('https');
                    setRegAllowInsecureLocal(false);
                  }
                }}
              >
                <option value="OCI_GENERIC">OCI Generic Registry (HTTPS)</option>
                <option value="GHCR">GitHub Container Registry (ghcr.io)</option>
                <option value="LOCAL_DEV">Local Dev Registry (Loopback Only)</option>
              </select>
            </div>
          </div>

          <div className="cf-stage-editor-grid">
            <div className="cf-field">
              <label htmlFor="cf-reg-host" className="cf-field__label">
                Registry Host (host or host:port) *
              </label>
              <input
                id="cf-reg-host"
                type="text"
                className="cf-input mono"
                required
                placeholder="ghcr.io or 127.0.0.1:5000"
                value={regHost}
                onChange={(e) => setRegHost(e.target.value)}
              />
            </div>

            <div className="cf-field">
              <label htmlFor="cf-reg-ns" className="cf-field__label">
                Approved Repository Namespace *
              </label>
              <input
                id="cf-reg-ns"
                type="text"
                className="cf-input mono"
                required
                placeholder="e.g. my-org or cloudforge-dev"
                value={regNamespace}
                onChange={(e) => setRegNamespace(e.target.value)}
              />
            </div>
          </div>

          {regType === 'LOCAL_DEV' && (
            <div className="cf-stage-editor-grid">
              <div className="cf-field">
                <label htmlFor="cf-reg-scheme" className="cf-field__label">
                  Scheme (Local Dev Only)
                </label>
                <select
                  id="cf-reg-scheme"
                  className="cf-select mono"
                  value={regScheme}
                  onChange={(e) => setRegScheme(e.target.value as 'https' | 'http')}
                >
                  <option value="https">https</option>
                  <option value="http">http (loopback dev only)</option>
                </select>
              </div>
              <div className="cf-field" style={{ justifyContent: 'flex-end' }}>
                <label className="cf-checkbox-inline">
                  <input
                    type="checkbox"
                    checked={regAllowInsecureLocal}
                    onChange={(e) => setRegAllowInsecureLocal(e.target.checked)}
                  />
                  <span>Allow loopback local dev registry</span>
                </label>
              </div>
            </div>
          )}

          <div className="cf-field">
            <span className="cf-field__label">
              Encrypted Registry Credential References (Optional for Anonymous Local Dev)
            </span>
            <div className="cf-oci-config-grid">
              <div className="cf-field">
                <label htmlFor="cf-reg-cred-proj" className="cf-field__label">
                  Secret Project
                </label>
                <select
                  id="cf-reg-cred-proj"
                  className="cf-select"
                  value={regCredProjectId}
                  onChange={(e) => {
                    setRegCredProjectId(e.target.value);
                    setRegCredEnvId('');
                    setRegUserSecretId('');
                    setRegPassSecretId('');
                  }}
                >
                  <option value="">None (anonymous local dev)</option>
                  {projects.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </div>

              <div className="cf-field">
                <label htmlFor="cf-reg-cred-env" className="cf-field__label">
                  Secret Environment
                </label>
                <select
                  id="cf-reg-cred-env"
                  className="cf-select"
                  disabled={!regCredProjectId}
                  value={regCredEnvId}
                  onChange={(e) => {
                    setRegCredEnvId(e.target.value);
                    setRegUserSecretId('');
                    setRegPassSecretId('');
                  }}
                >
                  <option value="">Select environment...</option>
                  {credEnvs.map((env) => (
                    <option key={env.id} value={env.id}>
                      {env.name} ({env.environmentType})
                    </option>
                  ))}
                </select>
              </div>

              <div className="cf-field">
                <label htmlFor="cf-reg-user-sec" className="cf-field__label">
                  Username Secret (Optional)
                </label>
                <select
                  id="cf-reg-user-sec"
                  className="cf-select mono"
                  disabled={!regCredEnvId}
                  value={regUserSecretId}
                  onChange={(e) => setRegUserSecretId(e.target.value)}
                >
                  <option value="">Default (oauth2)</option>
                  {credSecrets.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name} (v{s.currentVersionNumber})
                    </option>
                  ))}
                </select>
              </div>

              <div className="cf-field">
                <label htmlFor="cf-reg-pass-sec" className="cf-field__label">
                  Password / Token Secret
                </label>
                <select
                  id="cf-reg-pass-sec"
                  className="cf-select mono"
                  disabled={!regCredEnvId}
                  value={regPassSecretId}
                  onChange={(e) => setRegPassSecretId(e.target.value)}
                >
                  <option value="">Select encrypted secret...</option>
                  {credSecrets.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name} (v{s.currentVersionNumber})
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          <div className="cf-field">
            <label htmlFor="cf-reg-desc" className="cf-field__label">
              Description (Optional)
            </label>
            <input
              id="cf-reg-desc"
              type="text"
              className="cf-input"
              maxLength={450}
              placeholder="Purpose and allowed artifact scope"
              value={regDescription}
              onChange={(e) => setRegDescription(e.target.value)}
            />
          </div>

          <div className="cf-inline-actions cf-inline-actions--end">
            <button
              type="button"
              className="btn"
              onClick={() => setIsRegModalOpen(false)}
              disabled={isSavingReg}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSavingReg}>
              {isSavingReg ? 'Saving...' : 'Save Registry Connection'}
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Immutable Artifact Details Modal */}
      <Modal
        isOpen={selectedArtifactId !== null}
        title={
          artifactDetail
            ? `${artifactDetail.artifact.imageRepository}`
            : 'OCI Image Artifact Details'
        }
        subtitle={
          artifactDetail
            ? `${artifactDetail.artifact.registryHost} · Immutable Digest Verification Record`
            : undefined
        }
        onClose={() => {
          setSelectedArtifactId(null);
          setArtifactDetail(null);
        }}
        footer={
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setSelectedArtifactId(null);
              setArtifactDetail(null);
            }}
          >
            Close
          </button>
        }
      >
        {isLoadingDetail ? (
          <LoadingState
            title="Loading artifact verification record"
            description="Fetching immutable digest metadata, build snapshot, and publish events..."
          />
        ) : detailError ? (
          <ErrorState title="Unable to load artifact details" message={detailError} />
        ) : artifactDetail ? (
          <div className="cf-run-detail-section">
            <div className="cf-run-detail-header-strip">
              <StatusBadge
                label={VERIFICATION_STATUS_META[artifactDetail.artifact.verificationStatus].label}
                tone={VERIFICATION_STATUS_META[artifactDetail.artifact.verificationStatus].tone}
                size="sm"
              />
              <span className="cf-stage-pill">{artifactDetail.artifact.platform}</span>
              <span className="mono cf-muted" style={{ fontSize: '12px' }}>
                {formatBytes(artifactDetail.artifact.imageSizeBytes)}
              </span>
            </div>

            <div className="cf-field">
              <span className="cf-field__label">Canonical Immutable OCI Reference (Phase 3.6 Deployment Target)</span>
              <div className="cf-oci-digest-full">
                <span>{artifactDetail.artifact.immutableReference}</span>
                <button
                  type="button"
                  className="cf-oci-copy-btn"
                  onClick={() =>
                    handleCopy('detail-ref', artifactDetail.artifact.immutableReference)
                  }
                >
                  {copiedKey === 'detail-ref' ? <Check size={12} /> : <Copy size={12} />}
                  <span>{copiedKey === 'detail-ref' ? 'Copied' : 'Copy Ref'}</span>
                </button>
              </div>
            </div>

            <dl className="cf-kv-list">
              <div className="cf-kv-item">
                <dt>Registry</dt>
                <dd className="mono">{artifactDetail.artifact.registryHost}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Repository</dt>
                <dd className="mono">{artifactDetail.artifact.imageRepository}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Convenience Tag</dt>
                <dd className="mono">{artifactDetail.artifact.imageTag ?? 'None (digest-only)'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Immutable Digest</dt>
                <dd className="mono" style={{ wordBreak: 'break-all' }}>
                  {artifactDetail.artifact.manifestDigest}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>Source Repository</dt>
                <dd className="mono">{artifactDetail.artifact.repositoryFullName}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Pinned Commit SHA</dt>
                <dd className="mono">{artifactDetail.artifact.commitSha}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Media Type</dt>
                <dd className="mono">{artifactDetail.artifact.imageMediaType}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Published / Verified</dt>
                <dd className="mono">
                  {formatTimestamp(artifactDetail.artifact.publishedAt)} /{' '}
                  {formatTimestamp(artifactDetail.artifact.verifiedAt)}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>Retention Policy</dt>
                <dd className="mono">{artifactDetail.artifact.retentionPolicy}</dd>
              </div>
            </dl>

            {artifactDetail.buildRecord && (
              <div className="cf-jobs-section">
                <h3 className="cf-jobs-section__title">
                  <ShieldCheck size={14} aria-hidden="true" style={{ verticalAlign: '-2px', marginRight: '6px' }} />
                  Originating BuildKit Build Record
                </h3>
                <div className="cf-attempt-row">
                  <div className="cf-attempt-row__top">
                    <span className="mono">
                      Dockerfile: <strong>{artifactDetail.buildRecord.dockerfilePath}</strong> ·
                      Context: <strong>{artifactDetail.buildRecord.buildContextPath}</strong>
                    </span>
                    <StatusBadge
                      label={BUILD_STATUS_META[artifactDetail.buildRecord.status].label}
                      tone={BUILD_STATUS_META[artifactDetail.buildRecord.status].tone}
                      size="sm"
                    />
                  </div>
                  <div className="cf-orch-stage-card__meta">
                    <span>
                      Mode: <strong className="mono">{artifactDetail.buildRecord.buildMode}</strong>
                    </span>
                    <span>
                      Worker: <strong className="mono">{artifactDetail.buildRecord.workerId}</strong>
                    </span>
                    <span>
                      Completed:{' '}
                      <strong className="mono">
                        {formatTimestamp(artifactDetail.buildRecord.completedAt)}
                      </strong>
                    </span>
                  </div>
                </div>
              </div>
            )}

            {artifactDetail.publishAttempts.length > 0 && (
              <div className="cf-jobs-section">
                <h3 className="cf-jobs-section__title">
                  <Database size={14} aria-hidden="true" style={{ verticalAlign: '-2px', marginRight: '6px' }} />
                  Publish &amp; Verification Attempts ({artifactDetail.publishAttempts.length})
                </h3>
                <div className="cf-attempt-list">
                  {artifactDetail.publishAttempts.map((pa) => (
                    <div key={pa.id} className="cf-attempt-row">
                      <div className="cf-attempt-row__top">
                        <span className="mono">
                          Worker <strong>{pa.workerId}</strong>
                        </span>
                        <StatusBadge
                          label={PUBLISH_STATUS_META[pa.status].label}
                          tone={PUBLISH_STATUS_META[pa.status].tone}
                          size="sm"
                        />
                      </div>
                      <div className="cf-orch-stage-card__meta">
                        <span>
                          Started: <strong className="mono">{formatTimestamp(pa.startedAt)}</strong>
                        </span>
                        {pa.completedAt && (
                          <span>
                            Completed:{' '}
                            <strong className="mono">{formatTimestamp(pa.completedAt)}</strong>
                          </span>
                        )}
                      </div>
                      {pa.errorSummary && <div className="cf-muted">{pa.errorSummary}</div>}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {artifactDetail.events.length > 0 && (
              <div className="cf-jobs-section">
                <h3 className="cf-jobs-section__title">
                  <History size={14} aria-hidden="true" style={{ verticalAlign: '-2px', marginRight: '6px' }} />
                  Artifact Lifecycle Events ({artifactDetail.events.length})
                </h3>
                <ol className="cf-event-timeline">
                  {artifactDetail.events.map((ev) => (
                    <li key={ev.id} className="cf-event-item">
                      <div className="cf-event-item__top">
                        <span className="cf-event-item__type">{ev.eventType}</span>
                        <span className="cf-event-item__time">
                          {formatTimestamp(ev.occurredAt)}
                        </span>
                      </div>
                      <div className="cf-event-item__msg">
                        {ev.message} ({ev.actorLabel})
                      </div>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
