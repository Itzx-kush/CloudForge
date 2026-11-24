package com.cloudforge.backend.orchestration.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
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
import com.cloudforge.backend.secrets.dto.StageSecretBindingDto;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.PipelineRunSecretReferenceEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.secrets.repository.PipelineRunSecretReferenceRepository;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
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
    private final StageExecutionAttemptRepository attemptRepository;
    private final PipelineIdempotencyService idempotencyService;
    private final StageDagValidator dagValidator;
    private final PipelineDefinitionService definitionService;
    private final EnvironmentSecretRepository secretRepository;
    private final EnvironmentSecretVersionRepository secretVersionRepository;
    private final PipelineRunSecretReferenceRepository secretReferenceRepository;
    private final SecretAuditService secretAuditService;
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
            StageExecutionAttemptRepository attemptRepository,
            PipelineIdempotencyService idempotencyService,
            StageDagValidator dagValidator,
            PipelineDefinitionService definitionService,
            EnvironmentSecretRepository secretRepository,
            EnvironmentSecretVersionRepository secretVersionRepository,
            PipelineRunSecretReferenceRepository secretReferenceRepository,
            SecretAuditService secretAuditService,
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
        this.attemptRepository = attemptRepository;
        this.idempotencyService = idempotencyService;
        this.dagValidator = dagValidator;
        this.definitionService = definitionService;
        this.secretRepository = secretRepository;
        this.secretVersionRepository = secretVersionRepository;
        this.secretReferenceRepository = secretReferenceRepository;
        this.secretAuditService = secretAuditService;
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

        ProjectEnvironmentEntity targetEnvironment = definition.getTargetEnvironment();
        Map<String, List<PinnedSecretReferenceSpec>> pinnedByStageKey = resolveAndValidatePinnedBindings(
                workspaceId,
                project.getId(),
                definition,
                stageDefinitions,
                targetEnvironment,
                user
        );
        boolean hasSecretBindings = !pinnedByStageKey.isEmpty();

        Instant now = Instant.now();
        int nextRunNumber = runRepository.findMaxRunNumberByDefinitionId(definition.getId()) + 1;

        String effectiveBranch = targetBranch != null ? targetBranch : project.getDefaultBranch();
        Map<String, List<StageSecretBindingDto>> pinnedDtoMap = toPinnedBindingDtoMap(pinnedByStageKey);
        String snapshotJson = buildImmutableSnapshotJson(
                definition, project.getName(), validatedGraph, effectiveBranch, commitSha, targetEnvironment, pinnedDtoMap
        );

        PipelineRunEntity run = new PipelineRunEntity();
        run.setWorkspace(workspace);
        run.setProject(project);
        run.setPipelineDefinition(definition);
        run.setLinkedRepository(definition.getLinkedRepository());
        run.setTargetEnvironment(targetEnvironment);
        run.setHasSecretBindings(hasSecretBindings);
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
            stageRun.setMetadataJson(writeJson(spec.metadata()));
            stageRun.setAllowedOperation(
                    AllowedWorkerOperation.resolveForStage(spec.stageType(), spec.metadata())
                            .map(Enum::name)
                            .orElse(null)
            );
            List<StageSecretBindingDto> stagePinnedDtos = pinnedDtoMap.getOrDefault(spec.stageKey(), List.of());
            stageRun.setSecretBindingsJson(writeJson(stagePinnedDtos));
            stageRun.setHasSecretBindings(!stagePinnedDtos.isEmpty());
            stageRun.setTimeoutSeconds(spec.timeoutSeconds());
            stageRun.setMaxRetries(spec.maxRetries());
            stageRun.setAllowFailure(spec.allowFailure());
            stageRun.setInitialStatusReason("Waiting for upstream dependencies");
            PipelineStageRunEntity savedStage = stageRunRepository.save(stageRun);
            stageRuns.add(savedStage);
            stageRunsByKey.put(savedStage.getStageKey(), savedStage);
        }
        stageRunRepository.flush();

        if (hasSecretBindings) {
            List<PipelineRunSecretReferenceEntity> refEntities = new ArrayList<>();
            for (Map.Entry<String, List<PinnedSecretReferenceSpec>> entry : pinnedByStageKey.entrySet()) {
                PipelineStageRunEntity stageRun = stageRunsByKey.get(entry.getKey());
                for (PinnedSecretReferenceSpec pinned : entry.getValue()) {
                    PipelineRunSecretReferenceEntity ref = new PipelineRunSecretReferenceEntity();
                    ref.setWorkspace(workspace);
                    ref.setProject(project);
                    ref.setPipelineRun(savedRun);
                    ref.setStageRun(stageRun);
                    ref.setEnvironment(targetEnvironment);
                    ref.setSecret(pinned.secret());
                    ref.setSecretVersion(pinned.version());
                    ref.setSecretName(pinned.secret().getSecretName());
                    ref.setDeliveryAlias(pinned.bindingDto().deliveryAlias());
                    ref.setPinnedVersionNumber(pinned.version().getVersionNumber());
                    refEntities.add(ref);
                }
            }
            secretReferenceRepository.saveAll(refEntities);
            secretReferenceRepository.flush();
        }

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
        // Reject cancelling already terminal SUCCEEDED/FAILED runs via state machine.
        if (run.getStatus().isTerminal()) {
            run.transitionStatus(PipelineRunStatus.CANCELLED, Instant.now(), "Cannot cancel terminal run", user);
        }

        Instant now = Instant.now();
        String userReason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Cancelled by @" + user.getUsername();

        List<PipelineStageRunEntity> runningStages = stageRuns.stream()
                .filter(s -> s.getStatus() == PipelineStageStatus.RUNNING)
                .toList();

        // Cancel all unstarted (PENDING / READY) stages immediately so scheduler cannot claim them.
        int cancelledUnstartedCount = 0;
        for (PipelineStageRunEntity stage : stageRuns) {
            if (stage.getStatus() == PipelineStageStatus.PENDING || stage.getStatus() == PipelineStageStatus.READY) {
                PipelineStageStatus prevStage = stage.transitionStatus(
                        PipelineStageStatus.CANCELLED,
                        now,
                        "Cancelled because parent pipeline run cancellation was requested"
                );
                stageRunRepository.save(stage);
                cancelledUnstartedCount++;
                recordEvent(
                        workspace,
                        run,
                        stage,
                        "STAGE_CANCELLED",
                        prevStage.name(),
                        PipelineStageStatus.CANCELLED.name(),
                        user,
                        "Unstarted stage cancelled with parent pipeline run",
                        Map.of("stageType", stage.getStageType().name()),
                        now
                );
            }
        }

        if (!runningStages.isEmpty()) {
            // Active isolated worker execution is in flight: persist cancellation intent and signal
            // active worker leases via heartbeat. Do NOT claim final CANCELLED until termination is confirmed.
            boolean firstCancelRequest = run.getCancellationRequestedAt() == null;
            run.recordCancellationRequest(now, userReason, user);
            runRepository.save(run);

            int signaledAttempts = 0;
            for (PipelineStageRunEntity runningStage : runningStages) {
                runningStage.recordCancellationRequest(now);
                stageRunRepository.save(runningStage);
                List<StageExecutionAttemptEntity> activeAttempts = attemptRepository.findAllByStageRunIdAndStatusIn(
                        runningStage.getId(),
                        List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING)
                );
                for (StageExecutionAttemptEntity attempt : activeAttempts) {
                    if (attempt.getCancellationRequestedAt() == null) {
                        attempt.setCancellationRequestedAt(now);
                        attemptRepository.save(attempt);
                        signaledAttempts++;
                    }
                }
            }

            if (firstCancelRequest) {
                recordEvent(
                        workspace,
                        run,
                        null,
                        "RUN_CANCELLATION_REQUESTED",
                        run.getStatus().name(),
                        run.getStatus().name(),
                        user,
                        "Cancellation requested (" + userReason + "); awaiting confirmed worker termination for "
                                + runningStages.size() + " active stage(s)",
                        Map.of(
                                "activeRunningStages", runningStages.size(),
                                "cancelledUnstartedStages", cancelledUnstartedCount,
                                "signaledAttempts", signaledAttempts
                        ),
                        now
                );
                auditService.record(
                        workspace,
                        user,
                        "PIPELINE_RUN_CANCELLATION_REQUESTED",
                        "PIPELINE_RUN",
                        run.getId().toString(),
                        "Requested cancellation of running pipeline #" + run.getRunNumber() + " for \"" + run.getDefinitionName() + "\"",
                        Map.of(
                                "activeRunningStages", runningStages.size(),
                                "cancelledUnstartedStages", cancelledUnstartedCount,
                                "runNumber", run.getRunNumber()
                        )
                );
            }
            runRepository.flush();
            stageRunRepository.flush();
            attemptRepository.flush();
            return loadRunDto(workspaceId, run.getId());
        }

        // No active worker execution in flight: immediately transition run to terminal CANCELLED.
        run.recordCancellationRequest(now, userReason, user);
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
                        "cancelledStages", cancelledUnstartedCount,
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
            String commitSha,
            ProjectEnvironmentEntity targetEnvironment,
            Map<String, List<StageSecretBindingDto>> pinnedBindingsByStage
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
        if (targetEnvironment != null) {
            snapshot.put("targetEnvironmentId", targetEnvironment.getId().toString());
            snapshot.put("targetEnvironmentName", targetEnvironment.getName());
            snapshot.put("targetEnvironmentType", targetEnvironment.getEnvironmentType().name());
        }
        snapshot.put("targetBranch", targetBranch);
        snapshot.put("commitSha", commitSha);
        snapshot.put("stages", graph.orderedStages());
        if (pinnedBindingsByStage != null && !pinnedBindingsByStage.isEmpty()) {
            snapshot.put("secretBindingsByStage", pinnedBindingsByStage);
        }
        return writeJson(snapshot);
    }

    private Map<String, List<PinnedSecretReferenceSpec>> resolveAndValidatePinnedBindings(
            UUID workspaceId,
            UUID projectId,
            PipelineDefinitionEntity definition,
            List<PipelineStageDefinitionEntity> stageDefinitions,
            ProjectEnvironmentEntity targetEnvironment,
            UserEntity user
    ) {
        Map<String, List<StageSecretBindingDto>> rawByStage = new LinkedHashMap<>();
        int total = 0;
        for (PipelineStageDefinitionEntity stageDef : stageDefinitions) {
            List<StageSecretBindingDto> stageBindings = definitionService.readSecretBindings(stageDef.getSecretBindingsJson());
            if (!stageBindings.isEmpty()) {
                rawByStage.put(stageDef.getStageKey(), stageBindings);
                total += stageBindings.size();
            }
        }
        if (total == 0) {
            return Map.of();
        }

        try {
            authorizationService.requirePipelineRunTriggerAccess(workspaceId, user.getId(), true);
        } catch (ForbiddenOperationException ex) {
            secretAuditService.recordDeniedAccess(
                    workspaceId,
                    user,
                    "PIPELINE_RUN",
                    definition.getId().toString(),
                    user.getUsername(),
                    "trigger_secret_bearing_pipeline",
                    Map.of(
                            "projectId", projectId.toString(),
                            "definitionId", definition.getId().toString()
                    )
            );
            throw ex;
        }

        if (targetEnvironment == null || !targetEnvironment.getProject().getId().equals(projectId)) {
            throw new ConflictException(
                    "Pipeline definition has secret bindings without a valid target environment in this project"
            );
        }

        Map<String, List<PinnedSecretReferenceSpec>> pinnedByStage = new LinkedHashMap<>();
        for (PipelineStageDefinitionEntity stageDef : stageDefinitions) {
            List<StageSecretBindingDto> bindings = rawByStage.get(stageDef.getStageKey());
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            Map<String, String> meta = definitionService.readStringMap(stageDef.getMetadataJson());
            if (AllowedWorkerOperation.resolveForStage(stageDef.getStageType(), meta).isEmpty()) {
                throw new ConflictException(
                        "Stage \"" + stageDef.getStageKey() + "\" cannot execute with secrets because it has no approved worker operation"
                );
            }

            List<PinnedSecretReferenceSpec> pinnedStage = new ArrayList<>(bindings.size());
            for (StageSecretBindingDto binding : bindings) {
                EnvironmentSecretEntity secret = secretRepository
                        .findByScopedIdWithDetails(
                                binding.secretId(), targetEnvironment.getId(), projectId, workspaceId
                        )
                        .orElseThrow(() -> new ConflictException(
                                "Bound secret \"" + binding.secretName() + "\" no longer exists in environment \""
                                        + targetEnvironment.getName() + "\""
                        ));
                if (secret.getStatus() != SecretLifecycleStatus.ACTIVE || secret.getCurrentVersionId() == null) {
                    throw new ConflictException(
                            "Bound secret \"" + secret.getSecretName() + "\" is " + secret.getStatus()
                                    + " and cannot be queued"
                    );
                }
                EnvironmentSecretVersionEntity version = secretVersionRepository
                        .findByIdAndSecretIdAndWorkspaceId(secret.getCurrentVersionId(), secret.getId(), workspaceId)
                        .orElseThrow(() -> new ConflictException(
                                "Current active version for secret \"" + secret.getSecretName() + "\" is missing"
                        ));
                if (version.getStatus() != SecretVersionStatus.ACTIVE) {
                    throw new ConflictException(
                            "Current version v" + version.getVersionNumber() + " of secret \""
                                    + secret.getSecretName() + "\" is revoked"
                    );
                }

                StageSecretBindingDto pinnedDto = new StageSecretBindingDto(
                        secret.getId(),
                        secret.getSecretName(),
                        binding.deliveryAlias(),
                        targetEnvironment.getId(),
                        version.getVersionNumber()
                );
                pinnedStage.add(new PinnedSecretReferenceSpec(secret, version, pinnedDto));
            }
            pinnedByStage.put(stageDef.getStageKey(), pinnedStage);
        }
        return pinnedByStage;
    }

    private Map<String, List<StageSecretBindingDto>> toPinnedBindingDtoMap(
            Map<String, List<PinnedSecretReferenceSpec>> pinnedByStageKey
    ) {
        if (pinnedByStageKey.isEmpty()) {
            return Map.of();
        }
        Map<String, List<StageSecretBindingDto>> dtoMap = new LinkedHashMap<>();
        pinnedByStageKey.forEach((key, list) ->
                dtoMap.put(key, list.stream().map(PinnedSecretReferenceSpec::bindingDto).toList())
        );
        return dtoMap;
    }

    private record PinnedSecretReferenceSpec(
            EnvironmentSecretEntity secret,
            EnvironmentSecretVersionEntity version,
            StageSecretBindingDto bindingDto
    ) {
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
                .map(s -> {
                    boolean supported = StringUtils.hasText(s.getAllowedOperation())
                            && AllowedWorkerOperation.parseStrict(s.getAllowedOperation())
                            .map(op -> op.supportsStageType(s.getStageType()))
                            .orElse(false);
                    String capabilityMsg = supported
                            ? null
                            : AllowedWorkerOperation.unsupportedCapabilityMessage(s.getStageType(), s.getAllowedOperation());
                    return new PipelineStageRunDto(
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
                            s.getAllowedOperation(),
                            supported,
                            capabilityMsg,
                            s.isHasSecretBindings(),
                            definitionService.readSecretBindings(s.getSecretBindingsJson()),
                            s.getAssignedWorkerId(),
                            s.getLeaseExpiresAt(),
                            s.getNextRetryAt(),
                            s.getCancellationRequestedAt(),
                            s.getReadyAt(),
                            s.getStartedAt(),
                            s.getCompletedAt()
                    );
                })
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
                run.getTargetEnvironment() != null ? run.getTargetEnvironment().getId() : null,
                run.getTargetEnvironment() != null ? run.getTargetEnvironment().getName() : null,
                run.getTargetEnvironment() != null ? run.getTargetEnvironment().getEnvironmentType() : null,
                run.isHasSecretBindings(),
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
                run.getCancellationRequestedAt(),
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
