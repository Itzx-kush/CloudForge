package com.cloudforge.backend.exception;

import org.springframework.http.HttpStatus;

public class ExternalIntegrationException extends ApiException {

    public ExternalIntegrationException(HttpStatus status, String message) {
        super(status, message);
    }
}
