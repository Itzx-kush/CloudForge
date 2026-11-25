package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesClusterConnectionEntity;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KubernetesClusterConnectionRepository
        extends JpaRepository<KubernetesClusterConnectionEntity, UUID> {

    @Query("""
            SELECT c FROM KubernetesClusterConnectionEntity c
            LEFT JOIN FETCH c.credentialProject
            LEFT JOIN FETCH c.credentialEnvironment
            LEFT JOIN FETCH c.bearerTokenSecret
            LEFT JOIN FETCH c.createdBy
            LEFT JOIN FETCH c.updatedBy
            WHERE c.workspace.id = :workspaceId
            ORDER BY c.createdAt DESC
            """)
    List<KubernetesClusterConnectionEntity> findAllByWorkspaceIdWithDetails(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT c FROM KubernetesClusterConnectionEntity c
            LEFT JOIN FETCH c.credentialProject
            LEFT JOIN FETCH c.credentialEnvironment
            LEFT JOIN FETCH c.bearerTokenSecret
            LEFT JOIN FETCH c.createdBy
            LEFT JOIN FETCH c.updatedBy
            WHERE c.id = :id AND c.workspace.id = :workspaceId
            """)
    Optional<KubernetesClusterConnectionEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM KubernetesClusterConnectionEntity c WHERE c.id = :id AND c.workspace.id = :workspaceId")
    Optional<KubernetesClusterConnectionEntity> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM KubernetesClusterConnectionEntity c WHERE c.id = :id")
    Optional<KubernetesClusterConnectionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT c.id FROM KubernetesClusterConnectionEntity c
            WHERE c.connectionStatus = :status
            ORDER BY c.validationRequestedAt ASC, c.updatedAt ASC
            """)
    List<UUID> findClusterIdsByStatusOrderByValidationRequested(
            @Param("status") KubernetesClusterStatus status,
            Pageable pageable
    );

    boolean existsByWorkspaceIdAndSlug(UUID workspaceId, String slug);

    boolean existsByWorkspaceIdAndSlugAndIdNot(UUID workspaceId, String slug, UUID id);

    boolean existsByWorkspaceIdAndClusterIdentifier(UUID workspaceId, String clusterIdentifier);

    boolean existsByWorkspaceIdAndClusterIdentifierAndIdNot(UUID workspaceId, String clusterIdentifier, UUID id);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndConnectionStatus(UUID workspaceId, KubernetesClusterStatus status);
}
