package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRunRepository
        extends JpaRepository<PipelineRunEntity, UUID>, JpaSpecificationExecutor<PipelineRunEntity> {

    default Page<PipelineRunEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            UUID definitionId,
            PipelineRunStatus status,
            String search,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("project", JoinType.INNER);
                root.fetch("linkedRepository", JoinType.LEFT);
                root.fetch("targetEnvironment", JoinType.LEFT);
                root.fetch("requestedBy", JoinType.LEFT);
                root.fetch("cancelledBy", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));

            if (projectId != null) {
                predicates.add(cb.equal(root.get("project").get("id"), projectId));
            }
            if (definitionId != null) {
                predicates.add(cb.equal(root.get("pipelineDefinition").get("id"), definitionId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (search != null && !search.isBlank()) {
                String escaped = search.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("definitionName")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("targetBranch"), "")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("commitSha"), "")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    @Query("""
            SELECT r FROM PipelineRunEntity r
            JOIN FETCH r.workspace w
            JOIN FETCH r.project p
            LEFT JOIN FETCH r.pipelineDefinition d
            LEFT JOIN FETCH r.linkedRepository lr
            LEFT JOIN FETCH r.targetEnvironment te
            LEFT JOIN FETCH r.requestedBy req
            LEFT JOIN FETCH r.cancelledBy can
            WHERE r.id = :id AND r.workspace.id = :workspaceId
            """)
    Optional<PipelineRunEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM PipelineRunEntity r
            WHERE r.id = :id AND r.workspace.id = :workspaceId
            """)
    Optional<PipelineRunEntity> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
            SELECT COALESCE(MAX(r.runNumber), 0)
            FROM PipelineRunEntity r
            WHERE r.pipelineDefinition.id = :definitionId
            """)
    int findMaxRunNumberByDefinitionId(@Param("definitionId") UUID definitionId);

    long countByWorkspaceId(UUID workspaceId);
}
