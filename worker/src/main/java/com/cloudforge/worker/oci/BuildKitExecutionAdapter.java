package com.cloudforge.worker.oci;

import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.cloudforge.worker.security.WorkerSecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated BuildKit OCI image build adapter ({@code OCI_BUILDKIT}).
 *
 * <p><strong>Security boundaries enforced:</strong></p>
 * <ul>
 *   <li>Invokes BuildKit strictly via {@link ProcessBuilder} argument arrays (never a host shell).</li>
 *   <li>Produces a local OCI Image Layout TAR ({@code --output type=oci,dest=...}) — NEVER pushes
 *       directly from the Dockerfile build stage and NEVER exposes registry credentials or GitHub
 *       tokens to Dockerfile commands.</li>
 *   <li>Never passes {@code --allow security.insecure}, {@code --allow network.host}, or
 *       {@code --privileged}.</li>
 *   <li>Defaults to {@code --network=none} during build unless explicitly opted in via operator config.</li>
 *   <li>Enforces timeout deadline, active cancellation termination, disk bounds, and OCI layout
 *       SHA-256 verification.</li>
 * </ul>
 */
public class BuildKitExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(BuildKitExecutionAdapter.class);
    private static final long MIN_FREE_DISK_BYTES = 512L * 1024 * 1024;

    private final WorkerAgentConfig config;

    public BuildKitExecutionAdapter(WorkerAgentConfig config) {
        this.config = config;
    }

    public String adapterType() {
        return "OCI_BUILDKIT";
    }

    /**
     * Checks whether the configured BuildKit driver ({@code buildctl} or {@code docker buildx})
     * is reachable without silently falling back to privileged Docker builds.
     */
    public boolean isAvailable() {
        List<String> probeCmd = "buildctl".equalsIgnoreCase(config.buildKitDriver())
                ? buildBuildctlProbeCommand()
                : List.of("docker", "buildx", "version");
        try {
            Process p = new ProcessBuilder(probeCmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) {
                return false;
            }
            if (!"buildctl".equalsIgnoreCase(config.buildKitDriver())) {
                Process info = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                        .redirectErrorStream(true)
                        .start();
                boolean done = info.waitFor(5, TimeUnit.SECONDS);
                if (!done) {
                    info.destroyForcibly();
                    return false;
                }
                return info.exitValue() == 0;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> buildBuildctlProbeCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("buildctl");
        if (config.buildKitAddr() != null && !config.buildKitAddr().isBlank()) {
            cmd.add("--addr");
            cmd.add(config.buildKitAddr().trim());
        }
        cmd.add("debug");
        cmd.add("workers");
        return cmd;
    }

    /**
     * Constructs the exact hardened BuildKit command array. Visible for security unit tests.
     */
    public List<String> buildCommand(
            Path contextDir,
            Path dockerfile,
            Path outputOciTar,
            String targetPlatform,
            Map<String, String> buildArgs
    ) {
        String driver = config.buildKitDriver() == null ? "buildx" : config.buildKitDriver().trim().toLowerCase(Locale.ROOT);
        if ("buildctl".equals(driver)) {
            List<String> cmd = new ArrayList<>();
            cmd.add("buildctl");
            if (config.buildKitAddr() != null && !config.buildKitAddr().isBlank()) {
                cmd.add("--addr");
                cmd.add(config.buildKitAddr().trim());
            }
            cmd.add("build");
            cmd.add("--frontend");
            cmd.add("dockerfile.v0");
            cmd.add("--local");
            cmd.add("context=" + contextDir.toAbsolutePath().normalize());
            cmd.add("--local");
            cmd.add("dockerfile=" + dockerfile.getParent().toAbsolutePath().normalize());
            cmd.add("--opt");
            cmd.add("filename=" + dockerfile.getFileName().toString());
            cmd.add("--opt");
            cmd.add("platform=" + targetPlatform);
            if (!config.allowBuildNetwork()) {
                cmd.add("--opt");
                cmd.add("force-network-mode=none");
            }
            cmd.add("--no-cache");
            cmd.add("--progress=plain");
            if (buildArgs != null) {
                buildArgs.forEach((k, v) -> {
                    cmd.add("--opt");
                    cmd.add("build-arg:" + k + "=" + v);
                });
            }
            cmd.add("--output");
            cmd.add("type=oci,dest=" + outputOciTar.toAbsolutePath().normalize());
            return List.copyOf(cmd);
        }

        // Default: docker buildx (BuildKit engine) with zero privileged/insecure entitlements
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("buildx");
        cmd.add("build");
        if (config.buildKitBuilder() != null && !config.buildKitBuilder().isBlank()) {
            cmd.add("--builder");
            cmd.add(config.buildKitBuilder().trim());
        }
        cmd.add("--file");
        cmd.add(dockerfile.toAbsolutePath().normalize().toString());
        cmd.add("--platform");
        cmd.add(targetPlatform);
        cmd.add("--provenance=false");
        cmd.add("--sbom=false");
        cmd.add("--no-cache");
        cmd.add("--progress=plain");
        cmd.add("--network");
        cmd.add(config.allowBuildNetwork() ? "default" : "none");
        if (buildArgs != null) {
            buildArgs.forEach((k, v) -> {
                cmd.add("--build-arg");
                cmd.add(k + "=" + v);
            });
        }
        cmd.add("--output");
        cmd.add("type=oci,dest=" + outputOciTar.toAbsolutePath().normalize());
        cmd.add(contextDir.toAbsolutePath().normalize().toString());
        return List.copyOf(cmd);
    }

    public BuildOutcome executeBuild(
            Path contextDir,
            Path dockerfile,
            Path outputOciTar,
            String targetPlatform,
            Map<String, String> buildArgs,
            int timeoutSeconds,
            int maxLogLines,
            int maxLogBytes,
            AtomicBoolean cancelSignal,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        Instant startedAt = Instant.now();
        int effectiveTimeout = Math.min(Math.max(timeoutSeconds, 10), config.maxExecutionTimeoutSeconds());
        Instant deadline = startedAt.plusSeconds(effectiveTimeout);

        try {
            long usableSpace = Files.getFileStore(outputOciTar.getParent()).getUsableSpace();
            if (usableSpace < MIN_FREE_DISK_BYTES) {
                return BuildOutcome.failure("INFRASTRUCTURE_FAILURE", -1,
                        "Insufficient temporary disk space for bounded OCI BuildKit execution", startedAt, true);
            }
        } catch (IOException ignored) {
        }

        List<String> cmd = buildCommand(contextDir, dockerfile, outputOciTar, targetPlatform, buildArgs);
        logConsumer.accept(
                "SYSTEM",
                "[CloudForge BuildKit] Starting bounded OCI build (driver=" + config.buildKitDriver()
                        + ", platform=" + targetPlatform
                        + ", network=" + (config.allowBuildNetwork() ? "default" : "none")
                        + ", timeout=" + effectiveTimeout + "s, entitlements=none)",
                startedAt
        );

        Process process = null;
        boolean cancelled = false;
        boolean timedOut = false;
        boolean terminationConfirmed = false;
        AtomicInteger capturedLines = new AtomicInteger(0);
        AtomicInteger capturedBytes = new AtomicInteger(0);
        WorkerSecretRedactor redactor = new WorkerSecretRedactor();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Strip any sensitive CloudForge worker credentials from BuildKit child process env
            Map<String, String> env = pb.environment();
            env.remove("CLOUDFORGE_WORKER_TOKEN");
            env.remove("CLOUDFORGE_SECRETS_KEYRING");
            env.remove("GITHUB_APP_PRIVATE_KEY");
            env.put("DOCKER_BUILDKIT", "1");

            process = pb.start();
            Process procRef = process;
            Thread stdoutReader = new Thread(() -> drainStream(
                    procRef.getInputStream(), "STDOUT", maxLogLines, maxLogBytes,
                    capturedLines, capturedBytes, redactor, logConsumer
            ), "cf-bk-stdout");
            Thread stderrReader = new Thread(() -> drainStream(
                    procRef.getErrorStream(), "STDERR", maxLogLines, maxLogBytes,
                    capturedLines, capturedBytes, redactor, logConsumer
            ), "cf-bk-stderr");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();

            while (process.isAlive()) {
                if (cancelSignal != null && cancelSignal.get()) {
                    cancelled = true;
                    logConsumer.accept("SYSTEM", "[CloudForge BuildKit] Cancellation received; terminating build process", Instant.now());
                    terminationConfirmed = terminateProcessTree(process);
                    break;
                }
                if (Instant.now().isAfter(deadline)) {
                    timedOut = true;
                    logConsumer.accept("SYSTEM", "[CloudForge BuildKit] Build timeout (" + effectiveTimeout + "s) reached; terminating build process", Instant.now());
                    terminationConfirmed = terminateProcessTree(process);
                    break;
                }
                process.waitFor(150, TimeUnit.MILLISECONDS);
            }

            stdoutReader.join(3000);
            stderrReader.join(3000);

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            if (!cancelled && !timedOut) {
                terminationConfirmed = !process.isAlive();
            }

            if (cancelled) {
                return BuildOutcome.cancelled("OCI BuildKit execution cancelled and termination confirmed", startedAt, terminationConfirmed);
            }
            if (timedOut) {
                return BuildOutcome.timedOut("OCI BuildKit execution exceeded timeout of " + effectiveTimeout + "s", startedAt, terminationConfirmed);
            }
            if (exitCode != 0) {
                return BuildOutcome.failure("BUILD_FAILED", exitCode,
                        "BuildKit exited with non-zero code " + exitCode, startedAt, terminationConfirmed);
            }

            OciLayoutInspector.VerifiedOciLayout layout = OciLayoutInspector.inspectAndVerify(outputOciTar);
            String tarSha256 = OciLayoutInspector.sha256FileHex(outputOciTar);
            long tarBytes = Files.size(outputOciTar);

            logConsumer.accept(
                    "SYSTEM",
                    "[CloudForge BuildKit] Verified OCI layout (digest=" + layout.manifestDigest()
                            + ", platform=" + layout.platform()
                            + ", size=" + layout.totalImageBytes() + " bytes)",
                    Instant.now()
            );

            return new BuildOutcome(
                    "SUCCEEDED",
                    "NONE",
                    0,
                    "OCI BuildKit image built and verified (" + layout.manifestDigest() + ")",
                    startedAt,
                    Instant.now(),
                    terminationConfirmed,
                    layout,
                    tarSha256,
                    tarBytes
            );
        } catch (Exception ex) {
            String safeErr = redactor.redact(ex.getMessage());
            log.warn("BuildKit execution failed: {}", safeErr);
            terminationConfirmed = terminateProcessTree(process);
            return BuildOutcome.failure("INFRASTRUCTURE_FAILURE", -1,
                    "BuildKit execution error: " + safeErr, startedAt, terminationConfirmed);
        } finally {
            redactor.clear();
        }
    }

    private static boolean terminateProcessTree(Process process) {
        if (process == null) {
            return true;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            return !process.isAlive();
        } catch (Exception ex) {
            process.destroyForcibly();
            return !process.isAlive();
        }
    }

    private static void drainStream(
            InputStream stream,
            String streamType,
            int maxLines,
            int maxBytes,
            AtomicInteger lineCounter,
            AtomicInteger byteCounter,
            WorkerSecretRedactor redactor,
            IsolatedExecutionAdapter.LogLineConsumer consumer
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineCounter.get() >= maxLines || byteCounter.get() >= maxBytes) {
                    continue;
                }
                String safeLine = redactor.redact(line);
                int len = Math.min(safeLine.length(), 1800);
                String bounded = safeLine.length() > 1800 ? safeLine.substring(0, 1785) + "... [truncated]" : safeLine;
                lineCounter.incrementAndGet();
                byteCounter.addAndGet(len);
                consumer.accept(streamType, bounded, Instant.now());
            }
        } catch (Exception ignored) {
        }
    }

    public record BuildOutcome(
            String status,
            String failureCategory,
            Integer exitCode,
            String summary,
            Instant startedAt,
            Instant completedAt,
            boolean terminationConfirmed,
            OciLayoutInspector.VerifiedOciLayout layout,
            String layoutTarSha256,
            long layoutTarBytes
    ) {
        static BuildOutcome failure(String category, int exitCode, String summary, Instant startedAt, boolean confirmed) {
            return new BuildOutcome("FAILED", category, exitCode, summary, startedAt, Instant.now(), confirmed, null, null, 0L);
        }

        static BuildOutcome cancelled(String summary, Instant startedAt, boolean confirmed) {
            return new BuildOutcome("CANCELLED", "CANCELLED", 143, summary, startedAt, Instant.now(), confirmed, null, null, 0L);
        }

        static BuildOutcome timedOut(String summary, Instant startedAt, boolean confirmed) {
            return new BuildOutcome("TIMED_OUT", "EXECUTION_TIMEOUT", 124, summary, startedAt, Instant.now(), confirmed, null, null, 0L);
        }
    }
}
