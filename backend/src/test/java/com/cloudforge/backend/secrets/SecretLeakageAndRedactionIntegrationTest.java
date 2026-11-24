package com.cloudforge.backend.secrets;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.repository.StageExecutionLogRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SecretLeakageAndRedactionIntegrationTest {

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
    private StageExecutionLogRepository logRepository;
    @Autowired
    private WorkerCredentialService workerCredentialService;
    @Autowired
    private SecretAuditService secretAuditService;

    private UserEntity owner;
    private WorkspaceEntity workspace;
    private ProjectEntity project;
    private ProjectEnvironmentEntity env;
    private String workerId;
    private String workerToken;

    @BeforeEach
    void setup() {
        secretAuditService.clearRateLimitState();
        long uid = 97000L + (System.nanoTime() % 10000);
        owner = userAccountService.upsertFromGitHubOAuth(uid, "redact-owner-" + uid, "Redact Owner", null, null, null);
        workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setCreatedBy(owner);
        project.setName("Redaction Project");
        project.setSlug("redaction-" + uid);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.saveAndFlush(project);

        env = new ProjectEnvironmentEntity();
        env.setProject(project);
        env.setName("Staging");
        env.setEnvironmentType(EnvironmentType.STAGING);
        env.setConfigVariablesJson("{}");
        env = environmentRepository.saveAndFlush(env);

        workerId = "redact-worker-" + uid;
        workerToken = "redact-worker-secret-token-" + uid;
        workerCredentialService.provisionCredential(workerId, "rk1-" + uid, workerToken, Instant.now().plusSeconds(3600), "Redact w1");
    }

    @AfterEach
    void cleanup() {
        if (workspace != null) {
            workspaceRepository.deleteById(workspace.getId());
        }
    }

    @Test
    void scrubsDirectMultilineBase64UrlEncodedAndChunkBoundarySplitSecretsAcrossLogsAndCompletion() throws Exception {
        String rawSecret = "UltraSecretValue!@#2026_SplitToken";
        String multilineSecret = "-----BEGIN TEST KEY-----\nSECRET_LINE_SEGMENT_ALPHA_77\n-----END TEST KEY-----";
        String b64Secret = Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8));
        String urlSecret = URLEncoder.encode(rawSecret, StandardCharsets.UTF_8);

        String secUrl = "/api/workspaces/" + workspace.getId() + "/projects/" + project.getId()
                + "/environments/" + env.getId() + "/secrets";

        MvcResult s1Res = mockMvc.perform(post(secUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "RAW_TOKEN",
                                "value", rawSecret
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID s1Id = UUID.fromString(objectMapper.readTree(s1Res.getResponse().getContentAsString()).get("id").asText());

        MvcResult s2Res = mockMvc.perform(post(secUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "CERT_KEY",
                                "value", multilineSecret
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID s2Id = UUID.fromString(objectMapper.readTree(s2Res.getResponse().getContentAsString()).get("id").asText());

        MvcResult defRes = mockMvc.perform(post("/api/workspaces/" + workspace.getId() + "/pipelines/definitions")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Redaction Smoke",
                                  "targetEnvironmentId": "%s",
                                  "stages": [{
                                    "stageKey": "test",
                                    "displayName": "Test",
                                    "stageType": "TEST",
                                    "metadata": {"operation": "CONTROLLED_SMOKE_TEST"},
                                    "secretBindings": [
                                      {"secretId": "%s", "deliveryAlias": "ALIAS_RAW"},
                                      {"secretId": "%s", "deliveryAlias": "ALIAS_CERT"}
                                    ]
                                  }]
                                }
                                """.formatted(project.getId(), env.getId(), s1Id, s2Id)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID defId = UUID.fromString(objectMapper.readTree(defRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult runRes = mockMvc.perform(post("/api/workspaces/" + workspace.getId() + "/pipelines/runs")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"" + defId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(objectMapper.readTree(runRes.getResponse().getContentAsString()).get("id").asText());
        UUID stageId = UUID.fromString(objectMapper.readTree(runRes.getResponse().getContentAsString())
                .get("stages").get(0).get("id").asText());

        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Redact Worker",
                                  "adapterType": "MOCK_TEST",
                                  "supportedOperations": ["CONTROLLED_SMOKE_TEST"],
                                  "maxConcurrency": 1
                                }
                                """.formatted(workerId)))
                .andExpect(status().isOk());

        MvcResult claimRes = mockMvc.perform(post("/api/internal/workers/" + workerId + "/claim")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lease = objectMapper.readTree(claimRes.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(lease.get("attemptId").asText());
        String leaseToken = lease.get("leaseToken").asText();
        long fence = lease.get("fencingGeneration").asLong();

        // Attempt logs: direct raw, Base64, URL-encoded, multiline segment, AND cross-chunk split!
        String splitPart1 = rawSecret.substring(0, 14);
        String splitPart2 = rawSecret.substring(14);
        String logsPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "leaseToken", leaseToken,
                "fencingGeneration", fence,
                "entries", java.util.List.of(
                        java.util.Map.of("streamType", "STDOUT", "message", "Direct leak: " + rawSecret),
                        java.util.Map.of("streamType", "STDOUT", "message", "Base64 leak: " + b64Secret + " URL leak: " + urlSecret),
                        java.util.Map.of("streamType", "STDOUT", "message", "Segment leak: SECRET_LINE_SEGMENT_ALPHA_77"),
                        java.util.Map.of("streamType", "STDOUT", "message", "Boundary chunk 1 " + splitPart1)
                )
        ));
        mockMvc.perform(post("/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/logs")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logsPayload))
                .andExpect(status().isOk());

        // Second separate POST request completing the cross-chunk boundary split
        String secondChunkPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "leaseToken", leaseToken,
                "fencingGeneration", fence,
                "entries", java.util.List.of(
                        java.util.Map.of("streamType", "STDOUT", "message", splitPart2 + " end boundary chunk")
                )
        ));
        mockMvc.perform(post("/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/logs")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondChunkPayload))
                .andExpect(status().isOk());

        // Complete attempt with secret leaked inside summary and resourceUsage
        String completePayload = objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("pipelineRunId", runId),
                Map.entry("stageRunId", stageId),
                Map.entry("attemptNumber", 1),
                Map.entry("leaseToken", leaseToken),
                Map.entry("fencingGeneration", fence),
                Map.entry("resultStatus", "SUCCEEDED"),
                Map.entry("failureCategory", "NONE"),
                Map.entry("exitCode", 0),
                Map.entry("summary", "Completed with accidental leak " + rawSecret),
                Map.entry("resourceUsage", Map.of("debugNote", b64Secret)),
                Map.entry("terminationConfirmed", true)
        ));
        mockMvc.perform(post("/api/internal/workers/" + workerId + "/attempts/" + attemptId + "/complete")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completePayload))
                .andExpect(status().isOk());

        // Verify ALL persisted logs and completion summary via API contain ZERO raw, b64, url, or segment leaks
        MvcResult fetchedLogs = mockMvc.perform(get("/api/workspaces/" + workspace.getId()
                        + "/pipelines/runs/" + runId + "/stages/" + stageId + "/logs")
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andReturn();
        String logsText = fetchedLogs.getResponse().getContentAsString();
        assertThat(logsText)
                .contains("[REDACTED]")
                .doesNotContain(rawSecret)
                .doesNotContain(b64Secret)
                .doesNotContain(urlSecret)
                .doesNotContain("SECRET_LINE_SEGMENT_ALPHA_77")
                .doesNotContain(splitPart1 + splitPart2);

        // Also assert persisted DB rows directly for boundary redaction verification
        var dbLogs = logRepository.findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAscSequenceNumberAsc(
                stageId, workspace.getId(), PageRequest.of(0, 20)
        );
        String joinedDbMessages = dbLogs.getContent().stream().map(l -> l.getMessage()).reduce("", String::concat);
        assertThat(joinedDbMessages).doesNotContain(rawSecret).doesNotContain(splitPart1 + splitPart2);

        MvcResult fetchedRun = mockMvc.perform(get("/api/workspaces/" + workspace.getId()
                        + "/pipelines/runs/" + runId)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(fetchedRun.getResponse().getContentAsString()).doesNotContain(rawSecret);

        MvcResult fetchedAttempts = mockMvc.perform(get("/api/workspaces/" + workspace.getId()
                        + "/pipelines/runs/" + runId + "/stages/" + stageId + "/attempts")
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(fetchedAttempts.getResponse().getContentAsString())
                .contains("[REDACTED]")
                .doesNotContain(rawSecret)
                .doesNotContain(b64Secret);
    }
}
