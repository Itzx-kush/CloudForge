package com.cloudforge.backend.integration;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.github.WebhookSignatureVerifier;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GitHubWebhookIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

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

    private WorkspaceEntity workspace;
    private GitHubInstallationEntity installation;

    @BeforeEach
    void setUp() {
        UserEntity owner = userAccountService.upsertFromGitHubOAuth(80001L, "wh-owner", "WH Owner", null, null, null);
        workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();

        GitHubInstallationEntity inst = new GitHubInstallationEntity();
        inst.setWorkspace(workspace);
        inst.setGithubInstallationId(44001L);
        inst.setAccountLogin("wh-owner");
        inst.setAccountType("User");
        inst.setStatus(InstallationStatus.ACTIVE);
        installation = installationRepository.save(inst);

        LinkedRepositoryEntity repo = new LinkedRepositoryEntity();
        repo.setWorkspace(workspace);
        repo.setInstallation(installation);
        repo.setGithubRepoId(88001L);
        repo.setOwnerLogin("wh-owner");
        repo.setName("api-gateway");
        repo.setFullName("wh-owner/api-gateway");
        repo.setDefaultBranch("main");
        repo.setVisibility("private");
        repo.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        repo.setLastSyncedAt(Instant.now());
        linkedRepositoryRepository.save(repo);
    }

    @Test
    void rejectsMissingAndInvalidWebhookSignatures() throws Exception {
        byte[] payload = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);

        // Missing signature header
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "del-missing-sig")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Invalid signature header
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "del-bad-sig")
                        .header("X-Hub-Signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void verifiesValidSignatureDeduplicatesDeliveriesAndIgnoresUnsupportedPushEvents() throws Exception {
        String secret = appProperties.getGithubApp().getWebhookSecret();
        byte[] pingPayload = "{\"zen\":\"Non-blocking is better than blocking.\"}".getBytes(StandardCharsets.UTF_8);
        String pingSig = signatureVerifier.computeSignatureHeader(pingPayload, secret);

        // First ping processed
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "del-ping-001")
                        .header("X-Hub-Signature-256", pingSig)
                        .content(pingPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        // Duplicate delivery safely deduplicated
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "del-ping-001")
                        .header("X-Hub-Signature-256", pingSig)
                        .content(pingPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        // Unsupported push event safely ignored (202) — no builds/deployments triggered in Phase 2
        byte[] pushPayload = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        String pushSig = signatureVerifier.computeSignatureHeader(pushPayload, secret);
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", "del-push-001")
                        .header("X-Hub-Signature-256", pushSig)
                        .content(pushPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void processesInstallationAndRepositoryLifecycleWebhooks() throws Exception {
        String secret = appProperties.getGithubApp().getWebhookSecret();

        // 1. installation.suspend -> marks installation and repos SUSPENDED
        byte[] suspendPayload = "{\"action\":\"suspend\",\"installation\":{\"id\":44001}}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "installation")
                        .header("X-GitHub-Delivery", "del-inst-suspend")
                        .header("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(suspendPayload, secret))
                        .content(suspendPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        assertThat(installationRepository.findByGithubInstallationId(44001L).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.SUSPENDED);
        assertThat(linkedRepositoryRepository.findByWorkspaceIdAndGithubRepoId(workspace.getId(), 88001L)
                .orElseThrow().getConnectionStatus())
                .isEqualTo(RepositoryConnectionStatus.SUSPENDED);

        // 2. installation.unsuspend -> restores ACTIVE / CONNECTED
        byte[] unsuspendPayload = "{\"action\":\"unsuspend\",\"installation\":{\"id\":44001}}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "installation")
                        .header("X-GitHub-Delivery", "del-inst-unsuspend")
                        .header("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(unsuspendPayload, secret))
                        .content(unsuspendPayload))
                .andExpect(status().isOk());

        assertThat(installationRepository.findByGithubInstallationId(44001L).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.ACTIVE);

        // 3. installation_repositories.added -> creates new linked repo
        byte[] addedPayload = """
                {
                  "action": "added",
                  "installation": {"id": 44001},
                  "repositories_added": [
                    {"id": 88002, "name": "billing-worker", "full_name": "wh-owner/billing-worker", "private": false}
                  ],
                  "repositories_removed": []
                }
                """.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "installation_repositories")
                        .header("X-GitHub-Delivery", "del-inst-repos-add")
                        .header("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(addedPayload, secret))
                        .content(addedPayload))
                .andExpect(status().isOk());

        var addedRepo = linkedRepositoryRepository.findByWorkspaceIdAndGithubRepoId(workspace.getId(), 88002L).orElseThrow();
        assertThat(addedRepo.getFullName()).isEqualTo("wh-owner/billing-worker");
        assertThat(addedRepo.getVisibility()).isEqualTo("public");
        assertThat(addedRepo.getConnectionStatus()).isEqualTo(RepositoryConnectionStatus.CONNECTED);

        // 4. installation.deleted -> revokes installation and disconnects all repositories
        byte[] deletePayload = "{\"action\":\"deleted\",\"installation\":{\"id\":44001}}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "installation")
                        .header("X-GitHub-Delivery", "del-inst-deleted")
                        .header("X-Hub-Signature-256", signatureVerifier.computeSignatureHeader(deletePayload, secret))
                        .content(deletePayload))
                .andExpect(status().isOk());

        assertThat(installationRepository.findByGithubInstallationId(44001L).orElseThrow().getStatus())
                .isEqualTo(InstallationStatus.REVOKED);
        assertThat(linkedRepositoryRepository.findByWorkspaceIdAndGithubRepoId(workspace.getId(), 88002L)
                .orElseThrow().getConnectionStatus())
                .isEqualTo(RepositoryConnectionStatus.DISCONNECTED);
    }
}
