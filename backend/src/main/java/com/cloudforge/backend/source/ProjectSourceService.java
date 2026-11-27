package com.cloudforge.backend.source;

import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.github.GitHubSourceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectSourceService {

    private final GitHubSourceClient github;
    private final boolean enabled;
    private final Set<String> repositories;
    private final Set<String> workers;

    public ProjectSourceService(
            GitHubSourceClient github,
            @Value("${cloudforge.source.enabled:${CLOUDFORGE_SOURCE_ACQUISITION_ENABLED:false}}") boolean enabled,
            @Value("${CLOUDFORGE_SOURCE_ALLOWED_REPOSITORY_IDS:}") String repositories,
            @Value("${CLOUDFORGE_SOURCE_ALLOWED_WORKER_IDS:}") String workers
    ) {
        this.github = github;
        this.enabled = enabled;
        this.repositories = parseAllowlist(repositories, "[1-9][0-9]{0,18}");
        this.workers = parseAllowlist(workers, "[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}");
    }

    public boolean permitsWorker(String workerId) {
        return enabled && !repositories.isEmpty() && workers.contains(workerId);
    }

    public String resolveCommit(UUID workspaceId, LinkedRepositoryEntity linked, String ref) {
        return github.resolveCommit(requireSourceRepository(workspaceId, linked), qualifyRef(ref));
    }

    public URI requestArchive(UUID workspaceId, LinkedRepositoryEntity linked, String sha) {
        return github.requestArchive(requireSourceRepository(workspaceId, linked), sha);
    }

    private GitHubSourceClient.Repository requireSourceRepository(UUID workspaceId, LinkedRepositoryEntity linked) {
        if (!enabled || repositories.isEmpty() || workers.isEmpty()) {
            throw new ExternalIntegrationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub source acquisition is disabled or lacks operator configuration");
        }
        if (linked == null || linked.getWorkspace() == null || !workspaceId.equals(linked.getWorkspace().getId())
                || linked.getInstallation() == null || linked.getInstallation().getWorkspace() == null
                || !workspaceId.equals(linked.getInstallation().getWorkspace().getId())
                || linked.getConnectionStatus() != RepositoryConnectionStatus.CONNECTED
                || linked.getInstallation().getStatus() != InstallationStatus.ACTIVE
                || linked.isArchived() || !repositories.contains(String.valueOf(linked.getGithubRepoId()))) {
            throw new ForbiddenOperationException("The linked repository is unavailable or unapproved for source acquisition");
        }
        return new GitHubSourceClient.Repository(linked.getInstallation().getGithubInstallationId(),
                linked.getGithubRepoId(), linked.getOwnerLogin(), linked.getName());
    }

    static String qualifyRef(String input) {
        if (input == null || input.isBlank() || input.length() > 240) {
            throw new BadRequestException("Enter a GitHub branch or a tag prefixed with refs/tags/");
        }
        String ref = input.startsWith("refs/") ? input : "refs/heads/" + input;
        if (!ref.matches("refs/(heads|tags)/[A-Za-z0-9._/-]+")
                || ref.contains("..") || ref.endsWith(".") || ref.endsWith("/")
                || Arrays.stream(ref.split("/", -1)).anyMatch(part -> part.isEmpty()
                || part.startsWith(".") || part.toLowerCase(Locale.ROOT).endsWith(".lock"))) {
            throw new BadRequestException("Invalid GitHub branch or tag name");
        }
        return ref;
    }

    private static Set<String> parseAllowlist(String text, String pattern) {
        if (text.isBlank()) {
            return Set.of();
        }
        Set<String> items = Arrays.stream(text.split(",", -1)).map(String::trim).collect(Collectors.toUnmodifiableSet());
        if (items.stream().anyMatch(item -> !item.matches(pattern))) {
            throw new IllegalArgumentException("Invalid GitHub source acquisition allowlist");
        }
        return items;
    }
}
