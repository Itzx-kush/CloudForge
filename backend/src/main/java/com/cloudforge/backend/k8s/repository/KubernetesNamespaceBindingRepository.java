package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingEntity;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KubernetesNamespaceBindingRepository
        extends JpaRepository<KubernetesNamespaceBindingEntity, UUID> {

    @Query("""
            SELECT n FROM KubernetesNamespaceBindingEntity n
            LEFT JOIN FETCH n.clusterConnection
            LEFT JOIN FETCH n.project
            LEFT JOIN FETCH n.environment
            LEFT JOIN FETCH n.createdBy
            WHERE n.workspace.id = :workspaceId
            ORDER BY n.createdAt DESC
            """)
    List<KubernetesNamespaceBindingEntity> findAllByWorkspaceIdWithDetails(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT n FROM KubernetesNamespaceBindingEntity n
            LEFT JOIN FETCH n.clusterConnection
            LEFT JOIN FETCH n.project
            LEFT JOIN FETCH n.environment
            LEFT JOIN FETCH n.createdBy
            WHERE n.clusterConnection.id = :clusterId AND n.workspace.id = :workspaceId
            ORDER BY n.namespaceName ASC
            """)
    List<KubernetesNamespaceBindingEntity> findAllByClusterConnectionIdAndWorkspaceIdWithDetails(
            @Param("clusterId") UUID clusterId,
            @Param("workspaceId") UUID workspaceId
    );

    List<KubernetesNamespaceBindingEntity> findAllByClusterConnectionIdAndStatus(
            UUID clusterConnectionId,
            KubernetesNamespaceBindingStatus status
    );

    @Query("""
            SELECT n FROM KubernetesNamespaceBindingEntity n
            LEFT JOIN FETCH n.clusterConnection
            LEFT JOIN FETCH n.project
            LEFT JOIN FETCH n.environment
            LEFT JOIN FETCH n.createdBy
            WHERE n.id = :id AND n.workspace.id = :workspaceId
            """)
    Optional<KubernetesNamespaceBindingEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    Optional<KubernetesNamespaceBindingEntity> findByWorkspaceIdAndProjectIdAndEnvironmentIdAndClusterConnectionId(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterConnectionId
    );

    boolean existsByClusterConnectionIdAndNamespaceName(UUID clusterConnectionId, String namespaceName);

    long countByWorkspaceIdAndStatus(UUID workspaceId, KubernetesNamespaceBindingStatus status);
}
