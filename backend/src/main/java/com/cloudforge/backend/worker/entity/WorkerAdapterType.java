package com.cloudforge.backend.worker.entity;

public enum WorkerAdapterType {
    DOCKER_ISOLATED,
    GITHUB_SOURCE,
    OCI_BUILDKIT,
    MOCK_TEST
}
