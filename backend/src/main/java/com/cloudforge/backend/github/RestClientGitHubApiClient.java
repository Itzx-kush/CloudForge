package com.cloudforge.backend.github;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RestClientGitHubApiClient implements GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientGitHubApiClient.class);
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final int REPO_PER_PAGE = 100;
    private static final int REPO_MAX_PAGES = 10;
    private static final int JOB_PER_PAGE = 100;

    private final AppProperties appProperties;
    private final GitHubAppTokenService tokenService;
    private final RestClient restClient;

    @Autowired
    public RestClientGitHubApiClient(AppProperties appProperties, GitHubAppTokenService tokenService) {
        this(appProperties, tokenService, buildDefaultRestClient(appProperties));
    }

    RestClientGitHubApiClient(
            AppProperties appProperties,
            GitHubAppTokenService tokenService,
            RestClient restClient
    ) {
        this.appProperties = appProperties;
        this.tokenService = tokenService;
        this.restClient = restClient;
    }

    private static RestClient buildDefaultRestClient(AppProperties appProperties) {
        AppProperties.GitHubApp config = appProperties.getGithubApp();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(config.getApiConnectTimeoutSeconds()).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(config.getApiReadTimeoutSeconds()).toMillis());

        return RestClient.builder()
                .baseUrl(config.getApiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, GITHUB_ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, "CloudForge-Platform/0.3")
                .build();
    }

    @Override
    public GitHubInstallationDetails getInstallation(long githubInstallationId) {
        String appJwt = tokenService.generateAppJwt();
        try {
            JsonNode body = restClient.get()
                    .uri("/app/installations/{id}", githubInstallationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.hasNonNull("id")) {
                throw new ExternalIntegrationException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an incomplete installation payload"
                );
            }

            JsonNode account = body.path("account");
            Instant suspendedAt = parseInstant(body.path("suspended_at").asText(null));
            return new GitHubInstallationDetails(
                    body.path("id").asLong(),
                    account.path("id").asLong(0L),
                    account.path("login").asText("unknown"),
                    account.path("type").asText("User"),
                    sanitizeHttpsUrl(account.path("avatar_url").asText(null), 512),
                    body.path("repository_selection").asText("selected"),
                    suspendedAt != null,
                    suspendedAt
            );
        } catch (RestClientResponseException ex) {
            throw translateInstallationHttpError(ex, githubInstallationId, GitHubTokenScope.REPOSITORY_METADATA);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while inspecting installationId={}", githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to communicate with GitHub API. Please retry shortly."
            );
        }
    }

    @Override
    public List<GitHubRepositoryMetadata> listAllInstallationRepositories(long githubInstallationId) {
        String installationToken = tokenService.getOrFetchInstallationToken(
                githubInstallationId,
                GitHubTokenScope.REPOSITORY_METADATA,
                id -> requestInstallationAccessToken(id, GitHubTokenScope.REPOSITORY_METADATA)
        );

        List<GitHubRepositoryMetadata> collected = new ArrayList<>();
        for (int page = 1; page <= REPO_MAX_PAGES; page++) {
            GitHubRepositoryPage repoPage = fetchRepositoryPage(githubInstallationId, installationToken, page);
            collected.addAll(repoPage.repositories());
            if (repoPage.repositories().size() < REPO_PER_PAGE || collected.size() >= repoPage.totalCount()) {
                break;
            }
        }
        return collected;
    }

    @Override
    public List<GitHubWorkflowMetadata> listRepositoryWorkflows(
            long githubInstallationId,
            String owner,
            String repo
    ) {
        validateRepoCoordinates(owner, repo);
        String installationToken = acquireActionsReadToken(githubInstallationId);

        int perPage = Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncPerPage()), 100);
        int maxPages = Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncMaxWorkflowPages()), 20);

        List<GitHubWorkflowMetadata> collected = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            WorkflowPage workflowPage = fetchWorkflowPage(
                    githubInstallationId,
                    installationToken,
                    owner,
                    repo,
                    perPage,
                    page
            );
            collected.addAll(workflowPage.workflows());
            if (workflowPage.workflows().size() < perPage || collected.size() >= workflowPage.totalCount()) {
                break;
            }
        }
        return List.copyOf(collected);
    }

    @Override
    public List<GitHubWorkflowRunMetadata> listRepositoryWorkflowRuns(
            long githubInstallationId,
            String owner,
            String repo,
            int maxRuns
    ) {
        validateRepoCoordinates(owner, repo);
        int effectiveMaxRuns = clampMaxRuns(maxRuns);
        int perPage = Math.min(effectiveMaxRuns, Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncPerPage()), 100));
        int maxPages = (int) Math.ceil((double) effectiveMaxRuns / perPage);

        String installationToken = acquireActionsReadToken(githubInstallationId);
        List<GitHubWorkflowRunMetadata> collected = new ArrayList<>();

        for (int page = 1; page <= maxPages && collected.size() < effectiveMaxRuns; page++) {
            WorkflowRunPage runPage = fetchWorkflowRunsPage(
                    githubInstallationId,
                    installationToken,
                    owner,
                    repo,
                    null,
                    perPage,
                    page
            );
            for (GitHubWorkflowRunMetadata run : runPage.runs()) {
                collected.add(run);
                if (collected.size() >= effectiveMaxRuns) {
                    break;
                }
            }
            if (runPage.runs().size() < perPage || collected.size() >= runPage.totalCount()) {
                break;
            }
        }
        return List.copyOf(collected);
    }

    @Override
    public List<GitHubWorkflowRunMetadata> listWorkflowRunsForWorkflow(
            long githubInstallationId,
            String owner,
            String repo,
            long githubWorkflowId,
            int maxRuns
    ) {
        validateRepoCoordinates(owner, repo);
        int effectiveMaxRuns = clampMaxRuns(maxRuns);
        int perPage = Math.min(effectiveMaxRuns, Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncPerPage()), 100));
        int maxPages = (int) Math.ceil((double) effectiveMaxRuns / perPage);

        String installationToken = acquireActionsReadToken(githubInstallationId);
        List<GitHubWorkflowRunMetadata> collected = new ArrayList<>();

        for (int page = 1; page <= maxPages && collected.size() < effectiveMaxRuns; page++) {
            WorkflowRunPage runPage = fetchWorkflowRunsPage(
                    githubInstallationId,
                    installationToken,
                    owner,
                    repo,
                    githubWorkflowId,
                    perPage,
                    page
            );
            for (GitHubWorkflowRunMetadata run : runPage.runs()) {
                collected.add(run);
                if (collected.size() >= effectiveMaxRuns) {
                    break;
                }
            }
            if (runPage.runs().size() < perPage || collected.size() >= runPage.totalCount()) {
                break;
            }
        }
        return List.copyOf(collected);
    }

    @Override
    public List<GitHubWorkflowJobMetadata> listWorkflowRunJobs(
            long githubInstallationId,
            String owner,
            String repo,
            long githubRunId
    ) {
        validateRepoCoordinates(owner, repo);
        String installationToken = acquireActionsReadToken(githubInstallationId);

        int maxPages = Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncMaxJobPages()), 10);
        List<GitHubWorkflowJobMetadata> collected = new ArrayList<>();

        for (int page = 1; page <= maxPages; page++) {
            WorkflowJobPage jobPage = fetchWorkflowJobsPage(
                    githubInstallationId,
                    installationToken,
                    owner,
                    repo,
                    githubRunId,
                    JOB_PER_PAGE,
                    page
            );
            collected.addAll(jobPage.jobs());
            if (jobPage.jobs().size() < JOB_PER_PAGE || collected.size() >= jobPage.totalCount()) {
                break;
            }
        }
        return List.copyOf(collected);
    }

    private String acquireActionsReadToken(long githubInstallationId) {
        return tokenService.getOrFetchInstallationToken(
                githubInstallationId,
                GitHubTokenScope.ACTIONS_READ,
                id -> requestInstallationAccessToken(id, GitHubTokenScope.ACTIONS_READ)
        );
    }

    private GitHubInstallationToken requestInstallationAccessToken(
            long githubInstallationId,
            GitHubTokenScope scope
    ) {
        String appJwt = tokenService.generateAppJwt();
        // Strictly request only the minimum read-only permissions required for this scope.
        Map<String, Object> requestBody = Map.of("permissions", scope.permissions());
        try {
            JsonNode body = restClient.post()
                    .uri("/app/installations/{id}/access_tokens", githubInstallationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !StringUtils.hasText(body.path("token").asText(null))) {
                throw new ExternalIntegrationException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub did not return an installation access token"
                );
            }
            return new GitHubInstallationToken(
                    body.path("token").asText(),
                    parseInstant(body.path("expires_at").asText(null))
            );
        } catch (RestClientResponseException ex) {
            throw translateInstallationHttpError(ex, githubInstallationId, scope);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while issuing {} installation token for installationId={}",
                    scope, githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to obtain short-lived GitHub installation token"
            );
        }
    }

    private GitHubRepositoryPage fetchRepositoryPage(long githubInstallationId, String installationToken, int page) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/installation/repositories")
                            .queryParam("per_page", REPO_PER_PAGE)
                            .queryParam("page", page)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                return new GitHubRepositoryPage(0, List.of());
            }

            int totalCount = body.path("total_count").asInt(0);
            List<GitHubRepositoryMetadata> repos = new ArrayList<>();
            JsonNode items = body.path("repositories");
            if (items.isArray()) {
                for (JsonNode repo : items) {
                    boolean isPrivate = repo.path("private").asBoolean(true);
                    String visibility = repo.path("visibility").asText(isPrivate ? "private" : "public");
                    repos.add(new GitHubRepositoryMetadata(
                            repo.path("id").asLong(),
                            repo.path("owner").path("login").asText("unknown"),
                            repo.path("name").asText(),
                            repo.path("full_name").asText(),
                            repo.path("default_branch").asText("main"),
                            visibility,
                            sanitizeHttpsUrl(repo.path("html_url").asText(null), 512),
                            truncate(repo.path("description").asText(null), 500),
                            repo.path("archived").asBoolean(false),
                            parseInstant(repo.path("updated_at").asText(null))
                    ));
                }
            }
            return new GitHubRepositoryPage(totalCount, repos);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                tokenService.evictInstallationToken(githubInstallationId);
            }
            throw translateInstallationHttpError(ex, githubInstallationId, GitHubTokenScope.REPOSITORY_METADATA);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while listing repositories for installationId={}", githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch repositories from GitHub API"
            );
        }
    }

    private WorkflowPage fetchWorkflowPage(
            long githubInstallationId,
            String installationToken,
            String owner,
            String repo,
            int perPage,
            int page
    ) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/actions/workflows")
                            .queryParam("per_page", perPage)
                            .queryParam("page", page)
                            .build(owner, repo))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                return new WorkflowPage(0, List.of());
            }

            int totalCount = body.path("total_count").asInt(0);
            List<GitHubWorkflowMetadata> workflows = new ArrayList<>();
            JsonNode items = body.path("workflows");
            if (items.isArray()) {
                for (JsonNode wf : items) {
                    long id = wf.path("id").asLong(0L);
                    if (id <= 0L) {
                        continue;
                    }
                    String name = fallbackText(wf.path("name").asText(null), "Workflow #" + id);
                    String path = fallbackText(wf.path("path").asText(null), ".github/workflows/unknown.yml");
                    String state = normalizeLowerOrDefault(wf.path("state").asText(null), "unknown");
                    workflows.add(new GitHubWorkflowMetadata(
                            id,
                            truncate(name, 255),
                            truncate(path, 512),
                            truncate(state, 64),
                            sanitizeHttpsUrl(wf.path("html_url").asText(null), 512),
                            sanitizeHttpsUrl(wf.path("badge_url").asText(null), 512),
                            parseInstant(wf.path("created_at").asText(null)),
                            parseInstant(wf.path("updated_at").asText(null))
                    ));
                }
            }
            return new WorkflowPage(totalCount, workflows);
        } catch (RestClientResponseException ex) {
            throw translateActionsHttpError(ex, githubInstallationId, owner, repo);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while listing workflows for {}/{} (installationId={})",
                    owner, repo, githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch GitHub Actions workflows from GitHub API"
            );
        }
    }

    private WorkflowRunPage fetchWorkflowRunsPage(
            long githubInstallationId,
            String installationToken,
            String owner,
            String repo,
            Long githubWorkflowId,
            int perPage,
            int page
    ) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> {
                        if (githubWorkflowId != null) {
                            return uriBuilder
                                    .path("/repos/{owner}/{repo}/actions/workflows/{workflowId}/runs")
                                    .queryParam("per_page", perPage)
                                    .queryParam("page", page)
                                    .build(owner, repo, githubWorkflowId);
                        }
                        return uriBuilder
                                .path("/repos/{owner}/{repo}/actions/runs")
                                .queryParam("per_page", perPage)
                                .queryParam("page", page)
                                .build(owner, repo);
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                return new WorkflowRunPage(0, List.of());
            }

            int totalCount = body.path("total_count").asInt(0);
            List<GitHubWorkflowRunMetadata> runs = new ArrayList<>();
            JsonNode items = body.path("workflow_runs");
            if (items.isArray()) {
                for (JsonNode run : items) {
                    long runId = run.path("id").asLong(0L);
                    if (runId <= 0L) {
                        continue;
                    }
                    long wfId = run.path("workflow_id").asLong(githubWorkflowId != null ? githubWorkflowId : 0L);
                    int runNumber = Math.max(0, run.path("run_number").asInt(0));
                    int runAttempt = Math.max(1, run.path("run_attempt").asInt(1));
                    String workflowName = fallbackText(run.path("name").asText(null), "Workflow #" + wfId);
                    String displayTitle = trimToNull(run.path("display_title").asText(null));
                    String event = normalizeLowerOrDefault(run.path("event").asText(null), "unknown");
                    String headBranch = trimToNull(run.path("head_branch").asText(null));
                    String headSha = trimToNull(run.path("head_sha").asText(null));
                    String status = normalizeLowerOrDefault(run.path("status").asText(null), "unknown");
                    String conclusion = normalizeLowerOrNull(run.path("conclusion").asText(null));

                    JsonNode actor = run.path("actor");
                    if (actor.isMissingNode() || actor.isNull()) {
                        actor = run.path("triggering_actor");
                    }
                    String actorLogin = trimToNull(actor.path("login").asText(null));
                    String actorAvatarUrl = sanitizeHttpsUrl(actor.path("avatar_url").asText(null), 512);

                    runs.add(new GitHubWorkflowRunMetadata(
                            runId,
                            wfId,
                            runNumber,
                            runAttempt,
                            truncate(workflowName, 255),
                            truncate(displayTitle, 500),
                            truncate(event, 64),
                            truncate(headBranch, 255),
                            truncate(headSha, 64),
                            truncate(status, 64),
                            truncate(conclusion, 64),
                            truncate(actorLogin, 150),
                            actorAvatarUrl,
                            sanitizeHttpsUrl(run.path("html_url").asText(null), 512),
                            parseInstant(run.path("created_at").asText(null)),
                            parseInstant(run.path("updated_at").asText(null)),
                            parseInstant(run.path("run_started_at").asText(null))
                    ));
                }
            }
            return new WorkflowRunPage(totalCount, runs);
        } catch (RestClientResponseException ex) {
            throw translateActionsHttpError(ex, githubInstallationId, owner, repo);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while listing workflow runs for {}/{} (installationId={})",
                    owner, repo, githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch GitHub Actions workflow runs from GitHub API"
            );
        }
    }

    private WorkflowJobPage fetchWorkflowJobsPage(
            long githubInstallationId,
            String installationToken,
            String owner,
            String repo,
            long githubRunId,
            int perPage,
            int page
    ) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/actions/runs/{runId}/jobs")
                            .queryParam("per_page", perPage)
                            .queryParam("page", page)
                            .build(owner, repo, githubRunId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                return new WorkflowJobPage(0, List.of());
            }

            int totalCount = body.path("total_count").asInt(0);
            List<GitHubWorkflowJobMetadata> jobs = new ArrayList<>();
            JsonNode items = body.path("jobs");
            if (items.isArray()) {
                for (JsonNode job : items) {
                    long jobId = job.path("id").asLong(0L);
                    if (jobId <= 0L) {
                        continue;
                    }
                    List<GitHubWorkflowStepMetadata> steps = new ArrayList<>();
                    JsonNode stepItems = job.path("steps");
                    if (stepItems.isArray()) {
                        int fallbackNumber = 1;
                        for (JsonNode step : stepItems) {
                            int stepNumber = step.path("number").asInt(fallbackNumber++);
                            steps.add(new GitHubWorkflowStepMetadata(
                                    stepNumber,
                                    truncate(fallbackText(step.path("name").asText(null), "Step " + stepNumber), 255),
                                    truncate(normalizeLowerOrDefault(step.path("status").asText(null), "unknown"), 64),
                                    truncate(normalizeLowerOrNull(step.path("conclusion").asText(null)), 64),
                                    parseInstant(step.path("started_at").asText(null)),
                                    parseInstant(step.path("completed_at").asText(null))
                            ));
                        }
                        steps.sort(Comparator.comparingInt(GitHubWorkflowStepMetadata::number));
                    }

                    jobs.add(new GitHubWorkflowJobMetadata(
                            jobId,
                            job.path("run_id").asLong(githubRunId),
                            Math.max(1, job.path("run_attempt").asInt(1)),
                            truncate(fallbackText(job.path("name").asText(null), "Job #" + jobId), 255),
                            truncate(normalizeLowerOrDefault(job.path("status").asText(null), "unknown"), 64),
                            truncate(normalizeLowerOrNull(job.path("conclusion").asText(null)), 64),
                            parseInstant(job.path("started_at").asText(null)),
                            parseInstant(job.path("completed_at").asText(null)),
                            sanitizeHttpsUrl(job.path("html_url").asText(null), 512),
                            truncate(trimToNull(job.path("runner_name").asText(null)), 150),
                            List.copyOf(steps)
                    ));
                }
            }
            return new WorkflowJobPage(totalCount, jobs);
        } catch (RestClientResponseException ex) {
            throw translateActionsHttpError(ex, githubInstallationId, owner, repo);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while listing jobs for {}/{} runId={} (installationId={})",
                    owner, repo, githubRunId, githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch GitHub Actions jobs from GitHub API"
            );
        }
    }

    private RuntimeException translateInstallationHttpError(
            RestClientResponseException ex,
            long githubInstallationId,
            GitHubTokenScope scope
    ) {
        int code = ex.getStatusCode().value();
        HttpHeaders headers = ex.getResponseHeaders();
        String responseBody = ex.getResponseBodyAsString();

        if (isRateLimitError(code, headers, responseBody)) {
            return buildRateLimitException(headers, githubInstallationId);
        }
        if (code == 404) {
            tokenService.evictInstallationToken(githubInstallationId);
            return new GitHubInstallationRevokedException("GitHub App installation was removed or revoked on GitHub");
        }
        if (code == 403 && isSuspendedResponse(responseBody)) {
            tokenService.evictInstallationToken(githubInstallationId);
            return new GitHubInstallationSuspendedException("GitHub App installation is currently suspended");
        }
        if (scope == GitHubTokenScope.ACTIONS_READ && (code == 422 || code == 403)) {
            tokenService.evictInstallationToken(githubInstallationId, GitHubTokenScope.ACTIONS_READ);
            log.warn("GitHub App installationId={} lacks Actions: Read permission (HTTP {})", githubInstallationId, code);
            return new GitHubActionsPermissionException(
                    "GitHub App installation does not have 'Actions: Read' permission. "
                            + "Update the GitHub App repository permissions to grant Actions (Read-only) and approve the permission update on this installation."
            );
        }
        if (code == 401 || code == 403) {
            tokenService.evictInstallationToken(githubInstallationId);
            return new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub App authorization was rejected by GitHub (HTTP " + code + "). Check App credentials and installation status."
            );
        }

        log.warn("GitHub API returned HTTP {} for installationId={}", code, githubInstallationId);
        return new ExternalIntegrationException(
                HttpStatus.BAD_GATEWAY,
                "GitHub API request failed with HTTP " + code
        );
    }

    private RuntimeException translateActionsHttpError(
            RestClientResponseException ex,
            long githubInstallationId,
            String owner,
            String repo
    ) {
        int code = ex.getStatusCode().value();
        HttpHeaders headers = ex.getResponseHeaders();
        String responseBody = ex.getResponseBodyAsString();

        if (isRateLimitError(code, headers, responseBody)) {
            return buildRateLimitException(headers, githubInstallationId);
        }
        if (code == 401) {
            tokenService.evictInstallationToken(githubInstallationId, GitHubTokenScope.ACTIONS_READ);
            return new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub installation token was rejected (HTTP 401). Retry synchronization to request a fresh token."
            );
        }
        if (code == 403) {
            if (isSuspendedResponse(responseBody)) {
                tokenService.evictInstallationToken(githubInstallationId);
                return new GitHubInstallationSuspendedException("GitHub App installation is currently suspended");
            }
            tokenService.evictInstallationToken(githubInstallationId, GitHubTokenScope.ACTIONS_READ);
            log.warn("GitHub Actions read access denied for {}/{} (installationId={})", owner, repo, githubInstallationId);
            return new GitHubActionsPermissionException(
                    "GitHub App installation lacks 'Actions: Read' access for " + owner + "/" + repo
                            + ". Grant Actions (Read-only) in the GitHub App settings and approve the update on this installation."
            );
        }
        if (code == 404) {
            log.warn("GitHub repository or Actions resource not found for {}/{} (installationId={})",
                    owner, repo, githubInstallationId);
            return new GitHubRepositoryNotFoundException(
                    "Repository " + owner + "/" + repo + " was not found on GitHub or is no longer accessible to this GitHub App installation."
            );
        }

        log.warn("GitHub Actions API returned HTTP {} for {}/{} (installationId={})",
                code, owner, repo, githubInstallationId);
        return new ExternalIntegrationException(
                HttpStatus.BAD_GATEWAY,
                "GitHub Actions API request failed with HTTP " + code
        );
    }

    private boolean isRateLimitError(int code, HttpHeaders headers, String responseBody) {
        String remaining = headers != null ? headers.getFirst("X-RateLimit-Remaining") : null;
        if (code == 429 || "0".equals(remaining)) {
            return true;
        }
        if (code == 403 && responseBody != null) {
            String lower = responseBody.toLowerCase(Locale.ROOT);
            return lower.contains("rate limit") || lower.contains("secondary rate");
        }
        return false;
    }

    private boolean isSuspendedResponse(String responseBody) {
        return responseBody != null && responseBody.toLowerCase(Locale.ROOT).contains("suspend");
    }

    private GitHubRateLimitException buildRateLimitException(HttpHeaders headers, long githubInstallationId) {
        Long retryAfter = extractRetryAfterSeconds(headers);
        log.warn("GitHub API rate limit exceeded for installationId={} retryAfterSeconds={}",
                githubInstallationId, retryAfter);
        String message = retryAfter != null && retryAfter > 0
                ? "GitHub API rate limit exceeded. Retry after approximately " + retryAfter + " seconds."
                : "GitHub API rate limit exceeded. Please wait before synchronizing again.";
        return new GitHubRateLimitException(message, retryAfter);
    }

    private Long extractRetryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String retryAfterHeader = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (StringUtils.hasText(retryAfterHeader)) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                if (seconds > 0) {
                    return Math.min(seconds, 3600L);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to X-RateLimit-Reset
            }
        }
        String resetHeader = headers.getFirst("X-RateLimit-Reset");
        if (StringUtils.hasText(resetHeader)) {
            try {
                long resetEpoch = Long.parseLong(resetHeader.trim());
                long waitSeconds = resetEpoch - Instant.now().getEpochSecond();
                if (waitSeconds > 0) {
                    return Math.min(waitSeconds, 3600L);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void validateRepoCoordinates(String owner, String repo) {
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)
                || owner.contains("/") || repo.contains("/")
                || owner.contains("..") || repo.contains("..")) {
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid repository owner or name coordinates"
            );
        }
    }

    private int clampMaxRuns(int requested) {
        int configuredMax = Math.min(Math.max(1, appProperties.getGithubApp().getActionsSyncMaxRunsPerRepo()), 200);
        if (requested <= 0) {
            return configuredMax;
        }
        return Math.min(requested, configuredMax);
    }

    private Instant parseInstant(String iso) {
        if (!StringUtils.hasText(iso)) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sanitizeHttpsUrl(String rawUrl, int maxLen) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > maxLen) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String fallbackText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeLowerOrDefault(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLowerOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private record WorkflowPage(int totalCount, List<GitHubWorkflowMetadata> workflows) {
    }

    private record WorkflowRunPage(int totalCount, List<GitHubWorkflowRunMetadata> runs) {
    }

    private record WorkflowJobPage(int totalCount, List<GitHubWorkflowJobMetadata> jobs) {
    }
}
