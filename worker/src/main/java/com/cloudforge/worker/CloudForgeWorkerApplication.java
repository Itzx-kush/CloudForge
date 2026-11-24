package com.cloudforge.worker;

import com.cloudforge.worker.agent.WorkerAgentLoop;
import com.cloudforge.worker.client.HttpControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.DockerIsolatedExecutionAdapter;
import com.cloudforge.worker.oci.BuildKitExecutionAdapter;
import com.cloudforge.worker.oci.OciRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the standalone CloudForge isolated execution-plane worker agent.
 *
 * <p>Run strictly as a trusted host-side development/build process (never inside the Spring Boot
 * control-plane web server and never inside Render web services).</p>
 */
public final class CloudForgeWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(CloudForgeWorkerApplication.class);

    private CloudForgeWorkerApplication() {
    }

    public static void main(String[] args) {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(System.getenv());
        DockerIsolatedExecutionAdapter adapter = new DockerIsolatedExecutionAdapter(config);
        BuildKitExecutionAdapter buildKitAdapter = new BuildKitExecutionAdapter(config);

        if (config.ociBuildKitEnabled()) {
            if (!buildKitAdapter.isAvailable()) {
                log.error("Configured BuildKit driver ({}) is unavailable. Refusing to fall back to privileged Docker builds.",
                        config.buildKitDriver());
                System.exit(2);
                return;
            }
        } else if (!config.sourceOnly() && !adapter.isAvailable()) {
            log.error("Local Docker daemon is not reachable. Start Docker Engine before launching CloudForge worker.");
            System.exit(2);
            return;
        }

        HttpControlPlaneClient client = new HttpControlPlaneClient(config);
        WorkerAgentLoop loop = new WorkerAgentLoop(config, client, adapter, buildKitAdapter, new OciRegistryClient());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Received shutdown signal; draining CloudForge worker {}", config.workerId());
            loop.requestGracefulShutdown();
            loop.close();
        }, "cf-worker-shutdown"));

        log.info("Starting CloudForge isolated worker {} targeting {}",
                config.workerId(), config.controlPlaneBaseUrl());
        loop.start();
    }
}
