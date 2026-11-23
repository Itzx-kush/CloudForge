package com.cloudforge.backend.dto;

public record LogoutResponse(
        boolean loggedOut,
        String message
) {
}
