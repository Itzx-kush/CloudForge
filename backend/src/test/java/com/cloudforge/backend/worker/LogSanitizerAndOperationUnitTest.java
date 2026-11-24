package com.cloudforge.backend.worker;

import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.service.LogSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerAndOperationUnitTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    @Test
    void redactsSecretsTokensHeadersPrivateKeysAndTruncatesBoundedMessages() {
        String raw = "Authorization: Bearer super_secret_worker_token_12345 "
                + "ghp_1234567890abcdef1234567890 "
                + "AKIAIOSFODNN7EXAMPLE "
                + "jdbc:postgresql://cloudforge:topsecretpass@db:5432/cloudforge "
                + "worker_token=my_secret_value_999 "
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";

        LogSanitizer.SanitizedLogResult result = sanitizer.sanitize(raw, 1000);
        assertThat(result.message())
                .doesNotContain("super_secret_worker_token_12345")
                .doesNotContain("ghp_1234567890abcdef1234567890")
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .doesNotContain("topsecretpass")
                .doesNotContain("my_secret_value_999")
                .doesNotContain("MIIEpAIBAAKCAQEA")
                .contains("[REDACTED]");

        String huge = "X".repeat(500);
        LogSanitizer.SanitizedLogResult truncated = sanitizer.sanitize(huge, 100);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.message().length()).isLessThanOrEqualTo(100);
        assertThat(truncated.message()).endsWith("[truncated]");
    }

    @Test
    void enforcesStrictAllowlistedOperationsAndHonestUnsupportedCapabilityMessages() {
        assertThat(AllowedWorkerOperation.resolveForStage(
                PipelineStageType.SOURCE_PREP, Map.of("operation", "ENV_VALIDATION")
        )).contains(AllowedWorkerOperation.ENV_VALIDATION);

        assertThat(AllowedWorkerOperation.resolveForStage(
                PipelineStageType.TEST, Map.of("operation", "CONTROLLED_SMOKE_TEST")
        )).contains(AllowedWorkerOperation.CONTROLLED_SMOKE_TEST);

        assertThat(AllowedWorkerOperation.resolveForStage(
                PipelineStageType.BUILD, Map.of("operation", "ENV_VALIDATION")
        )).isEmpty();

        assertThat(AllowedWorkerOperation.parseStrict("ARBITRARY_SHELL")).isEmpty();

        assertThat(AllowedWorkerOperation.unsupportedCapabilityMessage(PipelineStageType.BUILD, null))
                .containsIgnoringCase("Unsupported in Phase 3.3");
        assertThat(AllowedWorkerOperation.unsupportedCapabilityMessage(PipelineStageType.DEPLOYMENT, null))
                .containsIgnoringCase("Phase 3.6");
        assertThat(AllowedWorkerOperation.unsupportedCapabilityMessage(PipelineStageType.CONTAINER_PACKAGE, null))
                .containsIgnoringCase("Phase 3.5");
    }
}
