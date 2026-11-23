package com.cloudforge.backend.controller;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.AuthConfigResponse;
import com.cloudforge.backend.dto.AuthMeResponse;
import com.cloudforge.backend.dto.CsrfTokenResponse;
import com.cloudforge.backend.dto.LogoutResponse;
import com.cloudforge.backend.dto.UserProfileDto;
import com.cloudforge.backend.dto.WorkspaceSummaryDto;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppProperties appProperties;
    private final CurrentUserResolver currentUserResolver;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final AuditService auditService;

    public AuthController(
            AppProperties appProperties,
            CurrentUserResolver currentUserResolver,
            WorkspaceMemberRepository workspaceMemberRepository,
            ProjectRepository projectRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            AuditService auditService
    ) {
        this.appProperties = appProperties;
        this.currentUserResolver = currentUserResolver;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.projectRepository = projectRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.auditService = auditService;
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthConfigResponse> getAuthConfig() {
        boolean githubAppConfigured = appProperties.getGithubApp().isConfigured();
        String slug = githubAppConfigured ? appProperties.getGithubApp().getSlug().trim() : null;
        return ResponseEntity.ok(new AuthConfigResponse(
                appProperties.getAuth().isGithubOAuthConfigured(),
                githubAppConfigured,
                slug,
                "/oauth2/authorization/github"
        ));
    }

    @GetMapping(value = "/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CsrfTokenResponse> getCsrfToken(CsrfToken csrfToken) {
        return ResponseEntity.ok(new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        ));
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<AuthMeResponse> getCurrentUser(Authentication authentication) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        List<WorkspaceMemberEntity> memberships = workspaceMemberRepository.findAllByUserIdWithWorkspace(user.getId());

        List<WorkspaceSummaryDto> workspaces = memberships.stream()
                .map(membership -> {
                    WorkspaceEntity ws = membership.getWorkspace();
                    return new WorkspaceSummaryDto(
                            ws.getId(),
                            ws.getName(),
                            ws.getSlug(),
                            ws.getDescription(),
                            ws.isPersonal(),
                            membership.getRole(),
                            workspaceMemberRepository.countByWorkspaceId(ws.getId()),
                            projectRepository.countByWorkspaceId(ws.getId()),
                            linkedRepositoryRepository.countByWorkspaceIdAndConnectionStatus(
                                    ws.getId(),
                                    RepositoryConnectionStatus.CONNECTED
                            ),
                            ws.getCreatedAt(),
                            ws.getUpdatedAt()
                    );
                })
                .toList();

        UserProfileDto profile = new UserProfileDto(
                user.getId(),
                user.getGithubUserId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getProfileUrl(),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthMeResponse(profile, workspaces));
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<LogoutResponse> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                UserEntity user = currentUserResolver.requireUserEntity(authentication);
                List<WorkspaceMemberEntity> memberships = workspaceMemberRepository.findAllByUserIdWithWorkspace(user.getId());
                WorkspaceEntity auditWorkspace = memberships.isEmpty() ? null : memberships.get(0).getWorkspace();
                auditService.record(
                        auditWorkspace,
                        user,
                        "USER_LOGOUT",
                        "USER",
                        user.getId().toString(),
                        "Signed out of CloudForge session",
                        Map.of("username", user.getUsername())
                );
            } catch (Exception ignored) {
                // Proceed with logout even if user record lookup fails
            }
        }

        SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
        logoutHandler.setInvalidateHttpSession(true);
        logoutHandler.setClearAuthentication(true);
        logoutHandler.logout(request, response, authentication);

        boolean secure = appProperties.getAuth().isCookieSecure();
        ResponseCookie expiredSession = ResponseCookie.from("CLOUDFORGE_SESSION", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        ResponseCookie expiredCsrf = ResponseCookie.from("XSRF-TOKEN", "")
                .httpOnly(false)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, expiredSession.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCsrf.toString());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new LogoutResponse(true, "Session terminated successfully"));
    }
}
