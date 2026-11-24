package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface KubernetesDeploymentOperationRepositoryCustom {

    Page<KubernetesDeploymentOperationEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            KubernetesDeploymentStatus status,
            String search,
            Pageable pageable
    );
}
