package com.cloudforge.backend.orchestration.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_stage_definitions")
public class PipelineStageDefinitionEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_definition_id", nullable = false)
    private PipelineDefinitionEntity pipelineDefinition;

    @Column(name = "stage_key", nullable = false, length = 64)
    private String stageKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type", nullable = false, length = 32)
    private PipelineStageType stageType;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder;

    @Column(name = "depends_on_json", nullable = false, columnDefinition = "TEXT")
    private String dependsOnJson = "[]";

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 600;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 0;

    @Column(name = "allow_failure", nullable = false)
    private boolean allowFailure = false;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    private String metadataJson = "{}";

    public PipelineDefinitionEntity getPipelineDefinition() {
        return pipelineDefinition;
    }

    public void setPipelineDefinition(PipelineDefinitionEntity pipelineDefinition) {
        this.pipelineDefinition = pipelineDefinition;
    }

    public String getStageKey() {
        return stageKey;
    }

    public void setStageKey(String stageKey) {
        this.stageKey = stageKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PipelineStageType getStageType() {
        return stageType;
    }

    public void setStageType(PipelineStageType stageType) {
        this.stageType = stageType;
    }

    public int getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(int executionOrder) {
        this.executionOrder = executionOrder;
    }

    public String getDependsOnJson() {
        return dependsOnJson;
    }

    public void setDependsOnJson(String dependsOnJson) {
        this.dependsOnJson = dependsOnJson;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isAllowFailure() {
        return allowFailure;
    }

    public void setAllowFailure(boolean allowFailure) {
        this.allowFailure = allowFailure;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
