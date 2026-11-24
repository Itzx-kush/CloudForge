package com.cloudforge.backend.worker.security;

import java.security.Principal;

/**
 * Authenticated trusted worker identity placed into the stateless Spring Security context
 * for {@code /api/internal/workers/**} requests only. Distinct from {@code CloudForgeUserPrincipal}.
 */
public record WorkerPrincipal(String workerId, String keyId) implements Principal {
    @Override
    public String getName() {
        return workerId;
    }
}
