package com.cloudforge.backend.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ScrapeSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain scrapeChain(HttpSecurity http,
            @Value("${cloudforge.monitoring.token:}") String token) throws Exception {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/actuator/prometheus", "/actuator/prometheus/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().access((auth, context) -> {
                    var request = context.getRequest();
                    String header = request.getHeader("Authorization");
                    boolean allowed = token.length() >= 32 && "GET".equals(request.getMethod())
                            && header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
                    return new AuthorizationDecision(allowed);
                })).build();
    }
}
