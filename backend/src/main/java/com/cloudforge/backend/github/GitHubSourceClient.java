package com.cloudforge.backend.github;

import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class GitHubSourceClient {

    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private final GitHubAppTokenService tokens;
    private final RestClient api;

    @Autowired
    public GitHubSourceClient(GitHubAppTokenService tokens) {
        this(tokens, sourceRestClient());
    }

    GitHubSourceClient(GitHubAppTokenService tokens, RestClient api) {
        this.tokens = tokens;
        this.api = api;
    }

    private static RestClient sourceRestClient() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "CloudForge-Platform/0.3")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public String resolveCommit(Repository repository, String qualifiedRef) {
        if (qualifiedRef == null || !qualifiedRef.matches("refs/(heads|tags)/[A-Za-z0-9._/-]{1,240}")) {
            throw new IllegalArgumentException("A qualified GitHub source ref is required");
        }
        return withRepositoryToken(repository, token -> {
            JsonNode body = api.get()
                    .uri("/repositories/{id}/commits/{ref}", repository.repositoryId(), qualifiedRef)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(JsonNode.class);
            String sha = body == null ? null : body.path("sha").asText(null);
            if (!isCommitSha(sha)) {
                throw upstream("GitHub did not return an immutable commit SHA");
            }
            return sha;
        });
    }

    public URI requestArchive(Repository repository, String commitSha) {
        if (!isCommitSha(commitSha)) {
            throw new IllegalArgumentException("A full immutable GitHub commit SHA is required");
        }
        return withRepositoryToken(repository, token -> api.get()
                .uri("/repositories/{id}/zipball/{commit}", repository.repositoryId(), commitSha)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status != 302) {
                        throw translateSourceStatus(status);
                    }
                    URI location;
                    try {
                        location = response.getHeaders().getLocation();
                    } catch (IllegalArgumentException ex) {
                        throw upstream("GitHub returned an invalid archive location");
                    }
                    String prefix = "/" + repository.owner() + "/" + repository.name() + "/";
                    if (location == null || !"https".equals(location.getScheme())
                            || !"codeload.github.com".equalsIgnoreCase(location.getHost())
                            || location.getPort() != -1 || location.getRawUserInfo() != null
                            || location.getRawFragment() != null
                            || !(location.getRawPath().equalsIgnoreCase(prefix + "legacy.zip/" + commitSha)
                            || location.getRawPath().equalsIgnoreCase(prefix + "zip/" + commitSha))
                            || location.toASCIIString().length() > 4096) {
                        throw upstream("GitHub returned an unapproved archive location");
                    }
                    return location;
                }));
    }

    private <T> T withRepositoryToken(Repository repository, java.util.function.Function<String, T> action) {
        try {
            // A fresh token avoids sharing a contents grant across repositories or retrying cached credentials.
            JsonNode tokenResponse = api.post()
                    .uri("/app/installations/{installationId}/access_tokens", repository.installationId())
                    .headers(headers -> headers.setBearerAuth(tokens.generateAppJwt()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "permissions", GitHubTokenScope.CONTENTS_READ.permissions(),
                            "repository_ids", List.of(repository.repositoryId())
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            String token = tokenResponse == null ? null : tokenResponse.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw upstream("GitHub did not issue a repository contents token");
            }
            return action.apply(token);
        } catch (RestClientResponseException ex) {
            throw translateSourceStatus(ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw upstream("Unable to communicate with GitHub for source acquisition");
        }
    }

    private static ExternalIntegrationException translateSourceStatus(int status) {
        return switch (status) {
            case 401 -> upstream("GitHub rejected the installation token. Retry to request a fresh token.");
            case 403, 422 -> upstream("GitHub denied source access. Grant Contents (Read-only) and approve the permission update on this installation.");
            case 404, 409 -> new ExternalIntegrationException(
                    HttpStatus.NOT_FOUND, "GitHub repository, installation, branch or tag is unavailable");
            case 429 -> new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE, "GitHub source requests are rate limited. Retry shortly.");
            default -> upstream("GitHub source request failed (HTTP " + status + ")");
        };
    }

    private static ExternalIntegrationException upstream(String message) {
        return new ExternalIntegrationException(HttpStatus.BAD_GATEWAY, message);
    }

    public static boolean isCommitSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    public record Repository(long installationId, long repositoryId, String owner, String name) {
        public Repository {
            if (installationId <= 0 || repositoryId <= 0 || !validPart(owner) || !validPart(name)) {
                throw new IllegalArgumentException("Invalid linked GitHub repository identity");
            }
        }

        private static boolean validPart(String value) {
            return value != null && REPOSITORY_PART.matcher(value).matches()
                    && !value.equals(".") && !value.equals("..");
        }
    }
}
