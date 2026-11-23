package com.cloudforge.backend;

import com.cloudforge.backend.dto.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CloudForgeBackendApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointRemainsPublicAndReturnsUpWithLiveDatabaseCheckAndCorrelationHeader() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity("/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();

        HealthResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.application()).isEqualTo("CloudForge Backend");
        assertThat(body.status()).isEqualTo("UP");
        assertThat(body.database()).isNotNull();
        assertThat(body.database().status()).isEqualTo("UP");
        assertThat(body.database().engine()).contains("H2");
        assertThat(body.database().responseTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void actuatorHealthEndpointRemainsPublicWithoutExposingInternalDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("password");
    }

    @Test
    void unauthenticatedAccessToProtectedOrUnknownApiReturnsStructuredUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .contains("\"status\":401")
                .contains("\"error\":\"Unauthorized\"")
                .contains("\"path\":\"/api/does-not-exist\"");
    }
}
