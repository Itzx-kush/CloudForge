package com.cloudforge.backend.worker.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Persistent trusted worker node identity, capabilities, concurrency counters, and heartbeat state.
 * Never stores plaintext credentials.
 */
@Entity
@Table(name = "worker_nodes")
public class WorkerNodeEntity extends BaseAuditableEntity {

    @Column(name = "worker_id", nullable = false, unique = true, length = 128)
    private String workerId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 40)
    private WorkerAdapterType adapterType = WorkerAdapterType.DOCKER_ISOLATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WorkerStatus status = WorkerStatus.AVAILABLE;

    @Column(name = "supported_operations_json", nullable = false, columnDefinition = "TEXT")
    private String supportedOperationsJson = "[]";

    @Column(name = "max_concurrency", nullable = false)
    private int maxConcurrency = 1;

    @Column(name = "active_assignments", nullable = false)
    private int activeAssignments = 0;

    @Column(name = "credential_key_id", length = 64)
    private String credentialKeyId;

    @Column(name = "host_label", length = 120)
    private String hostLabel;

    @Column(name = "agent_version", length = 64)
    private String agentVersion;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "offline_at")
    private Instant offlineAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public WorkerAdapterType getAdapterType() {
        return adapterType;
    }

    public void setAdapterType(WorkerAdapterType adapterType) {
        this.adapterType = adapterType;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public String getSupportedOperationsJson() {
        return supportedOperationsJson;
    }

    public void setSupportedOperationsJson(String supportedOperationsJson) {
        this.supportedOperationsJson = supportedOperationsJson;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getActiveAssignments() {
        return activeAssignments;
    }

    public void setActiveAssignments(int activeAssignments) {
        this.activeAssignments = Math.max(0, activeAssignments);
    }

    public String getCredentialKeyId() {
        return credentialKeyId;
    }

    public void setCredentialKeyId(String credentialKeyId) {
        this.credentialKeyId = credentialKeyId;
    }

    public String getHostLabel() {
        return hostLabel;
    }

    public void setHostLabel(String hostLabel) {
        this.hostLabel = hostLabel;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getOfflineAt() {
        return offlineAt;
    }

    public void setOfflineAt(Instant offlineAt) {
        this.offlineAt = offlineAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }
}
