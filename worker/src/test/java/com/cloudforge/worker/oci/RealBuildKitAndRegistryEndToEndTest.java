package com.cloudforge.worker.oci;

import com.cloudforge.worker.config.WorkerAgentConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real BuildKit + disposable local OCI {@code registry:2} end-to-end verification test.
 *
 * <p>Executes whenever a local Docker BuildKit daemon is reachable (including GitHub Actions
 * {@code ubuntu-latest} CI and local Docker Desktop). Skips explicitly via JUnit assumption
 * if Docker/BuildKit is unavailable in the host environment — never fabricates results.</p>
 */
class RealBuildKitAndRegistryEndToEndTest {

    @TempDir
    Path tempDir;

    private BuildKitExecutionAdapter buildKitAdapter;

    @BeforeEach
    void setUp() {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "oci-e2e-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "oci-e2e-ephemeral-token-12345678",
                "CLOUDFORGE_WORKER_OCI_BUILDKIT_ENABLED", "true",
                "CLOUDFORGE_OCI_BUILDKIT_DRIVER", "buildx",
                "CLOUDFORGE_OCI_ALLOW_BUILD_NETWORK", "false"
        ));
        buildKitAdapter = new BuildKitExecutionAdapter(config);
        Assumptions.assumeTrue(
                buildKitAdapter.isAvailable(),
                "Skipping live BuildKit + disposable registry:2 E2E test because Docker BuildKit daemon is not running"
        );
    }

    @Test
    void buildsRealMinimalOciImagePublishesToDisposableLocalRegistryAndVerifiesImmutableDigest() throws Exception {
        int freePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            freePort = ss.getLocalPort();
        }
        String registryContainerName = "cf-test-oci-reg-" + UUID.randomUUID().toString().substring(0, 8);
        String registryHost = "127.0.0.1:" + freePort;

        Process startReg = new ProcessBuilder(
                "docker", "run", "-d", "--rm",
                "--name", registryContainerName,
                "-p", "127.0.0.1:" + freePort + ":5000",
                "registry:2"
        ).redirectErrorStream(true).start();
        String startOut = new String(startReg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(
                startReg.waitFor(60, TimeUnit.SECONDS) && startReg.exitValue() == 0,
                "Skipping live registry E2E because disposable registry:2 container could not start: " + startOut.trim()
        );

        try {
            waitForRegistryReady("http://" + registryHost + "/v2/");

            // 1. Prepare minimal deterministic build context (FROM scratch — zero external network pull needed)
            Path contextDir = Files.createDirectories(tempDir.resolve("verified-src"));
            Files.writeString(contextDir.resolve("hello.txt"), "CloudForge Phase 3.5B verified artifact\n", StandardCharsets.UTF_8);
            Files.writeString(contextDir.resolve("Dockerfile"), """
                    FROM scratch
                    COPY hello.txt /hello.txt
                    LABEL org.cloudforge.verified="true"
                    """, StandardCharsets.UTF_8);

            // 2. Run Dockerfile policy validator
            DockerfilePolicyValidator.ValidatedContext validated = DockerfilePolicyValidator.validate(
                    contextDir, ".", "Dockerfile", Set.of()
            );
            assertThat(validated.baseImages()).containsExactly("scratch");

            // 3. Execute real BuildKit build producing local OCI Image Layout TAR
            Path ociTar = tempDir.resolve("built-image.oci.tar");
            List<String> buildLogs = Collections.synchronizedList(new ArrayList<>());
            BuildKitExecutionAdapter.BuildOutcome outcome = buildKitAdapter.executeBuild(
                    validated.contextDirectory(),
                    validated.dockerfile(),
                    ociTar,
                    "linux/amd64",
                    Map.of("BUILD_PROFILE", "e2e-test"),
                    120,
                    200,
                    65536,
                    new AtomicBoolean(false),
                    (stream, msg, ts) -> buildLogs.add(stream + ":" + msg)
            );

            assertThat(outcome.status())
                    .withFailMessage("Real BuildKit build failed (%s): %s | logs=%s",
                            outcome.failureCategory(), outcome.summary(), buildLogs)
                    .isEqualTo("SUCCEEDED");
            assertThat(outcome.exitCode()).isEqualTo(0);
            assertThat(outcome.terminationConfirmed()).isTrue();
            assertThat(outcome.layout()).isNotNull();
            assertThat(outcome.layout().manifestDigest()).matches("^sha256:[0-9a-f]{64}$");
            assertThat(Files.exists(ociTar)).isTrue();

            // 4. Publish built OCI layout to real disposable local registry:2 and verify immutable digest
            OciRegistryClient registryClient = new OciRegistryClient();
            List<String> publishLogs = Collections.synchronizedList(new ArrayList<>());
            OciRegistryClient.PublishVerificationResult published = registryClient.publishAndVerify(
                    "http",
                    registryHost,
                    "cloudforge/e2e-hello",
                    "phase35b-test",
                    true,
                    null,
                    null,
                    outcome.layout(),
                    new AtomicBoolean(false),
                    (stream, msg, ts) -> publishLogs.add(stream + ":" + msg)
            );

            assertThat(published.verified()).isTrue();
            assertThat(published.reportedDigest()).isEqualTo(outcome.layout().manifestDigest());
            assertThat(published.verifiedDigest()).isEqualTo(outcome.layout().manifestDigest());
            assertThat(published.immutableReference())
                    .isEqualTo(registryHost + "/cloudforge/e2e-hello@" + outcome.layout().manifestDigest());

            // 5. Independent raw HTTP GET check directly against registry:2 by immutable digest
            HttpClient rawClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<byte[]> rawManifestResp = rawClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://" + registryHost + "/v2/cloudforge/e2e-hello/manifests/" + published.verifiedDigest()))
                            .header("Accept", outcome.layout().mediaType())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(rawManifestResp.statusCode()).isEqualTo(200);
            String recomputed = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawManifestResp.body())
            );
            assertThat(recomputed).isEqualTo(published.verifiedDigest());

            System.out.println("[CLOUDFORGE-E2E-PROOF] Real BuildKit + registry:2 verified immutable reference: "
                    + published.immutableReference() + " (" + published.totalImageBytes() + " bytes)");
        } finally {
            try {
                new ProcessBuilder("docker", "rm", "-f", registryContainerName)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private static void waitForRegistryReady(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> resp = client.send(
                        HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()
                );
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("Disposable local OCI registry:2 did not become ready within 20s at " + url);
    }
}
