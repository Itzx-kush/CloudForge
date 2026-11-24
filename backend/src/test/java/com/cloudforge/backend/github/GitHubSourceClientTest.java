package com.cloudforge.backend.github;

import com.cloudforge.backend.exception.ExternalIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubSourceClientTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final GitHubSourceClient.Repository REPOSITORY =
            new GitHubSourceClient.Repository(900, 801, "acme", "source");
    private static final String API = "https://api.github.test";
    private static final String TOKEN = "synthetic-installation-token";

    private MockRestServiceServer server;
    private GitHubSourceClient client;

    @BeforeEach
    void setup() {
        GitHubAppTokenService tokens = mock(GitHubAppTokenService.class);
        when(tokens.generateAppJwt()).thenReturn("synthetic-app-jwt");
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubSourceClient(tokens, builder.build());
    }

    @Test
    void resolvesAQualifiedRefWithRepositorySpecificReadAccess() {
        expectToken();
        server.expect(requestTo(API + "/repositories/801/commits/refs%2Ftags%2Fv1.0"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"sha\":\"" + SHA + "\"}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveCommit(REPOSITORY, "refs/tags/v1.0")).isEqualTo(SHA);
        server.verify();
    }

    @Test
    void requestsArchiveForPinnedCommitAndLeavesRedirectForWorker() {
        expectToken();
        URI download = URI.create("https://codeload.github.com/acme/source/legacy.zip/" + SHA + "?token=fixture");
        server.expect(requestTo(API + "/repositories/801/zipball/" + SHA))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withStatus(HttpStatus.FOUND).location(download));

        assertThat(client.requestArchive(REPOSITORY, SHA)).isEqualTo(download);
        server.verify();
    }

    @Test
    void rejectsArchiveLocationForAnotherRepositoryWithoutExposingGrant() {
        expectToken();
        server.expect(requestTo(API + "/repositories/801/zipball/" + SHA))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .location(URI.create("https://codeload.github.com/acme/other/legacy.zip/" + SHA + "?token=fixture")));

        assertThatThrownBy(() -> client.requestArchive(REPOSITORY, SHA))
                .isInstanceOf(ExternalIntegrationException.class).hasMessageNotContaining("fixture");
        server.verify();
    }

    @Test
    void handlesExpiredTokenWithoutExposingUpstreamBody() {
        expectToken();
        server.expect(requestTo(API + "/repositories/801/commits/refs%2Fheads%2Fmain"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(TOKEN));

        assertThatThrownBy(() -> client.resolveCommit(REPOSITORY, "refs/heads/main"))
                .isInstanceOf(ExternalIntegrationException.class)
                .hasMessageContaining("fresh token").hasMessageNotContaining(TOKEN);
        server.verify();
    }

    @Test
    void reportsMissingContentsPermission() {
        server.expect(requestTo(API + "/app/installations/900/access_tokens"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("synthetic upstream response"));

        assertThatThrownBy(() -> client.resolveCommit(REPOSITORY, "refs/heads/main"))
                .isInstanceOf(ExternalIntegrationException.class).hasMessageContaining("Contents (Read-only)");
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo(API + "/app/installations/900/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-app-jwt"))
                .andExpect(jsonPath("$.repository_ids[0]").value(801))
                .andExpect(jsonPath("$.permissions.metadata").value("read"))
                .andExpect(jsonPath("$.permissions.contents").value("read"))
                .andExpect(jsonPath("$.permissions.actions").doesNotExist())
                .andRespond(withSuccess("{\"token\":\"" + TOKEN + "\"}", MediaType.APPLICATION_JSON));
    }
}
