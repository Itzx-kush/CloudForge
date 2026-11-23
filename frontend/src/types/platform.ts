export type WorkspaceRole = 'OWNER' | 'DEVELOPER' | 'VIEWER';
export type ProjectStatus = 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type EnvironmentType = 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';
export type InstallationStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
export type RepositoryConnectionStatus = 'CONNECTED' | 'SUSPENDED' | 'DISCONNECTED';

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
