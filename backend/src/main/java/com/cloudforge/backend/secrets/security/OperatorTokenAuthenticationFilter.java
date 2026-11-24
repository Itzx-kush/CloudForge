package com.cloudforge.backend.secrets.security;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.config.RequestCorrelationFilter;
import com.cloudforge.backend.dto.ApiErrorResponse;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stateless Bearer token filter guarding operator-only master key rotation endpoints
 * ({@code /api/internal/operator/secrets/**}). Fails closed unless operator rotation
 * is explicitly enabled and configured with a non-placeholder secret token.
 */
public class OperatorTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretManagementProperties secretManagementProperties;
    private final ObjectMapper objectMapper;

    public OperatorTokenAuthenticationFilter(
            SecretManagementProperties secretManagementProperties,
            ObjectMapper objectMapper
    ) {
        this.secretManagementProperties = secretManagementProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!secretManagementProperties.isOperatorRotationEnabled()) {
            writeUnauthorized(response, request.getRequestURI(), "Operator key rotation endpoint is disabled");
            return;
        }

        String configuredToken = secretManagementProperties.getOperatorRotationToken();
        if (!AppProperties.isNonPlaceholder(configuredToken) || configuredToken.trim().length() < 16) {
            writeUnauthorized(response, request.getRequestURI(), "Operator key rotation token is not configured");
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, request.getRequestURI(), "Missing Bearer operator credential");
            return;
        }

        String presented = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(presented) || !constantTimeSha256Match(configuredToken.trim(), presented)) {
            writeUnauthorized(response, request.getRequestURI(), "Invalid operator credential");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "secret-operator",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SECRET_OPERATOR"))
        );
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean constantTimeSha256Match(String expected, String presented) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] expectedHash = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] presentedHash = digest.digest(presented.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedHash, presentedHash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String path, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                path,
                MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY),
                Map.of()
        );
        objectMapper.writeValue(response.getWriter(), error);
    }
}
