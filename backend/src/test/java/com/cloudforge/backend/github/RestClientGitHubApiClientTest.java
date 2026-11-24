package com.cloudforge.backend.github;

import com.cloudforge.backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientGitHubApiClientTest {

    private AppProperties appProperties;
    private GitHubAppTokenService tokenService;
    private MockRestServiceServer mockServer;
    private RestClientGitHubApiClient apiClient;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        appProperties = new AppProperties();
        appProperties.getGithubApp().setAppId("123456");
        appProperties.getGithubApp().setSlug("cloudforge-test");
        appProperties.getGithubApp().setPrivateKey(pem);
        appProperties.getGithubApp().setApiBaseUrl("https://api.github.test");
        appProperties.getGithubApp().setActionsSyncPerPage(2);
        appProperties.getGithubApp().setActionsSyncMaxWorkflowPages(5);
        appProperties.getGithubApp().setActionsSyncMaxRunsPerRepo(10);

        tokenService = new GitHubAppTokenService(appProperties);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(appProperties.getGithubApp().getApiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        apiClient = new RestClientGitHubApiClient(appProperties, tokenService, builder.build());
    }

    @Test
    void requestsOnlyReadOnlyPermissionsAndPaginatesWorkflowsRunsAndJobs() {
        long installId = 7001L;

        // 1. Installation token for Actions MUST request only metadata:read and actions:read (no write permissions)
        mockServer.expect(requestTo("https://api.github.test/app/installations/7001/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.permissions.metadata").value("read"))
                .andExpect(jsonPath("$.permissions.actions").value("read"))
                .andExpect(jsonPath("$.permissions.contents").doesNotExist())
                .andExpect(jsonPath("$.permissions.workflows").doesNotExist())
                .andExpect(jsonPath("$.permissions.administration").doesNotExist())
                .andRespond(withSuccess("""
                        {"token":"ghs_actions_read_only_token","expires_at":"2099-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        // 2. Paginated workflows (page 1 has 2 items of total 3; page 2 has 1 item + unsafe URL test)
        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/workflows?per_page=2&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghs_actions_read_only_token"))
                .andRespond(withSuccess("""
                        {
                          "total_count": 3,
                          "workflows": [
                            {
                              "id": 101,
                              "name": "CI",
                              "path": ".github/workflows/ci.yml",
                              "state": "active",
                              "html_url": "https://github.com/acme/api/blob/main/.github/workflows/ci.yml",
                              "created_at": "2026-01-01T00:00:00Z",
                              "updated_at": "2026-11-01T00:00:00Z"
                            },
                            {
                              "id": 102,
                              "name": "Security Scan",
                              "path": ".github/workflows/security.yml",
                              "state": "active",
                              "html_url": "https://github.com/acme/api/blob/main/.github/workflows/security.yml"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/workflows?per_page=2&page=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "total_count": 3,
                          "workflows": [
                            {
                              "id": 103,
                              "name": "Nightly",
                              "path": ".github/workflows/nightly.yml",
                              "state": "disabled_manually",
                              "html_url": "javascript:alert(1)"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<GitHubWorkflowMetadata> workflows = apiClient.listRepositoryWorkflows(installId, "acme", "api");
        assertThat(workflows).hasSize(3);
        assertThat(workflows.get(0).name()).isEqualTo("CI");
        assertThat(workflows.get(0).htmlUrl()).isEqualTo("https://github.com/acme/api/blob/main/.github/workflows/ci.yml");
        assertThat(workflows.get(2).state()).isEqualTo("disabled_manually");
        // Unsafe javascript: URL must be sanitized to null
        assertThat(workflows.get(2).htmlUrl()).isNull();
        mockServer.verify();
        mockServer.reset();

        // 3. Paginated runs bounded by maxRuns=3 (cached token reused — no second access_tokens call!)
        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/runs?per_page=2&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "total_count": 5,
                          "workflow_runs": [
                            {
                              "id": 5001,
                              "workflow_id": 101,
                              "name": "CI",
                              "display_title": "Add Phase 3.1",
                              "run_number": 42,
                              "run_attempt": 2,
                              "event": "pull_request",
                              "head_branch": "feat/phase3",
                              "head_sha": "abcdef1234567890",
                              "status": "completed",
                              "conclusion": "success",
                              "html_url": "https://github.com/acme/api/actions/runs/5001",
                              "created_at": "2026-11-24T01:00:00Z",
                              "updated_at": "2026-11-24T01:03:00Z",
                              "run_started_at": "2026-11-24T01:00:10Z",
                              "actor": { "login": "kush-dev", "avatar_url": "https://avatars.githubusercontent.com/u/1" }
                            },
                            {
                              "id": 5002,
                              "workflow_id": 101,
                              "name": "CI",
                              "run_number": 41,
                              "run_attempt": 1,
                              "event": "push",
                              "head_branch": "main",
                              "head_sha": "1111222233334444",
                              "status": "in_progress",
                              "conclusion": null,
                              "html_url": "https://github.com/acme/api/actions/runs/5002",
                              "created_at": "2026-11-24T00:50:00Z"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/runs?per_page=2&page=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "total_count": 5,
                          "workflow_runs": [
                            {
                              "id": 5003,
                              "workflow_id": 102,
                              "name": "Security Scan",
                              "run_number": 9,
                              "run_attempt": 1,
                              "event": "schedule",
                              "head_branch": "main",
                              "head_sha": "9999888877776666",
                              "status": "completed",
                              "conclusion": "failure"
                            },
                            {
                              "id": 5004,
                              "workflow_id": 102,
                              "name": "Security Scan",
                              "run_number": 8,
                              "run_attempt": 1,
                              "event": "schedule",
                              "status": "completed",
                              "conclusion": "timed_out"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<GitHubWorkflowRunMetadata> runs = apiClient.listRepositoryWorkflowRuns(installId, "acme", "api", 3);
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0).runAttempt()).isEqualTo(2);
        assertThat(runs.get(0).actorLogin()).isEqualTo("kush-dev");
        assertThat(runs.get(1).conclusion()).isNull();
        assertThat(runs.get(2).githubRunId()).isEqualTo(5003L);
        mockServer.verify();
        mockServer.reset();

        // 4. Jobs preserve job order and sort steps by step number
        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/runs/5001/jobs?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "total_count": 2,
                          "jobs": [
                            {
                              "id": 901,
                              "run_id": 5001,
                              "run_attempt": 2,
                              "name": "Backend Test & Package",
                              "status": "completed",
                              "conclusion": "success",
                              "started_at": "2026-11-24T01:00:12Z",
                              "completed_at": "2026-11-24T01:02:12Z",
                              "html_url": "https://github.com/acme/api/actions/runs/5001/job/901",
                              "runner_name": "GitHub Actions 4",
                              "steps": [
                                {"number": 2, "name": "Run Maven tests", "status": "completed", "conclusion": "success"},
                                {"number": 1, "name": "Checkout", "status": "completed", "conclusion": "success"}
                              ]
                            },
                            {
                              "id": 902,
                              "run_id": 5001,
                              "run_attempt": 2,
                              "name": "Frontend Build",
                              "status": "completed",
                              "conclusion": "failure",
                              "steps": []
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<GitHubWorkflowJobMetadata> jobs = apiClient.listWorkflowRunJobs(installId, "acme", "api", 5001L);
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).name()).isEqualTo("Backend Test & Package");
        assertThat(jobs.get(0).steps()).extracting(GitHubWorkflowStepMetadata::name)
                .containsExactly("Checkout", "Run Maven tests");
        assertThat(jobs.get(1).name()).isEqualTo("Frontend Build");

        mockServer.verify();
    }

    @Test
    void translatesMissingActionsPermissionWhilePreservingMetadataOnlyRepositorySync() {
        long installId = 7002L;

        // Actions token fails with 422 when installation has not approved Actions: Read
        mockServer.expect(requestTo("https://api.github.test/app/installations/7002/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.permissions.actions").value("read"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"The permissions requested are not granted to this installation.\"}"));

        assertThatThrownBy(() -> apiClient.listRepositoryWorkflows(installId, "acme", "api"))
                .isInstanceOf(GitHubActionsPermissionException.class)
                .hasMessageContaining("Actions: Read");
        mockServer.verify();
        mockServer.reset();

        // Repository metadata sync still requests ONLY metadata:read and succeeds cleanly
        mockServer.expect(requestTo("https://api.github.test/app/installations/7002/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.permissions.metadata").value("read"))
                .andExpect(jsonPath("$.permissions.actions").doesNotExist())
                .andRespond(withSuccess("""
                        {"token":"ghs_metadata_only","expires_at":"2099-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.test/installation/repositories?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"total_count": 0, "repositories": []}
                        """, MediaType.APPLICATION_JSON));

        assertThat(apiClient.listAllInstallationRepositories(installId)).isEmpty();
        mockServer.verify();
    }

    @Test
    void translatesRateLimitRevokedSuspendedAndMissingRepositoryErrors() {
        long installId = 7003L;

        // 1. Revoked installation (404 on access_tokens)
        mockServer.expect(requestTo("https://api.github.test/app/installations/7003/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> apiClient.listRepositoryWorkflows(installId, "acme", "api"))
                .isInstanceOf(GitHubInstallationRevokedException.class);
        mockServer.verify();
        mockServer.reset();

        // 2. Suspended installation (403 with "suspended")
        mockServer.expect(requestTo("https://api.github.test/app/installations/7003/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"This installation has been suspended\"}"));

        assertThatThrownBy(() -> apiClient.listRepositoryWorkflows(installId, "acme", "api"))
                .isInstanceOf(GitHubInstallationSuspendedException.class);
        mockServer.verify();
        mockServer.reset();

        // 3. Valid token, then 429 rate limit with Retry-After header
        mockServer.expect(requestTo("https://api.github.test/app/installations/7003/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"token":"ghs_valid_token","expires_at":"2099-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        HttpHeaders rateHeaders = new HttpHeaders();
        rateHeaders.set(HttpHeaders.RETRY_AFTER, "60");
        mockServer.expect(requestTo("https://api.github.test/repos/acme/api/actions/workflows?per_page=2&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(rateHeaders));

        assertThatThrownBy(() -> apiClient.listRepositoryWorkflows(installId, "acme", "api"))
                .isInstanceOf(GitHubRateLimitException.class)
                .satisfies(ex -> assertThat(((GitHubRateLimitException) ex).getRetryAfterSeconds()).isEqualTo(60L));
        mockServer.verify();
        mockServer.reset();

        // 4. Missing repository (404 on /repos/.../actions/workflows)
        mockServer.expect(requestTo("https://api.github.test/repos/acme/deleted-repo/actions/workflows?per_page=2&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> apiClient.listRepositoryWorkflows(installId, "acme", "deleted-repo"))
                .isInstanceOf(GitHubRepositoryNotFoundException.class);

        mockServer.verify();
    }
}
