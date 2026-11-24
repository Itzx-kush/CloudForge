package com.cloudforge.backend.secrets.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.SecretCryptoService;
import com.cloudforge.backend.secrets.crypto.SecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretManagementUnavailableException;
import com.cloudforge.backend.secrets.dto.CreateEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretDetailDto;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretSummaryDto;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretVersionDto;
import com.cloudforge.backend.secrets.dto.RevokeEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.RotateEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.SecretStatusResponse;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages write-only encrypted environment secrets scoped cleanly to a workspace, project,
 * and project environment. Never returns plaintext secret values, ciphertexts, wrapped DEKs,
 * nonces, or KEK bytes through any user-facing query or mutation endpoint.
 */
@Service
public class SecretManagementService {

    public static final Pattern VALID_SECRET_NAME_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");

    private static final Set<String> FORBIDDEN_OS_ENV_NAMES = Set.of(
            "PATH",
            "IFS",
            "HOME",
            "USER",
            "SHELL",
            "ENV",
            "BASH_ENV",
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES",
            "DYLD_LIBRARY_PATH",
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "CLOUDFORGE_WORKER_TOKEN",
            "CLOUDFORGE_SECRETS_KEYRING",
            "CLOUDFORGE_SECRETS_OPERATOR_TOKEN"
    );

    private final SecretManagementProperties properties;
    private final SecretKeyProvider keyProvider;
    private final SecretCryptoService cryptoService;
    private final EnvironmentSecretRepository secretRepository;
    private final EnvironmentSecretVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final SecretAuditService secretAuditService;

    public SecretManagementService(
            SecretManagementProperties properties,
            SecretKeyProvider keyProvider,
            SecretCryptoService cryptoService,
            EnvironmentSecretRepository secretRepository,
            EnvironmentSecretVersionRepository versionRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            WorkspaceAuthorizationService authorizationService,
            SecretAuditService secretAuditService
    ) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.cryptoService = cryptoService;
        this.secretRepository = secretRepository;
        this.versionRepository = versionRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
        this.secretAuditService = secretAuditService;
    }

    @Transactional(readOnly = true)
    public SecretStatusResponse getWorkspaceSecretStatus(UUID workspaceId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireMembership(workspaceId, user.getId());
        boolean isOwner = membership.getRole() == WorkspaceRole.OWNER;
        return new SecretStatusResponse(
                keyProvider.isEnabled(),
                keyProvider.isAvailable(),
                keyProvider.getProviderType(),
                isOwner && keyProvider.isAvailable() ? keyProvider.getActiveKeyId() : null,
                properties.isWorkerDeliveryEnabled(),
                isOwner && properties.isAllowInsecureLocalDelivery(),
                isOwner && properties.isOperatorRotationEnabled(),
                properties.getMaxSecretValueBytes(),
                properties.getMaxSecretsPerEnvironment(),
                properties.getMaxBindingsPerPipeline(),
                keyProvider.getStatusDiagnostic()
        );
    }

    @Transactional(readOnly = true)
    public PagedResponse<EnvironmentSecretSummaryDto> listSecrets(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            SecretLifecycleStatus status,
            String search,
            Pageable pageable,
            UserEntity user
    ) {
        requireOwnerWithDenialAudit(workspaceId, projectId, environmentId, user, null, "list_secrets");
        ensureProjectAndEnvironment(workspaceId, projectId, environmentId);

        Page<EnvironmentSecretEntity> page = secretRepository.searchByEnvironment(
                workspaceId,
                projectId,
                environmentId,
                status,
                status == SecretLifecycleStatus.DELETED,
                search,
                pageable
        );

        Map<UUID, EnvironmentSecretVersionEntity> currentVersionsById = loadVersionsById(
                page.getContent().stream()
                        .map(EnvironmentSecretEntity::getCurrentVersionId)
                        .filter(Objects::nonNull)
                        .toList()
        );

        return PagedResponse.from(page, secret -> toSummaryDto(secret, currentVersionsById.get(secret.getCurrentVersionId())));
    }

    @Transactional(readOnly = true)
    public EnvironmentSecretDetailDto getSecretDetail(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            UserEntity user
    ) {
        requireOwnerWithDenialAudit(workspaceId, projectId, environmentId, user, secretId.toString(), "inspect_secret");
        ensureProjectAndEnvironment(workspaceId, projectId, environmentId);

        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdWithDetails(secretId, environmentId, projectId, workspaceId)
                .filter(s -> s.getStatus() != SecretLifecycleStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Environment secret not found"));

        List<EnvironmentSecretVersionEntity> versions = versionRepository
                .findAllBySecretIdAndWorkspaceIdOrderByVersionNumberDesc(secret.getId(), workspaceId);

        EnvironmentSecretVersionEntity currentVersion = versions.stream()
                .filter(v -> Objects.equals(v.getId(), secret.getCurrentVersionId()))
                .findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0));

        return new EnvironmentSecretDetailDto(
                toSummaryDto(secret, currentVersion),
                versions.stream()
                        .map(v -> toVersionDto(v, Objects.equals(v.getId(), secret.getCurrentVersionId())))
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public List<EnvironmentSecretVersionDto> listSecretVersions(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            UserEntity user
    ) {
        requireOwnerWithDenialAudit(workspaceId, projectId, environmentId, user, secretId.toString(), "list_secret_versions");
        ensureProjectAndEnvironment(workspaceId, projectId, environmentId);

        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdWithDetails(secretId, environmentId, projectId, workspaceId)
                .filter(s -> s.getStatus() != SecretLifecycleStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Environment secret not found"));

        return versionRepository.findAllBySecretIdAndWorkspaceIdOrderByVersionNumberDesc(secret.getId(), workspaceId)
                .stream()
                .map(v -> toVersionDto(v, Objects.equals(v.getId(), secret.getCurrentVersionId())))
                .toList();
    }

    @Transactional
    public EnvironmentSecretSummaryDto createSecret(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            CreateEnvironmentSecretRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = requireOwnerWithDenialAudit(
                workspaceId, projectId, environmentId, user, null, "create_secret"
        );
        ProjectAndEnv scope = ensureProjectAndEnvironment(workspaceId, projectId, environmentId);
        ensureEncryptionAvailable();

        String normalizedName = validateSecretName(request.name());
        validateSecretValue(request.value());

        if (secretRepository.existsByEnvironmentIdAndNameLockKey(scope.environment().getId(), normalizedName)) {
            throw new ConflictException(
                    "Secret \"" + normalizedName + "\" already exists in environment \"" + scope.environment().getName() + "\""
            );
        }

        long activeOrRevokedCount = secretRepository.countByEnvironmentIdAndStatusNot(
                scope.environment().getId(),
                SecretLifecycleStatus.DELETED
        );
        if (activeOrRevokedCount >= properties.getMaxSecretsPerEnvironment()) {
            throw new BadRequestException(
                    "Maximum limit of " + properties.getMaxSecretsPerEnvironment() + " secrets per environment reached"
            );
        }

        EnvironmentSecretEntity secret = new EnvironmentSecretEntity();
        secret.setWorkspace(membership.getWorkspace());
        secret.setProject(scope.project());
        secret.setEnvironment(scope.environment());
        secret.setSecretName(normalizedName);
        secret.setNameLockKey(normalizedName);
        secret.setDescription(trimToNull(request.description()));
        secret.setCurrentVersionNumber(1);
        secret.setStatus(SecretLifecycleStatus.ACTIVE);
        secret.setCreatedBy(user);
        secret.setUpdatedBy(user);
        secret = secretRepository.saveAndFlush(secret);

        EnvironmentSecretVersionEntity version = encryptAndSaveVersion(
                secret,
                1,
                request.value(),
                user
        );

        secret.setCurrentVersionId(version.getId());
        secret = secretRepository.save(secret);

        secretAuditService.recordSecretEvent(
                membership.getWorkspace(),
                user,
                SecretAuditService.ACTION_SECRET_CREATED,
                "ENVIRONMENT_SECRET",
                secret.getId().toString(),
                "Created encrypted secret \"" + secret.getSecretName() + "\" v1 in "
                        + scope.environment().getEnvironmentType() + " environment of \"" + scope.project().getName() + "\"",
                Map.of(
                        "entryId", secret.getId().toString(),
                        "entryName", secret.getSecretName(),
                        "projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "environmentType", scope.environment().getEnvironmentType().name(),
                        "versionNumber", 1,
                        "kekId", version.getKekId()
                )
        );

        return toSummaryDto(secret, version);
    }

    @Transactional
    public EnvironmentSecretSummaryDto rotateSecret(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            RotateEnvironmentSecretRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = requireOwnerWithDenialAudit(
                workspaceId, projectId, environmentId, user, secretId.toString(), "rotate_secret"
        );
        ProjectAndEnv scope = ensureProjectAndEnvironment(workspaceId, projectId, environmentId);
        ensureEncryptionAvailable();
        validateSecretValue(request.value());

        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdForUpdate(secretId, environmentId, projectId, workspaceId)
                .filter(s -> s.getStatus() != SecretLifecycleStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Environment secret not found"));

        if (secret.getStatus() == SecretLifecycleStatus.REVOKED) {
            throw new ConflictException(
                    "Revoked secret \"" + secret.getSecretName() + "\" cannot be rotated; create a new secret instead"
            );
        }

        int previousVersion = secret.getCurrentVersionNumber();
        int nextVersion = previousVersion + 1;

        EnvironmentSecretVersionEntity newVersion = encryptAndSaveVersion(
                secret,
                nextVersion,
                request.value(),
                user
        );

        if (request.description() != null) {
            secret.setDescription(trimToNull(request.description()));
        }
        secret.setCurrentVersionNumber(nextVersion);
        secret.setCurrentVersionId(newVersion.getId());
        secret.setLastRotatedAt(Instant.now());
        secret.setUpdatedBy(user);
        secret = secretRepository.save(secret);

        secretAuditService.recordSecretEvent(
                membership.getWorkspace(),
                user,
                SecretAuditService.ACTION_SECRET_ROTATED,
                "ENVIRONMENT_SECRET",
                secret.getId().toString(),
                "Rotated encrypted secret \"" + secret.getSecretName() + "\" from v" + previousVersion
                        + " to v" + nextVersion + " in " + scope.environment().getEnvironmentType(),
                Map.of(
                        "entryId", secret.getId().toString(),
                        "entryName", secret.getSecretName(),
                        "projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "environmentType", scope.environment().getEnvironmentType().name(),
                        "previousVersionNumber", previousVersion,
                        "versionNumber", nextVersion,
                        "kekId", newVersion.getKekId()
                )
        );

        return toSummaryDto(secret, newVersion);
    }

    @Transactional
    public EnvironmentSecretSummaryDto revokeSecret(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            RevokeEnvironmentSecretRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = requireOwnerWithDenialAudit(
                workspaceId, projectId, environmentId, user, secretId.toString(), "revoke_secret"
        );
        ProjectAndEnv scope = ensureProjectAndEnvironment(workspaceId, projectId, environmentId);

        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdForUpdate(secretId, environmentId, projectId, workspaceId)
                .filter(s -> s.getStatus() != SecretLifecycleStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Environment secret not found"));

        Instant now = Instant.now();
        Integer targetVersionNumber = request != null ? request.versionNumber() : null;
        String reason = request != null ? trimToNull(request.reason()) : null;
        final String currentSecretName = secret.getSecretName();

        if (targetVersionNumber != null) {
            EnvironmentSecretVersionEntity targetVersion = versionRepository
                    .findBySecretIdAndVersionNumberAndWorkspaceId(secret.getId(), targetVersionNumber, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Secret version v" + targetVersionNumber + " not found for secret \"" + currentSecretName + "\""
                    ));

            if (targetVersion.getStatus() != SecretVersionStatus.REVOKED) {
                targetVersion.setStatus(SecretVersionStatus.REVOKED);
                targetVersion.setRevokedAt(now);
                targetVersion.setRevokedBy(user);
                versionRepository.save(targetVersion);
            }

            if (targetVersionNumber == secret.getCurrentVersionNumber()
                    || versionRepository.findAllBySecretIdAndStatus(secret.getId(), SecretVersionStatus.ACTIVE).isEmpty()) {
                secret.setStatus(SecretLifecycleStatus.REVOKED);
                secret.setRevokedAt(now);
                secret.setRevokedBy(user);
            }
            secret.setUpdatedBy(user);
            secret = secretRepository.save(secret);
        } else {
            List<EnvironmentSecretVersionEntity> activeVersions =
                    versionRepository.findAllBySecretIdAndStatus(secret.getId(), SecretVersionStatus.ACTIVE);
            for (EnvironmentSecretVersionEntity version : activeVersions) {
                version.setStatus(SecretVersionStatus.REVOKED);
                version.setRevokedAt(now);
                version.setRevokedBy(user);
            }
            if (!activeVersions.isEmpty()) {
                versionRepository.saveAll(activeVersions);
            }
            secret.setStatus(SecretLifecycleStatus.REVOKED);
            secret.setRevokedAt(now);
            secret.setRevokedBy(user);
            secret.setUpdatedBy(user);
            secret = secretRepository.save(secret);
        }

        EnvironmentSecretVersionEntity currentVersion = secret.getCurrentVersionId() != null
                ? versionRepository.findById(secret.getCurrentVersionId()).orElse(null)
                : null;

        secretAuditService.recordSecretEvent(
                membership.getWorkspace(),
                user,
                SecretAuditService.ACTION_SECRET_REVOKED,
                "ENVIRONMENT_SECRET",
                secret.getId().toString(),
                "Revoked " + (targetVersionNumber != null ? ("v" + targetVersionNumber + " of ") : "")
                        + "secret \"" + secret.getSecretName() + "\" in " + scope.environment().getEnvironmentType(),
                Map.of(
                        "entryId", secret.getId().toString(),
                        "entryName", secret.getSecretName(),
                        "projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "environmentType", scope.environment().getEnvironmentType().name(),
                        "targetVersionNumber", targetVersionNumber != null ? targetVersionNumber : "ALL",
                        "reason", reason != null ? reason : "owner_revoked"
                )
        );

        return toSummaryDto(secret, currentVersion);
    }

    @Transactional
    public void deleteSecret(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = requireOwnerWithDenialAudit(
                workspaceId, projectId, environmentId, user, secretId.toString(), "delete_secret"
        );
        ProjectAndEnv scope = ensureProjectAndEnvironment(workspaceId, projectId, environmentId);

        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdForUpdate(secretId, environmentId, projectId, workspaceId)
                .filter(s -> s.getStatus() != SecretLifecycleStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Environment secret not found"));

        Instant now = Instant.now();
        List<EnvironmentSecretVersionEntity> activeVersions =
                versionRepository.findAllBySecretIdAndStatus(secret.getId(), SecretVersionStatus.ACTIVE);
        for (EnvironmentSecretVersionEntity version : activeVersions) {
            version.setStatus(SecretVersionStatus.REVOKED);
            version.setRevokedAt(now);
            version.setRevokedBy(user);
        }
        if (!activeVersions.isEmpty()) {
            versionRepository.saveAll(activeVersions);
        }

        secret.setStatus(SecretLifecycleStatus.DELETED);
        secret.setDeletedAt(now);
        secret.setDeletedBy(user);
        secret.setUpdatedBy(user);
        secret.setNameLockKey("DELETED:" + secret.getId());
        if (secret.getRevokedAt() == null) {
            secret.setRevokedAt(now);
            secret.setRevokedBy(user);
        }
        secretRepository.save(secret);

        secretAuditService.recordSecretEvent(
                membership.getWorkspace(),
                user,
                SecretAuditService.ACTION_SECRET_DELETED,
                "ENVIRONMENT_SECRET",
                secret.getId().toString(),
                "Deleted secret \"" + secret.getSecretName() + "\" from " + scope.environment().getEnvironmentType()
                        + " environment of \"" + scope.project().getName() + "\"",
                Map.of(
                        "entryId", secret.getId().toString(),
                        "entryName", secret.getSecretName(),
                        "projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "environmentType", scope.environment().getEnvironmentType().name()
                )
        );
    }

    /**
     * Decrypts a specific immutable secret version inside an authorized execution context.
     * Enforces context binding via AAD over workspace, project, environment, secret ID, and version.
     */
    public String decryptVersionValue(EnvironmentSecretEntity secret, EnvironmentSecretVersionEntity version) {
        ensureEncryptionAvailable();
        byte[] plaintextBytes = cryptoService.decrypt(
                new SecretCryptoService.EncryptedSecretPayload(
                        version.getCiphertext(),
                        version.getNonce(),
                        version.getWrappedDek(),
                        version.getKekId(),
                        version.getEncryptionAlgorithm(),
                        version.getEncryptionFormatVersion()
                ),
                new SecretCryptoService.SecretContext(
                        secret.getWorkspace().getId(),
                        secret.getProject().getId(),
                        secret.getEnvironment().getId(),
                        secret.getId(),
                        version.getVersionNumber()
                )
        );
        try {
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    public String validateSecretName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            throw new BadRequestException("Secret name must not be blank");
        }
        String trimmed = rawName.trim();
        if (!VALID_SECRET_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Secret name must match ^[A-Z][A-Z0-9_]{0,127}$ (uppercase letters, digits, underscores only)"
            );
        }
        if (FORBIDDEN_OS_ENV_NAMES.contains(trimmed)) {
            throw new BadRequestException(
                    "Secret name \"" + trimmed + "\" is reserved for runtime isolation safety and cannot be used"
            );
        }
        return trimmed;
    }

    private void validateSecretValue(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            throw new BadRequestException("Secret value must not be blank");
        }
        if (rawValue.indexOf('\0') >= 0) {
            throw new BadRequestException("Secret value must not contain NUL bytes");
        }
        int utf8Bytes = rawValue.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > properties.getMaxSecretValueBytes()) {
            throw new BadRequestException(
                    "Secret value exceeds maximum allowed size of " + properties.getMaxSecretValueBytes() + " UTF-8 bytes"
            );
        }
    }

    private EnvironmentSecretVersionEntity encryptAndSaveVersion(
            EnvironmentSecretEntity secret,
            int versionNumber,
            String plaintextValue,
            UserEntity creator
    ) {
        byte[] plaintextBytes = plaintextValue.getBytes(StandardCharsets.UTF_8);
        try {
            SecretCryptoService.EncryptedSecretPayload encrypted = cryptoService.encrypt(
                    plaintextBytes,
                    new SecretCryptoService.SecretContext(
                            secret.getWorkspace().getId(),
                            secret.getProject().getId(),
                            secret.getEnvironment().getId(),
                            secret.getId(),
                            versionNumber
                    )
            );
            EnvironmentSecretVersionEntity version = new EnvironmentSecretVersionEntity();
            version.setWorkspace(secret.getWorkspace());
            version.setProject(secret.getProject());
            version.setEnvironment(secret.getEnvironment());
            version.setSecret(secret);
            version.setVersionNumber(versionNumber);
            version.setCiphertext(encrypted.ciphertext());
            version.setNonce(encrypted.nonce());
            version.setWrappedDek(encrypted.wrappedDek());
            version.setKekId(encrypted.kekId());
            version.setEncryptionAlgorithm(encrypted.algorithm());
            version.setEncryptionFormatVersion(encrypted.encryptionFormatVersion());
            version.setStatus(SecretVersionStatus.ACTIVE);
            version.setCreatedBy(creator);
            return versionRepository.saveAndFlush(version);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    private WorkspaceMemberEntity requireOwnerWithDenialAudit(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UserEntity user,
            String resourceId,
            String actionContext
    ) {
        try {
            return authorizationService.requireSecretWriteAccess(workspaceId, user.getId());
        } catch (ForbiddenOperationException ex) {
            secretAuditService.recordDeniedAccess(
                    workspaceId,
                    user,
                    "ENVIRONMENT_SECRET",
                    resourceId != null ? resourceId : environmentId.toString(),
                    user.getUsername(),
                    actionContext,
                    Map.of(
                            "projectId", projectId.toString(),
                            "environmentId", environmentId.toString(),
                            "attemptedAction", actionContext
                    )
            );
            throw ex;
        }
    }

    private ProjectAndEnv ensureProjectAndEnvironment(UUID workspaceId, UUID projectId, UUID environmentId) {
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        ProjectEnvironmentEntity environment = environmentRepository.findByIdAndProjectId(environmentId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project environment not found in this project"));
        return new ProjectAndEnv(project, environment);
    }

    private void ensureEncryptionAvailable() {
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            throw new SecretManagementUnavailableException(keyProvider.getStatusDiagnostic());
        }
    }

    private Map<UUID, EnvironmentSecretVersionEntity> loadVersionsById(Collection<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        return versionRepository.findAllById(versionIds).stream()
                .collect(Collectors.toMap(EnvironmentSecretVersionEntity::getId, Function.identity()));
    }

    private EnvironmentSecretSummaryDto toSummaryDto(
            EnvironmentSecretEntity secret,
            EnvironmentSecretVersionEntity currentVersion
    ) {
        return new EnvironmentSecretSummaryDto(
                secret.getId(),
                secret.getWorkspace().getId(),
                secret.getProject().getId(),
                secret.getEnvironment().getId(),
                secret.getEnvironment().getName(),
                secret.getEnvironment().getEnvironmentType(),
                secret.getSecretName(),
                secret.getDescription(),
                secret.getStatus(),
                secret.getCurrentVersionNumber(),
                secret.getCurrentVersionId(),
                currentVersion != null ? currentVersion.getKekId() : null,
                currentVersion != null ? currentVersion.getEncryptionAlgorithm() : SecretCryptoService.ALGORITHM_AES_256_GCM,
                currentVersion != null ? currentVersion.getEncryptionFormatVersion() : SecretCryptoService.FORMAT_VERSION_V1,
                secret.getCreatedBy() != null ? secret.getCreatedBy().getUsername() : null,
                secret.getUpdatedBy() != null ? secret.getUpdatedBy().getUsername() : null,
                secret.getCreatedAt(),
                secret.getUpdatedAt(),
                secret.getLastRotatedAt(),
                secret.getRevokedAt(),
                secret.getRevokedBy() != null ? secret.getRevokedBy().getUsername() : null
        );
    }

    private EnvironmentSecretVersionDto toVersionDto(EnvironmentSecretVersionEntity version, boolean current) {
        return new EnvironmentSecretVersionDto(
                version.getId(),
                version.getSecret().getId(),
                version.getVersionNumber(),
                version.getStatus(),
                version.getKekId(),
                version.getEncryptionAlgorithm(),
                version.getEncryptionFormatVersion(),
                version.getCreatedBy() != null ? version.getCreatedBy().getUsername() : null,
                version.getCreatedAt(),
                version.getRevokedAt(),
                version.getRevokedBy() != null ? version.getRevokedBy().getUsername() : null,
                current
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ProjectAndEnv(ProjectEntity project, ProjectEnvironmentEntity environment) {
    }
}
