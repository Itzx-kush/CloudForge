package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesOperationKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KubernetesDeploymentOperationRepository
        extends JpaRepository<KubernetesDeploymentOperationEntity, UUID>,
        KubernetesDeploymentOperationRepositoryCustom {

    @Query("""
            SELECT op FROM KubernetesDeploymentOperationEntity op
            LEFT JOIN FETCH op.project
            LEFT JOIN FETCH op.environment
            LEFT JOIN FETCH op.clusterConnection
            LEFT JOIN FETCH op.namespaceBinding
            LEFT JOIN FETCH op.ociArtifact
            LEFT JOIN FETCH op.pipelineRun
            LEFT JOIN FETCH op.stageRun
            LEFT JOIN FETCH op.requestedBy
            LEFT JOIN FETCH op.cancelledBy
            LEFT JOIN FETCH op.productionApprovedBy
            WHERE op.id = :id AND op.workspace.id = :workspaceId
            """)
    Optional<KubernetesDeploymentOperationEntity> findByIdAndWorkspaceIdWithDetails(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT op FROM KubernetesDeploymentOperationEntity op WHERE op.id = :id AND op.workspace.id = :workspaceId")
    Optional<KubernetesDeploymentOperationEntity> findByIdAndWorkspaceIdForUpdate(
            @Param("id") UUID id,
            @Param("workspaceId") UUID workspaceId
    );

    Optional<KubernetesDeploymentOperationEntity> findByStageRunId(UUID stageRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT op FROM KubernetesDeploymentOperationEntity op WHERE op.stageRun.id = :stageRunId")
    Optional<KubernetesDeploymentOperationEntity> findByStageRunIdForUpdate(@Param("stageRunId") UUID stageRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT op FROM KubernetesDeploymentOperationEntity op WHERE op.attempt.id = :attemptId")
    Optional<KubernetesDeploymentOperationEntity> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT op FROM KubernetesDeploymentOperationEntity op WHERE op.pipelineRun.id = :pipelineRunId")
    List<KubernetesDeploymentOperationEntity> findAllByPipelineRunIdForUpdate(@Param("pipelineRunId") UUID pipelineRunId);

    Optional<KubernetesDeploymentOperationEntity> findByWorkspaceIdAndIdempotencyKey(
            UUID workspaceId,
            String idempotencyKey
    );

    @Query("""
            SELECT COUNT(op) > 0 FROM KubernetesDeploymentOperationEntity op
            WHERE op.clusterConnection.id = :clusterId
              AND op.namespaceName = :namespaceName
              AND op.deploymentName = :deploymentName
              AND op.status IN :activeStatuses
            """)
    boolean existsActiveOperationForTarget(
            @Param("clusterId") UUID clusterId,
            @Param("namespaceName") String namespaceName,
            @Param("deploymentName") String deploymentName,
            @Param("activeStatuses") Collection<KubernetesDeploymentStatus> activeStatuses
    );

    @Query("""
            SELECT COALESCE(MAX(op.revisionNumber), 0) FROM KubernetesDeploymentOperationEntity op
            WHERE op.clusterConnection.id = :clusterId AND op.namespaceName = :namespace
              AND op.deploymentName = :deployment
            """)
    long latestRevision(UUID clusterId, String namespace, String deployment);

    Optional<KubernetesDeploymentOperationEntity>
    findFirstByClusterConnectionIdAndNamespaceNameAndDeploymentNameOrderByCreatedAtDescIdDesc(
            UUID clusterId, String namespace, String deployment
    );

    Optional<KubernetesDeploymentOperationEntity>
    findFirstByClusterConnectionIdAndNamespaceNameAndDeploymentNameAndStatusOrderByCreatedAtDescIdDesc(
            UUID clusterId, String namespace, String deployment, KubernetesDeploymentStatus status
    );

    @Query("""
            SELECT op FROM KubernetesDeploymentOperationEntity op WHERE op.workspace.id = :workspace
              AND op.project.id = COALESCE(:project, op.project.id)
              AND op.environment.id = COALESCE(:environment, op.environment.id)
              AND op.clusterConnection.id = COALESCE(:cluster, op.clusterConnection.id)
              AND op.status = COALESCE(:status, op.status)
              AND op.deploymentName = COALESCE(:deployment, op.deploymentName)
              AND (op.revisionNumber IS NOT NULL OR :includeReconciliation = true)
              AND op.operationKind = COALESCE(:kind, op.operationKind)
            """)
    Page<KubernetesDeploymentOperationEntity> listReleases(
            UUID workspace, UUID project, UUID environment, UUID cluster,
            KubernetesDeploymentStatus status, String deployment, KubernetesOperationKind kind, boolean includeReconciliation, Pageable pageable
    );

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, Collection<KubernetesDeploymentStatus> statuses);
}
