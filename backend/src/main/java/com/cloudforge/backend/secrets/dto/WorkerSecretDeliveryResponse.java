package com.cloudforge.backend.secrets.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkerSecretDeliveryResponse(
        UUID attemptId,
        UUID pipelineRunId,
        UUID stageRunId,
        long fencingGeneration,
        Instant deliveredAt,
        List<DeliveredSecretEntry> secrets
) {
    @Override
    public String toString() {
        return "WorkerSecretDeliveryResponse[attemptId=" + attemptId
                + ", pipelineRunId=" + pipelineRunId
                + ", stageRunId=" + stageRunId
                + ", count=" + (secrets == null ? 0 : secrets.size())
                + ", secrets=[REDACTED]]";
    }

    public record DeliveredSecretEntry(
            String alias,
            String secretName,
            UUID secretId,
            UUID secretVersionId,
            int versionNumber,
            String value
    ) {
        @Override
        public String toString() {
            return "DeliveredSecretEntry[alias=" + alias
                    + ", secretId=" + secretId
                    + ", versionNumber=" + versionNumber
                    + ", value=[REDACTED]]";
        }
    }
}
