package com.cloudforge.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final RedirectOriginValidator redirectOriginValidator;

    public OAuth2LoginFailureHandler(RedirectOriginValidator redirectOriginValidator) {
        this.redirectOriginValidator = redirectOriginValidator;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        String errorCode = "oauth_failed";
        if (exception instanceof OAuth2AuthenticationException oauthEx && oauthEx.getError() != null) {
            String rawCode = oauthEx.getError().getErrorCode();
            if ("access_denied".equalsIgnoreCase(rawCode)) {
                errorCode = "access_denied";
            } else if ("invalid_state_parameter".equalsIgnoreCase(rawCode)
                    || "authorization_request_not_found".equalsIgnoreCase(rawCode)) {
                errorCode = "invalid_state";
            }
        }

        log.warn("GitHub OAuth login failed code={}", errorCode);
        String encoded = URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
        response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl("/login?error=" + encoded));
    }
}
