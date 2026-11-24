package com.cloudforge.backend.oci.entity;

public enum OciPublishAttemptStatus {
    STARTED,
    PUSHED,
    VERIFIED,
    FAILED,
    CANCELLED,
    RECONCILIATION_REQUIRED
}
