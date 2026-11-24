package com.cloudforge.backend.orchestration.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.PipelineDefinitionDto;
import com.cloudforge.backend.orchestration.dto.PipelineStageDefinitionDto;
import com.cloudforge.backend.orchestration.dto.StageDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.UpdatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.engine.StageDagValidator;
import com.cloudforge.backend.orchestration.entity.PipelineDefinitionEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageDefinitionEntity;
import com.cloudforge.backend.orchestration.repository.PipelineDefinitionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageDefinitionRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.SlugUtils;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PipelineDefinitionService {

    private final WorkspaceAuthorizationService authorizationService;
    private final ProjectRepository projectRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final PipelineDefinitionRepository definitionRepository;
    private final PipelineStageDefinitionRepository stageDefinitionRepository;
    private final StageDagValidator dagValidator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PipelineDefinitionService(
            WorkspaceAuthorizationService authorizationService,
            ProjectRepository projectRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            PipelineDefinitionRepository definitionRepository,
            PipelineStageDefinitionRepository stageDefinitionRepository,
            StageDagValidator dagValidator,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.projectRepository = projectRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.definitionRepository = definitionRepository;
        this.stageDefinitionRepository = stageDefinitionRepository;
        this.dagValidator = dagValidator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PipelineDefinitionDto> listDefinitions(
            UUID workspaceId,
            UUID projectId,
            Boolean enabled,
            String query,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        }

        Page<PipelineDefinitionEntity> page = definitionRepository.searchByWorkspace(
                workspaceId,
                projectId,
                enabled,
                StringUtils.hasText(query) ? query.trim() : null,
                pageable
        );

        Map<UUID, List<PipelineStageDefinitionEntity>> stagesByDefId = loadStagesByDefinitionIds(page.getContent());
        return PagedResponse.from(page, def -> toDto(def, stagesByDefId.getOrDefault(def.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public PipelineDefinitionDto getDefinition(UUID workspaceId, UUID definitionId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        PipelineDefinitionEntity definition = definitionRepository
                .findByIdAndWorkspaceIdWithDetails(definitionId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline definition not found in this workspace"));
        List<PipelineStageDefinitionEntity> stages = stageDefinitionRepository
                .findAllByPipelineDefinitionIdOrderByExecutionOrderAsc(definition.getId());
        return toDto(definition, stages);
    }

    @Transactional
    public PipelineDefinitionDto createDefinition(
            UUID workspaceId,
            CreatePipelineDefinitionRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));

        LinkedRepositoryEntity repository = resolveRepository(workspaceId, request.linkedRepositoryId(), project);
        StageDagValidator.ValidatedStageGraph validatedGraph = validateStageRequests(request.stages());

        String name = request.name().trim();
        String slug = resolveUniqueSlug(project.getId(), name, null);

        PipelineDefinitionEntity definition = new PipelineDefinitionEntity();
        definition.setWorkspace(workspace);
        definition.setProject(project);
        definition.setLinkedRepository(repository);
        definition.setName(name);
        definition.setSlug(slug);
        definition.setDescription(trimToNull(request.description()));
        definition.setEnabled(request.enabled() == null || request.enabled());
        definition.setDefinitionVersion(1);
        definition.setCreatedBy(user);
        definition.setUpdatedBy(user);

        PipelineDefinitionEntity saved = definitionRepository.saveAndFlush(definition);
        List<PipelineStageDefinitionEntity> savedStages = persistStages(saved, validatedGraph.orderedStages());

        auditService.record(
                workspace,
                user,
                "PIPELINE_DEFINITION_CREATED",
                "PIPELINE_DEFINITION",
                saved.getId().toString(),
                "Created pipeline definition \"" + saved.getName() + "\" (v1) for project \"" + project.getName() + "\"",
                Map.of(
                        "projectId", project.getId().toString(),
                        "definitionVersion", saved.getDefinitionVersion(),
                        "stageCount", savedStages.size(),
                        "enabled", saved.isEnabled()
                )
        );

        return toDto(saved, savedStages);
    }

    @Transactional
    public PipelineDefinitionDto updateDefinition(
            UUID workspaceId,
            UUID definitionId,
            UpdatePipelineDefinitionRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        PipelineDefinitionEntity definition = definitionRepository
                .findByIdAndWorkspaceIdForUpdate(definitionId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline definition not found in this workspace"));

        if (request.expectedRowVersion() != null
                && !request.expectedRowVersion().equals(definition.getRowVersion())) {
            throw new ConflictException(
                    "Pipeline definition was modified concurrently (expected version "
                            + request.expectedRowVersion() + ", actual " + definition.getRowVersion() + ")"
            );
        }

        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(definition.getProject().getId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));

        LinkedRepositoryEntity repository = request.linkedRepositoryId() != null
                ? linkedRepositoryRepository.findByIdAndWorkspaceId(request.linkedRepositoryId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Linked repository not found in this workspace"))
                : null;

        StageDagValidator.ValidatedStageGraph validatedGraph = validateStageRequests(request.stages());

        String name = request.name().trim();
        String slug = resolveUniqueSlug(project.getId(), name, definition.getId());

        definition.setName(name);
        definition.setSlug(slug);
        definition.setDescription(trimToNull(request.description()));
        definition.setLinkedRepository(repository);
        if (request.enabled() != null) {
            definition.setEnabled(request.enabled());
        }
        definition.setDefinitionVersion(definition.getDefinitionVersion() + 1);
        definition.setUpdatedBy(user);

        PipelineDefinitionEntity saved = definitionRepository.saveAndFlush(definition);

        stageDefinitionRepository.deleteAllByPipelineDefinitionId(saved.getId());
        PipelineDefinitionEntity reloaded = definitionRepository
                .findByIdAndWorkspaceIdWithDetails(saved.getId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline definition not found in this workspace"));
        List<PipelineStageDefinitionEntity> savedStages = persistStages(reloaded, validatedGraph.orderedStages());

        auditService.record(
                workspace,
                user,
                "PIPELINE_DEFINITION_UPDATED",
                "PIPELINE_DEFINITION",
                reloaded.getId().toString(),
                "Updated pipeline definition \"" + reloaded.getName() + "\" to v" + reloaded.getDefinitionVersion(),
                Map.of(
                        "projectId", project.getId().toString(),
                        "definitionVersion", reloaded.getDefinitionVersion(),
                        "stageCount", savedStages.size(),
                        "enabled", reloaded.isEnabled()
                )
        );

        return toDto(reloaded, savedStages);
    }

    private LinkedRepositoryEntity resolveRepository(
            UUID workspaceId,
            UUID requestedRepoId,
            ProjectEntity project
    ) {
        if (requestedRepoId != null) {
            return linkedRepositoryRepository.findByIdAndWorkspaceId(requestedRepoId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Linked repository not found in this workspace"));
        }
        return project.getLinkedRepository();
    }

    private StageDagValidator.ValidatedStageGraph validateStageRequests(List<StageDefinitionRequest> requests) {
        List<StageDagValidator.StageSpecInput> inputs = requests.stream()
                .map(req -> new StageDagValidator.StageSpecInput(
                        req.stageKey(),
                        req.displayName(),
                        req.stageType(),
                        req.dependsOn(),
                        req.timeoutSeconds(),
                        req.maxRetries(),
                        req.allowFailure(),
                        req.metadata()
                ))
                .toList();
        return dagValidator.validateAndSort(inputs);
    }

    private List<PipelineStageDefinitionEntity> persistStages(
            PipelineDefinitionEntity definition,
            List<StageDagValidator.ValidatedStageSpec> orderedStages
    ) {
        List<PipelineStageDefinitionEntity> result = new ArrayList<>(orderedStages.size());
        for (StageDagValidator.ValidatedStageSpec spec : orderedStages) {
            PipelineStageDefinitionEntity entity = new PipelineStageDefinitionEntity();
            entity.setPipelineDefinition(definition);
            entity.setStageKey(spec.stageKey());
            entity.setDisplayName(spec.displayName());
            entity.setStageType(spec.stageType());
            entity.setExecutionOrder(spec.executionOrder());
            entity.setDependsOnJson(writeJson(spec.dependsOn()));
            entity.setTimeoutSeconds(spec.timeoutSeconds());
            entity.setMaxRetries(spec.maxRetries());
            entity.setAllowFailure(spec.allowFailure());
            entity.setMetadataJson(writeJson(spec.metadata()));
            result.add(stageDefinitionRepository.save(entity));
        }
        stageDefinitionRepository.flush();
        return result;
    }

    private String resolveUniqueSlug(UUID projectId, String name, UUID excludeId) {
        String baseSlug = SlugUtils.toSlug(name, "pipeline");
        String candidate = baseSlug;
        int suffix = 2;
        while (excludeId == null
                ? definitionRepository.existsByProjectIdAndSlug(projectId, candidate)
                : definitionRepository.existsByProjectIdAndSlugAndIdNot(projectId, candidate, excludeId)) {
            candidate = baseSlug + "-" + suffix++;
        }
        return candidate;
    }

    private Map<UUID, List<PipelineStageDefinitionEntity>> loadStagesByDefinitionIds(
            List<PipelineDefinitionEntity> definitions
    ) {
        Map<UUID, List<PipelineStageDefinitionEntity>> map = new HashMap<>();
        if (definitions.isEmpty()) {
            return map;
        }
        List<UUID> ids = definitions.stream().map(PipelineDefinitionEntity::getId).toList();
        for (PipelineStageDefinitionEntity stage : stageDefinitionRepository
                .findAllByPipelineDefinitionIdInOrderByExecutionOrderAsc(ids)) {
            map.computeIfAbsent(stage.getPipelineDefinition().getId(), k -> new ArrayList<>()).add(stage);
        }
        return map;
    }

    public PipelineDefinitionDto toDto(
            PipelineDefinitionEntity entity,
            List<PipelineStageDefinitionEntity> stages
    ) {
        List<PipelineStageDefinitionDto> stageDtos = stages.stream()
                .map(this::toStageDto)
                .toList();
        return new PipelineDefinitionDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getProject().getId(),
                entity.getProject().getName(),
                entity.getLinkedRepository() != null ? entity.getLinkedRepository().getId() : null,
                entity.getLinkedRepository() != null ? entity.getLinkedRepository().getFullName() : null,
                entity.getName(),
                entity.getSlug(),
                entity.getDescription(),
                entity.isEnabled(),
                entity.getDefinitionVersion(),
                entity.getRowVersion(),
                entity.getCreatedBy() != null ? entity.getCreatedBy().getUsername() : null,
                entity.getUpdatedBy() != null ? entity.getUpdatedBy().getUsername() : null,
                stageDtos,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public PipelineStageDefinitionDto toStageDto(PipelineStageDefinitionEntity stage) {
        return new PipelineStageDefinitionDto(
                stage.getId(),
                stage.getStageKey(),
                stage.getDisplayName(),
                stage.getStageType(),
                stage.getExecutionOrder(),
                readStringList(stage.getDependsOnJson()),
                stage.getTimeoutSeconds(),
                stage.getMaxRetries(),
                stage.isAllowFailure(),
                readStringMap(stage.getMetadataJson())
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize pipeline stage definition payload");
        }
    }

    List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    Map<String, String> readStringMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
