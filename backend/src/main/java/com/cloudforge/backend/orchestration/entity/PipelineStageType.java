package com.cloudforge.backend.orchestration.entity;

/**
 * Supported declarative pipeline stage types in CloudForge orchestration definitions.
 * Phase 3.2 persists and schedules these stage types without executing repository code.
 */
public enum PipelineStageType {
    SOURCE_PREP,
    BUILD,
    TEST,
    SECURITY_SCAN,
    CONTAINER_PACKAGE,
    DEPLOYMENT
}
