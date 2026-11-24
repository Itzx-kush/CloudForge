package com.cloudforge.worker.oci;

import com.cloudforge.worker.agent.WorkerAgentLoop;
import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.cloudforge.worker.source.GitHubSourceArchive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildKitAdapterAndRegistryClientTest {

    @TempDir
    Path tempDir;

    @Test
    void enforcesHardenedBuildKitCommandLineForBothBuildxAndBuildctlDrivers() {
        Path contextDir = tempDir.resolve("ctx");
        Path dockerfile = contextDir.resolve("Dockerfile");
        Path outTar = tempDir.resolve("image.oci.tar");

        WorkerAgentConfig buildxCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "oci-bk-1",
                "CLOUDFORGE_WORKER_TOKEN", "oci-worker-secret-token-12345678",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true",
                "CLOUDFORGE_OCI_BUILDKIT_DRIVER", "buildx",
                "CLOUDFORGE_OCI_BUILDKIT_BUILDER", "cf-rootless"
        ));
        assertThat(buildxCfg.ociBuildKitEnabled()).isTrue();
        assertThat(buildxCfg.supportedOperations()).containsExactly("OCI_IMAGE_BUILD", "OCI_IMAGE_PUBLISH");

        BuildKitExecutionAdapter buildxAdapter = new BuildKitExecutionAdapter(buildxCfg);
        List<String> buildxCmd = buildxAdapter.buildCommand(
                contextDir, dockerfile, outTar, "linux/amd64", Map.of("APP_ENV", "staging")
        );
        assertThat(buildxCmd).contains("docker", "buildx", "build", "--builder", "cf-rootless",
                "--platform", "linux/amd64", "--network", "none", "--no-cache", "--provenance=false", "--sbom=false");
        assertThat(buildxCmd).anyMatch(arg -> arg.startsWith("type=oci,dest="));
        assertThat(buildxCmd).noneMatch(arg -> arg.contains("security.insecure")
                || arg.contains("network.host")
                || arg.contains("--privileged")
                || arg.contains("--push")
                || arg.contains("docker.sock"));

        WorkerAgentConfig buildctlCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "oci-bk-2",
                "CLOUDFORGE_WORKER_TOKEN", "oci-worker-secret-token-12345678",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true",
                "CLOUDFORGE_OCI_BUILDKIT_DRIVER", "buildctl",
                "CLOUDFORGE_OCI_BUILDKIT_ADDR", "unix:///run/user/1000/buildkit/buildkitd.sock"
        ));
        BuildKitExecutionAdapter buildctlAdapter = new BuildKitExecutionAdapter(buildctlCfg);
        List<String> buildctlCmd = buildctlAdapter.buildCommand(
                contextDir, dockerfile, outTar, "linux/arm64", Map.of("VERSION", "1.2.3")
        );
        assertThat(buildctlCmd).contains("buildctl", "--addr", "unix:///run/user/1000/buildkit/buildkitd.sock",
                "build", "--frontend", "dockerfile.v0", "force-network-mode=none", "platform=linux/arm64");
        assertThat(buildctlCmd).anyMatch(arg -> arg.startsWith("type=oci,dest="));
        assertThat(buildctlCmd).noneMatch(arg -> arg.contains("security.insecure") || arg.contains("network.host"));

        // Source-only and OCI BuildKit cannot be enabled simultaneously
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "bad-mixed-worker",
                "CLOUDFORGE_WORKER_TOKEN", "oci-worker-secret-token-12345678",
                "CLOUDFORGE_WORKER_SOURCE_ONLY", "true",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true"
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inspectsValidOciLayoutAndRejectsTamperedBlobDigests() throws Exception {
        Path validTar = createSyntheticOciLayoutTar(tempDir.resolve("valid.oci.tar"), false);
        OciLayoutInspector.VerifiedOciLayout layout = OciLayoutInspector.inspectAndVerify(validTar);
        assertThat(layout.manifestDigest()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(layout.platform()).isEqualTo("linux/amd64");
        assertThat(layout.blobs()).hasSize(2); // config + 1 layer

        Path tamperedTar = createSyntheticOciLayoutTar(tempDir.resolve("tampered.oci.tar"), true);
        assertThatThrownBy(() -> OciLayoutInspector.inspectAndVerify(tamperedTar))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SHA-256 verification failed");
    }

    @Test
    void publishesToMockOciRegistryWithBearerChallengeVerifiesDigestAndRejectsMismatchSsrfAndErrors() throws Exception {
        Path validTar = createSyntheticOciLayoutTar(tempDir.resolve("push.oci.tar"), false);
        OciLayoutInspector.VerifiedOciLayout layout = OciLayoutInspector.inspectAndVerify(validTar);

        Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        Map<String, byte[]> manifests = new ConcurrentHashMap<>();
        Map<String, String> manifestTypes = new ConcurrentHashMap<>();
        AtomicBoolean tamperManifestOnGet = new AtomicBoolean(false);
        AtomicBoolean forceAuthFailure = new AtomicBoolean(false);
        AtomicBoolean forceRateLimit = new AtomicBoolean(false);
        AtomicReference<String> receivedAuthForToken = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String hostPort = "127.0.0.1:" + port;

        server.createContext("/token", exchange -> {
            receivedAuthForToken.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (forceAuthFailure.get()) {
                send(exchange, 401, "application/json", "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = "{\"token\":\"short-lived-bearer-token\"}".getBytes(StandardCharsets.UTF_8);
            send(exchange, 200, "application/json", body);
        });

        server.createContext("/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");

            if (forceRateLimit.get()) {
                send(exchange, 429, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!"Bearer short-lived-bearer-token".equals(auth)) {
                exchange.getResponseHeaders().set(
                        "WWW-Authenticate",
                        "Bearer realm=\"http://" + hostPort + "/token\",service=\"local-oci\",scope=\"repository:cloudforge/app:pull,push\""
                );
                send(exchange, 401, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/v2/".equals(path)) {
                send(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.contains("/blobs/uploads/")) {
                if ("POST".equals(method)) {
                    String uploadId = UUID.randomUUID().toString();
                    exchange.getResponseHeaders().set("Location", "/v2/cloudforge/app/blobs/uploads/" + uploadId);
                    send(exchange, 202, "application/octet-stream", new byte[0]);
                    return;
                }
                if ("PUT".equals(method)) {
                    String query = exchange.getRequestURI().getQuery();
                    String digest = query.substring(query.indexOf("digest=") + "digest=".length());
                    byte[] data = exchange.getRequestBody().readAllBytes();
                    blobs.put(digest, data);
                    exchange.getResponseHeaders().set("Docker-Content-Digest", digest);
                    send(exchange, 201, "application/octet-stream", new byte[0]);
                    return;
                }
            }
            if (path.contains("/blobs/")) {
                String digest = path.substring(path.lastIndexOf('/') + 1);
                if ("HEAD".equals(method)) {
                    if (blobs.containsKey(digest)) {
                        exchange.getResponseHeaders().set("Content-Length", String.valueOf(blobs.get(digest).length));
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                    }
                    return;
                }
            }
            if (path.contains("/manifests/")) {
                String ref = path.substring(path.lastIndexOf('/') + 1);
                if ("PUT".equals(method)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String digest = sha256Digest(body);
                    String ctype = exchange.getRequestHeaders().getFirst("Content-Type");
                    manifests.put(ref, body);
                    manifests.put(digest, body);
                    manifestTypes.put(digest, ctype);
                    exchange.getResponseHeaders().set("Docker-Content-Digest", digest);
                    send(exchange, 201, ctype, new byte[0]);
                    return;
                }
                if ("GET".equals(method)) {
                    byte[] body = manifests.get(ref);
                    if (body == null) {
                        send(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    byte[] out = tamperManifestOnGet.get()
                            ? "{\"schemaVersion\":2,\"tampered\":true}".getBytes(StandardCharsets.UTF_8)
                            : body;
                    exchange.getResponseHeaders().set("Docker-Content-Digest", ref);
                    send(exchange, 200, manifestTypes.getOrDefault(ref, "application/vnd.oci.image.manifest.v1+json"), out);
                    return;
                }
            }
            send(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });

        server.start();
        try {
            OciRegistryClient client = new OciRegistryClient();
            List<String> logs = new ArrayList<>();

            // 1. Happy path with Bearer token negotiation & independent SHA-256 verification
            char[] userChars = "bot-user".toCharArray();
            char[] passChars = "bot-secret-pass".toCharArray();
            OciRegistryClient.PublishVerificationResult result = client.publishAndVerify(
                    "http", hostPort, "cloudforge/app", "v1.0.0", true,
                    userChars, passChars, layout, new AtomicBoolean(false),
                    (s, m, t) -> logs.add(m)
            );
            assertThat(result.verified()).isTrue();
            assertThat(result.verifiedDigest()).isEqualTo(layout.manifestDigest());
            assertThat(result.immutableReference()).isEqualTo(hostPort + "/cloudforge/app@" + layout.manifestDigest());
            assertThat(receivedAuthForToken.get()).startsWith("Basic ");
            // Credential arrays must be zeroed in finally!
            assertThat(new String(passChars).replace("\0", "")).isEmpty();
            assertThat(new String(userChars).replace("\0", "")).isEmpty();

            // 2. Digest mismatch on independent GET retrieval MUST fail with DIGEST_VERIFICATION_FAILED
            tamperManifestOnGet.set(true);
            assertThatThrownBy(() -> client.publishAndVerify(
                    "http", hostPort, "cloudforge/app", "v1.0.1", true,
                    "u".toCharArray(), "p".toCharArray(), layout, new AtomicBoolean(false), (s, m, t) -> {}
            )).isInstanceOf(OciRegistryClient.RegistryPublishException.class)
                    .extracting("failureCategory")
                    .isEqualTo("DIGEST_VERIFICATION_FAILED");
            tamperManifestOnGet.set(false);

            // 3. Registry 401 failure -> REGISTRY_AUTH_FAILED
            forceAuthFailure.set(true);
            assertThatThrownBy(() -> client.publishAndVerify(
                    "http", hostPort, "cloudforge/app", "v1.0.2", true,
                    "u".toCharArray(), "bad".toCharArray(), layout, new AtomicBoolean(false), (s, m, t) -> {}
            )).isInstanceOf(OciRegistryClient.RegistryPublishException.class)
                    .extracting("failureCategory")
                    .isEqualTo("REGISTRY_AUTH_FAILED");
            forceAuthFailure.set(false);

            // 4. Registry 429 rate-limit -> REGISTRY_UNAVAILABLE
            forceRateLimit.set(true);
            assertThatThrownBy(() -> client.publishAndVerify(
                    "http", hostPort, "cloudforge/app", "v1.0.3", true,
                    "u".toCharArray(), "p".toCharArray(), layout, new AtomicBoolean(false), (s, m, t) -> {}
            )).isInstanceOf(OciRegistryClient.RegistryPublishException.class)
                    .extracting("failureCategory")
                    .isEqualTo("REGISTRY_UNAVAILABLE");
            forceRateLimit.set(false);

            // 5. SSRF & plaintext HTTP remote rejections
            assertThatThrownBy(() -> OciRegistryClient.validateAndBuildBaseUri("https", "169.254.169.254", false))
                    .isInstanceOf(OciRegistryClient.RegistryPublishException.class);
            assertThatThrownBy(() -> OciRegistryClient.validateAndBuildBaseUri("https", "10.0.0.1:5000", false))
                    .isInstanceOf(OciRegistryClient.RegistryPublishException.class);
            assertThatThrownBy(() -> OciRegistryClient.validateAndBuildBaseUri("http", "ghcr.io", true))
                    .isInstanceOf(OciRegistryClient.RegistryPublishException.class);
            assertThatThrownBy(() -> OciRegistryClient.validateAndBuildBaseUri("http", "127.0.0.1:5000", false))
                    .isInstanceOf(OciRegistryClient.RegistryPublishException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void workerAgentLoopRejectsInvalidDockerfileBeforeInvokingBuildKitAndReportsPolicyViolation() throws Exception {
        WorkerAgentConfig cfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "oci-loop-1",
                "CLOUDFORGE_WORKER_TOKEN", "oci-worker-secret-token-12345678",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true"
        ));

        Path badSrcDir = Files.createDirectories(tempDir.resolve("bad-src"));
        Files.writeString(
                badSrcDir.resolve("Dockerfile"),
                "FROM evil.example.com/untrusted:latest\nRUN --security=insecure id\n",
                StandardCharsets.UTF_8
        );
        Path badZip = tempDir.resolve("bad-source.zip");
        String badZipSha = GitHubSourceArchive.packageCanonicalZip(
                badSrcDir, badZip, Instant.now().plusSeconds(60), new AtomicBoolean(false)
        );

        UUID runId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AtomicBoolean claimed = new AtomicBoolean(false);
        List<ControlPlaneClient.OciBuildResultPayload> buildReports = new ArrayList<>();
        List<ControlPlaneClient.CompletionPayload> completions = new ArrayList<>();

        ControlPlaneClient stubClient = new ControlPlaneClient() {
            @Override public void register(RegistrationPayload payload) {}
            @Override public HeartbeatResult heartbeat(String workerId, List<ActiveLease> activeLeases) {
                return new HeartbeatResult(workerId, "AVAILABLE", Instant.now(), List.of());
            }
            @Override public Optional<ClaimedStageLease> claimNextStage(String workerId) {
                if (!claimed.compareAndSet(false, true)) return Optional.empty();
                return Optional.of(new ClaimedStageLease(
                        new StageInfo(UUID.randomUUID(), UUID.randomUUID(), runId, stageId,
                                "build", "OCI Build", "BUILD", 1, 1,
                                "org/repo", "main", "a".repeat(40), 120, 0, 1),
                        attemptId, workerId, "lease-oci-1", 1L, "OCI_IMAGE_BUILD", true,
                        Instant.now(), Instant.now().plusSeconds(120), 10, 100, 32768
                ));
            }
            @Override public void acknowledgeRunning(String w, UUID a, String lt, long f, Instant s) {}
            @Override public void appendLogs(String w, UUID a, String lt, long f, List<LogEntry> e) {}
            @Override public void completeAttempt(String w, UUID a, CompletionPayload p) { completions.add(p); }
            @Override public void updateStatus(String w, String s) {}
            @Override public OciExecutionDescriptor fetchOciDescriptor(String w, UUID a, String lt, long f) {
                return new OciExecutionDescriptor(
                        UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), runId, stageId, attemptId,
                        "OCI_IMAGE_BUILD", 42L, "org/repo", "a".repeat(40),
                        "Dockerfile", ".", "org/app", "v1", "linux/amd64", "LOCAL_DEV_BUILDKIT",
                        Map.of(), "1.0", "512m", 120, "b".repeat(64),
                        UUID.randomUUID(), badZipSha, null, null, null,
                        UUID.randomUUID(), "127.0.0.1:5000", "http", "org", true, false
                );
            }
            @Override public DownloadedHandoff downloadBuildContext(String w, UUID a, String lt, long f, Path dest) {
                try {
                    Files.copy(badZip, dest);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return new DownloadedHandoff(UUID.randomUUID(), badZipSha, "a".repeat(40), dest);
            }
            @Override public void reportOciBuildResult(String w, UUID a, OciBuildResultPayload payload) {
                buildReports.add(payload);
            }
        };

        BuildKitExecutionAdapter failIfCalledAdapter = new BuildKitExecutionAdapter(cfg) {
            @Override
            public BuildOutcome executeBuild(
                    Path c, Path d, Path o, String p, Map<String, String> args, int t, int ml, int mb,
                    AtomicBoolean cancel, IsolatedExecutionAdapter.LogLineConsumer logs
            ) {
                throw new AssertionError("BuildKit must NOT execute when Dockerfile policy validation fails");
            }
        };

        try (WorkerAgentLoop loop = new WorkerAgentLoop(cfg, stubClient, null, failIfCalledAdapter, new OciRegistryClient())) {
            assertThat(loop.runSingleClaimCycle()).isTrue();
        }

        assertThat(buildReports).hasSize(1);
        assertThat(buildReports.get(0).status()).isEqualTo("FAILED");
        assertThat(buildReports.get(0).failureCategory()).isEqualTo("DOCKERFILE_POLICY_VIOLATION");
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).resultStatus()).isEqualTo("FAILED");
        assertThat(completions.get(0).failureCategory()).isEqualTo("DOCKERFILE_POLICY_VIOLATION");
    }

    static Path createSyntheticOciLayoutTar(Path target, boolean tamperLayerBlob) throws Exception {
        byte[] configBytes = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}"
                .getBytes(StandardCharsets.UTF_8);
        String configDigest = sha256Digest(configBytes);

        byte[] layerBytes = "synthetic-oci-layer-payload-cloudforge".getBytes(StandardCharsets.UTF_8);
        String layerDigest = sha256Digest(layerBytes);

        String manifestJson = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.oci.image.manifest.v1+json",
                  "config": {
                    "mediaType": "application/vnd.oci.image.config.v1+json",
                    "digest": "%s",
                    "size": %d
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
                      "digest": "%s",
                      "size": %d
                    }
                  ]
                }
                """.formatted(configDigest, configBytes.length, layerDigest, layerBytes.length);
        byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        String manifestDigest = sha256Digest(manifestBytes);

        String indexJson = """
                {
                  "schemaVersion": 2,
                  "manifests": [
                    {
                      "mediaType": "application/vnd.oci.image.manifest.v1+json",
                      "digest": "%s",
                      "size": %d,
                      "platform": {"os": "linux", "architecture": "amd64"}
                    }
                  ]
                }
                """.formatted(manifestDigest, manifestBytes.length);

        byte[] writtenLayerBytes = tamperLayerBlob
                ? "TAMPERED-oci-layer-payload-cloudforge!".getBytes(StandardCharsets.UTF_8)
                : layerBytes;

        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(Files.newOutputStream(target))) {
            addTarEntry(tos, "oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
            addTarEntry(tos, "index.json", indexJson.getBytes(StandardCharsets.UTF_8));
            addTarEntry(tos, "blobs/sha256/" + manifestDigest.substring(7), manifestBytes);
            addTarEntry(tos, "blobs/sha256/" + configDigest.substring(7), configBytes);
            addTarEntry(tos, "blobs/sha256/" + layerDigest.substring(7), writtenLayerBytes);
        }
        return target;
    }

    private static void addTarEntry(TarArchiveOutputStream tos, String name, byte[] bytes) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tos.putArchiveEntry(entry);
        tos.write(bytes);
        tos.closeArchiveEntry();
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String sha256Digest(byte[] data) {
        return "sha256:" + sha256Hex(data);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
