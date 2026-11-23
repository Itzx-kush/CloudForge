package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkspaceRbacAndProjectsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @Autowired
    private LinkedRepositoryRepository linkedRepositoryRepository;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private LinkedRepositoryEntity repoInA;
    private LinkedRepositoryEntity repoInB;

    @BeforeEach
    void setUp() {
        owner = userAccountService.upsertFromGitHubOAuth(60001L, "owner-a", "Owner A", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(60002L, "dev-a", "Dev A", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(60003L, "viewer-a", "Viewer A", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(60004L, "outsider-b", "Outsider B", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        addMembership(workspaceA, developer, WorkspaceRole.DEVELOPER);
        addMembership(workspaceA, viewer, WorkspaceRole.VIEWER);

        repoInA = seedRepo(workspaceA, 80001L, 90001L, "owner-a/service-a");
        repoInB = seedRepo(workspaceB, 80002L, 90002L, "outsider-b/secret-service");
    }

    @Test
    void enforcesRoleMatrixAcrossProjectCrudEnvironmentsAuditAndCrossWorkspaceAccess() throws Exception {
        // 1. VIEWER cannot create a project
        mockMvc.perform(post("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(viewer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "viewer-blocked",
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isForbidden());

        // 2. DEVELOPER can create a project linked to authorized workspace repo (and seeds 3 environments)
        MvcResult createResult = mockMvc.perform(post("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Payments API",
                                "description", "Core payments microservice",
                                "linkedRepositoryId", repoInA.getId(),
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.slug").value("payments-api"))
                .andExpect(jsonPath("$.linkedRepository.fullName").value("owner-a/service-a"))
                .andExpect(jsonPath("$.environments.length()").value(3))
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID projectId = UUID.fromString(created.path("id").asText());
        UUID devEnvId = findEnvId(created, "DEVELOPMENT");
        UUID prodEnvId = findEnvId(created, "PRODUCTION");

        // 3. Cross-workspace repository linking is blocked (linking Workspace B repo in Workspace A)
        mockMvc.perform(post("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Cross Repo Attempt",
                                "linkedRepositoryId", repoInB.getId()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.linkedRepositoryId").exists());

        // 4. VIEWER can read projects and detail
        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 5. OUTSIDER from Workspace B cannot read or mutate Workspace A projects, members, or audit logs
        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/projects/{p}", workspaceA.getId(), projectId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());

        // 6. DEVELOPER can update DEVELOPMENT environment with non-secret variables
        mockMvc.perform(put("/api/workspaces/{ws}/projects/{p}/environments/{e}",
                        workspaceA.getId(), projectId, devEnvId)
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Development",
                                "branchOverride", "develop",
                                "configVariables", Map.of("LOG_LEVEL", "DEBUG", "APP_REGION", "us-east-1"),
                                "notes", "Local sandbox config"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchOverride").value("develop"))
                .andExpect(jsonPath("$.configVariables.LOG_LEVEL").value("DEBUG"));

        // 7. Plaintext secrets are actively rejected in environment configuration
        mockMvc.perform(put("/api/workspaces/{ws}/projects/{p}/environments/{e}",
                        workspaceA.getId(), projectId, devEnvId)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Development",
                                "configVariables", Map.of("DB_PASSWORD", "super-secret-value")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['configVariables.DB_PASSWORD']").exists());

        // 8. DEVELOPER cannot modify PRODUCTION environment; OWNER can
        mockMvc.perform(put("/api/workspaces/{ws}/projects/{p}/environments/{e}",
                        workspaceA.getId(), projectId, prodEnvId)
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Production",
                                "configVariables", Map.of("LOG_LEVEL", "WARN")
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/workspaces/{ws}/projects/{p}/environments/{e}",
                        workspaceA.getId(), projectId, prodEnvId)
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Production",
                                "configVariables", Map.of("LOG_LEVEL", "WARN")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVariables.LOG_LEVEL").value("WARN"));

        // 9. Audit logs restricted strictly to OWNER
        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(developer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        // 10. DEVELOPER cannot delete a project; OWNER can
        mockMvc.perform(delete("/api/workspaces/{ws}/projects/{p}", workspaceA.getId(), projectId)
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/workspaces/{ws}/projects/{p}", workspaceA.getId(), projectId)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/workspaces/{ws}/projects/{p}", workspaceA.getId(), projectId)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void protectsAgainstDemotingOrRemovingLastWorkspaceOwner() throws Exception {
        WorkspaceMemberEntity ownerMember = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceA.getId(), owner.getId())
                .orElseThrow();

        mockMvc.perform(patch("/api/workspaces/{ws}/members/{m}", workspaceA.getId(), ownerMember.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "DEVELOPER"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/workspaces/{ws}/members/{m}", workspaceA.getId(), ownerMember.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        // DEVELOPER cannot add members
        mockMvc.perform(post("/api/workspaces/{ws}/members", workspaceA.getId())
                        .with(authenticatedUser(developer))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "outsider-b", "role", "VIEWER"))))
                .andExpect(status().isForbidden());

        // OWNER can add outsider-b, then remove them
        MvcResult added = mockMvc.perform(post("/api/workspaces/{ws}/members", workspaceA.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "outsider-b", "role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("outsider-b"))
                .andReturn();

        String newMemberId = objectMapper.readTree(added.getResponse().getContentAsString()).path("id").asText();
        assertThat(newMemberId).isNotBlank();

        mockMvc.perform(delete("/api/workspaces/{ws}/members/{m}", workspaceA.getId(), newMemberId)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private void addMembership(WorkspaceEntity ws, UserEntity user, WorkspaceRole role) {
        WorkspaceMemberEntity m = new WorkspaceMemberEntity();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(role);
        workspaceMemberRepository.save(m);
    }

    private LinkedRepositoryEntity seedRepo(WorkspaceEntity ws, long ghInstallId, long ghRepoId, String fullName) {
        GitHubInstallationEntity inst = new GitHubInstallationEntity();
        inst.setWorkspace(ws);
        inst.setGithubInstallationId(ghInstallId);
        inst.setAccountLogin(fullName.split("/")[0]);
        inst.setAccountType("User");
        inst.setStatus(InstallationStatus.ACTIVE);
        inst = installationRepository.save(inst);

        LinkedRepositoryEntity repo = new LinkedRepositoryEntity();
        repo.setWorkspace(ws);
        repo.setInstallation(inst);
        repo.setGithubRepoId(ghRepoId);
        repo.setOwnerLogin(fullName.split("/")[0]);
        repo.setName(fullName.split("/")[1]);
        repo.setFullName(fullName);
        repo.setDefaultBranch("main");
        repo.setVisibility("private");
        repo.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
        repo.setLastSyncedAt(Instant.now());
        return linkedRepositoryRepository.save(repo);
    }

    private UUID findEnvId(JsonNode projectJson, String type) {
        for (JsonNode env : projectJson.path("environments")) {
            if (type.equals(env.path("environmentType").asText())) {
                return UUID.fromString(env.path("id").asText());
            }
        }
        throw new AssertionError("Missing environment type " + type);
    }
}
