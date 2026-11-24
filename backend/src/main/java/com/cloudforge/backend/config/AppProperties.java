package com.cloudforge.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "cloudforge")
public class AppProperties {

    @NotBlank
    private String name = "CloudForge Backend";

    @NotBlank
    private String version = "0.2.0-phase2";

    @NotBlank
    private String phase = "Phase 2 — Identity, GitHub Integration & Persistent Workspaces";

    @Min(1)
    @Max(30)
    private int databaseCheckTimeoutSeconds = 3;

    private final Cors cors = new Cors();
    private final Auth auth = new Auth();
    private final GitHubApp githubApp = new GitHubApp();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public int getDatabaseCheckTimeoutSeconds() {
        return databaseCheckTimeoutSeconds;
    }

    public void setDatabaseCheckTimeoutSeconds(int databaseCheckTimeoutSeconds) {
        this.databaseCheckTimeoutSeconds = databaseCheckTimeoutSeconds;
    }

    public Cors getCors() {
        return cors;
    }

    public Auth getAuth() {
        return auth;
    }

    public GitHubApp getGithubApp() {
        return githubApp;
    }

    public static boolean isNonPlaceholder(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return !normalized.startsWith("your_")
                && !normalized.startsWith("replace_")
                && !normalized.startsWith("<")
                && !normalized.equals("changeme");
    }

    public static class Cors {

        @NotEmpty
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Auth {

        @NotBlank
        private String frontendBaseUrl = "http://localhost:5173";

        @NotEmpty
        private List<String> allowedRedirectOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");

        private boolean cookieSecure = false;

        private final GitHubOAuth githubOauth = new GitHubOAuth();

        public String getFrontendBaseUrl() {
            return frontendBaseUrl;
        }

        public void setFrontendBaseUrl(String frontendBaseUrl) {
            this.frontendBaseUrl = frontendBaseUrl;
        }

        public List<String> getAllowedRedirectOrigins() {
            return allowedRedirectOrigins;
        }

        public void setAllowedRedirectOrigins(List<String> allowedRedirectOrigins) {
            this.allowedRedirectOrigins = allowedRedirectOrigins;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public GitHubOAuth getGithubOauth() {
            return githubOauth;
        }

        public boolean isGithubOAuthConfigured() {
            return isNonPlaceholder(githubOauth.getClientId())
                    && isNonPlaceholder(githubOauth.getClientSecret());
        }
    }

    public static class GitHubOAuth {

        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "{baseUrl}/login/oauth2/code/{registrationId}";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }
    }

    public static class GitHubApp {

        private String appId = "";
        private String slug = "";
        private String privateKey = "";
        private String privateKeyPath = "";
        private String webhookSecret = "";
        private String apiBaseUrl = "https://api.github.com";
        private String installationCallbackUrl = "http://localhost:5173/api/github/app/callback";

        @Min(1)
        @Max(30)
        private int apiConnectTimeoutSeconds = 5;

        @Min(1)
        @Max(60)
        private int apiReadTimeoutSeconds = 10;

        @Min(1)
        @Max(20)
        private int actionsSyncMaxWorkflowPages = 5;

        @Min(1)
        @Max(200)
        private int actionsSyncMaxRunsPerRepo = 50;

        @Min(1)
        @Max(100)
        private int actionsSyncPerPage = 50;

        @Min(1)
        @Max(10)
        private int actionsSyncMaxJobPages = 3;

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getInstallationCallbackUrl() {
            return installationCallbackUrl;
        }

        public void setInstallationCallbackUrl(String installationCallbackUrl) {
            this.installationCallbackUrl = installationCallbackUrl;
        }

        public int getApiConnectTimeoutSeconds() {
            return apiConnectTimeoutSeconds;
        }

        public void setApiConnectTimeoutSeconds(int apiConnectTimeoutSeconds) {
            this.apiConnectTimeoutSeconds = apiConnectTimeoutSeconds;
        }

        public int getApiReadTimeoutSeconds() {
            return apiReadTimeoutSeconds;
        }

        public void setApiReadTimeoutSeconds(int apiReadTimeoutSeconds) {
            this.apiReadTimeoutSeconds = apiReadTimeoutSeconds;
        }

        public int getActionsSyncMaxWorkflowPages() {
            return actionsSyncMaxWorkflowPages;
        }

        public void setActionsSyncMaxWorkflowPages(int actionsSyncMaxWorkflowPages) {
            this.actionsSyncMaxWorkflowPages = actionsSyncMaxWorkflowPages;
        }

        public int getActionsSyncMaxRunsPerRepo() {
            return actionsSyncMaxRunsPerRepo;
        }

        public void setActionsSyncMaxRunsPerRepo(int actionsSyncMaxRunsPerRepo) {
            this.actionsSyncMaxRunsPerRepo = actionsSyncMaxRunsPerRepo;
        }

        public int getActionsSyncPerPage() {
            return actionsSyncPerPage;
        }

        public void setActionsSyncPerPage(int actionsSyncPerPage) {
            this.actionsSyncPerPage = actionsSyncPerPage;
        }

        public int getActionsSyncMaxJobPages() {
            return actionsSyncMaxJobPages;
        }

        public void setActionsSyncMaxJobPages(int actionsSyncMaxJobPages) {
            this.actionsSyncMaxJobPages = actionsSyncMaxJobPages;
        }

        public boolean hasPrivateKeyMaterial() {
            if (isNonPlaceholder(privateKey)) {
                return true;
            }
            if (isNonPlaceholder(privateKeyPath)) {
                try {
                    return Files.isRegularFile(Path.of(privateKeyPath.trim()));
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        }

        public boolean isConfigured() {
            return isNonPlaceholder(appId)
                    && isNonPlaceholder(slug)
                    && hasPrivateKeyMaterial();
        }

        public boolean isWebhookSecretConfigured() {
            return isNonPlaceholder(webhookSecret);
        }
    }
}
