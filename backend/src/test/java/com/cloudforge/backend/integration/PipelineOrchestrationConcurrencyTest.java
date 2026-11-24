package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.PipelineDefinitionDto;
import com.cloudforge.backend.orchestration.dto.PipelineRunDto;
import com.cloudforge.backend.orchestration.dto.StageDefinitionRequest;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineDefinitionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.service.PipelineDefinitionService;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real multi-threaded concurrency tests (without outer @Transactional rollback)
 * verifying pessimistic locking, monotonic run numbering, concurrent idempotent
 * run creation, and concurrent cancellation consistency.
 */
@SpringBootTest
@ActiveProfiles("test")
class PipelineOrchestrationConcurrencyTest {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PipelineDefinitionService definitionService;

    @Autowired
    private PipelineOrchestrationService orchestrationService;

    @Autowired
    private PipelineDefinitionRepository definitionRepository;

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private PipelineRunEventRepository runEventRepository;

    private final List<UUID> createdDefinitionIds = new ArrayList<>();
    private final List<UUID> createdProjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID defId : createdDefinitionIds) {
            definitionRepository.deleteById(defId);
        }
        createdDefinitionIds.clear();
        for (UUID projId : createdProjectIds) {
            projectRepository.deleteById(projId);
        }
        createdProjectIds.clear();
    }

    @Test
    void handlesConcurrentRunCreationMonotonicallyAndConcurrentCancellationConsistently() throws Exception {
        long uniqueGhId = 950000L + (System.nanoTime() % 100000L);
        UserEntity owner = userAccountService.upsertFromGitHubOAuth(
                uniqueGhId, "conc-owner-" + uniqueGhId, "Conc Owner", null, null, null
        );
        WorkspaceEntity workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId())
                .get(0).getWorkspace();

        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setCreatedBy(owner);
        project.setName("Concurrency Project " + uniqueGhId);
        project.setSlug("conc-proj-" + uniqueGhId);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.saveAndFlush(project);
        createdProjectIds.add(project.getId());

        PipelineDefinitionDto definition = definitionService.createDefinition(
                workspace.getId(),
                new CreatePipelineDefinitionRequest(
                        project.getId(),
                        "Concurrent Pipeline",
                        "Race condition test",
                        null,
                        true,
                        List.of(
                                new StageDefinitionRequest("source", "Source", PipelineStageType.SOURCE_PREP, List.of(), 300, 0, false, Map.of()),
                                new StageDefinitionRequest("build", "Build", PipelineStageType.BUILD, List.of("source"), 300, 0, false, Map.of())
                        )
                ),
                owner
        );
        createdDefinitionIds.add(definition.id());

        // 1. 4 parallel threads create distinct runs simultaneously -> monotonic unique run numbers 1..4
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Callable<PipelineRunDto>> createTasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final String key = "parallel-run-" + uniqueGhId + "-" + i;
                createTasks.add(() -> {
                    startGate.await();
                    return orchestrationService.createRun(
                            workspace.getId(),
                            new CreatePipelineRunRequest(definition.id(), "main", null, PipelineTriggerType.API, null),
                            key,
                            owner
                    ).run();
                });
            }
            List<Future<PipelineRunDto>> createFutures = createTasks.stream().map(pool::submit).toList();
            startGate.countDown();

            List<PipelineRunDto> createdRuns = new ArrayList<>();
            for (Future<PipelineRunDto> f : createFutures) {
                createdRuns.add(f.get());
            }

            Set<Integer> runNumbers = createdRuns.stream().map(PipelineRunDto::runNumber).collect(Collectors.toSet());
            assertThat(runNumbers).containsExactlyInAnyOrder(1, 2, 3, 4);
            assertThat(createdRuns).allMatch(r -> r.status() == PipelineRunStatus.QUEUED);

            // 2. 4 parallel threads cancel the SAME run simultaneously -> consistent CANCELLED state & single RUN_CANCELLED event
            UUID targetRunId = createdRuns.get(0).id();
            CountDownLatch cancelGate = new CountDownLatch(1);
            List<Callable<PipelineRunDto>> cancelTasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                cancelTasks.add(() -> {
                    cancelGate.await();
                    return orchestrationService.cancelRun(
                            workspace.getId(),
                            targetRunId,
                            new CancelPipelineRunRequest("Concurrent cancel test"),
                            owner
                    );
                });
            }
            List<Future<PipelineRunDto>> cancelFutures = cancelTasks.stream().map(pool::submit).toList();
            cancelGate.countDown();

            for (Future<PipelineRunDto> f : cancelFutures) {
                PipelineRunDto cancelled = f.get();
                assertThat(cancelled.status()).isEqualTo(PipelineRunStatus.CANCELLED);
                assertThat(cancelled.stages()).allMatch(s -> s.status() == PipelineStageStatus.CANCELLED);
            }

            long runCancelledEvents = runEventRepository
                    .findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(targetRunId, workspace.getId())
                    .stream()
                    .filter(e -> "RUN_CANCELLED".equals(e.getEventType()))
                    .count();
            assertThat(runCancelledEvents).isEqualTo(1);

            // 3. 4 parallel threads submit the EXACT SAME Idempotency-Key simultaneously ->
            // exactly ONE new pipeline run (run_number = 5) is ever created, zero duplicates.
            String sharedIdemKey = "shared-concurrent-idem-" + uniqueGhId;
            CountDownLatch idemGate = new CountDownLatch(1);
            List<Callable<UUID>> idemTasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                idemTasks.add(() -> {
                    idemGate.await();
                    try {
                        return orchestrationService.createRun(
                                workspace.getId(),
                                new CreatePipelineRunRequest(definition.id(), "main", "1234567", PipelineTriggerType.API, null),
                                sharedIdemKey,
                                owner
                        ).run().id();
                    } catch (ConflictException ex) {
                        // A concurrent caller arriving while the first transaction is mid-flight
                        // receives a clean, retryable 409 Conflict instead of creating a duplicate.
                        return null;
                    }
                });
            }
            List<Future<UUID>> idemFutures = idemTasks.stream().map(pool::submit).toList();
            idemGate.countDown();

            Set<UUID> observedRunIds = new java.util.HashSet<>();
            for (Future<UUID> f : idemFutures) {
                UUID id = f.get();
                if (id != null) {
                    observedRunIds.add(id);
                }
            }
            assertThat(observedRunIds).hasSize(1);
            // Total runs for this definition must be 4 (step 1) + 1 (step 3) = 5, never > 5
            assertThat(runRepository.findMaxRunNumberByDefinitionId(definition.id())).isEqualTo(5);

            // Follow-up sequential replay with the same key deterministically resolves to that exact run
            var replayAfterRace = orchestrationService.createRun(
                    workspace.getId(),
                    new CreatePipelineRunRequest(definition.id(), "main", "1234567", PipelineTriggerType.API, null),
                    sharedIdemKey,
                    owner
            );
            assertThat(replayAfterRace.idempotentReplay()).isTrue();
            assertThat(replayAfterRace.run().id()).isEqualTo(observedRunIds.iterator().next());
        } finally {
            pool.shutdownNow();
        }
    }
}
