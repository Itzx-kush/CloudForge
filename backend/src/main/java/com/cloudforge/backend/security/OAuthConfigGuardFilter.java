package com.cloudforge.backend.security;

import com.cloudforge.backend.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Prevents confusing Spring Security 500/404 errors when GitHub OAuth env vars
 * have not yet been configured in a fresh local environment.
 */
public class OAuthConfigGuardFilter extends OncePerRequestFilter {

    private final AppProperties appProperties;
    private final RedirectOriginValidator redirectOriginValidator;

    public OAuthConfigGuardFilter(
            AppProperties appProperties,
            RedirectOriginValidator redirectOriginValidator
    ) {
        this.appProperties = appProperties;
        this.redirectOriginValidator = redirectOriginValidator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!appProperties.getAuth().isGithubOAuthConfigured()
                && (uri.startsWith("/oauth2/authorization/") || uri.startsWith("/login/oauth2/code/"))) {
            response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl("/login?error=oauth_not_configured"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
