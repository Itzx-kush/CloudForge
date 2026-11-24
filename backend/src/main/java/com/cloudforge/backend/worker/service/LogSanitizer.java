package com.cloudforge.backend.worker.service;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Defense-in-depth sanitizer and redactor for isolated worker execution logs and summaries.
 *
 * <p>Strips unsafe control characters, normalizes line endings, redacts well-known
 * credential formats (GitHub tokens, Bearer headers, AWS keys, Stripe keys, PEM blocks,
 * JDBC/URI passwords, key=value secrets), exact secret values plus multiline, Base64, and
 * URL-encoded variants, and boundary-spanning chunks across streamed entries.</p>
 */
@Component
public class LogSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final int MIN_EXACT_SECRET_CHARS = 5;

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
        return sanitize(rawMessage, maxLength, List.of());
    }

    public SanitizedLogResult sanitize(String rawMessage, int maxLength, Collection<String> exactSecrets) {
        if (rawMessage == null) {
            return new SanitizedLogResult("", false);
        }
        String cleaned = CONTROL_CHARS.matcher(rawMessage).replaceAll("");
        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n');

        List<String> variants = expandSecretVariants(exactSecrets);
        for (String variant : variants) {
            if (cleaned.contains(variant)) {
                cleaned = cleaned.replace(variant, REDACTED);
            }
        }

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

    /**
     * Expands known plaintext secrets into normalized raw, multiline segment, Base64,
     * Base64URL, and URL-encoded forms ordered longest-first for deterministic literal redaction.
     */
    public List<String> expandSecretVariants(Collection<String> rawSecrets) {
        if (rawSecrets == null || rawSecrets.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : rawSecrets) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String normalized = CONTROL_CHARS.matcher(raw).replaceAll("")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            addIfEligible(unique, raw);
            addIfEligible(unique, normalized);

            byte[] utf8 = normalized.getBytes(StandardCharsets.UTF_8);
            addIfEligible(unique, Base64.getEncoder().encodeToString(utf8));
            addIfEligible(unique, Base64.getEncoder().withoutPadding().encodeToString(utf8));
            addIfEligible(unique, Base64.getUrlEncoder().encodeToString(utf8));
            addIfEligible(unique, Base64.getUrlEncoder().withoutPadding().encodeToString(utf8));

            String formUrl = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            addIfEligible(unique, formUrl);
            addIfEligible(unique, formUrl.replace("+", "%20"));

            if (normalized.contains("\n")) {
                for (String line : normalized.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.length() >= 8 && !trimmed.startsWith("-----BEGIN ") && !trimmed.startsWith("-----END ")) {
                        addIfEligible(unique, trimmed);
                        byte[] lineBytes = trimmed.getBytes(StandardCharsets.UTF_8);
                        addIfEligible(unique, Base64.getEncoder().encodeToString(lineBytes));
                    }
                }
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /**
     * Detects whether any known secret variant spans the boundary between two consecutive log lines
     * (either directly joined or separated by {@code \n}), and redacts both sides cleanly.
     */
    public BoundaryRedactionResult redactBoundarySpan(
            String previousMessage,
            String currentMessage,
            List<String> expandedVariants
    ) {
        if (previousMessage == null || currentMessage == null
                || expandedVariants == null || expandedVariants.isEmpty()) {
            return new BoundaryRedactionResult(false, previousMessage, currentMessage);
        }

        String updatedPrev = previousMessage;
        String updatedCurr = currentMessage;
        boolean prevModified = false;

        int maxVariantLen = expandedVariants.get(0).length();
        int tailLen = Math.min(updatedPrev.length(), maxVariantLen);
        int headLen = Math.min(updatedCurr.length(), maxVariantLen);
        if (tailLen == 0 || headLen == 0) {
            return new BoundaryRedactionResult(false, previousMessage, currentMessage);
        }

        String tail = updatedPrev.substring(updatedPrev.length() - tailLen);
        String head = updatedCurr.substring(0, headLen);

        for (String variant : expandedVariants) {
            if (variant.length() < MIN_EXACT_SECRET_CHARS) {
                continue;
            }
            BoundaryMatch direct = findSpanningMatch(tail, head, "", variant);
            if (direct != null) {
                int prevCut = updatedPrev.length() - (tail.length() - direct.tailStart());
                int currCut = direct.headEnd();
                updatedPrev = updatedPrev.substring(0, Math.max(0, prevCut)) + REDACTED;
                updatedCurr = REDACTED + updatedCurr.substring(Math.min(updatedCurr.length(), currCut));
                prevModified = true;
                tailLen = Math.min(updatedPrev.length(), maxVariantLen);
                headLen = Math.min(updatedCurr.length(), maxVariantLen);
                tail = updatedPrev.substring(updatedPrev.length() - tailLen);
                head = updatedCurr.substring(0, headLen);
                continue;
            }
            if (variant.contains("\n")) {
                BoundaryMatch nl = findSpanningMatch(tail, head, "\n", variant);
                if (nl != null) {
                    int prevCut = updatedPrev.length() - (tail.length() - nl.tailStart());
                    int currCut = nl.headEnd();
                    updatedPrev = updatedPrev.substring(0, Math.max(0, prevCut)) + REDACTED;
                    updatedCurr = REDACTED + updatedCurr.substring(Math.min(updatedCurr.length(), currCut));
                    prevModified = true;
                    tailLen = Math.min(updatedPrev.length(), maxVariantLen);
                    headLen = Math.min(updatedCurr.length(), maxVariantLen);
                    tail = updatedPrev.substring(updatedPrev.length() - tailLen);
                    head = updatedCurr.substring(0, headLen);
                }
            }
        }

        return new BoundaryRedactionResult(prevModified, updatedPrev, updatedCurr);
    }

    private BoundaryMatch findSpanningMatch(String tail, String head, String separator, String variant) {
        String combined = tail + separator + head;
        int idx = combined.indexOf(variant);
        while (idx >= 0) {
            int end = idx + variant.length();
            int boundaryEndOfTail = tail.length();
            int boundaryStartOfHead = tail.length() + separator.length();
            if (idx < boundaryEndOfTail && end > boundaryStartOfHead) {
                return new BoundaryMatch(idx, end - boundaryStartOfHead);
            }
            idx = combined.indexOf(variant, idx + 1);
        }
        return null;
    }

    private static void addIfEligible(Set<String> target, String candidate) {
        if (candidate != null && candidate.length() >= MIN_EXACT_SECRET_CHARS && !REDACTED.equals(candidate)) {
            target.add(candidate);
        }
    }

    private record BoundaryMatch(int tailStart, int headEnd) {
    }

    public record BoundaryRedactionResult(boolean previousModified, String previousMessage, String currentMessage) {
    }

    public record SanitizedLogResult(String message, boolean truncated) {
    }
}
