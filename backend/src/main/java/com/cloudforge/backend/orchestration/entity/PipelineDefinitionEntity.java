package com.cloudforge.backend.orchestration.entity;

import com.cloudforge.backend.entity.BaseAuditableEntity;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "pipeline_definitions")
public class PipelineDefinitionEntity extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_repository_id")
    private LinkedRepositoryEntity linkedRepository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_environment_id")
    private ProjectEnvironmentEntity targetEnvironment;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "definition_version", nullable = false)
    private int definitionVersion = 1;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private UserEntity updatedBy;

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public LinkedRepositoryEntity getLinkedRepository() {
        return linkedRepository;
    }

    public void setLinkedRepository(LinkedRepositoryEntity linkedRepository) {
        this.linkedRepository = linkedRepository;
    }

    public ProjectEnvironmentEntity getTargetEnvironment() {
        return targetEnvironment;
    }

    public void setTargetEnvironment(ProjectEnvironmentEntity targetEnvironment) {
        this.targetEnvironment = targetEnvironment;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(int definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public UserEntity getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserEntity createdBy) {
        this.createdBy = createdBy;
    }

    public UserEntity getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UserEntity updatedBy) {
        this.updatedBy = updatedBy;
    }
}
