package com.cloudforge.backend.github;

public class GitHubInstallationSuspendedException extends RuntimeException {

    public GitHubInstallationSuspendedException(String message) {
        super(message);
    }
}
