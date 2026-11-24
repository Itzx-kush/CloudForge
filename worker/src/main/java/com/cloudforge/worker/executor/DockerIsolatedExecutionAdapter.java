package com.cloudforge.worker.executor;

import com.cloudforge.worker.config.WorkerAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardened local Docker isolated execution adapter running strictly allowlisted operations
 * inside ephemeral, rootless-user, capability-dropped, read-only, network-isolated containers.
 *
 * <p><strong>Security guarantees enforced on every invocation:</strong></p>
 * <ul>
 *   <li>Non-root user ({@code --user 65534:65534})</li>
 *   <li>All Linux capabilities dropped ({@code --cap-drop=ALL})</li>
 *   <li>Privilege escalation blocked ({@code --security-opt no-new-privileges:true})</li>
 *   <li>Read-only root filesystem ({@code --read-only}) with bounded noexec tmpfs</li>
 *   <li>No network egress ({@code --network=none})</li>
 *   <li>Strict CPU, memory (swap disabled), and PID limits</li>
 *   <li>No privileged flag, no Docker socket mount, no host mounts, no secrets</li>
 *   <li>Fixed allowlisted image &amp; fixed allowlisted commands only</li>
 *   <li>Confirmed container cleanup targeting strictly {@code cf-exec-<attemptPrefix>}</li>
 * </ul>
 */
public class DockerIsolatedExecutionAdapter implements IsolatedExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(DockerIsolatedExecutionAdapter.class);
    private static final int STOP_GRACE_SECONDS = 3;

    private final WorkerAgentConfig config;

    public DockerIsolatedExecutionAdapter(WorkerAgentConfig config) {
        this.config = config;
    }

    @Override
    public String adapterType() {
        return "DOCKER_ISOLATED";
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Builds the exact hardened {@code docker run} command array (never invokes a host shell).
     * Visible for security unit testing.
     */
    public List<String> buildDockerRunCommand(String containerName, ExecutionRequest request) {
        List<String> fixedCommand = AllowlistedOperationRegistry.resolveFixedCommand(request.allowedOperation());
        return buildDockerRunCommandWithArgs(containerName, request, fixedCommand);
    }

    List<String> buildDockerRunCommandWithArgs(
            String containerName,
            ExecutionRequest request,
            List<String> containerArgs
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--init");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--label");
        cmd.add("cloudforge.managed=true");
        cmd.add("--label");
        cmd.add("cloudforge.attempt-id=" + request.attemptId());
        cmd.add("--user");
        cmd.add("65534:65534");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt");
        cmd.add("no-new-privileges:true");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,size=64m");
        cmd.add("--network=none");
        cmd.add("--cpus");
        cmd.add(config.cpuLimit());
        cmd.add("--memory");
        cmd.add(config.memoryLimit());
        cmd.add("--memory-swap");
        cmd.add(config.memoryLimit());
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(config.pidsLimit()));
        cmd.add("--env");
        cmd.add("CLOUDFORGE_OPERATION=" + request.allowedOperation());
        cmd.add("--env");
        cmd.add("CLOUDFORGE_ATTEMPT_NUMBER=" + request.attemptNumber());
        cmd.add(config.executorImage());
        cmd.addAll(containerArgs);
        return List.copyOf(cmd);
    }

    @Override
    public ExecutionResult execute(
            ExecutionRequest request,
            AtomicBoolean cancelSignal,
            LogLineConsumer logConsumer
    ) {
        if (!AllowlistedOperationRegistry.isSupported(request.allowedOperation())) {
            Instant now = Instant.now();
            return new ExecutionResult(
                    "FAILED",
                    "UNSUPPORTED_OPERATION",
                    126,
                    "Operation \"" + request.allowedOperation() + "\" is not allowlisted for Phase 3.3 isolated execution",
                    Map.of("executorImage", config.executorImage()),
                    now,
                    now,
                    true
            );
        }

        List<String> fixedCommand = AllowlistedOperationRegistry.resolveFixedCommand(request.allowedOperation());
        return executeContainerArgs(request, fixedCommand, cancelSignal, logConsumer);
    }

    /**
     * Package-private hook used strictly by integration tests to verify timeout and cancellation
     * termination of real containers using fixed harmless test probes (e.g. sleep).
     */
    ExecutionResult executeContainerArgs(
            ExecutionRequest request,
            List<String> containerArgs,
            AtomicBoolean cancelSignal,
            LogLineConsumer logConsumer
    ) {
        String shortId = request.attemptId().toString().replace("-", "").substring(0, 12);
        String containerName = "cf-exec-" + shortId;
        Instant startedAt = Instant.now();
        int effectiveTimeout = Math.min(Math.max(request.timeoutSeconds(), 1), config.maxExecutionTimeoutSeconds());
        Instant deadline = startedAt.plusSeconds(effectiveTimeout);

        List<String> cmd = buildDockerRunCommandWithArgs(containerName, request, containerArgs);
        logConsumer.accept(
                "SYSTEM",
                "[CloudForge Worker] Launching isolated container " + containerName
                        + " (image=" + config.executorImage()
                        + ", cpus=" + config.cpuLimit()
                        + ", memory=" + config.memoryLimit()
                        + ", pids=" + config.pidsLimit()
                        + ", network=none, readOnlyRootfs=true, uid=65534)",
                startedAt
        );

        Process process = null;
        boolean timedOut = false;
        boolean cancelled = false;
        boolean terminationConfirmed = false;
        AtomicInteger capturedLines = new AtomicInteger(0);
        AtomicInteger capturedBytes = new AtomicInteger(0);

        try {
            process = new ProcessBuilder(cmd).start();

            Process procRef = process;
            Thread stdoutReader = new Thread(() -> drainStream(
                    procRef.getInputStream(), "STDOUT", request.maxLogLines(), request.maxLogBytes(),
                    capturedLines, capturedBytes, logConsumer
            ), "cf-stdout-" + shortId);
            Thread stderrReader = new Thread(() -> drainStream(
                    procRef.getErrorStream(), "STDERR", request.maxLogLines(), request.maxLogBytes(),
                    capturedLines, capturedBytes, logConsumer
            ), "cf-stderr-" + shortId);
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();

            while (process.isAlive()) {
                if (cancelSignal != null && cancelSignal.get()) {
                    cancelled = true;
                    logConsumer.accept("SYSTEM", "[CloudForge Worker] Cancellation signal received; stopping container " + containerName, Instant.now());
                    terminationConfirmed = terminateAndConfirmContainerGone(containerName, process);
                    break;
                }
                if (Instant.now().isAfter(deadline)) {
                    timedOut = true;
                    logConsumer.accept("SYSTEM", "[CloudForge Worker] Execution timeout (" + effectiveTimeout + "s) reached; terminating container " + containerName, Instant.now());
                    terminationConfirmed = terminateAndConfirmContainerGone(containerName, process);
                    break;
                }
                process.waitFor(150, TimeUnit.MILLISECONDS);
            }

            stdoutReader.join(3000);
            stderrReader.join(3000);

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            if (!cancelled && !timedOut) {
                terminationConfirmed = verifyContainerRemoved(containerName);
            }
            Instant completedAt = Instant.now();

            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("adapter", adapterType());
            usage.put("executorImage", config.executorImage());
            usage.put("cpuLimit", config.cpuLimit());
            usage.put("memoryLimit", config.memoryLimit());
            usage.put("pidsLimit", config.pidsLimit());
            usage.put("networkMode", "none");
            usage.put("readOnlyRootfs", true);
            usage.put("durationMs", Duration.between(startedAt, completedAt).toMillis());
            usage.put("capturedLines", capturedLines.get());

            if (cancelled) {
                return new ExecutionResult(
                        "CANCELLED",
                        "CANCELLED",
                        exitCode,
                        "Isolated container execution cancelled and termination confirmed",
                        usage,
                        startedAt,
                        completedAt,
                        terminationConfirmed
                );
            }
            if (timedOut) {
                return new ExecutionResult(
                        "TIMED_OUT",
                        "EXECUTION_TIMEOUT",
                        exitCode,
                        "Isolated container execution exceeded timeout of " + effectiveTimeout + "s",
                        usage,
                        startedAt,
                        completedAt,
                        terminationConfirmed
                );
            }
            if (exitCode == 0) {
                return new ExecutionResult(
                        "SUCCEEDED",
                        "NONE",
                        0,
                        "Allowlisted operation " + request.allowedOperation() + " succeeded in isolated container",
                        usage,
                        startedAt,
                        completedAt,
                        terminationConfirmed
                );
            }
            return new ExecutionResult(
                    "FAILED",
                    "OPERATION_FAILURE",
                    exitCode,
                    "Allowlisted operation " + request.allowedOperation() + " exited with code " + exitCode,
                    usage,
                    startedAt,
                    completedAt,
                    terminationConfirmed
            );
        } catch (Exception ex) {
            log.warn("Docker isolated execution infrastructure failure for attempt={}: {}", request.attemptId(), ex.getMessage());
            terminationConfirmed = terminateAndConfirmContainerGone(containerName, process);
            Instant completedAt = Instant.now();
            return new ExecutionResult(
                    "FAILED",
                    "INFRASTRUCTURE_FAILURE",
                    -1,
                    "Isolated container infrastructure error: " + ex.getMessage(),
                    Map.of("adapter", adapterType(), "executorImage", config.executorImage()),
                    startedAt,
                    completedAt,
                    terminationConfirmed
            );
        } finally {
            // Defense-in-depth cleanup targeting strictly this attempt's container name
            forceRemoveContainerQuietly(containerName);
        }
    }

    private void drainStream(
            InputStream stream,
            String streamType,
            int maxLines,
            int maxBytes,
            AtomicInteger lineCounter,
            AtomicInteger byteCounter,
            LogLineConsumer consumer
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineCounter.get() >= maxLines || byteCounter.get() >= maxBytes) {
                    continue;
                }
                int len = Math.min(line.length(), 1800);
                String bounded = line.length() > 1800 ? line.substring(0, 1785) + "... [truncated]" : line;
                lineCounter.incrementAndGet();
                byteCounter.addAndGet(len);
                consumer.accept(streamType, bounded, Instant.now());
            }
        } catch (Exception ignored) {
            // Stream closed on termination
        }
    }

    private boolean terminateAndConfirmContainerGone(String containerName, Process hostProcess) {
        try {
            runQuietDockerCommand(List.of("docker", "stop", "-t", String.valueOf(STOP_GRACE_SECONDS), containerName), 8);
            if (!verifyContainerRemoved(containerName)) {
                runQuietDockerCommand(List.of("docker", "kill", containerName), 5);
                runQuietDockerCommand(List.of("docker", "rm", "-f", containerName), 5);
            }
            if (hostProcess != null && hostProcess.isAlive()) {
                hostProcess.waitFor(3, TimeUnit.SECONDS);
                if (hostProcess.isAlive()) {
                    hostProcess.destroyForcibly();
                }
            }
            return verifyContainerRemoved(containerName);
        } catch (Exception ex) {
            return false;
        }
    }

    private void forceRemoveContainerQuietly(String containerName) {
        if (!verifyContainerRemoved(containerName)) {
            runQuietDockerCommand(List.of("docker", "rm", "-f", containerName), 5);
        }
    }

    /**
     * Returns {@code true} strictly when {@code docker inspect <containerName>} confirms the container no longer exists.
     */
    public boolean verifyContainerRemoved(String containerName) {
        try {
            Process p = new ProcessBuilder("docker", "inspect", containerName)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() != 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private void runQuietDockerCommand(List<String> args, int timeoutSeconds) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }
}
