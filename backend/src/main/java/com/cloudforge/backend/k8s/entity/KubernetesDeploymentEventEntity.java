package com.cloudforge.backend.k8s.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Chronological deployment lifecycle event log for Kubernetes deployment operations.
 */
@Entity
@Table(name = "kubernetes_deployment_events")
public class KubernetesDeploymentEventEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deployment_operation_id", nullable = false)
    private KubernetesDeploymentOperationEntity deploymentOperation;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(name = "actor_label", nullable = false, length = 128)
    private String actorLabel;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    private String metadataJson = "{}";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public KubernetesDeploymentOperationEntity getDeploymentOperation() {
        return deploymentOperation;
    }

    public void setDeploymentOperation(KubernetesDeploymentOperationEntity deploymentOperation) {
        this.deploymentOperation = deploymentOperation;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message != null && message.length() > 500 ? message.substring(0, 500) : message;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson != null ? metadataJson : "{}";
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
