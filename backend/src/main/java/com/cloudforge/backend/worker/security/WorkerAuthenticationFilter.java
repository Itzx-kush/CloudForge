package com.cloudforge.backend.worker.security;

import com.cloudforge.backend.config.RequestCorrelationFilter;
import com.cloudforge.backend.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dedicated stateless authentication filter for {@code /api/internal/workers/**}.
 *
 * <p>Fails closed when the worker plane is disabled or unconfigured, never accepts
 * ordinary workspace user cookies, and strictly authenticates per-worker Bearer credentials
 * bound to {@code X-CloudForge-Worker-Id}.</p>
 */
public class WorkerAuthenticationFilter extends OncePerRequestFilter {

    public static final String WORKER_ID_HEADER = "X-CloudForge-Worker-Id";
    private static final String BEARER_PREFIX = "Bearer ";

    private final WorkerCredentialService credentialService;
    private final ObjectMapper objectMapper;

    public WorkerAuthenticationFilter(
            WorkerCredentialService credentialService,
            ObjectMapper objectMapper
    ) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!credentialService.isWorkerPlaneEnabled()) {
            writeError(
                    response,
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Worker execution plane is disabled on this CloudForge instance"
            );
            return;
        }

        if (!credentialService.hasAnyConfiguredCredentials()) {
            writeError(
                    response,
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Worker authentication is not configured; internal worker endpoints fail closed"
            );
            return;
        }

        String workerId = request.getHeader(WORKER_ID_HEADER);
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(workerId)
                || !StringUtils.hasText(authHeader)
                || !authHeader.startsWith(BEARER_PREFIX)) {
            writeError(
                    response,
                    request,
                    HttpStatus.UNAUTHORIZED,
                    "Trusted worker credentials (X-CloudForge-Worker-Id and Bearer token) are required"
            );
            return;
        }

        String rawToken = authHeader.substring(BEARER_PREFIX.length()).trim();
        Optional<WorkerPrincipal> authenticated = credentialService.authenticate(workerId, rawToken);
        if (authenticated.isEmpty()) {
            writeError(
                    response,
                    request,
                    HttpStatus.UNAUTHORIZED,
                    "Invalid, expired, or revoked worker credential"
            );
            return;
        }

        WorkerPrincipal principal = authenticated.get();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TRUSTED_WORKER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse payload = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY),
                Map.of()
        );
        objectMapper.writeValue(response.getWriter(), payload);
    }
}
