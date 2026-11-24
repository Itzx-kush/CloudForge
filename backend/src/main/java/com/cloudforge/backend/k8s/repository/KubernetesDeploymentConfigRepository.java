package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KubernetesDeploymentConfigRepository
        extends JpaRepository<KubernetesDeploymentConfigEntity, UUID> {

    @Query("""
            SELECT cfg FROM KubernetesDeploymentConfigEntity cfg
            LEFT JOIN FETCH cfg.project
            LEFT JOIN FETCH cfg.environment
            LEFT JOIN FETCH cfg.clusterConnection
            LEFT JOIN FETCH cfg.namespaceBinding
            WHERE cfg.workspace.id = :workspaceId
            ORDER BY cfg.updatedAt DESC
            """)
    List<KubernetesDeploymentConfigEntity> findAllByWorkspaceIdWithDetails(@Param("workspaceId") UUID workspaceId);

    @Query("""
            SELECT cfg FROM KubernetesDeploymentConfigEntity cfg
            LEFT JOIN FETCH cfg.project
            LEFT JOIN FETCH cfg.environment
            LEFT JOIN FETCH cfg.clusterConnection
            LEFT JOIN FETCH cfg.namespaceBinding
            WHERE cfg.id = :id AND cfg.workspace.id = :workspaceId
            """)
    Optional<KubernetesDeploymentConfigEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    Optional<KubernetesDeploymentConfigEntity> findByWorkspaceIdAndProjectIdAndEnvironmentIdAndClusterConnectionIdAndDeploymentName(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterConnectionId,
            String deploymentName
    );
}
