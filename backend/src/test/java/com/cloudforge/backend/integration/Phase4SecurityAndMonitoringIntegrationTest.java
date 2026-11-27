package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.oci.entity.OciArtifactRetentionPolicy;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.supplychain.ArtifactEvidenceService;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles({"test", "monitoring"})
class Phase4SecurityAndMonitoringIntegrationTest {

    private static final String MONITORING_TOKEN = "cf_test_metrics_token_0123456789abcdef";
    private static final KeyPair SIGNING_KEY_PAIR = generateEcKeyPair();
    private static final String COSIGN_PEM_PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(SIGNING_KEY_PAIR.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";

    @DynamicPropertySource
    static void registerPhase4Properties(DynamicPropertyRegistry registry) {
        registry.add("cloudforge.monitoring.token", () -> MONITORING_TOKEN);
        registry.add("cloudforge.supplychain.public-key", () -> COSIGN_PEM_PUBLIC_KEY);
    }

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
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private PipelineStageRunRepository pipelineStageRunRepository;

    @Autowired
    private StageExecutionAttemptRepository stageExecutionAttemptRepository;

    @Autowired
    private OciImageArtifactRepository artifactRepository;

    @Autowired
    private ArtifactEvidenceService artifactEvidenceService;

    @Autowired
    private MeterRegistry meterRegistry;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity outsider;
    private WorkspaceEntity workspace;
    private OciImageArtifactEntity artifact;

    private static final java.util.concurrent.atomic.AtomicLong UID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(7_100_000L);

    @BeforeEach
    void setUp() {
        long suffix = UID_SEQUENCE.getAndAdd(10L);
        owner = userAccountService.upsertFromGitHubOAuth(
                suffix, "p4-owner-" + suffix, "Phase4 Owner", null, null, null
        );
        developer = userAccountService.upsertFromGitHubOAuth(
                suffix + 1, "p4-dev-" + suffix, "Phase4 Dev", null, null, null
        );
        outsider = userAccountService.upsertFromGitHubOAuth(
                suffix + 2, "p4-outsider-" + suffix, "Phase4 Outsider", null, null, null
        );

        workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();

        WorkspaceMemberEntity devMember = new WorkspaceMemberEntity();
        devMember.setWorkspace(workspace);
        devMember.setUser(developer);
        devMember.setRole(WorkspaceRole.DEVELOPER);
        workspaceMemberRepository.saveAndFlush(devMember);

        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setName("SupplyChain Service " + suffix);
        project.setSlug("supplychain-svc-" + suffix);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCreatedBy(owner);
        project = projectRepository.saveAndFlush(project);

        Instant now = Instant.now();
        PipelineRunEntity run = new PipelineRunEntity();
        run.setWorkspace(workspace);
        run.setProject(project);
        run.setRunNumber(1);
        run.setDefinitionName("SupplyChain Build");
        run.setDefinitionVersion(1);
        run.setDefinitionSnapshotJson("{}");
        run.setTriggerType(PipelineTriggerType.MANUAL);
        run.setTargetBranch("main");
        run.setCommitSha("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678");
        run.setRequestedBy(owner);
        run.setQueuedAt(now);
        run = pipelineRunRepository.saveAndFlush(run);

        PipelineStageRunEntity stage = new PipelineStageRunEntity();
        stage.setWorkspace(workspace);
        stage.setPipelineRun(run);
        stage.setStageKey("publish");
        stage.setDisplayName("Publish OCI");
        stage.setStageType(PipelineStageType.CONTAINER_PACKAGE);
        stage.setExecutionOrder(0);
        stage.setDependsOnJson("[]");
        stage.setMetadataJson("{}");
        stage.setSecretBindingsJson("[]");
        stage.setTimeoutSeconds(300);
        stage.setMaxRetries(0);
        stage.setAllowFailure(false);
        stage = pipelineStageRunRepository.saveAndFlush(stage);

        StageExecutionAttemptEntity attempt = new StageExecutionAttemptEntity();
        attempt.setWorkspace(workspace);
        attempt.setPipelineRun(run);
        attempt.setStageRun(stage);
        attempt.setAttemptNumber(1);
        attempt.setWorkerId("oci-test-worker");
        attempt.setAllowedOperation("OCI_IMAGE_PUBLISH");
        attempt.setLeaseToken("lease-" + UUID.randomUUID());
        attempt.setFencingGeneration(1L);
        attempt.setStatus(AttemptStatus.SUCCEEDED);
        attempt.setLeaseStartedAt(now.minusSeconds(10));
        attempt.setLastHeartbeatAt(now);
        attempt.setStartedAt(now.minusSeconds(10));
        attempt.setCompletedAt(now);
        attempt.setLeaseExpiresAt(now.plusSeconds(60));
        attempt = stageExecutionAttemptRepository.saveAndFlush(attempt);

        String digest = "sha256:" + String.format("%064x", suffix);
        OciImageArtifactEntity entity = new OciImageArtifactEntity();
        entity.setWorkspace(workspace);
        entity.setProject(project);
        entity.setPipelineRun(run);
        entity.setStageRun(stage);
        entity.setAttempt(attempt);
        entity.setGithubRepositoryId(990001L);
        entity.setRepositoryFullName("cloudforge/demo");
        entity.setCommitSha("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678");
        entity.setRegistryHost("127.0.0.1:5000");
        entity.setImageRepository("cloudforge/demo");
        entity.setImageTag("v1");
        entity.setManifestDigest(digest);
        entity.setImmutableReference("127.0.0.1:5000/cloudforge/demo@" + digest);
        entity.setImageMediaType("application/vnd.oci.image.manifest.v1+json");
        entity.setPlatform("linux/amd64");
        entity.setImageSizeBytes(4096L);
        entity.setVerificationStatus(OciArtifactVerificationStatus.VERIFIED);
        entity.setVerifiedAt(now);
        entity.setPublishedAt(now);
        entity.setRetentionPolicy(OciArtifactRetentionPolicy.RETAIN_METADATA);
        artifact = artifactRepository.saveAndFlush(entity);
    }

    @Test
    void prometheusEndpointRejectsUnauthorizedRequestsAndAllowsBearerToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer wrong-token-000000000000000000000000000"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/actuator/prometheus")
                        .header("Authorization", "Bearer " + MONITORING_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + MONITORING_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void signedEvidenceEnforcesRbacCryptoProductionGateAndOperationMetrics() throws Exception {
        // Without evidence, production gate must fail closed
        assertThatThrownBy(() -> artifactEvidenceService.requireProductionApproval(artifact))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zero high or critical");

        long now = Instant.now().getEpochSecond();
        String sbomSha = "b".repeat(64);
        Map<String, Object> cleanEnvelope = signedEnvelope(
                workspace.getId(), artifact.getId(), artifact.getImmutableReference(),
                now - 5, 0, 0, sbomSha, true
        );

        // DEVELOPER and outsider cannot submit signed evidence
        mockMvc.perform(post("/api/workspaces/{ws}/security/artifacts/{art}/assessments",
                        workspace.getId(), artifact.getId())
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanEnvelope)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/workspaces/{ws}/security/assessments", workspace.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());

        // Tampered signature must be rejected
        Map<String, Object> tampered = Map.of(
                "payload", cleanEnvelope.get("payload"),
                "signature", Base64.getEncoder().encodeToString("invalid-signature".getBytes(StandardCharsets.UTF_8))
        );
        mockMvc.perform(post("/api/workspaces/{ws}/security/artifacts/{art}/assessments",
                        workspace.getId(), artifact.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tampered)))
                .andExpect(status().isBadRequest());

        // Zero findings without verified image signature must be rejected
        Map<String, Object> unsignedZero = signedEnvelope(
                workspace.getId(), artifact.getId(), artifact.getImmutableReference(),
                now - 4, 0, 0, sbomSha, false
        );
        mockMvc.perform(post("/api/workspaces/{ws}/security/artifacts/{art}/assessments",
                        workspace.getId(), artifact.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unsignedZero)))
                .andExpect(status().isBadRequest());

        double beforeCount = meterRegistry.counter("cloudforge.operations", "action", "SECURITY_EVIDENCE_RECORDED").count();

        // Valid signed zero-finding evidence passes (verifies Cosign PEM key parsing too)
        mockMvc.perform(post("/api/workspaces/{ws}/security/artifacts/{art}/assessments",
                        workspace.getId(), artifact.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cleanEnvelope)))
                .andExpect(status().isNoContent());

        // Production gate now approves the artifact
        artifactEvidenceService.requireProductionApproval(artifact);

        // OperationMetrics recorded the committed SECURITY_EVIDENCE_RECORDED event
        double afterCount = meterRegistry.counter("cloudforge.operations", "action", "SECURITY_EVIDENCE_RECORDED").count();
        assertThat(afterCount).isGreaterThan(beforeCount);

        // Workspace members can read the assessment summary
        mockMvc.perform(get("/api/workspaces/{ws}/security/assessments", workspace.getId())
                        .with(authenticatedUser(developer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifact").value(artifact.getId().toString()))
                .andExpect(jsonPath("$[0].critical").value(0))
                .andExpect(jsonPath("$[0].high").value(0))
                .andExpect(jsonPath("$[0].sbomSha256").value(sbomSha));

        // Newer adverse evidence (high=2, imageSignatureVerified=false) records and re-blocks production
        Map<String, Object> adverseEnvelope = signedEnvelope(
                workspace.getId(), artifact.getId(), artifact.getImmutableReference(),
                now - 1, 0, 2, sbomSha, false
        );
        mockMvc.perform(post("/api/workspaces/{ws}/security/artifacts/{art}/assessments",
                        workspace.getId(), artifact.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adverseEnvelope)))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> artifactEvidenceService.requireProductionApproval(artifact))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zero high or critical");
    }

    private Map<String, Object> signedEnvelope(
            UUID workspaceId,
            UUID artifactId,
            String image,
            long assessedAt,
            int critical,
            int high,
            String sbomSha256,
            boolean imageSignatureVerified
    ) throws Exception {
        Map<String, Object> payloadObj = new LinkedHashMap<>();
        payloadObj.put("version", 1);
        payloadObj.put("workspace", workspaceId.toString());
        payloadObj.put("artifact", artifactId.toString());
        payloadObj.put("image", image);
        payloadObj.put("assessedAt", assessedAt);
        payloadObj.put("critical", critical);
        payloadObj.put("high", high);
        payloadObj.put("sbomSha256", sbomSha256);
        payloadObj.put("imageSignatureVerified", imageSignatureVerified);

        byte[] rawBytes = objectMapper.writeValueAsBytes(payloadObj);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(SIGNING_KEY_PAIR.getPrivate());
        signer.update(rawBytes);
        byte[] sigBytes = signer.sign();

        return Map.of(
                "payload", Base64.getEncoder().encodeToString(rawBytes),
                "signature", Base64.getEncoder().encodeToString(sigBytes)
        );
    }

    private static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
