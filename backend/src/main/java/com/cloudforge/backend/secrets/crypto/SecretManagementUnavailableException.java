package com.cloudforge.backend.secrets.crypto;

import com.cloudforge.backend.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Fail-closed HTTP 503 exception thrown when encrypted secret management or
 * the configured key provider is disabled, misconfigured, or unavailable.
 */
public class SecretManagementUnavailableException extends ApiException {

    public SecretManagementUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
