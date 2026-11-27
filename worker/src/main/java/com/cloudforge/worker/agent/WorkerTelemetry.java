package com.cloudforge.worker.agent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Locale;
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
            Files.createDirectories(directory);
            pending = Files.createTempFile(directory, ".worker-", ".tmp");
            String payload = String.format(
                    Locale.ROOT,
                    """
                    cloudforge_worker_last_heartbeat_seconds %d
                    cloudforge_worker_active_assignments %d
                    cloudforge_worker_heartbeat_failures_total %d
                    cloudforge_worker_assignments_total %d
                    cloudforge_worker_assignment_seconds_total %.6f
                    """,
                    lastSuccess, active, failures, assignments.sum(), durationNanos.sum() / 1e9
            );
            Files.writeString(pending, payload);
            try {
                Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems (e.g., Windows NTFS) do not support POSIX permission bits.
            }
            Path target = directory.resolve("cloudforge.prom");
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
            pending = null;
        } catch (IOException ex) {
            LoggerFactory.getLogger(WorkerTelemetry.class).debug("Worker metric snapshot unavailable: {}", ex.getMessage());
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); } catch (IOException ignored) { }
            }
        }
    }
}
