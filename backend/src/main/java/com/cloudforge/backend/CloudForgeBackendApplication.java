package com.cloudforge.backend;

import com.cloudforge.backend.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CloudForgeBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudForgeBackendApplication.class, args);
    }
}
