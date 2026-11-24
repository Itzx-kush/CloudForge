package com.cloudforge.backend.secrets.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SecretManagementProperties.class)
public class SecretManagementConfig {
}
