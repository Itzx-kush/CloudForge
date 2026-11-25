package com.cloudforge.backend.orchestration.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentConfigRepository;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentLifecycleService;
import com.cloudforge.backend.oci.service.BuildRequestValidator;
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
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.dto.StageSecretBindingDto;
import com.cloudforge.backend.secrets.dto.StageSecretBindingInput;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.secrets.service.SecretManagementService;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.SlugUtils;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PipelineDefinitionService {

    private final WorkspaceAuthorizationService authorizationService;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final EnvironmentSecretRepository secretRepository;
    private final SecretManagementProperties secretManagementProperties;
    private final SecretManagementService secretManagementService;
    private final SecretAuditService secretAuditService;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final PipelineDefinitionRepository definitionRepository;
    private final PipelineStageDefinitionRepository stageDefinitionRepository;
    private final StageDagValidator dagValidator;
    private final BuildRequestValidator buildRequestValidator;
    private final KubernetesDeploymentConfigRepository k8sConfigRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PipelineDefinitionService(
            WorkspaceAuthorizationService authorizationService,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            EnvironmentSecretRepository secretRepository,
            SecretManagementProperties secretManagementProperties,
            SecretManagementService secretManagementService,
            SecretAuditService secretAuditService,
            LinkedRepositoryRepository linkedRepositoryRepository,
            PipelineDefinitionRepository definitionRepository,
            PipelineStageDefinitionRepository stageDefinitionRepository,
            StageDagValidator dagValidator,
            BuildRequestValidator buildRequestValidator,
            KubernetesDeploymentConfigRepository k8sConfigRepository,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.secretRepository = secretRepository;
        this.secretManagementProperties = secretManagementProperties;
        this.secretManagementService = secretManagementService;
        this.secretAuditService = secretAuditService;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.definitionRepository = definitionRepository;
        this.stageDefinitionRepository = stageDefinitionRepository;
        this.dagValidator = dagValidator;
        this.buildRequestValidator = buildRequestValidator;
        this.k8sConfigRepository = k8sConfigRepository;
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
        ProjectEnvironmentEntity targetEnvironment = resolveTargetEnvironment(project.getId(), request.targetEnvironmentId());
        StageDagValidator.ValidatedStageGraph validatedGraph = validateStageRequests(request.stages());
        validateOciStageConfigurations(workspaceId, project.getId(), validatedGraph);
        Map<String, List<StageSecretBindingDto>> resolvedBindingsByStage = validateAndResolveSecretBindings(
                workspaceId,
                project,
                targetEnvironment,
                request.stages(),
                validatedGraph,
                user
        );

        String name = request.name().trim();
        String slug = resolveUniqueSlug(project.getId(), name, null);

        PipelineDefinitionEntity definition = new PipelineDefinitionEntity();
        definition.setWorkspace(workspace);
        definition.setProject(project);
        definition.setLinkedRepository(repository);
        definition.setTargetEnvironment(targetEnvironment);
        definition.setName(name);
        definition.setSlug(slug);
        definition.setDescription(trimToNull(request.description()));
        definition.setEnabled(request.enabled() == null || request.enabled());
        definition.setDefinitionVersion(1);
        definition.setCreatedBy(user);
        definition.setUpdatedBy(user);

        PipelineDefinitionEntity saved = definitionRepository.saveAndFlush(definition);
        List<PipelineStageDefinitionEntity> savedStages = persistStages(
                saved, validatedGraph.orderedStages(), resolvedBindingsByStage
        );

        int totalBindings = resolvedBindingsByStage.values().stream().mapToInt(List::size).sum();
        if (totalBindings > 0) {
            secretAuditService.recordSecretEvent(
                    workspace,
                    user,
                    SecretAuditService.ACTION_SECRET_BINDING_UPDATED,
                    "PIPELINE_DEFINITION",
                    saved.getId().toString(),
                    "Bound " + totalBindings + " secret reference(s) to pipeline \"" + saved.getName() + "\" (env="
                            + targetEnvironment.getEnvironmentType() + ")",
                    Map.of(
                            "projectId", project.getId().toString(),
                            "environmentId", targetEnvironment.getId().toString(),
                            "environmentType", targetEnvironment.getEnvironmentType().name(),
                            "bindingCount", totalBindings,
                            "definitionVersion", saved.getDefinitionVersion()
                    )
            );
        }

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
        ProjectEnvironmentEntity targetEnvironment = resolveTargetEnvironment(project.getId(), request.targetEnvironmentId());

        StageDagValidator.ValidatedStageGraph validatedGraph = validateStageRequests(request.stages());
        validateOciStageConfigurations(workspaceId, project.getId(), validatedGraph);
        Map<String, List<StageSecretBindingDto>> resolvedBindingsByStage = validateAndResolveSecretBindings(
                workspaceId,
                project,
                targetEnvironment,
                request.stages(),
                validatedGraph,
                user
        );

        String name = request.name().trim();
        String slug = resolveUniqueSlug(project.getId(), name, definition.getId());

        definition.setName(name);
        definition.setSlug(slug);
        definition.setDescription(trimToNull(request.description()));
        definition.setLinkedRepository(repository);
        definition.setTargetEnvironment(targetEnvironment);
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
        List<PipelineStageDefinitionEntity> savedStages = persistStages(
                reloaded, validatedGraph.orderedStages(), resolvedBindingsByStage
        );

        int totalBindings = resolvedBindingsByStage.values().stream().mapToInt(List::size).sum();
        if (totalBindings > 0) {
            secretAuditService.recordSecretEvent(
                    workspace,
                    user,
                    SecretAuditService.ACTION_SECRET_BINDING_UPDATED,
                    "PIPELINE_DEFINITION",
                    reloaded.getId().toString(),
                    "Updated secret references (" + totalBindings + " binding(s)) on pipeline \"" + reloaded.getName()
                            + "\" v" + reloaded.getDefinitionVersion(),
                    Map.of(
                            "projectId", project.getId().toString(),
                            "environmentId", targetEnvironment.getId().toString(),
                            "environmentType", targetEnvironment.getEnvironmentType().name(),
                            "bindingCount", totalBindings,
                            "definitionVersion", reloaded.getDefinitionVersion()
                    )
            );
        }

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

    private void validateOciStageConfigurations(
            UUID workspaceId,
            UUID projectId,
            StageDagValidator.ValidatedStageGraph validatedGraph
    ) {
        for (StageDagValidator.ValidatedStageSpec spec : validatedGraph.orderedStages()) {
            AllowedWorkerOperation op = AllowedWorkerOperation.resolveForStage(spec.stageType(), spec.metadata()).orElse(null);
            if (op == AllowedWorkerOperation.OCI_IMAGE_BUILD) {
                buildRequestValidator.validateBuildStageConfig(workspaceId, spec.stageKey(), spec.metadata(), spec.timeoutSeconds());
            } else if (op == AllowedWorkerOperation.OCI_IMAGE_PUBLISH) {
                buildRequestValidator.validatePublishStageConfig(workspaceId, spec.stageKey(), spec.metadata());
            } else if (op == AllowedWorkerOperation.KUBERNETES_DEPLOY) {
                String rawConfigId = spec.metadata().get(KubernetesDeploymentLifecycleService.META_DEPLOYMENT_CONFIG_ID);
                if (!StringUtils.hasText(rawConfigId)) {
                    throw new BadRequestException(
                            "Stage \"" + spec.stageKey() + "\" (KUBERNETES_DEPLOY) requires a saved workspace deploymentConfigId"
                    );
                }
                UUID configId;
                try {
                    configId = UUID.fromString(rawConfigId.trim());
                } catch (IllegalArgumentException ex) {
                    throw new BadRequestException("Invalid deploymentConfigId UUID on stage \"" + spec.stageKey() + "\"");
                }
                var cfg = k8sConfigRepository.findByIdAndWorkspaceIdWithDetails(configId, workspaceId)
                        .orElseThrow(() -> new BadRequestException("Referenced Kubernetes deploymentConfigId was not found in this workspace"));
                if (!cfg.getProject().getId().equals(projectId)) {
                    throw new BadRequestException("Referenced Kubernetes deploymentConfigId belongs to a different project");
                }
            }
        }
    }

    private List<PipelineStageDefinitionEntity> persistStages(
            PipelineDefinitionEntity definition,
            List<StageDagValidator.ValidatedStageSpec> orderedStages,
            Map<String, List<StageSecretBindingDto>> resolvedBindingsByStage
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
            List<StageSecretBindingDto> stageBindings = resolvedBindingsByStage.getOrDefault(spec.stageKey(), List.of());
            entity.setSecretBindingsJson(writeJson(stageBindings));
            result.add(stageDefinitionRepository.save(entity));
        }
        stageDefinitionRepository.flush();
        return result;
    }

    private ProjectEnvironmentEntity resolveTargetEnvironment(UUID projectId, UUID targetEnvironmentId) {
        if (targetEnvironmentId == null) {
            return null;
        }
        return environmentRepository.findByIdAndProjectId(targetEnvironmentId, projectId)
                .orElseThrow(() -> new BadRequestException(
                        "Target environment does not belong to the selected pipeline project"
                ));
    }

    private Map<String, List<StageSecretBindingDto>> validateAndResolveSecretBindings(
            UUID workspaceId,
            ProjectEntity project,
            ProjectEnvironmentEntity targetEnvironment,
            List<StageDefinitionRequest> requests,
            StageDagValidator.ValidatedStageGraph validatedGraph,
            UserEntity user
    ) {
        Map<String, List<StageSecretBindingInput>> inputsByStageKey = new LinkedHashMap<>();
        int totalBindings = 0;
        for (StageDefinitionRequest req : requests) {
            if (req.secretBindings() != null && !req.secretBindings().isEmpty()) {
                String normalizedKey = req.stageKey().trim();
                inputsByStageKey.put(normalizedKey, req.secretBindings());
                totalBindings += req.secretBindings().size();
            }
        }
        if (totalBindings == 0) {
            return Map.of();
        }

        authorizationService.requireSecretBindingManagementAccess(workspaceId, user.getId());

        if (totalBindings > secretManagementProperties.getMaxBindingsPerPipeline()) {
            throw new BadRequestException(
                    "Pipeline cannot exceed " + secretManagementProperties.getMaxBindingsPerPipeline()
                            + " secret bindings across all stages"
            );
        }
        if (targetEnvironment == null) {
            throw new BadRequestException(
                    "A project targetEnvironmentId is required when binding encrypted secrets to pipeline stages"
            );
        }

        Map<String, StageDagValidator.ValidatedStageSpec> validatedByKey = validatedGraph.orderedStages().stream()
                .collect(Collectors.toMap(StageDagValidator.ValidatedStageSpec::stageKey, Function.identity()));

        Set<UUID> requestedSecretIds = new HashSet<>();
        for (Map.Entry<String, List<StageSecretBindingInput>> entry : inputsByStageKey.entrySet()) {
            StageDagValidator.ValidatedStageSpec stageSpec = validatedByKey.get(entry.getKey());
            if (stageSpec == null) {
                continue;
            }
            Optional<AllowedWorkerOperation> operation = AllowedWorkerOperation.resolveForStage(
                    stageSpec.stageType(), stageSpec.metadata()
            );
            if (operation.isEmpty()
                    || operation.get() == AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE
                    || operation.get().isOciOperation()
                    || operation.get().isKubernetesOperation()) {
                throw new BadRequestException(
                        "Stage \"" + stageSpec.stageKey() + "\" (" + stageSpec.stageType()
                                + ") cannot bind encrypted secrets without an approved Phase 3.4 worker operation"
                );
            }
            for (StageSecretBindingInput binding : entry.getValue()) {
                requestedSecretIds.add(binding.secretId());
            }
        }

        Map<UUID, EnvironmentSecretEntity> scopedSecrets = secretRepository
                .findAllByIdInAndWorkspaceIdAndProjectIdAndEnvironmentId(
                        requestedSecretIds, workspaceId, project.getId(), targetEnvironment.getId()
                )
                .stream()
                .collect(Collectors.toMap(EnvironmentSecretEntity::getId, Function.identity()));

        Map<String, List<StageSecretBindingDto>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<StageSecretBindingInput>> entry : inputsByStageKey.entrySet()) {
            String stageKey = entry.getKey();
            Set<String> seenAliases = new HashSet<>();
            Set<UUID> seenSecrets = new HashSet<>();
            List<StageSecretBindingDto> resolvedStageBindings = new ArrayList<>();

            for (StageSecretBindingInput binding : entry.getValue()) {
                String validatedAlias = secretManagementService.validateSecretName(binding.deliveryAlias());
                if (!seenAliases.add(validatedAlias)) {
                    throw new BadRequestException(
                            "Duplicate secret delivery alias \"" + validatedAlias + "\" in stage \"" + stageKey + "\""
                    );
                }
                if (!seenSecrets.add(binding.secretId())) {
                    throw new BadRequestException(
                            "Secret " + binding.secretId() + " is bound more than once on stage \"" + stageKey + "\""
                    );
                }
                EnvironmentSecretEntity secret = scopedSecrets.get(binding.secretId());
                if (secret == null || secret.getStatus() != SecretLifecycleStatus.ACTIVE) {
                    throw new BadRequestException(
                            "Referenced secret " + binding.secretId() + " must be ACTIVE within project environment \""
                                    + targetEnvironment.getName() + "\""
                    );
                }
                resolvedStageBindings.add(new StageSecretBindingDto(
                        secret.getId(),
                        secret.getSecretName(),
                        validatedAlias,
                        targetEnvironment.getId(),
                        null
                ));
            }
            resolved.put(stageKey, resolvedStageBindings);
        }
        return resolved;
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
        boolean hasSecretBindings = stageDtos.stream()
                .anyMatch(s -> s.secretBindings() != null && !s.secretBindings().isEmpty());
        return new PipelineDefinitionDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getProject().getId(),
                entity.getProject().getName(),
                entity.getLinkedRepository() != null ? entity.getLinkedRepository().getId() : null,
                entity.getLinkedRepository() != null ? entity.getLinkedRepository().getFullName() : null,
                entity.getTargetEnvironment() != null ? entity.getTargetEnvironment().getId() : null,
                entity.getTargetEnvironment() != null ? entity.getTargetEnvironment().getName() : null,
                entity.getTargetEnvironment() != null ? entity.getTargetEnvironment().getEnvironmentType() : null,
                hasSecretBindings,
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
                readStringMap(stage.getMetadataJson()),
                readSecretBindings(stage.getSecretBindingsJson())
        );
    }

    public List<StageSecretBindingDto> readSecretBindings(String json) {
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
