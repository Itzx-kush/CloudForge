package com.cloudforge.backend.security;

import com.cloudforge.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
public class GitHubOAuthRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthRegistrationConfig.class);

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(AppProperties appProperties) {
        if (!appProperties.getAuth().isGithubOAuthConfigured()) {
            log.info("GitHub OAuth credentials are not configured; OAuth login initiation will redirect to setup state.");
            return registrationId -> null;
        }

        AppProperties.GitHubOAuth oauth = appProperties.getAuth().getGithubOauth();
        ClientRegistration github = CommonOAuth2Provider.GITHUB.getBuilder("github")
                .clientId(oauth.getClientId().trim())
                .clientSecret(oauth.getClientSecret().trim())
                .redirectUri(oauth.getRedirectUri())
                // Least-privilege identity scopes only; repository access uses the dedicated GitHub App.
                .scope("read:user", "user:email")
                .build();

        log.info("Registered GitHub OAuth 2.0 client (scopes: read:user, user:email)");
        return new InMemoryClientRegistrationRepository(github);
    }
}
