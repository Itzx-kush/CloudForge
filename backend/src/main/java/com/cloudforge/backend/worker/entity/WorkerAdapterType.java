package com.cloudforge.backend.worker.entity;

public enum WorkerAdapterType {
    DOCKER_ISOLATED,
    GITHUB_SOURCE,
    OCI_BUILDKIT,
    KUBERNETES_DEPLOY,
    MOCK_TEST
}
