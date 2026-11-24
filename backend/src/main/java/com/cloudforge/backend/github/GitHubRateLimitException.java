package com.cloudforge.backend.github;

import com.cloudforge.backend.exception.ExternalIntegrationException;
import org.springframework.http.HttpStatus;

public class GitHubRateLimitException extends ExternalIntegrationException {

    private final Long retryAfterSeconds;

    public GitHubRateLimitException(String message, Long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
