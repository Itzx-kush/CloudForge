package com.cloudforge.backend.security;

import com.cloudforge.backend.entity.UserEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated user principal stored in the server-side HTTP session.
 * Intentionally holds only safe profile metadata — never GitHub access tokens or secrets.
 */
public final class CloudForgeUserPrincipal implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final Long githubUserId;
    private final String username;
    private final String displayName;
    private final String email;
    private final String avatarUrl;
    private final String profileUrl;

    public CloudForgeUserPrincipal(
            UUID userId,
            Long githubUserId,
            String username,
            String displayName,
            String email,
            String avatarUrl,
            String profileUrl
    ) {
        this.userId = userId;
        this.githubUserId = githubUserId;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.profileUrl = profileUrl;
    }

    public static CloudForgeUserPrincipal fromEntity(UserEntity entity) {
        return new CloudForgeUserPrincipal(
                entity.getId(),
                entity.getGithubUserId(),
                entity.getUsername(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getAvatarUrl(),
                entity.getProfileUrl()
        );
    }

    public UUID getUserId() {
        return userId;
    }

    public Long getGithubUserId() {
        return githubUserId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getProfileUrl() {
        return profileUrl;
    }

    @Override
    public Map<String, Object> getAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", githubUserId);
        attributes.put("login", username);
        attributes.put("name", displayName);
        if (email != null) {
            attributes.put("email", email);
        }
        if (avatarUrl != null) {
            attributes.put("avatar_url", avatarUrl);
        }
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
