package com.cloudforge.backend.github;

import java.util.List;

public interface GitHubApiClient {

    GitHubInstallationDetails getInstallation(long githubInstallationId);

    List<GitHubRepositoryMetadata> listAllInstallationRepositories(long githubInstallationId);
}
