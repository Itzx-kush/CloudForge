package com.cloudforge.backend.github;

import com.cloudforge.backend.exception.ExternalIntegrationException;
import org.springframework.http.HttpStatus;

public class GitHubRepositoryNotFoundException extends ExternalIntegrationException {

    public GitHubRepositoryNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
