package com.cloudforge.backend.k8s.entity;

public enum KubernetesNetworkPolicyMode {
    DEFAULT_DENY,
    ENFORCED_VERIFIED,
    UNENFORCED_LOCAL_CNI
}
