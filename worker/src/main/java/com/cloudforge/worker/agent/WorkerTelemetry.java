package com.cloudforge.worker.agent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.LoggerFactory;

/** A single local worker publishes one atomic snapshot per heartbeat. */
final class WorkerTelemetry {
    private final Path directory;
    private final LongAdder assignments = new LongAdder();
    private final LongAdder durationNanos = new LongAdder();
    private long lastSuccess;
    private long failures;

    WorkerTelemetry(String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory);
    }

    void completed(long elapsedNanos) {
        assignments.increment();
        durationNanos.add(elapsedNanos);
    }

    synchronized void heartbeat(boolean successful, int active) {
        if (directory == null) return;
        if (successful) lastSuccess = Instant.now().getEpochSecond();
        else failures++;
        Path pending = null;
        try {
            pending = Files.createTempFile(directory, ".worker-", ".tmp");
            Files.writeString(pending, """
                    cloudforge_worker_last_heartbeat_seconds %d
                    cloudforge_worker_active_assignments %d
                    cloudforge_worker_heartbeat_failures_total %d
                    cloudforge_worker_assignments_total %d
                    cloudforge_worker_assignment_seconds_total %s
                    """.formatted(lastSuccess, active, failures, assignments.sum(), durationNanos.sum() / 1e9));
            Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            Files.move(pending, directory.resolve("cloudforge.prom"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UnsupportedOperationException ex) {
            LoggerFactory.getLogger(WorkerTelemetry.class).debug("Worker metric snapshot unavailable");
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); } catch (IOException ignored) { }
            }
        }
    }
}
