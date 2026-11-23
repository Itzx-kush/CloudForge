package com.cloudforge.backend.support;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.security.CloudForgeUserPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

public final class TestAuthSupport {

    private TestAuthSupport() {
    }

    public static RequestPostProcessor authenticatedUser(UserEntity user) {
        CloudForgeUserPrincipal principal = CloudForgeUserPrincipal.fromEntity(user);
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                "github"
        );
        return authentication(token);
    }
}
