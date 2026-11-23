package com.cloudforge.backend.dto;

import java.util.List;

public record AuthMeResponse(
        UserProfileDto user,
        List<WorkspaceSummaryDto> workspaces
) {
}
