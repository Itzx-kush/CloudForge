package com.cloudforge.backend.github;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RestClientGitHubApiClient implements GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientGitHubApiClient.class);
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 10;

    private final GitHubAppTokenService tokenService;
    private final RestClient restClient;

    public RestClientGitHubApiClient(AppProperties appProperties, GitHubAppTokenService tokenService) {
        this.tokenService = tokenService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(appProperties.getGithubApp().getApiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, GITHUB_ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, "CloudForge-Platform/0.2")
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
                    account.path("avatar_url").asText(null),
                    body.path("repository_selection").asText("selected"),
                    suspendedAt != null,
                    suspendedAt
            );
        } catch (RestClientResponseException ex) {
            throw translateGitHubHttpError(ex, githubInstallationId);
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
                this::requestInstallationAccessToken
        );

        List<GitHubRepositoryMetadata> collected = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            GitHubRepositoryPage repoPage = fetchRepositoryPage(githubInstallationId, installationToken, page);
            collected.addAll(repoPage.repositories());
            if (repoPage.repositories().size() < PER_PAGE || collected.size() >= repoPage.totalCount()) {
                break;
            }
        }
        return collected;
    }

    private GitHubInstallationToken requestInstallationAccessToken(long githubInstallationId) {
        String appJwt = tokenService.generateAppJwt();
        // Enforce least-privilege scope strictly sufficient for repository discovery in Phase 2.
        Map<String, Object> requestBody = Map.of("permissions", Map.of("metadata", "read"));
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
            throw translateGitHubHttpError(ex, githubInstallationId);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while issuing installation token for installationId={}", githubInstallationId);
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
                            .queryParam("per_page", PER_PAGE)
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
                            repo.path("html_url").asText(null),
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
            throw translateGitHubHttpError(ex, githubInstallationId);
        } catch (RestClientException ex) {
            log.warn("GitHub API network error while listing repositories for installationId={}", githubInstallationId);
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch repositories from GitHub API"
            );
        }
    }

    private RuntimeException translateGitHubHttpError(RestClientResponseException ex, long githubInstallationId) {
        int code = ex.getStatusCode().value();
        HttpHeaders headers = ex.getResponseHeaders();
        String remaining = headers != null ? headers.getFirst("X-RateLimit-Remaining") : null;
        String responseBody = ex.getResponseBodyAsString();

        if ("0".equals(remaining) || code == 429) {
            log.warn("GitHub API rate limit exceeded for installationId={}", githubInstallationId);
            return new ExternalIntegrationException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GitHub API rate limit exceeded. Please wait before synchronizing again."
            );
        }
        if (code == 404) {
            tokenService.evictInstallationToken(githubInstallationId);
            return new GitHubInstallationRevokedException("GitHub App installation was removed or revoked on GitHub");
        }
        if (code == 403 && responseBody != null && responseBody.toLowerCase().contains("suspend")) {
            tokenService.evictInstallationToken(githubInstallationId);
            return new GitHubInstallationSuspendedException("GitHub App installation is currently suspended");
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

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
