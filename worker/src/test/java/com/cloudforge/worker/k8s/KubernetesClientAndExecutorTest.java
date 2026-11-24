package com.cloudforge.worker.k8s;

import com.cloudforge.worker.agent.WorkerAgentLoop;
import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive worker tests covering:
 * <ul>
 *   <li>Real TLS certificate verification &amp; wrong-CA rejection, HTTP 401/403/quota/redirect/SSRF handling in {@link OfficialKubernetesApiClient}</li>
 *   <li>{@link KubernetesClusterValidator} least-privilege &amp; cluster-admin rejection</li>
 *   <li>{@link KubernetesDeploymentExecutor} rolling updates, ImagePullBackOff, CrashLoopBackOff, Unschedulable, ProgressDeadlineExceeded, readiness timeout, foreign-owned Deployment refusal, and pre/post-apply cancellation</li>
 *   <li>{@link WorkerAgentLoop} K8s cluster validation &amp; deployment execution with bearer token redaction</li>
 * </ul>
 */
class KubernetesClientAndExecutorTest {

    private static final String VALID_DIGEST =
            "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void officialKubernetesClientEnforcesStrictTlsVerificationAuthErrorsRedirectsAndSsrf() throws Exception {
        CertBundle validCert = generateLoopbackCert("CN=CloudForge K8s Mock API");
        CertBundle wrongCaCert = generateLoopbackCert("CN=Wrong Unrelated CA");

        AtomicInteger statusToReturn = new AtomicInteger(200);
        AtomicReference<String> bodyToReturn = new AtomicReference<>(
                "{\"major\":\"1\",\"minor\":\"31\",\"gitVersion\":\"v1.31.2+k3s1\","
                        + "\"gitCommit\":\"abcdef123456\",\"gitTreeState\":\"clean\","
                        + "\"buildDate\":\"2026-11-24T00:00:00Z\",\"goVersion\":\"go1.22.5\","
                        + "\"compiler\":\"gc\",\"platform\":\"linux/amd64\"}"
        );
        AtomicReference<String> observedAuth = new AtomicReference<>();

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(validCert.sslContext()));
        server.createContext("/version", exchange -> {
            observedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int code = statusToReturn.get();
            if (code == 302) {
                exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] bytes = bodyToReturn.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            String url = "https://127.0.0.1:" + server.getAddress().getPort();
            OfficialKubernetesApiClient client = new OfficialKubernetesApiClient(true);

            // 1. Valid CA PEM succeeds and sends Bearer token
            var validCtx = new KubernetesApiClient.ClusterConnectionContext(
                    url, validCert.pem(), "sa-test-token-123".toCharArray(), true, 10
            );
            KubernetesApiClient.ServerVersionInfo ver = client.getServerVersion(validCtx);
            assertThat(ver.gitVersion()).isEqualTo("v1.31.2+k3s1");
            assertThat(observedAuth.get()).isEqualTo("Bearer sa-test-token-123");

            // 2. Wrong CA certificate strictly fails TLS verification (CLUSTER_TLS_ERROR)
            var wrongCaCtx = new KubernetesApiClient.ClusterConnectionContext(
                    url, wrongCaCert.pem(), "sa-test-token-123".toCharArray(), true, 10
            );
            assertThatThrownBy(() -> client.getServerVersion(wrongCaCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .extracting("failureCategory")
                    .isEqualTo("CLUSTER_TLS_ERROR");

            // 3. HTTP 401 -> CLUSTER_AUTH_FAILED
            statusToReturn.set(401);
            bodyToReturn.set("{\"kind\":\"Status\",\"status\":\"Failure\",\"message\":\"Unauthorized\",\"code\":401}");
            assertThatThrownBy(() -> client.getServerVersion(validCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .extracting("failureCategory")
                    .isEqualTo("CLUSTER_AUTH_FAILED");

            // 4. HTTP 403 RBAC -> K8S_RBAC_DENIED
            statusToReturn.set(403);
            bodyToReturn.set("{\"kind\":\"Status\",\"status\":\"Failure\",\"message\":\"forbidden\",\"code\":403}");
            assertThatThrownBy(() -> client.getServerVersion(validCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .extracting("failureCategory")
                    .isEqualTo("K8S_RBAC_DENIED");

            // 5. HTTP 403 ResourceQuota -> K8S_QUOTA_EXCEEDED
            statusToReturn.set(403);
            bodyToReturn.set("{\"kind\":\"Status\",\"status\":\"Failure\",\"message\":\"exceeded quota: pods\",\"code\":403}");
            assertThatThrownBy(() -> client.getServerVersion(validCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .extracting("failureCategory")
                    .isEqualTo("K8S_QUOTA_EXCEEDED");

            // 6. HTTP 302 redirect to metadata endpoint is NOT followed
            statusToReturn.set(302);
            assertThatThrownBy(() -> client.getServerVersion(validCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class);

            // 7. Plaintext HTTP rejected
            var httpCtx = new KubernetesApiClient.ClusterConnectionContext(
                    "http://127.0.0.1:8080", validCert.pem(), "tok".toCharArray(), true, 10
            );
            assertThatThrownBy(() -> client.getServerVersion(httpCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .extracting("failureCategory")
                    .isEqualTo("CLUSTER_TLS_ERROR");

            // 8. Loopback rejected when worker allowLocalDevWorker=false
            OfficialKubernetesApiClient prodClient = new OfficialKubernetesApiClient(false);
            assertThatThrownBy(() -> prodClient.getServerVersion(validCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class)
                    .hasMessageContaining("Loopback");

            // 9. Cloud metadata SSRF endpoint rejected
            var metadataCtx = new KubernetesApiClient.ClusterConnectionContext(
                    "https://169.254.169.254", validCert.pem(), "tok".toCharArray(), false, 5
            );
            assertThatThrownBy(() -> client.getServerVersion(metadataCtx))
                    .isInstanceOf(KubernetesApiClient.KubernetesClientException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clusterValidatorAcceptsLeastPrivilegeClusterAndRejectsClusterAdminOrMissingRbac() {
        KubernetesApiClient.ClusterConnectionContext dummyCtx =
                new KubernetesApiClient.ClusterConnectionContext("https://127.0.0.1:16443", "PEM", "tok".toCharArray(), true, 10);
        ControlPlaneClient.ClusterValidationTask task = new ControlPlaneClient.ClusterValidationTask(
                UUID.randomUUID(), UUID.randomUUID(), "kind-dev", "Kind Dev", "KIND_LOCAL",
                "https://127.0.0.1:16443", "a".repeat(64), "cloudforge-deployer", true, 1L,
                List.of("cf-ws01-payments-dev")
        );

        // 1. Valid least-privilege cluster passes
        StubKubernetesApiClient validStub = new StubKubernetesApiClient();
        KubernetesClusterValidator.ValidationOutcome ok = new KubernetesClusterValidator(validStub).validate(task, dummyCtx);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.kubernetesVersion()).isEqualTo("v1.31.2+k3s1");
        assertThat(ok.verifiedNamespaces()).containsExactly("cf-ws01-payments-dev");

        // 2. Over-privileged cluster-admin credential is rejected!
        StubKubernetesApiClient adminStub = new StubKubernetesApiClient();
        adminStub.allowClusterAdmin = true;
        KubernetesClusterValidator.ValidationOutcome adminRes = new KubernetesClusterValidator(adminStub).validate(task, dummyCtx);
        assertThat(adminRes.valid()).isFalse();
        assertThat(adminRes.errorSummary()).contains("over-privileged");

        // 3. Missing Deployment RBAC is rejected
        StubKubernetesApiClient noRbacStub = new StubKubernetesApiClient();
        noRbacStub.allowDeployments = false;
        KubernetesClusterValidator.ValidationOutcome noRbacRes = new KubernetesClusterValidator(noRbacStub).validate(task, dummyCtx);
        assertThat(noRbacRes.valid()).isFalse();
        assertThat(noRbacRes.errorSummary()).contains("Missing Deployment RBAC");

        // 4. Missing namespace is rejected
        StubKubernetesApiClient noNsStub = new StubKubernetesApiClient();
        noNsStub.namespaceExists = false;
        KubernetesClusterValidator.ValidationOutcome noNsRes = new KubernetesClusterValidator(noNsStub).validate(task, dummyCtx);
        assertThat(noNsRes.valid()).isFalse();
        assertThat(noNsRes.errorSummary()).contains("does not exist");
    }

    @Test
    void executorHandlesHealthyRolloutFailuresOwnershipGuardsAndPrePostApplyCancellation() {
        KubernetesApiClient.ClusterConnectionContext ctx =
                new KubernetesApiClient.ClusterConnectionContext("https://127.0.0.1:16443", "PEM", "tok".toCharArray(), true, 10);
        ControlPlaneClient.KubernetesExecutionDescriptor desc = KubernetesManifestAndSecurityTest.validDescriptor(null);
        List<String> reportedStatuses = new ArrayList<>();
        KubernetesDeploymentExecutor.StatusReporter reporter = (st, app, obs, upd, rdy, avail, gen, duid, suid, cond, pods, msg) ->
                reportedStatuses.add(st);
        IsolatedExecutionAdapter.LogLineConsumer logSink = (stream, line, ts) -> {};

        // 1. Healthy rolling update progression -> VERIFYING -> SUCCEEDED with verified digest
        StubKubernetesApiClient healthy = new StubKubernetesApiClient();
        healthy.rolloutPollsUntilReady = 2;
        healthy.expectedDigest = VALID_DIGEST;
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome okOut =
                new KubernetesDeploymentExecutor(healthy, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(okOut.deploymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(okOut.verifiedImageDigest()).isEqualTo(VALID_DIGEST);
        assertThat(okOut.readyReplicas()).isEqualTo(2);
        assertThat(reportedStatuses).contains("VALIDATING", "APPLYING", "ROLLING_OUT", "VERIFYING");

        // 2. Foreign-owned Deployment (missing CloudForge labels) is refused BEFORE apply
        StubKubernetesApiClient foreign = new StubKubernetesApiClient();
        foreign.existingDeploymentLabels = Map.of("app.kubernetes.io/managed-by", "helm");
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome foreignOut =
                new KubernetesDeploymentExecutor(foreign, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(foreignOut.deploymentStatus()).isEqualTo("FAILED");
        assertThat(foreignOut.failureCategory()).isEqualTo("K8S_MANIFEST_REJECTED");
        assertThat(foreignOut.applyInitiated()).isFalse();

        // 3. ImagePullBackOff -> K8S_IMAGE_PULL_FAILED
        StubKubernetesApiClient imgPull = new StubKubernetesApiClient();
        imgPull.podWaitingReason = "ImagePullBackOff";
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome pullOut =
                new KubernetesDeploymentExecutor(imgPull, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(pullOut.deploymentStatus()).isEqualTo("FAILED");
        assertThat(pullOut.failureCategory()).isEqualTo("K8S_IMAGE_PULL_FAILED");

        // 4. CrashLoopBackOff -> K8S_CRASH_LOOP
        StubKubernetesApiClient crash = new StubKubernetesApiClient();
        crash.podWaitingReason = "CrashLoopBackOff";
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome crashOut =
                new KubernetesDeploymentExecutor(crash, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(crashOut.deploymentStatus()).isEqualTo("FAILED");
        assertThat(crashOut.failureCategory()).isEqualTo("K8S_CRASH_LOOP");

        // 5. Unschedulable pod -> K8S_UNSCHEDULABLE
        StubKubernetesApiClient unsched = new StubKubernetesApiClient();
        unsched.podUnschedulable = true;
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome unschedOut =
                new KubernetesDeploymentExecutor(unsched, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(unschedOut.deploymentStatus()).isEqualTo("FAILED");
        assertThat(unschedOut.failureCategory()).isEqualTo("K8S_UNSCHEDULABLE");

        // 6. ProgressDeadlineExceeded -> K8S_ROLLOUT_TIMEOUT
        StubKubernetesApiClient deadline = new StubKubernetesApiClient();
        deadline.progressDeadlineExceeded = true;
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome dlOut =
                new KubernetesDeploymentExecutor(deadline, 50).execute(desc, ctx, reporter, new AtomicBoolean(false), logSink);
        assertThat(dlOut.deploymentStatus()).isEqualTo("FAILED");
        assertThat(dlOut.failureCategory()).isEqualTo("K8S_ROLLOUT_TIMEOUT");

        // 7. Cancellation BEFORE apply -> CANCELLED, applyInitiated=false
        StubKubernetesApiClient preCancel = new StubKubernetesApiClient();
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome preCancelOut =
                new KubernetesDeploymentExecutor(preCancel, 50).execute(desc, ctx, reporter, new AtomicBoolean(true), logSink);
        assertThat(preCancelOut.deploymentStatus()).isEqualTo("CANCELLED");
        assertThat(preCancelOut.applyInitiated()).isFalse();

        // 8. Cancellation AFTER apply -> RECONCILIATION_REQUIRED, applyInitiated=true (no resource deletion)
        AtomicBoolean cancelSignal = new AtomicBoolean(false);
        StubKubernetesApiClient postCancel = new StubKubernetesApiClient();
        postCancel.rolloutPollsUntilReady = 99;
        postCancel.onApply = () -> cancelSignal.set(true);
        KubernetesDeploymentExecutor.DeploymentExecutionOutcome postCancelOut =
                new KubernetesDeploymentExecutor(postCancel, 50).execute(desc, ctx, reporter, cancelSignal, logSink);
        assertThat(postCancelOut.deploymentStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(postCancelOut.applyInitiated()).isTrue();
    }

    @Test
    void workerAgentLoopExecutesClusterValidationAndDeploymentWithBearerTokenRedaction() {
        String ephemeralSaToken = "k8s_sa_bearer_secret_xyz_9988776655";
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "k8s-worker-loop-1",
                "CLOUDFORGE_WORKER_TOKEN", "worker-auth-token-1234567890",
                "CLOUDFORGE_WORKER_KUBERNETES_DEPLOY_ENABLED", "true",
                "CLOUDFORGE_K8S_ALLOW_LOCAL_DEV_CLUSTER_WORKER", "true",
                "CLOUDFORGE_K8S_ROLLOUT_POLL_INTERVAL_MS", "50"
        ));

        ControlPlaneClient.KubernetesExecutionDescriptor desc = KubernetesManifestAndSecurityTest.validDescriptor(null);
        UUID clusterId = desc.clusterConnectionId();
        UUID attemptId = UUID.randomUUID();
        AtomicBoolean validationClaimed = new AtomicBoolean(false);
        AtomicBoolean stageClaimed = new AtomicBoolean(false);
        List<ControlPlaneClient.ClusterValidationResultPayload> valResults = new ArrayList<>();
        List<ControlPlaneClient.KubernetesResultPayload> depResults = new ArrayList<>();
        List<ControlPlaneClient.CompletionPayload> completions = new ArrayList<>();
        List<ControlPlaneClient.LogEntry> uploadedLogs = new ArrayList<>();

        ControlPlaneClient stubCp = new ControlPlaneClient() {
            @Override public void register(RegistrationPayload payload) {}
            @Override public HeartbeatResult heartbeat(String w, List<ActiveLease> l) {
                return new HeartbeatResult(w, "AVAILABLE", Instant.now(), List.of());
            }
            @Override public Optional<ClusterValidationTask> claimClusterValidation(String w) {
                if (!validationClaimed.compareAndSet(false, true)) return Optional.empty();
                return Optional.of(new ClusterValidationTask(
                        clusterId, desc.workspaceId(), "kind-dev", "Kind Dev", "KIND_LOCAL",
                        "https://127.0.0.1:16443", "a".repeat(64), "cloudforge-deployer", true, 1L,
                        List.of("cf-ws01-payments-dev")
                ));
            }
            @Override public ClusterCredentialsResult fetchClusterValidationCredentials(String w, UUID c, long nonce) {
                return new ClusterCredentialsResult(c, "https://127.0.0.1:16443", "PEM", ephemeralSaToken, "cloudforge-deployer", true, Instant.now());
            }
            @Override public void completeClusterValidation(String w, UUID c, ClusterValidationResultPayload payload) {
                valResults.add(payload);
            }
            @Override public Optional<ClaimedStageLease> claimNextStage(String w) {
                if (!stageClaimed.compareAndSet(false, true)) return Optional.empty();
                return Optional.of(new ClaimedStageLease(
                        new StageInfo(desc.workspaceId(), desc.projectId(), UUID.randomUUID(), UUID.randomUUID(),
                                "deploy", "Deploy", "DEPLOYMENT", 0, 1, "org/repo", "main", desc.sourceCommitSha(), 60, 0, 1),
                        attemptId, w, "lease-k8s", 1L, "KUBERNETES_DEPLOY", false,
                        Instant.now(), Instant.now().plusSeconds(60), 10, 100, 16384
                ));
            }
            @Override public void acknowledgeRunning(String w, UUID a, String lt, long f, Instant s) {}
            @Override public KubernetesExecutionDescriptor fetchKubernetesDescriptor(String w, UUID a, String lt, long f) {
                return desc;
            }
            @Override public ClusterCredentialsResult fetchKubernetesDeploymentCredentials(String w, UUID a, String lt, long f) {
                return new ClusterCredentialsResult(clusterId, "https://127.0.0.1:16443", "PEM", ephemeralSaToken, "cloudforge-deployer", true, Instant.now());
            }
            @Override public void reportKubernetesStatus(String w, UUID a, KubernetesStatusUpdatePayload payload) {}
            @Override public void reportKubernetesResult(String w, UUID a, KubernetesResultPayload payload) {
                depResults.add(payload);
            }
            @Override public void appendLogs(String w, UUID a, String lt, long f, List<LogEntry> entries) {
                uploadedLogs.addAll(entries);
            }
            @Override public void completeAttempt(String w, UUID a, CompletionPayload payload) {
                completions.add(payload);
            }
            @Override public void updateStatus(String w, String s) {}
        };

        StubKubernetesApiClient k8sStub = new StubKubernetesApiClient();
        k8sStub.rolloutPollsUntilReady = 1;
        k8sStub.expectedDigest = VALID_DIGEST;
        k8sStub.leakedTokenInEvent = ephemeralSaToken;

        try (WorkerAgentLoop loop = new WorkerAgentLoop(config, stubCp, null, null, null, k8sStub)) {
            assertThat(loop.runSingleClaimCycle()).isTrue(); // claims cluster validation
            assertThat(loop.runSingleClaimCycle()).isTrue(); // claims KUBERNETES_DEPLOY stage
        }

        assertThat(valResults).hasSize(1);
        assertThat(valResults.get(0).valid()).isTrue();
        assertThat(depResults).hasSize(1);
        assertThat(depResults.get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).resultStatus()).isEqualTo("SUCCEEDED");
        assertThat(uploadedLogs).noneSatisfy(entry -> assertThat(entry.message()).contains(ephemeralSaToken));
    }

    private static final class StubKubernetesApiClient implements KubernetesApiClient {
        boolean allowClusterAdmin = false;
        boolean allowDeployments = true;
        boolean namespaceExists = true;
        int rolloutPollsUntilReady = 1;
        int pollCount = 0;
        String expectedDigest = VALID_DIGEST;
        Map<String, String> existingDeploymentLabels = null;
        String podWaitingReason = null;
        boolean podUnschedulable = false;
        boolean progressDeadlineExceeded = false;
        String leakedTokenInEvent = null;
        Runnable onApply = null;
        boolean applied = false;

        @Override
        public ServerVersionInfo getServerVersion(ClusterConnectionContext ctx) {
            return new ServerVersionInfo("v1.31.2+k3s1", "1", "31", "linux/amd64");
        }

        @Override
        public boolean hasAppsV1Api(ClusterConnectionContext ctx) {
            return true;
        }

        @Override
        public boolean checkSelfSubjectAccess(ClusterConnectionContext ctx, String ns, String group, String resource, String verb) {
            if ("clusterrolebindings".equals(resource) || "*".equals(resource)) {
                return allowClusterAdmin;
            }
            if ("secrets".equals(resource)) {
                return false;
            }
            if ("deployments".equals(resource)) {
                return allowDeployments;
            }
            return true;
        }

        @Override
        public NamespaceSnapshot inspectNamespace(ClusterConnectionContext ctx, String namespace) {
            return new NamespaceSnapshot(namespaceExists, namespace, namespaceExists ? "Active" : "NotFound",
                    Map.of("pod-security.kubernetes.io/enforce", "restricted"));
        }

        @Override
        public Optional<DeploymentSnapshot> getDeployment(ClusterConnectionContext ctx, String namespace, String name) {
            if (!applied) {
                if (existingDeploymentLabels != null) {
                    return Optional.of(new DeploymentSnapshot("uid-foreign", name, namespace, 1, 1, 1, 1, 1, 1, 1,
                            existingDeploymentLabels, List.of(), List.of()));
                }
                return Optional.empty();
            }
            pollCount++;
            if (progressDeadlineExceeded) {
                return Optional.of(new DeploymentSnapshot("uid-dep-1", name, namespace, 2, 2, 2, 2, 1, 0, 0,
                        Map.of("app.kubernetes.io/managed-by", "cloudforge"),
                        List.of("127.0.0.1:5000/cloudforge/payments@" + expectedDigest),
                        List.of(new DeploymentConditionInfo("Progressing", "False", "ProgressDeadlineExceeded", "ReplicaSet timed out"))));
            }
            boolean readyNow = pollCount >= rolloutPollsUntilReady && podWaitingReason == null && !podUnschedulable;
            int rdy = readyNow ? 2 : 0;
            List<DeploymentConditionInfo> conds = readyNow
                    ? List.of(new DeploymentConditionInfo("Available", "True", "MinimumReplicasAvailable", "OK"),
                    new DeploymentConditionInfo("Progressing", "True", "NewReplicaSetAvailable", "OK"))
                    : List.of(new DeploymentConditionInfo("Progressing", "True", "ReplicaSetUpdated", "Updating"));
            return Optional.of(new DeploymentSnapshot("uid-dep-1", name, namespace, 2, 2, 2, 2, 2, rdy, rdy,
                    Map.of("app.kubernetes.io/managed-by", "cloudforge"),
                    List.of("127.0.0.1:5000/cloudforge/payments@" + expectedDigest), conds));
        }

        @Override
        public DeploymentSnapshot applyDeployment(ClusterConnectionContext ctx, String namespace, String name, String deploymentJson) {
            applied = true;
            if (onApply != null) onApply.run();
            return new DeploymentSnapshot("uid-dep-1", name, namespace, 2, 1, 2, 0, 0, 0, 0,
                    Map.of("app.kubernetes.io/managed-by", "cloudforge"),
                    List.of("127.0.0.1:5000/cloudforge/payments@" + expectedDigest), List.of());
        }

        @Override
        public Optional<ServiceSnapshot> getService(ClusterConnectionContext ctx, String namespace, String name) {
            return Optional.empty();
        }

        @Override
        public ServiceSnapshot applyService(ClusterConnectionContext ctx, String namespace, String name, String serviceJson) {
            return new ServiceSnapshot("uid-svc-1", name, namespace, "ClusterIP", Map.of());
        }

        @Override
        public List<PodSnapshot> listDeploymentPods(ClusterConnectionContext ctx, String namespace, Map<String, String> matchLabels) {
            if (podUnschedulable) {
                return List.of(new PodSnapshot("payments-pod-1", "Pending", false, "Unschedulable", "0/1 nodes available: insufficient cpu", false, List.of()));
            }
            if (podWaitingReason != null) {
                return List.of(new PodSnapshot("payments-pod-1", "Pending", true, null, null, false,
                        List.of(new ContainerStatusSnapshot("app", "img@" + expectedDigest, "img@" + expectedDigest,
                                false, 3, podWaitingReason, "Back-off", null))));
            }
            boolean readyNow = pollCount >= rolloutPollsUntilReady;
            String img = "127.0.0.1:5000/cloudforge/payments@" + expectedDigest;
            return List.of(
                    new PodSnapshot("payments-pod-1", "Running", true, null, null, readyNow,
                            List.of(new ContainerStatusSnapshot("app", img, img, readyNow, 0, null, null, null))),
                    new PodSnapshot("payments-pod-2", "Running", true, null, null, readyNow,
                            List.of(new ContainerStatusSnapshot("app", img, img, readyNow, 0, null, null, null)))
            );
        }

        @Override
        public List<String> listRecentWarningEvents(ClusterConnectionContext ctx, String namespace, String involvedObjectName) {
            return leakedTokenInEvent != null ? List.of("Warning: " + leakedTokenInEvent) : List.of();
        }
    }

    private record CertBundle(String pem, SSLContext sslContext) {}

    private static CertBundle generateLoopbackCert(String subjectDn) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name dn = new X500Name(subjectDn);
        Date notBefore = Date.from(Instant.now().minusSeconds(3600));
        Date notAfter = Date.from(Instant.now().plusSeconds(86400));
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter, dn, kp.getPublic()
        );
        builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1"))
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(cert);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), "changeit".toCharArray(), new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return new CertBundle(sw.toString(), ssl);
    }
}
