package com.cloudforge.backend.security;

import com.cloudforge.backend.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

/**
 * Validates redirect targets against explicitly configured allowed origins
 * to prevent open redirect vulnerabilities during OAuth and GitHub App callbacks.
 */
@Component
public class RedirectOriginValidator {

    private final AppProperties appProperties;

    public RedirectOriginValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String buildSafeFrontendUrl(String relativePathAndQuery) {
        String base = trimTrailingSlash(appProperties.getAuth().getFrontendBaseUrl());
        if (!isValidRelativePath(relativePathAndQuery)) {
            return base + "/";
        }
        return base + relativePathAndQuery;
    }

    public String validateOrDefault(String candidateUrl, String defaultRelativePath) {
        String fallback = buildSafeFrontendUrl(defaultRelativePath);
        if (!StringUtils.hasText(candidateUrl)) {
            return fallback;
        }
        String trimmed = candidateUrl.trim();
        if (isValidRelativePath(trimmed)) {
            return buildSafeFrontendUrl(trimmed);
        }
        try {
            URI uri = URI.create(trimmed);
            String candidateOrigin = extractOrigin(uri);
            if (candidateOrigin == null) {
                return fallback;
            }
            boolean allowed = appProperties.getAuth().getAllowedRedirectOrigins().stream()
                    .map(this::normalizeOrigin)
                    .anyMatch(origin -> origin != null && origin.equals(candidateOrigin));
            return allowed ? uri.toString() : fallback;
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private boolean isValidRelativePath(String path) {
        return StringUtils.hasText(path)
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.startsWith("/\\")
                && !path.contains("\r")
                && !path.contains("\n");
    }

    private String normalizeOrigin(String rawOrigin) {
        if (!StringUtils.hasText(rawOrigin)) {
            return null;
        }
        try {
            return extractOrigin(URI.create(rawOrigin.trim()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String extractOrigin(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        int port = uri.getPort();
        boolean isDefaultPort = (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443)
                || port == -1;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (isDefaultPort ? "" : ":" + port);
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "http://localhost:5173";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
