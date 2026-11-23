package com.cloudforge.backend.security;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.exception.ApiException;
import com.cloudforge.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserResolver {

    private final UserRepository userRepository;

    public CurrentUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public CloudForgeUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        if (authentication.getPrincipal() instanceof CloudForgeUserPrincipal principal) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Unsupported authentication principal");
    }

    public UserEntity requireUserEntity(Authentication authentication) {
        CloudForgeUserPrincipal principal = requirePrincipal(authentication);
        return userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authenticated user account no longer exists"));
    }
}
