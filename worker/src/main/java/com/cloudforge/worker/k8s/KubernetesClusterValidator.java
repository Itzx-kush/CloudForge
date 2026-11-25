package com.cloudforge.worker.k8s;

import com.cloudforge.worker.client.ControlPlaneClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes worker-side Kubernetes cluster connectivity, TLS verification, API group,
 * namespace availability, and least-privilege RBAC ({@code SelfSubjectAccessReview}) validation.
 */
public class KubernetesClusterValidator {

    private final KubernetesApiClient k8sClient;

    public KubernetesClusterValidator(KubernetesApiClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    public ValidationOutcome validate(
            ControlPlaneClient.ClusterValidationTask task,
            KubernetesApiClient.ClusterConnectionContext ctx
    ) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        List<String> verifiedNamespaces = new ArrayList<>();
        try {
            // 1. Verify HTTPS/TLS connectivity & Kubernetes API server version
            KubernetesApiClient.ServerVersionInfo version = k8sClient.getServerVersion(ctx);
            capabilities.put("serverVersion", version.gitVersion());
            capabilities.put("platform", version.platform());
            capabilities.put("tlsVerified", true);

            // 2. Verify apps/v1 API availability
            boolean appsV1 = k8sClient.hasAppsV1Api(ctx);
            capabilities.put("appsV1Available", appsV1);
            if (!appsV1) {
                return ValidationOutcome.failed(
                        version.gitVersion(),
                        capabilities,
                        "Kubernetes cluster does not expose required apps/v1 API group"
                );
            }

            // 3. Verify deployment identity is NOT over-privileged cluster-admin
            boolean canCreateClusterRoleBindings = k8sClient.checkSelfSubjectAccess(
                    ctx, null, "rbac.authorization.k8s.io", "clusterrolebindings", "create"
            );
            boolean canWildcardAll = k8sClient.checkSelfSubjectAccess(ctx, null, "*", "*", "*");
            capabilities.put("leastPrivilegeVerified", !canCreateClusterRoleBindings && !canWildcardAll);
            if (canCreateClusterRoleBindings || canWildcardAll) {
                return ValidationOutcome.failed(
                        version.gitVersion(),
                        capabilities,
                        "Cluster credential appears over-privileged (cluster-admin / clusterrolebindings create permitted); provision a least-privilege namespace Role instead"
                );
            }

            // 4. Verify each bound namespace exists and RBAC grants required deployment operations
            List<String> namespacesToCheck = task.expectedNamespaces() != null ? task.expectedNamespaces() : List.of();
            Map<String, Object> namespaceChecks = new LinkedHashMap<>();
            List<String> missingPermissions = new ArrayList<>();

            for (String ns : namespacesToCheck) {
                Map<String, Object> nsCap = new LinkedHashMap<>();
                boolean deployGet = k8sClient.checkSelfSubjectAccess(ctx, ns, "apps", "deployments", "get");
                boolean deployCreate = k8sClient.checkSelfSubjectAccess(ctx, ns, "apps", "deployments", "create");
                boolean deployPatch = k8sClient.checkSelfSubjectAccess(ctx, ns, "apps", "deployments", "patch");
                boolean svcGet = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "services", "get");
                boolean svcCreate = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "services", "create");
                boolean svcPatch = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "services", "patch");
                boolean podsList = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "pods", "list");
                boolean rsList = k8sClient.checkSelfSubjectAccess(ctx, ns, "apps", "replicasets", "list");
                boolean eventsList = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "events", "list");
                boolean secretsGet = k8sClient.checkSelfSubjectAccess(ctx, ns, "", "secrets", "get");

                nsCap.put("deploymentsAccess", deployGet && deployCreate && deployPatch);
                nsCap.put("servicesAccess", svcGet && svcCreate && svcPatch);
                nsCap.put("podsVisibility", podsList);
                nsCap.put("replicaSetsVisibility", rsList);
                nsCap.put("eventsVisibility", eventsList);
                nsCap.put("secretsReadBlocked", !secretsGet);

                // Inspect namespace if readable (RBAC may allowlist namespace get, or rely on SSAR + deployment list)
                try {
                    KubernetesApiClient.NamespaceSnapshot nsSnap = k8sClient.inspectNamespace(ctx, ns);
                    nsCap.put("namespaceExists", nsSnap.exists());
                    nsCap.put("namespacePhase", nsSnap.phase());
                    if (nsSnap.labels() != null && nsSnap.labels().containsKey("pod-security.kubernetes.io/enforce")) {
                        nsCap.put("podSecurityEnforce", nsSnap.labels().get("pod-security.kubernetes.io/enforce"));
                    }
                    if (!nsSnap.exists()) {
                        missingPermissions.add("Namespace \"" + ns + "\" does not exist in cluster");
                    }
                } catch (KubernetesApiClient.KubernetesClientException ex) {
                    if ("K8S_RBAC_DENIED".equals(ex.getFailureCategory())) {
                        nsCap.put("namespaceInspectable", false);
                    } else if ("K8S_NAMESPACE_INVALID".equals(ex.getFailureCategory())) {
                        nsCap.put("namespaceExists", false);
                        missingPermissions.add("Namespace \"" + ns + "\" does not exist in cluster");
                    } else {
                        throw ex;
                    }
                }

                if (!(deployGet && deployCreate && deployPatch)) {
                    missingPermissions.add("Missing Deployment RBAC (get/create/patch) in namespace " + ns);
                }
                if (!(svcGet && svcCreate && svcPatch)) {
                    missingPermissions.add("Missing Service RBAC (get/create/patch) in namespace " + ns);
                }
                if (!podsList || !rsList) {
                    missingPermissions.add("Missing Pod/ReplicaSet read RBAC in namespace " + ns);
                }
                if (missingPermissions.isEmpty()) {
                    verifiedNamespaces.add(ns);
                }
                namespaceChecks.put(ns, nsCap);
            }

            capabilities.put("namespaces", namespaceChecks);
            capabilities.put("validatedAt", Instant.now().toString());

            if (!missingPermissions.isEmpty()) {
                return ValidationOutcome.failed(
                        version.gitVersion(),
                        capabilities,
                        String.join("; ", missingPermissions)
                );
            }

            return new ValidationOutcome(true, version.gitVersion(), capabilities, verifiedNamespaces, null);
        } catch (KubernetesApiClient.KubernetesClientException ex) {
            capabilities.put("failureCategory", ex.getFailureCategory());
            return ValidationOutcome.failed(null, capabilities, ex.getMessage());
        } catch (Exception ex) {
            return ValidationOutcome.failed(null, capabilities, "Cluster validation error: " + ex.getMessage());
        }
    }

    public record ValidationOutcome(
            boolean valid,
            String kubernetesVersion,
            Map<String, Object> supportedCapabilities,
            List<String> verifiedNamespaces,
            String errorSummary
    ) {
        static ValidationOutcome failed(String version, Map<String, Object> caps, String error) {
            return new ValidationOutcome(false, version, caps, List.of(), error);
        }
    }
}
