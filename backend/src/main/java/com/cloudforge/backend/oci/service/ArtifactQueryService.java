package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.dto.OciArtifactEventDto;
import com.cloudforge.backend.oci.dto.OciBuildRecordDto;
import com.cloudforge.backend.oci.dto.OciImageArtifactDetailDto;
import com.cloudforge.backend.oci.dto.OciImageArtifactDto;
import com.cloudforge.backend.oci.dto.OciPublishAttemptDto;
import com.cloudforge.backend.oci.dto.OciSubsystemStatusDto;
import com.cloudforge.backend.oci.dto.PipelineRunArtifactsResponse;
import com.cloudforge.backend.oci.entity.OciArtifactEventEntity;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import com.cloudforge.backend.oci.repository.OciArtifactEventRepository;
import com.cloudforge.backend.oci.repository.OciBuildRecordRepository;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.repository.OciPublishAttemptRepository;
import com.cloudforge.backend.oci.repository.OciRegistryConnectionRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace-isolated read-only query service for OCI build history, verified container image
 * artifacts, publish attempts, artifact lifecycle events, and OCI subsystem status.
 */
@Service
public class ArtifactQueryService {

    private final WorkspaceAuthorizationService authorizationService;
    private final ProjectRepository projectRepository;
    private final PipelineRunRepository runRepository;
    private final OciRegistryConnectionRepository registryRepository;
    private final OciBuildRecordRepository buildRecordRepository;
    private final OciImageArtifactRepository artifactRepository;
    private final OciPublishAttemptRepository publishAttemptRepository;
    private final OciArtifactEventRepository artifactEventRepository;
    private final WorkerNodeRepository workerNodeRepository;
    private final OciBuildLifecycleService lifecycleService;
    private final OciBuildProperties ociProperties;
    private final ObjectMapper objectMapper;

    public ArtifactQueryService(
            WorkspaceAuthorizationService authorizationService,
            ProjectRepository projectRepository,
            PipelineRunRepository runRepository,
            OciRegistryConnectionRepository registryRepository,
            OciBuildRecordRepository buildRecordRepository,
            OciImageArtifactRepository artifactRepository,
            OciPublishAttemptRepository publishAttemptRepository,
            OciArtifactEventRepository artifactEventRepository,
            WorkerNodeRepository workerNodeRepository,
            OciBuildLifecycleService lifecycleService,
            OciBuildProperties ociProperties,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.projectRepository = projectRepository;
        this.runRepository = runRepository;
        this.registryRepository = registryRepository;
        this.buildRecordRepository = buildRecordRepository;
        this.artifactRepository = artifactRepository;
        this.publishAttemptRepository = publishAttemptRepository;
        this.artifactEventRepository = artifactEventRepository;
        this.workerNodeRepository = workerNodeRepository;
        this.lifecycleService = lifecycleService;
        this.ociProperties = ociProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OciSubsystemStatusDto getWorkspaceOciStatus(UUID workspaceId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireMembership(workspaceId, user.getId());
        boolean isOwner = membership.getRole() == WorkspaceRole.OWNER;

        long activeBuildWorkers = workerNodeRepository.findAllByOrderByWorkerIdAsc().stream()
                .filter(w -> w.getAdapterType() == WorkerAdapterType.OCI_BUILDKIT
                        && w.getStatus() != WorkerStatus.OFFLINE)
                .count();

        return new OciSubsystemStatusDto(
                ociProperties.isEnabled(),
                isOwner && ociProperties.isAllowLocalDevRegistry(),
                ociProperties.parsedAllowedPlatforms().stream().sorted().toList(),
                isOwner ? ociProperties.parsedAllowedRegistryHosts().stream().sorted().toList() : List.of(),
                registryRepository.countByWorkspaceIdAndStatus(workspaceId, OciRegistryStatus.ACTIVE),
                buildRecordRepository.countByWorkspaceId(workspaceId),
                buildRecordRepository.countByWorkspaceIdAndStatus(workspaceId, OciBuildStatus.SUCCEEDED),
                artifactRepository.countByWorkspaceIdAndVerificationStatus(workspaceId, OciArtifactVerificationStatus.VERIFIED),
                activeBuildWorkers
        );
    }

    @Transactional(readOnly = true)
    public PagedResponse<OciBuildRecordDto> listBuilds(
            UUID workspaceId,
            UUID projectId,
            OciBuildStatus status,
            String search,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        }
        Page<com.cloudforge.backend.oci.entity.OciBuildRecordEntity> page =
                buildRecordRepository.searchByWorkspace(workspaceId, projectId, status, search, pageable);
        return PagedResponse.from(page, lifecycleService::toBuildDto);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OciImageArtifactDto> listArtifacts(
            UUID workspaceId,
            UUID projectId,
            OciArtifactVerificationStatus verificationStatus,
            String search,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        }
        Page<OciImageArtifactEntity> page =
                artifactRepository.searchByWorkspace(workspaceId, projectId, verificationStatus, search, pageable);
        return PagedResponse.from(page, lifecycleService::toArtifactDto);
    }

    @Transactional(readOnly = true)
    public OciImageArtifactDetailDto getArtifactDetail(UUID workspaceId, UUID artifactId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        OciImageArtifactEntity artifact = artifactRepository
                .findByIdAndWorkspaceIdWithDetails(artifactId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("OCI image artifact not found in this workspace"));

        OciBuildRecordDto buildDto = artifact.getBuildRecord() != null
                ? lifecycleService.toBuildDto(artifact.getBuildRecord())
                : null;
        List<OciPublishAttemptDto> attempts = publishAttemptRepository
                .findAllByArtifactIdAndWorkspaceIdOrderByStartedAtDesc(artifact.getId(), workspaceId)
                .stream()
                .map(lifecycleService::toPublishDto)
                .toList();
        List<OciArtifactEventDto> events = artifactEventRepository
                .findAllByArtifactIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(artifact.getId(), workspaceId)
                .stream()
                .map(this::toEventDto)
                .toList();

        return new OciImageArtifactDetailDto(
                lifecycleService.toArtifactDto(artifact),
                buildDto,
                attempts,
                events
        );
    }

    @Transactional(readOnly = true)
    public PipelineRunArtifactsResponse getPipelineRunArtifacts(UUID workspaceId, UUID runId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        runRepository.findByIdAndWorkspaceIdWithDetails(runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found in this workspace"));

        List<OciBuildRecordDto> builds = buildRecordRepository
                .findAllByRunAndWorkspaceWithDetails(runId, workspaceId)
                .stream()
                .map(lifecycleService::toBuildDto)
                .toList();
        List<OciPublishAttemptDto> publishAttempts = publishAttemptRepository
                .findAllByPipelineRunIdAndWorkspaceIdOrderByStartedAtDesc(runId, workspaceId)
                .stream()
                .map(lifecycleService::toPublishDto)
                .toList();
        List<OciImageArtifactDto> artifacts = artifactRepository
                .findAllByRunAndWorkspaceWithDetails(runId, workspaceId)
                .stream()
                .map(lifecycleService::toArtifactDto)
                .toList();
        List<OciArtifactEventDto> events = artifactEventRepository
                .findAllByPipelineRunIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(runId, workspaceId)
                .stream()
                .map(this::toEventDto)
                .toList();

        return new PipelineRunArtifactsResponse(runId, builds, publishAttempts, artifacts, events);
    }

    private OciArtifactEventDto toEventDto(OciArtifactEventEntity ev) {
        return new OciArtifactEventDto(
                ev.getId(),
                ev.getPipelineRun().getId(),
                ev.getBuildRecord() != null ? ev.getBuildRecord().getId() : null,
                ev.getArtifact() != null ? ev.getArtifact().getId() : null,
                ev.getPublishAttempt() != null ? ev.getPublishAttempt().getId() : null,
                ev.getEventType(),
                ev.getActorLabel(),
                ev.getMessage(),
                readObjectMap(ev.getMetadataJson()),
                ev.getOccurredAt()
        );
    }

    private Map<String, Object> readObjectMap(String json) {
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
}
