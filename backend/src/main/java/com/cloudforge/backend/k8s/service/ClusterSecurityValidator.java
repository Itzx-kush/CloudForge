package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.entity.KubernetesClusterType;
import com.cloudforge.backend.oci.service.RegistrySecurityValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Enforces strict Kubernetes cluster endpoint security (HTTPS-only, X.509 CA certificate
 * pinning, operator host allowlist, SSRF protection against link-local/metadata/private/loopback
 * addresses) and Kubernetes namespace/identifier safety rules.
 */
@Service
public class ClusterSecurityValidator {

    private static final Pattern DNS_1123_LABEL = Pattern.compile("^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern CLUSTER_ID_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{1,126}[a-z0-9])?$");
    private static final Pattern SERVICE_ACCOUNT_PATTERN = Pattern.compile("^[a-z0-9](?:[-a-z0-9.]{0,126}[a-z0-9])?$");

    private static final Set<String> FORBIDDEN_NAMESPACES = Set.of(
            "default",
            "kube-system",
            "kube-public",
            "kube-node-lease",
            "ingress-nginx",
            "cert-manager",
            "istio-system",
            "calico-system",
            "tigera-operator",
            "amazon-cloudwatch",
            "gke-system",
            "aks-system"
    );

    private static final Set<String> FORBIDDEN_NAMESPACE_PREFIXES = Set.of(
            "kube-",
            "openshift-",
            "k8s-"
    );

    private static final Set<String> FORBIDDEN_METADATA_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.goog",
            "metadata",
            "instance-data",
            "169.254.169.254",
            "100.100.100.200",
            "fd00:ec2::254"
    );

    private final KubernetesDeploymentProperties k8sProperties;

    public ClusterSecurityValidator(KubernetesDeploymentProperties k8sProperties) {
        this.k8sProperties = k8sProperties;
    }

    public ValidatedClusterEndpoint validateClusterEndpoint(
            String rawApiServerUrl,
            KubernetesClusterType clusterType,
            boolean allowLocalLoopbackRequested
    ) {
        if (!StringUtils.hasText(rawApiServerUrl)) {
            throw new BadRequestException("Kubernetes API server URL is required",
                    Map.of("apiServerUrl", "API server URL must not be blank"));
        }
        String trimmed = rawApiServerUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid Kubernetes API server URL syntax",
                    Map.of("apiServerUrl", "Provide a valid https://host:port URL"));
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ForbiddenOperationException(
                    "Kubernetes API server connections strictly require HTTPS; plaintext HTTP is prohibited"
            );
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new BadRequestException("Kubernetes API server URL must not include credentials, query strings, or fragments",
                    Map.of("apiServerUrl", "Remove embedded userinfo, query parameters, or fragments"));
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new BadRequestException("Kubernetes API server URL must specify a valid host",
                    Map.of("apiServerUrl", "Host is missing"));
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new BadRequestException("Invalid Kubernetes API server port",
                    Map.of("apiServerUrl", "Port must be between 1 and 65535"));
        }
        String path = uri.getPath();
        if (StringUtils.hasText(path) && !"/".equals(path)) {
            if (path.contains("..") || path.contains("\\") || path.contains("//")) {
                throw new BadRequestException("Kubernetes API server path contains unsafe segments",
                        Map.of("apiServerUrl", "Invalid URL path"));
            }
        }

        if (FORBIDDEN_METADATA_HOSTS.contains(normalizedHost)
                || normalizedHost.endsWith(".internal")
                || normalizedHost.endsWith(".local")) {
            throw new ForbiddenOperationException(
                    "Kubernetes API server host \"" + normalizedHost + "\" is blocked by SSRF security policy"
            );
        }

        boolean isLoopback = RegistrySecurityValidator.isLoopbackHostname(normalizedHost);
        boolean localDevAllowed = k8sProperties.isAllowLocalDevClusters()
                && clusterType != null
                && clusterType.isLocalDevCluster()
                && allowLocalLoopbackRequested;

        if (isLoopback && !localDevAllowed) {
            throw new ForbiddenOperationException(
                    "Loopback Kubernetes API endpoints are blocked unless a local dev cluster type, "
                            + "allowLocalLoopback=true, and CLOUDFORGE_K8S_ALLOW_LOCAL_DEV_CLUSTERS=true are explicitly enabled"
            );
        }

        String hostPort = port > 0 ? normalizedHost + ":" + port : normalizedHost;
        Set<String> allowedHosts = k8sProperties.parsedAllowedApiHosts();
        if (allowedHosts.isEmpty()
                || (!allowedHosts.contains(hostPort) && !allowedHosts.contains(normalizedHost))) {
            throw new ForbiddenOperationException(
                    "Kubernetes API server host \"" + hostPort + "\" is not in the operator-approved allowlist (CLOUDFORGE_K8S_ALLOWED_API_HOSTS)"
            );
        }

        validateResolvedAddresses(normalizedHost, localDevAllowed);

        String normalizedPath = StringUtils.hasText(path) && !"/".equals(path)
                ? path.replaceAll("/+$", "")
                : "";
        String normalizedUrl = "https://" + hostPort + normalizedPath;
        return new ValidatedClusterEndpoint(normalizedUrl, hostPort, isLoopback && localDevAllowed);
    }

    public ValidatedCaCertificate validateCaCertificatePem(String rawPem) {
        if (!StringUtils.hasText(rawPem)) {
            throw new BadRequestException("Cluster CA certificate PEM is required for TLS verification",
                    Map.of("caCertificatePem", "CA certificate PEM is required"));
        }
        String trimmed = rawPem.trim();
        if (trimmed.contains("PRIVATE KEY") || trimmed.contains("kubeconfig") || trimmed.contains("client-key-data")) {
            throw new BadRequestException(
                    "Cluster CA certificate field must contain public X.509 CA certificates only — never private keys or kubeconfigs",
                    Map.of("caCertificatePem", "Do not paste private keys or kubeconfig files")
            );
        }
        if (!trimmed.contains("-----BEGIN CERTIFICATE-----") || !trimmed.contains("-----END CERTIFICATE-----")) {
            throw new BadRequestException(
                    "Cluster CA certificate must be a valid PEM-encoded X.509 certificate",
                    Map.of("caCertificatePem", "Expected -----BEGIN CERTIFICATE----- PEM block")
            );
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(
                    new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8))
            );
            if (certs == null || certs.isEmpty()) {
                throw new BadRequestException("No valid X.509 certificates found in caCertificatePem");
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int count = 0;
            for (Certificate cert : certs) {
                if (!(cert instanceof X509Certificate x509)) {
                    throw new BadRequestException("Cluster CA bundle must contain X.509 certificates only");
                }
                md.update(x509.getEncoded());
                count++;
                if (count > 8) {
                    throw new BadRequestException("Cluster CA bundle exceeds maximum of 8 certificates");
                }
            }
            String fingerprint = HexFormat.of().formatHex(md.digest());
            return new ValidatedCaCertificate(trimmed, fingerprint);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException(
                    "Invalid X.509 CA certificate PEM: " + ex.getMessage(),
                    Map.of("caCertificatePem", "Unable to parse X.509 certificate")
            );
        }
    }

    public String validateClusterIdentifier(String rawId) {
        if (!StringUtils.hasText(rawId)) {
            throw new BadRequestException("Cluster identifier is required",
                    Map.of("clusterIdentifier", "Cluster identifier must not be blank"));
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        if (!CLUSTER_ID_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Cluster identifier must be 3-128 lowercase alphanumeric characters, hyphens, dots, or underscores",
                    Map.of("clusterIdentifier", "Invalid cluster identifier format")
            );
        }
        return normalized;
    }

    public String validateServiceAccountName(String rawName, String fieldName) {
        if (!StringUtils.hasText(rawName)) {
            throw new BadRequestException("ServiceAccount name is required",
                    Map.of(fieldName, "ServiceAccount name must not be blank"));
        }
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (!SERVICE_ACCOUNT_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Invalid Kubernetes ServiceAccount name \"" + rawName + "\"",
                    Map.of(fieldName, "Must be a valid Kubernetes resource name")
            );
        }
        if ("default".equals(normalized) || normalized.contains("cluster-admin") || normalized.contains("system:")) {
            throw new ForbiddenOperationException(
                    "ServiceAccount \"" + normalized + "\" is prohibited; provision a dedicated least-privilege ServiceAccount"
            );
        }
        return normalized;
    }

    public String validateOptionalPullSecretName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return null;
        }
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (!DNS_1123_LABEL.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Invalid imagePullSecret name \"" + rawName + "\"",
                    Map.of("imagePullSecretName", "Must be a valid DNS-1123 label")
            );
        }
        return normalized;
    }

    public String validateNamespaceName(String rawNamespace) {
        if (!StringUtils.hasText(rawNamespace)) {
            throw new BadRequestException("Kubernetes namespace is required",
                    Map.of("namespaceName", "Namespace must not be blank"));
        }
        String normalized = rawNamespace.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 63 || !DNS_1123_LABEL.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Invalid Kubernetes namespace \"" + rawNamespace + "\" (must be DNS-1123 label <= 63 chars)",
                    Map.of("namespaceName", "Use lowercase letters, digits, and hyphens only")
            );
        }
        if (isForbiddenNamespace(normalized)) {
            throw new ForbiddenOperationException(
                    "Deployment into reserved or control-plane namespace \"" + normalized + "\" is strictly prohibited"
            );
        }
        return normalized;
    }

    public static boolean isForbiddenNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return true;
        }
        String lower = namespace.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_NAMESPACES.contains(lower)) {
            return true;
        }
        for (String prefix : FORBIDDEN_NAMESPACE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public String generateDeterministicNamespace(
            UUID workspaceId,
            String projectSlug,
            EnvironmentType environmentType
    ) {
        String wsShort = workspaceId.toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String safeProj = (projectSlug == null ? "app" : projectSlug)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (safeProj.isEmpty()) {
            safeProj = "app";
        }
        if (safeProj.length() > 20) {
            safeProj = safeProj.substring(0, 20).replaceAll("-+$", "");
        }
        String envShort = switch (environmentType) {
            case DEVELOPMENT -> "dev";
            case STAGING -> "stg";
            case PRODUCTION -> "prod";
        };
        String candidate = "cf-" + wsShort + "-" + safeProj + "-" + envShort;
        if (candidate.length() > 63) {
            candidate = candidate.substring(0, 63).replaceAll("-+$", "");
        }
        return validateNamespaceName(candidate);
    }

    public void validateResolvedAddresses(String host, boolean allowLoopback) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new ForbiddenOperationException("Kubernetes API host failed DNS resolution");
            }
            for (InetAddress address : addresses) {
                if (RegistrySecurityValidator.isForbiddenAddress(address, allowLoopback)) {
                    throw new ForbiddenOperationException(
                            "Kubernetes API host \"" + host + "\" resolves to a restricted network address blocked by SSRF policy"
                    );
                }
            }
        } catch (UnknownHostException ex) {
            if (host.matches("^[0-9.]+$") || host.contains(":") || RegistrySecurityValidator.isLoopbackHostname(host)) {
                throw new ForbiddenOperationException("Kubernetes API host \"" + host + "\" is blocked by SSRF policy");
            }
        }
    }

    public record ValidatedClusterEndpoint(
            String apiServerUrl,
            String hostPort,
            boolean allowLocalLoopback
    ) {
    }

    public record ValidatedCaCertificate(
            String pem,
            String fingerprintSha256
    ) {
    }
}
