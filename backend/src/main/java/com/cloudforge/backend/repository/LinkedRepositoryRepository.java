package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkedRepositoryRepository extends JpaRepository<LinkedRepositoryEntity, UUID> {

    @Query(
            value = """
                    SELECT lr FROM LinkedRepositoryEntity lr
                    JOIN FETCH lr.installation gi
                    WHERE lr.workspace.id = :workspaceId
                      AND (:search IS NULL OR LOWER(lr.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(COALESCE(lr.description, '')) LIKE LOWER(CONCAT('%', :search, '%')))
                      AND (:visibility IS NULL OR LOWER(lr.visibility) = LOWER(:visibility))
                      AND (:status IS NULL OR lr.connectionStatus = :status)
                    """,
            countQuery = """
                    SELECT COUNT(lr) FROM LinkedRepositoryEntity lr
                    WHERE lr.workspace.id = :workspaceId
                      AND (:search IS NULL OR LOWER(lr.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(COALESCE(lr.description, '')) LIKE LOWER(CONCAT('%', :search, '%')))
                      AND (:visibility IS NULL OR LOWER(lr.visibility) = LOWER(:visibility))
                      AND (:status IS NULL OR lr.connectionStatus = :status)
                    """
    )
    Page<LinkedRepositoryEntity> searchByWorkspace(
            @Param("workspaceId") UUID workspaceId,
            @Param("search") String search,
            @Param("visibility") String visibility,
            @Param("status") RepositoryConnectionStatus status,
            Pageable pageable
    );

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
