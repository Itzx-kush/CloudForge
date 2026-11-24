package com.cloudforge.backend.oci.dto;

import java.util.List;
import java.util.UUID;

public record PipelineRunArtifactsResponse(
        UUID pipelineRunId,
        List<OciBuildRecordDto> builds,
        List<OciPublishAttemptDto> publishAttempts,
        List<OciImageArtifactDto> artifacts,
        List<OciArtifactEventDto> events
) {
}
