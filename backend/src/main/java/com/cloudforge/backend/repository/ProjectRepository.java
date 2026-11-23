package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    @Query(
            value = """
                    SELECT p FROM ProjectEntity p
                    LEFT JOIN FETCH p.linkedRepository lr
                    LEFT JOIN FETCH lr.installation
                    WHERE p.workspace.id = :workspaceId
                      AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(p.slug) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :search, '%')))
                      AND (:status IS NULL OR p.status = :status)
                    """,
            countQuery = """
                    SELECT COUNT(p) FROM ProjectEntity p
                    WHERE p.workspace.id = :workspaceId
                      AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(p.slug) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :search, '%')))
                      AND (:status IS NULL OR p.status = :status)
                    """
    )
    Page<ProjectEntity> searchByWorkspace(
            @Param("workspaceId") UUID workspaceId,
            @Param("search") String search,
            @Param("status") ProjectStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT p FROM ProjectEntity p
            JOIN FETCH p.workspace w
            LEFT JOIN FETCH p.linkedRepository lr
            LEFT JOIN FETCH lr.installation
            LEFT JOIN FETCH p.createdBy cb
            WHERE p.id = :id AND p.workspace.id = :workspaceId
            """)
    Optional<ProjectEntity> findByIdAndWorkspaceId(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);

    boolean existsByWorkspaceIdAndSlugAndIdNot(UUID workspaceId, String slug, UUID id);

    long countByWorkspaceId(UUID workspaceId);
}
