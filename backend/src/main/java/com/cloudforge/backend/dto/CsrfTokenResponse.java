package com.cloudforge.backend.dto;

public record CsrfTokenResponse(
        String headerName,
        String parameterName,
        String token
) {
}
