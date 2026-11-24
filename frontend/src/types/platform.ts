export type WorkspaceRole = 'OWNER' | 'DEVELOPER' | 'VIEWER';
export type ProjectStatus = 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type EnvironmentType = 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';
export type InstallationStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
export type RepositoryConnectionStatus = 'CONNECTED' | 'SUSPENDED' | 'DISCONNECTED';
export type WorkflowRunNormalizedStatus =
  | 'QUEUED'
  | 'IN_PROGRESS'
  | 'SUCCESS'
  | 'FAILURE'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'SKIPPED'
  | 'UNKNOWN';

export interface ApiErrorPayload {
  timestamp?: string;
  status: number;
  error: string;
  message: string;
  path?: string;
  requestId?: string | null;
  fieldErrors?: Record<string, string>;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AuthConfig {
  githubOAuthConfigured: boolean;
  githubAppConfigured: boolean;
  githubAppSlug: string | null;
  loginUrl: string;
}

export interface UserProfile {
  id: string;
  githubUserId: number;
  username: string;
  displayName: string | null;
  email: string | null;
  avatarUrl: string | null;
  profileUrl: string | null;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  personal: boolean;
  role: WorkspaceRole;
  memberCount: number;
  projectCount: number;
  connectedRepositoryCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AuthMePayload {
  user: UserProfile;
  workspaces: WorkspaceSummary[];
}

export interface WorkspaceMember {
  id: string;
  userId: string;
  username: string;
  displayName: string | null;
  email: string | null;
  avatarUrl: string | null;
  role: WorkspaceRole;
  joinedAt: string;
}

export interface GitHubInstallation {
  id: string;
  workspaceId: string;
  githubInstallationId: number;
  accountLogin: string;
  accountType: string;
  accountAvatarUrl: string | null;
  repositorySelection: string;
  status: InstallationStatus;
  lastSyncedAt: string | null;
  createdAt: string;
}

export interface GitHubInstallationsResponse {
  githubAppConfigured: boolean;
  githubAppSlug: string | null;
  installations: GitHubInstallation[];
}

export interface GitHubInstallUrlResponse {
  installUrl: string;
  expiresAt: string;
}

export interface GitHubSyncResponse {
  installationId: string;
  installationStatus: InstallationStatus;
  syncedCount: number;
  disconnectedCount: number;
  syncedAt: string;
  message: string;
}

export interface LinkedRepository {
  id: string;
  workspaceId: string;
  installationId: string;
  githubInstallationId: number;
  githubRepoId: number;
  ownerLogin: string;
  name: string;
  fullName: string;
  defaultBranch: string;
  visibility: string;
  htmlUrl: string | null;
  description: string | null;
  archived: boolean;
  connectionStatus: RepositoryConnectionStatus;
  githubUpdatedAt: string | null;
  lastSyncedAt: string;
}

export interface ProjectEnvironment {
  id: string;
  projectId: string;
  name: string;
  environmentType: EnvironmentType;
  branchOverride: string | null;
  configVariables: Record<string, string>;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectSummary {
  id: string;
  workspaceId: string;
  name: string;
  slug: string;
  description: string | null;
  linkedRepository: LinkedRepository | null;
  defaultBranch: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectDetail extends ProjectSummary {
  workspaceName: string;
  createdByUsername: string | null;
  environments: ProjectEnvironment[];
}

export interface AuditEvent {
  id: string;
  workspaceId: string | null;
  actorUsername: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  summary: string;
  metadata: Record<string, unknown>;
  requestId: string | null;
  createdAt: string;
}

export interface CreateProjectInput {
  name: string;
  description?: string;
  linkedRepositoryId?: string | null;
  defaultBranch?: string;
  status?: ProjectStatus;
}

export interface UpdateProjectInput {
  name: string;
  description?: string;
  linkedRepositoryId?: string | null;
  defaultBranch: string;
  status?: ProjectStatus;
}

export interface UpdateEnvironmentInput {
  name: string;
  branchOverride?: string | null;
  configVariables: Record<string, string>;
  notes?: string | null;
}

// --- Phase 3.1: Read-Only GitHub Actions Pipelines ---

export interface PipelineRepositorySummary {
  repositoryId: string;
  fullName: string;
  name: string;
  ownerLogin: string;
  defaultBranch: string;
  visibility: string;
  connectionStatus: RepositoryConnectionStatus;
  installationStatus: InstallationStatus;
  htmlUrl: string | null;
  workflowCount: number;
  runCount: number;
  activeRunCount: number;
  failedRunCount: number;
  lastWorkflowSyncAt: string | null;
  latestRunStatus: WorkflowRunNormalizedStatus | null;
  latestRunAt: string | null;
}

export interface PipelineOverview {
  workspaceId: string;
  githubAppConfigured: boolean;
  connectedRepositoryCount: number;
  repositoriesWithWorkflowsCount: number;
  workflowCount: number;
  recentRunCount: number;
  activeRunCount: number;
  failedRunCount: number;
  succeededRunCount: number;
  lastSyncedAt: string | null;
  repositories: PipelineRepositorySummary[];
}

export interface GitHubWorkflow {
  id: string;
  workspaceId: string;
  repositoryId: string;
  repositoryFullName: string;
  githubWorkflowId: number;
  name: string;
  path: string;
  state: string;
  htmlUrl: string | null;
  githubCreatedAt: string | null;
  githubUpdatedAt: string | null;
  lastSyncedAt: string;
  latestRunId: string | null;
  latestRunNumber: number | null;
  latestRunStatus: WorkflowRunNormalizedStatus | null;
  latestRunGithubStatus: string | null;
  latestRunConclusion: string | null;
  latestRunAt: string | null;
}

export interface GitHubWorkflowRun {
  id: string;
  workspaceId: string;
  repositoryId: string;
  repositoryFullName: string;
  workflowId: string | null;
  githubRunId: number;
  githubWorkflowId: number;
  runNumber: number;
  runAttempt: number;
  workflowName: string;
  displayTitle: string | null;
  event: string;
  headBranch: string | null;
  headSha: string | null;
  githubStatus: string;
  githubConclusion: string | null;
  normalizedStatus: WorkflowRunNormalizedStatus;
  actorLogin: string | null;
  actorAvatarUrl: string | null;
  htmlUrl: string | null;
  githubCreatedAt: string | null;
  githubUpdatedAt: string | null;
  runStartedAt: string | null;
  durationSeconds: number | null;
  lastSyncedAt: string;
}

export interface GitHubWorkflowStep {
  number: number;
  name: string;
  githubStatus: string;
  githubConclusion: string | null;
  normalizedStatus: WorkflowRunNormalizedStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationSeconds: number | null;
}

export interface GitHubWorkflowJob {
  githubJobId: number;
  githubRunId: number;
  runAttempt: number;
  name: string;
  githubStatus: string;
  githubConclusion: string | null;
  normalizedStatus: WorkflowRunNormalizedStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationSeconds: number | null;
  htmlUrl: string | null;
  runnerName: string | null;
  steps: GitHubWorkflowStep[];
}

export interface WorkflowRunJobsResponse {
  runId: string;
  githubRunId: number;
  repositoryId: string;
  repositoryFullName: string;
  fetchedAt: string;
  jobs: GitHubWorkflowJob[];
}

export interface GitHubActionsSyncResponse {
  repositoryId: string;
  repositoryFullName: string;
  workflowsSynced: number;
  runsSynced: number;
  runsCreated: number;
  runsUpdated: number;
  syncedAt: string;
  message: string;
}

// --- Phase 3.2: CloudForge Pipeline Orchestration Engine ---

export type PipelineStageType =
  | 'SOURCE_PREP'
  | 'BUILD'
  | 'TEST'
  | 'SECURITY_SCAN'
  | 'CONTAINER_PACKAGE'
  | 'DEPLOYMENT';

export type PipelineRunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type PipelineStageStatus =
  | 'PENDING'
  | 'READY'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELLED';

export type PipelineTriggerType = 'MANUAL' | 'API' | 'SCHEDULE' | 'WEBHOOK';

export interface StageSecretBindingInput {
  secretId: string;
  deliveryAlias: string;
}

export interface StageSecretBinding {
  secretId: string;
  secretName: string;
  deliveryAlias: string;
  environmentId: string;
  pinnedVersionNumber?: number | null;
}

export interface StageDefinitionInput {
  stageKey: string;
  displayName: string;
  stageType: PipelineStageType;
  dependsOn: string[];
  timeoutSeconds?: number;
  maxRetries?: number;
  allowFailure?: boolean;
  metadata?: Record<string, string>;
  secretBindings?: StageSecretBindingInput[];
}

export interface CreatePipelineDefinitionInput {
  projectId: string;
  name: string;
  description?: string;
  linkedRepositoryId?: string | null;
  targetEnvironmentId?: string | null;
  enabled?: boolean;
  stages: StageDefinitionInput[];
}

export interface UpdatePipelineDefinitionInput {
  name: string;
  description?: string;
  linkedRepositoryId?: string | null;
  targetEnvironmentId?: string | null;
  enabled?: boolean;
  expectedRowVersion?: number | null;
  stages: StageDefinitionInput[];
}

export interface PipelineStageDefinition {
  id: string;
  stageKey: string;
  displayName: string;
  stageType: PipelineStageType;
  executionOrder: number;
  dependsOn: string[];
  timeoutSeconds: number;
  maxRetries: number;
  allowFailure: boolean;
  metadata: Record<string, string>;
  secretBindings?: StageSecretBinding[];
}

export interface PipelineDefinition {
  id: string;
  workspaceId: string;
  projectId: string;
  projectName: string;
  linkedRepositoryId: string | null;
  repositoryFullName: string | null;
  targetEnvironmentId?: string | null;
  targetEnvironmentName?: string | null;
  targetEnvironmentType?: EnvironmentType | null;
  hasSecretBindings?: boolean;
  name: string;
  slug: string;
  description: string | null;
  enabled: boolean;
  definitionVersion: number;
  rowVersion: number | null;
  createdByUsername: string | null;
  updatedByUsername: string | null;
  stages: PipelineStageDefinition[];
  createdAt: string;
  updatedAt: string;
}

export interface CreatePipelineRunInput {
  definitionId: string;
  targetBranch?: string | null;
  commitSha?: string | null;
  triggerType?: PipelineTriggerType;
  idempotencyKey?: string | null;
}

export interface PipelineStageRun {
  id: string;
  stageKey: string;
  displayName: string;
  stageType: PipelineStageType;
  executionOrder: number;
  dependsOn: string[];
  timeoutSeconds: number;
  maxRetries: number;
  attemptNumber: number;
  allowFailure: boolean;
  status: PipelineStageStatus;
  statusReason: string | null;
  allowedOperation?: string | null;
  executionSupported?: boolean;
  capabilityMessage?: string | null;
  hasSecretBindings?: boolean;
  secretBindings?: StageSecretBinding[];
  assignedWorkerId?: string | null;
  leaseExpiresAt?: string | null;
  nextRetryAt?: string | null;
  cancellationRequestedAt?: string | null;
  readyAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PipelineRun {
  id: string;
  workspaceId: string;
  projectId: string;
  projectName: string;
  definitionId: string | null;
  definitionName: string;
  definitionVersion: number;
  linkedRepositoryId: string | null;
  repositoryFullName: string | null;
  targetEnvironmentId?: string | null;
  targetEnvironmentName?: string | null;
  targetEnvironmentType?: EnvironmentType | null;
  hasSecretBindings?: boolean;
  runNumber: number;
  triggerType: PipelineTriggerType;
  targetBranch: string | null;
  commitSha: string | null;
  status: PipelineRunStatus;
  statusReason: string | null;
  requestedByUsername: string | null;
  cancelledByUsername: string | null;
  queuedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancellationRequestedAt?: string | null;
  rowVersion: number | null;
  stages: PipelineStageRun[];
  createdAt: string;
  updatedAt: string;
}

export interface PipelineRunEvent {
  id: string;
  pipelineRunId: string;
  stageRunId: string | null;
  stageKey: string | null;
  eventType: string;
  fromStatus: string | null;
  toStatus: string;
  actorUsername: string | null;
  message: string;
  metadata: Record<string, unknown>;
  occurredAt: string;
}

// --- Phase 3.3: Isolated Build Workers & Secure Execution ---

export type AllowedWorkerOperation =
  | 'ENV_VALIDATION'
  | 'CONTROLLED_SMOKE_TEST'
  | 'WORKER_DIAGNOSTICS'
  | 'GITHUB_SOURCE_ACQUIRE'
  | 'OCI_IMAGE_BUILD'
  | 'OCI_IMAGE_PUBLISH';

export type WorkerStatus = 'AVAILABLE' | 'BUSY' | 'DRAINING' | 'OFFLINE';

export type WorkerAdapterType =
  | 'DOCKER_ISOLATED'
  | 'GITHUB_SOURCE'
  | 'OCI_BUILDKIT'
  | 'MOCK_TEST';

export type AttemptStatus =
  | 'CLAIMED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED'
  | 'INTERRUPTED'
  | 'EXPIRED';

export type AttemptFailureCategory =
  | 'NONE'
  | 'INFRASTRUCTURE_FAILURE'
  | 'WORKER_CRASH'
  | 'LEASE_EXPIRED'
  | 'EXECUTION_TIMEOUT'
  | 'OPERATION_FAILURE'
  | 'CANCELLED'
  | 'UNSUPPORTED_OPERATION'
  | 'DOCKERFILE_POLICY_VIOLATION'
  | 'SOURCE_CONTEXT_INVALID'
  | 'BUILD_FAILED'
  | 'REGISTRY_AUTH_FAILED'
  | 'REGISTRY_UNAVAILABLE'
  | 'DIGEST_VERIFICATION_FAILED';

export type LogStreamType = 'STDOUT' | 'STDERR' | 'SYSTEM';

export interface WorkerNodeSummary {
  workerId: string;
  displayName: string;
  adapterType: WorkerAdapterType;
  status: WorkerStatus;
  supportedOperations: string[];
  maxConcurrency: number;
  activeAssignments: number;
  hostLabel: string | null;
  agentVersion: string | null;
  registeredAt: string;
  lastHeartbeatAt: string;
  offlineAt: string | null;
}

export interface WorkerPoolStatus {
  workerPlaneEnabled: boolean;
  schedulerEnabled: boolean;
  registeredWorkers: number;
  availableWorkers: number;
  busyWorkers: number;
  drainingWorkers: number;
  offlineWorkers: number;
  activeAssignments: number;
  workspaceReadyStages: number;
  workspaceRunningStages: number;
  workspaceFailedAttempts: number;
  supportedOperations: string[];
  workers: WorkerNodeSummary[];
  sampledAt: string;
}

export interface StageExecutionAttempt {
  id: string;
  stageRunId: string;
  workerId: string;
  attemptNumber: number;
  fencingGeneration: number;
  allowedOperation: string;
  status: AttemptStatus;
  failureCategory: AttemptFailureCategory;
  exitCode: number | null;
  summary: string | null;
  resourceUsage: Record<string, unknown>;
  leaseStartedAt: string;
  leaseExpiresAt: string;
  lastHeartbeatAt: string;
  startedAt: string | null;
  completedAt: string | null;
  cancellationRequestedAt: string | null;
  terminationConfirmed: boolean;
  recoveredAt: string | null;
}

export interface StageExecutionLog {
  id: string;
  attemptId: string;
  attemptNumber: number;
  workerId: string;
  sequenceNumber: number;
  streamType: LogStreamType;
  message: string;
  truncated: boolean;
  occurredAt: string;
}

// --- Phase 3.4: Encrypted Environment Secrets & Pipeline Bindings ---

export type SecretLifecycleStatus = 'ACTIVE' | 'REVOKED' | 'DELETED';

export type SecretVersionStatus = 'ACTIVE' | 'REVOKED';

export interface SecretSubsystemStatus {
  enabled: boolean;
  available: boolean;
  providerType: string;
  activeKeyId: string | null;
  workerDeliveryEnabled: boolean;
  allowInsecureLocalDelivery: boolean;
  operatorRotationEnabled: boolean;
  maxSecretValueBytes: number;
  maxSecretsPerEnvironment: number;
  maxBindingsPerPipeline: number;
  statusDiagnostic: string;
}

export interface EnvironmentSecretSummary {
  id: string;
  workspaceId: string;
  projectId: string;
  environmentId: string;
  environmentName: string;
  environmentType: EnvironmentType;
  name: string;
  description: string | null;
  status: SecretLifecycleStatus;
  currentVersionNumber: number;
  currentVersionId: string | null;
  currentKekId: string | null;
  encryptionAlgorithm: string;
  encryptionFormatVersion: number;
  createdByUsername: string | null;
  updatedByUsername: string | null;
  createdAt: string;
  updatedAt: string;
  lastRotatedAt: string | null;
  revokedAt: string | null;
  revokedByUsername: string | null;
}

export interface EnvironmentSecretVersion {
  id: string;
  secretId: string;
  versionNumber: number;
  status: SecretVersionStatus;
  kekId: string;
  encryptionAlgorithm: string;
  encryptionFormatVersion: number;
  createdByUsername: string | null;
  createdAt: string;
  revokedAt: string | null;
  revokedByUsername: string | null;
  current: boolean;
}

export interface EnvironmentSecretDetail {
  secret: EnvironmentSecretSummary;
  versions: EnvironmentSecretVersion[];
}

export interface CreateEnvironmentSecretInput {
  name: string;
  value: string;
  description?: string;
}

export interface RotateEnvironmentSecretInput {
  value: string;
  description?: string;
}

export interface RevokeEnvironmentSecretInput {
  versionNumber?: number;
  reason?: string;
}

// --- Phase 3.5B: OCI Container Builds, Registry Connections & Immutable Artifacts ---

export type OciRegistryType = 'OCI_GENERIC' | 'GHCR' | 'LOCAL_DEV';
export type OciRegistryStatus = 'ACTIVE' | 'DISABLED';
export type OciBuildMode = 'ROOTLESS_BUILDKIT' | 'LOCAL_TRUSTED_BUILDKIT';
export type OciBuildStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED';
export type OciArtifactVerificationStatus =
  | 'PENDING'
  | 'VERIFIED'
  | 'VERIFICATION_FAILED';
export type OciPublishAttemptStatus =
  | 'STARTED'
  | 'PUSHED'
  | 'VERIFIED'
  | 'FAILED'
  | 'CANCELLED'
  | 'RECONCILIATION_REQUIRED';

export interface OciSubsystemStatus {
  ociEnabled: boolean;
  allowLocalDevRegistry: boolean;
  allowedPlatforms: string[];
  allowedRegistryHosts: string[];
  activeRegistryConnections: number;
  totalBuilds: number;
  succeededBuilds: number;
  verifiedArtifacts: number;
  activeBuildWorkers: number;
}

export interface OciRegistryConnection {
  id: string;
  workspaceId: string;
  name: string;
  slug: string;
  registryHost: string;
  scheme: 'https' | 'http';
  registryType: OciRegistryType;
  allowedNamespace: string;
  credentialProjectId: string | null;
  credentialProjectName: string | null;
  credentialEnvironmentId: string | null;
  credentialEnvironmentName: string | null;
  usernameSecretId: string | null;
  usernameSecretName: string | null;
  passwordSecretId: string | null;
  passwordSecretName: string | null;
  credentialsConfigured: boolean;
  allowInsecureLocal: boolean;
  status: OciRegistryStatus;
  description: string | null;
  createdByUsername: string | null;
  updatedByUsername: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRegistryConnectionInput {
  name: string;
  registryHost: string;
  scheme?: 'https' | 'http';
  registryType: OciRegistryType;
  allowedNamespace: string;
  credentialProjectId?: string | null;
  credentialEnvironmentId?: string | null;
  usernameSecretId?: string | null;
  passwordSecretId?: string | null;
  allowInsecureLocal?: boolean;
  description?: string;
}

export interface OciBuildRecord {
  id: string;
  workspaceId: string;
  projectId: string;
  projectName: string;
  pipelineRunId: string;
  runNumber: number | null;
  stageRunId: string;
  attemptId: string;
  githubRepositoryId: number;
  repositoryFullName: string;
  commitSha: string;
  sourceChecksumSha256: string | null;
  registryConnectionId: string | null;
  registryHost: string | null;
  dockerfilePath: string;
  buildContextPath: string;
  targetImageName: string;
  targetTag: string | null;
  targetPlatform: string;
  buildMode: OciBuildMode;
  configChecksumSha256: string;
  status: OciBuildStatus;
  builtManifestDigest: string | null;
  imageMediaType: string | null;
  imageSizeBytes: number | null;
  workerId: string;
  failureCategory: string | null;
  errorSummary: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface OciImageArtifact {
  id: string;
  workspaceId: string;
  projectId: string;
  projectName: string;
  pipelineRunId: string;
  runNumber: number | null;
  stageRunId: string;
  attemptId: string;
  buildRecordId: string | null;
  registryConnectionId: string | null;
  githubRepositoryId: number;
  repositoryFullName: string;
  commitSha: string;
  registryHost: string;
  imageRepository: string;
  imageTag: string | null;
  manifestDigest: string;
  immutableReference: string;
  imageMediaType: string;
  platform: string;
  imageSizeBytes: number | null;
  verificationStatus: OciArtifactVerificationStatus;
  verifiedAt: string | null;
  publishedAt: string;
  retentionPolicy: 'RETAIN_METADATA' | 'REFERENCED_PROTECTED';
  errorSummary: string | null;
  createdAt: string;
}

export interface OciPublishAttempt {
  id: string;
  workspaceId: string;
  projectId: string;
  pipelineRunId: string;
  stageRunId: string;
  attemptId: string;
  buildRecordId: string | null;
  registryConnectionId: string | null;
  artifactId: string | null;
  workerId: string;
  registryHost: string;
  imageRepository: string;
  imageTag: string | null;
  expectedDigest: string | null;
  reportedDigest: string | null;
  verifiedDigest: string | null;
  status: OciPublishAttemptStatus;
  failureCategory: string | null;
  errorSummary: string | null;
  startedAt: string;
  completedAt: string | null;
}

export interface OciArtifactEvent {
  id: string;
  pipelineRunId: string;
  buildRecordId: string | null;
  artifactId: string | null;
  publishAttemptId: string | null;
  eventType: string;
  actorLabel: string;
  message: string;
  metadata: Record<string, unknown>;
  occurredAt: string;
}

export interface OciImageArtifactDetail {
  artifact: OciImageArtifact;
  buildRecord: OciBuildRecord | null;
  publishAttempts: OciPublishAttempt[];
  events: OciArtifactEvent[];
}

export interface PipelineRunArtifacts {
  pipelineRunId: string;
  builds: OciBuildRecord[];
  publishAttempts: OciPublishAttempt[];
  artifacts: OciImageArtifact[];
  events: OciArtifactEvent[];
}
