package com.cloudforge.backend.worker.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Defense-in-depth sanitizer and redactor for isolated worker execution logs.
 *
 * <p>Strips unsafe control characters, normalizes UTF-8, redacts well-known
 * credential formats (GitHub tokens, Bearer headers, AWS keys, PEM blocks,
 * JDBC/URI passwords, key=value secrets), and enforces strict message bounds.</p>
 */
@Component
public class LogSanitizer {

    private static final String REDACTED = "[REDACTED]";

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PEM_HEADER = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{10,}\\b|\\bgithub_pat_[A-Za-z0-9_]{10,}\\b"
    );
    private static final Pattern BEARER_HEADER = Pattern.compile(
            "(?i)(Authorization\\s*:\\s*(?:Bearer|Basic|Token)\\s+)[^\\s,;\"']+|(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final Pattern AWS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
    private static final Pattern STRIPE_LIKE_KEY = Pattern.compile("\\bsk_(?:live|test)_[A-Za-z0-9]{10,}\\b");
    private static final Pattern URI_PASSWORD = Pattern.compile("(?i)(://[^:/\\s@]+:)([^@/\\s]+)(@)");
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|worker[_-]?token|oauth[_-]?token|auth[_-]?token)[A-Za-z0-9_.-]*\\s*[:=]\\s*)([^\\s,;\"']+)"
    );
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    public SanitizedLogResult sanitize(String rawMessage, int maxLength) {
        if (rawMessage == null) {
            return new SanitizedLogResult("", false);
        }
        String cleaned = CONTROL_CHARS.matcher(rawMessage).replaceAll("");
        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n');
        cleaned = PEM_BLOCK.matcher(cleaned).replaceAll(REDACTED);
        cleaned = PEM_HEADER.matcher(cleaned).replaceAll(REDACTED);
        cleaned = GITHUB_TOKEN.matcher(cleaned).replaceAll(REDACTED);
        cleaned = BEARER_HEADER.matcher(cleaned).replaceAll(matchResult -> {
            if (matchResult.group(1) != null) {
                return matchResult.group(1) + REDACTED;
            }
            return matchResult.group(2) + REDACTED;
        });
        cleaned = AWS_KEY.matcher(cleaned).replaceAll(REDACTED);
        cleaned = STRIPE_LIKE_KEY.matcher(cleaned).replaceAll(REDACTED);
        cleaned = URI_PASSWORD.matcher(cleaned).replaceAll("$1" + REDACTED + "$3");
        cleaned = KV_SECRET.matcher(cleaned).replaceAll("$1" + REDACTED);

        int limit = Math.max(64, maxLength);
        boolean truncated = false;
        if (cleaned.length() > limit) {
            String suffix = "... [truncated]";
            cleaned = cleaned.substring(0, Math.max(0, limit - suffix.length())) + suffix;
            truncated = true;
        }
        return new SanitizedLogResult(cleaned, truncated);
    }

    public record SanitizedLogResult(String message, boolean truncated) {
    }
}
