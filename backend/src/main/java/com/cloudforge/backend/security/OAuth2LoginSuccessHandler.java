package com.cloudforge.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    public static final String POST_LOGIN_REDIRECT_ATTR = "CLOUDFORGE_POST_LOGIN_REDIRECT";
    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final RedirectOriginValidator redirectOriginValidator;

    public OAuth2LoginSuccessHandler(RedirectOriginValidator redirectOriginValidator) {
        this.redirectOriginValidator = redirectOriginValidator;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        String requestedTarget = null;
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object attr = session.getAttribute(POST_LOGIN_REDIRECT_ATTR);
            if (attr instanceof String str) {
                requestedTarget = str;
            }
            session.removeAttribute(POST_LOGIN_REDIRECT_ATTR);
        }

        String safeTarget = redirectOriginValidator.validateOrDefault(requestedTarget, "/");
        log.info("GitHub OAuth login succeeded; redirecting user to validated frontend destination");
        response.sendRedirect(safeTarget);
    }
}
