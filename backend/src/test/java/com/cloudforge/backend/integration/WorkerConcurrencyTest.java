package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.StageDefinitionRequest;
import com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineDefinitionRepository;
import com.cloudforge.backend.orchestration.service.PipelineDefinitionService;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.dto.WorkerRegistrationRequest;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.cloudforge.backend.worker.service.WorkerSchedulerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real multi-threaded concurrency test verifying that when 4 trusted workers race
 * to claim the exact same READY stage simultaneously, strictly ONE worker receives
 * the lease and zero duplicate execution attempts are created.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "cloudforge.workers.enabled=true",
        "cloudforge.workers.scheduler-enabled=false"
})
class WorkerConcurrencyTest {

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
    private WorkerRegistryService workerRegistryService;
    @Autowired
    private WorkerSchedulerService workerSchedulerService;
    @Autowired
    private WorkerNodeRepository workerNodeRepository;
    @Autowired
    private StageExecutionAttemptRepository attemptRepository;

    private final List<UUID> createdDefinitionIds = new ArrayList<>();
    private final List<UUID> createdProjectIds = new ArrayList<>();
    private final List<String> createdWorkerIds = new ArrayList<>();

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
        for (String workerId : createdWorkerIds) {
            workerNodeRepository.findByWorkerId(workerId).ifPresent(workerNodeRepository::delete);
        }
        createdWorkerIds.clear();
    }

    @Test
    void fourConcurrentWorkersClaimSingleReadyStageExactlyOnceWithZeroDuplicates() throws Exception {
        long suffix = System.nanoTime() % 100000L;
        UserEntity owner = userAccountService.upsertFromGitHubOAuth(
                960000L + suffix, "wconc-owner-" + suffix, "WConc Owner", null, null, null
        );
        WorkspaceEntity workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId())
                .get(0).getWorkspace();

        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setCreatedBy(owner);
        project.setName("Worker Concurrency Project " + suffix);
        project.setSlug("wconc-proj-" + suffix);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.saveAndFlush(project);
        createdProjectIds.add(project.getId());

        var def = definitionService.createDefinition(
                workspace.getId(),
                new CreatePipelineDefinitionRequest(
                        project.getId(),
                        "Single Stage Race Pipeline",
                        "4-worker lease race",
                        null,
                        true,
                        List.of(new StageDefinitionRequest(
                                "env",
                                "Env Validation",
                                PipelineStageType.SOURCE_PREP,
                                List.of(),
                                120,
                                0,
                                false,
                                Map.of("operation", "ENV_VALIDATION")
                        ))
                ),
                owner
        );
        createdDefinitionIds.add(def.id());

        var run = orchestrationService.createRun(
                workspace.getId(),
                new CreatePipelineRunRequest(def.id(), "main", null, PipelineTriggerType.API, null),
                "worker-race-" + suffix,
                owner
        ).run();
        UUID stageId = run.stages().get(0).id();

        List<WorkerPrincipal> workers = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String workerId = "race-worker-" + suffix + "-" + i;
            createdWorkerIds.add(workerId);
            WorkerPrincipal principal = new WorkerPrincipal(workerId, "key-" + workerId);
            workerRegistryService.registerWorker(
                    new WorkerRegistrationRequest(
                            workerId,
                            "Race Worker " + i,
                            WorkerAdapterType.DOCKER_ISOLATED,
                            List.of("ENV_VALIDATION"),
                            1,
                            "race-host",
                            "0.3.3"
                    ),
                    principal
            );
            workers.add(principal);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Callable<Optional<WorkerAssignmentContract.LeasedStageWork>>> tasks = new ArrayList<>();
            for (WorkerPrincipal principal : workers) {
                tasks.add(() -> {
                    gate.await();
                    return workerSchedulerService.claimNextEligibleStage(principal.workerId(), principal);
                });
            }

            List<Future<Optional<WorkerAssignmentContract.LeasedStageWork>>> futures =
                    tasks.stream().map(pool::submit).toList();
            gate.countDown();

            List<WorkerAssignmentContract.LeasedStageWork> winners = new ArrayList<>();
            for (Future<Optional<WorkerAssignmentContract.LeasedStageWork>> f : futures) {
                f.get().ifPresent(winners::add);
            }

            assertThat(winners).hasSize(1);
            assertThat(winners.get(0).stage().stageRunId()).isEqualTo(stageId);
            assertThat(attemptRepository.findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAsc(stageId, workspace.getId()))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
