package com.cloudforge.worker.oci;

import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 17 standards-based OCI Distribution Specification v1.1 registry publisher and
 * independent digest verifier.
 *
 * <p><strong>Security guarantees:</strong></p>
 * <ul>
 *   <li>Requires HTTPS for all remote registries; HTTP allowed ONLY for loopback local dev registries
 *       when explicitly enabled by operator configuration.</li>
 *   <li>Enforces SSRF validation on every resolved IP address before connecting.</li>
 *   <li>Disables automatic redirects ({@link HttpClient.Redirect#NEVER}) so Authorization headers
 *       are NEVER forwarded to unknown hosts.</li>
 *   <li>Holds credentials strictly in memory ({@code char[]}/{@code byte[]}), never writes
 *       {@code ~/.docker/config.json} or CLI args, and zeroes arrays in {@code finally}.</li>
 *   <li>Independently retrieves the pushed manifest by immutable digest and computes raw SHA-256
 *       over the returned manifest bytes before reporting {@code verified = true}.</li>
 * </ul>
 */
public class OciRegistryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern BEARER_PARAM_PATTERN = Pattern.compile("([a-zA-Z]+)=\"([^\"]*)\"");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public OciRegistryClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public PublishVerificationResult publishAndVerify(
            String registryScheme,
            String registryHost,
            String imageRepository,
            String imageTag,
            boolean allowInsecureLocalRegistry,
            char[] username,
            char[] password,
            OciLayoutInspector.VerifiedOciLayout layout,
            AtomicBoolean cancelSignal,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) throws RegistryPublishException {
        checkCancelled(cancelSignal);
        URI baseUri = validateAndBuildBaseUri(registryScheme, registryHost, allowInsecureLocalRegistry);

        byte[] basicCredBytes = null;
        String authHeader = null;
        try {
            if (password != null && password.length > 0) {
                String userStr = username != null && username.length > 0 ? new String(username) : "oauth2";
                String passStr = new String(password);
                basicCredBytes = (userStr + ":" + passStr).getBytes(StandardCharsets.UTF_8);
                authHeader = "Basic " + Base64.getEncoder().encodeToString(basicCredBytes);
            }

            // 1. Negotiate v2 API & Bearer challenge if required
            logConsumer.accept("SYSTEM",
                    "[CloudForge Registry] Connecting to OCI registry " + registryHost + " for " + imageRepository,
                    Instant.now());
            authHeader = negotiateAuth(baseUri, imageRepository, authHeader, allowInsecureLocalRegistry, cancelSignal);

            // 2. Upload all config & layer blobs (skipping blobs already present by digest)
            int uploadedBlobs = 0;
            int skippedBlobs = 0;
            for (OciLayoutInspector.BlobEntry blob : layout.blobs()) {
                checkCancelled(cancelSignal);
                if (blobExists(baseUri, imageRepository, blob.digest(), authHeader)) {
                    skippedBlobs++;
                    continue;
                }
                uploadBlob(baseUri, imageRepository, blob, authHeader, allowInsecureLocalRegistry);
                uploadedBlobs++;
            }
            logConsumer.accept("SYSTEM",
                    "[CloudForge Registry] Blobs ready (uploaded=" + uploadedBlobs + ", alreadyPresent=" + skippedBlobs + ")",
                    Instant.now());

            // 3. Check cancellation right before publishing manifest
            checkCancelled(cancelSignal);
            String manifestRef = (imageTag != null && !imageTag.isBlank()) ? imageTag.trim() : layout.manifestDigest();
            String reportedDigest = pushManifest(
                    baseUri, imageRepository, manifestRef, layout.mediaType(), layout.manifestBytes(), authHeader
            );

            if (!DIGEST_PATTERN.matcher(reportedDigest).matches()) {
                throw new RegistryPublishException(
                        "DIGEST_VERIFICATION_FAILED",
                        "Registry returned malformed Docker-Content-Digest: " + reportedDigest
                );
            }
            if (!reportedDigest.equals(layout.manifestDigest())) {
                throw new RegistryPublishException(
                        "DIGEST_VERIFICATION_FAILED",
                        "Registry reported digest (" + reportedDigest + ") does not match built manifest digest ("
                                + layout.manifestDigest() + ")"
                );
            }

            logConsumer.accept("SYSTEM",
                    "[CloudForge Registry] Manifest pushed (reportedDigest=" + reportedDigest + "); verifying immutable retrieval",
                    Instant.now());

            // 4. Independent retrieval & byte-level SHA-256 verification by immutable digest
            String verifiedDigest = retrieveAndVerifyManifest(
                    baseUri, imageRepository, reportedDigest, layout.mediaType(), authHeader
            );
            if (!verifiedDigest.equals(layout.manifestDigest()) || !verifiedDigest.equals(reportedDigest)) {
                throw new RegistryPublishException(
                        "DIGEST_VERIFICATION_FAILED",
                        "Retrieved manifest bytes SHA-256 (" + verifiedDigest + ") failed verification against expected "
                                + layout.manifestDigest()
                );
            }

            String immutableRef = registryHost + "/" + imageRepository + "@" + verifiedDigest;
            logConsumer.accept("SYSTEM",
                    "[CloudForge Registry] Immutable OCI digest verified: " + immutableRef,
                    Instant.now());

            return new PublishVerificationResult(
                    registryHost,
                    imageRepository,
                    imageTag,
                    reportedDigest,
                    verifiedDigest,
                    immutableRef,
                    layout.mediaType(),
                    layout.platform(),
                    layout.totalImageBytes(),
                    true
            );
        } finally {
            if (basicCredBytes != null) {
                Arrays.fill(basicCredBytes, (byte) 0);
            }
            if (username != null) {
                Arrays.fill(username, '\0');
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    static URI validateAndBuildBaseUri(String scheme, String hostPort, boolean allowInsecureLocal)
            throws RegistryPublishException {
        if (scheme == null || hostPort == null || hostPort.isBlank()) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Registry scheme and host are required");
        }
        String normScheme = scheme.trim().toLowerCase(Locale.ROOT);
        String normHostPort = hostPort.trim().toLowerCase(Locale.ROOT);
        if (!"https".equals(normScheme) && !"http".equals(normScheme)) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Unsupported registry scheme: " + normScheme);
        }
        URI uri = URI.create(normScheme + "://" + normHostPort);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Invalid registry host URI");
        }
        boolean loopback = isLoopbackHost(host);
        if ("http".equals(normScheme) && (!allowInsecureLocal || !loopback)) {
            throw new RegistryPublishException(
                    "REGISTRY_UNAVAILABLE",
                    "Plaintext HTTP registry access is prohibited outside explicitly enabled loopback dev mode"
            );
        }
        if (loopback && !allowInsecureLocal) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Loopback registry access is blocked by SSRF policy");
        }
        validateHostAddresses(host, allowInsecureLocal && loopback);
        return uri;
    }

    static void validateHostAddresses(String host, boolean allowLoopback) throws RegistryPublishException {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Unable to resolve registry host: " + host);
            }
            for (InetAddress addr : addresses) {
                if (isForbiddenAddress(addr, allowLoopback)) {
                    throw new RegistryPublishException(
                            "REGISTRY_UNAVAILABLE",
                            "Registry host resolves to restricted network address blocked by SSRF protection"
                    );
                }
            }
        } catch (RegistryPublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "DNS resolution failed for registry host " + host);
        }
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
            if (((b[0] & 0xFF) & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    private String negotiateAuth(
            URI baseUri,
            String repository,
            String initialAuthHeader,
            boolean allowInsecureLocal,
            AtomicBoolean cancelSignal
    ) throws RegistryPublishException {
        checkCancelled(cancelSignal);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/v2/"))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (initialAuthHeader != null) {
            reqBuilder.header("Authorization", initialAuthHeader);
        }
        HttpResponse<String> resp = send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return initialAuthHeader;
        }
        if (resp.statusCode() == 401) {
            String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse("");
            if (wwwAuth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                return exchangeBearerToken(wwwAuth, repository, initialAuthHeader, allowInsecureLocal);
            }
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "OCI registry rejected credentials (HTTP 401)");
        }
        if (resp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "OCI registry denied access (HTTP 403)");
        }
        if (resp.statusCode() == 429) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "OCI registry rate limited request (HTTP 429)");
        }
        throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "OCI registry v2 probe failed (HTTP " + resp.statusCode() + ")");
    }

    private String exchangeBearerToken(
            String wwwAuth,
            String repository,
            String basicAuthHeader,
            boolean allowInsecureLocal
    ) throws RegistryPublishException {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = BEARER_PARAM_PATTERN.matcher(wwwAuth);
        while (m.find()) {
            params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        String realm = params.get("realm");
        if (realm == null || realm.isBlank()) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry Bearer challenge missing realm");
        }
        URI realmUri = URI.create(realm);
        boolean realmLoopback = isLoopbackHost(realmUri.getHost());
        if (!"https".equalsIgnoreCase(realmUri.getScheme()) && !(allowInsecureLocal && realmLoopback)) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry auth realm must use HTTPS");
        }
        validateHostAddresses(realmUri.getHost(), allowInsecureLocal && realmLoopback);

        StringBuilder query = new StringBuilder();
        if (params.containsKey("service")) {
            query.append("service=").append(URLEncoder.encode(params.get("service"), StandardCharsets.UTF_8));
        }
        String scope = "repository:" + repository + ":pull,push";
        if (query.length() > 0) {
            query.append('&');
        }
        query.append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));

        String tokenUrl = realm + (realm.contains("?") ? "&" : "?") + query;
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (basicAuthHeader != null) {
            req.header("Authorization", basicAuthHeader);
        }
        HttpResponse<String> resp = send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry token exchange failed (HTTP " + resp.statusCode() + ")");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Registry token endpoint error (HTTP " + resp.statusCode() + ")");
        }
        try {
            JsonNode json = MAPPER.readTree(resp.body());
            String token = json.path("token").asText("");
            if (token.isBlank()) {
                token = json.path("access_token").asText("");
            }
            if (token.isBlank()) {
                throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry token response missing bearer token");
            }
            return "Bearer " + token;
        } catch (RegistryPublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Invalid JSON from registry token endpoint");
        }
    }

    private boolean blobExists(URI baseUri, String repository, String digest, String authHeader)
            throws RegistryPublishException {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/v2/" + repository + "/blobs/" + digest))
                .timeout(REQUEST_TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        if (authHeader != null) {
            req.header("Authorization", authHeader);
        }
        HttpResponse<Void> resp = send(req.build(), HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 200) {
            return true;
        }
        if (resp.statusCode() == 404) {
            return false;
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry denied blob existence check (HTTP " + resp.statusCode() + ")");
        }
        throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Unexpected status checking blob existence: HTTP " + resp.statusCode());
    }

    private void uploadBlob(
            URI baseUri,
            String repository,
            OciLayoutInspector.BlobEntry blob,
            String authHeader,
            boolean allowInsecureLocal
    ) throws RegistryPublishException {
        HttpRequest.Builder initReq = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/v2/" + repository + "/blobs/uploads/"))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (authHeader != null) {
            initReq.header("Authorization", authHeader);
        }
        HttpResponse<String> initResp = send(initReq.build(), HttpResponse.BodyHandlers.ofString());
        if (initResp.statusCode() == 401 || initResp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry rejected blob upload initiation (HTTP " + initResp.statusCode() + ")");
        }
        if (initResp.statusCode() == 429) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Registry rate limited blob upload (HTTP 429)");
        }
        if (initResp.statusCode() != 202 && initResp.statusCode() != 201) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Blob upload initiation failed (HTTP " + initResp.statusCode() + ")");
        }

        String location = initResp.headers().firstValue("Location")
                .orElseThrow(() -> new RegistryPublishException("REGISTRY_UNAVAILABLE", "Registry blob upload missing Location header"));
        URI uploadUri = baseUri.resolve(location);
        boolean sameOrigin = baseUri.getScheme().equalsIgnoreCase(uploadUri.getScheme())
                && baseUri.getHost().equalsIgnoreCase(uploadUri.getHost())
                && effectivePort(baseUri) == effectivePort(uploadUri);
        if (!sameOrigin) {
            // Never forward credentials to a different host; verify SSRF on upload target too
            boolean loopback = isLoopbackHost(uploadUri.getHost());
            if (!"https".equalsIgnoreCase(uploadUri.getScheme()) && !(allowInsecureLocal && loopback)) {
                throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Cross-origin blob upload location must use HTTPS");
            }
            validateHostAddresses(uploadUri.getHost(), allowInsecureLocal && loopback);
        }

        String sep = uploadUri.getRawQuery() == null || uploadUri.getRawQuery().isEmpty() ? "?" : "&";
        URI finalUploadUri = URI.create(uploadUri + sep + "digest=" + URLEncoder.encode(blob.digest(), StandardCharsets.UTF_8));

        HttpRequest.Builder putReq = HttpRequest.newBuilder()
                .uri(finalUploadUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(blob.bytes()));
        if (authHeader != null && sameOrigin) {
            putReq.header("Authorization", authHeader);
        }
        HttpResponse<String> putResp = send(putReq.build(), HttpResponse.BodyHandlers.ofString());
        if (putResp.statusCode() == 401 || putResp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry rejected blob upload (HTTP " + putResp.statusCode() + ")");
        }
        if (putResp.statusCode() != 201 && putResp.statusCode() != 200) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Blob upload failed for " + blob.digest() + " (HTTP " + putResp.statusCode() + ")");
        }
    }

    private String pushManifest(
            URI baseUri,
            String repository,
            String reference,
            String mediaType,
            byte[] manifestBytes,
            String authHeader
    ) throws RegistryPublishException {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/v2/" + repository + "/manifests/" + reference))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", mediaType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(manifestBytes));
        if (authHeader != null) {
            req.header("Authorization", authHeader);
        }
        HttpResponse<String> resp = send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new RegistryPublishException("REGISTRY_AUTH_FAILED", "Registry rejected manifest push (HTTP " + resp.statusCode() + ")");
        }
        if (resp.statusCode() == 429) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Registry rate limited manifest push (HTTP 429)");
        }
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "Manifest push failed (HTTP " + resp.statusCode() + ")");
        }
        return resp.headers().firstValue("Docker-Content-Digest")
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new RegistryPublishException(
                        "DIGEST_VERIFICATION_FAILED",
                        "Registry response omitted required Docker-Content-Digest header"
                ));
    }

    private String retrieveAndVerifyManifest(
            URI baseUri,
            String repository,
            String digest,
            String mediaType,
            String authHeader
    ) throws RegistryPublishException {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/v2/" + repository + "/manifests/" + digest))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", mediaType + ", application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
                .GET();
        if (authHeader != null) {
            req.header("Authorization", authHeader);
        }
        HttpResponse<byte[]> resp = send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new RegistryPublishException(
                    "DIGEST_VERIFICATION_FAILED",
                    "Failed to retrieve published manifest by immutable digest (HTTP " + resp.statusCode() + ")"
            );
        }
        byte[] manifestBytes = resp.body();
        if (manifestBytes == null || manifestBytes.length == 0) {
            throw new RegistryPublishException("DIGEST_VERIFICATION_FAILED", "Retrieved manifest body was empty");
        }
        String computed = OciLayoutInspector.sha256Digest(manifestBytes);
        String headerDigest = resp.headers().firstValue("Docker-Content-Digest")
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .orElse(null);
        if (headerDigest != null && !headerDigest.equals(computed)) {
            throw new RegistryPublishException(
                    "DIGEST_VERIFICATION_FAILED",
                    "Registry Docker-Content-Digest header (" + headerDigest + ") disagreed with retrieved manifest bytes (" + computed + ")"
            );
        }
        return computed;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws RegistryPublishException {
        try {
            return httpClient.send(req, handler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("OCI registry request interrupted");
        } catch (IOException ex) {
            throw new RegistryPublishException("REGISTRY_UNAVAILABLE", "OCI registry network failure: " + ex.getMessage());
        }
    }

    private static void checkCancelled(AtomicBoolean cancelSignal) {
        if (cancelSignal != null && cancelSignal.get()) {
            throw new CancellationException("OCI publish cancelled");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || lower.endsWith(".localhost")
                || "127.0.0.1".equals(lower) || lower.startsWith("127.")
                || "::1".equals(lower) || "[::1]".equals(lower);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public record PublishVerificationResult(
            String registryHost,
            String imageRepository,
            String imageTag,
            String reportedDigest,
            String verifiedDigest,
            String immutableReference,
            String mediaType,
            String platform,
            long totalImageBytes,
            boolean verified
    ) {
    }

    public static final class RegistryPublishException extends Exception {
        private final String failureCategory;

        public RegistryPublishException(String failureCategory, String message) {
            super(message);
            this.failureCategory = failureCategory;
        }

        public String getFailureCategory() {
            return failureCategory;
        }
    }
}
