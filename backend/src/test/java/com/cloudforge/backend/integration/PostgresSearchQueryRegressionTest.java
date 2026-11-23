package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test executing Spring Data JPA search queries and REST list endpoints directly
 * against PostgreSQL to ensure nullable search/filter parameters never trigger
 * PostgreSQL "ERROR: function lower(bytea) does not exist".
 *
 * All writes execute inside a @Transactional test method and roll back automatically,
 * preserving all existing PostgreSQL data without resetting or modifying schema history.
 */
@ExtendWith(PostgresAvailableCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostgresSearchQueryRegressionTest {

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresAvailableCondition::resolveUrl);
        registry.add("spring.datasource.username", PostgresAvailableCondition::resolveUser);
        registry.add("spring.datasource.password", PostgresAvailableCondition::resolvePassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Never mutate or reset existing database schema; validate against existing Flyway-managed schema.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> "true");
        registry.add("spring.jpa.properties.jakarta.persistence.database-product-name", () -> "PostgreSQL");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @Autowired
    private LinkedRepositoryRepository linkedRepositoryRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditService auditService;

    private UserEntity ownerA;
    private UserEntity ownerB;
    private WorkspaceEntity workspaceA;
    private WorkspaceEntity workspaceB;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
        }

        ownerA = userAccountService.upsertFromGitHubOAuth(
                981001L, "pg-test-owner-a", "PG Owner A", null, null, null
        );
        ownerB = userAccountService.upsertFromGitHubOAuth(
                981002L, "pg-test-owner-b", "PG Owner B", null, null, null
        );

        workspaceA = workspaceMemberRepository.findAllByUserIdWithWorkspace(ownerA.getId()).get(0).getWorkspace();
        workspaceB = workspaceMemberRepository.findAllByUserIdWithWorkspace(ownerB.getId()).get(0).getWorkspace();

        GitHubInstallationEntity instA = new GitHubInstallationEntity();
        instA.setWorkspace(workspaceA);
        instA.setGithubInstallationId(9981001L);
        instA.setAccountLogin("pg-org-a");
        instA.setAccountType("Organization");
        instA.setStatus(InstallationStatus.ACTIVE);
        instA = installationRepository.save(instA);

        GitHubInstallationEntity instB = new GitHubInstallationEntity();
        instB.setWorkspace(workspaceB);
        instB.setGithubInstallationId(9981002L);
        instB.setAccountLogin("pg-org-b");
        instB.setAccountType("Organization");
        instB.setStatus(InstallationStatus.ACTIVE);
        instB = installationRepository.save(instB);

        LinkedRepositoryEntity repoA1 = saveRepo(
                workspaceA, instA, 991001L, "pg-org-a", "payments-api",
                "private", "Handles 100% of checkout payments", RepositoryConnectionStatus.CONNECTED
        );
        saveRepo(
                workspaceA, instA, 991002L, "pg-org-a", "docs-site",
                "public", "Public developer portal", RepositoryConnectionStatus.DISCONNECTED
        );
        saveRepo(
                workspaceB, instB, 991003L, "pg-org-b", "isolated-other-repo",
                "private", "Other workspace repo", RepositoryConnectionStatus.CONNECTED
        );

        saveProject(workspaceA, ownerA, repoA1, "Payments Core", "payments-core",
                "Core billing 100% SLA service", ProjectStatus.ACTIVE);
        saveProject(workspaceA, ownerA, null, "Analytics Worker", "analytics-worker",
                null, ProjectStatus.PAUSED);
        saveProject(workspaceB, ownerB, null, "Workspace B Project", "ws-b-project",
                "Should not leak into workspace A", ProjectStatus.ACTIVE);

        auditService.record(workspaceA, ownerA, "PROJECT_CREATED", "PROJECT", "pg-test-1", "Seed audit", Map.of());
    }

    @Test
    void projectSearchWorksOnPostgreSqlWithNullBlankAndPopulatedFiltersAndPagination() throws Exception {
        Pageable pageDesc = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "updatedAt"));

        // Exact failure case from Bug 2: search = null, status = null
        Page<ProjectEntity> allInA = projectRepository.searchByWorkspace(workspaceA.getId(), null, null, pageDesc);
        assertThat(allInA.getTotalElements()).isEqualTo(2);
        assertThat(allInA.getContent()).extracting(ProjectEntity::getSlug)
                .containsExactlyInAnyOrder("payments-core", "analytics-worker");

        // Blank search + status filter
        Page<ProjectEntity> pausedOnly = projectRepository.searchByWorkspace(
                workspaceA.getId(), "   ", ProjectStatus.PAUSED, pageDesc
        );
        assertThat(pausedOnly.getTotalElements()).isEqualTo(1);
        assertThat(pausedOnly.getContent().get(0).getSlug()).isEqualTo("analytics-worker");

        // Case-insensitive search + LIKE wildcard escaping ("100%")
        Page<ProjectEntity> literalPercent = projectRepository.searchByWorkspace(
                workspaceA.getId(), "100%", ProjectStatus.ACTIVE, pageDesc
        );
        assertThat(literalPercent.getTotalElements()).isEqualTo(1);
        assertThat(literalPercent.getContent().get(0).getSlug()).isEqualTo("payments-core");

        // REST API check with null query params & pagination + cross-workspace authorization
        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .param("page", "0")
                        .param("size", "1")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .param("q", "PAYMENTS")
                        .param("status", "ACTIVE")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].linkedRepository.fullName").value("pg-org-a/payments-api"));

        mockMvc.perform(get("/api/workspaces/{ws}/projects", workspaceA.getId())
                        .with(authenticatedUser(ownerB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void repositorySearchWorksOnPostgreSqlWithNullVisibilitySearchAndStatusFilters() throws Exception {
        Pageable pageAsc = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "fullName"));

        // Exact failure case from Bug 2: search = null, visibility = null, status = null
        Page<LinkedRepositoryEntity> allRepos = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), null, null, null, pageAsc
        );
        assertThat(allRepos.getTotalElements()).isEqualTo(2);
        assertThat(allRepos.getContent()).extracting(LinkedRepositoryEntity::getFullName)
                .containsExactly("pg-org-a/docs-site", "pg-org-a/payments-api");

        // Visibility filter with null search & null status
        Page<LinkedRepositoryEntity> publicOnly = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), null, "PUBLIC", null, pageAsc
        );
        assertThat(publicOnly.getTotalElements()).isEqualTo(1);
        assertThat(publicOnly.getContent().get(0).getName()).isEqualTo("docs-site");

        // Search + visibility + status combined + LIKE literal %
        Page<LinkedRepositoryEntity> filtered = linkedRepositoryRepository.searchByWorkspace(
                workspaceA.getId(), "100%", "private", RepositoryConnectionStatus.CONNECTED, pageAsc
        );
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent().get(0).getName()).isEqualTo("payments-api");

        // REST endpoint checks against live PostgreSQL
        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .param("visibility", "private")
                        .param("status", "CONNECTED")
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("pg-org-a/payments-api"));

        mockMvc.perform(get("/api/workspaces/{ws}/repositories", workspaceA.getId())
                        .with(authenticatedUser(ownerB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditEventSearchWorksOnPostgreSqlWithNullAndNonNullAction() throws Exception {
        var allEvents = auditEventRepository.findByWorkspaceId(workspaceA.getId(), null, PageRequest.of(0, 10));
        assertThat(allEvents.getTotalElements()).isGreaterThanOrEqualTo(2);

        var projectEvents = auditEventRepository.findByWorkspaceId(
                workspaceA.getId(), "PROJECT_CREATED", PageRequest.of(0, 10)
        );
        assertThat(projectEvents.getTotalElements()).isEqualTo(1);

        mockMvc.perform(get("/api/workspaces/{ws}/audit-events", workspaceA.getId())
                        .with(authenticatedUser(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    private LinkedRepositoryEntity saveRepo(
            WorkspaceEntity ws,
            GitHubInstallationEntity inst,
            long ghRepoId,
            String owner,
            String name,
            String visibility,
            String description,
            RepositoryConnectionStatus status
    ) {
        LinkedRepositoryEntity repo = new LinkedRepositoryEntity();
        repo.setWorkspace(ws);
        repo.setInstallation(inst);
        repo.setGithubRepoId(ghRepoId);
        repo.setOwnerLogin(owner);
        repo.setName(name);
        repo.setFullName(owner + "/" + name);
        repo.setDefaultBranch("main");
        repo.setVisibility(visibility);
        repo.setDescription(description);
        repo.setConnectionStatus(status);
        repo.setLastSyncedAt(Instant.now());
        return linkedRepositoryRepository.save(repo);
    }

    private ProjectEntity saveProject(
            WorkspaceEntity ws,
            UserEntity creator,
            LinkedRepositoryEntity repo,
            String name,
            String slug,
            String description,
            ProjectStatus status
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(ws);
        project.setCreatedBy(creator);
        project.setLinkedRepository(repo);
        project.setName(name);
        project.setSlug(slug);
        project.setDescription(description);
        project.setDefaultBranch("main");
        project.setStatus(status);
        return projectRepository.save(project);
    }
}
