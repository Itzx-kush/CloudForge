package com.cloudforge.backend.security;

import com.cloudforge.backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectOriginValidatorTest {

    private RedirectOriginValidator validator;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getAuth().setFrontendBaseUrl("http://localhost:5173");
        properties.getAuth().setAllowedRedirectOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        validator = new RedirectOriginValidator(properties);
    }

    @Test
    void allowsSafeRelativePathsAndConfiguredOrigins() {
        assertThat(validator.validateOrDefault("/projects", "/"))
                .isEqualTo("http://localhost:5173/projects");
        assertThat(validator.validateOrDefault("http://localhost:5173/repositories?installation=connected", "/"))
                .isEqualTo("http://localhost:5173/repositories?installation=connected");
        assertThat(validator.validateOrDefault("http://127.0.0.1:5173/settings", "/"))
                .isEqualTo("http://127.0.0.1:5173/settings");
    }

    @Test
    void blocksOpenRedirectsAndMalformedTargets() {
        assertThat(validator.validateOrDefault("https://evil.example.com/steal", "/"))
                .isEqualTo("http://localhost:5173/");
        assertThat(validator.validateOrDefault("//evil.example.com/steal", "/"))
                .isEqualTo("http://localhost:5173/");
        assertThat(validator.validateOrDefault("/\\evil.example.com", "/"))
                .isEqualTo("http://localhost:5173/");
        assertThat(validator.validateOrDefault("javascript:alert(1)", "/"))
                .isEqualTo("http://localhost:5173/");
    }
}
