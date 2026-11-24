package com.cloudforge.worker.executor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Execution-plane adapter boundary separating the worker agent loop from the underlying
 * isolated runtime (e.g. hardened local Docker containers or test doubles).
 */
public interface IsolatedExecutionAdapter {

    String adapterType();

    boolean isAvailable();

    ExecutionResult execute(
            ExecutionRequest request,
            AtomicBoolean cancelSignal,
            LogLineConsumer logConsumer
    );

    @FunctionalInterface
    interface LogLineConsumer {
        void accept(String streamType, String message, Instant occurredAt);
    }

    record ExecutionRequest(
            UUID attemptId,
            UUID pipelineRunId,
            UUID stageRunId,
            String stageKey,
            String allowedOperation,
            int attemptNumber,
            int timeoutSeconds,
            int maxLogLines,
            int maxLogBytes
    ) {
    }

    record ExecutionResult(
            String resultStatus,
            String failureCategory,
            Integer exitCode,
            String summary,
            Map<String, Object> resourceUsage,
            Instant startedAt,
            Instant completedAt,
            boolean terminationConfirmed
    ) {
    }
}
