package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
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

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, Collection<KubernetesDeploymentStatus> statuses);
}
