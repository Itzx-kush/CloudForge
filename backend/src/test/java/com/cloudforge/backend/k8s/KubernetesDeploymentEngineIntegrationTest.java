package com.cloudforge.backend.k8s;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentEventEntity;
import com.cloudforge.backend.k8s.repository.KubernetesClusterConnectionRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentConfigRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentEventRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentOperationRepository;
import com.cloudforge.backend.oci.entity.OciArtifactRetentionPolicy;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciBuildMode;
import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import com.cloudforge.backend.oci.entity.OciRegistryType;
import com.cloudforge.backend.oci.repository.OciBuildRecordRepository;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.repository.OciRegistryConnectionRepository;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.StageDefinitionRequest;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.orchestration.service.PipelineDefinitionService;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.secrets.crypto.EnvKeyringSecretKeyProvider;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.StageExecutionLogRepository;
import com.cloudforge.backend.worker.security.WorkerCredentialService;
import com.cloudforge.backend.worker.service.WorkerRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack Phase 3.6 Kubernetes Deployment Engine integration suite testing:
 * cluster registration &amp; RBAC, worker-executed cluster validation, namespace binding isolation,
 * verified OCI artifact ownership, production approval gate, idempotency, worker capability
 * boundaries, fencing generation protection, bearer-token redaction, premature SUCCEEDED rejection,
 * post-apply cancellation &amp; crash reconciliation, and multi-stage DAG deployment pipelines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:k8s_engine_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "cloudforge.workers.enabled=true",
        "cloudforge.workers.scheduler-enabled=false",
        "cloudforge.workers.lease-duration-seconds=60",
        "cloudforge.secrets.enabled=true",
        "cloudforge.secrets.key-provider=env-keyring",
        "cloudforge.secrets.active-key-id=k8s-kek-v1",
        "cloudforge.secrets.keyring=k8s-kek-v1:MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
        "cloudforge.secrets.worker-delivery-enabled=true",
        "cloudforge.secrets.allow-insecure-local-delivery=false",
        "cloudforge.secrets.denied-audit-cooldown-seconds=0",
        "cloudforge.kubernetes.enabled=true",
        "cloudforge.kubernetes.allow-local-dev-clusters=true",
        "cloudforge.kubernetes.allow-production-deployments=false",
        "cloudforge.kubernetes.allow-insecure-local-credential-delivery=false",
        "cloudforge.kubernetes.allowed-api-hosts=127.0.0.1:16443,127.0.0.1:26443",
        "cloudforge.kubernetes.allowed-worker-ids=k8s-worker-1,k8s-worker-2",
        "cloudforge.kubernetes.max-credential-deliveries-per-attempt=3"
})
class KubernetesDeploymentEngineIntegrationTest {

    private static final String VALID_DIGEST =
            "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String COMMIT_SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final String BEARER_SECRET_VALUE = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImNmLXRlc3QifQ.cloudforge_sa_token_super_secret_998877";

    private static String ephemeralTestCaPem;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserAccountService userAccountService;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private GitHubInstallationRepository installationRepository;
    @Autowired private LinkedRepositoryRepository linkedRepositoryRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectEnvironmentRepository environmentRepository;
    @Autowired private OciRegistryConnectionRepository registryRepository;
    @Autowired private OciBuildRecordRepository buildRecordRepository;
    @Autowired private OciImageArtifactRepository artifactRepository;
    @Autowired private PipelineDefinitionService pipelineDefinitionService;
    @Autowired private PipelineOrchestrationService pipelineOrchestrationService;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PipelineStageRunRepository stageRunRepository;
    @Autowired private StageExecutionAttemptRepository attemptRepository;
    @Autowired private StageExecutionLogRepository logRepository;
    @Autowired private WorkerCredentialService workerCredentialService;
    @Autowired private WorkerRecoveryService recoveryService;
    @Autowired private KubernetesClusterConnectionRepository clusterRepository;
    @Autowired private KubernetesDeploymentConfigRepository configRepository;
    @Autowired private KubernetesDeploymentOperationRepository operationRepository;
    @Autowired private KubernetesDeploymentEventRepository eventRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private KubernetesDeploymentProperties k8sProperties;
    @Autowired private EnvKeyringSecretKeyProvider keyProvider;
    @Autowired private SecretAuditService secretAuditService;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private LinkedRepositoryEntity repoA;
    private ProjectEntity projectA;
    private ProjectEntity projectOther;
    private ProjectEnvironmentEntity devEnv;
    private ProjectEnvironmentEntity prodEnv;
    private UUID bearerTokenSecretId;
    private OciImageArtifactEntity verifiedArtifact;
    private OciImageArtifactEntity unverifiedArtifact;
    private OciImageArtifactEntity otherProjectArtifact;

    private final List<UUID> releaseFixtureRuns = new ArrayList<>();

    @AfterEach
    void cancelUnclaimedReleaseFixtureRuns() {
        for (UUID runId : releaseFixtureRuns) {
            pipelineOrchestrationService.cancelRun(workspaceA.getId(), runId,
                    new CancelPipelineRunRequest("Release fixture cleanup"), owner);
        }
    }

    private final String k8sWorker1Id = "k8s-worker-1";
    private final String k8sWorker1Secret = "k8s-worker-1-secret-token-1234567890";
    private final String k8sWorker2Id = "k8s-worker-2";
    private final String k8sWorker2Secret = "k8s-worker-2-secret-token-1234567890";
    private final String rogueWorkerId = "rogue-k8s-worker";
    private final String rogueWorkerSecret = "rogue-k8s-worker-secret-token-123456";
    private final String ociWorkerId = "oci-build-worker-1";
    private final String ociWorkerSecret = "oci-build-worker-secret-token-123456";

    @BeforeAll
    static void generateEphemeralCa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name dn = new X500Name("CN=CloudForge Test K8s CA");
        Date notBefore = Date.from(Instant.now().minusSeconds(3600));
        Date notAfter = Date.from(Instant.now().plusSeconds(86400 * 30L));
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.ONE, notBefore, notAfter, dn, kp.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(cert);
        }
        ephemeralTestCaPem = sw.toString();
    }

    private static final java.util.concurrent.atomic.AtomicLong UID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(850_000L);

    @BeforeEach
    void setUp() throws Exception {
        secretAuditService.clearRateLimitState();
        keyProvider.reloadFromProperties();
        k8sProperties.setEnabled(true);
        k8sProperties.setAllowLocalDevClusters(true);
        k8sProperties.setAllowProductionDeployments(false);
        k8sProperties.setAllowInsecureLocalCredentialDelivery(false);
        k8sProperties.setAllowedApiHosts("127.0.0.1:16443,127.0.0.1:26443");
        k8sProperties.setAllowedWorkerIds("k8s-worker-1,k8s-worker-2");

        long uid = UID_SEQUENCE.getAndAdd(10L);
        owner = userAccountService.upsertFromGitHubOAuth(uid, "k8s-owner-" + uid, "K8s Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(uid + 1, "k8s-dev-" + uid, "K8s Dev", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(uid + 2, "k8s-view-" + uid, "K8s Viewer", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(uid + 3, "k8s-out-" + uid, "K8s Outsider", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        addMember(workspaceA, developer, WorkspaceRole.DEVELOPER);
        addMember(workspaceA, viewer, WorkspaceRole.VIEWER);

        GitHubInstallationEntity inst = new GitHubInstallationEntity();
        inst.setWorkspace(workspaceA);
        inst.setGithubInstallationId(1_850_000L + uid);
        inst.setAccountLogin("cloudforge-org");
        inst.setAccountType("Organization");
        inst.setStatus(InstallationStatus.ACTIVE);
        inst = installationRepository.saveAndFlush(inst);

        repoA = new LinkedRepositoryEntity();
        repoA.setWorkspace(workspaceA);
        repoA.setInstallation(inst);
        repoA.setGithubRepoId(2_881_000L + uid);
        repoA.setOwnerLogin("cloudforge-org");
        repoA.setName("payments-service");
        repoA.setFullName("cloudforge-org/payments-service");
        repoA.setDefaultBranch("main");
        repoA.setVisibility("private");
        repoA.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        repoA.setLastSyncedAt(Instant.now());
        repoA = linkedRepositoryRepository.saveAndFlush(repoA);

        projectA = saveProject(workspaceA, owner, repoA, "Payments Service", "payments-" + uid);
        projectOther = saveProject(workspaceA, owner, null, "Other Service", "other-" + uid);

        devEnv = saveEnv(projectA, "Development", EnvironmentType.DEVELOPMENT);
        prodEnv = saveEnv(projectA, "Production", EnvironmentType.PRODUCTION);

        // Store least-privilege K8s ServiceAccount bearer token in Phase 3.4 encrypted secret store
        String secretUrl = "/api/workspaces/" + workspaceA.getId() + "/projects/" + projectA.getId()
                + "/environments/" + devEnv.getId() + "/secrets";
        MvcResult secRes = mockMvc.perform(post(secretUrl)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "K8S_DEPLOYER_TOKEN",
                                "value", BEARER_SECRET_VALUE,
                                "description", "Least-privilege deployer SA token"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        bearerTokenSecretId = UUID.fromString(objectMapper.readTree(secRes.getResponse().getContentAsString()).get("id").asText());

        // Seed Phase 3.5 verified, unverified, and other-project OCI artifacts
        OciRegistryConnectionEntity reg = new OciRegistryConnectionEntity();
        reg.setWorkspace(workspaceA);
        reg.setName("Local Registry " + uid);
        reg.setSlug("local-reg-" + uid);
        reg.setRegistryHost("127.0.0.1:5000");
        reg.setScheme("http");
        reg.setRegistryType(OciRegistryType.LOCAL_DEV);
        reg.setAllowedNamespace("cloudforge");
        reg.setAllowInsecureLocal(true);
        reg.setStatus(OciRegistryStatus.ACTIVE);
        reg.setCreatedBy(owner);
        reg.setUpdatedBy(owner);
        reg = registryRepository.saveAndFlush(reg);

        var seedDef = pipelineDefinitionService.createDefinition(
                workspaceA.getId(),
                new CreatePipelineDefinitionRequest(
                        projectA.getId(), "Seed Build " + uid, "Seed", repoA.getId(), true,
                        List.of(new StageDefinitionRequest("build", "Build", PipelineStageType.BUILD, List.of(), 300, 0, false, Map.of()))
                ),
                owner
        );
        var seedRun = pipelineOrchestrationService.createRun(
                workspaceA.getId(),
                new CreatePipelineRunRequest(seedDef.id(), "main", COMMIT_SHA, PipelineTriggerType.MANUAL, null),
                "seed-run-" + UUID.randomUUID(),
                owner
        ).run();
        PipelineStageRunEntity seedStage = stageRunRepository.findAllByPipelineRunIdOrderByExecutionOrderAsc(seedRun.id()).get(0);

        verifiedArtifact = seedArtifact(workspaceA, projectA, seedStage, reg, VALID_DIGEST,
                "127.0.0.1:5000/cloudforge/payments@" + VALID_DIGEST, OciArtifactVerificationStatus.VERIFIED);
        unverifiedArtifact = seedArtifact(workspaceA, projectA, seedStage, reg,
                "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                "127.0.0.1:5000/cloudforge/payments@sha256:1111111111111111111111111111111111111111111111111111111111111111",
                OciArtifactVerificationStatus.PENDING);
        otherProjectArtifact = seedArtifact(workspaceA, projectOther, seedStage, reg,
                "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                "127.0.0.1:5000/cloudforge/other@sha256:2222222222222222222222222222222222222222222222222222222222222222",
                OciArtifactVerificationStatus.VERIFIED);

        // Provision worker credentials
        workerCredentialService.provisionCredential(k8sWorker1Id, "k8s-key-1-" + uid, k8sWorker1Secret, null, "Test K8s Worker 1");
        workerCredentialService.provisionCredential(k8sWorker2Id, "k8s-key-2-" + uid, k8sWorker2Secret, null, "Test K8s Worker 2");
        workerCredentialService.provisionCredential(rogueWorkerId, "k8s-key-rogue-" + uid, rogueWorkerSecret, null, "Test Rogue Worker");
        workerCredentialService.provisionCredential(ociWorkerId, "k8s-key-oci-" + uid, ociWorkerSecret, null, "Test OCI Worker");
    }

    @Test
    void enforcesClusterRbacIsolationValidationLifecycleAndWorkerCapabilityBoundaries() throws Exception {
        String clusterPayload = clusterCreateJson("kind-dev-" + System.nanoTime(), "https://127.0.0.1:16443", "DEVELOPMENT");

        // DEVELOPER, VIEWER, and outsider cannot register clusters
        mockMvc.perform(post("/api/workspaces/{ws}/clusters", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clusterPayload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/{ws}/clusters", workspaceA.getId())
                        .with(authenticatedUser(viewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clusterPayload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workspaces/{ws}/clusters", workspaceA.getId())
                        .with(authenticatedUser(outsider)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clusterPayload))
                .andExpect(status().isForbidden());

        // OWNER registers cluster; response never leaks bearer token
        MvcResult createRes = mockMvc.perform(post("/api/workspaces/{ws}/clusters", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clusterPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connectionStatus").value("PENDING_VALIDATION"))
                .andExpect(content().string(not(containsString(BEARER_SECRET_VALUE))))
                .andReturn();
        UUID clusterId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText());

        // Forbidden system namespace binding rejected
        mockMvc.perform(post("/api/workspaces/{ws}/clusters/{c}/namespaces", workspaceA.getId(), clusterId)
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectId", projectA.getId(),
                                "environmentId", devEnv.getId(),
                                "namespaceName", "kube-system"
                        ))))
                .andExpect(status().isForbidden());

        // Valid namespace binding succeeds
        MvcResult nsRes = mockMvc.perform(post("/api/workspaces/{ws}/clusters/{c}/namespaces", workspaceA.getId(), clusterId)
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectId", projectA.getId(),
                                "environmentId", devEnv.getId(),
                                "workloadServiceAccountName", "cloudforge-workload"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.podSecurityStandard").value("RESTRICTED"))
                .andReturn();
        UUID nsId = UUID.fromString(objectMapper.readTree(nsRes.getResponse().getContentAsString()).get("id").asText());

        // Deployment to unvalidated cluster is rejected (409 Conflict)
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), clusterId, nsId, verifiedArtifact.getId(), 2)))
                .andExpect(status().isConflict());

        // Non-Kubernetes worker (OCI_BUILDKIT) cannot register with KUBERNETES_DEPLOY capability
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", ociWorkerId)
                        .header("Authorization", "Bearer " + ociWorkerSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerId", ociWorkerId,
                                "displayName", "OCI Worker",
                                "adapterType", "OCI_BUILDKIT",
                                "supportedOperations", List.of("KUBERNETES_DEPLOY"),
                                "maxConcurrency", 1,
                                "hostLabel", "local",
                                "agentVersion", "0.3.6"
                        ))))
                .andExpect(status().isBadRequest());

        // Unallowlisted rogue K8s worker cannot register as KUBERNETES_DEPLOY
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", rogueWorkerId)
                        .header("Authorization", "Bearer " + rogueWorkerSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(k8sWorkerRegJson(rogueWorkerId)))
                .andExpect(status().isForbidden());

        // Allowlisted KUBERNETES_DEPLOY worker registers successfully
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);

        // Request cluster validation -> worker claims -> fetches decrypted token over HTTPS -> completes VALIDATED
        mockMvc.perform(post("/api/workspaces/{ws}/clusters/{c}/validate", workspaceA.getId(), clusterId)
                        .with(authenticatedUser(owner)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.connectionStatus").value("VALIDATING"));

        MvcResult claimValRes = mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value(clusterId.toString()))
                .andReturn();
        long valNonce = objectMapper.readTree(claimValRes.getResponse().getContentAsString()).get("validationNonce").asLong();

        // Non-HTTPS credential fetch fails closed when allowInsecureLocalCredentialDelivery=false
        mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/{c}/credentials", k8sWorker1Id, clusterId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("validationNonce", valNonce))))
                .andExpect(status().isForbidden());

        // HTTPS (secure=true) credential fetch succeeds with Cache-Control: no-store
        mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/{c}/credentials", k8sWorker1Id, clusterId)
                        .secure(true)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("validationNonce", valNonce))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.bearerToken").value(BEARER_SECRET_VALUE));

        // Complete cluster validation -> VALIDATED
        mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/{c}/complete", k8sWorker1Id, clusterId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "validationNonce", valNonce,
                                "valid", true,
                                "kubernetesVersion", "v1.31.2+k3s1",
                                "supportedCapabilities", Map.of("appsV1Available", true, "leastPrivilegeVerified", true),
                                "verifiedNamespaces", List.of(nsRes.getResponse().getContentAsString().contains("namespaceName")
                                        ? objectMapper.readTree(nsRes.getResponse().getContentAsString()).get("namespaceName").asText() : "")
                        ))))
                .andExpect(status().isNoContent());

        assertThat(clusterRepository.findById(clusterId).orElseThrow().getConnectionStatus())
                .isEqualTo(KubernetesClusterStatus.VALIDATED);
    }

    @Test
    void executesFullDeploymentLifecycleEnforcesDigestReadinessFencingIdempotencyAndProductionGate() throws Exception {
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);
        registerK8sWorker(k8sWorker2Id, k8sWorker2Secret);
        ValidatedTarget target = provisionValidatedClusterAndNamespace(devEnv, "DEVELOPMENT");

        // Reject unverified artifact (409) and cross-project artifact (403)
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), unverifiedArtifact.getId(), 2)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), otherProjectArtifact.getId(), 2)))
                .andExpect(status().isForbidden());

        // VIEWER cannot create deployments
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(viewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), verifiedArtifact.getId(), 2)))
                .andExpect(status().isForbidden());

        // Production environment blocked when allowProductionDeployments=false, and blocked for DEVELOPER even if enabled
        UUID prodNsId = bindNamespace(target.clusterId(), prodEnv);
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), prodEnv.getId(), target.clusterId(), prodNsId, verifiedArtifact.getId(), 2)))
                .andExpect(status().isForbidden());

        k8sProperties.setAllowProductionDeployments(true);
        // DEVELOPER still forbidden on PRODUCTION
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJsonWithProdApproval(projectA.getId(), prodEnv.getId(), target.clusterId(), prodNsId, verifiedArtifact.getId(), true, "CHG-101")))
                .andExpect(status().isForbidden());
        // OWNER without explicit confirmation + justification rejected (400 Bad Request) on PRODUCTION
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJsonWithProdApproval(projectA.getId(), prodEnv.getId(), target.clusterId(), prodNsId, verifiedArtifact.getId(), false, "")))
                .andExpect(status().isBadRequest());
        k8sProperties.setAllowProductionDeployments(false);

        // DEVELOPER queues valid DEVELOPMENT deployment with Idempotency-Key
        String idemKey = "k8s-idem-" + UUID.randomUUID();
        String depPayload = deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), verifiedArtifact.getId(), 2);
        MvcResult createDep = mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.manifestDigest").value(VALID_DIGEST))
                .andReturn();
        UUID depId = UUID.fromString(objectMapper.readTree(createDep.getResponse().getContentAsString()).get("id").asText());

        // Idempotent replay with identical key & payload returns 200 + X-Idempotent-Replay
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depPayload))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(depId.toString()));

        // Same Idempotency-Key with different payload -> 409 Conflict
        String diffPayload = deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), verifiedArtifact.getId(), 3);
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diffPayload))
                .andExpect(status().isConflict());

        // Concurrent second deployment to same workload/namespace while active -> 409 Conflict
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(developer)).with(csrf())
                        .header("Idempotency-Key", "k8s-concurrent-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depPayload))
                .andExpect(status().isConflict());

        // Outsider cross-workspace read rejected
        mockMvc.perform(get("/api/workspaces/{ws}/deployments/{d}", workspaceA.getId(), depId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());

        // Dedicated KUBERNETES_DEPLOY worker claims the leased stage
        MvcResult claimRes = mockMvc.perform(post("/api/internal/workers/{w}/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOperation").value("KUBERNETES_DEPLOY"))
                .andReturn();
        JsonNode lease = objectMapper.readTree(claimRes.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(lease.get("attemptId").asText());
        String leaseToken = lease.get("leaseToken").asText();
        long fence = lease.get("fencingGeneration").asLong();
        UUID runId = UUID.fromString(lease.at("/stage/pipelineRunId").asText());
        UUID stageId = UUID.fromString(lease.at("/stage/stageRunId").asText());

        // Acknowledge RUNNING before fetching descriptor & credentials (matches WorkerAgentLoop lifecycle)
        acknowledgeRunning(k8sWorker1Id, k8sWorker1Secret, attemptId, leaseToken, fence);

        // Fetch descriptor & credentials over HTTPS (also registers bearer token for log redaction)
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-descriptor", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("leaseToken", leaseToken, "fencingGeneration", fence))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestDigest").value(VALID_DIGEST));

        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-credentials", k8sWorker1Id, attemptId)
                        .secure(true)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("leaseToken", leaseToken, "fencingGeneration", fence))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.bearerToken").value(BEARER_SECRET_VALUE));

        // Accidental bearer token leak in worker logs is automatically redacted by control plane
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/logs", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leaseToken", leaseToken,
                                "fencingGeneration", fence,
                                "entries", List.of(Map.of(
                                        "streamType", "STDOUT",
                                        "message", "debug token=" + BEARER_SECRET_VALUE,
                                        "occurredAt", Instant.now().toString()
                                ))
                        ))))
                .andExpect(status().isOk());

        assertThat(logRepository.findAll())
                .anySatisfy(log -> assertThat(log.getMessage()).contains("[REDACTED]").doesNotContain(BEARER_SECRET_VALUE));

        // Stale fencing generation status update rejected (409)
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-status", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leaseToken", leaseToken,
                                "fencingGeneration", fence + 1,
                                "status", "APPLYING",
                                "applyInitiated", true,
                                "observedReplicas", 0, "updatedReplicas", 0, "readyReplicas", 0, "availableReplicas", 0,
                                "message", "stale"
                        ))))
                .andExpect(status().isConflict());

        // Valid progression VALIDATING -> APPLYING -> ROLLING_OUT -> VERIFYING
        for (String st : List.of("VALIDATING", "APPLYING", "ROLLING_OUT", "VERIFYING")) {
            mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-status", k8sWorker1Id, attemptId)
                            .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                            .header("Authorization", "Bearer " + k8sWorker1Secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "leaseToken", leaseToken, "fencingGeneration", fence,
                                    "status", st, "applyInitiated", !"VALIDATING".equals(st),
                                    "observedReplicas", 2, "updatedReplicas", 2, "readyReplicas", 2, "availableReplicas", 2,
                                    "message", "Transitioning to " + st
                            ))))
                    .andExpect(status().isNoContent());
        }

        // Premature SUCCEEDED without ready replicas (1/2) or wrong digest rejected (409)!
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-result", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("leaseToken", leaseToken),
                                Map.entry("fencingGeneration", fence),
                                Map.entry("status", "SUCCEEDED"),
                                Map.entry("applyInitiated", true),
                                Map.entry("observedReplicas", 2),
                                Map.entry("updatedReplicas", 2),
                                Map.entry("readyReplicas", 1),
                                Map.entry("availableReplicas", 1),
                                Map.entry("verifiedImageDigest", VALID_DIGEST),
                                Map.entry("errorSummary", "Premature claim")
                        ))))
                .andExpect(status().isConflict());

        // Genuine verified SUCCEEDED with 2/2 ready & matching digest succeeds
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-result", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("leaseToken", leaseToken),
                                Map.entry("fencingGeneration", fence),
                                Map.entry("status", "SUCCEEDED"),
                                Map.entry("applyInitiated", true),
                                Map.entry("observedReplicas", 2),
                                Map.entry("updatedReplicas", 2),
                                Map.entry("readyReplicas", 2),
                                Map.entry("availableReplicas", 2),
                                Map.entry("observedGeneration", 1),
                                Map.entry("k8sDeploymentUid", "uid-dep-1"),
                                Map.entry("k8sServiceUid", "uid-svc-1"),
                                Map.entry("verifiedImageDigest", VALID_DIGEST),
                                Map.entry("errorSummary", "Deployment verified healthy")
                        ))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("pipelineRunId", runId),
                                Map.entry("stageRunId", stageId),
                                Map.entry("attemptNumber", 1),
                                Map.entry("leaseToken", leaseToken),
                                Map.entry("fencingGeneration", fence),
                                Map.entry("resultStatus", "SUCCEEDED"),
                                Map.entry("failureCategory", "NONE"),
                                Map.entry("exitCode", 0),
                                Map.entry("summary", "Healthy"),
                                Map.entry("startedAt", Instant.now().toString()),
                                Map.entry("completedAt", Instant.now().toString()),
                                Map.entry("terminationConfirmed", true)
                        ))))
                .andExpect(status().isOk());

        // Verify persisted deployment operation, events, live status endpoint, and audit trail
        mockMvc.perform(get("/api/workspaces/{ws}/deployments/{d}/status", workspaceA.getId(), depId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.readyReplicas").value(2))
                .andExpect(jsonPath("$.k8sDeploymentUid").value("uid-dep-1"));

        mockMvc.perform(get("/api/workspaces/{ws}/deployments/{d}/events", workspaceA.getId(), depId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        assertThat(pipelineRunRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(PipelineRunStatus.SUCCEEDED);
        assertThat(auditEventRepository.findAll())
                .anySatisfy(a -> assertThat(a.getAction()).isEqualTo("KUBERNETES_DEPLOYMENT_REQUESTED"));
    }

    @Test
    void postApplyCancellationAndWorkerLeaseExpirationTransitionToReconciliationRequiredWithoutBlindReplay() throws Exception {
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);
        ValidatedTarget target = provisionValidatedClusterAndNamespace(devEnv, "DEVELOPMENT");

        // Case 1: Worker crashes (lease expires) AFTER applyInitiated=true -> RECONCILIATION_REQUIRED (never blindly replayed)
        MvcResult createDep = mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .header("Idempotency-Key", "k8s-crash-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), verifiedArtifact.getId(), 2)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID depId = UUID.fromString(objectMapper.readTree(createDep.getResponse().getContentAsString()).get("id").asText());

        MvcResult claimRes = mockMvc.perform(post("/api/internal/workers/{w}/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lease = objectMapper.readTree(claimRes.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(lease.get("attemptId").asText());
        String leaseToken = lease.get("leaseToken").asText();
        long fence = lease.get("fencingGeneration").asLong();
        acknowledgeRunning(k8sWorker1Id, k8sWorker1Secret, attemptId, leaseToken, fence);

        for (String st : List.of("VALIDATING", "APPLYING")) {
            mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-status", k8sWorker1Id, attemptId)
                            .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                            .header("Authorization", "Bearer " + k8sWorker1Secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "leaseToken", leaseToken, "fencingGeneration", fence,
                                    "status", st, "applyInitiated", "APPLYING".equals(st),
                                    "observedReplicas", 0, "updatedReplicas", 0, "readyReplicas", 0, "availableReplicas", 0,
                                    "message", "Phase " + st
                            ))))
                    .andExpect(status().isNoContent());
        }

        // Simulate worker crash by expiring lease in DB and running recovery sweep
        StageExecutionAttemptEntity attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.setLeaseExpiresAt(Instant.now().minusSeconds(120));
        attemptRepository.saveAndFlush(attempt);

        var recovery = recoveryService.recoverExpiredWork(Instant.now());
        assertThat(recovery.recoveredAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(operationRepository.findById(depId).orElseThrow().getStatus())
                .isEqualTo(KubernetesDeploymentStatus.RECONCILIATION_REQUIRED);

        String unresolvedWorkload = deployJson(projectA.getId(), devEnv.getId(), target.clusterId(),
                target.namespaceId(), verifiedArtifact.getId(), 2);
        mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(unresolvedWorkload))
                .andExpect(status().isConflict());

        // Exercise post-apply cancellation on another workload while the first requires reconciliation.
        var anotherWorkload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(unresolvedWorkload);
        anotherWorkload.put("deploymentName", "payments-cancel");
        anotherWorkload.put("serviceName", "payments-cancel");
        MvcResult createDep2 = mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .header("Idempotency-Key", "k8s-cancel-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(anotherWorkload)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID dep2Id = UUID.fromString(objectMapper.readTree(createDep2.getResponse().getContentAsString()).get("id").asText());

        MvcResult claim2 = mockMvc.perform(post("/api/internal/workers/{w}/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lease2 = objectMapper.readTree(claim2.getResponse().getContentAsString());
        UUID attempt2Id = UUID.fromString(lease2.get("attemptId").asText());
        String leaseToken2 = lease2.get("leaseToken").asText();
        long fence2 = lease2.get("fencingGeneration").asLong();
        UUID run2Id = UUID.fromString(lease2.at("/stage/pipelineRunId").asText());
        UUID stage2Id = UUID.fromString(lease2.at("/stage/stageRunId").asText());
        acknowledgeRunning(k8sWorker1Id, k8sWorker1Secret, attempt2Id, leaseToken2, fence2);

        for (String st : List.of("VALIDATING", "APPLYING", "ROLLING_OUT")) {
            mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-status", k8sWorker1Id, attempt2Id)
                            .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                            .header("Authorization", "Bearer " + k8sWorker1Secret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "leaseToken", leaseToken2, "fencingGeneration", fence2,
                                    "status", st, "applyInitiated", !"VALIDATING".equals(st),
                                    "observedReplicas", 1, "updatedReplicas", 1, "readyReplicas", 0, "availableReplicas", 0,
                                    "message", "Phase " + st
                            ))))
                    .andExpect(status().isNoContent());
        }

        mockMvc.perform(post("/api/workspaces/{ws}/deployments/{d}/cancel", workspaceA.getId(), dep2Id)
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Operator abort\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationRequestedAt").isNotEmpty());

        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-result", k8sWorker1Id, attempt2Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("leaseToken", leaseToken2),
                                Map.entry("fencingGeneration", fence2),
                                Map.entry("status", "RECONCILIATION_REQUIRED"),
                                Map.entry("failureCategory", "CANCELLED"),
                                Map.entry("applyInitiated", true),
                                Map.entry("observedReplicas", 1),
                                Map.entry("updatedReplicas", 1),
                                Map.entry("readyReplicas", 0),
                                Map.entry("availableReplicas", 0),
                                Map.entry("errorSummary", "Cancelled after apply; no automatic rollback")
                        ))))
                .andExpect(status().isNoContent());

        assertThat(operationRepository.findById(dep2Id).orElseThrow().getStatus())
                .isEqualTo(KubernetesDeploymentStatus.RECONCILIATION_REQUIRED);

        // Complete cancelled attempt so k8sWorker1Id releases its concurrency slot cleanly
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", k8sWorker1Id, attempt2Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("pipelineRunId", run2Id),
                                Map.entry("stageRunId", stage2Id),
                                Map.entry("attemptNumber", 1),
                                Map.entry("leaseToken", leaseToken2),
                                Map.entry("fencingGeneration", fence2),
                                Map.entry("resultStatus", "CANCELLED"),
                                Map.entry("failureCategory", "CANCELLED"),
                                Map.entry("exitCode", 143),
                                Map.entry("summary", "Cancelled after apply"),
                                Map.entry("startedAt", Instant.now().toString()),
                                Map.entry("completedAt", Instant.now().toString()),
                                Map.entry("terminationConfirmed", true)
                        ))))
                .andExpect(status().isOk());
    }

    @Test
    void multiStageDagPipelineSupportsKubernetesDeployStageLinkedToDeploymentConfig() throws Exception {
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);
        ValidatedTarget target = provisionValidatedClusterAndNamespace(devEnv, "DEVELOPMENT");

        // Create an initial deployment to persist a saved KubernetesDeploymentConfig
        MvcResult createDep = mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .header("Idempotency-Key", "k8s-cfg-seed-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(), verifiedArtifact.getId(), 1)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID depId = UUID.fromString(objectMapper.readTree(createDep.getResponse().getContentAsString()).get("id").asText());
        UUID configId = UUID.fromString(objectMapper.readTree(createDep.getResponse().getContentAsString()).get("deploymentConfigId").asText());
        // Cancel the seed deployment before claim so no active workload conflict blocks the DAG test
        mockMvc.perform(post("/api/workspaces/{ws}/deployments/{d}/cancel", workspaceA.getId(), depId)
                        .with(authenticatedUser(owner)).with(csrf()))
                .andExpect(status().isOk());

        // Define DAG: SOURCE_PREP -> BUILD -> CONTAINER_PACKAGE -> DEPLOYMENT (KUBERNETES_DEPLOY)
        var dagDef = pipelineDefinitionService.createDefinition(
                workspaceA.getId(),
                new CreatePipelineDefinitionRequest(
                        projectA.getId(),
                        "Full Delivery DAG " + System.nanoTime(),
                        "SOURCE_PREP -> BUILD -> CONTAINER_PACKAGE -> DEPLOYMENT",
                        repoA.getId(),
                        true,
                        List.of(
                                new StageDefinitionRequest("source", "Source", PipelineStageType.SOURCE_PREP, List.of(), 300, 0, false, Map.of()),
                                new StageDefinitionRequest("build", "Build", PipelineStageType.BUILD, List.of("source"), 300, 0, false, Map.of()),
                                new StageDefinitionRequest("pkg", "Package", PipelineStageType.CONTAINER_PACKAGE, List.of("build"), 300, 0, false, Map.of()),
                                new StageDefinitionRequest("deploy", "Deploy Dev", PipelineStageType.DEPLOYMENT, List.of("pkg"), 300, 0, false,
                                        Map.of("operation", "KUBERNETES_DEPLOY", "deploymentConfigId", configId.toString()))
                        )
                ),
                owner
        );
        assertThat(dagDef.stages()).hasSize(4);

        var run = pipelineOrchestrationService.createRun(
                workspaceA.getId(),
                new CreatePipelineRunRequest(dagDef.id(), "main", COMMIT_SHA, PipelineTriggerType.MANUAL, null),
                "dag-run-" + UUID.randomUUID(),
                owner
        ).run();

        // Mark upstream stages SUCCEEDED and attach verified artifact to this run so deploy stage becomes READY
        List<PipelineStageRunEntity> stages = stageRunRepository.findAllByPipelineRunIdOrderByExecutionOrderAsc(run.id());
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            PipelineStageRunEntity s = stages.get(i);
            if (s.getStatus() == PipelineStageStatus.PENDING) {
                s.transitionStatus(PipelineStageStatus.READY, now, "Upstream ready");
            }
            s.transitionStatus(PipelineStageStatus.RUNNING, now, "Running");
            s.transitionStatus(PipelineStageStatus.SUCCEEDED, now, "Succeeded");
            stageRunRepository.saveAndFlush(s);
        }
        OciImageArtifactEntity freshArtifact = artifactRepository.findById(verifiedArtifact.getId()).orElseThrow();
        freshArtifact.setPipelineRun(stages.get(2).getPipelineRun());
        artifactRepository.saveAndFlush(freshArtifact);
        stages.get(3).transitionStatus(PipelineStageStatus.READY, now, "Ready for deploy");
        stageRunRepository.saveAndFlush(stages.get(3));

        // KUBERNETES_DEPLOY worker claims the DAG's DEPLOYMENT stage and receives a generated DeploymentOperation descriptor
        MvcResult claimRes = mockMvc.perform(post("/api/internal/workers/{w}/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage.stageKey").value("deploy"))
                .andExpect(jsonPath("$.allowedOperation").value("KUBERNETES_DEPLOY"))
                .andReturn();
        JsonNode lease = objectMapper.readTree(claimRes.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(lease.get("attemptId").asText());
        String leaseToken = lease.get("leaseToken").asText();
        long fence = lease.get("fencingGeneration").asLong();
        UUID dagStageId = UUID.fromString(lease.at("/stage/stageRunId").asText());
        acknowledgeRunning(k8sWorker1Id, k8sWorker1Secret, attemptId, leaseToken, fence);

        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/k8s-descriptor", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leaseToken", leaseToken,
                                "fencingGeneration", fence
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestDigest").value(VALID_DIGEST))
                .andExpect(jsonPath("$.deploymentConfigId").value(configId.toString()));

        // Release slot cleanly
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", k8sWorker1Id, attemptId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("pipelineRunId", run.id()),
                                Map.entry("stageRunId", dagStageId),
                                Map.entry("attemptNumber", 1),
                                Map.entry("leaseToken", leaseToken),
                                Map.entry("fencingGeneration", fence),
                                Map.entry("resultStatus", "CANCELLED"),
                                Map.entry("failureCategory", "CANCELLED"),
                                Map.entry("exitCode", 0),
                                Map.entry("summary", "DAG descriptor verified"),
                                Map.entry("startedAt", Instant.now().toString()),
                                Map.entry("completedAt", Instant.now().toString()),
                                Map.entry("terminationConfirmed", true)
                        ))))
                .andExpect(status().isOk());
    }

    @Test
    void releaseHistoryQueuesNewRollbackAndPreservesTheVerifiedTargets() throws Exception {
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);
        var target = provisionValidatedClusterAndNamespace(devEnv, "DEVELOPMENT");
        var first = queueReleaseFixture(target, 1);
        recordVerifiedFixture(first, 1);
        var second = queueReleaseFixture(target, 2);
        recordVerifiedFixture(second, 2);
        var workspace = workspaceA.getId();

        mockMvc.perform(get("/api/workspaces/{ws}/releases", workspace).with(authenticatedUser(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].revision").value(2))
                .andExpect(jsonPath("$.items[1].revision").value(1))
                .andExpect(jsonPath("$.items[1].historicallyVerified").value(true))
                .andExpect(content().string(not(containsString(BEARER_SECRET_VALUE))));

        mockMvc.perform(get("/api/workspaces/{ws}/releases/{release}", workspaceB.getId(), first)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/{ws}/releases/compare", workspace)
                        .with(authenticatedUser(viewer)).param("first", first.toString()).param("second", second.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fields[?(@.field == 'replicaCount')].changed").value(true));

        mockMvc.perform(post("/api/workspaces/{ws}/releases/{release}/rollback-preview", workspace, first)
                        .with(authenticatedUser(developer)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.expectedOperationId").value(second.toString()));

        var rollbackJson = Map.of("expectedOperationId", second, "reason", "Restore verified release", "confirmed", true);
        rollbackRequest(asJson(rollbackJson), first, viewer, "rollback-viewer").andExpect(status().isForbidden());

        rollbackRequest(asJson(Map.of("expectedOperationId", first, "reason", "Restore verified release", "confirmed", true)),
                first, developer, "rollback-stale").andExpect(status().isConflict());

        var response = rollbackRequest(asJson(rollbackJson), first, developer, "rollback-correct")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.type").value("ROLLBACK"))
                .andExpect(jsonPath("$.targetReleaseId").value(first.toString()))
                .andExpect(jsonPath("$.operation.status").value("PENDING"))
                .andExpect(jsonPath("$.operation.requestedReplicas").value(1))
                .andReturn();
        var rollbackOperation = objectMapper.readTree(response.getResponse().getContentAsString()).get("operation");
        releaseFixtureRuns.add(UUID.fromString(rollbackOperation.get("pipelineRunId").asText()));
        var rollbackId = rollbackOperation.get("id").asText();

        rollbackRequest(asJson(rollbackJson), first, developer, "rollback-correct")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operation.id").value(rollbackId));
        rollbackRequest(asJson(rollbackJson), first, developer, "rollback-concurrent").andExpect(status().isConflict());

        for (var release : List.of(first, second)) {
            assertThat(operationRepository.findById(release).orElseThrow().getStatus())
                    .isEqualTo(KubernetesDeploymentStatus.SUCCEEDED);
        }
        assertThat(auditEventRepository.findAll())
                .anyMatch(event -> event.getAction().equals("KUBERNETES_ROLLBACK_REQUESTED"));
    }

    @Test
    void reconcilesUncertainStateAsASeparateReadOnlyOperation() throws Exception {
        registerK8sWorker(k8sWorker1Id, k8sWorker1Secret);
        var target = provisionValidatedClusterAndNamespace(devEnv, "DEVELOPMENT");
        var uncertainId = queueReleaseFixture(target, 1);
        var uncertain = operationRepository.findById(uncertainId).orElseThrow();
        uncertain.transitionStatus(KubernetesDeploymentStatus.RECONCILIATION_REQUIRED, Instant.now());
        operationRepository.saveAndFlush(uncertain);

        mockMvc.perform(post("/api/workspaces/{ws}/deployments/{release}/reconcile", workspaceA.getId(), uncertainId)
                        .with(authenticatedUser(developer)).with(csrf()).header("Idempotency-Key", "observe-denied"))
                .andExpect(status().isForbidden());
        var observation = mockMvc.perform(post("/api/workspaces/{ws}/deployments/{release}/reconcile", workspaceA.getId(), uncertainId)
                        .with(authenticatedUser(owner)).with(csrf()).header("Idempotency-Key", "observe-release"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.kind").value("RECONCILIATION"))
                .andExpect(jsonPath("$.operation.applyInitiated").value(false))
                .andExpect(jsonPath("$.expectedOperationId").value(uncertainId.toString())).andReturn();
        releaseFixtureRuns.add(UUID.fromString(objectMapper.readTree(observation.getResponse().getContentAsString())
                .at("/operation/pipelineRunId").asText()));
        assertThat(operationRepository.findById(uncertainId).orElseThrow().getStatus())
                .isEqualTo(KubernetesDeploymentStatus.RECONCILIATION_REQUIRED);
    }

    private org.springframework.test.web.servlet.ResultActions rollbackRequest(String payload, UUID release, UserEntity actor, String key)
            throws Exception {
        return mockMvc.perform(post("/api/workspaces/{ws}/releases/{release}/rollback", workspaceA.getId(), release)
                .with(authenticatedUser(actor)).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private String asJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private UUID queueReleaseFixture(ValidatedTarget target, int replicas) throws Exception {
        var queued = mockMvc.perform(post("/api/workspaces/{ws}/deployments", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(deployJson(projectA.getId(), devEnv.getId(), target.clusterId(), target.namespaceId(),
                                verifiedArtifact.getId(), replicas)))
                .andExpect(status().isCreated()).andReturn();
        var operation = objectMapper.readTree(queued.getResponse().getContentAsString());
        releaseFixtureRuns.add(UUID.fromString(operation.get("pipelineRunId").asText()));
        return UUID.fromString(operation.get("id").asText());
    }

    private void recordVerifiedFixture(UUID id, long generation) {
        var op = operationRepository.findById(id).orElseThrow();
        var now = Instant.now();
        for (var status : List.of(KubernetesDeploymentStatus.VALIDATING, KubernetesDeploymentStatus.APPLYING,
                KubernetesDeploymentStatus.VERIFYING, KubernetesDeploymentStatus.SUCCEEDED)) {
            op.transitionStatus(status, now);
        }
        op.setApplyInitiated(true);
        op.setObservedGeneration(generation);
        op.setObservedReplicas(op.getRequestedReplicas());
        op.setUpdatedReplicas(op.getRequestedReplicas());
        op.setReadyReplicas(op.getRequestedReplicas());
        op.setAvailableReplicas(op.getRequestedReplicas());
        op.setK8sDeploymentUid("existing-deployment-uid");
        op.setK8sServiceUid("existing-service-uid");
        operationRepository.saveAndFlush(op);
        var event = new KubernetesDeploymentEventEntity();
        event.setWorkspace(workspaceA);
        event.setDeploymentOperation(op);
        event.setEventType("DEPLOYMENT_SUCCEEDED");
        event.setToStatus("SUCCEEDED");
        event.setActorLabel("worker:fixture");
        event.setMessage("Verified healthy rollout");
        event.setMetadataJson("{}");
        event.setOccurredAt(now);
        eventRepository.saveAndFlush(event);
    }

    private ValidatedTarget provisionValidatedClusterAndNamespace(ProjectEnvironmentEntity env, String envCategory) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/workspaces/{ws}/clusters", workspaceA.getId())
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clusterCreateJson("kind-" + System.nanoTime(), "https://127.0.0.1:16443", envCategory)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID clusterId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText());
        UUID nsId = bindNamespace(clusterId, env);

        mockMvc.perform(post("/api/workspaces/{ws}/clusters/{c}/validate", workspaceA.getId(), clusterId)
                        .with(authenticatedUser(owner)).with(csrf()))
                .andExpect(status().isAccepted());

        MvcResult claimVal = mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/claim", k8sWorker1Id)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret))
                .andExpect(status().isOk())
                .andReturn();
        long nonce = objectMapper.readTree(claimVal.getResponse().getContentAsString()).get("validationNonce").asLong();

        mockMvc.perform(post("/api/internal/workers/{w}/k8s/cluster-validations/{c}/complete", k8sWorker1Id, clusterId)
                        .header("X-CloudForge-Worker-Id", k8sWorker1Id)
                        .header("Authorization", "Bearer " + k8sWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "validationNonce", nonce, "valid", true,
                                "kubernetesVersion", "v1.31.2+k3s1",
                                "supportedCapabilities", Map.of("appsV1Available", true)
                        ))))
                .andExpect(status().isNoContent());

        return new ValidatedTarget(clusterId, nsId);
    }

    private UUID bindNamespace(UUID clusterId, ProjectEnvironmentEntity env) throws Exception {
        MvcResult nsRes = mockMvc.perform(post("/api/workspaces/{ws}/clusters/{c}/namespaces", workspaceA.getId(), clusterId)
                        .with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectId", projectA.getId(),
                                "environmentId", env.getId(),
                                "workloadServiceAccountName", "cloudforge-workload"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(nsRes.getResponse().getContentAsString()).get("id").asText());
    }

    private void acknowledgeRunning(String workerId, String secret, UUID attemptId, String leaseToken, long fence) throws Exception {
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/running", workerId, attemptId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leaseToken", leaseToken,
                                "fencingGeneration", fence,
                                "startedAt", Instant.now().toString()
                        ))))
                .andExpect(status().isOk());
    }

    private void registerK8sWorker(String workerId, String secret) throws Exception {
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(k8sWorkerRegJson(workerId)))
                .andExpect(status().isOk());
    }

    private String k8sWorkerRegJson(String workerId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "workerId", workerId,
                "displayName", "K8s Deploy Worker " + workerId,
                "adapterType", "KUBERNETES_DEPLOY",
                "supportedOperations", List.of("KUBERNETES_DEPLOY"),
                "maxConcurrency", 1,
                "hostLabel", "local-test",
                "agentVersion", "0.3.6"
        ));
    }

    private String clusterCreateJson(String idSlug, String apiUrl, String envCat) throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("name", "Cluster " + idSlug),
                Map.entry("clusterIdentifier", idSlug),
                Map.entry("clusterType", "KIND_LOCAL"),
                Map.entry("environmentCategory", envCat),
                Map.entry("apiServerUrl", apiUrl),
                Map.entry("caCertificatePem", ephemeralTestCaPem),
                Map.entry("credentialProjectId", projectA.getId()),
                Map.entry("credentialEnvironmentId", devEnv.getId()),
                Map.entry("bearerTokenSecretId", bearerTokenSecretId),
                Map.entry("serviceAccountName", "cloudforge-deployer"),
                Map.entry("allowLocalLoopback", true),
                Map.entry("productionApprovalRequired", true)
        ));
    }

    private String deployJson(UUID projectId, UUID envId, UUID clusterId, UUID nsId, UUID artifactId, int replicas) throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("projectId", projectId),
                Map.entry("environmentId", envId),
                Map.entry("clusterConnectionId", clusterId),
                Map.entry("namespaceBindingId", nsId),
                Map.entry("ociArtifactId", artifactId),
                Map.entry("deploymentName", "payments-api"),
                Map.entry("serviceName", "payments-api"),
                Map.entry("replicaCount", replicas),
                Map.entry("containerPort", 8080),
                Map.entry("servicePort", 80)
        ));
    }

    private String deployJsonWithProdApproval(
            UUID projectId, UUID envId, UUID clusterId, UUID nsId, UUID artifactId, boolean confirmed, String reason
    ) throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("projectId", projectId),
                Map.entry("environmentId", envId),
                Map.entry("clusterConnectionId", clusterId),
                Map.entry("namespaceBindingId", nsId),
                Map.entry("ociArtifactId", artifactId),
                Map.entry("deploymentName", "payments-prod"),
                Map.entry("replicaCount", 2),
                Map.entry("productionApprovalConfirmed", confirmed),
                Map.entry("productionApprovalJustification", reason)
        ));
    }

    private void addMember(WorkspaceEntity ws, UserEntity user, WorkspaceRole role) {
        WorkspaceMemberEntity m = new WorkspaceMemberEntity();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(role);
        workspaceMemberRepository.saveAndFlush(m);
    }

    private ProjectEntity saveProject(WorkspaceEntity ws, UserEntity creator, LinkedRepositoryEntity repo, String name, String slug) {
        ProjectEntity p = new ProjectEntity();
        p.setWorkspace(ws);
        p.setCreatedBy(creator);
        p.setLinkedRepository(repo);
        p.setName(name);
        p.setSlug(slug);
        p.setDefaultBranch("main");
        p.setStatus(ProjectStatus.ACTIVE);
        return projectRepository.saveAndFlush(p);
    }

    private ProjectEnvironmentEntity saveEnv(ProjectEntity proj, String name, EnvironmentType type) {
        ProjectEnvironmentEntity e = new ProjectEnvironmentEntity();
        e.setProject(proj);
        e.setName(name);
        e.setEnvironmentType(type);
        e.setConfigVariablesJson("{}");
        return environmentRepository.saveAndFlush(e);
    }

    private final java.util.concurrent.atomic.AtomicInteger seedSeq = new java.util.concurrent.atomic.AtomicInteger(1);

    private OciImageArtifactEntity seedArtifact(
            WorkspaceEntity ws, ProjectEntity proj, PipelineStageRunEntity stage, OciRegistryConnectionEntity reg,
            String digest, String ref, OciArtifactVerificationStatus status
    ) {
        Instant now = Instant.now();
        int seq = seedSeq.getAndIncrement();
        StageExecutionAttemptEntity seedAttempt = new StageExecutionAttemptEntity();
        seedAttempt.setWorkspace(ws);
        seedAttempt.setPipelineRun(stage.getPipelineRun());
        seedAttempt.setStageRun(stage);
        seedAttempt.setWorkerId("oci-seed-worker");
        seedAttempt.setAttemptNumber(seq);
        seedAttempt.setFencingGeneration(seq);
        seedAttempt.setLeaseToken("seed-lease-" + UUID.randomUUID());
        seedAttempt.setAllowedOperation("OCI_IMAGE_PUBLISH");
        seedAttempt.setLeaseStartedAt(now);
        seedAttempt.setLeaseExpiresAt(now.plusSeconds(60));
        seedAttempt.setLastHeartbeatAt(now);
        seedAttempt = attemptRepository.saveAndFlush(seedAttempt);

        OciBuildRecordEntity build = new OciBuildRecordEntity();
        build.setWorkspace(ws);
        build.setProject(proj);
        build.setPipelineRun(stage.getPipelineRun());
        build.setStageRun(stage);
        build.setAttempt(seedAttempt);
        build.setLinkedRepository(repoA);
        build.setGithubRepositoryId(repoA.getGithubRepoId());
        build.setRepositoryFullName(repoA.getFullName());
        build.setCommitSha(COMMIT_SHA);
        build.setRegistryConnection(reg);
        build.setDockerfilePath("Dockerfile");
        build.setBuildContextPath(".");
        build.setTargetImageName("cloudforge/payments");
        build.setTargetTag("v1");
        build.setTargetPlatform("linux/amd64");
        build.setBuildMode(OciBuildMode.LOCAL_TRUSTED_BUILDKIT);
        build.setConfigChecksumSha256("3".repeat(64));
        build.setConfigSnapshotJson("{}");
        build.setStatus(OciBuildStatus.SUCCEEDED);
        build.setBuiltManifestDigest(digest);
        build.setWorkerId("oci-seed-worker");
        build = buildRecordRepository.saveAndFlush(build);

        OciImageArtifactEntity art = new OciImageArtifactEntity();
        art.setWorkspace(ws);
        art.setProject(proj);
        art.setPipelineRun(stage.getPipelineRun());
        art.setStageRun(stage);
        art.setAttempt(seedAttempt);
        art.setBuildRecord(build);
        art.setRegistryConnection(reg);
        art.setLinkedRepository(repoA);
        art.setGithubRepositoryId(repoA.getGithubRepoId());
        art.setRepositoryFullName(repoA.getFullName());
        art.setCommitSha(COMMIT_SHA);
        art.setRegistryHost("127.0.0.1:5000");
        art.setImageRepository("cloudforge/payments");
        art.setImageTag("v1");
        art.setManifestDigest(digest);
        art.setImmutableReference(ref);
        art.setImageMediaType("application/vnd.oci.image.manifest.v1+json");
        art.setPlatform("linux/amd64");
        art.setImageSizeBytes(2048L);
        art.setVerificationStatus(status);
        if (status == OciArtifactVerificationStatus.VERIFIED) {
            art.setVerifiedAt(Instant.now());
        }
        art.setPublishedAt(Instant.now());
        art.setRetentionPolicy(OciArtifactRetentionPolicy.RETAIN_METADATA);
        return artifactRepository.saveAndFlush(art);
    }

    private record ValidatedTarget(UUID clusterId, UUID namespaceId) {
    }
}
