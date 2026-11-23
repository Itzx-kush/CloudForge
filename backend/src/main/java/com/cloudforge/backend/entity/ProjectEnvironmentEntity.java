package com.cloudforge.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_environments")
public class ProjectEnvironmentEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_type", nullable = false, length = 32)
    private EnvironmentType environmentType;

    @Column(name = "branch_override", length = 150)
    private String branchOverride;

    @Column(name = "config_variables_json", nullable = false, columnDefinition = "TEXT")
    private String configVariablesJson = "{}";

    @Column(name = "notes", length = 500)
    private String notes;

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EnvironmentType getEnvironmentType() {
        return environmentType;
    }

    public void setEnvironmentType(EnvironmentType environmentType) {
        this.environmentType = environmentType;
    }

    public String getBranchOverride() {
        return branchOverride;
    }

    public void setBranchOverride(String branchOverride) {
        this.branchOverride = branchOverride;
    }

    public String getConfigVariablesJson() {
        return configVariablesJson;
    }

    public void setConfigVariablesJson(String configVariablesJson) {
        this.configVariablesJson = configVariablesJson;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
