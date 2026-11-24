package com.cloudforge.backend.secrets;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.EnvKeyringSecretKeyProvider;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "cloudforge.workers.enabled=true",
        "cloudforge.workers.scheduler-enabled=false",
        "cloudforge.secrets.enabled=true",
        "cloudforge.secrets.key-provider=env-keyring",
        "cloudforge.secrets.active-key-id=test-kek-v1",
        "cloudforge.secrets.keyring=test-kek-v1:MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=,test-kek-v2:QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=",
        "cloudforge.secrets.worker-delivery-enabled=true",
        "cloudforge.secrets.allow-insecure-local-delivery=false",
        "cloudforge.secrets.max-deliveries-per-attempt=2",
        "cloudforge.secrets.denied-audit-cooldown-seconds=0",
        "cloudforge.secrets.operator-rotation-enabled=true",
        "cloudforge.secrets.operator-rotation-token=operator-test-bearer-token-34-chars"
})
@Transactional
class EnvSecretLifecycleAndRbacIntegrationTest {

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
    private ProjectEnvironmentRepository environmentRepository;
    @Autowired
    private EnvironmentSecretRepository secretRepository;
    @Autowired
    private EnvironmentSecretVersionRepository versionRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private SecretManagementProperties secretProperties;
    @Autowired
    private EnvKeyringSecretKeyProvider keyProvider;
    @Autowired
    private SecretAuditService secretAuditService;

    private UserEntity ownerA;
    private UserEntity devA;
    private UserEntity viewerA;
    private UserEntity outsiderB;
    private WorkspaceEntity workspaceA;
    private ProjectEntity projectA;
    private ProjectEnvironmentEntity devEnvA;
    private ProjectEnvironmentEntity stagingEnvA;

    @BeforeEach
    void setup() {
        secretAuditService.clearRateLimitState();

        ownerA = userAccountService.upsertFromGitHubOAuth(94001L, "sec-owner-a", "Owner A", "sec-owner@a.test", null, null);
        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(ownerA.getId()).get(0).getWorkspace();
        devA = userAccountService.upsertFromGitHubOAuth(94002L, "sec-dev-a", "Dev A", "sec-dev@a.test", null, null);
        viewerA = userAccountService.upsertFromGitHubOAuth(94003L, "sec-viewer-a", "Viewer A", "sec-viewer@a.test", null, null);
        outsiderB = userAccountService.upsertFromGitHubOAuth(94004L, "sec-outsider-b", "Outsider B", "sec-out@b.test", null, null);

        WorkspaceMemberEntity devMember = new WorkspaceMemberEntity();
        devMember.setWorkspace(workspaceA);
        devMember.setUser(devA);
        devMember.setRole(WorkspaceRole.DEVELOPER);
        workspaceMemberRepository.save(devMember);

        WorkspaceMemberEntity viewerMember = new WorkspaceMemberEntity();
        viewerMember.setWorkspace(workspaceA);
        viewerMember.setUser(viewerA);
        viewerMember.setRole(WorkspaceRole.VIEWER);
        workspaceMemberRepository.save(viewerMember);

        projectA = new ProjectEntity();
        projectA.setWorkspace(workspaceA);
        projectA.setCreatedBy(ownerA);
        projectA.setName("Secret Core App");
        projectA.setSlug("secret-core-app");
        projectA.setDefaultBranch("main");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.saveAndFlush(projectA);

        devEnvA = new ProjectEnvironmentEntity();
        devEnvA.setProject(projectA);
        devEnvA.setName("Development");
        devEnvA.setEnvironmentType(EnvironmentType.DEVELOPMENT);
        devEnvA.setConfigVariablesJson("{}");
        devEnvA = environmentRepository.saveAndFlush(devEnvA);

        stagingEnvA = new ProjectEnvironmentEntity();
        stagingEnvA.setProject(projectA);
        stagingEnvA.setName("Staging");
        stagingEnvA.setEnvironmentType(EnvironmentType.STAGING);
        stagingEnvA.setConfigVariablesJson("{}");
        stagingEnvA = environmentRepository.saveAndFlush(stagingEnvA);
    }

    @AfterEach
    void restoreKeyProviderState() {
        secretProperties.setEnabled(true);
        secretProperties.setActiveKeyId("test-kek-v1");
        secretProperties.setDeniedAuditCooldownSeconds(0);
        keyProvider.reloadFromProperties();
    }

    @Test
    void fullWriteOnlyEncryptedLifecycleRotationRevocationDeletionAndMetadataSafety() throws Exception {
        String baseUrl = "/api/workspaces/" + workspaceA.getId()
                + "/projects/" + projectA.getId()
                + "/environments/" + stagingEnvA.getId() + "/secrets";

        String v1Plain = "cf_live_ultra_secret_token_v1_9988";
        String v2Plain = "cf_live_ultra_secret_token_v2_7766";

        // 1. CSRF required for mutating secret requests
        mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(ownerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DEPLOY_API_KEY",
                                  "value": "%s",
                                  "description": "Staging token"
                                }
                                """.formatted(v1Plain)))
                .andExpect(status().isForbidden());

        // 2. Create secret -> returns 201 metadata only with Cache-Control: no-store
        MvcResult createRes = mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DEPLOY_API_KEY",
                                  "value": "%s",
                                  "description": "Staging token"
                                }
                                """.formatted(v1Plain)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.name").value("DEPLOY_API_KEY"))
                .andExpect(jsonPath("$.currentVersionNumber").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentKekId").value("test-kek-v1"))
                .andReturn();

        String createJson = createRes.getResponse().getContentAsString();
        assertThat(createJson)
                .doesNotContain(v1Plain)
                .doesNotContain("ciphertext")
                .doesNotContain("wrappedDek")
                .doesNotContain("nonce")
                .doesNotContain("MTIzNDU");

        UUID secretId = UUID.fromString(objectMapper.readTree(createJson).get("id").asText());

        // Verify stored DB columns contain ciphertext + wrapped DEK, never plaintext
        EnvironmentSecretVersionEntity dbV1 = versionRepository
                .findAllBySecretIdAndWorkspaceIdOrderByVersionNumberDesc(secretId, workspaceA.getId())
                .get(0);
        assertThat(new String(dbV1.getCiphertext(), StandardCharsets.UTF_8)).doesNotContain(v1Plain);
        assertThat(dbV1.getNonce()).hasSize(12);
        assertThat(Base64.getEncoder().encodeToString(dbV1.getWrappedDek())).doesNotContain("MTIzNDU");

        // 3. Duplicate name rejected cleanly (409) and environment isolation enforced
        mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DEPLOY_API_KEY\",\"value\":\"dup\"}"))
                .andExpect(status().isConflict());

        // Same secret name IS allowed independently in Development environment
        mockMvc.perform(post("/api/workspaces/" + workspaceA.getId()
                        + "/projects/" + projectA.getId()
                        + "/environments/" + devEnvA.getId() + "/secrets")
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DEPLOY_API_KEY\",\"value\":\"dev_only_val_123\"}"))
                .andExpect(status().isCreated());

        // 4. Rotate secret -> v2 becomes active while v1 remains in version history
        mockMvc.perform(post(baseUrl + "/" + secretId + "/rotate")
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "value": "%s",
                                  "description": "Rotated staging token"
                                }
                                """.formatted(v2Plain)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.currentVersionNumber").value(2));

        MvcResult versionsRes = mockMvc.perform(get(baseUrl + "/" + secretId + "/versions")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].versionNumber").value(1))
                .andExpect(jsonPath("$[1].current").value(false))
                .andReturn();
        assertThat(versionsRes.getResponse().getContentAsString())
                .doesNotContain(v1Plain)
                .doesNotContain(v2Plain);

        // 5. Revoke entire secret -> REVOKED, cannot rotate further, still visible for audit
        mockMvc.perform(post(baseUrl + "/" + secretId + "/revoke")
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Incident test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(post(baseUrl + "/" + secretId + "/rotate")
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"new_val_should_fail\"}"))
                .andExpect(status().isConflict());

        // 6. Soft-delete secret -> hidden from default listing & allows safe name recreation
        mockMvc.perform(delete(baseUrl + "/" + secretId)
                        .with(authenticatedUser(ownerA))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(baseUrl).with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        EnvironmentSecretEntity deletedRow = secretRepository.findById(secretId).orElseThrow();
        assertThat(deletedRow.getStatus()).isEqualTo(SecretLifecycleStatus.DELETED);
        assertThat(versionRepository.findAllBySecretIdAndStatus(secretId, SecretVersionStatus.ACTIVE)).isEmpty();

        // Can create secret with same name again after deletion
        mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DEPLOY_API_KEY\",\"value\":\"fresh_v1_after_delete\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersionNumber").value(1));

        // Verify audit trail captured actions cleanly with zero plaintext secrets
        var audits = auditEventRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceA.getId(), PageRequest.of(0, 50));
        assertThat(audits.getContent()).extracting("action").contains(
                SecretAuditService.ACTION_SECRET_CREATED,
                SecretAuditService.ACTION_SECRET_ROTATED,
                SecretAuditService.ACTION_SECRET_REVOKED,
                SecretAuditService.ACTION_SECRET_DELETED
        );
        audits.getContent().forEach(evt -> {
            assertThat(evt.getSummary()).doesNotContain(v1Plain).doesNotContain(v2Plain);
            assertThat(evt.getMetadataJson()).doesNotContain(v1Plain).doesNotContain(v2Plain);
        });
    }

    @Test
    void enforcesStrictRbacInputValidationEnvironmentPlaintextRejectionAndFailClosedProviderOutage() throws Exception {
        String baseUrl = "/api/workspaces/" + workspaceA.getId()
                + "/projects/" + projectA.getId()
                + "/environments/" + devEnvA.getId() + "/secrets";

        // RBAC: DEVELOPER, VIEWER, and OUTSIDER cannot list or create secrets (403 + denial audit)
        mockMvc.perform(get(baseUrl).with(authenticatedUser(devA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(baseUrl).with(authenticatedUser(viewerA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(baseUrl).with(authenticatedUser(outsiderB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(devA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DEV_BLOCKED_SECRET\",\"value\":\"secret_val\"}"))
                .andExpect(status().isForbidden());

        // Check DEVELOPER can still update non-secret Development env vars (no regression!)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/workspaces/" + workspaceA.getId() + "/projects/" + projectA.getId()
                                + "/environments/" + devEnvA.getId())
                        .with(authenticatedUser(devA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Development",
                                  "configVariables": {"LOG_LEVEL": "debug"}
                                }
                                """))
                .andExpect(status().isOk());

        // Plaintext secret pattern STILL blocked on non-secret environment config endpoint
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/workspaces/" + workspaceA.getId() + "/projects/" + projectA.getId()
                                + "/environments/" + devEnvA.getId())
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Development",
                                  "configVariables": {"API_TOKEN": "ghp_1234567890abcdef"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Invalid & dangerous OS env variable names rejected
        for (String badName : new String[]{"bad-name", "lowercase_name", "PATH", "LD_PRELOAD", "CLOUDFORGE_WORKER_TOKEN"}) {
            mockMvc.perform(post(baseUrl)
                            .with(authenticatedUser(ownerA))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + badName + "\",\"value\":\"valid_secret_123\"}"))
                    .andExpect(status().isBadRequest());
        }

        // Disabled/unavailable key provider fails closed (503) without fallback
        secretProperties.setEnabled(false);
        keyProvider.reloadFromProperties();
        mockMvc.perform(post(baseUrl)
                        .with(authenticatedUser(ownerA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"FAIL_CLOSED_TEST\",\"value\":\"should_not_store\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
