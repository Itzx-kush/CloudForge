package com.cloudforge.worker.oci;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defensive Dockerfile and build-context policy validator.
 *
 * <p><strong>Security note:</strong> Static Dockerfile parsing and policy validation are
 * defense-in-depth controls only and are <em>not</em> a substitute for execution sandboxing.
 * Hostile multi-tenant builds still require kernel-level/microVM isolation (e.g. gVisor/Kata/Firecracker).</p>
 */
public final class DockerfilePolicyValidator {

    public static final long MAX_DOCKERFILE_BYTES = 64L * 1024;
    public static final long MAX_CONTEXT_BYTES = 128L * 1024 * 1024;
    public static final int MAX_CONTEXT_FILES = 12_000;

    public static final Set<String> DEFAULT_ALLOWED_BASE_IMAGES = Set.of(
            "scratch",
            "alpine:3.20",
            "alpine:3.21",
            "busybox:1.36",
            "gcr.io/distroless/static-debian12",
            "gcr.io/distroless/base-debian12",
            "eclipse-temurin:17-jre-alpine",
            "node:20-alpine",
            "node:22-alpine",
            "nginx:1.27-alpine"
    );

    private static final Pattern UNSAFE_REL_PATH = Pattern.compile("[\\\\:\\x00-\\x1f\\x7f*?\"<>|]");
    private static final Pattern REMOTE_URI = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://");
    private static final Pattern FROM_PATTERN = Pattern.compile(
            "(?i)^FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+([a-zA-Z0-9._-]+))?\\s*$"
    );
    private static final Pattern ALLOWED_SYNTAX_DIRECTIVE = Pattern.compile(
            "(?i)^#\\s*syntax\\s*=\\s*(docker/dockerfile:1(?:\\.\\d+)*)(?:@sha256:[0-9a-f]{64})?\\s*$"
    );
    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
            "SECRET", "PASSWORD", "PASSWD", "TOKEN", "PRIVATE_KEY", "API_KEY", "ACCESS_KEY", "CREDENTIAL"
    );

    private DockerfilePolicyValidator() {
    }

    public static ValidatedContext validate(
            Path verifiedSourceRoot,
            String buildContextRelPath,
            String dockerfileRelPath,
            Set<String> extraAllowedBaseImages
    ) throws PolicyViolationException, IOException {
        Path canonicalRoot = verifiedSourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyViolationException("Verified source root directory does not exist");
        }

        Path contextDir = resolveSafeInsideRoot(canonicalRoot, buildContextRelPath, true, "buildContextPath");
        if (!Files.exists(contextDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(contextDir)
                || !Files.isDirectory(contextDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyViolationException("Build context directory does not exist or is invalid: " + buildContextRelPath);
        }

        Path dockerfile = resolveSafeInsideRoot(canonicalRoot, dockerfileRelPath, false, "dockerfilePath");
        if (!Files.exists(dockerfile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dockerfile)
                || !Files.isRegularFile(dockerfile, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyViolationException("Dockerfile does not exist as a regular file inside verified source: " + dockerfileRelPath);
        }

        long dockerfileSize = Files.size(dockerfile);
        if (dockerfileSize == 0 || dockerfileSize > MAX_DOCKERFILE_BYTES) {
            throw new PolicyViolationException("Dockerfile size (" + dockerfileSize + " bytes) exceeds bounds (1.." + MAX_DOCKERFILE_BYTES + ")");
        }

        ContextStats stats = scanContextBounds(contextDir);
        String content = Files.readString(dockerfile, StandardCharsets.UTF_8);
        Set<String> allowedBases = new HashSet<>(DEFAULT_ALLOWED_BASE_IMAGES);
        if (extraAllowedBaseImages != null) {
            for (String img : extraAllowedBaseImages) {
                if (img != null && !img.isBlank()) {
                    allowedBases.add(img.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> baseImages = validateDockerfileContent(content, allowedBases);

        return new ValidatedContext(contextDir, dockerfile, stats.fileCount(), stats.totalBytes(), baseImages);
    }

    static List<String> validateDockerfileContent(String rawContent, Set<String> allowedBaseImages)
            throws PolicyViolationException {
        String[] rawLines = rawContent.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> logicalLines = new ArrayList<>();
        boolean parsingDirectives = true;
        StringBuilder current = new StringBuilder();

        for (String line : rawLines) {
            String trimmed = line.trim();
            if (parsingDirectives && trimmed.startsWith("#")) {
                if (trimmed.toLowerCase(Locale.ROOT).matches("^#\\s*syntax\\s*=.*")) {
                    if (!ALLOWED_SYNTAX_DIRECTIVE.matcher(trimmed).matches()) {
                        throw new PolicyViolationException(
                                "Untrusted Dockerfile #syntax frontend directive rejected: " + trimmed
                        );
                    }
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (current.length() == 0) {
                    continue;
                }
                continue;
            }
            parsingDirectives = false;
            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1).append(' ');
            } else {
                current.append(trimmed);
                logicalLines.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            logicalLines.add(current.toString().trim());
        }

        if (logicalLines.isEmpty()) {
            throw new PolicyViolationException("Dockerfile contains no executable instructions");
        }

        Set<String> definedStages = new HashSet<>();
        List<String> externalBaseImages = new ArrayList<>();
        int fromCount = 0;

        for (String instruction : logicalLines) {
            String upper = instruction.toUpperCase(Locale.ROOT);
            int spaceIdx = instruction.indexOf(' ');
            String keyword = (spaceIdx > 0 ? instruction.substring(0, spaceIdx) : instruction).toUpperCase(Locale.ROOT);
            String args = spaceIdx > 0 ? instruction.substring(spaceIdx + 1).trim() : "";

            switch (keyword) {
                case "FROM" -> {
                    Matcher m = FROM_PATTERN.matcher(instruction);
                    if (!m.matches()) {
                        throw new PolicyViolationException("Malformed FROM instruction in Dockerfile");
                    }
                    String imageRef = m.group(1).trim();
                    String alias = m.group(2);
                    if (imageRef.contains("$")) {
                        throw new PolicyViolationException("Dynamic variable interpolation in FROM base image is prohibited");
                    }
                    String lowerRef = imageRef.toLowerCase(Locale.ROOT);
                    if (!definedStages.contains(lowerRef)) {
                        if (!isAllowedBaseImage(lowerRef, allowedBaseImages)) {
                            throw new PolicyViolationException(
                                    "Base image \"" + imageRef + "\" is not in the trusted CloudForge base image allowlist"
                            );
                        }
                        externalBaseImages.add(imageRef);
                    }
                    if (alias != null && !alias.isBlank()) {
                        definedStages.add(alias.toLowerCase(Locale.ROOT));
                    }
                    fromCount++;
                }
                case "ADD" -> {
                    for (String token : args.split("\\s+")) {
                        if (REMOTE_URI.matcher(token).find()) {
                            throw new PolicyViolationException("Remote URL in Dockerfile ADD instruction is prohibited");
                        }
                    }
                }
                case "RUN" -> {
                    String lowerArgs = args.toLowerCase(Locale.ROOT);
                    if (lowerArgs.contains("--security=insecure")
                            || lowerArgs.contains("--network=host")
                            || lowerArgs.contains("--mount=type=ssh")
                            || lowerArgs.contains("--mount=type=secret")) {
                        throw new PolicyViolationException(
                                "Privileged, host-network, SSH, or secret mount entitlement in RUN is prohibited"
                        );
                    }
                    if (lowerArgs.contains("--mount=") && lowerArgs.contains("type=bind") && lowerArgs.contains("from=")) {
                        // Allow only bind mounts referencing previously defined local stages
                        Matcher fm = Pattern.compile("(?i)from=([^,\\s]+)").matcher(args);
                        while (fm.find()) {
                            String fromTarget = fm.group(1).toLowerCase(Locale.ROOT);
                            if (!definedStages.contains(fromTarget)) {
                                throw new PolicyViolationException(
                                        "RUN --mount=type=bind from external image \"" + fromTarget + "\" is prohibited"
                                );
                            }
                        }
                    }
                }
                case "COPY" -> {
                    Matcher fm = Pattern.compile("(?i)--from=([^\\s]+)").matcher(args);
                    if (fm.find()) {
                        String fromTarget = fm.group(1).toLowerCase(Locale.ROOT);
                        if (!definedStages.contains(fromTarget) && !fromTarget.matches("^[0-9]+$")) {
                            throw new PolicyViolationException(
                                    "COPY --from external image \"" + fromTarget + "\" is prohibited; copy only from prior build stages"
                            );
                        }
                    }
                }
                case "ONBUILD" -> throw new PolicyViolationException("Dockerfile ONBUILD triggers are prohibited");
                case "VOLUME" -> throw new PolicyViolationException("Dockerfile VOLUME declarations are prohibited by build policy");
                case "ARG", "ENV" -> {
                    validateNoSecretDeclaration(keyword, args);
                }
                default -> {
                    if (upper.contains("-----BEGIN ") || upper.contains("DOCKER.SOCK")) {
                        throw new PolicyViolationException("Prohibited credential or docker.sock reference detected in Dockerfile");
                    }
                }
            }
        }

        if (fromCount == 0) {
            throw new PolicyViolationException("Dockerfile must contain at least one valid FROM instruction");
        }
        return List.copyOf(externalBaseImages);
    }

    private static boolean isAllowedBaseImage(String lowerImageRef, Set<String> allowedBaseImages) {
        if (allowedBaseImages.contains(lowerImageRef)) {
            return true;
        }
        int digestIdx = lowerImageRef.indexOf("@sha256:");
        if (digestIdx > 0) {
            String digestPart = lowerImageRef.substring(digestIdx + 1);
            if (!digestPart.matches("^sha256:[0-9a-f]{64}$")) {
                return false;
            }
            String baseWithoutDigest = lowerImageRef.substring(0, digestIdx);
            return allowedBaseImages.contains(baseWithoutDigest);
        }
        return false;
    }

    private static void validateNoSecretDeclaration(String keyword, String args) throws PolicyViolationException {
        String normalized = args.replace("=", " ");
        for (String token : normalized.split("\\s+")) {
            String upper = token.toUpperCase(Locale.ROOT);
            if (FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(upper::contains)) {
                throw new PolicyViolationException(
                        "Secret-like " + keyword + " declaration \"" + token + "\" is prohibited in Dockerfile"
                );
            }
        }
        String lower = args.toLowerCase(Locale.ROOT);
        if (args.contains("-----BEGIN ") || lower.contains("ghp_") || lower.contains("github_pat_")
                || lower.contains("akia") || lower.contains("sk_live_")) {
            throw new PolicyViolationException("Literal secret value detected in Dockerfile " + keyword);
        }
    }

    private static Path resolveSafeInsideRoot(
            Path canonicalRoot,
            String rawRelPath,
            boolean allowDotRoot,
            String fieldName
    ) throws PolicyViolationException {
        if (rawRelPath == null || rawRelPath.isBlank()) {
            throw new PolicyViolationException(fieldName + " must not be blank");
        }
        String trimmed = rawRelPath.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.startsWith("~")
                || REMOTE_URI.matcher(trimmed).find()
                || UNSAFE_REL_PATH.matcher(trimmed).find()) {
            throw new PolicyViolationException("Unsafe " + fieldName + ": " + rawRelPath);
        }
        if (allowDotRoot && ".".equals(trimmed)) {
            return canonicalRoot;
        }
        String cleaned = trimmed.startsWith("./") ? trimmed.substring(2) : trimmed;
        Path current = canonicalRoot;
        for (String part : cleaned.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || ".git".equalsIgnoreCase(part)) {
                throw new PolicyViolationException("Path traversal or unsafe segment rejected in " + fieldName);
            }
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new PolicyViolationException("Symbolic link rejected in " + fieldName);
            }
        }
        Path normalized = current.normalize();
        if (!normalized.startsWith(canonicalRoot)) {
            throw new PolicyViolationException(fieldName + " escapes verified source root");
        }
        return normalized;
    }

    private static ContextStats scanContextBounds(Path contextDir) throws PolicyViolationException, IOException {
        int[] files = {0};
        long[] totalBytes = {0L};
        try (var walk = Files.walk(contextDir)) {
            for (Path entry : walk.toList()) {
                if (Files.isSymbolicLink(entry)) {
                    throw new PolicyViolationException("Symbolic links are prohibited inside the OCI build context");
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PolicyViolationException("Special non-regular file rejected inside OCI build context");
                }
                files[0]++;
                if (files[0] > MAX_CONTEXT_FILES) {
                    throw new PolicyViolationException("Build context exceeds maximum file count (" + MAX_CONTEXT_FILES + ")");
                }
                long sz = Files.size(entry);
                if (sz > MAX_CONTEXT_BYTES - totalBytes[0]) {
                    throw new PolicyViolationException("Build context exceeds maximum size limit (" + MAX_CONTEXT_BYTES + " bytes)");
                }
                totalBytes[0] += sz;
            }
        }
        if (files[0] == 0) {
            throw new PolicyViolationException("Build context contains no files");
        }
        return new ContextStats(files[0], totalBytes[0]);
    }

    private record ContextStats(int fileCount, long totalBytes) {
    }

    public record ValidatedContext(
            Path contextDirectory,
            Path dockerfile,
            int contextFileCount,
            long contextTotalBytes,
            List<String> baseImages
    ) {
    }

    public static final class PolicyViolationException extends Exception {
        public PolicyViolationException(String message) {
            super(message);
        }
    }
}
