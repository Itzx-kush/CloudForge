package com.cloudforge.backend.github;

import com.cloudforge.backend.exception.ExternalIntegrationException;
import org.springframework.http.HttpStatus;

public class GitHubActionsPermissionException extends ExternalIntegrationException {

    public GitHubActionsPermissionException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
