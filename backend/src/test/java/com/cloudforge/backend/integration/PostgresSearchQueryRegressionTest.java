package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.GitHubWorkflowEntity;
import com.cloudforge.backend.entity.GitHubWorkflowRunEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.StageDefinitionRequest;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineDefinitionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.service.PipelineDefinitionService;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.worker.dto.AppendExecutionLogsRequest;
import com.cloudforge.backend.worker.dto.CompleteAttemptRequest;
import com.cloudforge.backend.worker.dto.WorkerRegistrationRequest;
import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.LogStreamType;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.WorkerExecutionLogService;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.cloudforge.backend.worker.service.WorkerResultProcessorService;
import com.cloudforge.backend.worker.service.WorkerSchedulerService;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRunRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WebhookDeliveryRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test executing Spring Data JPA search queries and REST list endpoints directly
 * against PostgreSQL to ensure nullable search/filter parameters never trigger
 * PostgreSQL "ERROR: function lower(bytea) does not exist".
 *
 * All writes execute inside a @Transactional test method and roll back automatically,
 * preserving all existing PostgreSQL data without resetting or modifying schema history.
 */
@ExtendWith(PostgresAvailableCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostgresSearchQueryRegressionTest {

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresAvailableCondition::resolveUrl);
        registry.add("spring.datasource.username", PostgresAvailableCondition::resolveUser);
        registry.add("spring.datasource.password", PostgresAvailableCondition::resolvePassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Never mutate or reset an existing local database unless explicitly opted in (e.g., ephemeral CI container).
        boolean runFlyway = "true".equalsIgnoreCase(System.getenv("TEST_POSTGRES_RUN_FLYWAY"));
        registry.add("spring.flyway.enabled", () -> String.valueOf(runFlyway));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> "true");
        registry.add("spring.jpa.properties.jakarta.persistence.database-product-name", () -> "PostgreSQL");
        registry.add("cloudforge.workers.enabled", () -> "true");
        registry.add("cloudforge.workers.scheduler-enabled", () -> "false");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @Autowired
    private LinkedRepositoryRepository linkedRepositoryRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private GitHubWorkflowRepository workflowRepository;

    @Autowired
    private GitHubWorkflowRunRepository workflowRunRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private PipelineDefinitionService pipelineDefinitionService;

    @Autowired
    private PipelineOrchestrationService pipelineOrchestrationService;

    @Autowired
    private PipelineDefinitionRepository pipelineDefinitionRepository;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private WorkerRegistryService workerRegistryService;

    @Autowired
    private WorkerSchedulerService workerSchedulerService;

    @Autowired
    private WorkerExecutionLogService workerExecutionLogService;

    @Autowired
    private WorkerResultProcessorService workerResultProcessorService;

    private UserEntity ownerA;
    private UserEntity ownerB;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private LinkedRepositoryEntity repoA1;
    private ProjectEntity projectA1;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
        }

        ownerA = userAccountService.upsertFromGitHubOAuth(
                981001L, "pg-test-owner-a", "PG Owner A", null, null, null
        );
        ownerB = userAccountService.upsertFromGitHubOAuth(
                981002L, "pg-test-owner-b", "PG Owner B", null, null, null
        );

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(ownerA.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(ownerB.getId()).get(0).getWorkspace();

        GitHubInstallationEntity instA = new GitHubInstallationEntity();
        instA.setWorkspace(workspaceA);
        instA.setGithubInstallationId(9981001L);
        instA.setAccountLogin("pg-org-a");
        instA.setAccountType("Organization");
        instA.setStatus(InstallationStatus.ACTIVE);
        instA = installationRepository.save(instA);

        GitHubInstallationEntity instB = new GitHubInstallationEntity();
        instB.setWorkspace(workspaceB);
        instB.setGithubInstallationId(9981002L);
        instB.setAccountLogin("pg-org-b");
        instB.setAccountType("Organization");
        instB.setStatus(InstallationStatus.ACTIVE);
        instB = installationRepository.save(instB);

        repoA1 = saveRepo(
                workspaceA, instA, 991001L, "pg-org-a", "payments-api",
                "private", "Handles 100% of checkout payments", RepositoryConnectionStatus.CONNECTED
        );
        saveRepo(
                workspaceA, instA, 991002L, "pg-org-a", "docs-site",
                "public", "Public developer portal", RepositoryConnectionStatus.DISCONNECTED
        );
        saveRepo(
                workspaceB, instB, 991003L, "pg-org-b", "isolated-other-repo",
                "private", "Other workspace repo", RepositoryConnectionStatus.CONNECTED
        );

        projectA1 = saveProject(workspaceA, ownerA, repoA1, "Payments Core", "payments-core",
                "Core billing 100% SLA service", ProjectStatus.ACTIVE);
        saveProject(workspaceA, ownerA, null, "Analytics Worker", "analytics-worker",
                null, ProjectStatus.PAUSED);
        saveProject(workspaceB, ownerB, null, "Workspace B Project", "ws-b-project",
                "Should not leak into workspace A", ProjectStatus.ACTIVE);

        auditService.record(workspaceA, ownerA, "PROJECT_CREATED", "PROJECT", "pg-test-1", "Seed audit", Map.of());
    }

    @Test
    void projectSearchWorksOnPostgreSqlWithNullBlankAndPopulatedFiltersAndPagination() throws Exception {
        Pageable pageDesc = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "updatedAt"));

        // Exact failure case from Bug 2: search = null, status = null
        Page<ProjectEntity> allInA = projectRepository.searchByWorkspace(workspaceA.getId(), null, null, pageDesc);
        assertThat(allInA.getTotalElements()).isEqualTo(2);
        assertThat(allInA.getContent()).extracting(ProjectEntity::getSlug)
                .containsExactlyInAnyOrder("payments-core", "analytics-worker");

        // Blank search + status filter
        Page<ProjectEntity> pausedOnly = projectRepository.searchByWorkspace(
                workspaceA.getId(), "   ", ProjectStatus.PAUSED, pageDesc
        );
        assertThat(pausedOnly.getTotalElements()).isEqualTo(1);
        assertThat(pausedOnly.getContent().get(0).getSlug()).isEqualTo("analytics-worker");

        // Case-insensitive search + LIKE wildcard escaping ("100%")
        Page<ProjectEntity> literalPercent = projectRepository.searchByWorkspace(
                workspaceA.getId(), "100%", ProjectStatus.ACTIVE, pageDesc
        );
        assertThat(literalPercent.getTotalElements()).isEqualTo(1);
        assertThat(literalPercent.getContent().get(0).getSlug()).isEqualTo("payments-core");

        // REST API check with null query params & pagination + cross-workspace authorization
        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .param("page", "0")
                        .param("size", "1")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .param("q", "PAYMENTS")
                        .param("status", "ACTIVE")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].linkedRepository.fullName").value("pg-org-a/payments-api"));

        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(ownerB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void repositorySearchWorksOnPostgreSqlWithNullVisibilitySearchAndStatusFilters() throws Exception {
        Pageable pageAsc = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "fullName"));

        // Exact failure case from Bug 2: search = null, visibility = null, status = null
        Page<LinkedRepositoryEntity> allRepos = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), null, null, null, pageAsc
        );
        assertThat(allRepos.getTotalElements()).isEqualTo(2);
        assertThat(allRepos.getContent()).extracting(LinkedRepositoryEntity::getFullName)
                .containsExactly("pg-org-a/docs-site", "pg-org-a/payments-api");

        // Visibility filter with null search & null status
        Page<LinkedRepositoryEntity> publicOnly = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), null, "PUBLIC", null, pageAsc
        );
        assertThat(publicOnly.getTotalElements()).isEqualTo(1);
        assertThat(publicOnly.getContent().get(0).getName()).isEqualTo("docs-site");

        // Search + visibility + status combined + LIKE literal %
        Page<LinkedRepositoryEntity> filtered = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), "100%", "private", RepositoryConnectionStatus.CONNECTED, pageAsc
        );
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent().get(0).getName()).isEqualTo("payments-api");

        // REST endpoint checks against live PostgreSQL
        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .param("visibility", "private")
                        .param("status", "CONNECTED")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("pg-org-a/payments-api"));

        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .with(authenticatedUser(ownerB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditEventSearchWorksOnPostgreSqlWithNullAndNonNullAction() throws Exception {
        var allEvents = auditEventRepository.findByWorkspaceId(workspaceA.getId(), null, PageRequest.of(0, 10));
        assertThat(allEvents.getTotalElements()).isGreaterThanOrEqualTo(2);

        var projectEvents = auditEventRepository.findByWorkspaceId(
                workspaceA.getId(), "PROJECT_CREATED", PageRequest.of(0, 10)
        );
        assertThat(projectEvents.getTotalElements()).isEqualTo(1);

        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void githubActionsWorkflowsRunsAndAtomicWebhookClaimWorkOnPostgreSql() throws Exception {
        Instant now = Instant.now();

        GitHubWorkflowEntity wf = new GitHubWorkflowEntity();
        wf.setWorkspace(workspaceA);
        wf.setLinkedRepository(repoA1);
        wf.setGithubWorkflowId(888001L);
        wf.setName("Build 100% Verified");
        wf.setPath(".github/workflows/ci.yml");
        wf.setState("active");
        wf.setHtmlUrl("https://github.com/pg-org-a/payments-api/blob/main/.github/workflows/ci.yml");
        wf.setLastSyncedAt(now);
        wf = workflowRepository.save(wf);

        GitHubWorkflowRunEntity run = new GitHubWorkflowRunEntity();
        run.setWorkspace(workspaceA);
        run.setLinkedRepository(repoA1);
        run.setWorkflow(wf);
        run.setGithubRunId(999001L);
        run.setGithubWorkflowId(888001L);
        run.setRunNumber(77);
        run.setRunAttempt(1);
        run.setWorkflowName("Build 100% Verified");
        run.setDisplayTitle("Fix 100% coverage");
        run.setEvent("push");
        run.setHeadBranch("main");
        run.setHeadSha("abcdef1234567");
        run.setGithubStatus("completed");
        run.setGithubConclusion("success");
        run.setNormalizedStatus(WorkflowRunNormalizedStatus.SUCCESS);
        run.setActorLogin("pg-test-owner-a");
        run.setGithubCreatedAt(now.minusSeconds(120));
        run.setGithubUpdatedAt(now.minusSeconds(30));
        run.setRunStartedAt(now.minusSeconds(115));
        run.setLastSyncedAt(now);
        workflowRunRepository.save(run);

        // Verify null & populated filters against live PostgreSQL (no lower(bytea) errors)
        Pageable wfPage = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name"));
        assertThat(workflowRepository.searchByRepository(workspaceA.getId(), repoA1.getId(), null, null, wfPage)
                .getTotalElements()).isEqualTo(1);
        assertThat(workflowRepository.searchByRepository(workspaceA.getId(), repoA1.getId(), "100%", "ACTIVE", wfPage)
                .getTotalElements()).isEqualTo(1);

        Pageable runPage = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "githubCreatedAt"));
        assertThat(workflowRunRepository.searchByWorkspace(workspaceA.getId(), repoA1.getId(), null, null, null, null, runPage)
                .getTotalElements()).isEqualTo(1);
        assertThat(workflowRunRepository.searchByWorkspace(
                workspaceA.getId(), repoA1.getId(), wf.getId(), WorkflowRunNormalizedStatus.SUCCESS, "MAIN", "100%", runPage)
                .getTotalElements()).isEqualTo(1);

        // Verify Phase 3.1 REST endpoints directly against live PostgreSQL
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines", workspaceA.getId())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowCount").value(1))
                .andExpect(jsonPath("$.recentRunCount").value(1))
                .andExpect(jsonPath("$.succeededRunCount").value(1));

        // Verify atomic ON CONFLICT DO NOTHING delivery claim on real PostgreSQL without aborting transaction
        String deliveryId = "pg-atomic-delivery-" + java.util.UUID.randomUUID();
        int firstClaim = webhookDeliveryRepository.claimDelivery(
                java.util.UUID.randomUUID(), deliveryId, "ping", null, 9981001L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "PROCESSED", "Initial claim", now, now, now
        );
        int secondClaim = webhookDeliveryRepository.claimDelivery(
                java.util.UUID.randomUUID(), deliveryId, "ping", null, 9981001L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "PROCESSED", "Duplicate claim", now, now, now
        );
        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isEqualTo(0);
    }

    @Test
    void phase32PipelineOrchestrationIdempotencySearchAndCancelWorkOnPostgreSql() throws Exception {
        var def = pipelineDefinitionService.createDefinition(
                workspaceA.getId(),
                new CreatePipelineDefinitionRequest(
                        projectA1.getId(),
                        "PG 100% Verified Pipeline",
                        "PostgreSQL 17 orchestration check",
                        repoA1.getId(),
                        true,
                        java.util.List.of(
                                new StageDefinitionRequest("source", "Source Prep", PipelineStageType.SOURCE_PREP, java.util.List.of(), 300, 0, false, Map.of()),
                                new StageDefinitionRequest("build", "Build", PipelineStageType.BUILD, java.util.List.of("source"), 600, 1, false, Map.of())
                        )
                ),
                ownerA
        );

        String idemKey = "pg-idem-" + java.util.UUID.randomUUID();
        var run1 = pipelineOrchestrationService.createRun(
                workspaceA.getId(),
                new CreatePipelineRunRequest(def.id(), "main", "abcdef1234567", PipelineTriggerType.MANUAL, null),
                idemKey,
                ownerA
        );
        assertThat(run1.idempotentReplay()).isFalse();
        assertThat(run1.run().status()).isEqualTo(PipelineRunStatus.QUEUED);

        // Idempotent replay against real PostgreSQL ON CONFLICT DO NOTHING without aborting TX
        var replay = pipelineOrchestrationService.createRun(
                workspaceA.getId(),
                new CreatePipelineRunRequest(def.id(), "main", "abcdef1234567", PipelineTriggerType.MANUAL, null),
                idemKey,
                ownerA
        );
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.run().id()).isEqualTo(run1.run().id());

        // Criteria searches with null & literal % filters on PostgreSQL
        assertThat(pipelineDefinitionRepository.searchByWorkspace(workspaceA.getId(), null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(pipelineDefinitionRepository.searchByWorkspace(workspaceA.getId(), projectA1.getId(), true, "100%", PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(pipelineRunRepository.searchByWorkspace(workspaceA.getId(), null, null, null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);

        // Cancel & duplicate cancel on PostgreSQL with pessimistic row lock
        var cancelled = pipelineOrchestrationService.cancelRun(
                workspaceA.getId(), run1.run().id(), new CancelPipelineRunRequest("PG test cancel"), ownerA
        );
        assertThat(cancelled.status()).isEqualTo(PipelineRunStatus.CANCELLED);
        var cancelledAgain = pipelineOrchestrationService.cancelRun(
                workspaceA.getId(), run1.run().id(), null, ownerA
        );
        assertThat(cancelledAgain.status()).isEqualTo(PipelineRunStatus.CANCELLED);

        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{runId}/events", workspaceA.getId(), run1.run().id())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("RUN_QUEUED"));
    }

    @Test
    void phase33WorkerRegistrationClaimLogsCompletionAndProgressionWorkOnPostgreSql() {
        var def = pipelineDefinitionService.createDefinition(
                workspaceA.getId(),
                new CreatePipelineDefinitionRequest(
                        projectA1.getId(),
                        "PG Worker Execution Pipeline",
                        "PostgreSQL 17 worker lifecycle check",
                        repoA1.getId(),
                        true,
                        java.util.List.of(
                                new StageDefinitionRequest(
                                        "env", "Env Validation", PipelineStageType.SOURCE_PREP,
                                        java.util.List.of(), 120, 0, false, Map.of("operation", "ENV_VALIDATION")
                                ),
                                new StageDefinitionRequest(
                                        "smoke", "Smoke Test", PipelineStageType.TEST,
                                        java.util.List.of("env"), 120, 0, false, Map.of("operation", "CONTROLLED_SMOKE_TEST")
                                )
                        )
                ),
                ownerA
        );

        var run = pipelineOrchestrationService.createRun(
                workspaceA.getId(),
                new CreatePipelineRunRequest(def.id(), "main", null, PipelineTriggerType.MANUAL, null),
                "pg-worker-run-" + java.util.UUID.randomUUID(),
                ownerA
        ).run();

        String workerId = "pg-worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        WorkerPrincipal principal = new WorkerPrincipal(workerId, "pg-key-" + workerId);
        workerRegistryService.registerWorker(
                new WorkerRegistrationRequest(
                        workerId, "PG CI Worker", WorkerAdapterType.DOCKER_ISOLATED,
                        java.util.List.of("ENV_VALIDATION", "CONTROLLED_SMOKE_TEST"), 1, "pg-ci", "0.3.3"
                ),
                principal
        );

        var leased1 = workerSchedulerService.claimNextEligibleStage(workerId, principal).orElseThrow();
        assertThat(leased1.stage().stageKey()).isEqualTo("env");

        var logResp = workerExecutionLogService.appendWorkerLogs(
                workerId,
                leased1.attemptId(),
                new AppendExecutionLogsRequest(
                        leased1.leaseToken(),
                        leased1.fencingGeneration(),
                        java.util.List.of(new AppendExecutionLogsRequest.LogEntryInput(
                                LogStreamType.STDOUT,
                                "PG isolated container ok Authorization: Bearer secret_pg_token_12345",
                                Instant.now()
                        ))
                ),
                principal
        );
        assertThat(logResp.acceptedCount()).isEqualTo(1);

        workerResultProcessorService.completeAttempt(
                workerId,
                leased1.attemptId(),
                new CompleteAttemptRequest(
                        run.id(), leased1.stage().stageRunId(), 1, leased1.leaseToken(), leased1.fencingGeneration(),
                        AttemptStatus.SUCCEEDED, AttemptFailureCategory.NONE, 0, "Env valid",
                        Map.of("durationMs", 42), Instant.now(), Instant.now(), true
                ),
                principal
        );

        var leased2 = workerSchedulerService.claimNextEligibleStage(workerId, principal).orElseThrow();
        assertThat(leased2.stage().stageKey()).isEqualTo("smoke");

        workerResultProcessorService.completeAttempt(
                workerId,
                leased2.attemptId(),
                new CompleteAttemptRequest(
                        run.id(), leased2.stage().stageRunId(), 1, leased2.leaseToken(), leased2.fencingGeneration(),
                        AttemptStatus.SUCCEEDED, AttemptFailureCategory.NONE, 0, "Smoke valid",
                        Map.of("durationMs", 35), Instant.now(), Instant.now(), true
                ),
                principal
        );

        var completedRun = pipelineOrchestrationService.getRun(workspaceA.getId(), run.id(), ownerA);
        assertThat(completedRun.status()).isEqualTo(PipelineRunStatus.SUCCEEDED);

        var storedLogs = workerExecutionLogService.listWorkspaceStageLogs(
                workspaceA.getId(), run.id(), leased1.stage().stageRunId(), 1, PageRequest.of(0, 10), ownerA.getId()
        );
        assertThat(storedLogs.items()).hasSize(1);
        assertThat(storedLogs.items().get(0).message()).contains("[REDACTED]").doesNotContain("secret_pg_token_12345");
    }

    private LinkedRepositoryEntity saveRepo(
            WorkspaceEntity ws,
            GitHubInstallationEntity inst,
            long ghRepoId,
            String owner,
            String name,
            String visibility,
            String description,
            RepositoryConnectionStatus status
    ) {
        LinkedRepositoryEntity repo = new LinkedRepositoryEntity();
        repo.setWorkspace(ws);
        repo.setInstallation(inst);
        repo.setGithubRepoId(ghRepoId);
        repo.setOwnerLogin(owner);
        repo.setName(name);
        repo.setFullName(owner + "/" + name);
        repo.setDefaultBranch("main");
        repo.setVisibility(visibility);
        repo.setDescription(description);
        repo.setConnectionStatus(status);
        repo.setLastSyncedAt(Instant.now());
        return linkedRepositoryRepository.save(repo);
    }

    private ProjectEntity saveProject(
            WorkspaceEntity ws,
            UserEntity creator,
            LinkedRepositoryEntity repo,
            String name,
            String slug,
            String description,
            ProjectStatus status
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(ws);
        project.setCreatedBy(creator);
        project.setLinkedRepository(repo);
        project.setName(name);
        project.setSlug(slug);
        project.setDescription(description);
        project.setDefaultBranch("main");
        project.setStatus(status);
        return projectRepository.save(project);
    }
}
