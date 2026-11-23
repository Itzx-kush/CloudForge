package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface LinkedRepositoryRepository
        extends JpaRepository<LinkedRepositoryEntity, UUID>, JpaSpecificationExecutor<LinkedRepositoryEntity> {

    /**
     * Dynamically builds typed Criteria predicates so nullable search/visibility/status filters
     * are omitted from SQL rather than bound as untyped nulls (preventing PostgreSQL lower(bytea) errors).
     */
    default Page<LinkedRepositoryEntity> searchByWorkspace(
            UUID workspaceId,
            String search,
            String visibility,
            RepositoryConnectionStatus status,
            Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("installation", JoinType.INNER);
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));

            if (search != null && !search.isBlank()) {
                String escaped = search.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, '\\')
                ));
            }

            if (visibility != null && !visibility.isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(root.get("visibility")),
                        visibility.trim().toLowerCase(Locale.ROOT)
                ));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("connectionStatus"), status));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    @Query("""
            SELECT lr FROM LinkedRepositoryEntity lr
            JOIN FETCH lr.installation gi
            WHERE lr.id = :id AND lr.workspace.id = :workspaceId
            """)
    Optional<LinkedRepositoryEntity> findByIdAndWorkspaceId(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    List<LinkedRepositoryEntity> findAllByInstallationId(UUID installationId);

    Optional<LinkedRepositoryEntity> findByWorkspaceIdAndGithubRepoId(UUID workspaceId, Long githubRepoId);

    List<LinkedRepositoryEntity> findAllByGithubRepoId(Long githubRepoId);

    long countByWorkspaceIdAndConnectionStatus(UUID workspaceId, RepositoryConnectionStatus status);
}
