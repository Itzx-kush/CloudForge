package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.GitHubWorkflowRunEntity;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface GitHubWorkflowRunRepository
        extends JpaRepository<GitHubWorkflowRunEntity, UUID>, JpaSpecificationExecutor<GitHubWorkflowRunEntity> {

    /**
     * Typed Criteria search omitting null/blank filters to avoid PostgreSQL lower(bytea) errors.
     */
    default Page<GitHubWorkflowRunEntity> searchByWorkspace(
            UUID workspaceId,
            UUID repositoryId,
            UUID workflowId,
            WorkflowRunNormalizedStatus status,
            String branch,
            String search,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("linkedRepository", JoinType.INNER);
                root.fetch("workflow", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));

            if (repositoryId != null) {
                predicates.add(cb.equal(root.get("linkedRepository").get("id"), repositoryId));
            }
            if (workflowId != null) {
                predicates.add(cb.equal(root.get("workflow").get("id"), workflowId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("normalizedStatus"), status));
            }
            if (branch != null && !branch.isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(cb.coalesce(root.get("headBranch"), "")),
                        branch.trim().toLowerCase(Locale.ROOT)
                ));
            }
            if (search != null && !search.isBlank()) {
                String escaped = escapeLike(search.trim().toLowerCase(Locale.ROOT));
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("workflowName")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("displayTitle"), "")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("headBranch"), "")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("headSha"), "")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("actorLogin"), "")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    @Query("""
            SELECT r FROM GitHubWorkflowRunEntity r
            JOIN FETCH r.linkedRepository lr
            JOIN FETCH lr.installation gi
            LEFT JOIN FETCH r.workflow w
            WHERE r.id = :id AND r.workspace.id = :workspaceId
            """)
    Optional<GitHubWorkflowRunEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    List<GitHubWorkflowRunEntity> findAllByLinkedRepositoryIdAndGithubRunIdIn(
            UUID linkedRepositoryId,
            Collection<Long> githubRunIds
    );

    List<GitHubWorkflowRunEntity> findAllByLinkedRepositoryId(UUID linkedRepositoryId);

    Optional<GitHubWorkflowRunEntity> findByLinkedRepositoryIdAndGithubRunIdAndRunAttempt(
            UUID linkedRepositoryId,
            Long githubRunId,
            Integer runAttempt
    );

    @Query("""
            SELECT r FROM GitHubWorkflowRunEntity r
            WHERE r.workspace.id = :workspaceId
              AND r.workflow.id IN :workflowIds
            ORDER BY r.githubCreatedAt DESC, r.runNumber DESC, r.runAttempt DESC
            """)
    List<GitHubWorkflowRunEntity> findRunsForWorkflowIdsOrdered(
            @Param("workspaceId") UUID workspaceId,
            @Param("workflowIds") Collection<UUID> workflowIds
    );

    @Query("""
            SELECT r FROM GitHubWorkflowRunEntity r
            WHERE r.workspace.id = :workspaceId
              AND r.linkedRepository.id IN :repositoryIds
            ORDER BY r.githubCreatedAt DESC, r.runNumber DESC, r.runAttempt DESC
            """)
    List<GitHubWorkflowRunEntity> findRunsForRepositoryIdsOrdered(
            @Param("workspaceId") UUID workspaceId,
            @Param("repositoryIds") Collection<UUID> repositoryIds
    );

    @Query("""
            SELECT r.linkedRepository.id AS repositoryId,
                   r.normalizedStatus AS normalizedStatus,
                   COUNT(r) AS runCount,
                   MAX(r.lastSyncedAt) AS lastSyncedAt
            FROM GitHubWorkflowRunEntity r
            WHERE r.workspace.id = :workspaceId
            GROUP BY r.linkedRepository.id, r.normalizedStatus
            """)
    List<RepositoryRunStatusAggregate> summarizeRunsByWorkspace(@Param("workspaceId") UUID workspaceId);

    interface RepositoryRunStatusAggregate {
        UUID getRepositoryId();

        WorkflowRunNormalizedStatus getNormalizedStatus();

        long getRunCount();

        Instant getLastSyncedAt();
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
