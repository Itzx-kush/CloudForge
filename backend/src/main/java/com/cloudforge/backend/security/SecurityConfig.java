package com.cloudforge.backend.security;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.security.OperatorTokenAuthenticationFilter;
import com.cloudforge.backend.worker.security.WorkerAuthenticationFilter;
import com.cloudforge.backend.worker.security.WorkerCredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppProperties appProperties;
    private final CloudForgeOAuth2UserService oAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;
    private final RedirectOriginValidator redirectOriginValidator;
    private final WorkerCredentialService workerCredentialService;
    private final SecretManagementProperties secretManagementProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            AppProperties appProperties,
            CloudForgeOAuth2UserService oAuth2UserService,
            OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
            OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            RedirectOriginValidator redirectOriginValidator,
            WorkerCredentialService workerCredentialService,
            SecretManagementProperties secretManagementProperties,
            ObjectMapper objectMapper
    ) {
        this.appProperties = appProperties;
        this.oAuth2UserService = oAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.redirectOriginValidator = redirectOriginValidator;
        this.workerCredentialService = workerCredentialService;
        this.secretManagementProperties = secretManagementProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Dedicated stateless security boundary for internal trusted worker APIs ({@code /api/internal/workers/**}).
     * Completely isolated from browser OAuth/session authentication and CORS so ordinary workspace users
     * cannot impersonate workers or invoke execution completion endpoints.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain workerInternalSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/internal/workers/**")
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> { })
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("TRUSTED_WORKER")
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(
                        new WorkerAuthenticationFilter(workerCredentialService, objectMapper),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * Dedicated stateless security boundary for operator-only secret key rotation APIs
     * ({@code /api/internal/operator/secrets/**}). Isolated from browser OAuth/session
     * authentication and worker credentials.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain operatorSecretSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/internal/operator/secrets/**")
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> { })
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("SECRET_OPERATOR")
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(
                        new OperatorTokenAuthenticationFilter(secretManagementProperties, objectMapper),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");
        csrfTokenRepository.setCookieCustomizer(builder -> builder
                .sameSite("Lax")
                .secure(appProperties.getAuth().isCookieSecure()));

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Webhooks authenticate via X-Hub-Signature-256 HMAC-SHA256; /api/health is stateless read-only.
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/webhooks/github", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/api/health")
                        )
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> { })
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        // The static React shell must be publicly served so unauthenticated users can see /login.
                        // The React ProtectedRoute only guards the UI; all APIs remain backend-authenticated.
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/login", "/favicon.svg", "/assets/**",
                                "/projects", "/projects/*", "/repositories", "/deployments",
                                "/pipelines", "/monitoring", "/settings"
                        ).permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/config", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/github").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
                .addFilterBefore(
                        new OAuthConfigGuardFilter(appProperties, redirectOriginValidator),
                        OAuth2AuthorizationRequestRedirectFilter.class
                )
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(appProperties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Request-Id", "X-XSRF-TOKEN", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-Idempotent-Replay"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
