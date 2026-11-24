package com.cloudforge.backend.integration;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.github.GitHubActionsPermissionException;
import com.cloudforge.backend.github.GitHubApiClient;
import com.cloudforge.backend.github.GitHubInstallationRevokedException;
import com.cloudforge.backend.github.GitHubInstallationSuspendedException;
import com.cloudforge.backend.github.GitHubRateLimitException;
import com.cloudforge.backend.github.GitHubRepositoryNotFoundException;
import com.cloudforge.backend.github.GitHubWorkflowJobMetadata;
import com.cloudforge.backend.github.GitHubWorkflowMetadata;
import com.cloudforge.backend.github.GitHubWorkflowRunMetadata;
import com.cloudforge.backend.github.GitHubWorkflowStepMetadata;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRepository;
import com.cloudforge.backend.repository.GitHubWorkflowRunRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GitHubActionsPipelinesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @Autowired
    private LinkedRepositoryRepository linkedRepositoryRepository;

    @Autowired
    private GitHubWorkflowRepository workflowRepository;

    @Autowired
    private GitHubWorkflowRunRepository workflowRunRepository;

    @MockitoBean
    private GitHubApiClient gitHubApiClient;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private GitHubInstallationEntity installationA;
    private LinkedRepositoryEntity repoA1;
    private LinkedRepositoryEntity repoA2;
    private LinkedRepositoryEntity repoB1;
    private String previousPrivateKey;

    @BeforeEach
    void setUp() {
        previousPrivateKey = appProperties.getGithubApp().getPrivateKey();
        appProperties.getGithubApp().setPrivateKey("mock-configured-for-actions-test");

        owner = userAccountService.upsertFromGitHubOAuth(81001L, "actions-owner", "Actions Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(81002L, "actions-dev", "Actions Dev", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(81003L, "actions-viewer", "Actions Viewer", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(81004L, "actions-outsider", "Actions Outsider", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        addMember(workspaceA, developer, WorkspaceRole.DEVELOPER);
        addMember(workspaceA, viewer, WorkspaceRole.VIEWER);

        installationA = saveInstallation(workspaceA, 66101L, "actions-owner");
        GitHubInstallationEntity installationB = saveInstallation(workspaceB, 66102L, "actions-outsider");

        repoA1 = saveRepository(workspaceA, installationA, 77101L, "actions-owner", "payments-api");
        repoA2 = saveRepository(workspaceA, installationA, 77102L, "actions-owner", "docs-portal");
        repoB1 = saveRepository(workspaceB, installationB, 77201L, "actions-outsider", "secret-ws-b-repo");
    }

    @AfterEach
    void tearDown() {
        appProperties.getGithubApp().setPrivateKey(previousPrivateKey);
    }

    @Test
    void enforcesAuthenticationWorkspaceRbacCsrfAndCrossWorkspaceIsolation() throws Exception {
        // 1. Unauthenticated requests -> 401
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines", workspaceA.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        // 2. Outsider cannot access Workspace A overview, workflows, runs, or sync -> 403
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines", workspaceA.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/repositories/{repo}/workflows", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(outsider))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // 3. Cross-workspace repository access denied (Workspace B owner passing Workspace A repoId under Workspace B path -> 404)
        mockMvc.perform(get("/api/workspaces/{ws}/repositories/{repo}/workflows", workspaceB.getId(), repoA1.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceB.getId(), repoA1.getId())
                        .with(authenticatedUser(outsider))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // 4. VIEWER can read overview/workflows/runs, but CANNOT trigger sync (403)
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines", workspaceA.getId())
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectedRepositoryCount").value(2))
                .andExpect(jsonPath("$.workflowCount").value(0));

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(viewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // 5. Missing CSRF token on sync -> 403 even for OWNER
        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void synchronizesWorkflowsRunsIdempotentlyTracksTransitionsAttemptsAndJobsWithoutTokenExposure() throws Exception {
        Instant t1 = Instant.parse("2026-11-24T01:00:00Z");
        Instant t2 = Instant.parse("2026-11-24T01:02:00Z");
        Instant t3 = Instant.parse("2026-11-24T01:05:00Z");

        GitHubWorkflowMetadata ciWorkflow = new GitHubWorkflowMetadata(
                1001L, "CloudForge CI", ".github/workflows/ci.yml", "active",
                "https://github.com/actions-owner/payments-api/blob/main/.github/workflows/ci.yml",
                null, t1, t2
        );
        GitHubWorkflowMetadata releaseWorkflow = new GitHubWorkflowMetadata(
                1002L, "Container Audit", ".github/workflows/audit.yml", "active",
                "https://github.com/actions-owner/payments-api/blob/main/.github/workflows/audit.yml",
                null, t1, t2
        );

        // Initial sync: run 5001 attempt 1 is in_progress, run 5002 is completed/failure
        when(gitHubApiClient.listRepositoryWorkflows(66101L, "actions-owner", "payments-api"))
                .thenReturn(List.of(ciWorkflow, releaseWorkflow));
        when(gitHubApiClient.listRepositoryWorkflowRuns(eq(66101L), eq("actions-owner"), eq("payments-api"), anyInt()))
                .thenReturn(List.of(
                        new GitHubWorkflowRunMetadata(
                                5001L, 1001L, 12, 1, "CloudForge CI", "Implement Phase 3.1",
                                "pull_request", "feat/phase3-1", "abc1234567890",
                                "in_progress", null, "actions-dev", "https://avatars.githubusercontent.com/u/81002",
                                "https://github.com/actions-owner/payments-api/actions/runs/5001",
                                t1, t2, t1
                        ),
                        new GitHubWorkflowRunMetadata(
                                5002L, 1002L, 4, 1, "Container Audit", "Nightly CVE check",
                                "schedule", "main", "def9876543210",
                                "completed", "failure", "actions-owner", null,
                                "https://github.com/actions-owner/payments-api/actions/runs/5002",
                                t1.minusSeconds(3600), t1.minusSeconds(3480), t1.minusSeconds(3590)
                        )
                ));

        // DEVELOPER triggers initial sync
        MvcResult firstSync = mockMvc.perform(
                        post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                                .with(authenticatedUser(developer))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(2))
                .andExpect(jsonPath("$.runsSynced").value(2))
                .andExpect(jsonPath("$.runsCreated").value(2))
                .andExpect(jsonPath("$.runsUpdated").value(0))
                .andReturn();
        assertNoTokensInPayload(firstSync.getResponse().getContentAsString());

        // Overview reflects real database totals (2 workflows, 2 runs, 1 active, 1 failed)
        MvcResult overviewRes = mockMvc.perform(get("/api/workspaces/{ws}/pipelines", workspaceA.getId())
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoriesWithWorkflowsCount").value(1))
                .andExpect(jsonPath("$.workflowCount").value(2))
                .andExpect(jsonPath("$.recentRunCount").value(2))
                .andExpect(jsonPath("$.activeRunCount").value(1))
                .andExpect(jsonPath("$.failedRunCount").value(1))
                .andExpect(jsonPath("$.succeededRunCount").value(0))
                .andReturn();
        assertNoTokensInPayload(overviewRes.getResponse().getContentAsString());

        // Second sync:
        // - Run 5001 attempt 1 transitions in_progress -> completed/failure (idempotent UPDATE, no duplicate)
        // - Run 5001 attempt 2 (retry!) is created as distinct row completed/success
        // - Workflow 1002 removed from GitHub -> marked state=deleted while preserving historical run 5002
        when(gitHubApiClient.listRepositoryWorkflows(66101L, "actions-owner", "payments-api"))
                .thenReturn(List.of(ciWorkflow));
        when(gitHubApiClient.listRepositoryWorkflowRuns(eq(66101L), eq("actions-owner"), eq("payments-api"), anyInt()))
                .thenReturn(List.of(
                        new GitHubWorkflowRunMetadata(
                                5001L, 1001L, 12, 1, "CloudForge CI", "Implement Phase 3.1",
                                "pull_request", "feat/phase3-1", "abc1234567890",
                                "completed", "failure", "actions-dev", null,
                                "https://github.com/actions-owner/payments-api/actions/runs/5001",
                                t1, t2, t1
                        ),
                        new GitHubWorkflowRunMetadata(
                                5001L, 1001L, 12, 2, "CloudForge CI", "Implement Phase 3.1",
                                "pull_request", "feat/phase3-1", "abc1234567890",
                                "completed", "success", "actions-dev", null,
                                "https://github.com/actions-owner/payments-api/actions/runs/5001",
                                t2, t3, t2
                        )
                ));

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(1))
                .andExpect(jsonPath("$.runsSynced").value(2))
                .andExpect(jsonPath("$.runsCreated").value(1))
                .andExpect(jsonPath("$.runsUpdated").value(1));

        // Verify idempotency: exactly 3 run rows total (5001#1 updated, 5001#2 created, 5002#1 preserved)
        assertThat(workflowRunRepository.findAllByLinkedRepositoryId(repoA1.getId())).hasSize(3);
        assertThat(workflowRepository.findByLinkedRepositoryIdAndGithubWorkflowId(repoA1.getId(), 1002L)
                .orElseThrow().getState()).isEqualTo("deleted");

        // List workflows returns latest run metadata
        mockMvc.perform(get("/api/workspaces/{ws}/repositories/{repo}/workflows", workspaceA.getId(), repoA1.getId())
                        .param("state", "active")
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("CloudForge CI"))
                .andExpect(jsonPath("$.items[0].latestRunStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].latestRunNumber").value(12));

        // List runs and inspect run detail + live jobs
        MvcResult runsRes = mockMvc.perform(
                        get("/api/workspaces/{ws}/repositories/{repo}/workflow-runs", workspaceA.getId(), repoA1.getId())
                                .param("status", "SUCCESS")
                                .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].runAttempt").value(2))
                .andExpect(jsonPath("$.items[0].durationSeconds").value(180))
                .andReturn();

        JsonNode runsJson = objectMapper.readTree(runsRes.getResponse().getContentAsString());
        UUID runId = UUID.fromString(runsJson.path("items").get(0).path("id").asText());

        // Single run detail works for workspace member and is denied cross-workspace
        mockMvc.perform(get("/api/workspaces/{ws}/workflow-runs/{runId}", workspaceA.getId(), runId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubRunId").value(5001))
                .andExpect(jsonPath("$.normalizedStatus").value("SUCCESS"));

        mockMvc.perform(get("/api/workspaces/{ws}/workflow-runs/{runId}", workspaceB.getId(), runId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());

        // Live jobs mapping preserves job & step order and computes durations
        when(gitHubApiClient.listWorkflowRunJobs(66101L, "actions-owner", "payments-api", 5001L))
                .thenReturn(List.of(
                        new GitHubWorkflowJobMetadata(
                                9001L, 5001L, 2, "Backend Test & Package", "completed", "success",
                                t2, t2.plusSeconds(95),
                                "https://github.com/actions-owner/payments-api/actions/runs/5001/job/9001",
                                "GitHub Actions 1",
                                List.of(
                                        new GitHubWorkflowStepMetadata(1, "Checkout", "completed", "success", t2, t2.plusSeconds(5)),
                                        new GitHubWorkflowStepMetadata(2, "mvn test", "completed", "success", t2.plusSeconds(5), t2.plusSeconds(90))
                                )
                        ),
                        new GitHubWorkflowJobMetadata(
                                9002L, 5001L, 2, "Frontend Build", "completed", "skipped",
                                t2, t2,
                                "https://github.com/actions-owner/payments-api/actions/runs/5001/job/9002",
                                null,
                                List.of()
                        )
                ));

        MvcResult jobsRes = mockMvc.perform(get("/api/workspaces/{ws}/workflow-runs/{runId}/jobs", workspaceA.getId(), runId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(2))
                .andExpect(jsonPath("$.jobs[0].name").value("Backend Test & Package"))
                .andExpect(jsonPath("$.jobs[0].durationSeconds").value(95))
                .andExpect(jsonPath("$.jobs[0].steps[0].name").value("Checkout"))
                .andExpect(jsonPath("$.jobs[0].steps[1].name").value("mvn test"))
                .andExpect(jsonPath("$.jobs[1].normalizedStatus").value("SKIPPED"))
                .andReturn();
        assertNoTokensInPayload(jobsRes.getResponse().getContentAsString());

        // Cross-workspace job access is denied (404)
        mockMvc.perform(get("/api/workspaces/{ws}/workflow-runs/{runId}/jobs", workspaceB.getId(), runId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void handlesEmptyWorkflowsPermissionErrorsRateLimitsMissingRepoSuspendedAndRevokedInstallations() throws Exception {
        // 1. Empty workflow list succeeds cleanly
        when(gitHubApiClient.listRepositoryWorkflows(66101L, "actions-owner", "docs-portal")).thenReturn(List.of());
        when(gitHubApiClient.listRepositoryWorkflowRuns(eq(66101L), eq("actions-owner"), eq("docs-portal"), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA2.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(0))
                .andExpect(jsonPath("$.runsSynced").value(0))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no GitHub Actions workflows")));

        // 2. Missing Actions: Read permission -> 403 with clear guidance (installation remains ACTIVE!)
        doThrow(new GitHubActionsPermissionException("GitHub App installation lacks 'Actions: Read' permission."))
                .when(gitHubApiClient).listRepositoryWorkflows(66101L, "actions-owner", "payments-api");

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Actions: Read")));
        assertThat(installationRepository.findById(installationA.getId()).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.ACTIVE);

        // 3. Rate limit -> 429 with Retry-After header
        doThrow(new GitHubRateLimitException("GitHub API rate limit exceeded. Retry after approximately 45 seconds.", 45L))
                .when(gitHubApiClient).listRepositoryWorkflows(66101L, "actions-owner", "payments-api");

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rate limit")));

        // 4. Missing repository (404) -> marks ONLY repoA2 DISCONNECTED, leaves installationA ACTIVE & repoA1 CONNECTED
        doThrow(new GitHubRepositoryNotFoundException("Not found on GitHub"))
                .when(gitHubApiClient).listRepositoryWorkflows(66101L, "actions-owner", "docs-portal");

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA2.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(linkedRepositoryRepository.findById(repoA2.getId()).orElseThrow().getConnectionStatus())
                .isEqualTo(RepositoryConnectionStatus.DISCONNECTED);
        assertThat(linkedRepositoryRepository.findById(repoA1.getId()).orElseThrow().getConnectionStatus())
                .isEqualTo(RepositoryConnectionStatus.CONNECTED);

        // 5. Suspended installation -> marks installation SUSPENDED and repos SUSPENDED -> 409
        doThrow(new GitHubInstallationSuspendedException("Suspended"))
                .when(gitHubApiClient).listRepositoryWorkflows(66101L, "actions-owner", "payments-api");

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("suspended")));
        assertThat(installationRepository.findById(installationA.getId()).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.SUSPENDED);

        // Restore ACTIVE to test revocation path cleanly
        installationA.setStatus(InstallationStatus.ACTIVE);
        installationRepository.save(installationA);
        repoA1.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        linkedRepositoryRepository.save(repoA1);

        // 6. Revoked installation -> marks installation REVOKED and repos DISCONNECTED -> 409
        doThrow(new GitHubInstallationRevokedException("Revoked"))
                .when(gitHubApiClient).listRepositoryWorkflows(66101L, "actions-owner", "payments-api");

        mockMvc.perform(post("/api/workspaces/{ws}/repositories/{repo}/workflows/sync", workspaceA.getId(), repoA1.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("revoked")));
        assertThat(installationRepository.findById(installationA.getId()).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.REVOKED);
        assertThat(linkedRepositoryRepository.findById(repoA1.getId()).orElseThrow().getConnectionStatus())
                .isEqualTo(RepositoryConnectionStatus.DISCONNECTED);
    }

    private void addMember(WorkspaceEntity ws, UserEntity user, WorkspaceRole role) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspace(ws);
        member.setUser(user);
        member.setRole(role);
        workspaceMemberRepository.save(member);
    }

    private GitHubInstallationEntity saveInstallation(WorkspaceEntity ws, long ghInstallId, String login) {
        GitHubInstallationEntity inst = new GitHubInstallationEntity();
        inst.setWorkspace(ws);
        inst.setGithubInstallationId(ghInstallId);
        inst.setAccountLogin(login);
        inst.setAccountType("User");
        inst.setStatus(InstallationStatus.ACTIVE);
        return installationRepository.save(inst);
    }

    private LinkedRepositoryEntity saveRepository(
            WorkspaceEntity ws,
            GitHubInstallationEntity inst,
            long ghRepoId,
            String ownerLogin,
            String name
    ) {
        LinkedRepositoryEntity repo = new LinkedRepositoryEntity();
        repo.setWorkspace(ws);
        repo.setInstallation(inst);
        repo.setGithubRepoId(ghRepoId);
        repo.setOwnerLogin(ownerLogin);
        repo.setName(name);
        repo.setFullName(ownerLogin + "/" + name);
        repo.setDefaultBranch("main");
        repo.setVisibility("private");
        repo.setHtmlUrl("https://github.com/" + ownerLogin + "/" + name);
        repo.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        repo.setLastSyncedAt(Instant.now());
        return linkedRepositoryRepository.save(repo);
    }

    private void assertNoTokensInPayload(String body) {
        assertThat(body).doesNotContain("ghs_");
        assertThat(body).doesNotContain("gho_");
        assertThat(body).doesNotContain("ghp_");
        assertThat(body).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(body).doesNotContain("Bearer ");
    }
}
