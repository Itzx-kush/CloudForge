package com.cloudforge.backend.service;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.dto.GitHubInstallUrlResponse;
import com.cloudforge.backend.dto.GitHubInstallationDto;
import com.cloudforge.backend.dto.GitHubInstallationsResponse;
import com.cloudforge.backend.dto.GitHubSyncResponse;
import com.cloudforge.backend.dto.LinkedRepositoryDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.GitHubInstallationEntity;
import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.github.GitHubApiClient;
import com.cloudforge.backend.github.GitHubAppTokenService;
import com.cloudforge.backend.github.GitHubInstallationDetails;
import com.cloudforge.backend.github.GitHubInstallationRevokedException;
import com.cloudforge.backend.github.GitHubInstallationSuspendedException;
import com.cloudforge.backend.github.GitHubRepositoryMetadata;
import com.cloudforge.backend.repository.GitHubInstallationRepository;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serial;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GitHubIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GitHubIntegrationService.class);

    public static final String PENDING_INSTALL_STATE_ATTR = "CLOUDFORGE_GH_APP_INSTALL_STATE";
    private static final Duration STATE_TTL = Duration.ofMinutes(15);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppProperties appProperties;
    private final WorkspaceAuthorizationService authorizationService;
    private final GitHubInstallationRepository installationRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final GitHubApiClient gitHubApiClient;
    private final GitHubAppTokenService tokenService;
    private final AuditService auditService;

    public GitHubIntegrationService(
            AppProperties appProperties,
            WorkspaceAuthorizationService authorizationService,
            GitHubInstallationRepository installationRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            GitHubApiClient gitHubApiClient,
            GitHubAppTokenService tokenService,
            AuditService auditService
    ) {
        this.appProperties = appProperties;
        this.authorizationService = authorizationService;
        this.installationRepository = installationRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.gitHubApiClient = gitHubApiClient;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public GitHubInstallationsResponse listInstallations(UUID workspaceId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        boolean configured = appProperties.getGithubApp().isConfigured();
        String slug = configured ? appProperties.getGithubApp().getSlug().trim() : null;
        List<GitHubInstallationDto> items = installationRepository.findAllByWorkspaceId(workspaceId)
                .stream()
                .map(this::toInstallationDto)
                .toList();
        return new GitHubInstallationsResponse(configured, slug, items);
    }

    @Transactional(readOnly = true)
    public GitHubInstallUrlResponse createInstallationUrl(UUID workspaceId, UserEntity user, HttpSession session) {
        authorizationService.requireOwner(workspaceId, user.getId());
        if (!appProperties.getGithubApp().isConfigured()) {
            throw new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App integration is not configured. Configure GITHUB_APP_ID, GITHUB_APP_SLUG, and GITHUB_APP_PRIVATE_KEY first."
            );
        }

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(STATE_TTL);

        session.setAttribute(
                PENDING_INSTALL_STATE_ATTR,
                new PendingInstallationState(nonce, workspaceId, user.getId(), expiresAt)
        );

        String slug = appProperties.getGithubApp().getSlug().trim();
        String installUrl = "https://github.com/apps/"
                + URLEncoder.encode(slug, StandardCharsets.UTF_8)
                + "/installations/new?state="
                + URLEncoder.encode(nonce, StandardCharsets.UTF_8);

        return new GitHubInstallUrlResponse(installUrl, expiresAt);
    }

    @Transactional
    public GitHubInstallationDto completeInstallationCallback(
            Long githubInstallationId,
            String setupAction,
            String state,
            UserEntity user,
            HttpSession session
    ) {
        PendingInstallationState pending = consumeAndValidateState(state, user, session);
        WorkspaceMemberEntity ownerMembership = authorizationService.requireOwner(pending.workspaceId(), user.getId());
        WorkspaceEntity workspace = ownerMembership.getWorkspace();

        if (githubInstallationId == null || githubInstallationId <= 0) {
            throw new BadRequestException("Missing or invalid GitHub installation_id");
        }

        GitHubInstallationDetails details = gitHubApiClient.getInstallation(githubInstallationId);

        // Ownership validation: prevent cross-workspace hijacking or attaching unauthorized installations.
        GitHubInstallationEntity existing = installationRepository.findByGithubInstallationId(githubInstallationId).orElse(null);
        if (existing != null && !existing.getWorkspace().getId().equals(workspace.getId())) {
            throw new ConflictException(
                    "This GitHub App installation is already connected to another CloudForge workspace"
            );
        }
        verifyInstallationAccountAuthorization(details, existing, user);

        GitHubInstallationEntity entity = existing != null ? existing : new GitHubInstallationEntity();
        entity.setWorkspace(workspace);
        entity.setGithubInstallationId(details.installationId());
        entity.setAccountLogin(details.accountLogin());
        entity.setAccountType(details.accountType());
        entity.setAccountAvatarUrl(details.accountAvatarUrl());
        entity.setRepositorySelection(details.repositorySelection());
        entity.setStatus(details.suspended() ? InstallationStatus.SUSPENDED : InstallationStatus.ACTIVE);
        entity.setInstalledBy(user);

        GitHubInstallationEntity saved = installationRepository.save(entity);

        if (saved.getStatus() == InstallationStatus.ACTIVE) {
            syncInstallationRepositoriesInternal(saved);
        } else {
            markInstallationRepositoriesStatus(saved, RepositoryConnectionStatus.SUSPENDED);
        }

        auditService.record(
                workspace,
                user,
                "GITHUB_INSTALLATION_LINKED",
                "GITHUB_INSTALLATION",
                saved.getId().toString(),
                "Connected GitHub App installation #" + saved.getGithubInstallationId()
                        + " (" + saved.getAccountLogin() + ") via " + (setupAction != null ? setupAction : "install"),
                Map.of(
                        "githubInstallationId", saved.getGithubInstallationId(),
                        "accountLogin", saved.getAccountLogin(),
                        "accountType", saved.getAccountType(),
                        "status", saved.getStatus().name(),
                        "stateParameterProvided", state != null
                )
        );

        return toInstallationDto(saved);
    }

    @Transactional
    public GitHubSyncResponse syncInstallation(UUID workspaceId, UUID installationUuid, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        GitHubInstallationEntity installation = installationRepository.findByIdAndWorkspaceId(installationUuid, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("GitHub App installation not found in this workspace"));

        if (!appProperties.getGithubApp().isConfigured()) {
            throw new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App integration is not configured on this server"
            );
        }

        Instant syncedAt = Instant.now();
        try {
            GitHubInstallationDetails details = gitHubApiClient.getInstallation(installation.getGithubInstallationId());
            installation.setAccountLogin(details.accountLogin());
            installation.setAccountType(details.accountType());
            installation.setAccountAvatarUrl(details.accountAvatarUrl());
            installation.setRepositorySelection(details.repositorySelection());

            if (details.suspended()) {
                installation.setStatus(InstallationStatus.SUSPENDED);
                installation.setLastSyncedAt(syncedAt);
                installationRepository.save(installation);
                int updated = markInstallationRepositoriesStatus(installation, RepositoryConnectionStatus.SUSPENDED);
                tokenService.evictInstallationToken(installation.getGithubInstallationId());
                auditService.record(
                        membership.getWorkspace(),
                        user,
                        "GITHUB_INSTALLATION_SUSPENDED",
                        "GITHUB_INSTALLATION",
                        installation.getId().toString(),
                        "Detected suspended GitHub App installation #" + installation.getGithubInstallationId(),
                        Map.of("githubInstallationId", installation.getGithubInstallationId())
                );
                return new GitHubSyncResponse(
                        installation.getId(),
                        installation.getStatus(),
                        0,
                        updated,
                        syncedAt,
                        "Installation is suspended on GitHub; repositories marked SUSPENDED"
                );
            }

            installation.setStatus(InstallationStatus.ACTIVE);
            SyncCounts counts = syncInstallationRepositoriesInternal(installation);
            auditService.record(
                    membership.getWorkspace(),
                    user,
                    "GITHUB_INSTALLATION_SYNCED",
                    "GITHUB_INSTALLATION",
                    installation.getId().toString(),
                    "Synchronized " + counts.synced() + " repositories for @" + installation.getAccountLogin(),
                    Map.of(
                            "githubInstallationId", installation.getGithubInstallationId(),
                            "syncedCount", counts.synced(),
                            "disconnectedCount", counts.disconnected()
                    )
            );
            return new GitHubSyncResponse(
                    installation.getId(),
                    installation.getStatus(),
                    counts.synced(),
                    counts.disconnected(),
                    installation.getLastSyncedAt(),
                    "Synchronized " + counts.synced() + " authorized repositories"
            );
        } catch (GitHubInstallationRevokedException ex) {
            installation.setStatus(InstallationStatus.REVOKED);
            installation.setLastSyncedAt(syncedAt);
            installationRepository.save(installation);
            int disconnected = markInstallationRepositoriesStatus(installation, RepositoryConnectionStatus.DISCONNECTED);
            tokenService.evictInstallationToken(installation.getGithubInstallationId());
            auditService.record(
                    membership.getWorkspace(),
                    user,
                    "GITHUB_INSTALLATION_REVOKED",
                    "GITHUB_INSTALLATION",
                    installation.getId().toString(),
                    "GitHub App installation #" + installation.getGithubInstallationId() + " was revoked on GitHub",
                    Map.of("githubInstallationId", installation.getGithubInstallationId())
            );
            return new GitHubSyncResponse(
                    installation.getId(),
                    installation.getStatus(),
                    0,
                    disconnected,
                    syncedAt,
                    "Installation was revoked on GitHub; repositories marked DISCONNECTED"
            );
        } catch (GitHubInstallationSuspendedException ex) {
            installation.setStatus(InstallationStatus.SUSPENDED);
            installation.setLastSyncedAt(syncedAt);
            installationRepository.save(installation);
            int suspended = markInstallationRepositoriesStatus(installation, RepositoryConnectionStatus.SUSPENDED);
            tokenService.evictInstallationToken(installation.getGithubInstallationId());
            return new GitHubSyncResponse(
                    installation.getId(),
                    installation.getStatus(),
                    0,
                    suspended,
                    syncedAt,
                    "Installation is suspended on GitHub; repositories marked SUSPENDED"
            );
        }
    }

    @Transactional
    public void disconnectInstallation(UUID workspaceId, UUID installationUuid, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        GitHubInstallationEntity installation = installationRepository.findByIdAndWorkspaceId(installationUuid, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("GitHub App installation not found in this workspace"));

        installation.setStatus(InstallationStatus.REVOKED);
        installationRepository.save(installation);
        markInstallationRepositoriesStatus(installation, RepositoryConnectionStatus.DISCONNECTED);
        tokenService.evictInstallationToken(installation.getGithubInstallationId());

        auditService.record(
                membership.getWorkspace(),
                user,
                "GITHUB_INSTALLATION_DISCONNECTED",
                "GITHUB_INSTALLATION",
                installation.getId().toString(),
                "Disconnected GitHub App installation #" + installation.getGithubInstallationId()
                        + " (" + installation.getAccountLogin() + ")",
                Map.of(
                        "githubInstallationId", installation.getGithubInstallationId(),
                        "accountLogin", installation.getAccountLogin()
                )
        );
    }

    @Transactional(readOnly = true)
    public PagedResponse<LinkedRepositoryDto> searchRepositories(
            UUID workspaceId,
            String query,
            String visibility,
            RepositoryConnectionStatus status,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : null;
        String normalizedVisibility = StringUtils.hasText(visibility) ? visibility.trim().toLowerCase() : null;
        Page<LinkedRepositoryEntity> page = linkedRepositoryRepository.searchByWorkspace(
                workspaceId,
                normalizedQuery,
                normalizedVisibility,
                status,
                pageable
        );
        return PagedResponse.from(page, this::toRepositoryDto);
    }

    @Transactional(readOnly = true)
    public LinkedRepositoryDto getRepository(UUID workspaceId, UUID repositoryId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        LinkedRepositoryEntity repo = linkedRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found in this workspace"));
        return toRepositoryDto(repo);
    }

    private SyncCounts syncInstallationRepositoriesInternal(GitHubInstallationEntity installation) {
        List<GitHubRepositoryMetadata> remoteRepos =
                gitHubApiClient.listAllInstallationRepositories(installation.getGithubInstallationId());

        Instant now = Instant.now();
        List<LinkedRepositoryEntity> existingRepos =
                linkedRepositoryRepository.findAllByInstallationId(installation.getId());
        Map<Long, LinkedRepositoryEntity> byGithubId = new HashMap<>();
        for (LinkedRepositoryEntity existing : existingRepos) {
            byGithubId.put(existing.getGithubRepoId(), existing);
        }

        Set<Long> seenIds = new HashSet<>();
        int synced = 0;
        for (GitHubRepositoryMetadata remote : remoteRepos) {
            seenIds.add(remote.githubRepoId());
            LinkedRepositoryEntity entity = byGithubId.get(remote.githubRepoId());
            if (entity == null) {
                entity = linkedRepositoryRepository
                        .findByWorkspaceIdAndGithubRepoId(installation.getWorkspace().getId(), remote.githubRepoId())
                        .orElseGet(LinkedRepositoryEntity::new);
            }
            entity.setWorkspace(installation.getWorkspace());
            entity.setInstallation(installation);
            entity.setGithubRepoId(remote.githubRepoId());
            entity.setOwnerLogin(remote.ownerLogin());
            entity.setName(remote.name());
            entity.setFullName(remote.fullName());
            entity.setDefaultBranch(StringUtils.hasText(remote.defaultBranch()) ? remote.defaultBranch() : "main");
            entity.setVisibility(StringUtils.hasText(remote.visibility()) ? remote.visibility() : "private");
            entity.setHtmlUrl(remote.htmlUrl());
            entity.setDescription(remote.description());
            entity.setArchived(remote.archived());
            entity.setConnectionStatus(RepositoryConnectionStatus.CONNECTED);
            entity.setGithubUpdatedAt(remote.updatedAt());
            entity.setLastSyncedAt(now);
            linkedRepositoryRepository.save(entity);
            synced++;
        }

        int disconnected = 0;
        for (LinkedRepositoryEntity existing : existingRepos) {
            if (!seenIds.contains(existing.getGithubRepoId())
                    && existing.getConnectionStatus() != RepositoryConnectionStatus.DISCONNECTED) {
                existing.setConnectionStatus(RepositoryConnectionStatus.DISCONNECTED);
                existing.setLastSyncedAt(now);
                linkedRepositoryRepository.save(existing);
                disconnected++;
            }
        }

        installation.setLastSyncedAt(now);
        installationRepository.save(installation);
        return new SyncCounts(synced, disconnected);
    }

    public int markInstallationRepositoriesStatus(
            GitHubInstallationEntity installation,
            RepositoryConnectionStatus status
    ) {
        List<LinkedRepositoryEntity> repos = linkedRepositoryRepository.findAllByInstallationId(installation.getId());
        Instant now = Instant.now();
        for (LinkedRepositoryEntity repo : repos) {
            repo.setConnectionStatus(status);
            repo.setLastSyncedAt(now);
            linkedRepositoryRepository.save(repo);
        }
        return repos.size();
    }

    private PendingInstallationState consumeAndValidateState(String state, UserEntity user, HttpSession session) {
        if (session == null) {
            throw new BadRequestException("Missing active session for GitHub App installation callback");
        }
        Object raw = session.getAttribute(PENDING_INSTALL_STATE_ATTR);
        // Single-use state: always clear immediately to prevent replay attacks
        session.removeAttribute(PENDING_INSTALL_STATE_ATTR);

        if (!(raw instanceof PendingInstallationState pending)) {
            throw new BadRequestException("No pending GitHub App installation request found for this session");
        }
        if (!pending.expiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("GitHub App installation state has expired; please initiate connection again");
        }
        if (!pending.userId().equals(user.getId())) {
            throw new ForbiddenOperationException("GitHub App installation state mismatch");
        }
        if (state != null) {
            boolean matches = StringUtils.hasText(state)
                    && MessageDigest.isEqual(
                            pending.nonce().getBytes(StandardCharsets.UTF_8),
                            state.getBytes(StandardCharsets.UTF_8)
                    );
            if (!matches) {
                throw new ForbiddenOperationException("GitHub App installation state mismatch");
            }
        } else {
            log.info(
                    "GitHub App callback arrived without state parameter; validated unexpired session-bound pending installation for workspaceId={}",
                    pending.workspaceId()
            );
        }
        return pending;
    }

    private void verifyInstallationAccountAuthorization(
            GitHubInstallationDetails details,
            GitHubInstallationEntity existingForWorkspace,
            UserEntity user
    ) {
        String accountType = details.accountType();
        if ("User".equalsIgnoreCase(accountType)) {
            boolean matches;
            if (details.accountId() > 0) {
                matches = details.accountId() == user.getGithubUserId();
            } else {
                matches = details.accountLogin() != null
                        && details.accountLogin().equalsIgnoreCase(user.getUsername());
            }
            if (!matches) {
                throw new ForbiddenOperationException(
                        "Personal GitHub App installations can only be linked by the matching GitHub account owner"
                );
            }
            return;
        }

        if ("Organization".equalsIgnoreCase(accountType)) {
            if (existingForWorkspace != null) {
                // Already verified/bound to this workspace; workspace OWNER authorization was already enforced.
                return;
            }
            // Fail closed: without GitHub App user-to-server installation ownership verification
            // (GET /user/installations using an installation OAuth code), CloudForge cannot verify
            // that the caller is an authorized administrator of an unattached GitHub Organization.
            log.warn(
                    "Rejected unverified new Organization GitHub App installation callback installationId={} accountLogin={} userId={}",
                    details.installationId(),
                    details.accountLogin(),
                    user.getId()
            );
            throw new ForbiddenOperationException(
                    "New GitHub Organization App installations cannot be linked automatically until GitHub App user-to-server installation verification is enabled"
            );
        }

        throw new ForbiddenOperationException(
                "Unsupported GitHub App installation account type: " + (accountType != null ? accountType : "unknown")
        );
    }

    public GitHubInstallationDto toInstallationDto(GitHubInstallationEntity entity) {
        return new GitHubInstallationDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getGithubInstallationId(),
                entity.getAccountLogin(),
                entity.getAccountType(),
                entity.getAccountAvatarUrl(),
                entity.getRepositorySelection(),
                entity.getStatus(),
                entity.getLastSyncedAt(),
                entity.getCreatedAt()
        );
    }

    public LinkedRepositoryDto toRepositoryDto(LinkedRepositoryEntity entity) {
        return new LinkedRepositoryDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getInstallation().getId(),
                entity.getInstallation().getGithubInstallationId(),
                entity.getGithubRepoId(),
                entity.getOwnerLogin(),
                entity.getName(),
                entity.getFullName(),
                entity.getDefaultBranch(),
                entity.getVisibility(),
                entity.getHtmlUrl(),
                entity.getDescription(),
                entity.isArchived(),
                entity.getConnectionStatus(),
                entity.getGithubUpdatedAt(),
                entity.getLastSyncedAt()
        );
    }

    private record SyncCounts(int synced, int disconnected) {
    }

    public record PendingInstallationState(
            String nonce,
            UUID workspaceId,
            UUID userId,
            Instant expiresAt
    ) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
