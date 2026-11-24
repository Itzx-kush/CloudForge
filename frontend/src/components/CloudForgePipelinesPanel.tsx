import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  Activity,
  AlertTriangle,
  Ban,
  Edit3,
  GitBranch,
  History,
  Info,
  Layers,
  Play,
  Plus,
  RefreshCw,
  Trash2,
  Workflow,
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
  cancelPipelineRun,
  createPipelineDefinition,
  createPipelineRun,
  fetchPipelineDefinitions,
  fetchPipelineRunEvents,
  fetchPipelineRuns,
  fetchProjects,
  fetchRepositories,
  updatePipelineDefinition,
} from '../services/api';
import type {
  LinkedRepository,
  PipelineDefinition,
  PipelineRun,
  PipelineRunEvent,
  PipelineRunStatus,
  PipelineStageStatus,
  PipelineStageType,
  ProjectSummary,
  StageDefinitionInput,
} from '../types/platform';
import './CloudForgePipelinesPanel.css';

const STAGE_TYPE_OPTIONS: Array<{ value: PipelineStageType; label: string }> = [
  { value: 'SOURCE_PREP', label: 'Source Preparation' },
  { value: 'BUILD', label: 'Build' },
  { value: 'TEST', label: 'Test' },
  { value: 'SECURITY_SCAN', label: 'Security Scan' },
  { value: 'CONTAINER_PACKAGE', label: 'Container Packaging' },
  { value: 'DEPLOYMENT', label: 'Deployment' },
];

const RUN_STATUS_META: Record<
  PipelineRunStatus,
  { label: string; tone: BadgeTone; pulse?: boolean }
> = {
  QUEUED: {
    label: 'Queued — Awaiting execution infrastructure',
    tone: 'degraded',
    pulse: true,
  },
  RUNNING: { label: 'Running', tone: 'info', pulse: true },
  SUCCEEDED: { label: 'Succeeded', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
};

const STAGE_STATUS_META: Record<
  PipelineStageStatus,
  { label: string; tone: BadgeTone; pulse?: boolean }
> = {
  PENDING: { label: 'Pending (Waiting on Predecessors)', tone: 'neutral' },
  READY: { label: 'Ready (Awaiting Worker)', tone: 'degraded', pulse: true },
  RUNNING: { label: 'Running', tone: 'info', pulse: true },
  SUCCEEDED: { label: 'Succeeded', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  SKIPPED: { label: 'Skipped', tone: 'neutral' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
};

interface EditableStageRow {
  localId: string;
  stageKey: string;
  displayName: string;
  stageType: PipelineStageType;
  dependsOn: string[];
  timeoutSeconds: number;
  maxRetries: number;
  allowFailure: boolean;
}

function defaultStarterStages(): EditableStageRow[] {
  return [
    {
      localId: 'stage-1',
      stageKey: 'source',
      displayName: 'Source Preparation',
      stageType: 'SOURCE_PREP',
      dependsOn: [],
      timeoutSeconds: 300,
      maxRetries: 0,
      allowFailure: false,
    },
    {
      localId: 'stage-2',
      stageKey: 'build',
      displayName: 'Application Build',
      stageType: 'BUILD',
      dependsOn: ['source'],
      timeoutSeconds: 600,
      maxRetries: 1,
      allowFailure: false,
    },
    {
      localId: 'stage-3',
      stageKey: 'test',
      displayName: 'Automated Test Suite',
      stageType: 'TEST',
      dependsOn: ['build'],
      timeoutSeconds: 600,
      maxRetries: 0,
      allowFailure: false,
    },
  ];
}

function generateIdempotencyKey(): string {
  const randomPart =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().slice(0, 12)
      : Math.random().toString(36).slice(2, 14);
  return `cf-run-${Date.now()}-${randomPart}`;
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

function formatStageTypeLabel(stageType: PipelineStageType): string {
  return (
    STAGE_TYPE_OPTIONS.find((o) => o.value === stageType)?.label ??
    stageType.replace(/_/g, ' ')
  );
}

export function CloudForgePipelinesPanel() {
  const { activeWorkspace, activeRole } = useAuth();
  const isOwner = activeRole === 'OWNER';
  const canOperateRuns = activeRole === 'OWNER' || activeRole === 'DEVELOPER';

  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [repositories, setRepositories] = useState<LinkedRepository[]>([]);
  const [definitions, setDefinitions] = useState<PipelineDefinition[]>([]);
  const [totalDefinitions, setTotalDefinitions] = useState(0);
  const [runs, setRuns] = useState<PipelineRun[]>([]);
  const [totalRuns, setTotalRuns] = useState(0);

  // Filters
  const [projectFilter, setProjectFilter] = useState('');
  const [defSearch, setDefSearch] = useState('');
  const [runStatusFilter, setRunStatusFilter] = useState<PipelineRunStatus | ''>('');
  const [runSearch, setRunSearch] = useState('');

  // Loading & feedback
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [toast, setToast] = useState<{
    message: string;
    tone: 'success' | 'info' | 'error';
  } | null>(null);

  // Create / Edit definition modal state
  const [isDefModalOpen, setIsDefModalOpen] = useState(false);
  const [editingDefinition, setEditingDefinition] = useState<PipelineDefinition | null>(null);
  const [formProjectId, setFormProjectId] = useState('');
  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formRepoId, setFormRepoId] = useState('');
  const [formEnabled, setFormEnabled] = useState(true);
  const [formStages, setFormStages] = useState<EditableStageRow[]>(defaultStarterStages);
  const [isSavingDef, setIsSavingDef] = useState(false);
  const [defFormError, setDefFormError] = useState<string | null>(null);
  const [defFieldErrors, setDefFieldErrors] = useState<Record<string, string>>({});

  // Queue Run modal state
  const [queueingDef, setQueueingDef] = useState<PipelineDefinition | null>(null);
  const [runBranch, setRunBranch] = useState('');
  const [runSha, setRunSha] = useState('');
  const [runIdempotencyKey, setRunIdempotencyKey] = useState('');
  const [isQueueingRun, setIsQueueingRun] = useState(false);
  const [queueRunError, setQueueRunError] = useState<string | null>(null);

  // Run Details + Events modal state
  const [selectedRun, setSelectedRun] = useState<PipelineRun | null>(null);
  const [runEvents, setRunEvents] = useState<PipelineRunEvent[]>([]);
  const [isLoadingEvents, setIsLoadingEvents] = useState(false);
  const [eventsError, setEventsError] = useState<string | null>(null);

  // Cancel Run confirmation modal state
  const [cancellingRun, setCancellingRun] = useState<PipelineRun | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const loadOrchestrationData = useCallback(
    async (silent = false) => {
      if (!activeWorkspace) {
        setProjects([]);
        setRepositories([]);
        setDefinitions([]);
        setRuns([]);
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
        const [projRes, repoRes, defRes, runRes] = await Promise.all([
          fetchProjects(activeWorkspace.id, { page: 0, size: 100 }),
          fetchRepositories(activeWorkspace.id, { page: 0, size: 100 }),
          fetchPipelineDefinitions(activeWorkspace.id, {
            projectId: projectFilter || undefined,
            q: defSearch || undefined,
            page: 0,
            size: 25,
          }),
          fetchPipelineRuns(activeWorkspace.id, {
            projectId: projectFilter || undefined,
            status: runStatusFilter || undefined,
            q: runSearch || undefined,
            page: 0,
            size: 25,
          }),
        ]);

        setProjects(Array.isArray(projRes.items) ? projRes.items : []);
        setRepositories(Array.isArray(repoRes.items) ? repoRes.items : []);
        setDefinitions(Array.isArray(defRes.items) ? defRes.items : []);
        setTotalDefinitions(defRes.totalElements ?? 0);
        setRuns(Array.isArray(runRes.items) ? runRes.items : []);
        setTotalRuns(runRes.totalElements ?? 0);
      } catch (error) {
        setErrorMessage(
          error instanceof Error
            ? error.message
            : 'Unable to load CloudForge pipeline orchestration data.'
        );
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [activeWorkspace, defSearch, projectFilter, runSearch, runStatusFilter]
  );

  useEffect(() => {
    void loadOrchestrationData(false);
  }, [loadOrchestrationData]);

  const openCreateDefinitionModal = () => {
    setEditingDefinition(null);
    const defaultProj = projects[0];
    setFormProjectId(defaultProj?.id ?? '');
    setFormName('');
    setFormDescription('');
    setFormRepoId(defaultProj?.linkedRepository?.id ?? '');
    setFormEnabled(true);
    setFormStages(defaultStarterStages());
    setDefFormError(null);
    setDefFieldErrors({});
    setIsDefModalOpen(true);
  };

  const openEditDefinitionModal = (def: PipelineDefinition) => {
    setEditingDefinition(def);
    setFormProjectId(def.projectId);
    setFormName(def.name);
    setFormDescription(def.description ?? '');
    setFormRepoId(def.linkedRepositoryId ?? '');
    setFormEnabled(def.enabled);
    setFormStages(
      def.stages.map((s, idx) => ({
        localId: `stage-${idx + 1}-${s.stageKey}`,
        stageKey: s.stageKey,
        displayName: s.displayName,
        stageType: s.stageType,
        dependsOn: [...s.dependsOn],
        timeoutSeconds: s.timeoutSeconds,
        maxRetries: s.maxRetries,
        allowFailure: s.allowFailure,
      }))
    );
    setDefFormError(null);
    setDefFieldErrors({});
    setIsDefModalOpen(true);
  };

  const addStageRow = () => {
    const idx = formStages.length + 1;
    const prevKey = formStages[formStages.length - 1]?.stageKey;
    setFormStages((prev) => [
      ...prev,
      {
        localId: `stage-${Date.now()}-${idx}`,
        stageKey: `stage_${idx}`,
        displayName: `Stage ${idx}`,
        stageType: 'SECURITY_SCAN',
        dependsOn: prevKey ? [prevKey] : [],
        timeoutSeconds: 600,
        maxRetries: 0,
        allowFailure: false,
      },
    ]);
  };

  const removeStageRow = (localId: string, removedKey: string) => {
    setFormStages((prev) =>
      prev
        .filter((row) => row.localId !== localId)
        .map((row) => ({
          ...row,
          dependsOn: row.dependsOn.filter((dep) => dep !== removedKey),
        }))
    );
  };

  const updateStageRow = (
    localId: string,
    updater: (row: EditableStageRow) => EditableStageRow
  ) => {
    setFormStages((prev) =>
      prev.map((row) => (row.localId === localId ? updater(row) : row))
    );
  };

  const toggleStageDependency = (localId: string, depKey: string) => {
    updateStageRow(localId, (row) => {
      const exists = row.dependsOn.includes(depKey);
      return {
        ...row,
        dependsOn: exists
          ? row.dependsOn.filter((k) => k !== depKey)
          : [...row.dependsOn, depKey],
      };
    });
  };

  const handleSaveDefinition = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace) return;

    setIsSavingDef(true);
    setDefFormError(null);
    setDefFieldErrors({});

    const stagePayload: StageDefinitionInput[] = formStages.map((s) => ({
      stageKey: s.stageKey.trim().toLowerCase(),
      displayName: s.displayName.trim(),
      stageType: s.stageType,
      dependsOn: s.dependsOn,
      timeoutSeconds: Number(s.timeoutSeconds) || 600,
      maxRetries: Number(s.maxRetries) || 0,
      allowFailure: s.allowFailure,
    }));

    try {
      if (editingDefinition) {
        const updated = await updatePipelineDefinition(
          activeWorkspace.id,
          editingDefinition.id,
          {
            name: formName.trim(),
            description: formDescription.trim() || undefined,
            linkedRepositoryId: formRepoId || null,
            enabled: formEnabled,
            expectedRowVersion: editingDefinition.rowVersion,
            stages: stagePayload,
          }
        );
        setToast({
          message: `Updated pipeline definition "${updated.name}" to v${updated.definitionVersion}.`,
          tone: 'success',
        });
      } else {
        if (!formProjectId) {
          setDefFormError('Select a workspace project for this pipeline definition.');
          setIsSavingDef(false);
          return;
        }
        const created = await createPipelineDefinition(activeWorkspace.id, {
          projectId: formProjectId,
          name: formName.trim(),
          description: formDescription.trim() || undefined,
          linkedRepositoryId: formRepoId || null,
          enabled: formEnabled,
          stages: stagePayload,
        });
        setToast({
          message: `Created pipeline definition "${created.name}" (v${created.definitionVersion}).`,
          tone: 'success',
        });
      }
      setIsDefModalOpen(false);
      await loadOrchestrationData(true);
    } catch (error) {
      if (error instanceof ApiClientError) {
        setDefFormError(error.message);
        setDefFieldErrors(error.fieldErrors);
      } else {
        setDefFormError(
          error instanceof Error ? error.message : 'Failed to save pipeline definition.'
        );
      }
    } finally {
      setIsSavingDef(false);
    }
  };

  const openQueueRunModal = (def: PipelineDefinition) => {
    setQueueingDef(def);
    setRunBranch('');
    setRunSha('');
    setRunIdempotencyKey(generateIdempotencyKey());
    setQueueRunError(null);
  };

  const handleQueueRunSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || !queueingDef || isQueueingRun) return;

    setIsQueueingRun(true);
    setQueueRunError(null);
    try {
      const createdRun = await createPipelineRun(
        activeWorkspace.id,
        {
          definitionId: queueingDef.id,
          targetBranch: runBranch.trim() || undefined,
          commitSha: runSha.trim() || undefined,
          triggerType: 'MANUAL',
        },
        runIdempotencyKey.trim() || undefined
      );
      setQueueingDef(null);
      setToast({
        message: `Queued pipeline run #${createdRun.runNumber} for "${createdRun.definitionName}" — awaiting execution infrastructure.`,
        tone: 'success',
      });
      await loadOrchestrationData(true);
    } catch (error) {
      setQueueRunError(
        error instanceof Error ? error.message : 'Unable to queue pipeline run.'
      );
    } finally {
      setIsQueueingRun(false);
    }
  };

  const openRunDetails = async (run: PipelineRun) => {
    setSelectedRun(run);
    setRunEvents([]);
    setEventsError(null);
    if (!activeWorkspace) return;
    setIsLoadingEvents(true);
    try {
      const events = await fetchPipelineRunEvents(activeWorkspace.id, run.id);
      setRunEvents(Array.isArray(events) ? events : []);
    } catch (error) {
      setEventsError(
        error instanceof Error ? error.message : 'Unable to load lifecycle transition events.'
      );
    } finally {
      setIsLoadingEvents(false);
    }
  };

  const openCancelModal = (run: PipelineRun) => {
    setCancellingRun(run);
    setCancelReason('');
    setCancelError(null);
  };

  const handleConfirmCancel = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || !cancellingRun || isCancelling) return;

    setIsCancelling(true);
    setCancelError(null);
    try {
      const updated = await cancelPipelineRun(
        activeWorkspace.id,
        cancellingRun.id,
        cancelReason
      );
      setCancellingRun(null);
      setToast({
        message: `Cancelled orchestration run #${updated.runNumber} for "${updated.definitionName}".`,
        tone: 'info',
      });
      if (selectedRun?.id === updated.id) {
        await openRunDetails(updated);
      }
      await loadOrchestrationData(true);
    } catch (error) {
      setCancelError(
        error instanceof Error ? error.message : 'Unable to cancel pipeline run.'
      );
    } finally {
      setIsCancelling(false);
    }
  };

  const enabledDefCount = definitions.filter((d) => d.enabled).length;
  const queuedRunCount = runs.filter((r) => r.status === 'QUEUED').length;
  const cancelledRunCount = runs.filter((r) => r.status === 'CANCELLED').length;

  const definitionColumns: TableColumn<PipelineDefinition>[] = [
    {
      key: 'name',
      header: 'Pipeline Definition',
      render: (row) => (
        <div>
          <strong>{row.name}</strong>{' '}
          <span className="cf-attempt-pill">v{row.definitionVersion}</span>
          {row.description && (
            <span className="cf-pipelines-subtext">{row.description}</span>
          )}
        </div>
      ),
    },
    {
      key: 'project',
      header: 'Project & Source',
      render: (row) => (
        <div>
          <div>{row.projectName}</div>
          <span className="cf-pipelines-subtext mono">
            {row.repositoryFullName ?? 'No repository linked'}
          </span>
        </div>
      ),
    },
    {
      key: 'stages',
      header: 'Stage DAG',
      render: (row) => (
        <div className="cf-stage-pills">
          {row.stages.map((s) => (
            <span
              key={s.id}
              className="cf-stage-pill"
              title={
                s.dependsOn.length > 0
                  ? `${s.displayName} (depends on: ${s.dependsOn.join(', ')})`
                  : `${s.displayName} (root stage)`
              }
            >
              {s.stageKey}
            </span>
          ))}
        </div>
      ),
    },
    {
      key: 'enabled',
      header: 'Status',
      render: (row) => (
        <StatusBadge
          label={row.enabled ? 'Enabled' : 'Disabled'}
          tone={row.enabled ? 'healthy' : 'neutral'}
          size="sm"
        />
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
            className="btn btn-sm btn-primary"
            disabled={!canOperateRuns || !row.enabled}
            title={
              !canOperateRuns
                ? 'Only workspace OWNERs and DEVELOPERs can queue pipeline runs'
                : !row.enabled
                  ? 'Enable this pipeline definition before queueing runs'
                  : `Queue a new run for ${row.name}`
            }
            onClick={() => openQueueRunModal(row)}
          >
            <Play size={12} aria-hidden="true" />
            <span>Queue Run</span>
          </button>
          {isOwner && (
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => openEditDefinitionModal(row)}
            >
              <Edit3 size={12} aria-hidden="true" />
              <span>Edit</span>
            </button>
          )}
        </div>
      ),
    },
  ];

  const runColumns: TableColumn<PipelineRun>[] = [
    {
      key: 'run',
      header: 'Pipeline Run',
      render: (row) => (
        <div>
          <button
            type="button"
            className="cf-pipelines-link-btn"
            onClick={() => void openRunDetails(row)}
          >
            <span>
              {row.definitionName} <span className="mono">#{row.runNumber}</span>
              <span className="cf-attempt-pill">v{row.definitionVersion}</span>
            </span>
          </button>
          <span className="cf-pipelines-subtext">
            {row.projectName}
            {row.requestedByUsername ? ` · requested by @${row.requestedByUsername}` : ''}
          </span>
        </div>
      ),
    },
    {
      key: 'ref',
      header: 'Branch & Commit',
      render: (row) => (
        <div className="cf-run-sha-branch">
          <span className="cf-run-branch">
            <GitBranch size={12} aria-hidden="true" />
            <span>{row.targetBranch ?? '—'}</span>
          </span>
          {row.commitSha && <span className="cf-run-sha">{row.commitSha.slice(0, 7)}</span>}
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Orchestration Status',
      render: (row) => {
        const meta = RUN_STATUS_META[row.status];
        return (
          <StatusBadge
            label={meta.label}
            tone={meta.tone}
            pulse={Boolean(meta.pulse)}
            size="sm"
          />
        );
      },
    },
    {
      key: 'stages',
      header: 'Stages',
      render: (row) => {
        const ready = row.stages.filter((s) => s.status === 'READY').length;
        const pending = row.stages.filter((s) => s.status === 'PENDING').length;
        const cancelled = row.stages.filter((s) => s.status === 'CANCELLED').length;
        return (
          <span className="mono cf-muted" style={{ fontSize: '12px' }}>
            {row.stages.length} total
            {ready > 0 ? ` · ${ready} ready` : ''}
            {pending > 0 ? ` · ${pending} pending` : ''}
            {cancelled > 0 ? ` · ${cancelled} cancelled` : ''}
          </span>
        );
      },
    },
    {
      key: 'queuedAt',
      header: 'Queued',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {formatTimestamp(row.queuedAt)}
        </span>
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
            onClick={() => void openRunDetails(row)}
          >
            <Layers size={12} aria-hidden="true" />
            <span>Inspect</span>
          </button>
          {canOperateRuns && (row.status === 'QUEUED' || row.status === 'RUNNING') && (
            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={() => openCancelModal(row)}
            >
              <Ban size={12} aria-hidden="true" />
              <span>Cancel</span>
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="cf-orch-panel">
      {toast && (
        <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />
      )}

      {errorMessage && (
        <ErrorState
          title="CloudForge pipeline orchestration unavailable"
          message={errorMessage}
          onRetry={() => void loadOrchestrationData(false)}
        />
      )}

      <div className="cf-notice cf-notice--info">
        <div className="cf-notice__icon" aria-hidden="true">
          <Info size={18} />
        </div>
        <div>
          <p className="cf-notice__title">
            Phase 3.2 Orchestration Engine — Persistent Definitions, DAG Scheduling &amp; State Machine
          </p>
          <p className="cf-notice__text">
            CloudForge pipeline definitions and runs are distinct from GitHub Actions workflows.
            Queueing a run persists an immutable definition snapshot and marks root stages{' '}
            <span className="mono">READY</span> while the run remains{' '}
            <strong>Queued — Awaiting execution infrastructure</strong> until Phase 3.3 isolated
            build workers are introduced.
          </p>
        </div>
      </div>

      {/* Overview telemetry strip */}
      <section
        aria-label="CloudForge orchestration summary"
        className="cf-grid cf-grid--4 cf-pipelines-stats"
      >
        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Pipeline Definitions</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Workflow size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="orch-def-count">
            {isLoading ? '—' : totalDefinitions}
          </div>
          <div className="cf-stat-tile__meta">{enabledDefCount} enabled in current view</div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Orchestrated Runs</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Activity size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="orch-run-count">
            {isLoading ? '—' : totalRuns}
          </div>
          <div className="cf-stat-tile__meta">Persisted execution intents</div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Queued Runs</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Layers size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="orch-queued-count">
            {isLoading ? '—' : queuedRunCount}
          </div>
          <div className="cf-stat-tile__meta">Awaiting Phase 3.3 worker engine</div>
        </div>

        <div className="cf-stat-tile">
          <div className="cf-stat-tile__top">
            <span className="cf-stat-tile__label">Cancelled Runs</span>
            <span className="cf-stat-tile__icon" aria-hidden="true">
              <Ban size={16} />
            </span>
          </div>
          <div className="cf-stat-tile__value" data-testid="orch-cancelled-count">
            {isLoading ? '—' : cancelledRunCount}
          </div>
          <div className="cf-stat-tile__meta">Stopped in orchestration state</div>
        </div>
      </section>

      {/* Definitions Card */}
      <DashboardCard
        title="CloudForge Pipeline Definitions"
        subtitle="Project-scoped declarative stage DAGs (commands and plaintext secrets prohibited)"
        icon={<Workflow size={17} />}
        headerAction={
          <div className="cf-inline-actions">
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => void loadOrchestrationData(true)}
              disabled={isLoading || isRefreshing}
            >
              <RefreshCw
                size={12}
                className={isRefreshing ? 'cf-spin' : ''}
                aria-hidden="true"
              />
              <span>Refresh</span>
            </button>
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={openCreateDefinitionModal}
              disabled={!isOwner || projects.length === 0}
              title={
                !isOwner
                  ? 'Only workspace OWNERs can create or modify pipeline definitions'
                  : projects.length === 0
                    ? 'Create a workspace project first before defining a pipeline'
                    : 'Create a new CloudForge pipeline definition'
              }
            >
              <Plus size={13} aria-hidden="true" />
              <span>New Pipeline Definition</span>
            </button>
          </div>
        }
      >
        <div className="cf-toolbar">
          <SearchField
            label="Search pipeline definitions"
            placeholder="Search pipeline definitions by name or description..."
            value={defSearch}
            onChange={setDefSearch}
          />
          <div className="cf-filter-group">
            <select
              className="cf-filter-select"
              aria-label="Filter definitions by project"
              value={projectFilter}
              onChange={(e) => setProjectFilter(e.target.value)}
            >
              <option value="">All projects</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        {isLoading ? (
          <LoadingState
            title="Loading pipeline definitions"
            description="Fetching persisted stage DAG definitions for this workspace..."
          />
        ) : (
          <DataTable
            columns={definitionColumns}
            rows={definitions}
            getRowKey={(row) => row.id}
            emptyMessage={
              projects.length === 0
                ? 'Create a project on the Projects page before creating a CloudForge pipeline definition.'
                : 'No CloudForge pipeline definitions yet. Create one to model stage dependencies and queue runs.'
            }
            caption="CloudForge pipeline definitions"
          />
        )}
      </DashboardCard>

      {/* Runs Card */}
      <DashboardCard
        title="CloudForge Pipeline Runs"
        subtitle="Immutable execution snapshots, stage dependency state, and cancellation controls"
        icon={<Activity size={17} />}
        headerAction={
          <StatusBadge
            label={`${totalRuns} ${totalRuns === 1 ? 'Run' : 'Runs'}`}
            tone={totalRuns > 0 ? 'info' : 'neutral'}
            size="sm"
          />
        }
      >
        <div className="cf-toolbar">
          <SearchField
            label="Search orchestrated runs"
            placeholder="Search runs by definition, branch, or commit SHA..."
            value={runSearch}
            onChange={setRunSearch}
          />
          <div className="cf-filter-group">
            <select
              className="cf-filter-select"
              aria-label="Filter orchestrated runs by status"
              value={runStatusFilter}
              onChange={(e) => setRunStatusFilter(e.target.value as PipelineRunStatus | '')}
            >
              <option value="">All statuses</option>
              <option value="QUEUED">Queued</option>
              <option value="RUNNING">Running</option>
              <option value="SUCCEEDED">Succeeded</option>
              <option value="FAILED">Failed</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
          </div>
        </div>

        {isLoading ? (
          <LoadingState
            title="Loading orchestrated runs"
            description="Fetching persisted pipeline execution records..."
          />
        ) : (
          <DataTable
            columns={runColumns}
            rows={runs}
            getRowKey={(row) => row.id}
            emptyMessage="No CloudForge pipeline runs queued yet. Select an enabled pipeline definition above and click Queue Run."
            caption="CloudForge pipeline runs"
          />
        )}
      </DashboardCard>

      {/* Create / Edit Pipeline Definition Modal */}
      <Modal
        isOpen={isDefModalOpen}
        title={
          editingDefinition
            ? `Edit Pipeline Definition — ${editingDefinition.name}`
            : 'New Pipeline Definition'
        }
        subtitle="Declare project-scoped stages and DAG dependencies (no arbitrary shell commands)"
        onClose={() => setIsDefModalOpen(false)}
      >
        <ValidatedForm className="cf-form" onSubmit={(e) => void handleSaveDefinition(e)}>
          {defFormError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertTriangle size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Pipeline definition validation failed</p>
                <p className="cf-notice__text">{defFormError}</p>
                {Object.keys(defFieldErrors).length > 0 && (
                  <ul style={{ margin: '6px 0 0', paddingLeft: '18px', fontSize: '12px' }}>
                    {Object.entries(defFieldErrors).map(([field, msg]) => (
                      <li key={field}>
                        <span className="mono">{field}</span>: {msg}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}

          {!editingDefinition && (
            <div className="cf-field">
              <label htmlFor="cf-orch-project" className="cf-field__label">
                Project *
              </label>
              <select
                id="cf-orch-project"
                className="cf-select"
                required
                value={formProjectId}
                onChange={(e) => {
                  const nextProjId = e.target.value;
                  setFormProjectId(nextProjId);
                  const proj = projects.find((p) => p.id === nextProjId);
                  if (proj?.linkedRepository) {
                    setFormRepoId(proj.linkedRepository.id);
                  }
                }}
              >
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.slug})
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="cf-field">
            <label htmlFor="cf-orch-name" className="cf-field__label">
              Pipeline Name *
            </label>
            <input
              id="cf-orch-name"
              type="text"
              className="cf-input"
              required
              minLength={2}
              maxLength={120}
              placeholder="e.g. Production Build & Verify"
              value={formName}
              onChange={(e) => setFormName(e.target.value)}
            />
          </div>

          <div className="cf-field">
            <label htmlFor="cf-orch-desc" className="cf-field__label">
              Description
            </label>
            <textarea
              id="cf-orch-desc"
              className="cf-textarea"
              maxLength={1000}
              placeholder="Describe the intended build, scan, and release orchestration graph..."
              value={formDescription}
              onChange={(e) => setFormDescription(e.target.value)}
            />
          </div>

          <div className="cf-field">
            <label htmlFor="cf-orch-repo" className="cf-field__label">
              Source Repository (Optional)
            </label>
            <select
              id="cf-orch-repo"
              className="cf-select"
              value={formRepoId}
              onChange={(e) => setFormRepoId(e.target.value)}
            >
              <option value="">None / Inherit later</option>
              {repositories.map((repo) => (
                <option key={repo.id} value={repo.id}>
                  {repo.fullName} ({repo.connectionStatus})
                </option>
              ))}
            </select>
          </div>

          <label className="cf-checkbox-inline">
            <input
              type="checkbox"
              checked={formEnabled}
              onChange={(e) => setFormEnabled(e.target.checked)}
            />
            <span>Enabled (allow authorized users to queue pipeline runs)</span>
          </label>

          {/* Accessible Stage & Dependency Builder */}
          <div className="cf-field">
            <div className="cf-jobs-section__header">
              <span className="cf-field__label">Pipeline Stages &amp; Dependencies *</span>
              <button
                type="button"
                className="btn btn-sm"
                onClick={addStageRow}
                disabled={formStages.length >= 25}
              >
                <Plus size={12} aria-hidden="true" />
                <span>Add Stage</span>
              </button>
            </div>

            <div className="cf-stage-editor-list">
              {formStages.map((stage, idx) => {
                const candidateDeps = formStages.filter(
                  (other) =>
                    other.localId !== stage.localId && other.stageKey.trim().length > 0
                );
                return (
                  <div key={stage.localId} className="cf-stage-editor-card">
                    <div className="cf-stage-editor-card__header">
                      <span className="cf-stage-editor-card__title">
                        Stage {idx + 1} · {stage.stageKey || 'unnamed'}
                      </span>
                      {formStages.length > 1 && (
                        <button
                          type="button"
                          className="btn btn-sm btn-danger"
                          onClick={() => removeStageRow(stage.localId, stage.stageKey)}
                          aria-label={`Remove stage ${stage.stageKey || idx + 1}`}
                        >
                          <Trash2 size={12} aria-hidden="true" />
                          <span>Remove</span>
                        </button>
                      )}
                    </div>

                    <div className="cf-stage-editor-grid">
                      <div className="cf-field">
                        <label
                          htmlFor={`stage-key-${stage.localId}`}
                          className="cf-field__label"
                        >
                          Stage Key *
                        </label>
                        <input
                          id={`stage-key-${stage.localId}`}
                          type="text"
                          className="cf-input mono"
                          required
                          value={stage.stageKey}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              stageKey: e.target.value,
                            }))
                          }
                        />
                      </div>

                      <div className="cf-field">
                        <label
                          htmlFor={`stage-name-${stage.localId}`}
                          className="cf-field__label"
                        >
                          Display Name *
                        </label>
                        <input
                          id={`stage-name-${stage.localId}`}
                          type="text"
                          className="cf-input"
                          required
                          value={stage.displayName}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              displayName: e.target.value,
                            }))
                          }
                        />
                      </div>
                    </div>

                    <div className="cf-stage-editor-grid cf-stage-editor-grid--3">
                      <div className="cf-field">
                        <label
                          htmlFor={`stage-type-${stage.localId}`}
                          className="cf-field__label"
                        >
                          Stage Type
                        </label>
                        <select
                          id={`stage-type-${stage.localId}`}
                          className="cf-select"
                          value={stage.stageType}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              stageType: e.target.value as PipelineStageType,
                            }))
                          }
                        >
                          {STAGE_TYPE_OPTIONS.map((opt) => (
                            <option key={opt.value} value={opt.value}>
                              {opt.label}
                            </option>
                          ))}
                        </select>
                      </div>

                      <div className="cf-field">
                        <label
                          htmlFor={`stage-timeout-${stage.localId}`}
                          className="cf-field__label"
                        >
                          Timeout (sec)
                        </label>
                        <input
                          id={`stage-timeout-${stage.localId}`}
                          type="number"
                          className="cf-input mono"
                          min={30}
                          max={7200}
                          value={stage.timeoutSeconds}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              timeoutSeconds: Number(e.target.value),
                            }))
                          }
                        />
                      </div>

                      <div className="cf-field">
                        <label
                          htmlFor={`stage-retries-${stage.localId}`}
                          className="cf-field__label"
                        >
                          Max Retries
                        </label>
                        <input
                          id={`stage-retries-${stage.localId}`}
                          type="number"
                          className="cf-input mono"
                          min={0}
                          max={5}
                          value={stage.maxRetries}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              maxRetries: Number(e.target.value),
                            }))
                          }
                        />
                      </div>
                    </div>

                    {candidateDeps.length > 0 && (
                      <div className="cf-field">
                        <span className="cf-field__label">Depends On (Predecessor Stages)</span>
                        <div className="cf-dep-checklist">
                          {candidateDeps.map((dep) => {
                            const depKey = dep.stageKey.trim().toLowerCase();
                            return (
                              <label key={dep.localId} className="cf-dep-checkbox">
                                <input
                                  type="checkbox"
                                  checked={stage.dependsOn.includes(depKey)}
                                  onChange={() => toggleStageDependency(stage.localId, depKey)}
                                />
                                <span>{depKey}</span>
                              </label>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          <div className="cf-inline-actions cf-inline-actions--end">
            <button
              type="button"
              className="btn"
              onClick={() => setIsDefModalOpen(false)}
              disabled={isSavingDef}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSavingDef}>
              {isSavingDef
                ? 'Saving...'
                : editingDefinition
                  ? 'Save Definition Update'
                  : 'Create Pipeline Definition'}
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Queue Pipeline Run Modal */}
      <Modal
        isOpen={queueingDef !== null}
        title={queueingDef ? `Queue Run — ${queueingDef.name}` : 'Queue Pipeline Run'}
        subtitle={
          queueingDef
            ? `Captures immutable snapshot of v${queueingDef.definitionVersion} (${queueingDef.stages.length} stages)`
            : undefined
        }
        onClose={() => setQueueingDef(null)}
      >
        {queueingDef && (
          <ValidatedForm className="cf-form" onSubmit={(e) => void handleQueueRunSubmit(e)}>
            <div className="cf-notice cf-notice--warning" role="status">
              <div className="cf-notice__icon" aria-hidden="true">
                <Info size={17} />
              </div>
              <div>
                <p className="cf-notice__title">Queued — Awaiting execution infrastructure</p>
                <p className="cf-notice__text">
                  Queueing persists an immutable execution request and evaluates root stage
                  readiness. No build or deployment executes until Phase 3.3 isolated workers are
                  deployed.
                </p>
              </div>
            </div>

            {queueRunError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertTriangle size={17} />
                </div>
                <div>
                  <p className="cf-notice__title">Could not queue pipeline run</p>
                  <p className="cf-notice__text">{queueRunError}</p>
                </div>
              </div>
            )}

            <div className="cf-field">
              <label htmlFor="cf-run-branch" className="cf-field__label">
                Target Branch (Optional)
              </label>
              <input
                id="cf-run-branch"
                type="text"
                className="cf-input mono"
                placeholder="Defaults to project default branch"
                value={runBranch}
                onChange={(e) => setRunBranch(e.target.value)}
              />
            </div>

            <div className="cf-field">
              <label htmlFor="cf-run-sha" className="cf-field__label">
                Commit SHA (Optional)
              </label>
              <input
                id="cf-run-sha"
                type="text"
                className="cf-input mono"
                placeholder="7-40 hex characters (optional)"
                value={runSha}
                onChange={(e) => setRunSha(e.target.value)}
              />
            </div>

            <div className="cf-field">
              <label htmlFor="cf-run-idem-key" className="cf-field__label">
                Idempotency Key
              </label>
              <input
                id="cf-run-idem-key"
                type="text"
                className="cf-input mono"
                value={runIdempotencyKey}
                onChange={(e) => setRunIdempotencyKey(e.target.value)}
              />
              <span className="cf-field__hint">
                Sent via <span className="mono">Idempotency-Key</span> header to prevent duplicate
                runs across retries.
              </span>
            </div>

            <div className="cf-inline-actions cf-inline-actions--end">
              <button
                type="button"
                className="btn"
                onClick={() => setQueueingDef(null)}
                disabled={isQueueingRun}
              >
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={isQueueingRun}>
                {isQueueingRun ? 'Queueing...' : 'Confirm Queue Run'}
              </button>
            </div>
          </ValidatedForm>
        )}
      </Modal>

      {/* Run Details & Lifecycle Events Modal */}
      <Modal
        isOpen={selectedRun !== null}
        title={
          selectedRun
            ? `${selectedRun.definitionName} #${selectedRun.runNumber}`
            : 'Pipeline Run Details'
        }
        subtitle={
          selectedRun
            ? `${selectedRun.projectName} · Immutable Snapshot v${selectedRun.definitionVersion}`
            : undefined
        }
        onClose={() => setSelectedRun(null)}
        footer={
          selectedRun && (
            <>
              {canOperateRuns &&
                (selectedRun.status === 'QUEUED' || selectedRun.status === 'RUNNING') && (
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => openCancelModal(selectedRun)}
                  >
                    <Ban size={14} aria-hidden="true" />
                    <span>Cancel Run</span>
                  </button>
                )}
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => setSelectedRun(null)}
              >
                Close
              </button>
            </>
          )
        }
      >
        {selectedRun && (
          <div className="cf-run-detail-section">
            <div className="cf-run-detail-header-strip">
              <StatusBadge
                label={RUN_STATUS_META[selectedRun.status].label}
                tone={RUN_STATUS_META[selectedRun.status].tone}
                pulse={Boolean(RUN_STATUS_META[selectedRun.status].pulse)}
                size="sm"
              />
              <span className="mono cf-muted" style={{ fontSize: '12px' }}>
                Trigger: {selectedRun.triggerType}
              </span>
            </div>

            {selectedRun.statusReason && (
              <p className="cf-muted" style={{ margin: 0, fontSize: '13px' }}>
                {selectedRun.statusReason}
              </p>
            )}

            <dl className="cf-kv-list">
              <div className="cf-kv-item">
                <dt>Project</dt>
                <dd>{selectedRun.projectName}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Source Repository</dt>
                <dd className="mono">{selectedRun.repositoryFullName ?? '—'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Target Branch</dt>
                <dd className="mono">{selectedRun.targetBranch ?? '—'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Commit SHA</dt>
                <dd className="mono">{selectedRun.commitSha ?? '—'}</dd>
              </div>
              <div className="cf-kv-item">
                <dt>Requested By</dt>
                <dd className="mono">
                  {selectedRun.requestedByUsername ? `@${selectedRun.requestedByUsername}` : '—'}
                </dd>
              </div>
              <div className="cf-kv-item">
                <dt>Queued At</dt>
                <dd className="mono">{formatTimestamp(selectedRun.queuedAt)}</dd>
              </div>
              {selectedRun.cancelledAt && (
                <>
                  <div className="cf-kv-item">
                    <dt>Cancelled By</dt>
                    <dd className="mono">
                      {selectedRun.cancelledByUsername
                        ? `@${selectedRun.cancelledByUsername}`
                        : '—'}
                    </dd>
                  </div>
                  <div className="cf-kv-item">
                    <dt>Cancelled At</dt>
                    <dd className="mono">{formatTimestamp(selectedRun.cancelledAt)}</dd>
                  </div>
                </>
              )}
            </dl>

            <div className="cf-jobs-section">
              <h3 className="cf-jobs-section__title">
                Stage Instances &amp; Dependencies ({selectedRun.stages.length})
              </h3>
              <div className="cf-orch-stage-list">
                {selectedRun.stages.map((stage) => {
                  const sMeta = STAGE_STATUS_META[stage.status];
                  return (
                    <div key={stage.id} className="cf-orch-stage-card">
                      <div className="cf-orch-stage-card__top">
                        <div className="cf-orch-stage-card__title">
                          <span className="mono">#{stage.executionOrder + 1}</span>
                          <span>{stage.displayName}</span>
                          <span className="cf-stage-pill">{stage.stageKey}</span>
                          <span className="cf-event-pill">
                            {formatStageTypeLabel(stage.stageType)}
                          </span>
                        </div>
                        <StatusBadge
                          label={sMeta.label}
                          tone={sMeta.tone}
                          pulse={Boolean(sMeta.pulse)}
                          size="sm"
                        />
                      </div>
                      <div className="cf-orch-stage-card__meta">
                        <span>
                          Dependencies:{' '}
                          <strong className="mono">
                            {stage.dependsOn.length > 0 ? stage.dependsOn.join(', ') : 'None (root)'}
                          </strong>
                        </span>
                        <span>
                          Timeout: <strong className="mono">{stage.timeoutSeconds}s</strong>
                        </span>
                        <span>
                          Max retries: <strong className="mono">{stage.maxRetries}</strong>
                        </span>
                      </div>
                      {stage.statusReason && (
                        <div className="cf-muted" style={{ fontSize: '12px' }}>
                          {stage.statusReason}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            <div className="cf-jobs-section">
              <div className="cf-jobs-section__header">
                <h3 className="cf-jobs-section__title">
                  <History
                    size={14}
                    aria-hidden="true"
                    style={{ verticalAlign: '-2px', marginRight: '6px' }}
                  />
                  Lifecycle Transition History
                </h3>
              </div>
              {isLoadingEvents ? (
                <LoadingState
                  title="Loading transition history"
                  description="Fetching immutable pipeline run events..."
                />
              ) : eventsError ? (
                <ErrorState title="Unable to load run events" message={eventsError} />
              ) : runEvents.length === 0 ? (
                <p className="cf-muted" style={{ margin: 0, fontSize: '12.5px' }}>
                  No lifecycle events recorded yet.
                </p>
              ) : (
                <ol className="cf-event-timeline">
                  {runEvents.map((ev) => (
                    <li key={ev.id} className="cf-event-item">
                      <div className="cf-event-item__top">
                        <span className="cf-event-item__type">
                          {ev.eventType}
                          {ev.stageKey ? ` · ${ev.stageKey}` : ''} (
                          {ev.fromStatus ? `${ev.fromStatus} → ${ev.toStatus}` : ev.toStatus})
                        </span>
                        <span className="cf-event-item__time">
                          {formatTimestamp(ev.occurredAt)}
                        </span>
                      </div>
                      <div className="cf-event-item__msg">
                        {ev.message}
                        {ev.actorUsername ? ` (@${ev.actorUsername})` : ''}
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </div>
          </div>
        )}
      </Modal>

      {/* Cancel Run Confirmation Modal */}
      <Modal
        isOpen={cancellingRun !== null}
        title={
          cancellingRun
            ? `Cancel Run #${cancellingRun.runNumber}`
            : 'Cancel Pipeline Run'
        }
        subtitle="Applies to CloudForge orchestration state and stops any unstarted stage scheduling."
        onClose={() => setCancellingRun(null)}
      >
        {cancellingRun && (
          <ValidatedForm className="cf-form" onSubmit={(e) => void handleConfirmCancel(e)}>
            {cancelError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertTriangle size={17} />
                </div>
                <div>
                  <p className="cf-notice__title">Cancellation failed</p>
                  <p className="cf-notice__text">{cancelError}</p>
                </div>
              </div>
            )}

            <p className="cf-muted" style={{ margin: 0, fontSize: '13px' }}>
              Cancelling <strong>{cancellingRun.definitionName}</strong> #{cancellingRun.runNumber}{' '}
              marks the run and all unstarted stages <span className="mono">CANCELLED</span> in
              CloudForge&apos;s orchestration ledger.
            </p>

            <div className="cf-field">
              <label htmlFor="cf-cancel-reason" className="cf-field__label">
                Cancellation Reason (Optional)
              </label>
              <input
                id="cf-cancel-reason"
                type="text"
                className="cf-input"
                maxLength={400}
                placeholder="e.g. Superseded by newer commit"
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
              />
            </div>

            <div className="cf-inline-actions cf-inline-actions--end">
              <button
                type="button"
                className="btn"
                onClick={() => setCancellingRun(null)}
                disabled={isCancelling}
              >
                Keep Queued
              </button>
              <button type="submit" className="btn btn-danger" disabled={isCancelling}>
                {isCancelling ? 'Cancelling...' : 'Confirm Cancel Run'}
              </button>
            </div>
          </ValidatedForm>
        )}
      </Modal>
    </div>
  );
}
