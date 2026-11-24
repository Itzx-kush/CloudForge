package com.cloudforge.backend.k8s.entity;

public enum KubernetesClusterType {
    KIND_LOCAL,
    K3D_LOCAL,
    K3S_LOCAL,
    REMOTE_KUBERNETES;

    public boolean isLocalDevCluster() {
        return this == KIND_LOCAL || this == K3D_LOCAL || this == K3S_LOCAL;
    }
}
