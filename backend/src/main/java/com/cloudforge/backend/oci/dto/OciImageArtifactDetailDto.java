package com.cloudforge.backend.oci.dto;

import java.util.List;

public record OciImageArtifactDetailDto(
        OciImageArtifactDto artifact,
        OciBuildRecordDto buildRecord,
        List<OciPublishAttemptDto> publishAttempts,
        List<OciArtifactEventDto> events
) {
}
