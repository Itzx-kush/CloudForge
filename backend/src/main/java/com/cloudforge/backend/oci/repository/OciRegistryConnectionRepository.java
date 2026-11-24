package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OciRegistryConnectionRepository extends JpaRepository<OciRegistryConnectionEntity, UUID> {

    @Query("""
            SELECT r FROM OciRegistryConnectionEntity r
            LEFT JOIN FETCH r.credentialProject
            LEFT JOIN FETCH r.credentialEnvironment
            LEFT JOIN FETCH r.usernameSecret
            LEFT JOIN FETCH r.passwordSecret
            LEFT JOIN FETCH r.createdBy
            LEFT JOIN FETCH r.updatedBy
            WHERE r.workspace.id = :workspaceId
            ORDER BY r.createdAt DESC
            """)
    List<OciRegistryConnectionEntity> findAllByWorkspaceIdWithDetails(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT r FROM OciRegistryConnectionEntity r
            LEFT JOIN FETCH r.credentialProject
            LEFT JOIN FETCH r.credentialEnvironment
            LEFT JOIN FETCH r.usernameSecret
            LEFT JOIN FETCH r.passwordSecret
            LEFT JOIN FETCH r.createdBy
            LEFT JOIN FETCH r.updatedBy
            WHERE r.id = :id AND r.workspace.id = :workspaceId
            """)
    Optional<OciRegistryConnectionEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OciRegistryConnectionEntity r WHERE r.id = :id AND r.workspace.id = :workspaceId")
    Optional<OciRegistryConnectionEntity> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);

    boolean existsByWorkspaceIdAndSlugAndIdNot(UUID workspaceId, String slug, UUID id);

    long countByWorkspaceIdAndStatus(UUID workspaceId, OciRegistryStatus status);
}
