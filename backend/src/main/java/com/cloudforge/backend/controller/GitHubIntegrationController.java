package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.GitHubInstallUrlResponse;
import com.cloudforge.backend.dto.GitHubInstallationsResponse;
import com.cloudforge.backend.dto.GitHubSyncResponse;
import com.cloudforge.backend.dto.LinkedRepositoryDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.exception.ApiException;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.security.RedirectOriginValidator;
import com.cloudforge.backend.service.GitHubIntegrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class GitHubIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(GitHubIntegrationController.class);

    private final GitHubIntegrationService gitHubIntegrationService;
    private final CurrentUserResolver currentUserResolver;
    private final RedirectOriginValidator redirectOriginValidator;

    public GitHubIntegrationController(
            GitHubIntegrationService gitHubIntegrationService,
            CurrentUserResolver currentUserResolver,
            RedirectOriginValidator redirectOriginValidator
    ) {
        this.gitHubIntegrationService = gitHubIntegrationService;
        this.currentUserResolver = currentUserResolver;
        this.redirectOriginValidator = redirectOriginValidator;
    }

    @GetMapping(value = "/workspaces/{workspaceId}/github/installations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GitHubInstallationsResponse> listInstallations(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(gitHubIntegrationService.listInstallations(workspaceId, user));
    }

    @PostMapping(value = "/workspaces/{workspaceId}/github/install-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GitHubInstallUrlResponse> createInstallUrl(
            @PathVariable UUID workspaceId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(
                gitHubIntegrationService.createInstallationUrl(workspaceId, user, request.getSession(true))
        );
    }

    @GetMapping("/github/app/callback")
    public void handleAppCallback(
            @RequestParam(name = "installation_id", required = false) Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(name = "state", required = false) String state,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if ("request".equalsIgnoreCase(setupAction)) {
            response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl(
                    "/repositories?installation=pending_approval"
            ));
            return;
        }

        try {
            UserEntity user = currentUserResolver.requireUserEntity(authentication);
            gitHubIntegrationService.completeInstallationCallback(
                    installationId,
                    setupAction,
                    state,
                    user,
                    request.getSession(false)
            );
            response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl(
                    "/repositories?installation=connected"
            ));
        } catch (ApiException ex) {
            log.warn("GitHub App callback rejected with HTTP {}: {}", ex.getStatus().value(), ex.getMessage());
            String code = URLEncoder.encode(ex.getStatus().name().toLowerCase(), StandardCharsets.UTF_8);
            response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl(
                    "/repositories?installation_error=" + code
            ));
        } catch (Exception ex) {
            log.error("Unexpected failure processing GitHub App installation callback", ex);
            response.sendRedirect(redirectOriginValidator.buildSafeFrontendUrl(
                    "/repositories?installation_error=callback_failed"
            ));
        }
    }

    @PostMapping(
            value = "/workspaces/{workspaceId}/github/installations/{installationId}/sync",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<GitHubSyncResponse> syncInstallation(
            @PathVariable UUID workspaceId,
            @PathVariable UUID installationId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(gitHubIntegrationService.syncInstallation(workspaceId, installationId, user));
    }

    @DeleteMapping("/workspaces/{workspaceId}/github/installations/{installationId}")
    public ResponseEntity<Void> disconnectInstallation(
            @PathVariable UUID workspaceId,
            @PathVariable UUID installationId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        gitHubIntegrationService.disconnectInstallation(workspaceId, installationId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/workspaces/{workspaceId}/repositories", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<LinkedRepositoryDto>> listRepositories(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "visibility", required = false) String visibility,
            @RequestParam(name = "status", required = false) RepositoryConnectionStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "fullName"));
        return ResponseEntity.ok(
                gitHubIntegrationService.searchRepositories(workspaceId, query, visibility, status, pageable, user)
        );
    }

    @GetMapping(value = "/workspaces/{workspaceId}/repositories/{repositoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkedRepositoryDto> getRepository(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(gitHubIntegrationService.getRepository(workspaceId, repositoryId, user));
    }
}
