package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.GitHubActionsSyncResponse;
import com.cloudforge.backend.dto.GitHubWorkflowJobDto;
import com.cloudforge.backend.dto.GitHubWorkflowStepDto;
import com.cloudforge.backend.dto.WorkflowRunJobsResponse;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.GitHubWorkflowEntity;
import com.cloudforge.backend.entity.GitHubWorkflowRunEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.github.GitHubApiClient;
import com.cloudforge.backend.github.GitHubAppTokenService;
import com.cloudforge.backend.github.GitHubInstallationRevokedException;
import com.cloudforge.backend.github.GitHubInstallationSuspendedException;
import com.cloudforge.backend.github.GitHubRepositoryNotFoundException;
import com.cloudforge.backend.github.GitHubWorkflowJobMetadata;
import com.cloudforge.backend.github.GitHubWorkflowMetadata;
import com.cloudforge.backend.github.GitHubWorkflowRunMetadata;
import com.cloudforge.backend.github.GitHubWorkflowStepMetadata;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRunRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates read-only GitHub Actions workflow, run, and job synchronization.
 *
 * <p>Deliberately avoids holding database transactions open across external GitHub
 * HTTP calls by splitting preparation, external API retrieval, and idempotent
 * persistence into separate short transactions.</p>
 */
@Service
public class GitHubActionsSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubActionsSyncService.class);

    private final AppProperties appProperties;
    private final WorkspaceAuthorizationService authorizationService;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final GitHubWorkflowRepository workflowRepository;
    private final GitHubWorkflowRunRepository workflowRunRepository;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final GitHubApiClient gitHubApiClient;
    private final GitHubAppTokenService tokenService;
    private final AuditService auditService;
    private final TransactionTemplate writeTransactionTemplate;
    private final TransactionTemplate readTransactionTemplate;
    private final Set<UUID> activeRepositorySyncs = ConcurrentHashMap.newKeySet();

    public GitHubActionsSyncService(
            AppProperties appProperties,
            WorkspaceAuthorizationService authorizationService,
            LinkedRepositoryRepository linkedRepositoryRepository,
            GitHubInstallationRepository installationRepository,
            GitHubWorkflowRepository workflowRepository,
            GitHubWorkflowRunRepository workflowRunRepository,
            GitHubIntegrationService gitHubIntegrationService,
            GitHubApiClient gitHubApiClient,
            GitHubAppTokenService tokenService,
            AuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.appProperties = appProperties;
        this.authorizationService = authorizationService;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.installationRepository = installationRepository;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.gitHubIntegrationService = gitHubIntegrationService;
        this.gitHubApiClient = gitHubApiClient;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.writeTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readTransactionTemplate.setReadOnly(true);
    }

    public GitHubActionsSyncResponse syncRepositoryWorkflows(
            UUID workspaceId,
            UUID repositoryId,
            UserEntity user
    ) {
        // Phase 1: Short read-only transaction for RBAC + repository/installation verification.
        RepositorySyncContext context = readTransactionTemplate.execute(status -> {
            authorizationService.requireDeveloperOrAbove(workspaceId, user.getId());
            ensureGitHubAppConfigured();
            LinkedRepositoryEntity repository = linkedRepositoryRepository
                    .findByIdAndWorkspaceId(repositoryId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found in this workspace"));
            validateRepositorySyncEligible(repository);
            return new RepositorySyncContext(
                    workspaceId,
                    repository.getId(),
                    repository.getInstallation().getId(),
                    repository.getInstallation().getGithubInstallationId(),
                    repository.getOwnerLogin(),
                    repository.getName(),
                    repository.getFullName()
            );
        });

        if (context == null) {
            throw new ResourceNotFoundException("Repository not found in this workspace");
        }

        if (!activeRepositorySyncs.add(repositoryId)) {
            throw new ConflictException(
                    "GitHub Actions synchronization is already in progress for " + context.fullName()
                            + ". Please wait for the active sync to complete."
            );
        }

        try {
            // Phase 2: External GitHub API requests run OUTSIDE any database transaction.
            int maxRuns = appProperties.getGithubApp().getActionsSyncMaxRunsPerRepo();
            List<GitHubWorkflowMetadata> remoteWorkflows;
            List<GitHubWorkflowRunMetadata> remoteRuns;
            try {
                remoteWorkflows = gitHubApiClient.listRepositoryWorkflows(
                        context.githubInstallationId(),
                        context.ownerLogin(),
                        context.repoName()
                );
                remoteRuns = gitHubApiClient.listRepositoryWorkflowRuns(
                        context.githubInstallationId(),
                        context.ownerLogin(),
                        context.repoName(),
                        maxRuns
                );
            } catch (GitHubInstallationRevokedException ex) {
                markInstallationRevoked(context, user);
                throw new ExternalIntegrationException(
                        HttpStatus.CONFLICT,
                        "GitHub App installation was revoked on GitHub. Reconnect the GitHub App on the Repositories page to restore workflow synchronization."
                );
            } catch (GitHubInstallationSuspendedException ex) {
                markInstallationSuspended(context, user);
                throw new ExternalIntegrationException(
                        HttpStatus.CONFLICT,
                        "GitHub App installation is suspended on GitHub. Unsuspend the installation on GitHub before synchronizing workflows."
                );
            } catch (GitHubRepositoryNotFoundException ex) {
                markRepositoryDisconnected(context, user);
                throw new ResourceNotFoundException(
                        "Repository " + context.fullName()
                                + " no longer exists on GitHub or is no longer accessible to this installation. It has been marked DISCONNECTED."
                );
            }

            // Phase 3: Short write transaction for idempotent PostgreSQL persistence + audit log.
            GitHubActionsSyncResponse response = writeTransactionTemplate.execute(
                    status -> persistSynchronizedData(context, user, remoteWorkflows, remoteRuns)
            );
            log.info(
                    "Synchronized GitHub Actions for repo={} workspaceId={} workflows={} runs={} (created={}, updated={})",
                    context.fullName(),
                    workspaceId,
                    response != null ? response.workflowsSynced() : 0,
                    response != null ? response.runsSynced() : 0,
                    response != null ? response.runsCreated() : 0,
                    response != null ? response.runsUpdated() : 0
            );
            return response;
        } finally {
            activeRepositorySyncs.remove(repositoryId);
        }
    }

    public WorkflowRunJobsResponse fetchWorkflowRunJobs(
            UUID workspaceId,
            UUID runId,
            UserEntity user
    ) {
        RunJobFetchContext context = readTransactionTemplate.execute(status -> {
            authorizationService.requireMembership(workspaceId, user.getId());
            ensureGitHubAppConfigured();
            GitHubWorkflowRunEntity run = workflowRunRepository
                    .findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Workflow run not found in this workspace"));
            LinkedRepositoryEntity repository = run.getLinkedRepository();
            validateRepositorySyncEligible(repository);
            return new RunJobFetchContext(
                    workspaceId,
                    run.getId(),
                    run.getGithubRunId(),
                    repository.getId(),
                    repository.getInstallation().getId(),
                    repository.getInstallation().getGithubInstallationId(),
                    repository.getOwnerLogin(),
                    repository.getName(),
                    repository.getFullName()
            );
        });

        if (context == null) {
            throw new ResourceNotFoundException("Workflow run not found in this workspace");
        }

        List<GitHubWorkflowJobMetadata> remoteJobs;
        try {
            remoteJobs = gitHubApiClient.listWorkflowRunJobs(
                    context.githubInstallationId(),
                    context.ownerLogin(),
                    context.repoName(),
                    context.githubRunId()
            );
        } catch (GitHubInstallationRevokedException ex) {
            markInstallationRevoked(context.toRepositoryContext(), user);
            throw new ExternalIntegrationException(
                    HttpStatus.CONFLICT,
                    "GitHub App installation was revoked on GitHub. Reconnect the GitHub App on the Repositories page to inspect workflow jobs."
            );
        } catch (GitHubInstallationSuspendedException ex) {
            markInstallationSuspended(context.toRepositoryContext(), user);
            throw new ExternalIntegrationException(
                    HttpStatus.CONFLICT,
                    "GitHub App installation is suspended on GitHub. Unsuspend the installation on GitHub to inspect workflow jobs."
            );
        } catch (GitHubRepositoryNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "Workflow run jobs could not be retrieved because the repository or run no longer exists on GitHub."
            );
        }

        List<GitHubWorkflowJobDto> jobDtos = remoteJobs.stream()
                .map(this::toJobDto)
                .toList();

        return new WorkflowRunJobsResponse(
                context.runId(),
                context.githubRunId(),
                context.repositoryId(),
                context.fullName(),
                Instant.now(),
                jobDtos
        );
    }

    private GitHubActionsSyncResponse persistSynchronizedData(
            RepositorySyncContext context,
            UserEntity user,
            List<GitHubWorkflowMetadata> remoteWorkflows,
            List<GitHubWorkflowRunMetadata> remoteRuns
    ) {
        LinkedRepositoryEntity repository = linkedRepositoryRepository
                .findByIdAndWorkspaceId(context.repositoryId(), context.workspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found in this workspace"));
        WorkspaceEntity workspace = repository.getWorkspace();
        Instant syncedAt = Instant.now();

        List<GitHubWorkflowEntity> existingWorkflows = workflowRepository
                .findAllByLinkedRepositoryId(repository.getId());
        Map<Long, GitHubWorkflowEntity> workflowsByGithubId = new HashMap<>();
        for (GitHubWorkflowEntity existing : existingWorkflows) {
            workflowsByGithubId.put(existing.getGithubWorkflowId(), existing);
        }

        Set<Long> seenWorkflowIds = new HashSet<>();
        int workflowsSynced = 0;
        for (GitHubWorkflowMetadata remote : remoteWorkflows) {
            seenWorkflowIds.add(remote.githubWorkflowId());
            GitHubWorkflowEntity entity = workflowsByGithubId.get(remote.githubWorkflowId());
            if (entity == null) {
                entity = new GitHubWorkflowEntity();
                entity.setWorkspace(workspace);
                entity.setLinkedRepository(repository);
                entity.setGithubWorkflowId(remote.githubWorkflowId());
            }
            entity.setName(remote.name());
            entity.setPath(remote.path());
            entity.setState(StringUtils.hasText(remote.state()) ? remote.state() : "unknown");
            entity.setHtmlUrl(remote.htmlUrl());
            entity.setBadgeUrl(remote.badgeUrl());
            entity.setGithubCreatedAt(remote.createdAt());
            entity.setGithubUpdatedAt(remote.updatedAt());
            entity.setLastSyncedAt(syncedAt);

            GitHubWorkflowEntity saved = workflowRepository.save(entity);
            workflowsByGithubId.put(saved.getGithubWorkflowId(), saved);
            workflowsSynced++;
        }

        // Reflect workflows removed from GitHub without destroying historical workflow runs.
        for (GitHubWorkflowEntity existing : existingWorkflows) {
            if (!seenWorkflowIds.contains(existing.getGithubWorkflowId())
                    && !"deleted".equalsIgnoreCase(existing.getState())) {
                existing.setState("deleted");
                existing.setLastSyncedAt(syncedAt);
                workflowRepository.save(existing);
            }
        }

        Map<RunAttemptKey, GitHubWorkflowRunEntity> existingRunsByKey = new HashMap<>();
        if (!remoteRuns.isEmpty()) {
            Set<Long> runIds = new HashSet<>();
            for (GitHubWorkflowRunMetadata remoteRun : remoteRuns) {
                runIds.add(remoteRun.githubRunId());
            }
            List<GitHubWorkflowRunEntity> existingRuns = workflowRunRepository
                    .findAllByLinkedRepositoryIdAndGithubRunIdIn(repository.getId(), runIds);
            for (GitHubWorkflowRunEntity existingRun : existingRuns) {
                existingRunsByKey.put(
                        new RunAttemptKey(existingRun.getGithubRunId(), existingRun.getRunAttempt()),
                        existingRun
                );
            }
        }

        int runsCreated = 0;
        int runsUpdated = 0;
        Set<RunAttemptKey> processedKeys = new HashSet<>();
        for (GitHubWorkflowRunMetadata remoteRun : remoteRuns) {
            int attempt = Math.max(1, remoteRun.runAttempt());
            RunAttemptKey key = new RunAttemptKey(remoteRun.githubRunId(), attempt);
            if (!processedKeys.add(key)) {
                // Guard against duplicate items across shifting GitHub pagination pages.
                continue;
            }

            GitHubWorkflowRunEntity entity = existingRunsByKey.get(key);
            boolean isNew = entity == null;
            if (isNew) {
                entity = new GitHubWorkflowRunEntity();
                entity.setWorkspace(workspace);
                entity.setLinkedRepository(repository);
                entity.setGithubRunId(remoteRun.githubRunId());
                entity.setRunAttempt(attempt);
                runsCreated++;
            } else {
                runsUpdated++;
            }

            GitHubWorkflowEntity linkedWorkflow = workflowsByGithubId.get(remoteRun.githubWorkflowId());
            entity.setWorkflow(linkedWorkflow);
            entity.setGithubWorkflowId(remoteRun.githubWorkflowId());
            entity.setRunNumber(Math.max(0, remoteRun.runNumber()));
            entity.setWorkflowName(
                    StringUtils.hasText(remoteRun.workflowName())
                            ? remoteRun.workflowName()
                            : (linkedWorkflow != null ? linkedWorkflow.getName() : "Workflow #" + remoteRun.githubWorkflowId())
            );
            entity.setDisplayTitle(remoteRun.displayTitle());
            entity.setEvent(StringUtils.hasText(remoteRun.event()) ? remoteRun.event() : "unknown");
            entity.setHeadBranch(remoteRun.headBranch());
            entity.setHeadSha(remoteRun.headSha());
            entity.setGithubStatus(StringUtils.hasText(remoteRun.status()) ? remoteRun.status() : "unknown");
            entity.setGithubConclusion(remoteRun.conclusion());
            entity.setNormalizedStatus(
                    WorkflowRunNormalizedStatus.fromGitHub(remoteRun.status(), remoteRun.conclusion())
            );
            entity.setActorLogin(remoteRun.actorLogin());
            entity.setActorAvatarUrl(remoteRun.actorAvatarUrl());
            entity.setHtmlUrl(remoteRun.htmlUrl());
            entity.setGithubCreatedAt(remoteRun.createdAt());
            entity.setGithubUpdatedAt(remoteRun.updatedAt());
            entity.setRunStartedAt(remoteRun.runStartedAt());
            entity.setLastSyncedAt(syncedAt);

            GitHubWorkflowRunEntity savedRun = workflowRunRepository.save(entity);
            existingRunsByKey.put(key, savedRun);
        }

        int runsSynced = runsCreated + runsUpdated;
        String message = workflowsSynced == 0
                ? "Synchronized " + repository.getFullName() + ": no GitHub Actions workflows were found in this repository."
                : "Synchronized " + workflowsSynced + " workflow" + (workflowsSynced == 1 ? "" : "s")
                        + " and " + runsSynced + " recent run" + (runsSynced == 1 ? "" : "s")
                        + " for " + repository.getFullName() + ".";

        auditService.record(
                workspace,
                user,
                "GITHUB_ACTIONS_SYNCED",
                "LINKED_REPOSITORY",
                repository.getId().toString(),
                message,
                Map.of(
                        "repositoryId", repository.getId().toString(),
                        "repositoryFullName", repository.getFullName(),
                        "workflowsSynced", workflowsSynced,
                        "runsSynced", runsSynced,
                        "runsCreated", runsCreated,
                        "runsUpdated", runsUpdated
                )
        );

        return new GitHubActionsSyncResponse(
                repository.getId(),
                repository.getFullName(),
                workflowsSynced,
                runsSynced,
                runsCreated,
                runsUpdated,
                syncedAt,
                message
        );
    }

    private void markInstallationRevoked(RepositorySyncContext context, UserEntity user) {
        tokenService.evictInstallationToken(context.githubInstallationId());
        writeTransactionTemplate.executeWithoutResult(status -> installationRepository
                .findByIdAndWorkspaceId(context.installationUuid(), context.workspaceId())
                .ifPresent(installation -> {
                    installation.setStatus(InstallationStatus.REVOKED);
                    installation.setLastSyncedAt(Instant.now());
                    installationRepository.save(installation);
                    gitHubIntegrationService.markInstallationRepositoriesStatus(
                            installation,
                            RepositoryConnectionStatus.DISCONNECTED
                    );
                    auditService.record(
                            installation.getWorkspace(),
                            user,
                            "GITHUB_INSTALLATION_REVOKED",
                            "GITHUB_INSTALLATION",
                            installation.getId().toString(),
                            "Detected revoked GitHub App installation #" + installation.getGithubInstallationId()
                                    + " during GitHub Actions sync",
                            Map.of(
                                    "githubInstallationId", installation.getGithubInstallationId(),
                                    "repositoryId", context.repositoryId().toString()
                            )
                    );
                }));
    }

    private void markInstallationSuspended(RepositorySyncContext context, UserEntity user) {
        tokenService.evictInstallationToken(context.githubInstallationId());
        writeTransactionTemplate.executeWithoutResult(status -> installationRepository
                .findByIdAndWorkspaceId(context.installationUuid(), context.workspaceId())
                .ifPresent(installation -> {
                    installation.setStatus(InstallationStatus.SUSPENDED);
                    installation.setLastSyncedAt(Instant.now());
                    installationRepository.save(installation);
                    gitHubIntegrationService.markInstallationRepositoriesStatus(
                            installation,
                            RepositoryConnectionStatus.SUSPENDED
                    );
                    auditService.record(
                            installation.getWorkspace(),
                            user,
                            "GITHUB_INSTALLATION_SUSPENDED",
                            "GITHUB_INSTALLATION",
                            installation.getId().toString(),
                            "Detected suspended GitHub App installation #" + installation.getGithubInstallationId()
                                    + " during GitHub Actions sync",
                            Map.of(
                                    "githubInstallationId", installation.getGithubInstallationId(),
                                    "repositoryId", context.repositoryId().toString()
                            )
                    );
                }));
    }

    private void markRepositoryDisconnected(RepositorySyncContext context, UserEntity user) {
        writeTransactionTemplate.executeWithoutResult(status -> linkedRepositoryRepository
                .findByIdAndWorkspaceId(context.repositoryId(), context.workspaceId())
                .ifPresent(repository -> {
                    repository.setConnectionStatus(RepositoryConnectionStatus.DISCONNECTED);
                    repository.setLastSyncedAt(Instant.now());
                    linkedRepositoryRepository.save(repository);
                    auditService.record(
                            repository.getWorkspace(),
                            user,
                            "GITHUB_REPOSITORY_DISCONNECTED",
                            "LINKED_REPOSITORY",
                            repository.getId().toString(),
                            "Marked repository " + repository.getFullName()
                                    + " DISCONNECTED after GitHub returned 404 during Actions sync",
                            Map.of(
                                    "repositoryId", repository.getId().toString(),
                                    "repositoryFullName", repository.getFullName()
                            )
                    );
                }));
    }

    private void ensureGitHubAppConfigured() {
        if (!appProperties.getGithubApp().isConfigured()) {
            throw new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App integration is not configured on this server. Configure GITHUB_APP_ID, GITHUB_APP_SLUG, and GITHUB_APP_PRIVATE_KEY first."
            );
        }
    }

    private void validateRepositorySyncEligible(LinkedRepositoryEntity repository) {
        GitHubInstallationEntity installation = repository.getInstallation();
        if (installation.getStatus() == InstallationStatus.REVOKED) {
            throw new ExternalIntegrationException(
                    HttpStatus.CONFLICT,
                    "The GitHub App installation for " + repository.getFullName()
                            + " has been revoked. Reconnect the GitHub App on the Repositories page to synchronize workflows."
            );
        }
        if (installation.getStatus() == InstallationStatus.SUSPENDED
                || repository.getConnectionStatus() == RepositoryConnectionStatus.SUSPENDED) {
            throw new ExternalIntegrationException(
                    HttpStatus.CONFLICT,
                    "The GitHub App installation for " + repository.getFullName()
                            + " is currently suspended on GitHub. Unsuspend the installation on GitHub to resume synchronization."
            );
        }
        if (repository.getConnectionStatus() == RepositoryConnectionStatus.DISCONNECTED) {
            throw new ExternalIntegrationException(
                    HttpStatus.CONFLICT,
                    "Repository " + repository.getFullName()
                            + " is disconnected from the GitHub App installation. Re-authorize repository access on GitHub and sync installations first."
            );
        }
    }

    private GitHubWorkflowJobDto toJobDto(GitHubWorkflowJobMetadata job) {
        List<GitHubWorkflowStepDto> steps = job.steps() == null
                ? List.of()
                : job.steps().stream().map(this::toStepDto).toList();
        return new GitHubWorkflowJobDto(
                job.githubJobId(),
                job.githubRunId(),
                job.runAttempt(),
                job.name(),
                job.status(),
                job.conclusion(),
                WorkflowRunNormalizedStatus.fromGitHub(job.status(), job.conclusion()),
                job.startedAt(),
                job.completedAt(),
                calculateDurationSeconds(job.startedAt(), job.completedAt()),
                job.htmlUrl(),
                job.runnerName(),
                steps
        );
    }

    private GitHubWorkflowStepDto toStepDto(GitHubWorkflowStepMetadata step) {
        return new GitHubWorkflowStepDto(
                step.number(),
                step.name(),
                step.status(),
                step.conclusion(),
                WorkflowRunNormalizedStatus.fromGitHub(step.status(), step.conclusion()),
                step.startedAt(),
                step.completedAt(),
                calculateDurationSeconds(step.startedAt(), step.completedAt())
        );
    }

    static Long calculateDurationSeconds(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, completedAt).getSeconds();
    }

    private record RunAttemptKey(long githubRunId, int runAttempt) {
    }

    private record RepositorySyncContext(
            UUID workspaceId,
            UUID repositoryId,
            UUID installationUuid,
            long githubInstallationId,
            String ownerLogin,
            String repoName,
            String fullName
    ) {
    }

    private record RunJobFetchContext(
            UUID workspaceId,
            UUID runId,
            long githubRunId,
            UUID repositoryId,
            UUID installationUuid,
            long githubInstallationId,
            String ownerLogin,
            String repoName,
            String fullName
    ) {
        RepositorySyncContext toRepositoryContext() {
            return new RepositorySyncContext(
                    workspaceId,
                    repositoryId,
                    installationUuid,
                    githubInstallationId,
                    ownerLogin,
                    repoName,
                    fullName
            );
        }
    }
}
