package com.cloudforge.backend.oci;

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
import com.cloudforge.backend.github.GitHubSourceClient;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.controller.InternalWorkerOciController;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import com.cloudforge.backend.oci.entity.OciHandoffStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.entity.OciPublishAttemptEntity;
import com.cloudforge.backend.oci.entity.OciPublishAttemptStatus;
import com.cloudforge.backend.oci.entity.OciSourceHandoffEntity;
import com.cloudforge.backend.oci.repository.OciArtifactEventRepository;
import com.cloudforge.backend.oci.repository.OciBuildRecordRepository;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.repository.OciPublishAttemptRepository;
import com.cloudforge.backend.oci.repository.OciRegistryConnectionRepository;
import com.cloudforge.backend.oci.repository.OciSourceHandoffRepository;
import com.cloudforge.backend.oci.service.RegistrySecurityValidator;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.secrets.crypto.EnvKeyringSecretKeyProvider;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.service.UserAccountService;
import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.StageExecutionLogRepository;
import com.cloudforge.backend.worker.security.WorkerCredentialService;
import com.cloudforge.backend.worker.service.WorkerRecoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive Phase 3.5B OCI build, registry security, credential isolation,
 * digest verification, worker capability fencing, cancellation, and artifact persistence suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:oci_security_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "cloudforge.workers.enabled=true",
        "cloudforge.workers.scheduler-enabled=false",
        "cloudforge.workers.lease-duration-seconds=60",
        "cloudforge.workers.retry-base-backoff-seconds=1",
        "CLOUDFORGE_SOURCE_ACQUISITION_ENABLED=true",
        "CLOUDFORGE_SOURCE_ALLOWED_REPOSITORY_IDS=775501",
        "CLOUDFORGE_SOURCE_ALLOWED_WORKER_IDS=src-worker-1",
        "cloudforge.secrets.enabled=true",
        "cloudforge.secrets.key-provider=env-keyring",
        "cloudforge.secrets.active-key-id=oci-kek-v1",
        "cloudforge.secrets.keyring=oci-kek-v1:MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
        "cloudforge.secrets.worker-delivery-enabled=true",
        "cloudforge.secrets.allow-insecure-local-delivery=false",
        "cloudforge.secrets.denied-audit-cooldown-seconds=0",
        "cloudforge.oci.enabled=true",
        "cloudforge.oci.allow-local-dev-registry=true",
        "cloudforge.oci.allow-insecure-local-credential-delivery=false",
        "cloudforge.oci.allowed-registry-hosts=127.0.0.1:5000,localhost:5000,ghcr.io",
        "cloudforge.oci.allowed-worker-ids=oci-worker-1,oci-worker-2",
        "cloudforge.oci.max-credential-deliveries-per-attempt=2"
})
class OciRegistryAndBuildSecurityTest {

    private static final String PINNED_SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final String VALID_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TAMPERED_DIGEST = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    private static final String REGISTRY_USER_SECRET_VALUE = "cf-registry-bot-user";
    private static final String REGISTRY_PASS_SECRET_VALUE = "cf_super_secret_reg_pat_987654321";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserAccountService userAccountService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private GitHubInstallationRepository installationRepository;
    @Autowired private LinkedRepositoryRepository linkedRepositoryRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectEnvironmentRepository environmentRepository;
    @Autowired private WorkerCredentialService workerCredentialService;
    @Autowired private StageExecutionAttemptRepository attemptRepository;
    @Autowired private StageExecutionLogRepository logRepository;
    @Autowired private PipelineRunRepository runRepository;
    @Autowired private PipelineStageRunRepository stageRunRepository;
    @Autowired private WorkerRecoveryService recoveryService;
    @Autowired private OciRegistryConnectionRepository registryRepository;
    @Autowired private OciSourceHandoffRepository handoffRepository;
    @Autowired private OciBuildRecordRepository buildRecordRepository;
    @Autowired private OciImageArtifactRepository artifactRepository;
    @Autowired private OciPublishAttemptRepository publishAttemptRepository;
    @Autowired private OciArtifactEventRepository artifactEventRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private OciBuildProperties ociProperties;
    @Autowired private EnvKeyringSecretKeyProvider keyProvider;
    @Autowired private SecretAuditService secretAuditService;

    @MockitoBean
    private GitHubSourceClient githubSourceClient;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private LinkedRepositoryEntity approvedRepo;
    private ProjectEntity projectA;
    private ProjectEnvironmentEntity envA;
    private UUID regUserSecretId;
    private UUID regPassSecretId;

    private final String srcWorkerId = "src-worker-1";
    private final String srcWorkerSecret = "src-worker-1-secret-token-1234567890";
    private final String ociWorker1Id = "oci-worker-1";
    private final String ociWorker1Secret = "oci-worker-1-secret-token-1234567890";
    private final String ociWorker2Id = "oci-worker-2";
    private final String ociWorker2Secret = "oci-worker-2-secret-token-1234567890";
    private final String rogueOciWorkerId = "rogue-oci-worker";
    private final String rogueOciWorkerSecret = "rogue-oci-worker-secret-token-123456";
    private final String dockerWorkerId = "docker-worker-1";
    private final String dockerWorkerSecret = "docker-worker-1-secret-token-1234567";

    @BeforeEach
    void setUp() throws Exception {
        secretAuditService.clearRateLimitState();
        keyProvider.reloadFromProperties();
        ociProperties.setEnabled(true);
        ociProperties.setAllowLocalDevRegistry(true);
        ociProperties.setAllowInsecureLocalCredentialDelivery(false);
        ociProperties.setAllowedRegistryHosts("127.0.0.1:5000,localhost:5000,ghcr.io");
        ociProperties.setAllowedWorkerIds("oci-worker-1,oci-worker-2");

        long uid = 91000L + (System.nanoTime() % 10000);
        owner = userAccountService.upsertFromGitHubOAuth(uid, "oci-owner-" + uid, "OCI Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(uid + 1, "oci-dev-" + uid, "OCI Dev", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(uid + 2, "oci-view-" + uid, "OCI Viewer", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(uid + 3, "oci-out-" + uid, "OCI Outsider", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        addMember(workspaceA, developer, WorkspaceRole.DEVELOPER);
        addMember(workspaceA, viewer, WorkspaceRole.VIEWER);

        GitHubInstallationEntity inst = new GitHubInstallationEntity();
        inst.setWorkspace(workspaceA);
        inst.setGithubInstallationId(775500L + (uid % 500));
        inst.setAccountLogin("cloudforge-org");
        inst.setAccountType("Organization");
        inst.setStatus(InstallationStatus.ACTIVE);
        inst = installationRepository.saveAndFlush(inst);

        approvedRepo = new LinkedRepositoryEntity();
        approvedRepo.setWorkspace(workspaceA);
        approvedRepo.setInstallation(inst);
        approvedRepo.setGithubRepoId(775501L);
        approvedRepo.setOwnerLogin("cloudforge-org");
        approvedRepo.setName("payments-service");
        approvedRepo.setFullName("cloudforge-org/payments-service");
        approvedRepo.setDefaultBranch("main");
        approvedRepo.setVisibility("private");
        approvedRepo.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        approvedRepo.setLastSyncedAt(Instant.now());
        approvedRepo = linkedRepositoryRepository.saveAndFlush(approvedRepo);

        projectA = new ProjectEntity();
        projectA.setWorkspace(workspaceA);
        projectA.setCreatedBy(owner);
        projectA.setLinkedRepository(approvedRepo);
        projectA.setName("Payments OCI Service");
        projectA.setSlug("payments-oci-" + uid);
        projectA.setDefaultBranch("main");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.saveAndFlush(projectA);

        envA = new ProjectEnvironmentEntity();
        envA.setProject(projectA);
        envA.setName("Build Registry");
        envA.setEnvironmentType(EnvironmentType.STAGING);
        envA.setConfigVariablesJson("{}");
        envA = environmentRepository.saveAndFlush(envA);

        String secretBase = "/api/workspaces/" + workspaceA.getId() + "/projects/" + projectA.getId()
                + "/environments/" + envA.getId() + "/secrets";
        MvcResult uRes = mockMvc.perform(post(secretBase)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OCI_REG_USER\",\"value\":\"" + REGISTRY_USER_SECRET_VALUE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        regUserSecretId = UUID.fromString(objectMapper.readTree(uRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult pRes = mockMvc.perform(post(secretBase)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OCI_REG_PASS\",\"value\":\"" + REGISTRY_PASS_SECRET_VALUE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        regPassSecretId = UUID.fromString(objectMapper.readTree(pRes.getResponse().getContentAsString()).get("id").asText());

        provisionWorkerIfNeeded(srcWorkerId, "k-src-" + uid, srcWorkerSecret);
        provisionWorkerIfNeeded(ociWorker1Id, "k-oci1-" + uid, ociWorker1Secret);
        provisionWorkerIfNeeded(ociWorker2Id, "k-oci2-" + uid, ociWorker2Secret);
        provisionWorkerIfNeeded(rogueOciWorkerId, "k-rogue-" + uid, rogueOciWorkerSecret);
        provisionWorkerIfNeeded(dockerWorkerId, "k-dkr-" + uid, dockerWorkerSecret);

        GitHubSourceClient.Repository repoRef = new GitHubSourceClient.Repository(
                inst.getGithubInstallationId(), 775501L, "cloudforge-org", "payments-service"
        );
        when(githubSourceClient.resolveCommit(repoRef, "refs/heads/main")).thenReturn(PINNED_SHA);
        when(githubSourceClient.requestArchive(repoRef, PINNED_SHA))
                .thenReturn(java.net.URI.create("https://codeload.github.com/cloudforge-org/payments-service/zip/" + PINNED_SHA));
    }

    @AfterEach
    void tearDown() {
        auditEventRepository.deleteAll();
        if (workspaceA != null && workspaceRepository.existsById(workspaceA.getId())) {
            workspaceRepository.deleteById(workspaceA.getId());
        }
        if (workspaceB != null && workspaceRepository.existsById(workspaceB.getId())) {
            workspaceRepository.deleteById(workspaceB.getId());
        }
    }

    @Test
    void enforcesRegistryRbacCrossWorkspaceIsolationSsrfAndNoPlaintextDisclosure() throws Exception {
        String regUrl = "/api/workspaces/" + workspaceA.getId() + "/registries";
        String validPayload = createLocalRegistryPayload("Local Dev OCI", "127.0.0.1:5000", "http", "LOCAL_DEV", "cloudforge", true);

        // 1. Unauthorized DEVELOPER and VIEWER rejected (403); outsider rejected (403)
        mockMvc.perform(post(regUrl).with(authenticatedUser(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validPayload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(regUrl).with(authenticatedUser(viewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validPayload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(regUrl).with(authenticatedUser(outsider)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validPayload))
                .andExpect(status().isForbidden());

        // 2. CSRF required on mutations
        mockMvc.perform(post(regUrl).with(authenticatedUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(validPayload))
                .andExpect(status().isForbidden());

        // 3. SSRF & unapproved registry destination checks
        for (String badHost : List.of("169.254.169.254", "metadata.google.internal", "10.0.0.1:5000", "192.168.1.50:5000", "evil.registry.io")) {
            mockMvc.perform(post(regUrl).with(authenticatedUser(owner)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createLocalRegistryPayload("Bad " + badHost, badHost, "https", "OCI_GENERIC", "cloudforge", false)))
                    .andExpect(status().isForbidden());
        }

        // Plaintext HTTP for remote registry rejected
        mockMvc.perform(post(regUrl).with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLocalRegistryPayload("Insecure GHCR", "ghcr.io", "http", "GHCR", "cloudforge", false)))
                .andExpect(status().isForbidden());

        // Loopback rejected when LOCAL_DEV / allowInsecureLocal is false
        mockMvc.perform(post(regUrl).with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLocalRegistryPayload("Loopback Disguised", "127.0.0.1:5000", "https", "OCI_GENERIC", "cloudforge", false)))
                .andExpect(status().isForbidden());

        // 4. Valid OWNER registry creation succeeds and never returns plaintext secret values
        MvcResult created = mockMvc.perform(post(regUrl).with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registryHost").value("127.0.0.1:5000"))
                .andExpect(jsonPath("$.allowedNamespace").value("cloudforge"))
                .andExpect(jsonPath("$.passwordSecretName").value("OCI_REG_PASS"))
                .andExpect(content().string(not(containsString(REGISTRY_PASS_SECRET_VALUE))))
                .andExpect(content().string(not(containsString(REGISTRY_USER_SECRET_VALUE))))
                .andReturn();
        String regId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // 5. Workspace member can read; cross-workspace outsider blocked on registry, builds, artifacts
        mockMvc.perform(get(regUrl).with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(regId))
                .andExpect(content().string(not(containsString(REGISTRY_PASS_SECRET_VALUE))));

        mockMvc.perform(get(regUrl).with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/builds").with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/artifacts").with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/" + workspaceB.getId() + "/registries/" + regId).with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesDeclarativeBuildValidationDagAncestryAndWorkerCapabilityBoundaries() throws Exception {
        UUID registryId = createValidLocalRegistry();
        String defsUrl = "/api/workspaces/" + workspaceA.getId() + "/pipelines/definitions";

        // 1. OCI_IMAGE_BUILD without upstream GITHUB_SOURCE_ACQUIRE rejected
        mockMvc.perform(post(defsUrl).with(authenticatedUser(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Missing Source Stage",
                                  "stages": [{
                                    "stageKey": "build",
                                    "displayName": "Build",
                                    "stageType": "BUILD",
                                    "metadata": {
                                      "operation": "OCI_IMAGE_BUILD",
                                      "registryConnectionId": "%s",
                                      "targetImageName": "cloudforge/payments"
                                    }
                                  }]
                                }
                                """.formatted(projectA.getId(), registryId)))
                .andExpect(status().isBadRequest());

        // 2. Path traversal, absolute path, remote URI, secret-like buildArg, privileged mode, wrong namespace rejected
        for (String badMeta : List.of(
                "\"dockerfilePath\":\"../etc/passwd\"",
                "\"dockerfilePath\":\"/etc/Dockerfile\"",
                "\"buildContextPath\":\"https://evil.example/ctx.tar\"",
                "\"buildContextPath\":\"docker://unix:///var/run/docker.sock\"",
                "\"buildMode\":\"PRIVILEGED\"",
                "\"buildArg.API_SECRET\":\"leaked\"",
                "\"targetImageName\":\"other-org/hijack\""
        )) {
            mockMvc.perform(post(defsUrl).with(authenticatedUser(owner)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "projectId": "%s",
                                      "name": "Bad OCI Config",
                                      "stages": [
                                        {
                                          "stageKey": "src",
                                          "displayName": "Source",
                                          "stageType": "SOURCE_PREP",
                                          "metadata": {"operation": "GITHUB_SOURCE_ACQUIRE"}
                                        },
                                        {
                                          "stageKey": "build",
                                          "displayName": "Build",
                                          "stageType": "BUILD",
                                          "dependsOn": ["src"],
                                          "metadata": {
                                            "operation": "OCI_IMAGE_BUILD",
                                            "registryConnectionId": "%s",
                                            "targetImageName": "cloudforge/payments",
                                            %s
                                          }
                                        }
                                      ]
                                    }
                                    """.formatted(projectA.getId(), registryId, badMeta)))
                    .andExpect(status().is4xxClientError());
        }

        // 3. Worker capability isolation: DOCKER_ISOLATED cannot advertise OCI ops; OCI_BUILDKIT cannot advertise source/Phase 3.3 ops
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", dockerWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dockerWorkerSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Bad Docker Worker",
                                  "adapterType": "DOCKER_ISOLATED",
                                  "supportedOperations": ["OCI_IMAGE_BUILD"],
                                  "maxConcurrency": 1
                                }
                                """.formatted(dockerWorkerId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Bad Mixed OCI Worker",
                                  "adapterType": "OCI_BUILDKIT",
                                  "supportedOperations": ["OCI_IMAGE_BUILD", "GITHUB_SOURCE_ACQUIRE"],
                                  "maxConcurrency": 1
                                }
                                """.formatted(ociWorker1Id)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void executesFullEndToEndOciLifecycleWithCredentialIsolationDigestVerificationRedactionAndIdempotency() throws Exception {
        UUID registryId = createValidLocalRegistry();
        registerStandardWorkers();

        // Register unallowlisted OCI worker too; it must NOT claim OCI stages
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", rogueOciWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rogueOciWorkerSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Unallowlisted OCI",
                                  "adapterType": "OCI_BUILDKIT",
                                  "supportedOperations": ["OCI_IMAGE_BUILD", "OCI_IMAGE_PUBLISH"],
                                  "maxConcurrency": 1
                                }
                                """.formatted(rogueOciWorkerId)))
                .andExpect(status().isOk());

        UUID defId = createFullOciPipelineDefinition(registryId);
        UUID runId = queuePipelineRun(defId, "idem-oci-full-1");

        // === STAGE 1: GITHUB_SOURCE_ACQUIRE with sourceHandoffRequired=true ===
        JsonNode srcClaim = claimStage(srcWorkerId, srcWorkerSecret);
        assertThat(srcClaim.get("allowedOperation").asText()).isEqualTo("GITHUB_SOURCE_ACQUIRE");
        assertThat(srcClaim.get("sourceHandoffRequired").asBoolean()).isTrue();
        UUID srcAttemptId = UUID.fromString(srcClaim.get("attemptId").asText());
        String srcLease = srcClaim.get("leaseToken").asText();
        long srcFence = srcClaim.get("fencingGeneration").asLong();

        ackRunning(srcWorkerId, srcWorkerSecret, srcAttemptId, srcLease, srcFence);

        // Uploading source handoff BEFORE requesting a GitHub source archive grant MUST be rejected (409)
        byte[] sourceZip = buildMinimalZip("Dockerfile", "FROM scratch\nCOPY hello.txt /hello.txt\n");
        String sourceSha256 = sha256Hex(sourceZip);
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-handoff", srcWorkerId, srcAttemptId)
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, srcLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, srcFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, sourceSha256)
                        .header(InternalWorkerOciController.HEADER_FILE_COUNT, 1)
                        .header(InternalWorkerOciController.HEADER_EXTRACTED_BYTES, 40)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(sourceZip))
                .andExpect(status().isConflict());

        // Request pinned GitHub source archive grant over HTTPS first
        issueSourceArchive(srcWorkerId, srcWorkerSecret, srcAttemptId, srcLease, srcFence);

        // Source checksum mismatch must be rejected (409)
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-handoff", srcWorkerId, srcAttemptId)
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, srcLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, srcFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, "0".repeat(64))
                        .header(InternalWorkerOciController.HEADER_FILE_COUNT, 1)
                        .header(InternalWorkerOciController.HEADER_EXTRACTED_BYTES, 40)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(sourceZip))
                .andExpect(status().isConflict());

        // Valid verified source handoff upload succeeds
        MvcResult handoffRes = mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-handoff", srcWorkerId, srcAttemptId)
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, srcLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, srcFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, sourceSha256)
                        .header(InternalWorkerOciController.HEADER_FILE_COUNT, 1)
                        .header(InternalWorkerOciController.HEADER_EXTRACTED_BYTES, 40)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(sourceZip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentChecksumSha256").value(sourceSha256))
                .andReturn();
        UUID srcHandoffId = UUID.fromString(objectMapper.readTree(handoffRes.getResponse().getContentAsString()).get("handoffId").asText());

        completeAttempt(srcWorkerId, srcWorkerSecret, srcAttemptId, srcLease, srcFence, "SUCCEEDED", "NONE", "Source acquired");

        // === STAGE 2: OCI_IMAGE_BUILD ===
        // Unallowlisted OCI worker gets 204 No Content (cannot claim)
        mockMvc.perform(post("/api/internal/workers/{w}/claim", rogueOciWorkerId)
                        .header("X-CloudForge-Worker-Id", rogueOciWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rogueOciWorkerSecret))
                .andExpect(status().isNoContent());

        // Allowlisted worker 1 claims OCI_IMAGE_BUILD; duplicate claim by worker 2 gets 204
        JsonNode buildClaim = claimStage(ociWorker1Id, ociWorker1Secret);
        assertThat(buildClaim.get("allowedOperation").asText()).isEqualTo("OCI_IMAGE_BUILD");
        assertThat(buildClaim.get("idempotentOperation").asBoolean()).isTrue();
        UUID buildAttemptId = UUID.fromString(buildClaim.get("attemptId").asText());
        String buildLease = buildClaim.get("leaseToken").asText();
        long buildFence = buildClaim.get("fencingGeneration").asLong();

        mockMvc.perform(post("/api/internal/workers/{w}/claim", ociWorker2Id)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret))
                .andExpect(status().isNoContent());

        ackRunning(ociWorker1Id, ociWorker1Secret, buildAttemptId, buildLease, buildFence);

        // Fetch build descriptor -> registryCredentialsRequired MUST be false
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-descriptor", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(buildLease, buildFence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("OCI_IMAGE_BUILD"))
                .andExpect(jsonPath("$.commitSha").value(PINNED_SHA))
                .andExpect(jsonPath("$.sourceHandoffId").value(srcHandoffId.toString()))
                .andExpect(jsonPath("$.expectedSourceSha256").value(sourceSha256))
                .andExpect(jsonPath("$.hasRegistryCredentials").value(false));

        // CRITICAL SECURITY CHECK: Registry credentials MUST NEVER be delivered to OCI_IMAGE_BUILD stage!
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/registry-credentials", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .with(req -> { req.setSecure(true); req.setScheme("https"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(buildLease, buildFence)))
                .andExpect(status().isForbidden());

        // Download verified build context with checksum headers
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/build-context", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(buildLease, buildFence)))
                .andExpect(status().isOk())
                .andExpect(header().string(InternalWorkerOciController.HEADER_CONTENT_SHA256, sourceSha256))
                .andExpect(header().string(InternalWorkerOciController.HEADER_COMMIT_SHA, PINNED_SHA))
                .andExpect(content().bytes(sourceZip));

        // Build completion gate: cannot complete SUCCEEDED before reporting oci_build_records!
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(buildAttemptId, buildLease, buildFence, "SUCCEEDED", "NONE", "Premature")))
                .andExpect(status().isConflict());

        // Upload verified OCI layout tar
        byte[] ociLayoutTar = "fake-oci-layout-tar-bytes-for-backend-test".getBytes(StandardCharsets.UTF_8);
        String layoutSha = sha256Hex(ociLayoutTar);
        MvcResult layoutRes = mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-layout-upload", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, buildLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, buildFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, layoutSha)
                        .header(InternalWorkerOciController.HEADER_IMAGE_SIZE_BYTES, ociLayoutTar.length)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(ociLayoutTar))
                .andExpect(status().isOk())
                .andReturn();
        UUID ociHandoffId = UUID.fromString(objectMapper.readTree(layoutRes.getResponse().getContentAsString()).get("handoffId").asText());

        // Report build result with immutable digest
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/build-result", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "%s",
                                  "fencingGeneration": %d,
                                  "status": "SUCCEEDED",
                                  "builtManifestDigest": "%s",
                                  "imageMediaType": "application/vnd.oci.image.manifest.v1+json",
                                  "imageSizeBytes": 4096,
                                  "ociHandoffId": "%s"
                                }
                                """.formatted(buildLease, buildFence, VALID_DIGEST, ociHandoffId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtManifestDigest").value(VALID_DIGEST));

        // Stale fencing generation on complete rejected (409)
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", ociWorker1Id, buildAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(buildAttemptId, buildLease, buildFence + 99, "SUCCEEDED", "NONE", "Stale")))
                .andExpect(status().isConflict());

        // Complete build stage cleanly -> unlocks OCI_IMAGE_PUBLISH
        completeAttempt(ociWorker1Id, ociWorker1Secret, buildAttemptId, buildLease, buildFence, "SUCCEEDED", "NONE", "Built OCI layout");

        // === STAGE 3: OCI_IMAGE_PUBLISH ===
        JsonNode pubClaim = claimStage(ociWorker2Id, ociWorker2Secret);
        assertThat(pubClaim.get("allowedOperation").asText()).isEqualTo("OCI_IMAGE_PUBLISH");
        assertThat(pubClaim.get("idempotentOperation").asBoolean()).isFalse();
        UUID pubAttemptId = UUID.fromString(pubClaim.get("attemptId").asText());
        String pubLease = pubClaim.get("leaseToken").asText();
        long pubFence = pubClaim.get("fencingGeneration").asLong();

        ackRunning(ociWorker2Id, ociWorker2Secret, pubAttemptId, pubLease, pubFence);

        // Fetch publish descriptor
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-descriptor", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(pubLease, pubFence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("OCI_IMAGE_PUBLISH"))
                .andExpect(jsonPath("$.expectedManifestDigest").value(VALID_DIGEST))
                .andExpect(jsonPath("$.hasRegistryCredentials").value(true));

        // Credential delivery over plaintext HTTP fails closed (403) when allowInsecureLocalCredentialDelivery=false
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/registry-credentials", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(pubLease, pubFence)))
                .andExpect(status().isForbidden());

        // Credential delivery over HTTPS succeeds with Cache-Control: no-store
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/registry-credentials", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .with(req -> { req.setSecure(true); req.setScheme("https"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(pubLease, pubFence)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.username").value(REGISTRY_USER_SECRET_VALUE))
                .andExpect(jsonPath("$.password").value(REGISTRY_PASS_SECRET_VALUE));

        // Download verified OCI layout
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-layout-download", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(pubLease, pubFence)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(ociLayoutTar));

        // Secret redaction check: log containing registry password MUST be scrubbed on server
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/logs", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "%s",
                                  "fencingGeneration": %d,
                                  "entries": [{
                                    "streamType": "STDOUT",
                                    "message": "Accidental leak: %s",
                                    "occurredAt": "%s"
                                  }]
                                }
                                """.formatted(pubLease, pubFence, REGISTRY_PASS_SECRET_VALUE, Instant.now().toString())))
                .andExpect(status().isOk());
        var storedLogs = logRepository.findAll();
        assertThat(storedLogs).isNotEmpty();
        assertThat(storedLogs).allSatisfy(l -> assertThat(l.getMessage()).doesNotContain(REGISTRY_PASS_SECRET_VALUE));

        // Digest mismatch MUST be rejected (409) and recorded as DIGEST_VERIFICATION_FAILED
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/publish-result", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "%s",
                                  "fencingGeneration": %d,
                                  "status": "VERIFIED",
                                  "reportedDigest": "%s",
                                  "verifiedDigest": "%s",
                                  "imageMediaType": "application/vnd.oci.image.manifest.v1+json",
                                  "platform": "linux/amd64",
                                  "imageSizeBytes": 4096,
                                  "verified": true
                                }
                                """.formatted(pubLease, pubFence, TAMPERED_DIGEST, TAMPERED_DIGEST)))
                .andExpect(status().isConflict());

        // Publish completion gate: cannot complete SUCCEEDED without VERIFIED artifact
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(pubAttemptId, pubLease, pubFence, "SUCCEEDED", "NONE", "Unverified")))
                .andExpect(status().isConflict());

        // Valid verified publish report succeeds and persists immutable artifact
        String validPublishJson = """
                {
                  "leaseToken": "%s",
                  "fencingGeneration": %d,
                  "status": "VERIFIED",
                  "reportedDigest": "%s",
                  "verifiedDigest": "%s",
                  "imageMediaType": "application/vnd.oci.image.manifest.v1+json",
                  "platform": "linux/amd64",
                  "imageSizeBytes": 4096,
                  "verified": true
                }
                """.formatted(pubLease, pubFence, VALID_DIGEST, VALID_DIGEST);

        MvcResult pubRes = mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/publish-result", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPublishJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedDigest").value(VALID_DIGEST))
                .andReturn();
        String artifactId = objectMapper.readTree(pubRes.getResponse().getContentAsString()).get("artifactId").asText();

        // Idempotent duplicate publish report MUST NOT create duplicate artifact row
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/publish-result", ociWorker2Id, pubAttemptId)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPublishJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").value(artifactId));
        assertThat(artifactRepository.countByWorkspaceId(workspaceA.getId())).isEqualTo(1L);

        // Complete publish stage -> run transitions to SUCCEEDED and cleans ephemeral handoffs
        completeAttempt(ociWorker2Id, ociWorker2Secret, pubAttemptId, pubLease, pubFence, "SUCCEEDED", "NONE",
                "Published with " + REGISTRY_PASS_SECRET_VALUE);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(PipelineRunStatus.SUCCEEDED);
        // Ephemeral handoffs cleaned after terminal run completion
        assertThat(handoffRepository.findAllByPipelineRunIdAndStatusIn(
                runId, List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED)
        )).isEmpty();

        // Verify query REST APIs expose build history, immutable artifact, detail, and run artifacts
        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/artifacts").with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].manifestDigest").value(VALID_DIGEST))
                .andExpect(jsonPath("$.items[0].immutableReference").value("127.0.0.1:5000/cloudforge/payments@" + VALID_DIGEST))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("VERIFIED"))
                .andExpect(content().string(not(containsString(REGISTRY_PASS_SECRET_VALUE))));

        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/artifacts/" + artifactId).with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.id").value(artifactId))
                .andExpect(jsonPath("$.buildRecord.commitSha").value(PINNED_SHA))
                .andExpect(jsonPath("$.publishAttempts[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.events").isNotEmpty());

        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/pipelines/runs/" + runId + "/artifacts").with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builds.length()").value(1))
                .andExpect(jsonPath("$.artifacts.length()").value(1));

        mockMvc.perform(get("/api/workspaces/" + workspaceA.getId() + "/builds").with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"));
    }

    @Test
    void enforcesNonIdempotentPublishReconciliationOnLeaseExpiryAndCancellationCleanup() throws Exception {
        UUID registryId = createValidLocalRegistry();
        registerStandardWorkers();
        UUID defId = createFullOciPipelineDefinition(registryId);

        // === Case 1: Publish lease expiry marks RECONCILIATION_REQUIRED and does NOT auto-retry ===
        UUID runId = queuePipelineRun(defId, "idem-oci-expiry-1");
        advanceToPublishRunning(runId);

        StageExecutionAttemptEntity pubAttempt = attemptRepository.findAll().stream()
                .filter(a -> a.getPipelineRun().getId().equals(runId) && "OCI_IMAGE_PUBLISH".equals(a.getAllowedOperation()))
                .findFirst().orElseThrow();
        pubAttempt.setLeaseExpiresAt(Instant.now().minusSeconds(120));
        attemptRepository.saveAndFlush(pubAttempt);

        int recovered = recoveryService.recoverExpiredWork(Instant.now()).recoveredAttempts();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        StageExecutionAttemptEntity refreshedAttempt = attemptRepository.findById(pubAttempt.getId()).orElseThrow();
        assertThat(refreshedAttempt.getStatus()).isEqualTo(AttemptStatus.EXPIRED);
        assertThat(refreshedAttempt.getFailureCategory()).isEqualTo(AttemptFailureCategory.LEASE_EXPIRED);

        PipelineStageRunEntity pubStage = stageRunRepository.findById(pubAttempt.getStageRun().getId()).orElseThrow();
        assertThat(pubStage.getStatus()).isEqualTo(PipelineStageStatus.FAILED);
        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(PipelineRunStatus.FAILED);

        List<OciPublishAttemptEntity> pubRecords =
                publishAttemptRepository.findAllByPipelineRunIdAndWorkspaceIdOrderByStartedAtDesc(runId, workspaceA.getId());
        assertThat(pubRecords).anySatisfy(p -> assertThat(p.getStatus()).isEqualTo(OciPublishAttemptStatus.RECONCILIATION_REQUIRED));

        // Ensure no automatic second attempt was dispatched for non-idempotent publish
        mockMvc.perform(post("/api/internal/workers/{w}/claim", ociWorker1Id)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret))
                .andExpect(status().isNoContent());

        // === Case 2: Cancellation of active build cleans handoffs and blocks credential delivery ===
        UUID cancelRunId = queuePipelineRun(defId, "idem-oci-cancel-1");
        JsonNode srcClaim = claimStage(srcWorkerId, srcWorkerSecret);
        UUID srcAttempt = UUID.fromString(srcClaim.get("attemptId").asText());
        String srcLease = srcClaim.get("leaseToken").asText();
        long srcFence = srcClaim.get("fencingGeneration").asLong();
        ackRunning(srcWorkerId, srcWorkerSecret, srcAttempt, srcLease, srcFence);
        issueSourceArchive(srcWorkerId, srcWorkerSecret, srcAttempt, srcLease, srcFence);
        byte[] zip = buildMinimalZip("Dockerfile", "FROM scratch\n");
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-handoff", srcWorkerId, srcAttempt)
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, srcLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, srcFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, sha256Hex(zip))
                        .header(InternalWorkerOciController.HEADER_FILE_COUNT, 1)
                        .header(InternalWorkerOciController.HEADER_EXTRACTED_BYTES, 13)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(zip))
                .andExpect(status().isOk());
        completeAttempt(srcWorkerId, srcWorkerSecret, srcAttempt, srcLease, srcFence, "SUCCEEDED", "NONE", "ok");

        // Cancel before build completes -> immediately terminal CANCELLED and handoff cleaned
        mockMvc.perform(post("/api/workspaces/" + workspaceA.getId() + "/pipelines/runs/" + cancelRunId + "/cancel")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Operator cancelled OCI build\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(handoffRepository.findAllByPipelineRunIdAndStatusIn(
                cancelRunId, List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED)
        )).isEmpty();
    }

    private void advanceToPublishRunning(UUID runId) throws Exception {
        JsonNode srcClaim = claimStage(srcWorkerId, srcWorkerSecret);
        UUID srcAtt = UUID.fromString(srcClaim.get("attemptId").asText());
        String srcLease = srcClaim.get("leaseToken").asText();
        long srcFence = srcClaim.get("fencingGeneration").asLong();
        ackRunning(srcWorkerId, srcWorkerSecret, srcAtt, srcLease, srcFence);
        issueSourceArchive(srcWorkerId, srcWorkerSecret, srcAtt, srcLease, srcFence);
        byte[] zip = buildMinimalZip("Dockerfile", "FROM scratch\n");
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-handoff", srcWorkerId, srcAtt)
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, srcLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, srcFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, sha256Hex(zip))
                        .header(InternalWorkerOciController.HEADER_FILE_COUNT, 1)
                        .header(InternalWorkerOciController.HEADER_EXTRACTED_BYTES, 13)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(zip))
                .andExpect(status().isOk());
        completeAttempt(srcWorkerId, srcWorkerSecret, srcAtt, srcLease, srcFence, "SUCCEEDED", "NONE", "ok");

        JsonNode bClaim = claimStage(ociWorker1Id, ociWorker1Secret);
        UUID bAtt = UUID.fromString(bClaim.get("attemptId").asText());
        String bLease = bClaim.get("leaseToken").asText();
        long bFence = bClaim.get("fencingGeneration").asLong();
        ackRunning(ociWorker1Id, ociWorker1Secret, bAtt, bLease, bFence);
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-descriptor", ociWorker1Id, bAtt)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(bLease, bFence)))
                .andExpect(status().isOk());
        byte[] tar = "layout-bytes".getBytes(StandardCharsets.UTF_8);
        MvcResult lr = mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-layout-upload", ociWorker1Id, bAtt)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .header(InternalWorkerOciController.HEADER_LEASE_TOKEN, bLease)
                        .header(InternalWorkerOciController.HEADER_FENCING_GENERATION, bFence)
                        .header(InternalWorkerOciController.HEADER_CONTENT_SHA256, sha256Hex(tar))
                        .header(InternalWorkerOciController.HEADER_IMAGE_SIZE_BYTES, tar.length)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(tar))
                .andExpect(status().isOk())
                .andReturn();
        String hid = objectMapper.readTree(lr.getResponse().getContentAsString()).get("handoffId").asText();
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/build-result", ociWorker1Id, bAtt)
                        .header("X-CloudForge-Worker-Id", ociWorker1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker1Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"leaseToken":"%s","fencingGeneration":%d,"status":"SUCCEEDED",
                                 "builtManifestDigest":"%s","imageSizeBytes":1024,"ociHandoffId":"%s"}
                                """.formatted(bLease, bFence, VALID_DIGEST, hid)))
                .andExpect(status().isOk());
        completeAttempt(ociWorker1Id, ociWorker1Secret, bAtt, bLease, bFence, "SUCCEEDED", "NONE", "ok");

        JsonNode pClaim = claimStage(ociWorker2Id, ociWorker2Secret);
        UUID pAtt = UUID.fromString(pClaim.get("attemptId").asText());
        String pLease = pClaim.get("leaseToken").asText();
        long pFence = pClaim.get("fencingGeneration").asLong();
        ackRunning(ociWorker2Id, ociWorker2Secret, pAtt, pLease, pFence);
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/oci-descriptor", ociWorker2Id, pAtt)
                        .header("X-CloudForge-Worker-Id", ociWorker2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ociWorker2Secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(pLease, pFence)))
                .andExpect(status().isOk());
    }

    private UUID createValidLocalRegistry() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/workspaces/" + workspaceA.getId() + "/registries")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLocalRegistryPayload("Local OCI Registry", "127.0.0.1:5000", "http", "LOCAL_DEV", "cloudforge", true)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private String createLocalRegistryPayload(String name, String host, String scheme, String type, String ns, boolean allowLocal) {
        return """
                {
                  "name": "%s",
                  "registryHost": "%s",
                  "scheme": "%s",
                  "registryType": "%s",
                  "allowedNamespace": "%s",
                  "credentialProjectId": "%s",
                  "credentialEnvironmentId": "%s",
                  "usernameSecretId": "%s",
                  "passwordSecretId": "%s",
                  "allowInsecureLocal": %s
                }
                """.formatted(name, host, scheme, type, ns, projectA.getId(), envA.getId(), regUserSecretId, regPassSecretId, allowLocal);
    }

    private UUID createFullOciPipelineDefinition(UUID registryId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/workspaces/" + workspaceA.getId() + "/pipelines/definitions")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "%s",
                                  "name": "Payments OCI Build & Publish",
                                  "stages": [
                                    {
                                      "stageKey": "source",
                                      "displayName": "Acquire Immutable Source",
                                      "stageType": "SOURCE_PREP",
                                      "metadata": {"operation": "GITHUB_SOURCE_ACQUIRE"}
                                    },
                                    {
                                      "stageKey": "build",
                                      "displayName": "Build OCI Image",
                                      "stageType": "BUILD",
                                      "dependsOn": ["source"],
                                      "maxRetries": 1,
                                      "metadata": {
                                        "operation": "OCI_IMAGE_BUILD",
                                        "dockerfilePath": "Dockerfile",
                                        "buildContextPath": ".",
                                        "registryConnectionId": "%s",
                                        "targetImageName": "cloudforge/payments",
                                        "targetTag": "v1.0.0",
                                        "targetPlatform": "linux/amd64",
                                        "buildMode": "LOCAL_TRUSTED_BUILDKIT",
                                        "buildArg.APP_VERSION": "1.0.0"
                                      }
                                    },
                                    {
                                      "stageKey": "publish",
                                      "displayName": "Publish & Verify Digest",
                                      "stageType": "CONTAINER_PACKAGE",
                                      "dependsOn": ["build"],
                                      "maxRetries": 2,
                                      "metadata": {
                                        "operation": "OCI_IMAGE_PUBLISH",
                                        "registryConnectionId": "%s",
                                        "targetImageName": "cloudforge/payments",
                                        "targetTag": "v1.0.0",
                                        "buildStageKey": "build"
                                      }
                                    }
                                  ]
                                }
                                """.formatted(projectA.getId(), registryId, registryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID queuePipelineRun(UUID defId, String idempotencyKey) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/workspaces/" + workspaceA.getId() + "/pipelines/runs")
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionId": "%s",
                                  "targetBranch": "main"
                                }
                                """.formatted(defId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commitSha").value(PINNED_SHA))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private void registerStandardWorkers() throws Exception {
        mockMvc.perform(post("/api/internal/workers/register")
                        .header("X-CloudForge-Worker-Id", srcWorkerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + srcWorkerSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "%s",
                                  "displayName": "Source Worker",
                                  "adapterType": "GITHUB_SOURCE",
                                  "supportedOperations": ["GITHUB_SOURCE_ACQUIRE"],
                                  "maxConcurrency": 1
                                }
                                """.formatted(srcWorkerId)))
                .andExpect(status().isOk());

        for (String[] pair : List.of(new String[]{ociWorker1Id, ociWorker1Secret}, new String[]{ociWorker2Id, ociWorker2Secret})) {
            mockMvc.perform(post("/api/internal/workers/register")
                            .header("X-CloudForge-Worker-Id", pair[0])
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pair[1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "workerId": "%s",
                                      "displayName": "OCI BuildKit Worker",
                                      "adapterType": "OCI_BUILDKIT",
                                      "supportedOperations": ["OCI_IMAGE_BUILD", "OCI_IMAGE_PUBLISH"],
                                      "maxConcurrency": 1
                                    }
                                    """.formatted(pair[0])))
                    .andExpect(status().isOk());
        }
    }

    private JsonNode claimStage(String workerId, String secret) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/internal/workers/{w}/claim", workerId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void ackRunning(String workerId, String secret, UUID attemptId, String leaseToken, long fence) throws Exception {
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/running", workerId, attemptId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"leaseToken":"%s","fencingGeneration":%d,"startedAt":"%s"}
                                """.formatted(leaseToken, fence, Instant.now().toString())))
                .andExpect(status().isOk());
    }

    private void issueSourceArchive(String workerId, String secret, UUID attemptId, String leaseToken, long fence) throws Exception {
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/source-archive", workerId, attemptId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                        .with(req -> { req.setSecure(true); req.setScheme("https"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseJson(leaseToken, fence)))
                .andExpect(status().isOk());
    }

    private void completeAttempt(
            String workerId, String secret, UUID attemptId, String leaseToken, long fence,
            String statusVal, String failureCategory, String summary
    ) throws Exception {
        mockMvc.perform(post("/api/internal/workers/{w}/attempts/{a}/complete", workerId, attemptId)
                        .header("X-CloudForge-Worker-Id", workerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeJson(attemptId, leaseToken, fence, statusVal, failureCategory, summary)))
                .andExpect(status().isOk());
    }

    private static String leaseJson(String lease, long fence) {
        return "{\"leaseToken\":\"" + lease + "\",\"fencingGeneration\":" + fence + "}";
    }

    private String completeJson(UUID attemptId, String lease, long fence, String statusVal, String failCat, String summary) {
        StageExecutionAttemptEntity att = attemptRepository.findById(attemptId).orElseThrow();
        return """
                {
                  "pipelineRunId": "%s",
                  "stageRunId": "%s",
                  "attemptNumber": %d,
                  "leaseToken": "%s",
                  "fencingGeneration": %d,
                  "resultStatus": "%s",
                  "failureCategory": "%s",
                  "exitCode": 0,
                  "summary": "%s",
                  "resourceUsage": {},
                  "completedAt": "%s",
                  "terminationConfirmed": true
                }
                """.formatted(
                att.getPipelineRun().getId(),
                att.getStageRun().getId(),
                att.getAttemptNumber(),
                lease, fence, statusVal, failCat, summary, Instant.now().toString()
        );
    }

    private void addMember(WorkspaceEntity ws, UserEntity user, WorkspaceRole role) {
        WorkspaceMemberEntity m = new WorkspaceMemberEntity();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(role);
        workspaceMemberRepository.saveAndFlush(m);
    }

    private void provisionWorkerIfNeeded(String workerId, String keyId, String secret) {
        workerCredentialService.provisionCredential(workerId, keyId, secret, Instant.now().plusSeconds(3600), "OCI test worker");
    }

    private static byte[] buildMinimalZip(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
