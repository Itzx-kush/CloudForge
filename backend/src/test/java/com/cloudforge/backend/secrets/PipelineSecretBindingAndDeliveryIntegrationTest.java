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
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.EnvKeyringSecretKeyProvider;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.security.WorkerCredentialService;
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

import java.time.Instant;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class PipelineSecretBindingAndDeliveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectEnvironmentRepository environmentRepository;
    @Autowired
    private WorkerCredentialService workerCredentialService;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private SecretManagementProperties secretProperties;
    @Autowired
    private EnvKeyringSecretKeyProvider keyProvider;
    @Autowired
    private SecretAuditService secretAuditService;

    private UserEntity owner;
    private UserEntity developer;
    private WorkspaceEntity workspace;
    private ProjectEntity project;
    private ProjectEnvironmentEntity stagingEnv;
    private ProjectEnvironmentEntity prodEnv;
    private String worker1Id;
    private String worker1Token;
    private String worker2Id;
    private String worker2Token;

    @BeforeEach
    void setup() {
        secretAuditService.clearRateLimitState();
        secretProperties.setEnabled(true);
        secretProperties.setWorkerDeliveryEnabled(true);
        secretProperties.setAllowInsecureLocalDelivery(false);
        secretProperties.setMaxDeliveriesPerAttempt(2);
        secretProperties.setDeniedAuditCooldownSeconds(0);
        keyProvider.reloadFromProperties();

        long uid = 96000L + (System.nanoTime() % 10000);
        owner = userAccountService.upsertFromGitHubOAuth(uid, "pipe-sec-owner-" + uid, "Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(uid + 1, "pipe-sec-dev-" + uid, "Dev", null, null, null);
        workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();

        WorkspaceMemberEntity devMem = new WorkspaceMemberEntity();
        devMem.setWorkspace(workspace);
        devMem.setUser(developer);
        devMem.setRole(WorkspaceRole.DEVELOPER);
        workspaceMemberRepository.save(devMem);

        project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setCreatedBy(owner);
        project.setName("Secret Delivery Project");
        project.setSlug("secret-delivery-" + uid);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.saveAndFlush(project);

        stagingEnv = new ProjectEnvironmentEntity();
        stagingEnv.setProject(project);
        stagingEnv.setName("Staging");
        stagingEnv.setEnvironmentType(EnvironmentType.STAGING);
        stagingEnv.setConfigVariablesJson("{}");
        stagingEnv = environmentRepository.saveAndFlush(stagingEnv);

        prodEnv = new ProjectEnvironmentEntity();
        prodEnv.setProject(project);
        prodEnv.setName("Production");
        prodEnv.setEnvironmentType(EnvironmentType.PRODUCTION);
        prodEnv.setConfigVariablesJson("{}");
        prodEnv = environmentRepository.saveAndFlush(prodEnv);

        worker1Id = "sec-worker-1-" + uid;
        String sec1 = "worker1-secret-token-" + uid;
        worker1Token = sec1;
        workerCredentialService.provisionCredential(worker1Id, "k1-" + uid, sec1, Instant.now().plusSeconds(3600), "Test w1");

        worker2Id = "sec-worker-2-" + uid;
        String sec2 = "worker2-secret-token-" + uid;
        worker2Token = sec2;
        workerCredentialService.provisionCredential(worker2Id, "k2-" + uid, sec2, Instant.now().plusSeconds(3600), "Test w2");
    }

    @AfterEach
    void cleanup() {
        if (workspace != null) {
            auditEventRepository.deleteAll();
            workspaceRepository.deleteById(workspace.getId());
        }
        secretProperties.setWorkerDeliveryEnabled(true);
        secretProperties.setAllowInsecureLocalDelivery(false);
    }

    @Test
    void enforcesStageBindingValidationQueuePinningLeaseFenceTlsAndRevocationFailClosed() throws Exception {
        String stagingSecretUrl = "/api/workspaces/" + workspace.getId() + "/projects/" + project.getId()
                + "/environments/" + stagingEnv.getId() + "/secrets";
        String prodSecretUrl = "/api/workspaces/" + workspace.getId() + "/projects/" + project.getId()
                + "/environments/" + prodEnv.getId() + "/secrets";

        MvcResult stgCreate = mockMvc.perform(post(stagingSecretUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SMOKE_API_KEY\",\"value\":\"stg-pinned-val-v1\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID stagingSecretId = UUID.fromString(objectMapper.readTree(stgCreate.getResponse().getContentAsString()).get("id").asText());

        MvcResult prodCreate = mockMvc.perform(post(prodSecretUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PROD_ONLY_KEY\",\"value\":\"prod-val-999\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID prodSecretId = UUID.fromString(objectMapper.readTree(prodCreate.getResponse().getContentAsString()).get("id").asText());

        String defsUrl = "/api/workspaces/" + workspace.getId() + "/pipelines/definitions";

        // 1. Cross-environment secret binding rejected
        mockMvc.perform(post(defsUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Cross Env Blocked",
                                  "targetEnvironmentId": "%s",
                                  "stages": [{
                                    "stageKey": "verify",
                                    "displayName": "Verify",
                                    "stageType": "SOURCE_PREP",
                                    "metadata": {"operation": "ENV_VALIDATION"},
                                    "secretBindings": [{"secretId": "%s", "deliveryAlias": "BAD_ALIAS"}]
                                  }]
                                }
                                """.formatted(project.getId(), stagingEnv.getId(), prodSecretId)))
                .andExpect(status().isBadRequest());

        // 2. Binding secret on BUILD/declarative stage rejected
        mockMvc.perform(post(defsUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Build Binding Blocked",
                                  "targetEnvironmentId": "%s",
                                  "stages": [{
                                    "stageKey": "build",
                                    "displayName": "Build",
                                    "stageType": "BUILD",
                                    "secretBindings": [{"secretId": "%s", "deliveryAlias": "DEPLOY_KEY"}]
                                  }]
                                }
                                """.formatted(project.getId(), stagingEnv.getId(), stagingSecretId)))
                .andExpect(status().isBadRequest());

        // 3. Valid OWNER pipeline definition with staging secret binding on SOURCE_PREP
        MvcResult defRes = mockMvc.perform(post(defsUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Staging Encrypted Verify",
                                  "targetEnvironmentId": "%s",
                                  "stages": [{
                                    "stageKey": "verify",
                                    "displayName": "Verify",
                                    "stageType": "SOURCE_PREP",
                                    "metadata": {"operation": "ENV_VALIDATION"},
                                    "secretBindings": [{"secretId": "%s", "deliveryAlias": "RUNTIME_API_TOKEN"}]
                                  }]
                                }
                                """.formatted(project.getId(), stagingEnv.getId(), stagingSecretId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasSecretBindings").value(true))
                .andExpect(jsonPath("$.targetEnvironmentId").value(stagingEnv.getId().toString()))
                .andReturn();

        UUID secretDefId = UUID.fromString(objectMapper.readTree(defRes.getResponse().getContentAsString()).get("id").asText());
        assertThat(defRes.getResponse().getContentAsString()).doesNotContain("stg-pinned-val-v1");

        // 4. Non-secret pipeline definition CAN be queued by DEVELOPER (no regression!)
        MvcResult nonSecDefRes = mockMvc.perform(post(defsUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Public Diagnostics",
                                  "stages": [{
                                    "stageKey": "diag",
                                    "displayName": "Diag",
                                    "stageType": "TEST",
                                    "metadata": {"operation": "WORKER_DIAGNOSTICS"}
                                  }]
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID nonSecDefId = UUID.fromString(objectMapper.readTree(nonSecDefRes.getResponse().getContentAsString()).get("id").asText());

        String runsUrl = "/api/workspaces/" + workspace.getId() + "/pipelines/runs";
        mockMvc.perform(post(runsUrl)
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"" + nonSecDefId + "\"}"))
                .andExpect(status().isCreated());

        // 5. DEVELOPER CANNOT queue secret-bearing pipeline run (403)
        mockMvc.perform(post(runsUrl)
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"" + secretDefId + "\"}"))
                .andExpect(status().isForbidden());

        // 6. OWNER queues secret-bearing run -> pins v1 without decrypting
        MvcResult runRes = mockMvc.perform(post(runsUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"" + secretDefId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasSecretBindings").value(true))
                .andExpect(jsonPath("$.stages[0].hasSecretBindings").value(true))
                .andExpect(jsonPath("$.stages[0].secretBindings[0].pinnedVersionNumber").value(1))
                .andReturn();
        assertThat(runRes.getResponse().getContentAsString()).doesNotContain("stg-pinned-val-v1");

        // Rotate secret to v2 AFTER run is queued -> run remains pinned to v1!
        mockMvc.perform(post(stagingSecretUrl + "/" + stagingSecretId + "/rotate")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"stg-new-val-v2\"}"))
                .andExpect(status().isOk());

        // Register workers and claim staged work (non-secret FIFO run #1 first, then secret-bound run #2)
        registerWorker(worker1Id, worker1Token);
        registerWorker(worker2Id, worker2Token);

        // Drain non-secret stage first via worker2
        MvcResult firstFifoClaim = mockMvc.perform(post("/api/internal/workers/" + worker2Id + "/claim")
                        .header("X-CloudForge-Worker-Id", worker2Id)
                        .header("Authorization", "Bearer " + worker2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSecretBindings").value(false))
                .andReturn();
        assertThat(firstFifoClaim.getResponse().getContentAsString()).doesNotContain("stg-pinned-val-v1");

        MvcResult claimRes = mockMvc.perform(post("/api/internal/workers/" + worker1Id + "/claim")
                        .header("X-CloudForge-Worker-Id", worker1Id)
                        .header("Authorization", "Bearer " + worker1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSecretBindings").value(true))
                .andExpect(jsonPath("$.secretAliases[0]").value("RUNTIME_API_TOKEN"))
                .andReturn();

        // Claim ticket must NEVER contain plaintext secrets!
        assertThat(claimRes.getResponse().getContentAsString())
                .doesNotContain("stg-pinned-val-v1")
                .doesNotContain("stg-new-val-v2");

        JsonNode leaseNode = objectMapper.readTree(claimRes.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(leaseNode.get("attemptId").asText());
        String leaseToken = leaseNode.get("leaseToken").asText();
        long fence = leaseNode.get("fencingGeneration").asLong();

        String secretsEndpoint = "/api/internal/workers/" + worker1Id + "/attempts/" + attemptId + "/secrets";
        String body = "{\"leaseToken\":\"" + leaseToken + "\",\"fencingGeneration\":" + fence + "}";

        // 7. Non-HTTPS transport rejected when allowInsecureLocalDelivery=false
        mockMvc.perform(post(secretsEndpoint)
                        .header("X-CloudForge-Worker-Id", worker1Id)
                        .header("Authorization", "Bearer " + worker1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // 8. Non-owning Worker 2 rejected cleanly (403)
        mockMvc.perform(post("/api/internal/workers/" + worker2Id + "/attempts/" + attemptId + "/secrets")
                        .with(req -> { req.setSecure(true); return req; })
                        .header("X-CloudForge-Worker-Id", worker2Id)
                        .header("Authorization", "Bearer " + worker2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // 9. Stale fencing generation / wrong lease token rejected (409)
        mockMvc.perform(post(secretsEndpoint)
                        .with(req -> { req.setSecure(true); return req; })
                        .header("X-CloudForge-Worker-Id", worker1Id)
                        .header("Authorization", "Bearer " + worker1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"" + leaseToken + "\",\"fencingGeneration\":" + (fence + 99) + "}"))
                .andExpect(status().isConflict());

        // 10. Valid TLS + matching worker + lease + fence -> delivers PINNED v1 (NOT v2!) with no-store
        mockMvc.perform(post(secretsEndpoint)
                        .with(req -> { req.setSecure(true); return req; })
                        .header("X-CloudForge-Worker-Id", worker1Id)
                        .header("Authorization", "Bearer " + worker1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.secrets[0].alias").value("RUNTIME_API_TOKEN"))
                .andExpect(jsonPath("$.secrets[0].versionNumber").value(1))
                .andExpect(jsonPath("$.secrets[0].value").value("stg-pinned-val-v1"));

        // 11. Revoke secret -> next fetch denies fail-closed (409) even with valid lease!
        mockMvc.perform(post(stagingSecretUrl + "/" + stagingSecretId + "/revoke")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Mid-flight revocation\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(secretsEndpoint)
                        .with(req -> { req.setSecure(true); return req; })
                        .header("X-CloudForge-Worker-Id", worker1Id)
                        .header("Authorization", "Bearer " + worker1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        // Verify audit recorded granted and denied secret deliveries cleanly without leaking values
        var audits = auditEventRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId(), PageRequest.of(0, 50));
        assertThat(audits.getContent()).extracting("action").contains(
                SecretAuditService.ACTION_SECRET_ACCESS_GRANTED,
                SecretAuditService.ACTION_SECRET_ACCESS_DENIED,
                SecretAuditService.ACTION_SECRET_BINDING_UPDATED
        );
        audits.getContent().forEach(evt -> {
            assertThat(evt.getSummary()).doesNotContain("stg-pinned-val-v1").doesNotContain("stg-new-val-v2");
            assertThat(evt.getMetadataJson()).doesNotContain("stg-pinned-val-v1").doesNotContain("stg-new-val-v2");
        });
    }

    private void registerWorker(String workerId, String token) throws Exception {
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Test Worker",
                                  "adapterType": "MOCK_TEST",
                                  "supportedOperations": ["ENV_VALIDATION", "CONTROLLED_SMOKE_TEST", "WORKER_DIAGNOSTICS"],
                                  "maxConcurrency": 2
                                }
                                """.formatted(workerId)))
                .andExpect(status().isOk());
    }
}
