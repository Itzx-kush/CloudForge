package com.cloudforge.backend.orchestration.repository;

import com.cloudforge.backend.orchestration.entity.PipelineDefinitionEntity;
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

public interface PipelineDefinitionRepository
        extends JpaRepository<PipelineDefinitionEntity, UUID>, JpaSpecificationExecutor<PipelineDefinitionEntity> {

    /**
     * Typed Criteria search omitting null/blank filters to prevent PostgreSQL lower(bytea) errors.
     */
    default Page<PipelineDefinitionEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            Boolean enabled,
            String search,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("project", JoinType.INNER);
                root.fetch("linkedRepository", JoinType.LEFT);
                root.fetch("targetEnvironment", JoinType.LEFT);
                root.fetch("createdBy", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));

            if (projectId != null) {
                predicates.add(cb.equal(root.get("project").get("id"), projectId));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (search != null && !search.isBlank()) {
                String escaped = search.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    @Query("""
            SELECT d FROM PipelineDefinitionEntity d
            JOIN FETCH d.workspace w
            JOIN FETCH d.project p
            LEFT JOIN FETCH d.linkedRepository lr
            LEFT JOIN FETCH d.targetEnvironment te
            LEFT JOIN FETCH d.createdBy cb
            LEFT JOIN FETCH d.updatedBy ub
            WHERE d.id = :id AND d.workspace.id = :workspaceId
            """)
    Optional<PipelineDefinitionEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d FROM PipelineDefinitionEntity d
            WHERE d.id = :id AND d.workspace.id = :workspaceId
            """)
    Optional<PipelineDefinitionEntity> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    boolean existsByProjectIdAndSlug(UUID projectId, String slug);

    boolean existsByProjectIdAndSlugAndIdNot(UUID projectId, String slug, UUID id);

    long countByWorkspaceId(UUID workspaceId);
}
