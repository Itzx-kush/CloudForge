package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.dto.CreateDeploymentRequest;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentOperationDto;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import com.cloudforge.backend.k8s.entity.KubernetesOperationKind;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentEventRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentOperationRepository;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.repository.WorkspaceRepository;
import com.cloudforge.backend.worker.service.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReleaseHistoryService {

    private final KubernetesDeploymentOperationRepository operations;
    private final KubernetesDeploymentEventRepository events;
    private final WorkspaceAuthorizationService authorization;
    private final KubernetesDeploymentService deployments;
    private final KubernetesDeploymentLifecycleService lifecycle;
    private final KubernetesDeploymentConfigValidator configValidator;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final LogSanitizer sanitizer;
    private final WorkspaceRepository workspaces;

    public ReleaseHistoryService(
            KubernetesDeploymentOperationRepository operations, KubernetesDeploymentEventRepository events,
            WorkspaceAuthorizationService authorization, KubernetesDeploymentService deployments,
            KubernetesDeploymentLifecycleService lifecycle, KubernetesDeploymentConfigValidator configValidator,
            ObjectMapper mapper, Validator validator, LogSanitizer sanitizer, WorkspaceRepository workspaces
    ) {
        this.operations = operations;
        this.events = events;
        this.authorization = authorization;
        this.deployments = deployments;
        this.lifecycle = lifecycle;
        this.configValidator = configValidator;
        this.mapper = mapper;
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.workspaces = workspaces;
    }

    public PagedResponse<Release> list(
            UUID workspace, UUID user, UUID project, UUID environment, UUID cluster, String deployment,
            KubernetesDeploymentStatus status, KubernetesOperationKind kind, int page, int size
    ) {
        authorization.requireMembership(workspace, user);
        Sort sort = Sort.by(Sort.Direction.DESC, "revisionNumber", "createdAt", "id");
        return PagedResponse.from(operations.listReleases(workspace, project, environment, cluster, status,
                deployment, kind, kind == KubernetesOperationKind.RECONCILIATION, PageRequest.of(Math.min(100_000, Math.max(0, page)), Math.min(100, Math.max(1, size)), sort)),
                this::toRelease);
    }

    public Release get(UUID workspace, UUID id, UUID user) {
        authorization.requireMembership(workspace, user);
        return toRelease(find(workspace, id));
    }

    public Comparison compare(UUID workspace, UUID first, UUID second, UUID user) {
        authorization.requireMembership(workspace, user);
        return compare(find(workspace, first), find(workspace, second));
    }

    public Preview preview(UUID workspace, UUID id, UUID user) {
        WorkspaceRole role = authorization.requireMembership(workspace, user).getRole();
        var target = find(workspace, id);
        var latest = latest(target);
        var current = current(target);
        boolean production = isProduction(target);
        var subsystem = deployments.getSubsystemStatus(workspace, user);
        String blocked = eligibilityProblem(target);
        if (blocked == null && (role == WorkspaceRole.VIEWER || production && role != WorkspaceRole.OWNER)) {
            blocked = "Your workspace role cannot request rollback for this environment";
        }
        if (blocked == null && production && !subsystem.allowProductionDeployments()) {
            blocked = "Production rollbacks are disabled by platform policy";
        }
        if (blocked == null && (!subsystem.kubernetesEnabled() || subsystem.activeDeployWorkers() == 0)) {
            blocked = "Kubernetes execution infrastructure is unavailable";
        }
        if (blocked == null && (latest.getStatus().isActive() || KubernetesDeploymentService.requiresReconciliation(latest))) {
            blocked = latest.getStatus().isActive() ? "A deployment operation is active" : "External state requires reconciliation";
        }
        if (blocked == null && (current == null || target.getId().equals(effectiveRelease(current)))) {
            blocked = "Select a previous release while a verified current release is recorded";
        }
        if (blocked == null && !target.getServiceName().equals(current.getServiceName())) {
            blocked = "Rollback requires the same managed Service identity";
        }
        return new Preview(toRelease(target), current == null ? null : toRelease(current), latest.getId(), blocked == null,
                blocked, production, current == null ? null : compare(current, target),
                "Kubernetes will check the currently managed resources and pull the target digest. Rollout may interrupt service.",
                KubernetesDeploymentService.requiresReconciliation(latest));
    }

    @Transactional
    public Release requestRollback(
            UUID workspace, UUID id, UserEntity actor, String key,
            UUID expectedOperation, String reason, boolean confirmed
    ) {
        authorization.requireDeveloperOrAbove(workspace, actor.getId());
        if (!confirmed || expectedOperation == null || key == null || key.trim().length() < 4 || key.length() > 128
                || reason == null || reason.trim().length() < 8 || reason.length() > 128) {
            throw new BadRequestException("Provide confirmation, the preview operation ID, an idempotency key, and a reason of 8–128 characters");
        }
        workspaces.lockForDeploymentRequest(workspace).orElseThrow();
        var target = find(workspace, id);
        if (isProduction(target)) {
            authorization.requireOwner(workspace, actor.getId());
        }
        var prior = operations.findByWorkspaceIdAndIdempotencyKey(workspace, key.trim()).orElse(null);
        Preview preview = prior == null ? preview(workspace, id, actor.getId()) : null;
        if (preview != null && !preview.eligible()) {
            throw new ConflictException(preview.blockedReason());
        }
        var current = preview == null || preview.current() == null ? null : find(workspace, preview.current().operation().id());
        return queue(workspace, target, expectedOperation, current, actor, key, reason, KubernetesOperationKind.ROLLBACK);
    }

    @Transactional
    public Release reconcile(UUID workspace, UUID uncertainId, UUID candidateId, UserEntity actor, String key) {
        authorization.requireOwner(workspace, actor.getId());
        if (key == null || key.trim().length() < 4 || key.length() > 128) {
            throw new BadRequestException("Provide an idempotency key of 4–128 characters");
        }
        workspaces.lockForDeploymentRequest(workspace).orElseThrow();
        var uncertain = find(workspace, uncertainId);
        var target = find(workspace, candidateId == null ? uncertainId : candidateId);
        if (target.getRevisionNumber() == null || !KubernetesDeploymentService.ownsTarget(uncertain, target)
                || (!target.getId().equals(uncertainId)
                    && !KubernetesDeploymentService.isVerifiedObservation(target)
                    && !target.getId().equals(uncertain.getRestoredReleaseId()))) {
            throw new ConflictException("Select the affected release or a previously verified release for this deployment");
        }
        var subsystem = deployments.getSubsystemStatus(workspace, actor.getId());
        if (!subsystem.kubernetesEnabled() || subsystem.activeDeployWorkers() == 0) {
            throw new ConflictException("Kubernetes execution infrastructure is unavailable");
        }
        return queue(workspace, target, uncertainId, null, actor, key, "Read-only workload verification",
                KubernetesOperationKind.RECONCILIATION);
    }

    private Release queue(
            UUID workspace, KubernetesDeploymentOperationEntity target, UUID expected, KubernetesDeploymentOperationEntity current,
            UserEntity actor, String key, String reason, KubernetesOperationKind kind
    ) {
        String safeReason = sanitizer.sanitize(reason.trim(), 128).message();
        if (safeReason.isBlank()) {
            throw new BadRequestException("Provide a readable operation reason");
        }
        boolean approveProduction = kind == KubernetesOperationKind.ROLLBACK && isProduction(target);
        var context = new KubernetesDeploymentService.ReleaseOperation(kind, target, expected, current, safeReason);
        var result = deployments.queueDeployment(workspace, restoreRequest(target, approveProduction, safeReason), key, actor, context);
        return toRelease(find(workspace, result.deployment().id()));
    }

    private String eligibilityProblem(KubernetesDeploymentOperationEntity target) {
        if (target.getRevisionNumber() == null || !historicallyVerified(target)) {
            return "This release lacks verified rollout evidence";
        }
        if (target.getClusterConnection().getConnectionStatus() != KubernetesClusterStatus.VALIDATED
                || target.getNamespaceBinding().getStatus() != KubernetesNamespaceBindingStatus.ACTIVE) {
            return "Cluster validation and an active approved namespace are required";
        }
        var artifact = target.getOciArtifact();
        if (artifact.getVerificationStatus() != OciArtifactVerificationStatus.VERIFIED
                || !OciBuildLifecycleService.isValidDigest(target.getManifestDigest())
                || !Objects.equals(target.getManifestDigest(), artifact.getManifestDigest())
                || !Objects.equals(target.getImmutableImageReference(), artifact.getImmutableReference())
                || !Objects.equals(target.getSourceCommitSha(), artifact.getCommitSha())) {
            return "The historical immutable OCI artifact is unavailable or no longer verified";
        }
        try {
            var request = restoreRequest(target, false, null);
            configValidator.validate(request, target.getDeploymentName());
        } catch (BadRequestException | ConflictException ex) {
            return "The historical workload does not meet current configuration policy";
        }
        return null;
    }

    private boolean historicallyVerified(KubernetesDeploymentOperationEntity op) {
        return KubernetesDeploymentService.isVerifiedObservation(op)
                && events.existsByDeploymentOperationIdAndWorkspaceIdAndEventType(
                        op.getId(), op.getWorkspace().getId(), op.getOperationKind().name() + "_SUCCEEDED");
    }

    private CreateDeploymentRequest restoreRequest(KubernetesDeploymentOperationEntity op, boolean approval, String reason) {
        var values = lifecycle.readObjectMap(op.getConfigSnapshotJson());
        try {
            var request = new CreateDeploymentRequest(
                    op.getProject().getId(), op.getEnvironment().getId(), op.getClusterConnection().getId(),
                    op.getNamespaceBinding().getId(), op.getNamespaceName(), op.getOciArtifact().getId(),
                    required(values, "deploymentName", String.class), required(values, "serviceName", String.class),
                    required(values, "replicaCount", Integer.class), required(values, "containerPort", Integer.class),
                    required(values, "servicePort", Integer.class), required(values, "cpuRequest", String.class),
                    required(values, "cpuLimit", String.class), required(values, "memoryRequest", String.class),
                    required(values, "memoryLimit", String.class),
                    mapper.convertValue(Objects.requireNonNull(values.get("readinessProbe")), KubernetesProbeConfig.class),
                    mapper.convertValue(Objects.requireNonNull(values.get("livenessProbe")), KubernetesProbeConfig.class),
                    mapper.convertValue(values.get("startupProbe"), KubernetesProbeConfig.class),
                    required(values, "rollingMaxSurge", String.class), required(values, "rollingMaxUnavailable", String.class),
                    required(values, "progressDeadlineSeconds", Integer.class),
                    required(values, "deploymentTimeoutSeconds", Integer.class),
                    required(values, "readOnlyRootFilesystem", Boolean.class), required(values, "runAsNonRoot", Boolean.class),
                    required(values, "runAsUser", Number.class).longValue(), approval, reason, null);
            if (!validator.validate(request).isEmpty() || !op.getDeploymentName().equals(request.deploymentName())
                    || !op.getServiceName().equals(request.serviceName()) || op.getRequestedReplicas() != request.replicaCount()) {
                throw new IllegalArgumentException();
            }
            return request;
        } catch (IllegalArgumentException | NullPointerException | ClassCastException ex) {
            throw new ConflictException("Historical workload snapshot is incomplete or incompatible");
        }
    }

    private static <T> T required(Map<String, Object> snapshot, String property, Class<T> type) {
        return type.cast(Objects.requireNonNull(snapshot.get(property)));
    }

    private KubernetesDeploymentOperationEntity find(UUID workspace, UUID id) {
        return operations.findByIdAndWorkspaceIdWithDetails(id, workspace)
                .orElseThrow(() -> new ResourceNotFoundException("Release operation not found in this workspace"));
    }

    private KubernetesDeploymentOperationEntity latest(KubernetesDeploymentOperationEntity target) {
        return deployments.latestTargetOperation(target.getClusterConnection().getId(), target.getNamespaceName(),
                target.getDeploymentName()).filter(op -> KubernetesDeploymentService.ownsTarget(op, target))
                .orElseThrow(() -> new ConflictException("Target ownership has changed"));
    }

    private KubernetesDeploymentOperationEntity current(KubernetesDeploymentOperationEntity target) {
        return operations.findFirstByClusterConnectionIdAndNamespaceNameAndDeploymentNameAndStatusOrderByCreatedAtDescIdDesc(
                target.getClusterConnection().getId(), target.getNamespaceName(), target.getDeploymentName(),
                KubernetesDeploymentStatus.SUCCEEDED)
                .filter(op -> KubernetesDeploymentService.ownsTarget(op, target) && historicallyVerified(op)).orElse(null);
    }

    private static UUID effectiveRelease(KubernetesDeploymentOperationEntity op) {
        return op.getOperationKind() == KubernetesOperationKind.RECONCILIATION ? op.getRestoredReleaseId() : op.getId();
    }

    private static boolean isProduction(KubernetesDeploymentOperationEntity op) {
        return op.getEnvironment().getEnvironmentType() == EnvironmentType.PRODUCTION
                || op.getClusterConnection().getEnvironmentCategory() == EnvironmentType.PRODUCTION;
    }

    private Comparison compare(KubernetesDeploymentOperationEntity first, KubernetesDeploymentOperationEntity second) {
        if (!KubernetesDeploymentService.ownsTarget(first, second)) {
            throw new ConflictException("Select two releases for the same deployment target");
        }
        var left = lifecycle.readObjectMap(first.getConfigSnapshotJson());
        var right = lifecycle.readObjectMap(second.getConfigSnapshotJson());
        var fields = new ArrayList<Difference>();
        fields.add(new Difference("Revision", first.getRevisionNumber(), second.getRevisionNumber()));
        fields.add(new Difference("Image digest", first.getManifestDigest(), second.getManifestDigest()));
        fields.add(new Difference("Image reference", first.getImmutableImageReference(), second.getImmutableImageReference()));
        fields.add(new Difference("Source commit", first.getSourceCommitSha(), second.getSourceCommitSha()));
        fields.add(new Difference("Cluster", first.getClusterConnection().getId(), second.getClusterConnection().getId()));
        fields.add(new Difference("Namespace", first.getNamespaceName(), second.getNamespaceName()));
        fields.add(new Difference("Manifest checksum", first.getManifestChecksumSha256(), second.getManifestChecksumSha256()));
        fields.add(new Difference("Completed", first.getCompletedAt(), second.getCompletedAt()));
        fields.add(new Difference("Outcome", first.getStatus(), second.getStatus()));
        for (String field : List.of("replicaCount", "cpuRequest", "cpuLimit", "memoryRequest", "memoryLimit",
                "containerPort", "servicePort", "rollingMaxSurge", "rollingMaxUnavailable", "progressDeadlineSeconds")) {
            fields.add(new Difference(field, scalar(left.get(field)), scalar(right.get(field))));
        }
        for (String field : List.of("livenessProbe", "readinessProbe", "startupProbe")) {
            fields.add(new Difference(field, probe(left.get(field)), probe(right.get(field))));
        }
        return new Comparison(first.getId(), second.getId(), fields);
    }

    private Object scalar(Object value) {
        if (value instanceof String text && text.matches("[a-zA-Z0-9.%]+")) {
            return text;
        }
        return value instanceof Number ? value : null;
    }

    private KubernetesProbeConfig probe(Object value) {
        try {
            var probe = mapper.convertValue(value, KubernetesProbeConfig.class);
            return probe == null || !validator.validate(probe).isEmpty() ? null : probe;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Release toRelease(KubernetesDeploymentOperationEntity op) {
        boolean unresolved = KubernetesDeploymentService.requiresReconciliation(op) && op.getStatus().isTerminal()
                && deployments.latestTargetOperation(op.getClusterConnection().getId(), op.getNamespaceName(),
                        op.getDeploymentName()).map(KubernetesDeploymentOperationEntity::getId).filter(op.getId()::equals).isPresent();
        String type = op.getOperationKind() == KubernetesOperationKind.DEPLOYMENT
                ? (Long.valueOf(1).equals(op.getRevisionNumber()) ? "INITIAL_DEPLOYMENT" : "UPDATE")
                : op.getOperationKind().name();
        return new Release(op.getRevisionNumber(), op.getOperationKind(), type, op.getRestoredReleaseId(),
                op.getExpectedOperationId(), op.getOperationReason(), historicallyVerified(op), unresolved,
                lifecycle.toOperationDto(op));
    }

    public record Release(Long revision, KubernetesOperationKind kind, String type, UUID targetReleaseId, UUID expectedOperationId,
                          String reason, boolean historicallyVerified, boolean reconciliationRequired,
                          KubernetesDeploymentOperationDto operation) {}

    public record Difference(String field, Object before, Object after, boolean changed) {
        Difference(String field, Object before, Object after) {
            this(field, before, after, !Objects.equals(before, after));
        }
    }

    public record Comparison(UUID first, UUID second, List<Difference> fields) {}

    public record Preview(Release target, Release current, UUID expectedOperationId, boolean eligible, String blockedReason,
                          boolean productionApprovalRequired, Comparison comparison, String risk, boolean reconciliationRequired) {}
}
