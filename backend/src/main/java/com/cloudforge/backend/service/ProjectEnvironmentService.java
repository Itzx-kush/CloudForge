package com.cloudforge.backend.service;

import com.cloudforge.backend.dto.CreateEnvironmentRequest;
import com.cloudforge.backend.dto.ProjectEnvironmentDto;
import com.cloudforge.backend.dto.UpdateEnvironmentRequest;
import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages non-secret persistent project environment configurations.
 * Actively rejects secret-like keys and credential patterns; encrypted secret
 * storage is intentionally deferred to a later phase.
 */
@Service
public class ProjectEnvironmentService {

    private static final int MAX_VARIABLES = 50;
    private static final int MAX_VALUE_LENGTH = 500;
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern VALID_BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
            "SECRET",
            "PASSWORD",
            "PASSWD",
            "TOKEN",
            "PRIVATE_KEY",
            "API_KEY",
            "ACCESS_KEY",
            "AUTH_KEY",
            "CREDENTIAL",
            "JWT_KEY",
            "SIGNING_KEY",
            "CLIENT_SECRET",
            "WEBHOOK_SECRET"
    );

    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProjectEnvironmentService(
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            WorkspaceAuthorizationService authorizationService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ProjectEnvironmentEntity> initializeDefaultEnvironments(ProjectEntity project) {
        return List.of(EnvironmentType.DEVELOPMENT, EnvironmentType.STAGING, EnvironmentType.PRODUCTION)
                .stream()
                .map(type -> {
                    ProjectEnvironmentEntity env = new ProjectEnvironmentEntity();
                    env.setProject(project);
                    env.setName(type.getDefaultDisplayName());
                    env.setEnvironmentType(type);
                    env.setConfigVariablesJson("{}");
                    return environmentRepository.save(env);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectEnvironmentDto> listEnvironments(UUID workspaceId, UUID projectId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        ensureProjectInWorkspace(workspaceId, projectId);
        return environmentRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ProjectEnvironmentDto createEnvironment(
            UUID workspaceId,
            UUID projectId,
            CreateEnvironmentRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireEnvironmentWriteAccess(
                workspaceId, user.getId(), request.environmentType()
        );
        ProjectEntity project = ensureProjectInWorkspace(workspaceId, projectId);

        if (environmentRepository.existsByProjectIdAndEnvironmentType(projectId, request.environmentType())) {
            throw new ConflictException(
                    "Environment " + request.environmentType() + " already exists for this project"
            );
        }

        Map<String, String> validatedVars = validateAndSanitizeVariables(request.configVariables());
        String branchOverride = validateBranchOverride(request.branchOverride());

        ProjectEnvironmentEntity env = new ProjectEnvironmentEntity();
        env.setProject(project);
        env.setEnvironmentType(request.environmentType());
        env.setName(StringUtils.hasText(request.name())
                ? request.name().trim()
                : request.environmentType().getDefaultDisplayName());
        env.setBranchOverride(branchOverride);
        env.setConfigVariablesJson(serializeVariables(validatedVars));
        env.setNotes(trimToNull(request.notes()));

        ProjectEnvironmentEntity saved = environmentRepository.save(env);

        auditService.record(
                membership.getWorkspace(),
                user,
                "ENVIRONMENT_CREATED",
                "PROJECT_ENVIRONMENT",
                saved.getId().toString(),
                "Created " + saved.getEnvironmentType() + " environment for project \"" + project.getName() + "\"",
                Map.of(
                        "projectId", project.getId().toString(),
                        "environmentType", saved.getEnvironmentType().name(),
                        "variableCount", validatedVars.size()
                )
        );

        return toDto(saved);
    }

    @Transactional
    public ProjectEnvironmentDto updateEnvironment(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UpdateEnvironmentRequest request,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        ProjectEntity project = ensureProjectInWorkspace(workspaceId, projectId);
        ProjectEnvironmentEntity env = environmentRepository.findByIdAndProjectId(environmentId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project environment not found"));

        WorkspaceMemberEntity membership = authorizationService.requireEnvironmentWriteAccess(
                workspaceId, user.getId(), env.getEnvironmentType()
        );

        Map<String, String> validatedVars = validateAndSanitizeVariables(request.configVariables());
        String branchOverride = validateBranchOverride(request.branchOverride());

        env.setName(request.name().trim());
        env.setBranchOverride(branchOverride);
        env.setConfigVariablesJson(serializeVariables(validatedVars));
        env.setNotes(trimToNull(request.notes()));

        ProjectEnvironmentEntity saved = environmentRepository.save(env);

        auditService.record(
                membership.getWorkspace(),
                user,
                "ENVIRONMENT_UPDATED",
                "PROJECT_ENVIRONMENT",
                saved.getId().toString(),
                "Updated " + saved.getEnvironmentType() + " configuration for project \"" + project.getName() + "\"",
                Map.of(
                        "projectId", project.getId().toString(),
                        "environmentType", saved.getEnvironmentType().name(),
                        "variableKeys", List.copyOf(validatedVars.keySet())
                )
        );

        return toDto(saved);
    }

    @Transactional
    public void deleteEnvironment(UUID workspaceId, UUID projectId, UUID environmentId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        ProjectEntity project = ensureProjectInWorkspace(workspaceId, projectId);
        ProjectEnvironmentEntity env = environmentRepository.findByIdAndProjectId(environmentId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project environment not found"));

        EnvironmentType type = env.getEnvironmentType();
        environmentRepository.delete(env);

        auditService.record(
                membership.getWorkspace(),
                user,
                "ENVIRONMENT_DELETED",
                "PROJECT_ENVIRONMENT",
                environmentId.toString(),
                "Deleted " + type + " environment from project \"" + project.getName() + "\"",
                Map.of("projectId", project.getId().toString(), "environmentType", type.name())
        );
    }

    public ProjectEnvironmentDto toDto(ProjectEnvironmentEntity entity) {
        return new ProjectEnvironmentDto(
                entity.getId(),
                entity.getProject().getId(),
                entity.getName(),
                entity.getEnvironmentType(),
                entity.getBranchOverride(),
                deserializeVariables(entity.getConfigVariablesJson()),
                entity.getNotes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ProjectEntity ensureProjectInWorkspace(UUID workspaceId, UUID projectId) {
        return projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
    }

    private Map<String, String> validateAndSanitizeVariables(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        if (input.size() > MAX_VARIABLES) {
            throw new BadRequestException(
                    "At most " + MAX_VARIABLES + " non-secret configuration variables are permitted per environment"
            );
        }

        Map<String, String> cleaned = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : input.entrySet()) {
            String rawKey = entry.getKey();
            String rawValue = entry.getValue() == null ? "" : entry.getValue().trim();

            if (!StringUtils.hasText(rawKey)) {
                errors.put("configVariables", "Configuration variable keys must not be blank");
                continue;
            }
            String key = rawKey.trim().toUpperCase(Locale.ROOT);
            if (!VALID_KEY_PATTERN.matcher(key).matches()) {
                errors.put("configVariables." + key,
                        "Key must start with a letter and contain only uppercase letters, numbers, and underscores");
                continue;
            }
            if (isSecretLikeKey(key)) {
                errors.put("configVariables." + key,
                        "Plaintext secret storage is disabled in Phase 2 (key \"" + key
                                + "\" looks like a secret). Encrypted secret management is deferred to a later phase.");
                continue;
            }
            if (rawValue.length() > MAX_VALUE_LENGTH) {
                errors.put("configVariables." + key,
                        "Value must be at most " + MAX_VALUE_LENGTH + " characters");
                continue;
            }
            if (isSecretLikeValue(rawValue)) {
                errors.put("configVariables." + key,
                        "Value appears to contain a private key or access token. Plaintext secrets are not permitted in Phase 2.");
                continue;
            }
            cleaned.put(key, rawValue);
        }

        if (!errors.isEmpty()) {
            throw new BadRequestException("Environment configuration validation failed", errors);
        }
        return cleaned;
    }

    private boolean isSecretLikeKey(String upperKey) {
        return FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(upperKey::contains);
    }

    private boolean isSecretLikeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("-----BEGIN ")
                || lower.startsWith("ghp_")
                || lower.startsWith("gho_")
                || lower.startsWith("ghs_")
                || lower.startsWith("github_pat_")
                || lower.startsWith("sk_live_")
                || lower.startsWith("akia");
    }

    private String validateBranchOverride(String branchOverride) {
        String trimmed = trimToNull(branchOverride);
        if (trimmed != null && !VALID_BRANCH_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Invalid branch override",
                    Map.of("branchOverride", "Branch name may only contain letters, numbers, dots, underscores, slashes, and hyphens")
            );
        }
        return trimmed;
    }

    private String serializeVariables(Map<String, String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize configuration variables");
        }
    }

    private Map<String, String> deserializeVariables(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
