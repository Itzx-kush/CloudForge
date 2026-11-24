package com.cloudforge.backend.orchestration.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.PipelineRunDto;
import com.cloudforge.backend.orchestration.dto.PipelineRunEventDto;
import com.cloudforge.backend.orchestration.dto.PipelineStageRunDto;
import com.cloudforge.backend.orchestration.engine.StageDagValidator;
import com.cloudforge.backend.orchestration.engine.StageDependencyEvaluator;
import com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract;
import com.cloudforge.backend.orchestration.entity.PipelineDefinitionEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageDefinitionEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineDefinitionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageDefinitionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core CloudForge Phase 3.2 pipeline orchestration engine.
 *
 * <p>Persists validated execution intents, immutable definition snapshots, DAG stage
 * graphs, lifecycle event history, idempotency guarantees, and safe cancellation.
 * Deliberately never executes builds, containers, or repository scripts.</p>
 */
@Service
public class PipelineOrchestrationService implements WorkerAssignmentContract {

    public static final String QUEUED_AWAITING_WORKERS_REASON =
            "Queued — Awaiting execution infrastructure (Phase 3.3 isolated build workers)";

    private static final Pattern VALID_BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Pattern VALID_SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    private final WorkspaceAuthorizationService authorizationService;
    private final ProjectRepository projectRepository;
    private final PipelineDefinitionRepository definitionRepository;
    private final PipelineStageDefinitionRepository stageDefinitionRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final PipelineRunEventRepository runEventRepository;
    private final PipelineIdempotencyService idempotencyService;
    private final StageDagValidator dagValidator;
    private final PipelineDefinitionService definitionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PipelineOrchestrationService(
            WorkspaceAuthorizationService authorizationService,
            ProjectRepository projectRepository,
            PipelineDefinitionRepository definitionRepository,
            PipelineStageDefinitionRepository stageDefinitionRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            PipelineRunEventRepository runEventRepository,
            PipelineIdempotencyService idempotencyService,
            StageDagValidator dagValidator,
            PipelineDefinitionService definitionService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.projectRepository = projectRepository;
        this.definitionRepository = definitionRepository;
        this.stageDefinitionRepository = stageDefinitionRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.runEventRepository = runEventRepository;
        this.idempotencyService = idempotencyService;
        this.dagValidator = dagValidator;
        this.definitionService = definitionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatePipelineRunResult createRun(
            UUID workspaceId,
            CreatePipelineRunRequest request,
            String headerIdempotencyKey,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        String targetBranch = validateTargetBranch(request.targetBranch());
        String commitSha = validateCommitSha(request.commitSha());
        PipelineTriggerType triggerType = resolveUserTriggerType(request.triggerType());

        String rawKey = StringUtils.hasText(headerIdempotencyKey)
                ? headerIdempotencyKey
                : request.idempotencyKey();
        String normalizedKey = idempotencyService.normalizeAndValidateKey(rawKey);

        UUID claimedIdempotencyRecordId = null;
        if (normalizedKey != null) {
            String fingerprint = idempotencyService.computeRunRequestFingerprint(
                    workspaceId,
                    request.definitionId(),
                    targetBranch,
                    commitSha,
                    triggerType.name()
            );
            PipelineIdempotencyService.ClaimOutcome outcome = idempotencyService.claimOrResolve(
                    workspaceId,
                    user.getId(),
                    PipelineIdempotencyService.OPERATION_CREATE_RUN,
                    normalizedKey,
                    fingerprint
            );
            if (outcome.replay()) {
                PipelineRunDto existingDto = loadRunDto(workspaceId, outcome.existingRunId());
                return new CreatePipelineRunResult(existingDto, true);
            }
            claimedIdempotencyRecordId = outcome.claimedRecordId();
        }

        // Serialize concurrent run creations on the definition row to keep run_number monotonic
        // and guarantee snapshot consistency against concurrent definition updates.
        PipelineDefinitionEntity definition = definitionRepository
                .findByIdAndWorkspaceIdForUpdate(request.definitionId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline definition not found in this workspace"));

        if (!definition.isEnabled()) {
            throw new ConflictException(
                    "Pipeline definition \"" + definition.getName() + "\" is disabled and cannot be queued"
            );
        }

        // Verify project belongs to this workspace
        var project = projectRepository.findByIdAndWorkspaceId(definition.getProject().getId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));

        List<PipelineStageDefinitionEntity> stageDefinitions = stageDefinitionRepository
                .findAllByPipelineDefinitionIdOrderByExecutionOrderAsc(definition.getId());
        StageDagValidator.ValidatedStageGraph validatedGraph = validateStoredStages(stageDefinitions);

        Instant now = Instant.now();
        int nextRunNumber = runRepository.findMaxRunNumberByDefinitionId(definition.getId()) + 1;

        String effectiveBranch = targetBranch != null ? targetBranch : project.getDefaultBranch();
        String snapshotJson = buildImmutableSnapshotJson(definition, project.getName(), validatedGraph, effectiveBranch, commitSha);

        PipelineRunEntity run = new PipelineRunEntity();
        run.setWorkspace(workspace);
        run.setProject(project);
        run.setPipelineDefinition(definition);
        run.setLinkedRepository(definition.getLinkedRepository());
        run.setRunNumber(nextRunNumber);
        run.setDefinitionName(definition.getName());
        run.setDefinitionVersion(definition.getDefinitionVersion());
        run.setDefinitionSnapshotJson(snapshotJson);
        run.setTriggerType(triggerType);
        run.setTargetBranch(effectiveBranch);
        run.setCommitSha(commitSha);
        run.setInitialStatusReason(QUEUED_AWAITING_WORKERS_REASON);
        run.setRequestedBy(user);
        run.setQueuedAt(now);

        PipelineRunEntity savedRun = runRepository.saveAndFlush(run);

        // Create stage run records initially PENDING
        List<PipelineStageRunEntity> stageRuns = new ArrayList<>(validatedGraph.orderedStages().size());
        Map<String, PipelineStageRunEntity> stageRunsByKey = new LinkedHashMap<>();
        for (StageDagValidator.ValidatedStageSpec spec : validatedGraph.orderedStages()) {
            PipelineStageRunEntity stageRun = new PipelineStageRunEntity();
            stageRun.setWorkspace(workspace);
            stageRun.setPipelineRun(savedRun);
            stageRun.setStageKey(spec.stageKey());
            stageRun.setDisplayName(spec.displayName());
            stageRun.setStageType(spec.stageType());
            stageRun.setExecutionOrder(spec.executionOrder());
            stageRun.setDependsOnJson(writeJson(spec.dependsOn()));
            stageRun.setTimeoutSeconds(spec.timeoutSeconds());
            stageRun.setMaxRetries(spec.maxRetries());
            stageRun.setAllowFailure(spec.allowFailure());
            stageRun.setInitialStatusReason("Waiting for upstream dependencies");
            PipelineStageRunEntity savedStage = stageRunRepository.save(stageRun);
            stageRuns.add(savedStage);
            stageRunsByKey.put(savedStage.getStageKey(), savedStage);
        }
        stageRunRepository.flush();

        // Record initial RUN_QUEUED event
        recordEvent(
                workspace,
                savedRun,
                null,
                "RUN_QUEUED",
                null,
                PipelineRunStatus.QUEUED.name(),
                user,
                QUEUED_AWAITING_WORKERS_REASON,
                Map.of(
                        "definitionId", definition.getId().toString(),
                        "definitionVersion", definition.getDefinitionVersion(),
                        "stageCount", stageRuns.size(),
                        "triggerType", triggerType.name()
                ),
                now
        );

        // Deterministically promote root stages (no predecessors) from PENDING -> READY
        // without marking any stage RUNNING or SUCCEEDED.
        List<StageDependencyEvaluator.StageNodeState> nodeStates = stageRuns.stream()
                .map(s -> new StageDependencyEvaluator.StageNodeState(
                        s.getStageKey(),
                        s.getStatus(),
                        definitionService.readStringList(s.getDependsOnJson()),
                        s.isAllowFailure()
                ))
                .toList();

        List<StageDependencyEvaluator.StageTransitionDecision> initialDecisions =
                StageDependencyEvaluator.evaluateProgressions(nodeStates);
        for (StageDependencyEvaluator.StageTransitionDecision decision : initialDecisions) {
            PipelineStageRunEntity stage = stageRunsByKey.get(decision.stageKey());
            if (stage != null) {
                stage.transitionStatus(decision.toStatus(), now, decision.reason());
                stageRunRepository.save(stage);
                recordEvent(
                        workspace,
                        savedRun,
                        stage,
                        "STAGE_" + decision.toStatus().name(),
                        decision.fromStatus().name(),
                        decision.toStatus().name(),
                        user,
                        decision.reason(),
                        Map.of("stageType", stage.getStageType().name()),
                        now
                );
            }
        }
        stageRunRepository.flush();

        if (claimedIdempotencyRecordId != null) {
            idempotencyService.attachRun(claimedIdempotencyRecordId, savedRun.getId());
        }

        auditService.record(
                workspace,
                user,
                "PIPELINE_RUN_REQUESTED",
                "PIPELINE_RUN",
                savedRun.getId().toString(),
                "Queued pipeline run #" + savedRun.getRunNumber() + " for \"" + savedRun.getDefinitionName()
                        + "\" (v" + savedRun.getDefinitionVersion() + ")",
                Map.of(
                        "projectId", project.getId().toString(),
                        "definitionId", definition.getId().toString(),
                        "definitionVersion", savedRun.getDefinitionVersion(),
                        "runNumber", savedRun.getRunNumber(),
                        "status", savedRun.getStatus().name(),
                        "stageCount", stageRuns.size()
                )
        );

        return new CreatePipelineRunResult(toRunDto(savedRun, stageRuns), false);
    }

    @Transactional(readOnly = true)
    public PagedResponse<PipelineRunDto> listRuns(
            UUID workspaceId,
            UUID projectId,
            UUID definitionId,
            PipelineRunStatus status,
            String query,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        }
        if (definitionId != null) {
            definitionRepository.findByIdAndWorkspaceIdWithDetails(definitionId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Pipeline definition not found in this workspace"));
        }

        Page<PipelineRunEntity> page = runRepository.searchByWorkspace(
                workspaceId,
                projectId,
                definitionId,
                status,
                StringUtils.hasText(query) ? query.trim() : null,
                pageable
        );

        Map<UUID, List<PipelineStageRunEntity>> stagesByRunId = loadStagesByRunIds(page.getContent());
        return PagedResponse.from(page, run -> toRunDto(run, stagesByRunId.getOrDefault(run.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public PipelineRunDto getRun(UUID workspaceId, UUID runId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        return loadRunDto(workspaceId, runId);
    }

    @Transactional(readOnly = true)
    public List<PipelineRunEventDto> listRunEvents(UUID workspaceId, UUID runId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        runRepository.findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found in this workspace"));

        return runEventRepository
                .findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(runId, workspaceId)
                .stream()
                .map(this::toEventDto)
                .toList();
    }

    @Transactional
    public PipelineRunDto cancelRun(
            UUID workspaceId,
            UUID runId,
            CancelPipelineRunRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        // Pessimistic write lock serializes concurrent cancellations & future worker state updates.
        PipelineRunEntity run = runRepository.findByIdAndWorkspaceIdForUpdate(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found in this workspace"));

        List<PipelineStageRunEntity> stageRuns = stageRunRepository
                .findAllByPipelineRunIdOrderByExecutionOrderAsc(run.getId());

        // Safe idempotent duplicate cancellation: if already CANCELLED, return existing state cleanly.
        if (run.getStatus() == PipelineRunStatus.CANCELLED) {
            return loadRunDto(workspaceId, run.getId());
        }

        Instant now = Instant.now();
        String userReason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Cancelled by @" + user.getUsername() + " (orchestration state cancelled prior to worker execution)";

        PipelineRunStatus previousStatus = run.transitionStatus(
                PipelineRunStatus.CANCELLED,
                now,
                userReason,
                user
        );
        runRepository.save(run);

        recordEvent(
                workspace,
                run,
                null,
                "RUN_CANCELLED",
                previousStatus.name(),
                PipelineRunStatus.CANCELLED.name(),
                user,
                userReason,
                Map.of("cancelledBeforeWorkerExecution", previousStatus == PipelineRunStatus.QUEUED),
                now
        );

        int cancelledStageCount = 0;
        for (PipelineStageRunEntity stage : stageRuns) {
            if (!stage.getStatus().isTerminal()) {
                PipelineStageStatus prevStage = stage.transitionStatus(
                        PipelineStageStatus.CANCELLED,
                        now,
                        "Cancelled because parent pipeline run was cancelled"
                );
                stageRunRepository.save(stage);
                cancelledStageCount++;
                recordEvent(
                        workspace,
                        run,
                        stage,
                        "STAGE_CANCELLED",
                        prevStage.name(),
                        PipelineStageStatus.CANCELLED.name(),
                        user,
                        "Stage cancelled with parent pipeline run",
                        Map.of("stageType", stage.getStageType().name()),
                        now
                );
            }
        }
        runRepository.flush();
        stageRunRepository.flush();

        auditService.record(
                workspace,
                user,
                "PIPELINE_RUN_CANCELLED",
                "PIPELINE_RUN",
                run.getId().toString(),
                "Cancelled pipeline run #" + run.getRunNumber() + " for \"" + run.getDefinitionName() + "\"",
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "cancelledStages", cancelledStageCount,
                        "runNumber", run.getRunNumber()
                )
        );

        return loadRunDto(workspaceId, run.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StageDispatchDescriptor> listReadyStageTickets(UUID workspaceId, UUID runId) {
        PipelineRunEntity run = runRepository.findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found in this workspace"));
        if (run.getStatus().isTerminal()) {
            return List.of();
        }
        String repoFullName = run.getLinkedRepository() != null ? run.getLinkedRepository().getFullName() : null;
        return stageRunRepository
                .findAllByWorkspaceIdAndPipelineRunIdAndStatusOrderByExecutionOrderAsc(
                        workspaceId,
                        runId,
                        PipelineStageStatus.READY
                )
                .stream()
                .map(stage -> new StageDispatchDescriptor(
                        workspaceId,
                        run.getProject().getId(),
                        run.getId(),
                        stage.getId(),
                        stage.getStageKey(),
                        stage.getDisplayName(),
                        stage.getStageType(),
                        stage.getExecutionOrder(),
                        run.getDefinitionVersion(),
                        repoFullName,
                        run.getTargetBranch(),
                        run.getCommitSha(),
                        stage.getTimeoutSeconds(),
                        stage.getMaxRetries(),
                        stage.getAttemptNumber()
                ))
                .toList();
    }

    private PipelineRunDto loadRunDto(UUID workspaceId, UUID runId) {
        PipelineRunEntity run = runRepository.findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found in this workspace"));
        List<PipelineStageRunEntity> stageRuns = stageRunRepository
                .findAllByPipelineRunIdOrderByExecutionOrderAsc(run.getId());
        return toRunDto(run, stageRuns);
    }

    private StageDagValidator.ValidatedStageGraph validateStoredStages(
            List<PipelineStageDefinitionEntity> stageDefs
    ) {
        List<StageDagValidator.StageSpecInput> inputs = stageDefs.stream()
                .map(s -> new StageDagValidator.StageSpecInput(
                        s.getStageKey(),
                        s.getDisplayName(),
                        s.getStageType(),
                        definitionService.readStringList(s.getDependsOnJson()),
                        s.getTimeoutSeconds(),
                        s.getMaxRetries(),
                        s.isAllowFailure(),
                        definitionService.readStringMap(s.getMetadataJson())
                ))
                .toList();
        return dagValidator.validateAndSort(inputs);
    }

    private String buildImmutableSnapshotJson(
            PipelineDefinitionEntity definition,
            String projectName,
            StageDagValidator.ValidatedStageGraph graph,
            String targetBranch,
            String commitSha
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("definitionId", definition.getId().toString());
        snapshot.put("definitionName", definition.getName());
        snapshot.put("definitionVersion", definition.getDefinitionVersion());
        snapshot.put("projectId", definition.getProject().getId().toString());
        snapshot.put("projectName", projectName);
        if (definition.getLinkedRepository() != null) {
            snapshot.put("linkedRepositoryId", definition.getLinkedRepository().getId().toString());
            snapshot.put("repositoryFullName", definition.getLinkedRepository().getFullName());
        }
        snapshot.put("targetBranch", targetBranch);
        snapshot.put("commitSha", commitSha);
        snapshot.put("stages", graph.orderedStages());
        return writeJson(snapshot);
    }

    private void recordEvent(
            WorkspaceEntity workspace,
            PipelineRunEntity run,
            PipelineStageRunEntity stageRun,
            String eventType,
            String fromStatus,
            String toStatus,
            UserEntity actor,
            String message,
            Map<String, Object> metadata,
            Instant occurredAt
    ) {
        PipelineRunEventEntity event = new PipelineRunEventEntity();
        event.setWorkspace(workspace);
        event.setPipelineRun(run);
        event.setStageRun(stageRun);
        event.setStageKey(stageRun != null ? stageRun.getStageKey() : null);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setActor(actor);
        event.setActorUsername(actor != null ? actor.getUsername() : "system");
        event.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        event.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        event.setOccurredAt(occurredAt);
        runEventRepository.save(event);
    }

    private Map<UUID, List<PipelineStageRunEntity>> loadStagesByRunIds(List<PipelineRunEntity> runs) {
        Map<UUID, List<PipelineStageRunEntity>> map = new HashMap<>();
        if (runs.isEmpty()) {
            return map;
        }
        List<UUID> ids = runs.stream().map(PipelineRunEntity::getId).toList();
        for (PipelineStageRunEntity stage : stageRunRepository
                .findAllByPipelineRunIdInOrderByExecutionOrderAsc(ids)) {
            map.computeIfAbsent(stage.getPipelineRun().getId(), k -> new ArrayList<>()).add(stage);
        }
        return map;
    }

    private PipelineRunDto toRunDto(PipelineRunEntity run, List<PipelineStageRunEntity> stageRuns) {
        List<PipelineStageRunDto> stageDtos = stageRuns.stream()
                .map(s -> new PipelineStageRunDto(
                        s.getId(),
                        s.getStageKey(),
                        s.getDisplayName(),
                        s.getStageType(),
                        s.getExecutionOrder(),
                        definitionService.readStringList(s.getDependsOnJson()),
                        s.getTimeoutSeconds(),
                        s.getMaxRetries(),
                        s.getAttemptNumber(),
                        s.isAllowFailure(),
                        s.getStatus(),
                        s.getStatusReason(),
                        s.getReadyAt(),
                        s.getStartedAt(),
                        s.getCompletedAt()
                ))
                .toList();

        return new PipelineRunDto(
                run.getId(),
                run.getWorkspace().getId(),
                run.getProject().getId(),
                run.getProject().getName(),
                run.getPipelineDefinition() != null ? run.getPipelineDefinition().getId() : null,
                run.getDefinitionName(),
                run.getDefinitionVersion(),
                run.getLinkedRepository() != null ? run.getLinkedRepository().getId() : null,
                run.getLinkedRepository() != null ? run.getLinkedRepository().getFullName() : null,
                run.getRunNumber(),
                run.getTriggerType(),
                run.getTargetBranch(),
                run.getCommitSha(),
                run.getStatus(),
                run.getStatusReason(),
                run.getRequestedBy() != null ? run.getRequestedBy().getUsername() : null,
                run.getCancelledBy() != null ? run.getCancelledBy().getUsername() : null,
                run.getQueuedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCancelledAt(),
                run.getRowVersion(),
                stageDtos,
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    private PipelineRunEventDto toEventDto(PipelineRunEventEntity event) {
        return new PipelineRunEventDto(
                event.getId(),
                event.getPipelineRun().getId(),
                event.getStageRun() != null ? event.getStageRun().getId() : null,
                event.getStageKey(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getActorUsername(),
                event.getMessage(),
                readObjectMap(event.getMetadataJson()),
                event.getOccurredAt()
        );
    }

    private String validateTargetBranch(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (!VALID_BRANCH_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Invalid target branch",
                    Map.of("targetBranch", "Branch name may only contain letters, digits, dots, underscores, slashes, and hyphens")
            );
        }
        return trimmed;
    }

    private String validateCommitSha(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (!VALID_SHA_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Invalid commit SHA",
                    Map.of("commitSha", "Commit SHA must be 7-40 hexadecimal characters")
            );
        }
        return trimmed.toLowerCase();
    }

    private PipelineTriggerType resolveUserTriggerType(PipelineTriggerType requested) {
        if (requested == null) {
            return PipelineTriggerType.MANUAL;
        }
        if (requested != PipelineTriggerType.MANUAL && requested != PipelineTriggerType.API) {
            throw new BadRequestException(
                    "Only MANUAL or API trigger types can be requested through this endpoint",
                    Map.of("triggerType", "Allowed values: MANUAL, API")
            );
        }
        return requested;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize pipeline orchestration metadata");
        }
    }

    private Map<String, Object> readObjectMap(String json) {
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

    public record CreatePipelineRunResult(PipelineRunDto run, boolean idempotentReplay) {
    }
}
