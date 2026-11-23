package com.cloudforge.backend.integration;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.cloudforge.backend.github.GitHubApiClient;
import com.cloudforge.backend.github.GitHubInstallationDetails;
import com.cloudforge.backend.github.GitHubInstallationRevokedException;
import com.cloudforge.backend.github.GitHubRepositoryMetadata;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.GitHubIntegrationService;
import com.cloudforge.backend.service.UserAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mock-based integration tests for the GitHub App installation, ownership validation,
 * repository synchronization, revocation, and rate-limit handling lifecycle.
 * External GitHub API calls are mocked via GitHubApiClient.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GitHubAppAndRepositoriesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @MockitoBean
    private GitHubApiClient gitHubApiClient;

    private UserEntity owner;
    private UserEntity developer;
    private UserEntity otherUser;
    private WorkspaceEntity ownerWorkspace;
    private String previousPrivateKey;

    @BeforeEach
    void setUp() {
        previousPrivateKey = appProperties.getGithubApp().getPrivateKey();
        appProperties.getGithubApp().setPrivateKey("mock-configured-for-integration-test");

        owner = userAccountService.upsertFromGitHubOAuth(70001L, "gh-owner", "GH Owner", null, null, null);
        developer = userAccountService.upsertFromGitHubOAuth(70002L, "gh-dev", "GH Dev", null, null, null);
        otherUser = userAccountService.upsertFromGitHubOAuth(70003L, "gh-other", "GH Other", null, null, null);

        ownerWorkspace = workspaceMemberRepository.findAllByUserIdWithWorkspace(owner.getId()).get(0).getWorkspace();

        WorkspaceMemberEntity devMember = new WorkspaceMemberEntity();
        devMember.setWorkspace(ownerWorkspace);
        devMember.setUser(developer);
        devMember.setRole(WorkspaceRole.DEVELOPER);
        workspaceMemberRepository.save(devMember);
    }

    @AfterEach
    void tearDown() {
        appProperties.getGithubApp().setPrivateKey(previousPrivateKey);
    }

    @Test
    void ownerConnectsInstallationSyncsRepositoriesAndHandlesRevocation() throws Exception {
        long ghInstallId = 55100L;
        when(gitHubApiClient.getInstallation(ghInstallId)).thenReturn(
                new GitHubInstallationDetails(
                        ghInstallId,
                        70001L,
                        "gh-owner",
                        "User",
                        "https://avatars.githubusercontent.com/u/70001",
                        "selected",
                        false,
                        null
                )
        );
        when(gitHubApiClient.listAllInstallationRepositories(ghInstallId)).thenReturn(List.of(
                new GitHubRepositoryMetadata(
                        99101L,
                        "gh-owner",
                        "cloudforge-demo",
                        "gh-owner/cloudforge-demo",
                        "main",
                        "private",
                        "https://github.com/gh-owner/cloudforge-demo",
                        "Demo microservice",
                        false,
                        Instant.parse("2026-11-20T12:00:00Z")
                )
        ));

        // DEVELOPER cannot initiate GitHub App installation
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .with(authenticatedUser(developer))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // OWNER initiates installation -> receives state-bound GitHub URL
        MockHttpSession session = new MockHttpSession();
        MvcResult installUrlResult = mockMvc.perform(
                        post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                                .session(session)
                                .with(authenticatedUser(owner))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installUrl").isString())
                .andReturn();

        String installUrl = objectMapper.readTree(installUrlResult.getResponse().getContentAsString())
                .path("installUrl").asText();
        assertThat(installUrl).startsWith("https://github.com/apps/cloudforge-test-app/installations/new?state=");
        String state = URI.create(installUrl).getQuery().replace("state=", "");

        // Callback verifies state, checks ownership, persists installation, and syncs repos
        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .param("state", state)
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation=connected"));

        // Replaying the same single-use state fails safely
        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .param("state", state)
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=bad_request"));

        // DEVELOPER can list and search authorized workspace repositories
        mockMvc.perform(get("/api/workspaces/{ws}/repositories", ownerWorkspace.getId())
                        .param("q", "cloudforge-demo")
                        .with(authenticatedUser(developer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("gh-owner/cloudforge-demo"))
                .andExpect(jsonPath("$.items[0].connectionStatus").value("CONNECTED"));

        // Simulate revocation on GitHub during subsequent sync
        var installationEntity = installationRepository.findByGithubInstallationId(ghInstallId).orElseThrow();
        when(gitHubApiClient.getInstallation(ghInstallId))
                .thenThrow(new GitHubInstallationRevokedException("Revoked on GitHub"));

        mockMvc.perform(post("/api/workspaces/{ws}/github/installations/{inst}/sync",
                        ownerWorkspace.getId(), installationEntity.getId())
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installationStatus").value("REVOKED"))
                .andExpect(jsonPath("$.disconnectedCount").value(1));

        // Repository is now marked DISCONNECTED
        mockMvc.perform(get("/api/workspaces/{ws}/repositories", ownerWorkspace.getId())
                        .param("status", "DISCONNECTED")
                        .with(authenticatedUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rejectsPersonalInstallationBelongingToDifferentGitHubUserAndHandlesRateLimits() throws Exception {
        long foreignInstallId = 55200L;
        when(gitHubApiClient.getInstallation(foreignInstallId)).thenReturn(
                new GitHubInstallationDetails(
                        foreignInstallId,
                        70003L,
                        "gh-other",
                        "User",
                        null,
                        "selected",
                        false,
                        null
                )
        );

        MockHttpSession session = new MockHttpSession();
        MvcResult installUrlResult = mockMvc.perform(
                        post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                                .session(session)
                                .with(authenticatedUser(owner))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String installUrl = objectMapper.readTree(installUrlResult.getResponse().getContentAsString())
                .path("installUrl").asText();
        String state = URI.create(installUrl).getQuery().replace("state=", "");

        // Owner trying to attach gh-other's personal GitHub App installation is rejected (forbidden)
        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", String.valueOf(foreignInstallId))
                        .param("setup_action", "install")
                        .param("state", state)
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));

        assertThat(installationRepository.findByGithubInstallationId(foreignInstallId)).isEmpty();

        // Also test rate-limit exception translation on sync
        when(gitHubApiClient.getInstallation(55300L)).thenReturn(
                new GitHubInstallationDetails(55300L, 70001L, "gh-owner", "User", null, "all", false, null)
        );
        when(gitHubApiClient.listAllInstallationRepositories(55300L))
                .thenThrow(new ExternalIntegrationException(HttpStatus.TOO_MANY_REQUESTS, "GitHub API rate limit exceeded"));

        MvcResult url2 = mockMvc.perform(
                        post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                                .session(session)
                                .with(authenticatedUser(owner))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String state2 = URI.create(objectMapper.readTree(url2.getResponse().getContentAsString())
                .path("installUrl").asText()).getQuery().replace("state=", "");

        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", "55300")
                        .param("setup_action", "install")
                        .param("state", state2)
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=too_many_requests"));
    }

    @Test
    void acceptsCallbackWithoutStateWhenValidUnexpiredPendingSessionExistsAndConsumesSingleUse() throws Exception {
        long ghInstallId = 55400L;
        when(gitHubApiClient.getInstallation(ghInstallId)).thenReturn(
                new GitHubInstallationDetails(
                        ghInstallId,
                        70001L,
                        "gh-owner",
                        "User",
                        "https://avatars.githubusercontent.com/u/70001",
                        "selected",
                        false,
                        null
                )
        );
        when(gitHubApiClient.listAllInstallationRepositories(ghInstallId)).thenReturn(List.of(
                new GitHubRepositoryMetadata(
                        99401L,
                        "gh-owner",
                        "stateless-callback-repo",
                        "gh-owner/stateless-callback-repo",
                        "main",
                        "private",
                        "https://github.com/gh-owner/stateless-callback-repo",
                        "Verified without state query param",
                        false,
                        Instant.parse("2026-11-22T12:00:00Z")
                )
        ));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .session(session)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Callback arrives WITHOUT state query parameter (GitHub App setup/update redirect behavior)
        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation=connected"));

        assertThat(installationRepository.findByGithubInstallationId(ghInstallId)).isPresent();
        assertThat(session.getAttribute(GitHubIntegrationService.PENDING_INSTALL_STATE_ATTR)).isNull();

        // Single-use enforcement: second callback without state on the same session is rejected
        mockMvc.perform(get("/api/github/app/callback")
                        .session(session)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=bad_request"));
    }

    @Test
    void rejectsCallbackWithMissingSessionNoPendingRequestExpiredRequestInvalidStateOrUnauthorizedUser() throws Exception {
        long ghInstallId = 55500L;
        when(gitHubApiClient.getInstallation(ghInstallId)).thenReturn(
                new GitHubInstallationDetails(
                        ghInstallId,
                        70001L,
                        "gh-owner",
                        "User",
                        null,
                        "selected",
                        false,
                        null
                )
        );
        when(gitHubApiClient.listAllInstallationRepositories(ghInstallId)).thenReturn(List.of());

        // 1. Unauthenticated callback -> 401 Unauthorized
        mockMvc.perform(get("/api/github/app/callback")
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install"))
                .andExpect(status().isUnauthorized());

        // 2. Missing HTTP session (no session passed -> request.getSession(false) == null)
        mockMvc.perform(get("/api/github/app/callback")
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=bad_request"));

        // 3. Active HTTP session WITHOUT a pending installation request (arbitrary callback)
        MockHttpSession emptySession = new MockHttpSession();
        mockMvc.perform(get("/api/github/app/callback")
                        .session(emptySession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=bad_request"));

        // 4. Expired pending installation request (without state)
        MockHttpSession expiredSession = new MockHttpSession();
        expiredSession.setAttribute(
                GitHubIntegrationService.PENDING_INSTALL_STATE_ATTR,
                new GitHubIntegrationService.PendingInstallationState(
                        "expired-nonce",
                        ownerWorkspace.getId(),
                        owner.getId(),
                        Instant.now().minusSeconds(5)
                )
        );
        mockMvc.perform(get("/api/github/app/callback")
                        .session(expiredSession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=bad_request"));
        assertThat(expiredSession.getAttribute(GitHubIntegrationService.PENDING_INSTALL_STATE_ATTR)).isNull();

        // 5. Invalid supplied state value is still rejected (and consumes the pending state)
        MockHttpSession invalidStateSession = new MockHttpSession();
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .session(invalidStateSession)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/github/app/callback")
                        .session(invalidStateSession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .param("state", "tampered-wrong-state")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));
        assertThat(invalidStateSession.getAttribute(GitHubIntegrationService.PENDING_INSTALL_STATE_ATTR)).isNull();

        // 6. Blank supplied state (?state=) is treated as invalid supplied state
        MockHttpSession blankStateSession = new MockHttpSession();
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .session(blankStateSession)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/github/app/callback")
                        .session(blankStateSession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .param("state", "   ")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));

        // 7. Different authenticated user presenting owner's pending session (without state) -> forbidden
        MockHttpSession otherUserSession = new MockHttpSession();
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .session(otherUserSession)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/github/app/callback")
                        .session(otherUserSession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(developer)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));

        // 8. Unauthorized workspace role (pending state for DEVELOPER who is not OWNER) -> forbidden
        MockHttpSession nonOwnerRoleSession = new MockHttpSession();
        nonOwnerRoleSession.setAttribute(
                GitHubIntegrationService.PENDING_INSTALL_STATE_ATTR,
                new GitHubIntegrationService.PendingInstallationState(
                        "dev-nonce",
                        ownerWorkspace.getId(),
                        developer.getId(),
                        Instant.now().plusSeconds(300)
                )
        );
        mockMvc.perform(get("/api/github/app/callback")
                        .session(nonOwnerRoleSession)
                        .param("installation_id", String.valueOf(ghInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(developer)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));

        // 9. Personal installation ownership is still enforced when state is omitted
        long foreignInstallId = 55501L;
        when(gitHubApiClient.getInstallation(foreignInstallId)).thenReturn(
                new GitHubInstallationDetails(foreignInstallId, 70003L, "gh-other", "User", null, "selected", false, null)
        );
        MockHttpSession ownershipSession = new MockHttpSession();
        mockMvc.perform(post("/api/workspaces/{ws}/github/install-url", ownerWorkspace.getId())
                        .session(ownershipSession)
                        .with(authenticatedUser(owner))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/github/app/callback")
                        .session(ownershipSession)
                        .param("installation_id", String.valueOf(foreignInstallId))
                        .param("setup_action", "install")
                        .with(authenticatedUser(owner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/repositories?installation_error=forbidden"));

        assertThat(installationRepository.findByGithubInstallationId(ghInstallId)).isEmpty();
        assertThat(installationRepository.findByGithubInstallationId(foreignInstallId)).isEmpty();
    }
}
