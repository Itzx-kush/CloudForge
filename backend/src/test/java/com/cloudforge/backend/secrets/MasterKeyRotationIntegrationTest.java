package com.cloudforge.backend.secrets;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.EnvKeyringSecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretCryptoService;
import com.cloudforge.backend.secrets.dto.CreateEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretSummaryDto;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.KeyRotationRunStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.secrets.repository.SecretKeyRotationRunRepository;
import com.cloudforge.backend.secrets.service.SecretKeyRotationService;
import com.cloudforge.backend.secrets.service.SecretManagementService;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MasterKeyRotationIntegrationTest {

    private static final String KEY_V1 = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";
    private static final String KEY_V2 = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=";
    private static final String OP_TOKEN = "operator-test-bearer-token-34-chars";

    @Autowired
    private MockMvc mockMvc;
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
    private SecretManagementService secretManagementService;
    @Autowired
    private SecretKeyRotationService rotationService;
    @Autowired
    private EnvironmentSecretVersionRepository versionRepository;
    @Autowired
    private SecretKeyRotationRunRepository rotationRunRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private SecretCryptoService cryptoService;
    @Autowired
    private SecretManagementProperties secretProperties;
    @Autowired
    private EnvKeyringSecretKeyProvider keyProvider;

    private UserEntity owner;
    private WorkspaceEntity workspace;
    private ProjectEntity project;
    private ProjectEnvironmentEntity env;

    @BeforeEach
    void setup() {
        secretProperties.setEnabled(true);
        secretProperties.setActiveKeyId("test-kek-v1");
        secretProperties.setKeyring("test-kek-v1:" + KEY_V1 + ",test-kek-v2:" + KEY_V2);
        secretProperties.setOperatorRotationEnabled(true);
        secretProperties.setOperatorRotationToken(OP_TOKEN);
        keyProvider.reloadFromProperties();

        long uniqueGithubId = 95000L + (System.nanoTime() % 10000);
        owner = userAccountService.upsertFromGitHubOAuth(uniqueGithubId, "kek-rot-" + uniqueGithubId, "KEK Owner", null, null, null);
        workspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setCreatedBy(owner);
        project.setName("Rotation Project");
        project.setSlug("rotation-project-" + uniqueGithubId);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.saveAndFlush(project);

        env = new ProjectEnvironmentEntity();
        env.setProject(project);
        env.setName("Production");
        env.setEnvironmentType(EnvironmentType.PRODUCTION);
        env.setConfigVariablesJson("{}");
        env = environmentRepository.saveAndFlush(env);
    }

    @AfterEach
    void teardown() {
        if (workspace != null) {
            auditEventRepository.deleteAll();
            rotationRunRepository.deleteAll();
            workspaceRepository.deleteById(workspace.getId());
        }
        secretProperties.setEnabled(true);
        secretProperties.setActiveKeyId("test-kek-v1");
        secretProperties.setKeyring("test-kek-v1:" + KEY_V1 + ",test-kek-v2:" + KEY_V2);
        secretProperties.setOperatorRotationEnabled(true);
        secretProperties.setOperatorRotationToken(OP_TOKEN);
        keyProvider.reloadFromProperties();
    }

    @Test
    void operatorSecurityBoundaryAndMultiBatchDekRewrappingWithoutDecryptingSecrets() throws Exception {
        // Browser OAuth user rejected from operator endpoint
        mockMvc.perform(post("/api/internal/operator/secrets/keys/rotate")
                        .with(authenticatedUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        // Wrong Bearer token rejected
        mockMvc.perform(get("/api/internal/operator/secrets/keys/status")
                        .header("Authorization", "Bearer wrong-token-value-1234567890"))
                .andExpect(status().isUnauthorized());

        // Create secrets under test-kek-v1
        EnvironmentSecretSummaryDto sec1 = secretManagementService.createSecret(
                workspace.getId(), project.getId(), env.getId(),
                new CreateEnvironmentSecretRequest("SECRET_ALPHA", "alpha_value_111", null),
                owner
        );
        EnvironmentSecretSummaryDto sec2 = secretManagementService.createSecret(
                workspace.getId(), project.getId(), env.getId(),
                new CreateEnvironmentSecretRequest("SECRET_BETA", "beta_value_222", null),
                owner
        );

        EnvironmentSecretVersionEntity v1Before = versionRepository.findById(sec1.currentVersionId()).orElseThrow();
        byte[] ciphertextBefore = v1Before.getCiphertext().clone();
        byte[] nonceBefore = v1Before.getNonce().clone();
        assertThat(v1Before.getKekId()).isEqualTo("test-kek-v1");

        // Switch active KEK to test-kek-v2 while retaining test-kek-v1 in keyring
        secretProperties.setActiveKeyId("test-kek-v2");
        keyProvider.reloadFromProperties();

        mockMvc.perform(get("/api/internal/operator/secrets/keys/status")
                        .header("Authorization", "Bearer " + OP_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeKeyId").value("test-kek-v2"))
                .andExpect(jsonPath("$.versionsNeedingRotation").value(2));

        // Dry run does not mutate kekId
        mockMvc.perform(post("/api/internal/operator/secrets/keys/rotate")
                        .header("Authorization", "Bearer " + OP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchSize\":1,\"dryRun\":true,\"initiatedBy\":\"dry-check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totalCandidates").value(2))
                .andExpect(jsonPath("$.rewrappedCount").value(0));

        // Execute multi-batch DEK rewrap (batchSize=1 across 2 records)
        mockMvc.perform(post("/api/internal/operator/secrets/keys/rotate")
                        .header("Authorization", "Bearer " + OP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchSize\":1,\"dryRun\":false,\"initiatedBy\":\"sec-ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.rewrappedCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(0));

        // Ciphertext & nonce unchanged; only kekId and wrappedDek updated
        EnvironmentSecretVersionEntity v1After = versionRepository.findById(sec1.currentVersionId()).orElseThrow();
        assertThat(v1After.getKekId()).isEqualTo("test-kek-v2");
        assertThat(v1After.getCiphertext()).isEqualTo(ciphertextBefore);
        assertThat(v1After.getNonce()).isEqualTo(nonceBefore);

        // Retiring test-kek-v1 still decrypts both secrets cleanly under test-kek-v2
        secretProperties.setKeyring("test-kek-v2:" + KEY_V2);
        keyProvider.reloadFromProperties();

        byte[] plain1 = cryptoService.decrypt(
                new SecretCryptoService.EncryptedSecretPayload(
                        v1After.getCiphertext(), v1After.getNonce(), v1After.getWrappedDek(),
                        v1After.getKekId(), v1After.getEncryptionAlgorithm(), v1After.getEncryptionFormatVersion()
                ),
                new SecretCryptoService.SecretContext(workspace.getId(), project.getId(), env.getId(), sec1.id(), 1)
        );
        assertThat(new String(plain1, StandardCharsets.UTF_8)).isEqualTo("alpha_value_111");

        EnvironmentSecretVersionEntity v2After = versionRepository.findById(sec2.currentVersionId()).orElseThrow();
        byte[] plain2 = cryptoService.decrypt(
                new SecretCryptoService.EncryptedSecretPayload(
                        v2After.getCiphertext(), v2After.getNonce(), v2After.getWrappedDek(),
                        v2After.getKekId(), v2After.getEncryptionAlgorithm(), v2After.getEncryptionFormatVersion()
                ),
                new SecretCryptoService.SecretContext(workspace.getId(), project.getId(), env.getId(), sec2.id(), 1)
        );
        assertThat(new String(plain2, StandardCharsets.UTF_8)).isEqualTo("beta_value_222");

        // Second rotation run is idempotent with 0 candidates
        var idempotentRun = rotationService.rotateMasterKey(10, false, "sec-ops-2");
        assertThat(idempotentRun.status()).isEqualTo(KeyRotationRunStatus.SUCCEEDED);
        assertThat(idempotentRun.rewrappedCount()).isZero();
    }
}
