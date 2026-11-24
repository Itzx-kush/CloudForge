package com.cloudforge.worker.security;

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
 * Worker-side defense-in-depth redactor that scrubs delivered plaintext secrets
 * (raw, multiline, Base64, Base64URL, URL-encoded) and well-known credential patterns
 * from stdout/stderr chunks, summaries, and exceptions before any bytes leave the worker.
 */
public final class WorkerSecretRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final int MIN_EXACT_LEN = 5;

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
    private static final Pattern URI_PASSWORD = Pattern.compile("(?i)(://[^:/\\s@]+:)([^@/\\s]+)(@)");
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|worker[_-]?token|oauth[_-]?token|auth[_-]?token)[A-Za-z0-9_.-]*\\s*[:=]\\s*)([^\\s,;\"']+)"
    );
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private final List<String> variants = new ArrayList<>();

    public synchronized void addSecrets(Collection<String> rawSecrets) {
        if (rawSecrets == null || rawSecrets.isEmpty()) {
            return;
        }
        Set<String> collected = new LinkedHashSet<>(variants);
        for (String raw : rawSecrets) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String normalized = CONTROL_CHARS.matcher(raw).replaceAll("")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            addIfEligible(collected, raw);
            addIfEligible(collected, normalized);

            byte[] utf8 = normalized.getBytes(StandardCharsets.UTF_8);
            addIfEligible(collected, Base64.getEncoder().encodeToString(utf8));
            addIfEligible(collected, Base64.getEncoder().withoutPadding().encodeToString(utf8));
            addIfEligible(collected, Base64.getUrlEncoder().encodeToString(utf8));
            addIfEligible(collected, Base64.getUrlEncoder().withoutPadding().encodeToString(utf8));

            String urlEncoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            addIfEligible(collected, urlEncoded);
            addIfEligible(collected, urlEncoded.replace("+", "%20"));

            if (normalized.contains("\n")) {
                for (String line : normalized.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.length() >= 8 && !trimmed.startsWith("-----BEGIN ") && !trimmed.startsWith("-----END ")) {
                        addIfEligible(collected, trimmed);
                        addIfEligible(collected, Base64.getEncoder().encodeToString(trimmed.getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        variants.clear();
        variants.addAll(collected);
        variants.sort(Comparator.comparingInt(String::length).reversed());
    }

    public synchronized String redact(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = CONTROL_CHARS.matcher(raw).replaceAll("")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        for (String v : variants) {
            if (cleaned.contains(v)) {
                cleaned = cleaned.replace(v, REDACTED);
            }
        }
        cleaned = PEM_BLOCK.matcher(cleaned).replaceAll(REDACTED);
        cleaned = PEM_HEADER.matcher(cleaned).replaceAll(REDACTED);
        cleaned = GITHUB_TOKEN.matcher(cleaned).replaceAll(REDACTED);
        cleaned = BEARER_HEADER.matcher(cleaned).replaceAll(m ->
                m.group(1) != null ? m.group(1) + REDACTED : m.group(2) + REDACTED);
        cleaned = AWS_KEY.matcher(cleaned).replaceAll(REDACTED);
        cleaned = URI_PASSWORD.matcher(cleaned).replaceAll("$1" + REDACTED + "$3");
        cleaned = KV_SECRET.matcher(cleaned).replaceAll("$1" + REDACTED);
        return cleaned;
    }

    public synchronized BoundaryPair redactAcrossBoundary(String previousMessage, String nextMessage) {
        String curr = redact(nextMessage);
        if (previousMessage == null || curr == null || variants.isEmpty()) {
            return new BoundaryPair(false, previousMessage, curr);
        }
        int maxLen = variants.get(0).length();
        String prev = previousMessage;
        boolean modifiedPrev = false;

        int tailLen = Math.min(prev.length(), maxLen);
        int headLen = Math.min(curr.length(), maxLen);
        if (tailLen == 0 || headLen == 0) {
            return new BoundaryPair(false, prev, curr);
        }
        String tail = prev.substring(prev.length() - tailLen);
        String head = curr.substring(0, headLen);
        for (String v : variants) {
            int directIdx = (tail + head).indexOf(v);
            if (directIdx >= 0 && directIdx < tail.length() && directIdx + v.length() > tail.length()) {
                int cutPrev = prev.length() - (tail.length() - directIdx);
                int cutCurr = directIdx + v.length() - tail.length();
                prev = prev.substring(0, Math.max(0, cutPrev)) + REDACTED;
                curr = REDACTED + curr.substring(Math.min(curr.length(), cutCurr));
                modifiedPrev = true;
                break;
            }
            if (v.contains("\n")) {
                String nlCombined = tail + "\n" + head;
                int nlIdx = nlCombined.indexOf(v);
                if (nlIdx >= 0 && nlIdx < tail.length() && nlIdx + v.length() > tail.length() + 1) {
                    int cutPrev = prev.length() - (tail.length() - nlIdx);
                    int cutCurr = nlIdx + v.length() - (tail.length() + 1);
                    prev = prev.substring(0, Math.max(0, cutPrev)) + REDACTED;
                    curr = REDACTED + curr.substring(Math.min(curr.length(), cutCurr));
                    modifiedPrev = true;
                    break;
                }
            }
        }
        return new BoundaryPair(modifiedPrev, prev, curr);
    }

    public synchronized void clear() {
        variants.clear();
    }

    private static void addIfEligible(Set<String> target, String candidate) {
        if (candidate != null && candidate.length() >= MIN_EXACT_LEN && !REDACTED.equals(candidate)) {
            target.add(candidate);
        }
    }

    public record BoundaryPair(boolean previousModified, String previousMessage, String currentMessage) {
    }
}
