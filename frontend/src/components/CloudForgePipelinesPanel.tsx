import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  Activity,
  AlertTriangle,
  Ban,
  ChevronDown,
  ChevronUp,
  Cpu,
  Edit3,
  GitBranch,
  History,
  Info,
  KeyRound,
  Layers,
  Lock,
  PackageCheck,
  Play,
  Plus,
  RefreshCw,
  ShieldCheck,
  Terminal,
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
  fetchEnvironmentSecrets,
  fetchOciRegistries,
  fetchPipelineDefinitions,
  fetchPipelineRunArtifacts,
  fetchPipelineRunEvents,
  fetchPipelineRuns,
  fetchProjectDetail,
  fetchProjects,
  fetchRepositories,
  fetchStageExecutionAttempts,
  fetchStageExecutionLogs,
  fetchWorkerPoolStatus,
  updatePipelineDefinition,
} from '../services/api';
import type {
  AllowedWorkerOperation,
  AttemptStatus,
  EnvironmentSecretSummary,
  LinkedRepository,
  OciRegistryConnection,
  PipelineDefinition,
  PipelineRun,
  PipelineRunArtifacts,
  PipelineRunEvent,
  PipelineRunStatus,
  PipelineStageRun,
  PipelineStageStatus,
  PipelineStageType,
  ProjectEnvironment,
  ProjectSummary,
  StageDefinitionInput,
  StageExecutionAttempt,
  StageExecutionLog,
  StageSecretBindingInput,
  WorkerPoolStatus,
  WorkerStatus,
} from '../types/platform';
import './CloudForgePipelinesPanel.css';
import './OciArtifactsPanel.css';

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

const ALLOWLISTED_OPS_BY_STAGE_TYPE: Partial<
  Record<PipelineStageType, Array<{ value: AllowedWorkerOperation; label: string }>>
> = {
  SOURCE_PREP: [
    { value: 'GITHUB_SOURCE_ACQUIRE', label: 'GitHub source acquisition (Dedicated worker)' },
    { value: 'ENV_VALIDATION', label: 'ENV_VALIDATION (Container sandbox check)' },
    { value: 'WORKER_DIAGNOSTICS', label: 'WORKER_DIAGNOSTICS (Resource & isolation probe)' },
  ],
  BUILD: [
    { value: 'OCI_IMAGE_BUILD', label: 'OCI_IMAGE_BUILD (Dedicated BuildKit OCI image build)' },
  ],
  TEST: [
    { value: 'CONTROLLED_SMOKE_TEST', label: 'CONTROLLED_SMOKE_TEST (Deterministic smoke test)' },
    { value: 'WORKER_DIAGNOSTICS', label: 'WORKER_DIAGNOSTICS (Resource & isolation probe)' },
  ],
  CONTAINER_PACKAGE: [
    { value: 'OCI_IMAGE_PUBLISH', label: 'OCI_IMAGE_PUBLISH (Publish & verify immutable digest)' },
  ],
};

function isOciStageOperation(op: string): boolean {
  return op === 'OCI_IMAGE_BUILD' || op === 'OCI_IMAGE_PUBLISH';
}

function stageSupportsSecretBindings(stageType: PipelineStageType, allowedOperation: string): boolean {
  return (
    (ALLOWLISTED_OPS_BY_STAGE_TYPE[stageType]?.length ?? 0) > 0 &&
    Boolean(allowedOperation) &&
    allowedOperation !== 'GITHUB_SOURCE_ACQUIRE' &&
    !isOciStageOperation(allowedOperation)
  );
}

const WORKER_STATUS_META: Record<WorkerStatus, { label: string; tone: BadgeTone; pulse?: boolean }> = {
  AVAILABLE: { label: 'Available', tone: 'healthy', pulse: true },
  BUSY: { label: 'Busy', tone: 'info', pulse: true },
  DRAINING: { label: 'Draining', tone: 'degraded' },
  OFFLINE: { label: 'Offline', tone: 'neutral' },
};

const ATTEMPT_STATUS_META: Record<AttemptStatus, { label: string; tone: BadgeTone }> = {
  CLAIMED: { label: 'Starting (Claimed)', tone: 'info' },
  RUNNING: { label: 'Running', tone: 'info' },
  SUCCEEDED: { label: 'Succeeded', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  TIMED_OUT: { label: 'Timed Out', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  INTERRUPTED: { label: 'Interrupted', tone: 'degraded' },
  EXPIRED: { label: 'Lease Expired (Recovered)', tone: 'degraded' },
};

interface EditableStageRow {
  localId: string;
  stageKey: string;
  displayName: string;
  stageType: PipelineStageType;
  allowedOperation: string;
  dependsOn: string[];
  timeoutSeconds: number;
  maxRetries: number;
  allowFailure: boolean;
  secretBindings: StageSecretBindingInput[];
  ociRegistryId?: string;
  ociDockerfilePath?: string;
  ociContextPath?: string;
  ociTargetImageName?: string;
  ociTargetTag?: string;
  ociTargetPlatform?: string;
  ociBuildMode?: string;
}

function defaultStarterStages(): EditableStageRow[] {
  return [
    {
      localId: 'stage-1',
      stageKey: 'source',
      displayName: 'Source Preparation',
      stageType: 'SOURCE_PREP',
      allowedOperation: 'ENV_VALIDATION',
      dependsOn: [],
      timeoutSeconds: 300,
      maxRetries: 0,
      allowFailure: false,
      secretBindings: [],
    },
    {
      localId: 'stage-2',
      stageKey: 'build',
      displayName: 'Application Build',
      stageType: 'BUILD',
      allowedOperation: '',
      dependsOn: ['source'],
      timeoutSeconds: 600,
      maxRetries: 1,
      allowFailure: false,
      secretBindings: [],
    },
    {
      localId: 'stage-3',
      stageKey: 'test',
      displayName: 'Automated Test Suite',
      stageType: 'TEST',
      allowedOperation: 'CONTROLLED_SMOKE_TEST',
      dependsOn: ['build'],
      timeoutSeconds: 600,
      maxRetries: 0,
      allowFailure: false,
      secretBindings: [],
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
  const [workerPool, setWorkerPool] = useState<WorkerPoolStatus | null>(null);
  const [ociRegistries, setOciRegistries] = useState<OciRegistryConnection[]>([]);
  const [selectedRunArtifacts, setSelectedRunArtifacts] = useState<PipelineRunArtifacts | null>(null);

  // Stage execution attempts & bounded sanitized logs state (keyed by stageRunId)
  const [expandedStageId, setExpandedStageId] = useState<string | null>(null);
  const [stageAttempts, setStageAttempts] = useState<Record<string, StageExecutionAttempt[]>>({});
  const [stageLogs, setStageLogs] = useState<Record<string, StageExecutionLog[]>>({});
  const [stageExecLoading, setStageExecLoading] = useState<Record<string, boolean>>({});
  const [stageExecError, setStageExecError] = useState<Record<string, string | null>>({});

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
  const [formTargetEnvId, setFormTargetEnvId] = useState('');
  const [projectEnvironments, setProjectEnvironments] = useState<ProjectEnvironment[]>([]);
  const [availableSecrets, setAvailableSecrets] = useState<EnvironmentSecretSummary[]>([]);
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
        const [projRes, repoRes, defRes, runRes, workerRes, regRes] = await Promise.all([
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
          fetchWorkerPoolStatus(activeWorkspace.id).catch(() => null),
          fetchOciRegistries(activeWorkspace.id).catch(() => []),
        ]);

        setProjects(Array.isArray(projRes.items) ? projRes.items : []);
        setRepositories(Array.isArray(repoRes.items) ? repoRes.items : []);
        setDefinitions(Array.isArray(defRes.items) ? defRes.items : []);
        setTotalDefinitions(defRes.totalElements ?? 0);
        setRuns(Array.isArray(runRes.items) ? runRes.items : []);
        setTotalRuns(runRes.totalElements ?? 0);
        setWorkerPool(
          workerRes && typeof workerRes.workerPlaneEnabled === 'boolean' ? workerRes : null
        );
        setOciRegistries(Array.isArray(regRes) ? regRes : []);
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

  useEffect(() => {
    if (!isDefModalOpen || !isOwner || !activeWorkspace || !formProjectId) {
      setProjectEnvironments([]);
      return;
    }
    let cancelled = false;
    void fetchProjectDetail(activeWorkspace.id, formProjectId)
      .then((detail) => {
        if (!cancelled) {
          setProjectEnvironments(Array.isArray(detail.environments) ? detail.environments : []);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setProjectEnvironments([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isDefModalOpen, isOwner, activeWorkspace, formProjectId]);

  useEffect(() => {
    if (!isDefModalOpen || !isOwner || !activeWorkspace || !formProjectId || !formTargetEnvId) {
      setAvailableSecrets([]);
      return;
    }
    let cancelled = false;
    void fetchEnvironmentSecrets(activeWorkspace.id, formProjectId, formTargetEnvId, {
      status: 'ACTIVE',
      size: 100,
    })
      .then((res) => {
        if (!cancelled) {
          setAvailableSecrets(Array.isArray(res.items) ? res.items : []);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setAvailableSecrets([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isDefModalOpen, isOwner, activeWorkspace, formProjectId, formTargetEnvId]);

  const openCreateDefinitionModal = () => {
    setEditingDefinition(null);
    const defaultProj = projects[0];
    setFormProjectId(defaultProj?.id ?? '');
    setFormName('');
    setFormDescription('');
    setFormRepoId(defaultProj?.linkedRepository?.id ?? '');
    setFormTargetEnvId('');
    setAvailableSecrets([]);
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
    setFormTargetEnvId(def.targetEnvironmentId ?? '');
    setFormEnabled(def.enabled);
    setFormStages(
      def.stages.map((s, idx) => ({
        localId: `stage-${idx + 1}-${s.stageKey}`,
        stageKey: s.stageKey,
        displayName: s.displayName,
        stageType: s.stageType,
        allowedOperation: s.metadata?.operation ?? '',
        dependsOn: [...s.dependsOn],
        timeoutSeconds: s.timeoutSeconds,
        maxRetries: s.maxRetries,
        allowFailure: s.allowFailure,
        secretBindings: (s.secretBindings ?? []).map((b) => ({
          secretId: b.secretId,
          deliveryAlias: b.deliveryAlias,
        })),
        ociRegistryId: s.metadata?.registryConnectionId ?? '',
        ociDockerfilePath: s.metadata?.dockerfilePath ?? 'Dockerfile',
        ociContextPath: s.metadata?.buildContextPath ?? '.',
        ociTargetImageName: s.metadata?.targetImageName ?? '',
        ociTargetTag: s.metadata?.targetTag ?? '',
        ociTargetPlatform: s.metadata?.targetPlatform ?? 'linux/amd64',
        ociBuildMode: s.metadata?.buildMode ?? 'ROOTLESS_BUILDKIT',
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
        stageType: 'TEST',
        allowedOperation: 'WORKER_DIAGNOSTICS',
        dependsOn: prevKey ? [prevKey] : [],
        timeoutSeconds: 600,
        maxRetries: 0,
        allowFailure: false,
        secretBindings: [],
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

    const stagePayload: StageDefinitionInput[] = formStages.map((s) => {
      const stageEligibleForSecrets = stageSupportsSecretBindings(s.stageType, s.allowedOperation);
      const cleanedBindings = stageEligibleForSecrets
        ? s.secretBindings.filter((b) => b.secretId && b.deliveryAlias.trim().length > 0)
        : [];
      let metadata: Record<string, string> | undefined;
      if (s.allowedOperation) {
        metadata = { operation: s.allowedOperation };
        if (s.allowedOperation === 'OCI_IMAGE_BUILD') {
          if (s.ociRegistryId) metadata.registryConnectionId = s.ociRegistryId;
          metadata.dockerfilePath = (s.ociDockerfilePath ?? 'Dockerfile').trim() || 'Dockerfile';
          metadata.buildContextPath = (s.ociContextPath ?? '.').trim() || '.';
          if (s.ociTargetImageName?.trim()) metadata.targetImageName = s.ociTargetImageName.trim();
          if (s.ociTargetTag?.trim()) metadata.targetTag = s.ociTargetTag.trim();
          metadata.targetPlatform = s.ociTargetPlatform || 'linux/amd64';
          metadata.buildMode = s.ociBuildMode || 'ROOTLESS_BUILDKIT';
        } else if (s.allowedOperation === 'OCI_IMAGE_PUBLISH') {
          if (s.ociRegistryId) metadata.registryConnectionId = s.ociRegistryId;
          if (s.ociTargetImageName?.trim()) metadata.targetImageName = s.ociTargetImageName.trim();
          if (s.ociTargetTag?.trim()) metadata.targetTag = s.ociTargetTag.trim();
        }
      }
      return {
        stageKey: s.stageKey.trim().toLowerCase(),
        displayName: s.displayName.trim(),
        stageType: s.stageType,
        dependsOn: s.dependsOn,
        timeoutSeconds: Number(s.timeoutSeconds) || 600,
        maxRetries: Number(s.maxRetries) || 0,
        allowFailure: s.allowFailure,
        metadata,
        secretBindings: cleanedBindings.length > 0 ? cleanedBindings : undefined,
      };
    });

    try {
      if (editingDefinition) {
        const updated = await updatePipelineDefinition(
          activeWorkspace.id,
          editingDefinition.id,
          {
            name: formName.trim(),
            description: formDescription.trim() || undefined,
            linkedRepositoryId: formRepoId || null,
            targetEnvironmentId: formTargetEnvId || null,
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
          targetEnvironmentId: formTargetEnvId || null,
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

    const acquiresSource = queueingDef.stages.some((stage) => stage.metadata.operation === 'GITHUB_SOURCE_ACQUIRE');
    if (acquiresSource && runSha.trim() && !/^[a-f0-9]{40}$/i.test(runSha.trim())) {
      setQueueRunError('Enter a full 40-character commit SHA or leave it blank to resolve the selected ref.');
      return;
    }

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
    setExpandedStageId(null);
    setStageAttempts({});
    setStageLogs({});
    setStageExecError({});
    setRunEvents([]);
    setSelectedRunArtifacts(null);
    setEventsError(null);
    if (!activeWorkspace) return;
    setIsLoadingEvents(true);
    try {
      const [events, runArtifacts] = await Promise.all([
        fetchPipelineRunEvents(activeWorkspace.id, run.id),
        fetchPipelineRunArtifacts(activeWorkspace.id, run.id).catch(() => null),
      ]);
      setRunEvents(Array.isArray(events) ? events : []);
      if (runArtifacts && Array.isArray(runArtifacts.artifacts)) {
        setSelectedRunArtifacts(runArtifacts);
      }
    } catch (error) {
      setEventsError(
        error instanceof Error ? error.message : 'Unable to load lifecycle transition events.'
      );
    } finally {
      setIsLoadingEvents(false);
    }
  };

  const loadStageExecutionDetails = async (runId: string, stage: PipelineStageRun) => {
    if (!activeWorkspace) return;
    setStageExecLoading((prev) => ({ ...prev, [stage.id]: true }));
    setStageExecError((prev) => ({ ...prev, [stage.id]: null }));
    try {
      const [attemptsRes, logsRes] = await Promise.all([
        fetchStageExecutionAttempts(activeWorkspace.id, runId, stage.id),
        fetchStageExecutionLogs(activeWorkspace.id, runId, stage.id, { page: 0, size: 150 }),
      ]);
      setStageAttempts((prev) => ({
        ...prev,
        [stage.id]: Array.isArray(attemptsRes) ? attemptsRes : [],
      }));
      setStageLogs((prev) => ({
        ...prev,
        [stage.id]: Array.isArray(logsRes.items) ? logsRes.items : [],
      }));
    } catch (error) {
      setStageExecError((prev) => ({
        ...prev,
        [stage.id]:
          error instanceof Error
            ? error.message
            : 'Unable to load execution attempts and sanitized logs.',
      }));
    } finally {
      setStageExecLoading((prev) => ({ ...prev, [stage.id]: false }));
    }
  };

  const toggleStageExecutionDrawer = (runId: string, stage: PipelineStageRun) => {
    if (expandedStageId === stage.id) {
      setExpandedStageId(null);
      return;
    }
    setExpandedStageId(stage.id);
    void loadStageExecutionDetails(runId, stage);
  };

  const resolveRunStatusBadge = (
    run: PipelineRun
  ): { label: string; tone: BadgeTone; pulse?: boolean } => {
    if (run.status === 'RUNNING' && run.cancellationRequestedAt) {
      return {
        label: 'Cancelling — Awaiting worker termination',
        tone: 'degraded',
        pulse: true,
      };
    }
    if (run.status === 'QUEUED') {
      if (workerPool?.workerPlaneEnabled) {
        return {
          label: 'QUEUED — Awaiting available worker',
          tone: 'degraded',
          pulse: true,
        };
      }
      return RUN_STATUS_META.QUEUED;
    }
    return RUN_STATUS_META[run.status];
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
          {row.hasSecretBindings && (
            <span className="cf-secret-ref-pill" style={{ marginLeft: '6px' }}>
              <KeyRound size={11} aria-hidden="true" />
              <span>
                {row.targetEnvironmentName ? `${row.targetEnvironmentName} Secrets` : 'Secrets Bound'}
              </span>
            </span>
          )}
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
      render: (row) => {
        const blockedBySecretRbac = Boolean(row.hasSecretBindings && !isOwner);
        return (
          <div className="cf-inline-actions cf-inline-actions--end">
            <button
              type="button"
              className="btn btn-sm btn-primary"
              disabled={!canOperateRuns || !row.enabled || blockedBySecretRbac}
              title={
                blockedBySecretRbac
                  ? 'Only workspace OWNERs can queue runs for pipelines with bound encrypted secrets'
                  : !canOperateRuns
                    ? 'Only workspace OWNERs and DEVELOPERs can queue pipeline runs'
                    : !row.enabled
                      ? 'Enable this pipeline definition before queueing runs'
                      : `Queue a new run for ${row.name}`
              }
              onClick={() => openQueueRunModal(row)}
            >
              {blockedBySecretRbac ? <Lock size={12} aria-hidden="true" /> : <Play size={12} aria-hidden="true" />}
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
        );
      },
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
        const meta = resolveRunStatusBadge(row);
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

      {/* Phase 3.3 Isolated Worker Pool Status Card */}
      {workerPool && (
        <DashboardCard
          title="Isolated Worker Execution Plane"
          subtitle="Live persisted worker availability, active leases, and allowlisted Phase 3.3 operations"
          icon={<Cpu size={17} />}
          headerAction={
            <StatusBadge
              label={
                !workerPool.workerPlaneEnabled
                  ? 'Execution Plane Disabled'
                  : workerPool.availableWorkers > 0
                    ? `${workerPool.availableWorkers} Worker${workerPool.availableWorkers === 1 ? '' : 's'} Available`
                    : workerPool.busyWorkers > 0
                      ? `${workerPool.busyWorkers} Worker${workerPool.busyWorkers === 1 ? '' : 's'} Busy`
                      : 'No Active Workers'
              }
              tone={
                !workerPool.workerPlaneEnabled
                  ? 'neutral'
                  : workerPool.availableWorkers > 0
                    ? 'healthy'
                    : workerPool.busyWorkers > 0
                      ? 'info'
                      : 'degraded'
              }
              pulse={workerPool.availableWorkers > 0 || workerPool.busyWorkers > 0}
              size="sm"
            />
          }
        >
          <div className="cf-worker-pool-grid" data-testid="worker-pool-summary">
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Registered Workers</span>
              <span className="cf-worker-metric__value" data-testid="worker-registered-count">
                {workerPool.registeredWorkers}
              </span>
            </div>
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Available / Busy</span>
              <span className="cf-worker-metric__value" data-testid="worker-available-busy">
                {workerPool.availableWorkers} / {workerPool.busyWorkers}
              </span>
            </div>
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Offline Workers</span>
              <span className="cf-worker-metric__value">{workerPool.offlineWorkers}</span>
            </div>
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Active Leases</span>
              <span className="cf-worker-metric__value">{workerPool.activeAssignments}</span>
            </div>
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Workspace Running</span>
              <span className="cf-worker-metric__value">{workerPool.workspaceRunningStages}</span>
            </div>
            <div className="cf-worker-metric">
              <span className="cf-worker-metric__label">Failed Attempts</span>
              <span className="cf-worker-metric__value">{workerPool.workspaceFailedAttempts}</span>
            </div>
          </div>

          {!workerPool.workerPlaneEnabled ? (
            <p className="cf-muted" style={{ margin: '10px 0 0', fontSize: '12.5px' }}>
              Worker scheduling is disabled on this control-plane instance (default safe setting for
              Render staging). Enable <span className="mono">CLOUDFORGE_WORKERS_ENABLED=true</span>{' '}
              locally and run a trusted host-side worker agent to execute allowlisted operations:{' '}
              <span className="mono">{workerPool.supportedOperations.join(', ')}</span>.
            </p>
          ) : workerPool.registeredWorkers === 0 ? (
            <p className="cf-muted" style={{ margin: '10px 0 0', fontSize: '12.5px' }}>
              Worker control plane is enabled, but no trusted workers are currently registered.
              Queued allowlisted stages remain in <span className="mono">READY</span> until a worker
              connects.
            </p>
          ) : null}

          {workerPool.workers.length > 0 && (
            <div className="cf-worker-node-list" aria-label="Registered worker nodes">
              {workerPool.workers.map((node) => {
                const wMeta = WORKER_STATUS_META[node.status];
                return (
                  <div key={node.workerId} className="cf-worker-node-row">
                    <div className="cf-worker-node-row__id">
                      <span>{node.workerId}</span>
                      <StatusBadge
                        label={wMeta.label}
                        tone={wMeta.tone}
                        pulse={Boolean(wMeta.pulse)}
                        size="sm"
                      />
                      <span className="cf-stage-pill">{node.adapterType}</span>
                    </div>
                    <div className="cf-worker-node-row__meta">
                      <span>
                        Slots:{' '}
                        <strong className="mono">
                          {node.activeAssignments}/{node.maxConcurrency}
                        </strong>
                      </span>
                      <span>
                        Heartbeat:{' '}
                        <strong className="mono">{formatTimestamp(node.lastHeartbeatAt)}</strong>
                      </span>
                      <span className="mono">{node.supportedOperations.join(', ')}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </DashboardCard>
      )}

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

          <div className="cf-field">
            <label htmlFor="cf-orch-target-env" className="cf-field__label">
              Target Secret Environment (Optional, Owner Only)
            </label>
            <select
              id="cf-orch-target-env"
              className="cf-select"
              value={formTargetEnvId}
              onChange={(e) => {
                setFormTargetEnvId(e.target.value);
                setFormStages((prev) => prev.map((row) => ({ ...row, secretBindings: [] })));
              }}
            >
              <option value="">None (no encrypted environment secret bindings)</option>
              {projectEnvironments.map((env) => (
                <option key={env.id} value={env.id}>
                  {env.name} ({env.environmentType})
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
                          onChange={(e) => {
                            const nextType = e.target.value as PipelineStageType;
                            const compatible = ALLOWLISTED_OPS_BY_STAGE_TYPE[nextType] ?? [];
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              stageType: nextType,
                              allowedOperation: compatible.some(
                                (o) => o.value === row.allowedOperation
                              )
                                ? row.allowedOperation
                                : '',
                            }));
                          }}
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

                    <div className="cf-field">
                      <label
                        htmlFor={`stage-op-${stage.localId}`}
                        className="cf-field__label"
                      >
                        Allowlisted Worker Operation
                      </label>
                      {(ALLOWLISTED_OPS_BY_STAGE_TYPE[stage.stageType]?.length ?? 0) > 0 ? (
                        <select
                          id={`stage-op-${stage.localId}`}
                          className="cf-select mono"
                          value={stage.allowedOperation}
                          onChange={(e) =>
                            updateStageRow(stage.localId, (row) => ({
                              ...row,
                              allowedOperation: e.target.value,
                              secretBindings: stageSupportsSecretBindings(row.stageType, e.target.value)
                                ? row.secretBindings
                                : [],
                            }))
                          }
                        >
                          <option value="">Declarative only (not dispatched to workers)</option>
                          {(ALLOWLISTED_OPS_BY_STAGE_TYPE[stage.stageType] ?? []).map((op) => (
                            <option key={op.value} value={op.value}>
                              {op.label}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <div className="cf-stage-capability-note">
                          Declarative stage — isolated execution for {formatStageTypeLabel(stage.stageType)}{' '}
                          stages is planned for later phases (arbitrary builds/deployments disabled).
                        </div>
                      )}
                    </div>

                    {isOciStageOperation(stage.allowedOperation) && (
                      <div className="cf-field">
                        <span className="cf-field__label">
                          OCI Container Build &amp; Registry Configuration (Phase 3.5B)
                        </span>
                        <div className="cf-oci-config-grid">
                          <div className="cf-field">
                            <label htmlFor={`stage-oci-reg-${stage.localId}`} className="cf-field__label">
                              Registry Connection *
                            </label>
                            <select
                              id={`stage-oci-reg-${stage.localId}`}
                              className="cf-select"
                              value={stage.ociRegistryId ?? ''}
                              onChange={(e) =>
                                updateStageRow(stage.localId, (row) => ({
                                  ...row,
                                  ociRegistryId: e.target.value,
                                }))
                              }
                            >
                              <option value="">Select approved workspace registry...</option>
                              {ociRegistries
                                .filter((r) => r.status === 'ACTIVE')
                                .map((reg) => (
                                  <option key={reg.id} value={reg.id}>
                                    {reg.name} ({reg.registryHost}/{reg.allowedNamespace}/*)
                                  </option>
                                ))}
                            </select>
                          </div>

                          <div className="cf-field">
                            <label htmlFor={`stage-oci-image-${stage.localId}`} className="cf-field__label">
                              Target Image Repository *
                            </label>
                            <input
                              id={`stage-oci-image-${stage.localId}`}
                              type="text"
                              className="cf-input mono"
                              placeholder="namespace/app-name"
                              value={stage.ociTargetImageName ?? ''}
                              onChange={(e) =>
                                updateStageRow(stage.localId, (row) => ({
                                  ...row,
                                  ociTargetImageName: e.target.value,
                                }))
                              }
                            />
                          </div>

                          <div className="cf-field">
                            <label htmlFor={`stage-oci-tag-${stage.localId}`} className="cf-field__label">
                              Convenience Tag (Optional)
                            </label>
                            <input
                              id={`stage-oci-tag-${stage.localId}`}
                              type="text"
                              className="cf-input mono"
                              placeholder="v1 or leave blank for sha-<commit>"
                              value={stage.ociTargetTag ?? ''}
                              onChange={(e) =>
                                updateStageRow(stage.localId, (row) => ({
                                  ...row,
                                  ociTargetTag: e.target.value,
                                }))
                              }
                            />
                          </div>

                          {stage.allowedOperation === 'OCI_IMAGE_BUILD' && (
                            <>
                              <div className="cf-field">
                                <label htmlFor={`stage-oci-df-${stage.localId}`} className="cf-field__label">
                                  Dockerfile Path
                                </label>
                                <input
                                  id={`stage-oci-df-${stage.localId}`}
                                  type="text"
                                  className="cf-input mono"
                                  value={stage.ociDockerfilePath ?? 'Dockerfile'}
                                  onChange={(e) =>
                                    updateStageRow(stage.localId, (row) => ({
                                      ...row,
                                      ociDockerfilePath: e.target.value,
                                    }))
                                  }
                                />
                              </div>
                              <div className="cf-field">
                                <label htmlFor={`stage-oci-ctx-${stage.localId}`} className="cf-field__label">
                                  Build Context Path
                                </label>
                                <input
                                  id={`stage-oci-ctx-${stage.localId}`}
                                  type="text"
                                  className="cf-input mono"
                                  value={stage.ociContextPath ?? '.'}
                                  onChange={(e) =>
                                    updateStageRow(stage.localId, (row) => ({
                                      ...row,
                                      ociContextPath: e.target.value,
                                    }))
                                  }
                                />
                              </div>
                              <div className="cf-field">
                                <label htmlFor={`stage-oci-plat-${stage.localId}`} className="cf-field__label">
                                  Target Platform
                                </label>
                                <select
                                  id={`stage-oci-plat-${stage.localId}`}
                                  className="cf-select mono"
                                  value={stage.ociTargetPlatform ?? 'linux/amd64'}
                                  onChange={(e) =>
                                    updateStageRow(stage.localId, (row) => ({
                                      ...row,
                                      ociTargetPlatform: e.target.value,
                                    }))
                                  }
                                >
                                  <option value="linux/amd64">linux/amd64</option>
                                  <option value="linux/arm64">linux/arm64</option>
                                </select>
                              </div>
                            </>
                          )}
                        </div>
                      </div>
                    )}

                    <div className="cf-field">
                      <div className="cf-jobs-section__header">
                        <span className="cf-field__label">
                          Authorized Secret Bindings (Phase 3.4)
                        </span>
                        {stageSupportsSecretBindings(stage.stageType, stage.allowedOperation) &&
                          formTargetEnvId &&
                          availableSecrets.length > 0 && (
                            <button
                              type="button"
                              className="btn btn-sm"
                              onClick={() =>
                                updateStageRow(stage.localId, (row) => {
                                  const firstUnused =
                                    availableSecrets.find(
                                      (s) => !row.secretBindings.some((b) => b.secretId === s.id)
                                    ) ?? availableSecrets[0];
                                  return {
                                    ...row,
                                    secretBindings: [
                                      ...row.secretBindings,
                                      {
                                        secretId: firstUnused.id,
                                        deliveryAlias: firstUnused.name,
                                      },
                                    ],
                                  };
                                })
                              }
                            >
                              <KeyRound size={12} aria-hidden="true" />
                              <span>Bind Secret Reference</span>
                            </button>
                          )}
                      </div>

                      {!stageSupportsSecretBindings(stage.stageType, stage.allowedOperation) ? (
                        <div className="cf-stage-capability-note">
                          This stage does not accept secret bindings.
                        </div>
                      ) : !formTargetEnvId ? (
                        <div className="cf-stage-capability-note">
                          Select a Target Secret Environment above to authorize encrypted secret references for this stage.
                        </div>
                      ) : availableSecrets.length === 0 ? (
                        <div className="cf-stage-capability-note">
                          No active encrypted secrets exist in the selected environment yet.
                        </div>
                      ) : stage.secretBindings.length === 0 ? (
                        <div className="cf-stage-capability-note">
                          No secrets bound to this stage (zero secrets will be delivered to workers).
                        </div>
                      ) : (
                        <div className="cf-secret-binding-list">
                          {stage.secretBindings.map((binding, bIdx) => (
                            <div key={`${binding.secretId}-${bIdx}`} className="cf-secret-binding-row">
                              <select
                                className="cf-select mono"
                                aria-label={`Bound secret ${bIdx + 1}`}
                                value={binding.secretId}
                                onChange={(e) => {
                                  const chosen = availableSecrets.find((s) => s.id === e.target.value);
                                  updateStageRow(stage.localId, (row) => ({
                                    ...row,
                                    secretBindings: row.secretBindings.map((item, idx2) =>
                                      idx2 === bIdx
                                        ? {
                                            secretId: e.target.value,
                                            deliveryAlias: chosen ? chosen.name : item.deliveryAlias,
                                          }
                                        : item
                                    ),
                                  }));
                                }}
                              >
                                {availableSecrets.map((sec) => (
                                  <option key={sec.id} value={sec.id}>
                                    {sec.name} (v{sec.currentVersionNumber})
                                  </option>
                                ))}
                              </select>
                              <input
                                type="text"
                                className="cf-input mono"
                                placeholder="DELIVERY_ALIAS"
                                aria-label={`Delivery alias ${bIdx + 1}`}
                                value={binding.deliveryAlias}
                                onChange={(e) =>
                                  updateStageRow(stage.localId, (row) => ({
                                    ...row,
                                    secretBindings: row.secretBindings.map((item, idx2) =>
                                      idx2 === bIdx
                                        ? { ...item, deliveryAlias: e.target.value.toUpperCase() }
                                        : item
                                    ),
                                  }))
                                }
                              />
                              <button
                                type="button"
                                className="btn btn-sm btn-danger"
                                aria-label={`Remove secret binding ${bIdx + 1}`}
                                onClick={() =>
                                  updateStageRow(stage.localId, (row) => ({
                                    ...row,
                                    secretBindings: row.secretBindings.filter((_, idx2) => idx2 !== bIdx),
                                  }))
                                }
                              >
                                <Trash2 size={12} aria-hidden="true" />
                              </button>
                            </div>
                          ))}
                        </div>
                      )}
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

            {queueingDef.hasSecretBindings && (
              <div className="cf-notice cf-notice--info" role="status">
                <div className="cf-notice__icon" aria-hidden="true">
                  <ShieldCheck size={17} />
                </div>
                <div>
                  <p className="cf-notice__title">
                    Queue-time secret pinning ({queueingDef.targetEnvironmentName ?? 'Environment'})
                  </p>
                  <p className="cf-notice__text">
                    Queueing resolves active secret versions from {queueingDef.targetEnvironmentName ?? 'the target environment'}{' '}
                    into immutable pinned references without decrypting values. Worker delivery remains
                    gated by active leases and revocation state.
                  </p>
                </div>
              </div>
            )}

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
                Target Branch or GitHub Tag (Optional)
              </label>
              <input
                id="cf-run-branch"
                type="text"
                className="cf-input mono"
                placeholder="Project default branch or refs/tags/<tag>"
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
                placeholder={queueingDef.stages.some((stage) => stage.metadata.operation === 'GITHUB_SOURCE_ACQUIRE')
                  ? 'Full commit SHA or resolve automatically'
                  : '7-40 hex characters (optional)'}
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
                label={resolveRunStatusBadge(selectedRun).label}
                tone={resolveRunStatusBadge(selectedRun).tone}
                pulse={Boolean(resolveRunStatusBadge(selectedRun).pulse)}
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
              {selectedRun.targetEnvironmentName && (
                <div className="cf-kv-item">
                  <dt>Target Environment</dt>
                  <dd className="mono">
                    {selectedRun.targetEnvironmentName} ({selectedRun.targetEnvironmentType})
                  </dd>
                </div>
              )}
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
                  const isExpanded = expandedStageId === stage.id;
                  const attempts = stageAttempts[stage.id] ?? [];
                  const logs = stageLogs[stage.id] ?? [];
                  const execLoading = Boolean(stageExecLoading[stage.id]);
                  const execError = stageExecError[stage.id];
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
                          {stage.allowedOperation && (
                            <span className="cf-stage-pill">{stage.allowedOperation}</span>
                          )}
                          {(stage.secretBindings?.length ?? 0) > 0 && (
                            <span className="cf-secret-ref-pill">
                              <KeyRound size={11} aria-hidden="true" />
                              <span>
                                {stage.secretBindings!.map((b) =>
                                  b.pinnedVersionNumber
                                    ? `${b.deliveryAlias} (v${b.pinnedVersionNumber})`
                                    : b.deliveryAlias
                                ).join(', ')}
                              </span>
                            </span>
                          )}
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
                          Attempts:{' '}
                          <strong className="mono">
                            {stage.attemptNumber} / {stage.maxRetries + 1}
                          </strong>
                        </span>
                        <span>
                          Timeout: <strong className="mono">{stage.timeoutSeconds}s</strong>
                        </span>
                        {stage.assignedWorkerId && (
                          <span>
                            Worker: <strong className="mono">{stage.assignedWorkerId}</strong>
                          </span>
                        )}
                        {stage.nextRetryAt && (
                          <span>
                            Next retry:{' '}
                            <strong className="mono">{formatTimestamp(stage.nextRetryAt)}</strong>
                          </span>
                        )}
                      </div>
                      {stage.statusReason && (
                        <div className="cf-muted" style={{ fontSize: '12px' }}>
                          {stage.statusReason}
                        </div>
                      )}
                      {stage.executionSupported === false && stage.capabilityMessage && (
                        <div className="cf-stage-capability-note" role="note">
                          <strong>Capability boundary:</strong> {stage.capabilityMessage}
                        </div>
                      )}

                      <div className="cf-inline-actions">
                        <button
                          type="button"
                          className="btn btn-sm"
                          aria-expanded={isExpanded}
                          onClick={() => toggleStageExecutionDrawer(selectedRun.id, stage)}
                        >
                          <Terminal size={12} aria-hidden="true" />
                          <span>
                            {isExpanded ? 'Hide Attempts & Logs' : 'View Attempts & Logs'}
                          </span>
                          {isExpanded ? (
                            <ChevronUp size={12} aria-hidden="true" />
                          ) : (
                            <ChevronDown size={12} aria-hidden="true" />
                          )}
                        </button>
                        {isExpanded && (
                          <button
                            type="button"
                            className="btn btn-sm"
                            disabled={execLoading}
                            onClick={() => void loadStageExecutionDetails(selectedRun.id, stage)}
                          >
                            <RefreshCw
                              size={12}
                              className={execLoading ? 'cf-spin' : ''}
                              aria-hidden="true"
                            />
                            <span>Refresh Logs</span>
                          </button>
                        )}
                      </div>

                      {isExpanded && (
                        <div
                          className="cf-stage-execution-drawer"
                          data-testid={`stage-exec-drawer-${stage.stageKey}`}
                        >
                          {execLoading ? (
                            <LoadingState
                              title="Loading execution attempts and logs"
                              description="Fetching persisted worker leases and sanitized container output..."
                            />
                          ) : execError ? (
                            <ErrorState
                              title="Unable to load execution details"
                              message={execError}
                              onRetry={() => void loadStageExecutionDetails(selectedRun.id, stage)}
                            />
                          ) : (
                            <>
                              <div>
                                <strong style={{ fontSize: '12px' }}>
                                  Execution Attempts ({attempts.length})
                                </strong>
                                {attempts.length === 0 ? (
                                  <p className="cf-muted" style={{ margin: '4px 0 0', fontSize: '12px' }}>
                                    No worker execution attempts recorded yet for this stage.
                                  </p>
                                ) : (
                                  <div className="cf-attempt-list" style={{ marginTop: '6px' }}>
                                    {attempts.map((att) => {
                                      const aMeta = ATTEMPT_STATUS_META[att.status];
                                      return (
                                        <div key={att.id} className="cf-attempt-row">
                                          <div className="cf-attempt-row__top">
                                            <span className="mono">
                                              Attempt #{att.attemptNumber} · worker{' '}
                                              <strong>{att.workerId}</strong> · fence{' '}
                                              {att.fencingGeneration}
                                            </span>
                                            <StatusBadge
                                              label={aMeta.label}
                                              tone={aMeta.tone}
                                              size="sm"
                                            />
                                          </div>
                                          <div className="cf-orch-stage-card__meta">
                                            <span>
                                              Operation:{' '}
                                              <strong className="mono">{att.allowedOperation}</strong>
                                            </span>
                                            {att.exitCode !== null && (
                                              <span>
                                                Exit code:{' '}
                                                <strong className="mono">{att.exitCode}</strong>
                                              </span>
                                            )}
                                            {att.failureCategory !== 'NONE' && (
                                              <span>
                                                Category:{' '}
                                                <strong className="mono">
                                                  {att.failureCategory}
                                                </strong>
                                              </span>
                                            )}
                                            <span>
                                              Started:{' '}
                                              <strong className="mono">
                                                {formatTimestamp(att.startedAt)}
                                              </strong>
                                            </span>
                                          </div>
                                          {att.summary && (
                                            <div className="cf-muted">{att.summary}</div>
                                          )}
                                        </div>
                                      );
                                    })}
                                  </div>
                                )}
                              </div>

                              <div>
                                <strong style={{ fontSize: '12px' }}>
                                  Sanitized Execution Logs ({logs.length})
                                </strong>
                                {logs.length === 0 ? (
                                  <p className="cf-muted" style={{ margin: '4px 0 0', fontSize: '12px' }}>
                                    No execution logs captured yet.
                                  </p>
                                ) : (
                                  <div
                                    className="cf-log-console"
                                    role="log"
                                    aria-label={`Execution logs for stage ${stage.stageKey}`}
                                    style={{ marginTop: '6px' }}
                                  >
                                    <ol className="cf-log-list">
                                      {logs.map((entry) => (
                                        <li key={entry.id} className="cf-log-line">
                                          <span className="cf-log-line__seq">
                                            {entry.sequenceNumber}
                                          </span>
                                          <span
                                            className={`cf-log-line__stream cf-log-line__stream--${entry.streamType}`}
                                          >
                                            {entry.streamType}
                                          </span>
                                          <span className="cf-log-line__msg">{entry.message}</span>
                                        </li>
                                      ))}
                                    </ol>
                                  </div>
                                )}
                              </div>
                            </>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            {selectedRunArtifacts &&
              (selectedRunArtifacts.artifacts.length > 0 ||
                selectedRunArtifacts.builds.length > 0) && (
                <div className="cf-jobs-section">
                  <h3 className="cf-jobs-section__title">
                    <PackageCheck
                      size={14}
                      aria-hidden="true"
                      style={{ verticalAlign: '-2px', marginRight: '6px' }}
                    />
                    Verified OCI Artifacts &amp; Builds (
                    {selectedRunArtifacts.artifacts.length} artifacts ·{' '}
                    {selectedRunArtifacts.builds.length} builds)
                  </h3>
                  {selectedRunArtifacts.artifacts.length > 0 && (
                    <div className="cf-oci-artifact-cards">
                      {selectedRunArtifacts.artifacts.map((art) => (
                        <div key={art.id} className="cf-oci-artifact-card">
                          <div className="cf-oci-artifact-card__head">
                            <span className="cf-oci-artifact-card__repo">
                              {art.registryHost}/{art.imageRepository}
                              {art.imageTag ? `:${art.imageTag}` : ''}
                            </span>
                            <StatusBadge
                              label={
                                art.verificationStatus === 'VERIFIED'
                                  ? 'Digest Verified'
                                  : art.verificationStatus
                              }
                              tone={art.verificationStatus === 'VERIFIED' ? 'healthy' : 'error'}
                              size="sm"
                            />
                          </div>
                          <div className="mono" style={{ fontSize: '11.5px', wordBreak: 'break-all' }}>
                            {art.immutableReference}
                          </div>
                          <div className="cf-oci-artifact-card__meta">
                            <span>Platform: {art.platform}</span>
                            <span>Commit: {art.commitSha.slice(0, 10)}</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

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
