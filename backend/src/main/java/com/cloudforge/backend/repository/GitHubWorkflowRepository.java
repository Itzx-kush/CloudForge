package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.GitHubWorkflowEntity;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface GitHubWorkflowRepository
        extends JpaRepository<GitHubWorkflowEntity, UUID>, JpaSpecificationExecutor<GitHubWorkflowEntity> {

    /**
     * Typed Criteria search omitting null/blank filters to avoid PostgreSQL lower(bytea) errors.
     */
    default Page<GitHubWorkflowEntity> searchByRepository(
            UUID workspaceId,
            UUID repositoryId,
            String search,
            String state,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("linkedRepository", JoinType.INNER);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
            predicates.add(cb.equal(root.get("linkedRepository").get("id"), repositoryId));

            if (search != null && !search.isBlank()) {
                String escaped = escapeLike(search.trim().toLowerCase(Locale.ROOT));
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("path")), pattern, '\\')
                ));
            }

            if (state != null && !state.isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(root.get("state")),
                        state.trim().toLowerCase(Locale.ROOT)
                ));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    List<GitHubWorkflowEntity> findAllByLinkedRepositoryId(UUID linkedRepositoryId);

    Optional<GitHubWorkflowEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<GitHubWorkflowEntity> findByLinkedRepositoryIdAndGithubWorkflowId(
            UUID linkedRepositoryId,
            Long githubWorkflowId
    );

    long countByWorkspaceId(UUID workspaceId);

    @Query("""
            SELECT w.linkedRepository.id AS repositoryId,
                   COUNT(w) AS workflowCount,
                   MAX(w.lastSyncedAt) AS lastSyncedAt
            FROM GitHubWorkflowEntity w
            WHERE w.workspace.id = :workspaceId
            GROUP BY w.linkedRepository.id
            """)
    List<RepositoryWorkflowAggregate> summarizeByWorkspace(@Param("workspaceId") UUID workspaceId);

    interface RepositoryWorkflowAggregate {
        UUID getRepositoryId();

        long getWorkflowCount();

        Instant getLastSyncedAt();
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
