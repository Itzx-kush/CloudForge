package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KubernetesDeploymentEventRepository
        extends JpaRepository<KubernetesDeploymentEventEntity, UUID> {

    List<KubernetesDeploymentEventEntity> findAllByDeploymentOperationIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(
            UUID deploymentOperationId,
            UUID workspaceId
    );
}
