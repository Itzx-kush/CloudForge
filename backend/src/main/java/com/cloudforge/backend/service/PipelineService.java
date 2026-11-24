package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.GitHubWorkflowDto;
import com.cloudforge.backend.dto.GitHubWorkflowRunDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.dto.PipelineOverviewResponse;
import com.cloudforge.backend.dto.PipelineRepositorySummaryDto;
import com.cloudforge.backend.entity.GitHubWorkflowEntity;
import com.cloudforge.backend.entity.GitHubWorkflowRunEntity;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.repository.GitHubWorkflowRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRunRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only query service for CloudForge's PostgreSQL-backed Pipelines dashboard.
 * Enforces workspace membership and repository ownership on every query.
 */
@Service
@Transactional(readOnly = true)
public class PipelineService {

    private final AppProperties appProperties;
    private final WorkspaceAuthorizationService authorizationService;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final GitHubWorkflowRepository workflowRepository;
    private final GitHubWorkflowRunRepository workflowRunRepository;

    public PipelineService(
            AppProperties appProperties,
            WorkspaceAuthorizationService authorizationService,
            LinkedRepositoryRepository linkedRepositoryRepository,
            GitHubWorkflowRepository workflowRepository,
            GitHubWorkflowRunRepository workflowRunRepository
    ) {
        this.appProperties = appProperties;
        this.authorizationService = authorizationService;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    public PipelineOverviewResponse getOverview(UUID workspaceId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());

        List<LinkedRepositoryEntity> repositories = linkedRepositoryRepository
                .findAllByWorkspaceIdWithInstallationOrderByFullNameAsc(workspaceId);

        Map<UUID, Long> workflowCountsByRepo = new HashMap<>();
        Map<UUID, Instant> lastSyncByRepo = new HashMap<>();
        long totalWorkflows = 0L;
        Instant workspaceLastSyncedAt = null;

        for (GitHubWorkflowRepository.RepositoryWorkflowAggregate agg : workflowRepository.summarizeByWorkspace(workspaceId)) {
            workflowCountsByRepo.put(agg.getRepositoryId(), agg.getWorkflowCount());
            totalWorkflows += agg.getWorkflowCount();
            lastSyncByRepo.merge(agg.getRepositoryId(), agg.getLastSyncedAt(), PipelineService::maxInstant);
            workspaceLastSyncedAt = maxInstant(workspaceLastSyncedAt, agg.getLastSyncedAt());
        }

        Map<UUID, RepoRunCounts> runCountsByRepo = new HashMap<>();
        long totalRuns = 0L;
        long activeRuns = 0L;
        long failedRuns = 0L;
        long succeededRuns = 0L;

        for (GitHubWorkflowRunRepository.RepositoryRunStatusAggregate agg : workflowRunRepository.summarizeRunsByWorkspace(workspaceId)) {
            long count = agg.getRunCount();
            WorkflowRunNormalizedStatus status = agg.getNormalizedStatus();
            totalRuns += count;
            if (status != null) {
                if (status.isActive()) {
                    activeRuns += count;
                }
                if (status.isFailureLike()) {
                    failedRuns += count;
                }
                if (status == WorkflowRunNormalizedStatus.SUCCESS) {
                    succeededRuns += count;
                }
            }
            runCountsByRepo.computeIfAbsent(agg.getRepositoryId(), id -> new RepoRunCounts())
                    .add(status, count);
            lastSyncByRepo.merge(agg.getRepositoryId(), agg.getLastSyncedAt(), PipelineService::maxInstant);
            workspaceLastSyncedAt = maxInstant(workspaceLastSyncedAt, agg.getLastSyncedAt());
        }

        Map<UUID, GitHubWorkflowRunEntity> latestRunByRepo = new HashMap<>();
        if (!repositories.isEmpty() && totalRuns > 0) {
            List<UUID> repoIds = repositories.stream().map(LinkedRepositoryEntity::getId).toList();
            for (GitHubWorkflowRunEntity run : workflowRunRepository.findRunsForRepositoryIdsOrdered(workspaceId, repoIds)) {
                latestRunByRepo.putIfAbsent(run.getLinkedRepository().getId(), run);
            }
        }

        int connectedRepoCount = 0;
        int reposWithWorkflowsCount = 0;
        List<PipelineRepositorySummaryDto> repoSummaries = new ArrayList<>(repositories.size());

        for (LinkedRepositoryEntity repo : repositories) {
            if (repo.getConnectionStatus() == RepositoryConnectionStatus.CONNECTED) {
                connectedRepoCount++;
            }
            long wfCount = workflowCountsByRepo.getOrDefault(repo.getId(), 0L);
            if (wfCount > 0) {
                reposWithWorkflowsCount++;
            }
            RepoRunCounts counts = runCountsByRepo.getOrDefault(repo.getId(), RepoRunCounts.EMPTY);
            GitHubWorkflowRunEntity latestRun = latestRunByRepo.get(repo.getId());

            repoSummaries.add(new PipelineRepositorySummaryDto(
                    repo.getId(),
                    repo.getFullName(),
                    repo.getName(),
                    repo.getOwnerLogin(),
                    repo.getDefaultBranch(),
                    repo.getVisibility(),
                    repo.getConnectionStatus(),
                    repo.getInstallation().getStatus(),
                    repo.getHtmlUrl(),
                    wfCount,
                    counts.total,
                    counts.active,
                    counts.failed,
                    lastSyncByRepo.get(repo.getId()),
                    latestRun != null ? latestRun.getNormalizedStatus() : null,
                    latestRun != null ? resolveRunTimestamp(latestRun) : null
            ));
        }

        return new PipelineOverviewResponse(
                workspaceId,
                appProperties.getGithubApp().isConfigured(),
                connectedRepoCount,
                reposWithWorkflowsCount,
                totalWorkflows,
                totalRuns,
                activeRuns,
                failedRuns,
                succeededRuns,
                workspaceLastSyncedAt,
                repoSummaries
        );
    }

    public PagedResponse<GitHubWorkflowDto> listRepositoryWorkflows(
            UUID workspaceId,
            UUID repositoryId,
            String query,
            String state,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        ensureRepositoryInWorkspace(workspaceId, repositoryId);

        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : null;
        String normalizedState = StringUtils.hasText(state) ? state.trim() : null;
        Page<GitHubWorkflowEntity> page = workflowRepository.searchByRepository(
                workspaceId,
                repositoryId,
                normalizedQuery,
                normalizedState,
                pageable
        );

        Map<UUID, GitHubWorkflowRunEntity> latestRunByWorkflowId = new HashMap<>();
        List<UUID> workflowIds = page.getContent().stream().map(GitHubWorkflowEntity::getId).toList();
        if (!workflowIds.isEmpty()) {
            for (GitHubWorkflowRunEntity run : workflowRunRepository.findRunsForWorkflowIdsOrdered(workspaceId, workflowIds)) {
                if (run.getWorkflow() != null) {
                    latestRunByWorkflowId.putIfAbsent(run.getWorkflow().getId(), run);
                }
            }
        }

        return PagedResponse.from(page, wf -> toWorkflowDto(wf, latestRunByWorkflowId.get(wf.getId())));
    }

    public PagedResponse<GitHubWorkflowRunDto> listRepositoryWorkflowRuns(
            UUID workspaceId,
            UUID repositoryId,
            UUID workflowId,
            WorkflowRunNormalizedStatus status,
            String branch,
            String query,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        ensureRepositoryInWorkspace(workspaceId, repositoryId);
        if (workflowId != null) {
            ensureWorkflowInWorkspaceAndRepository(workspaceId, repositoryId, workflowId);
        }

        Page<GitHubWorkflowRunEntity> page = workflowRunRepository.searchByWorkspace(
                workspaceId,
                repositoryId,
                workflowId,
                status,
                StringUtils.hasText(branch) ? branch.trim() : null,
                StringUtils.hasText(query) ? query.trim() : null,
                pageable
        );
        return PagedResponse.from(page, this::toWorkflowRunDto);
    }

    public PagedResponse<GitHubWorkflowRunDto> listWorkspaceWorkflowRuns(
            UUID workspaceId,
            UUID repositoryId,
            UUID workflowId,
            WorkflowRunNormalizedStatus status,
            String branch,
            String query,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        if (repositoryId != null) {
            ensureRepositoryInWorkspace(workspaceId, repositoryId);
        }
        if (workflowId != null) {
            ensureWorkflowInWorkspaceAndRepository(workspaceId, repositoryId, workflowId);
        }

        Page<GitHubWorkflowRunEntity> page = workflowRunRepository.searchByWorkspace(
                workspaceId,
                repositoryId,
                workflowId,
                status,
                StringUtils.hasText(branch) ? branch.trim() : null,
                StringUtils.hasText(query) ? query.trim() : null,
                pageable
        );
        return PagedResponse.from(page, this::toWorkflowRunDto);
    }

    public GitHubWorkflowRunDto getWorkflowRun(UUID workspaceId, UUID runId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        GitHubWorkflowRunEntity run = workflowRunRepository
                .findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow run not found in this workspace"));
        return toWorkflowRunDto(run);
    }

    private void ensureRepositoryInWorkspace(UUID workspaceId, UUID repositoryId) {
        linkedRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found in this workspace"));
    }

    private void ensureWorkflowInWorkspaceAndRepository(UUID workspaceId, UUID repositoryId, UUID workflowId) {
        GitHubWorkflowEntity workflow = workflowRepository.findByIdAndWorkspaceId(workflowId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found in this workspace"));
        if (repositoryId != null && !workflow.getLinkedRepository().getId().equals(repositoryId)) {
            throw new ResourceNotFoundException("Workflow not found in the specified repository");
        }
    }

    private GitHubWorkflowDto toWorkflowDto(GitHubWorkflowEntity entity, GitHubWorkflowRunEntity latestRun) {
        return new GitHubWorkflowDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getLinkedRepository().getId(),
                entity.getLinkedRepository().getFullName(),
                entity.getGithubWorkflowId(),
                entity.getName(),
                entity.getPath(),
                entity.getState(),
                entity.getHtmlUrl(),
                entity.getGithubCreatedAt(),
                entity.getGithubUpdatedAt(),
                entity.getLastSyncedAt(),
                latestRun != null ? latestRun.getId() : null,
                latestRun != null ? latestRun.getRunNumber() : null,
                latestRun != null ? latestRun.getNormalizedStatus() : null,
                latestRun != null ? latestRun.getGithubStatus() : null,
                latestRun != null ? latestRun.getGithubConclusion() : null,
                latestRun != null ? resolveRunTimestamp(latestRun) : null
        );
    }

    private GitHubWorkflowRunDto toWorkflowRunDto(GitHubWorkflowRunEntity entity) {
        Long durationSeconds = entity.getNormalizedStatus().isActive()
                ? null
                : GitHubActionsSyncService.calculateDurationSeconds(
                        entity.getRunStartedAt() != null ? entity.getRunStartedAt() : entity.getGithubCreatedAt(),
                        entity.getGithubUpdatedAt()
                );

        return new GitHubWorkflowRunDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getLinkedRepository().getId(),
                entity.getLinkedRepository().getFullName(),
                entity.getWorkflow() != null ? entity.getWorkflow().getId() : null,
                entity.getGithubRunId(),
                entity.getGithubWorkflowId(),
                entity.getRunNumber(),
                entity.getRunAttempt(),
                entity.getWorkflowName(),
                entity.getDisplayTitle(),
                entity.getEvent(),
                entity.getHeadBranch(),
                entity.getHeadSha(),
                entity.getGithubStatus(),
                entity.getGithubConclusion(),
                entity.getNormalizedStatus(),
                entity.getActorLogin(),
                entity.getActorAvatarUrl(),
                entity.getHtmlUrl(),
                entity.getGithubCreatedAt(),
                entity.getGithubUpdatedAt(),
                entity.getRunStartedAt(),
                durationSeconds,
                entity.getLastSyncedAt()
        );
    }

    private static Instant resolveRunTimestamp(GitHubWorkflowRunEntity run) {
        if (run.getRunStartedAt() != null) {
            return run.getRunStartedAt();
        }
        if (run.getGithubCreatedAt() != null) {
            return run.getGithubCreatedAt();
        }
        return run.getLastSyncedAt();
    }

    private static Instant maxInstant(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static final class RepoRunCounts {
        static final RepoRunCounts EMPTY = new RepoRunCounts();

        long total;
        long active;
        long failed;

        void add(WorkflowRunNormalizedStatus status, long count) {
            this.total += count;
            if (status != null) {
                if (status.isActive()) {
                    this.active += count;
                }
                if (status.isFailureLike()) {
                    this.failed += count;
                }
            }
        }
    }
}
