package com.cloudforge.backend.security;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class CloudForgeOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CloudForgeOAuth2UserService.class);
    private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";

    private final UserAccountService userAccountService;
    private final RestClient restClient;

    public CloudForgeOAuth2UserService(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        return processGitHubUser(oauth2User, userRequest.getAccessToken().getTokenValue());
    }

    CloudForgeUserPrincipal processGitHubUser(OAuth2User oauth2User, String transientAccessToken) {
        Map<String, Object> attributes = oauth2User.getAttributes();

        Long githubUserId = extractLong(attributes.get("id"));
        String username = extractString(attributes.get("login"));

        if (githubUserId == null || !StringUtils.hasText(username)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_user_info", "GitHub profile is missing required id or login attributes", null)
            );
        }

        String displayName = extractString(attributes.get("name"));
        String email = extractString(attributes.get("email"));
        String avatarUrl = extractString(attributes.get("avatar_url"));
        String profileUrl = extractString(attributes.get("html_url"));

        if (!StringUtils.hasText(email) && StringUtils.hasText(transientAccessToken)) {
            email = fetchPrimaryVerifiedEmail(transientAccessToken);
        }

        UserEntity persisted = userAccountService.upsertFromGitHubOAuth(
                githubUserId,
                username,
                displayName,
                email,
                avatarUrl,
                profileUrl
        );

        return CloudForgeUserPrincipal.fromEntity(persisted);
    }

    private String fetchPrimaryVerifiedEmail(String transientAccessToken) {
        try {
            List<Map<String, Object>> emails = restClient.get()
                    .uri(GITHUB_EMAILS_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + transientAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (emails == null || emails.isEmpty()) {
                return null;
            }

            return emails.stream()
                    .filter(item -> Boolean.TRUE.equals(item.get("primary")) && Boolean.TRUE.equals(item.get("verified")))
                    .map(item -> extractString(item.get("email")))
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElseGet(() -> emails.stream()
                            .filter(item -> Boolean.TRUE.equals(item.get("verified")))
                            .map(item -> extractString(item.get("email")))
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElse(null));
        } catch (Exception ex) {
            // Never log access tokens; gracefully proceed without email if scope or visibility prevents reading it
            log.debug("Could not retrieve GitHub primary email during OAuth login: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private Long extractLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractString(Object value) {
        return value instanceof String str && StringUtils.hasText(str) ? str.trim() : null;
    }
}
