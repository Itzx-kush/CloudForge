package com.cloudforge.worker.k8s;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes a leased, fenced {@code KUBERNETES_DEPLOY} stage:
 * validates manifest checksum &amp; security invariants, verifies CloudForge ownership labels
 * before write, applies {@code apps/v1 Deployment} and {@code v1 ClusterIP Service} via
 * Server-Side Apply, monitors live rollout progression &amp; pod health, detects image pull /
 * crash loop / quota / unschedulable / readiness failures, respects active cancellation,
 * and marks success ONLY after replica readiness AND deployed immutable digest are verified.
 */
public class KubernetesDeploymentExecutor {

    private final KubernetesApiClient k8sClient;
    private final long pollIntervalMs;

    public KubernetesDeploymentExecutor(KubernetesApiClient k8sClient, long pollIntervalMs) {
        this.k8sClient = k8sClient;
        this.pollIntervalMs = Math.max(50L, Math.min(pollIntervalMs, 10_000L));
    }

    public DeploymentExecutionOutcome execute(
            ControlPlaneClient.KubernetesExecutionDescriptor descriptor,
            KubernetesApiClient.ClusterConnectionContext clusterCtx,
            StatusReporter statusReporter,
            AtomicBoolean cancelSignal,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plusSeconds(Math.max(10, descriptor.deploymentTimeoutSeconds()));
        boolean applyInitiated = false;
        String deploymentUid = null;
        String serviceUid = null;
        KubernetesApiClient.DeploymentSnapshot lastSnapshot = null;
        List<Map<String, Object>> lastConditions = List.of();
        List<Map<String, Object>> lastPodDiagnostics = List.of();

        try {
            // 1. Build deterministic manifests & verify checksum + security policy
            statusReporter.report(
                    "VALIDATING", false, 0, 0, 0, 0, null, null, null, List.of(), List.of(),
                    "Validating manifest checksum, namespace isolation, and RBAC permissions"
            );
            logConsumer.accept("SYSTEM",
                    "[CloudForge K8s] Validating deployment manifest for "
                            + descriptor.namespaceName() + "/" + descriptor.deploymentName()
                            + " (" + descriptor.manifestDigest() + ")",
                    Instant.now());

            KubernetesManifestBuilder.BuiltManifests manifests = KubernetesManifestBuilder.buildAndVerify(descriptor);

            if (cancelSignal.get()) {
                return cancelled(false, null, null, null, List.of(), List.of(),
                        "Deployment cancelled before Kubernetes API apply", startedAt);
            }

            // 2. Verify required RBAC permissions in target namespace before writing
            boolean canPatchDeploy = k8sClient.checkSelfSubjectAccess(
                    clusterCtx, descriptor.namespaceName(), "apps", "deployments", "patch"
            );
            boolean canCreateDeploy = k8sClient.checkSelfSubjectAccess(
                    clusterCtx, descriptor.namespaceName(), "apps", "deployments", "create"
            );
            boolean canPatchSvc = k8sClient.checkSelfSubjectAccess(
                    clusterCtx, descriptor.namespaceName(), "", "services", "patch"
            );
            boolean canCreateSvc = k8sClient.checkSelfSubjectAccess(
                    clusterCtx, descriptor.namespaceName(), "", "services", "create"
            );
            if (!(canPatchDeploy && canCreateDeploy && canPatchSvc && canCreateSvc)) {
                return failed("K8S_RBAC_DENIED", false, null, null, null, List.of(), List.of(),
                        "Deployment ServiceAccount lacks required Deployment/Service RBAC in namespace \""
                                + descriptor.namespaceName() + "\"", startedAt);
            }

            // 3. Protect resources owned by unrelated controllers (check ownership labels if resource already exists)
            Optional<KubernetesApiClient.DeploymentSnapshot> existingDeploy =
                    k8sClient.getDeployment(clusterCtx, descriptor.namespaceName(), descriptor.deploymentName());
            if (existingDeploy.isPresent() && !isOwnedByCloudForge(existingDeploy.get().labels(), descriptor)) {
                return failed("K8S_MANIFEST_REJECTED", false, null, existingDeploy.get().uid(), null, List.of(), List.of(),
                        "Refusing to overwrite existing Deployment \"" + descriptor.deploymentName()
                                + "\" not managed by CloudForge for this workspace/project/environment", startedAt);
            }
            Optional<KubernetesApiClient.ServiceSnapshot> existingSvc =
                    k8sClient.getService(clusterCtx, descriptor.namespaceName(), descriptor.serviceName());
            if (existingSvc.isPresent() && !isOwnedByCloudForge(existingSvc.get().labels(), descriptor)) {
                return failed("K8S_MANIFEST_REJECTED", false, null, null, existingSvc.get().uid(), List.of(), List.of(),
                        "Refusing to overwrite existing Service \"" + descriptor.serviceName()
                                + "\" not managed by CloudForge for this workspace/project/environment", startedAt);
            }

            if (cancelSignal.get()) {
                return cancelled(false, null, null, null, List.of(), List.of(),
                        "Deployment cancelled prior to applying Kubernetes manifests", startedAt);
            }

            // 4. Mark APPLYING (applyInitiated=true) BEFORE issuing Kubernetes write API calls
            applyInitiated = true;
            statusReporter.report(
                    "APPLYING", true, 0, 0, 0, 0, null, null, null, List.of(), List.of(),
                    "Applying validated apps/v1 Deployment and v1 ClusterIP Service via Server-Side Apply"
            );
            logConsumer.accept("SYSTEM",
                    "[CloudForge K8s] Applying Deployment " + descriptor.deploymentName()
                            + " and ClusterIP Service " + descriptor.serviceName()
                            + " into namespace " + descriptor.namespaceName(),
                    Instant.now());

            KubernetesApiClient.DeploymentSnapshot appliedDeploy = k8sClient.applyDeployment(
                    clusterCtx, descriptor.namespaceName(), descriptor.deploymentName(), manifests.deploymentJson()
            );
            deploymentUid = appliedDeploy.uid();
            lastSnapshot = appliedDeploy;

            KubernetesApiClient.ServiceSnapshot appliedSvc = k8sClient.applyService(
                    clusterCtx, descriptor.namespaceName(), descriptor.serviceName(), manifests.serviceJson()
            );
            serviceUid = appliedSvc.uid();

            long targetGeneration = appliedDeploy.generation();
            logConsumer.accept("SYSTEM",
                    "[CloudForge K8s] Applied resources (deploymentUid=" + deploymentUid
                            + ", serviceUid=" + serviceUid + ", generation=" + targetGeneration + "). Observing rollout...",
                    Instant.now());

            statusReporter.report(
                    "ROLLING_OUT", true,
                    appliedDeploy.statusReplicas(), appliedDeploy.updatedReplicas(),
                    appliedDeploy.readyReplicas(), appliedDeploy.availableReplicas(),
                    appliedDeploy.observedGeneration(), deploymentUid, serviceUid,
                    toConditionMaps(appliedDeploy.conditions()), List.of(),
                    "Observing rolling update progression (generation " + targetGeneration + ")"
            );

            // 5. Poll rollout status, pods, and warning events until verified ready, failed, cancelled, or timed out
            int lastReportedReady = -1;
            int lastReportedUpdated = -1;
            while (true) {
                if (cancelSignal.get()) {
                    Optional<KubernetesApiClient.DeploymentSnapshot> finalSnap =
                            safeGetDeployment(clusterCtx, descriptor.namespaceName(), descriptor.deploymentName());
                    if (finalSnap.isPresent()) {
                        lastSnapshot = finalSnap.get();
                        lastConditions = toConditionMaps(lastSnapshot.conditions());
                    }
                    logConsumer.accept("SYSTEM",
                            "[CloudForge K8s] Cancellation signal received after apply — halting further writes without deleting cluster resources.",
                            Instant.now());
                    return new DeploymentExecutionOutcome(
                            "RECONCILIATION_REQUIRED",
                            "CANCELLED",
                            true,
                            lastSnapshot != null ? lastSnapshot.statusReplicas() : 0,
                            lastSnapshot != null ? lastSnapshot.updatedReplicas() : 0,
                            lastSnapshot != null ? lastSnapshot.readyReplicas() : 0,
                            lastSnapshot != null ? lastSnapshot.availableReplicas() : 0,
                            lastSnapshot != null ? lastSnapshot.observedGeneration() : null,
                            deploymentUid,
                            serviceUid,
                            null,
                            lastConditions,
                            lastPodDiagnostics,
                            "Deployment cancelled after Kubernetes apply began; inspect cluster workload state (no automatic rollback performed)",
                            startedAt,
                            Instant.now()
                    );
                }

                KubernetesApiClient.DeploymentSnapshot snap = k8sClient
                        .getDeployment(clusterCtx, descriptor.namespaceName(), descriptor.deploymentName())
                        .orElseThrow(() -> new KubernetesApiClient.KubernetesClientException(
                                "K8S_RECONCILIATION_REQUIRED", 404,
                                "Deployment \"" + descriptor.deploymentName() + "\" disappeared during rollout observation"
                        ));
                lastSnapshot = snap;
                deploymentUid = snap.uid() != null ? snap.uid() : deploymentUid;
                lastConditions = toConditionMaps(snap.conditions());

                List<KubernetesApiClient.PodSnapshot> pods =
                        k8sClient.listDeploymentPods(clusterCtx, descriptor.namespaceName(), manifests.selectorLabels());
                lastPodDiagnostics = toPodDiagnosticMaps(pods);

                // Check for immediate fatal Deployment conditions
                FailureDetection conditionFailure = detectDeploymentConditionFailure(snap, clusterCtx, descriptor);
                if (conditionFailure != null) {
                    logConsumer.accept("STDERR", "[CloudForge K8s] " + conditionFailure.message(), Instant.now());
                    return failed(conditionFailure.category(), true, snap, deploymentUid, serviceUid,
                            lastConditions, lastPodDiagnostics, conditionFailure.message(), startedAt);
                }

                // Check pods for ImagePullBackOff, ErrImagePull, CrashLoopBackOff, Unschedulable
                FailureDetection podFailure = detectPodFailure(pods);
                if (podFailure != null) {
                    logConsumer.accept("STDERR", "[CloudForge K8s] " + podFailure.message(), Instant.now());
                    return failed(podFailure.category(), true, snap, deploymentUid, serviceUid,
                            lastConditions, lastPodDiagnostics, podFailure.message(), startedAt);
                }

                if (snap.readyReplicas() != lastReportedReady || snap.updatedReplicas() != lastReportedUpdated) {
                    lastReportedReady = snap.readyReplicas();
                    lastReportedUpdated = snap.updatedReplicas();
                    statusReporter.report(
                            "ROLLING_OUT", true,
                            snap.statusReplicas(), snap.updatedReplicas(),
                            snap.readyReplicas(), snap.availableReplicas(),
                            snap.observedGeneration(), deploymentUid, serviceUid,
                            lastConditions, lastPodDiagnostics,
                            "Rollout progress: updated=" + snap.updatedReplicas()
                                    + "/" + descriptor.replicaCount()
                                    + ", ready=" + snap.readyReplicas()
                                    + "/" + descriptor.replicaCount()
                                    + ", available=" + snap.availableReplicas()
                                    + "/" + descriptor.replicaCount()
                    );
                }

                // Check if rollout has converged to desired replicas & generation
                if (isRolloutConverged(snap, targetGeneration, descriptor.replicaCount())) {
                    statusReporter.report(
                            "VERIFYING", true,
                            snap.statusReplicas(), snap.updatedReplicas(),
                            snap.readyReplicas(), snap.availableReplicas(),
                            snap.observedGeneration(), deploymentUid, serviceUid,
                            lastConditions, lastPodDiagnostics,
                            "Verifying pod readiness and deployed immutable image digest"
                    );

                    String verifiedDigest = verifyRunningImageDigestAndReadiness(snap, pods, descriptor);
                    if (verifiedDigest != null) {
                        logConsumer.accept("SYSTEM",
                                "[CloudForge K8s] Rollout verified healthy: " + snap.readyReplicas()
                                        + "/" + descriptor.replicaCount() + " replicas ready running " + verifiedDigest,
                                Instant.now());
                        return new DeploymentExecutionOutcome(
                                "SUCCEEDED",
                                "NONE",
                                true,
                                snap.statusReplicas(),
                                snap.updatedReplicas(),
                                snap.readyReplicas(),
                                snap.availableReplicas(),
                                snap.observedGeneration(),
                                deploymentUid,
                                serviceUid,
                                verifiedDigest,
                                lastConditions,
                                lastPodDiagnostics,
                                "Deployment \"" + descriptor.deploymentName() + "\" healthy ("
                                        + snap.readyReplicas() + "/" + descriptor.replicaCount()
                                        + " ready) with verified digest " + verifiedDigest,
                                startedAt,
                                Instant.now()
                        );
                    }
                }

                if (Instant.now().isAfter(deadline)) {
                    boolean runningButNotReady = pods.stream()
                            .anyMatch(p -> "Running".equalsIgnoreCase(p.phase()) && !p.ready());
                    String timeoutCategory = runningButNotReady ? "K8S_READINESS_FAILED" : "K8S_ROLLOUT_TIMEOUT";
                    String timeoutMsg = runningButNotReady
                            ? "Deployment timed out after " + descriptor.deploymentTimeoutSeconds()
                            + "s: pods are running but failed readiness probes (" + snap.readyReplicas()
                            + "/" + descriptor.replicaCount() + " ready)"
                            : "Deployment rollout timed out after " + descriptor.deploymentTimeoutSeconds()
                            + "s (" + snap.readyReplicas() + "/" + descriptor.replicaCount() + " replicas ready)";
                    logConsumer.accept("STDERR", "[CloudForge K8s] " + timeoutMsg, Instant.now());
                    return failed(timeoutCategory, true, snap, deploymentUid, serviceUid,
                            lastConditions, lastPodDiagnostics, timeoutMsg, startedAt);
                }

                Thread.sleep(pollIntervalMs);
            }
        } catch (KubernetesManifestBuilder.ManifestPolicyException ex) {
            return failed("K8S_MANIFEST_REJECTED", applyInitiated, lastSnapshot, deploymentUid, serviceUid,
                    lastConditions, lastPodDiagnostics, ex.getMessage(), startedAt);
        } catch (KubernetesApiClient.KubernetesClientException ex) {
            return failed(ex.getFailureCategory(), applyInitiated, lastSnapshot, deploymentUid, serviceUid,
                    lastConditions, lastPodDiagnostics, ex.getMessage(), startedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new DeploymentExecutionOutcome(
                    applyInitiated ? "RECONCILIATION_REQUIRED" : "CANCELLED",
                    "CANCELLED",
                    applyInitiated,
                    lastSnapshot != null ? lastSnapshot.statusReplicas() : 0,
                    lastSnapshot != null ? lastSnapshot.updatedReplicas() : 0,
                    lastSnapshot != null ? lastSnapshot.readyReplicas() : 0,
                    lastSnapshot != null ? lastSnapshot.availableReplicas() : 0,
                    lastSnapshot != null ? lastSnapshot.observedGeneration() : null,
                    deploymentUid,
                    serviceUid,
                    null,
                    lastConditions,
                    lastPodDiagnostics,
                    "Deployment worker interrupted during rollout",
                    startedAt,
                    Instant.now()
            );
        } catch (Exception ex) {
            return failed(applyInitiated ? "K8S_RECONCILIATION_REQUIRED" : "CLUSTER_UNAVAILABLE",
                    applyInitiated, lastSnapshot, deploymentUid, serviceUid,
                    lastConditions, lastPodDiagnostics, "Kubernetes deployment failed: " + ex.getMessage(), startedAt);
        }
    }

    private static boolean isOwnedByCloudForge(
            Map<String, String> labels,
            ControlPlaneClient.KubernetesExecutionDescriptor descriptor
    ) {
        if (labels == null || labels.isEmpty()) {
            return false;
        }
        return "cloudforge".equals(labels.get("app.kubernetes.io/managed-by"))
                && descriptor.workspaceId().toString().equals(labels.get("cloudforge.io/workspace-id"))
                && descriptor.projectId().toString().equals(labels.get("cloudforge.io/project-id"))
                && descriptor.environmentId().toString().equals(labels.get("cloudforge.io/environment-id"));
    }

    private static boolean isRolloutConverged(
            KubernetesApiClient.DeploymentSnapshot snap,
            long targetGeneration,
            int desiredReplicas
    ) {
        if (snap.observedGeneration() < Math.max(targetGeneration, snap.generation())) {
            return false;
        }
        if (snap.updatedReplicas() < desiredReplicas
                || snap.readyReplicas() < desiredReplicas
                || snap.availableReplicas() < desiredReplicas) {
            return false;
        }
        // If old pods are still terminating during surge, wait until total replicas == updatedReplicas
        if (snap.statusReplicas() > snap.updatedReplicas()) {
            return false;
        }
        boolean availableTrue = false;
        boolean progressingOk = true;
        if (snap.conditions() != null) {
            for (KubernetesApiClient.DeploymentConditionInfo c : snap.conditions()) {
                if ("Available".equalsIgnoreCase(c.type()) && "True".equalsIgnoreCase(c.status())) {
                    availableTrue = true;
                }
                if ("Progressing".equalsIgnoreCase(c.type()) && "False".equalsIgnoreCase(c.status())) {
                    progressingOk = false;
                }
            }
        }
        return availableTrue && progressingOk;
    }

    private static String verifyRunningImageDigestAndReadiness(
            KubernetesApiClient.DeploymentSnapshot snap,
            List<KubernetesApiClient.PodSnapshot> pods,
            ControlPlaneClient.KubernetesExecutionDescriptor descriptor
    ) {
        String expectedDigest = descriptor.manifestDigest().toLowerCase(Locale.ROOT);
        boolean deploymentSpecMatches = snap.containerImages() != null
                && snap.containerImages().stream()
                .anyMatch(img -> img != null && img.toLowerCase(Locale.ROOT).contains(expectedDigest));
        if (!deploymentSpecMatches) {
            return null;
        }
        int readyMatchingPods = 0;
        for (KubernetesApiClient.PodSnapshot pod : pods) {
            if (!pod.ready() || pod.containerStatuses() == null || pod.containerStatuses().isEmpty()) {
                continue;
            }
            boolean podMatches = pod.containerStatuses().stream().allMatch(cs ->
                    cs.ready() && (
                            (cs.image() != null && cs.image().toLowerCase(Locale.ROOT).contains(expectedDigest))
                                    || (cs.imageId() != null && cs.imageId().toLowerCase(Locale.ROOT).contains(expectedDigest))
                    )
            );
            if (podMatches) {
                readyMatchingPods++;
            }
        }
        return readyMatchingPods >= descriptor.replicaCount() ? expectedDigest : null;
    }

    private FailureDetection detectDeploymentConditionFailure(
            KubernetesApiClient.DeploymentSnapshot snap,
            KubernetesApiClient.ClusterConnectionContext ctx,
            ControlPlaneClient.KubernetesExecutionDescriptor descriptor
    ) {
        if (snap.conditions() == null) {
            return null;
        }
        for (KubernetesApiClient.DeploymentConditionInfo c : snap.conditions()) {
            if ("Progressing".equalsIgnoreCase(c.type())
                    && "False".equalsIgnoreCase(c.status())
                    && "ProgressDeadlineExceeded".equalsIgnoreCase(c.reason())) {
                return new FailureDetection("K8S_ROLLOUT_TIMEOUT",
                        "Kubernetes Deployment exceeded progressDeadlineSeconds: " + safeText(c.message()));
            }
            if ("ReplicaFailure".equalsIgnoreCase(c.type()) && "True".equalsIgnoreCase(c.status())) {
                String msg = safeText(c.message());
                if (msg.toLowerCase(Locale.ROOT).contains("quota") || msg.toLowerCase(Locale.ROOT).contains("exceeded")) {
                    return new FailureDetection("K8S_QUOTA_EXCEEDED", "Namespace ResourceQuota exceeded: " + msg);
                }
                if (msg.toLowerCase(Locale.ROOT).contains("forbidden") || msg.toLowerCase(Locale.ROOT).contains("podsecurity")) {
                    return new FailureDetection("K8S_MANIFEST_REJECTED", "Admission / security policy rejected workload: " + msg);
                }
                List<String> events = safeListWarningEvents(ctx, descriptor.namespaceName(), descriptor.deploymentName());
                return new FailureDetection("K8S_MANIFEST_REJECTED",
                        "ReplicaFailure (" + safeText(c.reason()) + "): " + msg
                                + (events.isEmpty() ? "" : " | Events: " + events.get(0)));
            }
        }
        return null;
    }

    private static FailureDetection detectPodFailure(List<KubernetesApiClient.PodSnapshot> pods) {
        for (KubernetesApiClient.PodSnapshot pod : pods) {
            if (!pod.scheduled() && "Unschedulable".equalsIgnoreCase(pod.unschedulableReason())) {
                return new FailureDetection("K8S_UNSCHEDULABLE",
                        "Pod " + pod.name() + " is Unschedulable: " + safeText(pod.unschedulableMessage()));
            }
            if (pod.containerStatuses() == null) {
                continue;
            }
            for (KubernetesApiClient.ContainerStatusSnapshot cs : pod.containerStatuses()) {
                String wait = cs.waitingReason() == null ? "" : cs.waitingReason();
                if ("ImagePullBackOff".equalsIgnoreCase(wait)
                        || "ErrImagePull".equalsIgnoreCase(wait)
                        || "InvalidImageName".equalsIgnoreCase(wait)) {
                    return new FailureDetection("K8S_IMAGE_PULL_FAILED",
                            "Container image pull failed (" + wait + ") on pod " + pod.name()
                                    + ": " + safeText(cs.waitingMessage()));
                }
                if ("CrashLoopBackOff".equalsIgnoreCase(wait)
                        || "CreateContainerConfigError".equalsIgnoreCase(wait)
                        || "CreateContainerError".equalsIgnoreCase(wait)
                        || "RunContainerError".equalsIgnoreCase(wait)) {
                    return new FailureDetection("K8S_CRASH_LOOP",
                            "Container failed to start (" + wait + ", restarts=" + cs.restartCount()
                                    + ") on pod " + pod.name() + ": " + safeText(cs.waitingMessage()));
                }
            }
        }
        return null;
    }

    private Optional<KubernetesApiClient.DeploymentSnapshot> safeGetDeployment(
            KubernetesApiClient.ClusterConnectionContext ctx, String ns, String name
    ) {
        try {
            return k8sClient.getDeployment(ctx, ns, name);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private List<String> safeListWarningEvents(
            KubernetesApiClient.ClusterConnectionContext ctx, String ns, String name
    ) {
        try {
            return k8sClient.listRecentWarningEvents(ctx, ns, name);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Map<String, Object>> toConditionMaps(List<KubernetesApiClient.DeploymentConditionInfo> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(conditions.size());
        for (KubernetesApiClient.DeploymentConditionInfo c : conditions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", safeText(c.type()));
            m.put("status", safeText(c.status()));
            m.put("reason", safeText(c.reason()));
            m.put("message", safeText(c.message()));
            result.add(m);
        }
        return result;
    }

    private static List<Map<String, Object>> toPodDiagnosticMaps(List<KubernetesApiClient.PodSnapshot> pods) {
        if (pods == null || pods.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(Math.min(pods.size(), 10));
        int count = 0;
        for (KubernetesApiClient.PodSnapshot p : pods) {
            if (count++ >= 10) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("podName", safeText(p.name()));
            m.put("phase", safeText(p.phase()));
            m.put("ready", p.ready());
            m.put("scheduled", p.scheduled());
            if (!p.scheduled() && p.unschedulableReason() != null) {
                m.put("reason", p.unschedulableReason());
                m.put("message", safeText(p.unschedulableMessage()));
            } else if (p.containerStatuses() != null && !p.containerStatuses().isEmpty()) {
                KubernetesApiClient.ContainerStatusSnapshot cs = p.containerStatuses().get(0);
                m.put("restarts", cs.restartCount());
                if (cs.waitingReason() != null) {
                    m.put("reason", cs.waitingReason());
                    m.put("message", safeText(cs.waitingMessage()));
                }
            }
            result.add(m);
        }
        return result;
    }

    private static String safeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 240 ? raw.substring(0, 240) : raw;
    }

    private static DeploymentExecutionOutcome failed(
            String category,
            boolean applyInitiated,
            KubernetesApiClient.DeploymentSnapshot snap,
            String deploymentUid,
            String serviceUid,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> podDiagnostics,
            String summary,
            Instant startedAt
    ) {
        return new DeploymentExecutionOutcome(
                "FAILED",
                category,
                applyInitiated,
                snap != null ? snap.statusReplicas() : 0,
                snap != null ? snap.updatedReplicas() : 0,
                snap != null ? snap.readyReplicas() : 0,
                snap != null ? snap.availableReplicas() : 0,
                snap != null ? snap.observedGeneration() : null,
                deploymentUid,
                serviceUid,
                null,
                conditions,
                podDiagnostics,
                summary,
                startedAt,
                Instant.now()
        );
    }

    private static DeploymentExecutionOutcome cancelled(
            boolean applyInitiated,
            KubernetesApiClient.DeploymentSnapshot snap,
            String deploymentUid,
            String serviceUid,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> podDiagnostics,
            String summary,
            Instant startedAt
    ) {
        return new DeploymentExecutionOutcome(
                "CANCELLED",
                "CANCELLED",
                applyInitiated,
                snap != null ? snap.statusReplicas() : 0,
                snap != null ? snap.updatedReplicas() : 0,
                snap != null ? snap.readyReplicas() : 0,
                snap != null ? snap.availableReplicas() : 0,
                snap != null ? snap.observedGeneration() : null,
                deploymentUid,
                serviceUid,
                null,
                conditions,
                podDiagnostics,
                summary,
                startedAt,
                Instant.now()
        );
    }

    @FunctionalInterface
    public interface StatusReporter {
        void report(
                String status,
                boolean applyInitiated,
                int observedReplicas,
                int updatedReplicas,
                int readyReplicas,
                int availableReplicas,
                Long observedGeneration,
                String k8sDeploymentUid,
                String k8sServiceUid,
                List<Map<String, Object>> rolloutConditions,
                List<Map<String, Object>> podDiagnostics,
                String message
        );
    }

    private record FailureDetection(String category, String message) {
    }

    public record DeploymentExecutionOutcome(
            String deploymentStatus,
            String failureCategory,
            boolean applyInitiated,
            int observedReplicas,
            int updatedReplicas,
            int readyReplicas,
            int availableReplicas,
            Long observedGeneration,
            String k8sDeploymentUid,
            String k8sServiceUid,
            String verifiedImageDigest,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics,
            String summary,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
