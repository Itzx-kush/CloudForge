package com.cloudforge.backend.github;

public class GitHubInstallationRevokedException extends RuntimeException {

    public GitHubInstallationRevokedException(String message) {
        super(message);
    }
}
