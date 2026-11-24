package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerCredentialService;
import com.cloudforge.backend.worker.service.WorkerRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive Phase 3.3 security and end-to-end execution lifecycle test suite covering:
 * worker credential auth/expiration/revocation/spoofing, ordinary-user exclusion,
 * full multi-stage DAG claim -> execute -> log redaction -> complete -> downstream READY -> SUCCEEDED,
 * non-retryable failure & transitive skip, retryable timeout backoff without terminal FAILED bypass,
 * cancellation signaling + termination confirmation, lease expiry recovery & stale worker fencing,
 * log bounds, unsupported stage rejection, and cross-workspace isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "cloudforge.workers.enabled=true",
        "cloudforge.workers.scheduler-enabled=false",
        "cloudforge.workers.lease-duration-seconds=60",
        "cloudforge.workers.retry-base-backoff-seconds=1",
        "cloudforge.workers.max-log-lines-per-attempt=10",
        "cloudforge.workers.max-log-bytes-per-attempt=2048"
})
@Transactional
class WorkerSecurityAndExecutionEndToEndTest {

    private static final String WORKER_1 = "e2e-worker-1";
    private static final String WORKER_1_KEY = "key-e2e-1";
    private static final String WORKER_1_SECRET = "worker-1-ephemeral-test-secret-0001";

    private static final String WORKER_2 = "e2e-worker-2";
    private static final String WORKER_2_KEY = "key-e2e-2";
    private static final String WORKER_2_SECRET = "worker-2-ephemeral-test-secret-0002";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private WorkerCredentialService workerCredentialService;
    @Autowired
    private WorkerNodeRepository workerNodeRepository;
    @Autowired
    private StageExecutionAttemptRepository attemptRepository;
    @Autowired
    private PipelineRunRepository runRepository;
    @Autowired
    private PipelineStageRunRepository stageRunRepository;
    @Autowired
    private PipelineRunEventRepository runEventRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private WorkerRecoveryService recoveryService;

    private UserEntity owner;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private ProjectEntity projectA;

    @BeforeEach
    void setUp() {
        owner = userAccountService.upsertFromGitHubOAuth(89101L, "w-owner", "Worker Owner", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(89102L, "w-viewer", "Worker Viewer", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(89103L, "w-outsider", "Worker Outsider", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        WorkspaceMemberEntity viewerMember = new WorkspaceMemberEntity();
        viewerMember.setWorkspace(workspaceA);
        viewerMember.setUser(viewer);
        viewerMember.setRole(WorkspaceRole.VIEWER);
        workspaceMemberRepository.save(viewerMember);

        projectA = new ProjectEntity();
        projectA.setWorkspace(workspaceA);
        projectA.setCreatedBy(owner);
        projectA.setName("Worker E2E Service");
        projectA.setSlug("worker-e2e-service");
        projectA.setDefaultBranch("main");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.saveAndFlush(projectA);

        workerCredentialService.provisionCredential(
                WORKER_1, WORKER_1_KEY, WORKER_1_SECRET, Instant.now().plusSeconds(3600), "Test worker 1"
        );
        workerCredentialService.provisionCredential(
                WORKER_2, WORKER_2_KEY, WORKER_2_SECRET, Instant.now().plusSeconds(3600), "Test worker 2"
        );
    }

    @Test
    void enforcesWorkerAuthenticationSpoofingExpirationRevocationAndUserExclusion() throws Exception {
        String registerPayload = """
                {
                  "workerId": "%s",
                  "displayName": "Isolated Worker 1",
                  "adapterType": "DOCKER_ISOLATED",
                  "supportedOperations": ["ENV_VALIDATION", "CONTROLLED_SMOKE_TEST"],
                  "maxConcurrency": 1,
                  "hostLabel": "dev-box"
                }
                """.formatted(WORKER_1);

        // 1. Unauthenticated registration -> 401
        mockMvc.perform(post("/api/internal/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isUnauthorized());

        // 2. Ordinary authenticated workspace OWNER cannot call internal worker endpoints -> 401
        mockMvc.perform(post("/api/internal/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isUnauthorized());

        // 3. Worker 2 attempting to register under Worker 1's identity (spoofing) -> 403
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", WORKER_2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_2_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isForbidden());

        // 4. Expired credential rejected -> 401
        String expiredSecret = "expired-worker-credential-secret-0009";
        workerCredentialService.provisionCredential(
                "expired-worker", "key-expired", expiredSecret, Instant.now().minusSeconds(60), "Expired"
        );
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", "expired-worker")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload.replace(WORKER_1, "expired-worker")))
                .andExpect(status().isUnauthorized());

        // 5. Valid registration succeeds -> 200
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", WORKER_1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_1_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(WORKER_1))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        // 6. Worker 2 attempting to claim on Worker 1's path -> 403
        mockMvc.perform(post("/api/internal/workers/{w}/claim", WORKER_1)
                        .header("X-CloudForge-Worker-Id", WORKER_2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_2_SECRET))
                .andExpect(status().isForbidden());

        // 7. Revoked credential immediately rejected -> 401
        workerCredentialService.revokeCredential(WORKER_1_KEY);
        mockMvc.perform(post("/api/internal/workers/{w}/claim", WORKER_1)
                        .header("X-CloudForge-Worker-Id", WORKER_1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_1_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executesCompleteMultiStageAllowlistedPipelineEndToEndWithRedactedLogsAndAudit() throws Exception {
        registerWorker(WORKER_1, WORKER_1_SECRET, "[\"ENV_VALIDATION\", \"CONTROLLED_SMOKE_TEST\", \"WORKER_DIAGNOSTICS\"]");

        // 1. Unsupported operation rejected during definition creation -> 400
        String badOpDef = """
                {
                  "projectId": "%s",
                  "name": "Bad Op Pipeline",
                  "stages": [
                    {
                      "stageKey": "build",
                      "displayName": "Arbitrary Build",
                      "stageType": "BUILD",
                      "dependsOn": [],
                      "metadata": {"operation": "ENV_VALIDATION"}
                    }
                  ]
                }
                """.formatted(projectA.getId());
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badOpDef)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        // 2. Create executable 2-stage DAG: source (ENV_VALIDATION) -> smoke (CONTROLLED_SMOKE_TEST)
        UUID defId = createDefinition("""
                {
                  "projectId": "%s",
                  "name": "Isolated Allowlisted Pipeline",
                  "stages": [
                    {
                      "stageKey": "source",
                      "displayName": "Sandbox Env Validation",
                      "stageType": "SOURCE_PREP",
                      "dependsOn": [],
                      "timeoutSeconds": 120,
                      "maxRetries": 1,
                      "metadata": {"operation": "ENV_VALIDATION"}
                    },
                    {
                      "stageKey": "smoke",
                      "displayName": "Controlled Smoke Verification",
                      "stageType": "TEST",
                      "dependsOn": ["source"],
                      "timeoutSeconds": 120,
                      "maxRetries": 0,
                      "metadata": {"operation": "CONTROLLED_SMOKE_TEST"}
                    }
                  ]
                }
                """.formatted(projectA.getId()));

        // 3. Queue run -> QUEUED, source=READY, smoke=PENDING
        JsonNode runNode = queueRun(defId, "e2e-idem-1");
        UUID runId = UUID.fromString(runNode.path("id").asText());
        UUID sourceStageId = UUID.fromString(runNode.path("stages").get(0).path("id").asText());
        UUID smokeStageId = UUID.fromString(runNode.path("stages").get(1).path("id").asText());
        assertThat(runNode.path("status").asText()).isEqualTo("QUEUED");
        assertThat(runNode.path("stages").get(0).path("status").asText()).isEqualTo("READY");
        assertThat(runNode.path("stages").get(0).path("executionSupported").asBoolean()).isTrue();
        assertThat(runNode.path("stages").get(1).path("status").asText()).isEqualTo("PENDING");

        // 4. Worker claims stage #1 (source) -> run becomes RUNNING, stage becomes RUNNING
        JsonNode claim1 = claimNext(WORKER_1, WORKER_1_SECRET);
        assertThat(claim1.path("stage").path("stageKey").asText()).isEqualTo("source");
        assertThat(claim1.path("allowedOperation").asText()).isEqualTo("ENV_VALIDATION");
        UUID attempt1Id = UUID.fromString(claim1.path("attemptId").asText());
        String lease1 = claim1.path("leaseToken").asText();
        long fence1 = claim1.path("fencingGeneration").asLong();

        // Worker acknowledges RUNNING & heartbeats
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/running", WORKER_1, attempt1Id)
                        .header("X-CloudForge-Worker-Id", WORKER_1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_1_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"leaseToken":"%s","fencingGeneration":%d}
                                """.formatted(lease1, fence1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 5. Worker appends logs including secret patterns & exceeding max-log-lines-per-attempt (10)
        StringBuilder entries = new StringBuilder("[");
        entries.append("{\"streamType\":\"STDOUT\",\"message\":\"[ENV_VALIDATION] uid=65534 Authorization: Bearer leaked_token_abc123456 ghp_secret1234567890abcd\"}");
        for (int i = 2; i <= 12; i++) {
            entries.append(",{\"streamType\":\"STDOUT\",\"message\":\"line ").append(i).append("\"}");
        }
        entries.append("]");

        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/logs", WORKER_1, attempt1Id)
                        .header("X-CloudForge-Worker-Id", WORKER_1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_1_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"leaseToken":"%s","fencingGeneration":%d,"entries":%s}
                                """.formatted(lease1, fence1, entries)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(10))
                .andExpect(jsonPath("$.droppedCount").value(2))
                .andExpect(jsonPath("$.limitReached").value(true));

        // Verify workspace user reads redacted logs & outsider is denied (403/404)
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{r}/stages/{s}/logs",
                        workspaceA.getId(), runId, sourceStageId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].message").value(org.hamcrest.Matchers.containsString("[REDACTED]")))
                .andExpect(jsonPath("$.items[0].message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("leaked_token_abc123456"))))
                .andExpect(jsonPath("$.items[0].message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ghp_secret1234567890abcd"))));

        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{r}/stages/{s}/logs",
                        workspaceB.getId(), runId, sourceStageId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());

        // 6. Worker completes stage #1 SUCCEEDED -> smoke automatically becomes READY
        completeAttempt(WORKER_1, WORKER_1_SECRET, attempt1Id, runId, sourceStageId, 1, lease1, fence1,
                "SUCCEEDED", "NONE", 0, "ENV_VALIDATION passed", true)
                .andExpect(status().isOk());

        // Duplicate completion rejected -> 409 Conflict
        completeAttempt(WORKER_1, WORKER_1_SECRET, attempt1Id, runId, sourceStageId, 1, lease1, fence1,
                "SUCCEEDED", "NONE", 0, "Duplicate report", true)
                .andExpect(status().isConflict());

        // 7. Worker claims dependent stage #2 (smoke) and completes SUCCEEDED -> entire run SUCCEEDED!
        JsonNode claim2 = claimNext(WORKER_1, WORKER_1_SECRET);
        assertThat(claim2.path("stage").path("stageKey").asText()).isEqualTo("smoke");
        UUID attempt2Id = UUID.fromString(claim2.path("attemptId").asText());
        String lease2 = claim2.path("leaseToken").asText();
        long fence2 = claim2.path("fencingGeneration").asLong();

        completeAttempt(WORKER_1, WORKER_1_SECRET, attempt2Id, runId, smokeStageId, 1, lease2, fence2,
                "SUCCEEDED", "NONE", 0, "CONTROLLED_SMOKE_TEST passed", true)
                .andExpect(status().isOk());

        // 8. Verify terminal run SUCCEEDED, events, worker status (OWNER vs VIEWER), and audit trail
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{r}", workspaceA.getId(), runId)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stages[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stages[1].status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/workers/status", workspaceA.getId())
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredWorkers").value(1))
                .andExpect(jsonPath("$.availableWorkers").value(1))
                .andExpect(jsonPath("$.workers.length()").value(1));

        // VIEWER sees aggregate worker counts only, not detailed global worker nodes
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/workers/status", workspaceA.getId())
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredWorkers").value(1))
                .andExpect(jsonPath("$.workers.length()").value(0));

        assertThat(runEventRepository.findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(runId, workspaceA.getId()))
                .extracting(e -> e.getEventType())
                .contains("RUN_QUEUED", "STAGE_READY", "RUN_RUNNING", "STAGE_CLAIMED", "STAGE_SUCCEEDED", "RUN_SUCCEEDED");

        assertThat(auditEventRepository.findByWorkspaceId(workspaceA.getId(), null, PageRequest.of(0, 50)).getContent())
                .extracting(a -> a.getAction())
                .contains("PIPELINE_RUN_REQUESTED", "PIPELINE_RUN_STARTED", "PIPELINE_RUN_SUCCEEDED");
    }

    @Test
    void handlesRetriesTimeoutsFailurePropagationCancellationRacesAndLeaseRecoveryFencing() throws Exception {
        registerWorker(WORKER_1, WORKER_1_SECRET, "[\"ENV_VALIDATION\", \"CONTROLLED_SMOKE_TEST\"]");
        registerWorker(WORKER_2, WORKER_2_SECRET, "[\"ENV_VALIDATION\", \"CONTROLLED_SMOKE_TEST\"]");

        // --- Scenario A: Timeout + Retry without illegal terminal FAILED transition ---
        UUID retryDefId = createDefinition("""
                {
                  "projectId": "%s",
                  "name": "Timeout Retry Pipeline",
                  "stages": [
                    {
                      "stageKey": "env",
                      "displayName": "Env Check",
                      "stageType": "SOURCE_PREP",
                      "dependsOn": [],
                      "timeoutSeconds": 60,
                      "maxRetries": 1,
                      "metadata": {"operation": "ENV_VALIDATION"}
                    }
                  ]
                }
                """.formatted(projectA.getId()));
        JsonNode retryRun = queueRun(retryDefId, "retry-run-1");
        UUID retryRunId = UUID.fromString(retryRun.path("id").asText());
        UUID retryStageId = UUID.fromString(retryRun.path("stages").get(0).path("id").asText());

        JsonNode claimAttempt1 = claimNext(WORKER_1, WORKER_1_SECRET);
        UUID att1Id = UUID.fromString(claimAttempt1.path("attemptId").asText());
        String att1Lease = claimAttempt1.path("leaseToken").asText();
        long att1Fence = claimAttempt1.path("fencingGeneration").asLong();

        // Attempt #1 times out -> retryable! Stage transitions RUNNING -> READY (never terminal FAILED!)
        completeAttempt(WORKER_1, WORKER_1_SECRET, att1Id, retryRunId, retryStageId, 1, att1Lease, att1Fence,
                "TIMED_OUT", "EXECUTION_TIMEOUT", 124, "Timed out after 60s", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TIMED_OUT"));

        var stageAfterTimeout = stageRunRepository.findById(retryStageId).orElseThrow();
        assertThat(stageAfterTimeout.getStatus()).isEqualTo(PipelineStageStatus.READY);
        assertThat(stageAfterTimeout.getNextRetryAt()).isNotNull();

        // Wait past 1s test backoff so attempt #2 becomes claimable
        Thread.sleep(1100);
        JsonNode claimAttempt2 = claimNext(WORKER_2, WORKER_2_SECRET);
        assertThat(claimAttempt2.path("stage").path("attemptNumber").asInt()).isEqualTo(2);
        assertThat(claimAttempt2.path("fencingGeneration").asLong()).isGreaterThan(att1Fence);
        UUID att2Id = UUID.fromString(claimAttempt2.path("attemptId").asText());
        completeAttempt(WORKER_2, WORKER_2_SECRET, att2Id, retryRunId, retryStageId, 2,
                claimAttempt2.path("leaseToken").asText(), claimAttempt2.path("fencingGeneration").asLong(),
                "SUCCEEDED", "NONE", 0, "Retry attempt #2 succeeded", true)
                .andExpect(status().isOk());

        assertThat(runRepository.findById(retryRunId).orElseThrow().getStatus()).isEqualTo(PipelineRunStatus.SUCCEEDED);

        // --- Scenario B: Cancellation during active execution + unconfirmed cancel rejected ---
        UUID cancelDefId = createDefinition("""
                {
                  "projectId": "%s",
                  "name": "Active Cancel Pipeline",
                  "stages": [
                    {"stageKey": "s1", "displayName": "Stage 1", "stageType": "SOURCE_PREP", "dependsOn": [], "metadata": {"operation": "ENV_VALIDATION"}},
                    {"stageKey": "s2", "displayName": "Stage 2", "stageType": "TEST", "dependsOn": ["s1"], "metadata": {"operation": "CONTROLLED_SMOKE_TEST"}}
                  ]
                }
                """.formatted(projectA.getId()));
        JsonNode cancelRun = queueRun(cancelDefId, "cancel-run-1");
        UUID cancelRunId = UUID.fromString(cancelRun.path("id").asText());
        UUID s1Id = UUID.fromString(cancelRun.path("stages").get(0).path("id").asText());

        JsonNode cancelClaim = claimNext(WORKER_1, WORKER_1_SECRET);
        UUID cancelAttId = UUID.fromString(cancelClaim.path("attemptId").asText());
        String cancelLease = cancelClaim.path("leaseToken").asText();
        long cancelFence = cancelClaim.path("fencingGeneration").asLong();

        // User requests cancellation while s1 is RUNNING -> s2 cancelled immediately, s1 & run await confirmed termination
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs/{r}/cancel", workspaceA.getId(), cancelRunId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Abort active test\"}")
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cancellationRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.stages[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.stages[1].status").value("CANCELLED"));

        // Heartbeat signals cancelRequested=true to worker
        mockMvc.perform(post("/api/internal/workers/{w}/heartbeat", WORKER_1)
                        .header("X-CloudForge-Worker-Id", WORKER_1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_1_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activeLeases":[{"attemptId":"%s","leaseToken":"%s","fencingGeneration":%d}]}
                                """.formatted(cancelAttId, cancelLease, cancelFence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directives[0].cancelRequested").value(true));

        // Unconfirmed cancellation rejected -> 400
        completeAttempt(WORKER_1, WORKER_1_SECRET, cancelAttId, cancelRunId, s1Id, 1, cancelLease, cancelFence,
                "CANCELLED", "CANCELLED", 143, "Stopping...", false)
                .andExpect(status().isBadRequest());

        // Confirmed cancellation accepted -> stage & run transition to terminal CANCELLED
        completeAttempt(WORKER_1, WORKER_1_SECRET, cancelAttId, cancelRunId, s1Id, 1, cancelLease, cancelFence,
                "CANCELLED", "CANCELLED", 143, "Container terminated cleanly", true)
                .andExpect(status().isOk());

        assertThat(runRepository.findById(cancelRunId).orElseThrow().getStatus()).isEqualTo(PipelineRunStatus.CANCELLED);

        // --- Scenario C: Lease Expiration Recovery, Offline Worker Detection & Stale Fencing ---
        UUID recoverDefId = createDefinition("""
                {
                  "projectId": "%s",
                  "name": "Lease Recovery Pipeline",
                  "stages": [
                    {"stageKey": "r1", "displayName": "Recoverable Stage", "stageType": "SOURCE_PREP", "dependsOn": [], "maxRetries": 1, "metadata": {"operation": "ENV_VALIDATION"}}
                  ]
                }
                """.formatted(projectA.getId()));
        JsonNode recoverRun = queueRun(recoverDefId, "recover-run-1");
        UUID recoverRunId = UUID.fromString(recoverRun.path("id").asText());
        UUID recoverStageId = UUID.fromString(recoverRun.path("stages").get(0).path("id").asText());

        JsonNode staleClaim = claimNext(WORKER_1, WORKER_1_SECRET);
        UUID staleAttId = UUID.fromString(staleClaim.path("attemptId").asText());
        String staleLease = staleClaim.path("leaseToken").asText();
        long staleFence = staleClaim.path("fencingGeneration").asLong();

        // Simulate time advancing past lease expiration & heartbeat timeout -> recovery fences stale attempt & marks worker OFFLINE
        WorkerRecoveryService.RecoverySummary summary =
                recoveryService.recoverExpiredWork(Instant.now().plusSeconds(120));
        assertThat(summary.recoveredAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(summary.markedOfflineWorkers()).isGreaterThanOrEqualTo(1);
        assertThat(workerNodeRepository.findByWorkerId(WORKER_1).orElseThrow().getStatus())
                .isEqualTo(WorkerStatus.OFFLINE);
        assertThat(attemptRepository.findById(staleAttId).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.EXPIRED);

        // Stale worker's late completion MUST be rejected (409 Conflict)
        completeAttempt(WORKER_1, WORKER_1_SECRET, staleAttId, recoverRunId, recoverStageId, 1, staleLease, staleFence,
                "SUCCEEDED", "NONE", 0, "Stale completion from dead worker", true)
                .andExpect(status().isConflict());

        // --- Scenario D: Non-retryable Operation Failure skips downstream + Unsupported Stage never claimed ---
        UUID failDefId = createDefinition("""
                {
                  "projectId": "%s",
                  "name": "Failure & Unsupported Pipeline",
                  "stages": [
                    {"stageKey": "f1", "displayName": "Failing Op", "stageType": "TEST", "dependsOn": [], "maxRetries": 2, "metadata": {"operation": "CONTROLLED_SMOKE_TEST"}},
                    {"stageKey": "f2", "displayName": "Unsupported Build", "stageType": "BUILD", "dependsOn": ["f1"]}
                  ]
                }
                """.formatted(projectA.getId()));
        JsonNode failRun = queueRun(failDefId, "fail-run-1");
        UUID failRunId = UUID.fromString(failRun.path("id").asText());
        UUID f1Id = UUID.fromString(failRun.path("stages").get(0).path("id").asText());
        assertThat(failRun.path("stages").get(1).path("executionSupported").asBoolean()).isFalse();

        registerWorker(WORKER_2, WORKER_2_SECRET, "[\"ENV_VALIDATION\", \"CONTROLLED_SMOKE_TEST\"]");
        JsonNode failClaim = claimNext(WORKER_2, WORKER_2_SECRET);
        completeAttempt(WORKER_2, WORKER_2_SECRET,
                UUID.fromString(failClaim.path("attemptId").asText()), failRunId, f1Id, 1,
                failClaim.path("leaseToken").asText(), failClaim.path("fencingGeneration").asLong(),
                "FAILED", "OPERATION_FAILURE", 1, "Smoke assertion failed", true)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{r}", workspaceA.getId(), failRunId)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stages[0].status").value("FAILED"))
                .andExpect(jsonPath("$.stages[1].status").value("SKIPPED"));
    }

    private void registerWorker(String workerId, String secret, String opsJson) throws Exception {
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Worker %s",
                                  "adapterType": "DOCKER_ISOLATED",
                                  "supportedOperations": %s,
                                  "maxConcurrency": 2
                                }
                                """.formatted(workerId, workerId, opsJson)))
                .andExpect(status().isOk());
    }

    private UUID createDefinition(String json) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode queueRun(UUID definitionId, String idemKey) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definitionId":"%s","targetBranch":"main","triggerType":"MANUAL"}
                                """.formatted(definitionId))
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode claimNext(String workerId, String secret) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/internal/workers/{w}/claim", workerId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions completeAttempt(
            String workerId,
            String secret,
            UUID attemptId,
            UUID runId,
            UUID stageId,
            int attemptNum,
            String leaseToken,
            long fence,
            String resultStatus,
            String failureCategory,
            int exitCode,
            String summary,
            boolean terminationConfirmed
    ) throws Exception {
        return mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", workerId, attemptId)
                .header("X-CloudForge-Worker-Id", workerId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "pipelineRunId": "%s",
                          "stageRunId": "%s",
                          "attemptNumber": %d,
                          "leaseToken": "%s",
                          "fencingGeneration": %d,
                          "resultStatus": "%s",
                          "failureCategory": "%s",
                          "exitCode": %d,
                          "summary": "%s",
                          "resourceUsage": {"cpuLimit": "0.5", "memoryLimit": "128m"},
                          "terminationConfirmed": %s
                        }
                        """.formatted(
                        runId, stageId, attemptNum, leaseToken, fence,
                        resultStatus, failureCategory, exitCode, summary, terminationConfirmed
                )));
    }
}
