package com.cloudforge.backend.integration;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.cloudforge.backend.repository.UserRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static com.cloudforge.backend.support.TestAuthSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthAndSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void authConfigIsPublicAndNeverExposesSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubOAuthConfigured").value(true))
                .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/github"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("client-secret");
        assertThat(body).doesNotContain("test-github-oauth-client-secret");
        assertThat(body).doesNotContain("test-webhook-secret");
    }

    @Test
    void unauthenticatedRequestsToProtectedEndpointsAreRejectedWithJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/auth/me"));

        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void firstAndSubsequentGitHubOAuthLoginPersistsUserOnceAndProvisionsPersonalWorkspace() {
        UserEntity first = userAccountService.upsertFromGitHubOAuth(
                50001L,
                "alice-dev",
                "Alice Developer",
                "alice@example.com",
                "https://avatars.githubusercontent.com/u/50001",
                "https://github.com/alice-dev"
        );

        assertThat(first.getId()).isNotNull();
        assertThat(workspaceMemberRepository.findAllByUserIdWithWorkspace(first.getId())).hasSize(1);

        UserEntity second = userAccountService.upsertFromGitHubOAuth(
                50001L,
                "alice-dev",
                "Alice Updated",
                "alice@example.com",
                "https://avatars.githubusercontent.com/u/50001",
                "https://github.com/alice-dev"
        );

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getDisplayName()).isEqualTo("Alice Updated");
        assertThat(userRepository.findByGithubUserId(50001L)).isPresent();
        // Should not create duplicate personal workspaces on subsequent logins
        assertThat(workspaceMemberRepository.findAllByUserIdWithWorkspace(first.getId())).hasSize(1);

        var workspaceId = workspaceMemberRepository.findAllByUserIdWithWorkspace(first.getId())
                .get(0).getWorkspace().getId();
        var events = auditEventRepository.findByWorkspaceId(workspaceId, null, PageRequest.of(0, 10));
        assertThat(events.getContent())
                .extracting(e -> e.getAction())
                .contains("USER_REGISTERED", "USER_LOGIN");
    }

    @Test
    void authenticatedMeReturnsProfileWorkspacesAndNeverExposesTokens() throws Exception {
        UserEntity user = userAccountService.upsertFromGitHubOAuth(
                50002L, "bob-owner", "Bob Owner", "bob@example.com", null, "https://github.com/bob-owner"
        );

        MvcResult result = mockMvc.perform(get("/api/auth/me").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.user.username").value("bob-owner"))
                .andExpect(jsonPath("$.user.githubUserId").value(50002))
                .andExpect(jsonPath("$.workspaces.length()").value(1))
                .andExpect(jsonPath("$.workspaces[0].role").value("OWNER"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("accessToken");
        assertThat(body).doesNotContain("clientSecret");
    }

    @Test
    void csrfIsEnforcedOnLogoutAndSessionIsInvalidatedUponValidLogout() throws Exception {
        UserEntity user = userAccountService.upsertFromGitHubOAuth(
                50003L, "carol-sec", "Carol Security", "carol@example.com", null, null
        );

        // Missing CSRF token -> rejected 403
        mockMvc.perform(post("/api/auth/logout").with(authenticatedUser(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/auth/logout")
                        .session(session)
                        .with(authenticatedUser(user))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedOut").value(true))
                .andExpect(header().exists("Set-Cookie"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void authenticatedUnknownEndpointReturnsStructured404() throws Exception {
        UserEntity user = userAccountService.upsertFromGitHubOAuth(
                50004L, "dave-check", "Dave Check", null, null, null
        );

        mockMvc.perform(get("/api/does-not-exist").with(authenticatedUser(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/does-not-exist"));
    }
}
