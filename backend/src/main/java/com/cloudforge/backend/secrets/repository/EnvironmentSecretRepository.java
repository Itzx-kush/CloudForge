package com.cloudforge.backend.secrets.repository;

import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentSecretRepository
        extends JpaRepository<EnvironmentSecretEntity, UUID>, JpaSpecificationExecutor<EnvironmentSecretEntity> {

    /**
     * Typed Criteria search omitting null/blank filters to avoid PostgreSQL lower(bytea) errors.
     */
    default Page<EnvironmentSecretEntity> searchByEnvironment(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            SecretLifecycleStatus status,
            boolean includeDeleted,
            String search,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("environment", JoinType.INNER);
                root.fetch("createdBy", JoinType.LEFT);
                root.fetch("updatedBy", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
            predicates.add(cb.equal(root.get("environment").get("id"), environmentId));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else if (!includeDeleted) {
                predicates.add(cb.notEqual(root.get("status"), SecretLifecycleStatus.DELETED));
            }

            if (search != null && !search.isBlank()) {
                String escaped = search.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("secretName")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, '\\')
                ));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    @Query("""
            SELECT s FROM EnvironmentSecretEntity s
            JOIN FETCH s.environment e
            LEFT JOIN FETCH s.createdBy cb
            LEFT JOIN FETCH s.updatedBy ub
            LEFT JOIN FETCH s.revokedBy rb
            LEFT JOIN FETCH s.deletedBy db
            WHERE s.id = :secretId
              AND s.environment.id = :environmentId
              AND s.project.id = :projectId
              AND s.workspace.id = :workspaceId
            """)
    Optional<EnvironmentSecretEntity> findByScopedIdWithDetails(
            @Param("secretId") UUID secretId,
            @Param("environmentId") UUID environmentId,
            @Param("projectId") UUID projectId,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM EnvironmentSecretEntity s
            WHERE s.id = :secretId
              AND s.environment.id = :environmentId
              AND s.project.id = :projectId
              AND s.workspace.id = :workspaceId
            """)
    Optional<EnvironmentSecretEntity> findByScopedIdForUpdate(
            @Param("secretId") UUID secretId,
            @Param("environmentId") UUID environmentId,
            @Param("projectId") UUID projectId,
            @Param("workspaceId") UUID workspaceId
    );

    List<EnvironmentSecretEntity> findAllByIdInAndWorkspaceIdAndProjectIdAndEnvironmentId(
            Collection<UUID> ids,
            UUID workspaceId,
            UUID projectId,
            UUID environmentId
    );

    boolean existsByEnvironmentIdAndNameLockKey(UUID environmentId, String nameLockKey);

    long countByEnvironmentIdAndStatusNot(UUID environmentId, SecretLifecycleStatus excludedStatus);
}
