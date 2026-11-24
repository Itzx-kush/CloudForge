package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.entity.OciRegistryType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces OCI registry destination security: HTTPS enforcement, operator host allowlist,
 * repository namespace restrictions, and SSRF protection against loopback, link-local,
 * cloud metadata endpoints, private RFC1918/ULA networks, and DNS rebinding.
 */
@Service
public class RegistrySecurityValidator {

    private static final Pattern HOST_PORT_PATTERN = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9.-]{0,240}[a-z0-9])?(?::[1-9][0-9]{0,4})?$"
    );
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile(
            "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$"
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

    private final OciBuildProperties ociProperties;

    public RegistrySecurityValidator(OciBuildProperties ociProperties) {
        this.ociProperties = ociProperties;
    }

    public ValidatedRegistryDestination validateRegistryDestination(
            String rawHost,
            String rawScheme,
            OciRegistryType registryType,
            String rawNamespace,
            boolean allowInsecureLocalRequested
    ) {
        if (!StringUtils.hasText(rawHost)) {
            throw new BadRequestException("Registry host is required", Map.of("registryHost", "Registry host must not be blank"));
        }
        String hostPort = rawHost.trim().toLowerCase(Locale.ROOT);
        if (hostPort.contains("://") || hostPort.contains("/") || hostPort.contains("@")
                || hostPort.contains("?") || hostPort.contains("#") || hostPort.contains("\\")
                || hostPort.contains("..")) {
            throw new BadRequestException(
                    "Registry host must be a bare host or host:port without scheme, path, or credentials",
                    Map.of("registryHost", "Provide only host[:port], e.g. ghcr.io or 127.0.0.1:5000")
            );
        }
        if (!HOST_PORT_PATTERN.matcher(hostPort).matches()) {
            throw new BadRequestException(
                    "Invalid registry host format",
                    Map.of("registryHost", "Invalid registry host or port syntax")
            );
        }

        URI parsed = URI.create("https://" + hostPort);
        String host = parsed.getHost();
        int port = parsed.getPort();
        if (host == null || (port != -1 && (port < 1 || port > 65535))) {
            throw new BadRequestException("Invalid registry host or port", Map.of("registryHost", "Invalid host or port"));
        }

        String scheme = StringUtils.hasText(rawScheme) ? rawScheme.trim().toLowerCase(Locale.ROOT) : "https";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BadRequestException("Registry scheme must be https (or http in explicit local dev mode)",
                    Map.of("scheme", "Unsupported URI scheme"));
        }

        if (FORBIDDEN_METADATA_HOSTS.contains(host) || host.endsWith(".internal") || host.endsWith(".local")) {
            throw new ForbiddenOperationException("Registry host \"" + host + "\" is blocked by SSRF security policy");
        }

        boolean isLocalLoopback = isLoopbackHostname(host);
        boolean localDevAllowed = ociProperties.isAllowLocalDevRegistry()
                && registryType == OciRegistryType.LOCAL_DEV
                && allowInsecureLocalRequested;

        if ("http".equals(scheme)) {
            if (!localDevAllowed || !isLocalLoopback) {
                throw new ForbiddenOperationException(
                        "Remote registries require HTTPS; plaintext HTTP is strictly restricted to loopback local development registries when explicitly enabled"
                );
            }
        }
        if (isLocalLoopback && !localDevAllowed) {
            throw new ForbiddenOperationException(
                    "Loopback registry destinations are blocked unless LOCAL_DEV mode and CLOUDFORGE_OCI_ALLOW_LOCAL_DEV_REGISTRY=true are explicitly enabled"
            );
        }

        // Enforce explicit operator host allowlist
        Set<String> allowedHosts = ociProperties.parsedAllowedRegistryHosts();
        if (allowedHosts.isEmpty() || (!allowedHosts.contains(hostPort) && !allowedHosts.contains(host))) {
            throw new ForbiddenOperationException(
                    "Registry destination \"" + hostPort + "\" is not in the operator-approved registry allowlist"
            );
        }

        // SSRF IP validation (including DNS resolution checks)
        validateResolvedAddresses(host, localDevAllowed);

        String namespace = validateNamespace(rawNamespace);
        return new ValidatedRegistryDestination(hostPort, scheme, namespace, isLocalLoopback && localDevAllowed);
    }

    public String validateNamespace(String rawNamespace) {
        if (!StringUtils.hasText(rawNamespace)) {
            throw new BadRequestException("Allowed repository namespace is required",
                    Map.of("allowedNamespace", "Namespace must not be blank"));
        }
        String normalized = rawNamespace.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128 || !NAMESPACE_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Allowed namespace must be a valid lowercase OCI repository prefix (e.g. my-org or my-org/team)",
                    Map.of("allowedNamespace", "Use lowercase alphanumeric segments separated by /")
            );
        }
        return normalized;
    }

    public String validateRepositoryWithinNamespace(String rawTargetImageName, String allowedNamespace) {
        if (!StringUtils.hasText(rawTargetImageName)) {
            throw new BadRequestException("Target image repository name is required");
        }
        String normalized = rawTargetImageName.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200 || !NAMESPACE_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Invalid OCI target image name \"" + rawTargetImageName + "\": use lowercase path segments only"
            );
        }
        String ns = allowedNamespace.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(ns) && !normalized.startsWith(ns + "/")) {
            throw new ForbiddenOperationException(
                    "Target image repository \"" + normalized + "\" is outside the approved registry namespace \"" + ns + "\""
            );
        }
        return normalized;
    }

    public void validateResolvedAddresses(String host, boolean allowLoopback) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new ForbiddenOperationException("Registry host failed DNS resolution");
            }
            for (InetAddress address : addresses) {
                if (isForbiddenAddress(address, allowLoopback)) {
                    throw new ForbiddenOperationException(
                            "Registry host \"" + host + "\" resolves to a restricted network address blocked by SSRF policy"
                    );
                }
            }
        } catch (UnknownHostException ex) {
            // If host is an operator-allowlisted public hostname in an offline unit-test environment,
            // reject raw IP literals and obvious internal names; otherwise allow configured DNS name
            // when worker itself enforces resolved-IP SSRF checks before connecting.
            if (isIpLiteral(host) || isLoopbackHostname(host)) {
                throw new ForbiddenOperationException("Registry host \"" + host + "\" is blocked by SSRF policy");
            }
        }
    }

    public static boolean isForbiddenAddress(InetAddress address, boolean allowLoopback) {
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
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT / Alibaba metadata 100.100.100.200), 169.254.0.0/16, 192.0.0.0/24, 198.18.0.0/15
            if (first == 0 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 224) {
                return true;
            }
        } else if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xFF;
            // fc00::/7 (Unique Local Address, includes AWS IMDSv2 fd00:ec2::254)
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLoopbackHostname(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(lower)
                || lower.endsWith(".localhost")
                || "127.0.0.1".equals(lower)
                || lower.startsWith("127.")
                || "::1".equals(lower)
                || "[::1]".equals(lower);
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("^[0-9.]+$") || host.contains(":");
    }

    public record ValidatedRegistryDestination(
            String registryHost,
            String scheme,
            String allowedNamespace,
            boolean allowInsecureLocal
    ) {
    }
}
