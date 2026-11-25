package com.cloudforge.worker.k8s;

import io.kubernetes.client.openapi.JSON;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.PatchUtils;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.AuthorizationV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.VersionApi;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.V1APIResourceList;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentCondition;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ResourceAttributes;
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview;
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReviewSpec;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.VersionInfo;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidatorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Production Kubernetes API client backed by the official Java 17 Kubernetes Java Client
 * ({@code io.kubernetes:client-java}).
 *
 * <h3>Security Controls</h3>
 * <ul>
 *   <li>Strictly HTTPS-only; never falls back to HTTP.</li>
 *   <li>Always enforces TLS certificate verification against the cluster's explicit X.509 CA PEM
 *       ({@code setVerifyingSsl(true)} unconditionally).</li>
 *   <li>Never loads ambient {@code ~/.kube/config} or in-cluster pod service account tokens.</li>
 *   <li>Disables HTTP &amp; SSL redirects ({@code followRedirects(false)}, {@code followSslRedirects(false)}).</li>
 *   <li>Enforces SSRF IP/DNS restrictions inside OkHttp's {@link Dns} resolver prior to every socket connect.</li>
 *   <li>Applies resources via Server-Side Apply ({@code fieldManager = "cloudforge-deployment-worker"}, {@code force = false})
 *       so unrelated controllers' fields are never silently clobbered.</li>
 * </ul>
 */
public class OfficialKubernetesApiClient implements KubernetesApiClient {

    public static final String FIELD_MANAGER = "cloudforge-deployment-worker";
    private final boolean allowLocalDevWorker;

    public OfficialKubernetesApiClient(boolean allowLocalDevWorker) {
        this.allowLocalDevWorker = allowLocalDevWorker;
    }

    @Override
    public ServerVersionInfo getServerVersion(ClusterConnectionContext ctx) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            VersionInfo info = new VersionApi(client).getCode().execute();
            return new ServerVersionInfo(
                    info.getGitVersion() != null ? info.getGitVersion() : "unknown",
                    info.getMajor() != null ? info.getMajor() : "",
                    info.getMinor() != null ? info.getMinor() : "",
                    info.getPlatform() != null ? info.getPlatform() : ""
            );
        } catch (ApiException ex) {
            throw translateApiException("Failed to query Kubernetes /version", ex);
        }
    }

    @Override
    public boolean hasAppsV1Api(ClusterConnectionContext ctx) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1APIResourceList list = new AppsV1Api(client).getAPIResources().execute();
            return list != null && list.getResources() != null
                    && list.getResources().stream().anyMatch(r -> "deployments".equals(r.getName()));
        } catch (ApiException ex) {
            if (ex.getCode() == 404) {
                return false;
            }
            throw translateApiException("Failed to verify apps/v1 API group", ex);
        }
    }

    @Override
    public boolean checkSelfSubjectAccess(
            ClusterConnectionContext ctx,
            String namespace,
            String group,
            String resource,
            String verb
    ) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1ResourceAttributes attrs = new V1ResourceAttributes()
                    .group(group == null ? "" : group)
                    .resource(resource)
                    .verb(verb);
            if (namespace != null && !namespace.isBlank()) {
                attrs.namespace(namespace);
            }
            V1SelfSubjectAccessReview review = new V1SelfSubjectAccessReview()
                    .apiVersion("authorization.k8s.io/v1")
                    .kind("SelfSubjectAccessReview")
                    .spec(new V1SelfSubjectAccessReviewSpec().resourceAttributes(attrs));
            V1SelfSubjectAccessReview response = new AuthorizationV1Api(client)
                    .createSelfSubjectAccessReview(review)
                    .execute();
            return response != null && response.getStatus() != null
                    && Boolean.TRUE.equals(response.getStatus().getAllowed());
        } catch (ApiException ex) {
            throw translateApiException("SelfSubjectAccessReview failed for " + verb + " " + resource, ex);
        }
    }

    @Override
    public NamespaceSnapshot inspectNamespace(ClusterConnectionContext ctx, String namespace) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1Namespace ns = new CoreV1Api(client).readNamespace(namespace).execute();
            String phase = ns.getStatus() != null && ns.getStatus().getPhase() != null
                    ? ns.getStatus().getPhase()
                    : "Active";
            Map<String, String> labels = ns.getMetadata() != null && ns.getMetadata().getLabels() != null
                    ? Map.copyOf(ns.getMetadata().getLabels())
                    : Map.of();
            return new NamespaceSnapshot(true, namespace, phase, labels);
        } catch (ApiException ex) {
            if (ex.getCode() == 404) {
                return new NamespaceSnapshot(false, namespace, "NotFound", Map.of());
            }
            throw translateApiException("Failed to inspect namespace \"" + namespace + "\"", ex);
        }
    }

    @Override
    public Optional<DeploymentSnapshot> getDeployment(ClusterConnectionContext ctx, String namespace, String name) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1Deployment dep = new AppsV1Api(client).readNamespacedDeployment(name, namespace).execute();
            return Optional.of(toDeploymentSnapshot(dep));
        } catch (ApiException ex) {
            if (ex.getCode() == 404) {
                return Optional.empty();
            }
            throw translateApiException("Failed to read Deployment " + namespace + "/" + name, ex);
        }
    }

    @Override
    public DeploymentSnapshot applyDeployment(
            ClusterConnectionContext ctx,
            String namespace,
            String name,
            String deploymentJson
    ) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            // Server-Side Apply strictly using Content-Type: application/apply-patch+yaml
            // (valid JSON is valid YAML 1.2) and force=false so unrelated controllers' fields are preserved.
            V1Patch patch = new V1Patch(deploymentJson);
            V1Deployment applied = PatchUtils.patch(
                    V1Deployment.class,
                    () -> new AppsV1Api(client)
                            .patchNamespacedDeployment(name, namespace, patch)
                            .fieldManager(FIELD_MANAGER)
                            .force(false)
                            .buildCall(null),
                    V1Patch.PATCH_FORMAT_APPLY_YAML,
                    client
            );
            return toDeploymentSnapshot(applied);
        } catch (ApiException ex) {
            throw translateApiException("Failed to apply Deployment " + namespace + "/" + name, ex);
        }
    }

    @Override
    public Optional<ServiceSnapshot> getService(ClusterConnectionContext ctx, String namespace, String name) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1Service svc = new CoreV1Api(client).readNamespacedService(name, namespace).execute();
            return Optional.of(toServiceSnapshot(svc));
        } catch (ApiException ex) {
            if (ex.getCode() == 404) {
                return Optional.empty();
            }
            throw translateApiException("Failed to read Service " + namespace + "/" + name, ex);
        }
    }

    @Override
    public ServiceSnapshot applyService(
            ClusterConnectionContext ctx,
            String namespace,
            String name,
            String serviceJson
    ) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            V1Patch patch = new V1Patch(serviceJson);
            V1Service applied = PatchUtils.patch(
                    V1Service.class,
                    () -> new CoreV1Api(client)
                            .patchNamespacedService(name, namespace, patch)
                            .fieldManager(FIELD_MANAGER)
                            .force(false)
                            .buildCall(null),
                    V1Patch.PATCH_FORMAT_APPLY_YAML,
                    client
            );
            return toServiceSnapshot(applied);
        } catch (ApiException ex) {
            throw translateApiException("Failed to apply Service " + namespace + "/" + name, ex);
        }
    }

    @Override
    public List<PodSnapshot> listDeploymentPods(
            ClusterConnectionContext ctx,
            String namespace,
            Map<String, String> matchLabels
    ) {
        ApiClient client = buildIsolatedClient(ctx);
        String labelSelector = matchLabels.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        try {
            V1PodList list = new CoreV1Api(client)
                    .listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .limit(50)
                    .execute();
            if (list == null || list.getItems() == null) {
                return List.of();
            }
            List<PodSnapshot> snapshots = new ArrayList<>(list.getItems().size());
            for (V1Pod pod : list.getItems()) {
                snapshots.add(toPodSnapshot(pod));
            }
            return snapshots;
        } catch (ApiException ex) {
            throw translateApiException("Failed to list Pods in namespace " + namespace, ex);
        }
    }

    @Override
    public List<String> listRecentWarningEvents(
            ClusterConnectionContext ctx,
            String namespace,
            String involvedObjectName
    ) {
        ApiClient client = buildIsolatedClient(ctx);
        try {
            CoreV1EventList events = new CoreV1Api(client)
                    .listNamespacedEvent(namespace)
                    .fieldSelector("type=Warning,involvedObject.name=" + involvedObjectName)
                    .limit(10)
                    .execute();
            if (events == null || events.getItems() == null) {
                return List.of();
            }
            List<String> msgs = new ArrayList<>();
            for (CoreV1Event ev : events.getItems()) {
                String reason = ev.getReason() != null ? ev.getReason() : "Warning";
                String msg = ev.getMessage() != null ? ev.getMessage() : "";
                msgs.add(reason + ": " + msg);
            }
            return msgs;
        } catch (ApiException ex) {
            // Events read may be unavailable on heavily restricted roles; never crash rollout polling
            return List.of();
        }
    }

    /**
     * Constructs an isolated {@link ApiClient} with strict HTTPS, explicit CA PEM trust,
     * disabled redirects, bounded timeouts, and SSRF-safe DNS resolution.
     */
    ApiClient buildIsolatedClient(ClusterConnectionContext ctx) {
        if (ctx == null || ctx.apiServerUrl() == null || !ctx.apiServerUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new KubernetesClientException("CLUSTER_TLS_ERROR", 0,
                    "Kubernetes API server endpoint must use HTTPS");
        }
        URI uri = URI.create(ctx.apiServerUrl());
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new KubernetesClientException("CLUSTER_UNAVAILABLE", 0, "Invalid Kubernetes API server URL");
        }
        boolean allowLoopback = ctx.allowLocalLoopback() && allowLocalDevWorker;
        if (isLoopbackHost(uri.getHost()) && !allowLoopback) {
            throw new KubernetesClientException("CLUSTER_UNAVAILABLE", 0,
                    "Loopback Kubernetes cluster connections are disabled on this worker");
        }
        if (ctx.caCertificatePem() == null || !ctx.caCertificatePem().contains("-----BEGIN CERTIFICATE-----")) {
            throw new KubernetesClientException("CLUSTER_TLS_ERROR", 0,
                    "Valid X.509 CA certificate PEM is required for TLS verification");
        }
        if (ctx.bearerToken() == null || ctx.bearerToken().length == 0) {
            throw new KubernetesClientException("CLUSTER_AUTH_FAILED", 401,
                    "Cluster ServiceAccount bearer token is missing");
        }

        int timeoutSec = Math.max(5, Math.min(ctx.timeoutSeconds() > 0 ? ctx.timeoutSeconds() : 15, 30));

        OkHttpClient baseHttp = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSec, 10), TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .callTimeout(timeoutSec + 5L, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(ssrfSafeDns(allowLoopback))
                .build();

        ApiClient apiClient = new ApiClient(baseHttp);
        apiClient.setBasePath(ctx.apiServerUrl());
        apiClient.setVerifyingSsl(true); // ALWAYS verify TLS — never disable!
        apiClient.setSslCaCert(new ByteArrayInputStream(ctx.caCertificatePem().getBytes(StandardCharsets.UTF_8)));
        apiClient.setApiKeyPrefix("Bearer");
        apiClient.setApiKey(new String(ctx.bearerToken()));
        return apiClient;
    }

    private static Dns ssrfSafeDns(boolean allowLoopback) {
        return hostname -> {
            String lower = hostname.toLowerCase(Locale.ROOT);
            if ("metadata.google.internal".equals(lower)
                    || "metadata.goog".equals(lower)
                    || "169.254.169.254".equals(lower)
                    || "100.100.100.200".equals(lower)) {
                throw new UnknownHostException("Blocked metadata endpoint by SSRF policy: " + hostname);
            }
            List<InetAddress> resolved = Dns.SYSTEM.lookup(hostname);
            for (InetAddress addr : resolved) {
                if (isForbiddenAddress(addr, allowLoopback)) {
                    throw new UnknownHostException("Blocked restricted Kubernetes API IP by SSRF policy: " + hostname);
                }
            }
            return resolved;
        };
    }

    static boolean isForbiddenAddress(InetAddress address, boolean allowLoopback) {
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        if (address.isLoopbackAddress()) {
            return !allowLoopback;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 224) {
                return true;
            }
        } else if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || lower.endsWith(".localhost")
                || "127.0.0.1".equals(lower) || lower.startsWith("127.")
                || "::1".equals(lower) || "[::1]".equals(lower);
    }

    private static DeploymentSnapshot toDeploymentSnapshot(V1Deployment dep) {
        String uid = dep.getMetadata() != null ? dep.getMetadata().getUid() : null;
        String name = dep.getMetadata() != null ? dep.getMetadata().getName() : "";
        String ns = dep.getMetadata() != null ? dep.getMetadata().getNamespace() : "";
        long gen = dep.getMetadata() != null && dep.getMetadata().getGeneration() != null
                ? dep.getMetadata().getGeneration() : 1L;
        Map<String, String> labels = dep.getMetadata() != null && dep.getMetadata().getLabels() != null
                ? Map.copyOf(dep.getMetadata().getLabels()) : Map.of();

        int specReplicas = dep.getSpec() != null && dep.getSpec().getReplicas() != null
                ? dep.getSpec().getReplicas() : 0;
        List<String> images = new ArrayList<>();
        if (dep.getSpec() != null && dep.getSpec().getTemplate() != null
                && dep.getSpec().getTemplate().getSpec() != null
                && dep.getSpec().getTemplate().getSpec().getContainers() != null) {
            for (V1Container c : dep.getSpec().getTemplate().getSpec().getContainers()) {
                if (c.getImage() != null) {
                    images.add(c.getImage());
                }
            }
        }

        long obsGen = 0L;
        int statusRep = 0, updatedRep = 0, readyRep = 0, availRep = 0;
        List<DeploymentConditionInfo> conditions = new ArrayList<>();
        if (dep.getStatus() != null) {
            obsGen = dep.getStatus().getObservedGeneration() != null ? dep.getStatus().getObservedGeneration() : 0L;
            statusRep = dep.getStatus().getReplicas() != null ? dep.getStatus().getReplicas() : 0;
            updatedRep = dep.getStatus().getUpdatedReplicas() != null ? dep.getStatus().getUpdatedReplicas() : 0;
            readyRep = dep.getStatus().getReadyReplicas() != null ? dep.getStatus().getReadyReplicas() : 0;
            availRep = dep.getStatus().getAvailableReplicas() != null ? dep.getStatus().getAvailableReplicas() : 0;
            if (dep.getStatus().getConditions() != null) {
                for (V1DeploymentCondition c : dep.getStatus().getConditions()) {
                    conditions.add(new DeploymentConditionInfo(c.getType(), c.getStatus(), c.getReason(), c.getMessage()));
                }
            }
        }

        return new DeploymentSnapshot(
                uid, name, ns, gen, obsGen, specReplicas, statusRep, updatedRep, readyRep, availRep,
                labels, images, conditions, dep.getMetadata() == null ? null : dep.getMetadata().getResourceVersion(),
                JSON.serialize(dep)
        );
    }

    private static ServiceSnapshot toServiceSnapshot(V1Service svc) {
        String uid = svc.getMetadata() != null ? svc.getMetadata().getUid() : null;
        String name = svc.getMetadata() != null ? svc.getMetadata().getName() : "";
        String ns = svc.getMetadata() != null ? svc.getMetadata().getNamespace() : "";
        String type = svc.getSpec() != null && svc.getSpec().getType() != null ? svc.getSpec().getType() : "ClusterIP";
        Map<String, String> labels = svc.getMetadata() != null && svc.getMetadata().getLabels() != null
                ? Map.copyOf(svc.getMetadata().getLabels()) : Map.of();
        return new ServiceSnapshot(uid, name, ns, type, labels,
                svc.getMetadata() == null ? null : svc.getMetadata().getResourceVersion(), JSON.serialize(svc));
    }

    private static PodSnapshot toPodSnapshot(V1Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "unknown";
        String phase = pod.getStatus() != null && pod.getStatus().getPhase() != null
                ? pod.getStatus().getPhase() : "Unknown";
        boolean scheduled = true;
        String unschedReason = null;
        String unschedMsg = null;
        boolean ready = false;
        if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
            for (V1PodCondition c : pod.getStatus().getConditions()) {
                if ("PodScheduled".equalsIgnoreCase(c.getType()) && "False".equalsIgnoreCase(c.getStatus())) {
                    scheduled = false;
                    unschedReason = c.getReason();
                    unschedMsg = c.getMessage();
                }
                if ("Ready".equalsIgnoreCase(c.getType()) && "True".equalsIgnoreCase(c.getStatus())) {
                    ready = true;
                }
            }
        }
        List<ContainerStatusSnapshot> statuses = new ArrayList<>();
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (V1ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                String waitReason = cs.getState() != null && cs.getState().getWaiting() != null
                        ? cs.getState().getWaiting().getReason() : null;
                String waitMsg = cs.getState() != null && cs.getState().getWaiting() != null
                        ? cs.getState().getWaiting().getMessage() : null;
                String termReason = cs.getState() != null && cs.getState().getTerminated() != null
                        ? cs.getState().getTerminated().getReason() : null;
                statuses.add(new ContainerStatusSnapshot(
                        cs.getName(),
                        cs.getImage(),
                        cs.getImageID(),
                        Boolean.TRUE.equals(cs.getReady()),
                        cs.getRestartCount() != null ? cs.getRestartCount() : 0,
                        waitReason,
                        waitMsg,
                        termReason
                ));
            }
        }
        return new PodSnapshot(name, phase, scheduled, unschedReason, unschedMsg, ready, statuses);
    }

    private static KubernetesClientException translateApiException(String context, ApiException ex) {
        Throwable cause = ex.getCause();
        if (hasCause(cause, SSLHandshakeException.class) || hasCause(cause, CertPathValidatorException.class)) {
            return new KubernetesClientException("CLUSTER_TLS_ERROR", ex.getCode(),
                    context + ": TLS certificate validation failed (" + cause.getMessage() + ")", ex);
        }
        int code = ex.getCode();
        String body = ex.getResponseBody() != null ? ex.getResponseBody() : ex.getMessage();
        String lowerBody = body != null ? body.toLowerCase(Locale.ROOT) : "";

        if (code == 401) {
            return new KubernetesClientException("CLUSTER_AUTH_FAILED", 401,
                    context + ": Kubernetes API rejected ServiceAccount bearer token (HTTP 401)", ex);
        }
        if (code == 403) {
            if (lowerBody.contains("exceeded quota") || lowerBody.contains("resourcequota")) {
                return new KubernetesClientException("K8S_QUOTA_EXCEEDED", 403,
                        context + ": Namespace ResourceQuota exceeded (HTTP 403)", ex);
            }
            if (lowerBody.contains("violates podsecurity") || lowerBody.contains("pod security")) {
                return new KubernetesClientException("K8S_MANIFEST_REJECTED", 403,
                        context + ": Pod Security Admission policy rejected workload (HTTP 403)", ex);
            }
            return new KubernetesClientException("K8S_RBAC_DENIED", 403,
                    context + ": Kubernetes RBAC denied operation (HTTP 403)", ex);
        }
        if (code == 404 && lowerBody.contains("namespaces")) {
            return new KubernetesClientException("K8S_NAMESPACE_INVALID", 404,
                    context + ": Target namespace does not exist in cluster (HTTP 404)", ex);
        }
        if (code == 400 || code == 409 || code == 422) {
            return new KubernetesClientException("K8S_MANIFEST_REJECTED", code,
                    context + ": Kubernetes API rejected manifest or field ownership conflict (HTTP " + code + ")", ex);
        }
        return new KubernetesClientException("CLUSTER_UNAVAILABLE", code,
                context + ": Kubernetes API error (HTTP " + code + "): " + (ex.getMessage() != null ? ex.getMessage() : "connection error"), ex);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> target) {
        Throwable curr = t;
        int depth = 0;
        while (curr != null && depth++ < 10) {
            if (target.isInstance(curr)) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }
}
