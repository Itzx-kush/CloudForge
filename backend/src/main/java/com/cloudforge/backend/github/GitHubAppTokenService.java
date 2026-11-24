package com.cloudforge.backend.github;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.exception.ExternalIntegrationException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Generates short-lived RS256 GitHub App JWTs and caches short-lived installation
 * access tokens strictly in memory. Never persists or logs key material or tokens.
 */
@Service
public class GitHubAppTokenService {

    private static final Duration JWT_CLOCK_DRIFT_BUFFER = Duration.ofSeconds(60);
    private static final Duration JWT_LIFETIME = Duration.ofMinutes(9);
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final AppProperties appProperties;
    private final Clock clock;
    private final ConcurrentHashMap<TokenCacheKey, CachedInstallationToken> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public GitHubAppTokenService(AppProperties appProperties) {
        this(appProperties, Clock.systemUTC());
    }

    GitHubAppTokenService(AppProperties appProperties, Clock clock) {
        this.appProperties = appProperties;
        this.clock = clock;
    }

    public String generateAppJwt() {
        AppProperties.GitHubApp config = appProperties.getGithubApp();
        if (!config.isConfigured()) {
            throw new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App integration is not configured on this server"
            );
        }

        String pemContent = loadPrivateKeyPem(config);
        RSAPrivateKey privateKey = parseRsaPrivateKey(pemContent);
        return signJwt(config.getAppId().trim(), privateKey);
    }

    public String signJwt(String appId, RSAPrivateKey privateKey) {
        Instant now = Instant.now(clock);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(appId)
                .issueTime(Date.from(now.minus(JWT_CLOCK_DRIFT_BUFFER)))
                .expirationTime(Date.from(now.plus(JWT_LIFETIME)))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            JWSSigner signer = new RSASSASigner(privateKey);
            signedJwt.sign(signer);
            return signedJwt.serialize();
        } catch (JOSEException ex) {
            throw new ExternalIntegrationException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to sign GitHub App authentication JWT"
            );
        }
    }

    public String getOrFetchInstallationToken(
            long githubInstallationId,
            Function<Long, GitHubInstallationToken> fetcher
    ) {
        return getOrFetchInstallationToken(
                githubInstallationId,
                GitHubTokenScope.REPOSITORY_METADATA,
                fetcher
        );
    }

    public String getOrFetchInstallationToken(
            long githubInstallationId,
            GitHubTokenScope scope,
            Function<Long, GitHubInstallationToken> fetcher
    ) {
        Instant now = Instant.now(clock);
        TokenCacheKey key = new TokenCacheKey(githubInstallationId, scope);
        CachedInstallationToken existing = tokenCache.get(key);
        if (existing != null && existing.expiresAt().isAfter(now.plus(TOKEN_REFRESH_MARGIN))) {
            return existing.token();
        }

        GitHubInstallationToken fresh = fetcher.apply(githubInstallationId);
        if (fresh == null || !StringUtils.hasText(fresh.token())) {
            throw new ExternalIntegrationException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub did not return a valid installation access token"
            );
        }

        Instant expiresAt = fresh.expiresAt() != null ? fresh.expiresAt() : now.plus(Duration.ofMinutes(50));
        tokenCache.put(key, new CachedInstallationToken(fresh.token(), expiresAt));
        return fresh.token();
    }

    public void evictInstallationToken(long githubInstallationId) {
        for (GitHubTokenScope scope : GitHubTokenScope.values()) {
            tokenCache.remove(new TokenCacheKey(githubInstallationId, scope));
        }
    }

    public void evictInstallationToken(long githubInstallationId, GitHubTokenScope scope) {
        tokenCache.remove(new TokenCacheKey(githubInstallationId, scope));
    }

    public RSAPrivateKey parseRsaPrivateKey(String rawPem) {
        if (!StringUtils.hasText(rawPem)) {
            throw new ExternalIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App private key is not configured"
            );
        }

        String normalizedPem = rawPem.trim().replace("\\n", "\n").replace("\r\n", "\n");
        try (PEMParser parser = new PEMParser(new StringReader(normalizedPem))) {
            Object parsed = parser.readObject();
            if (parsed == null) {
                throw new ExternalIntegrationException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "GitHub App private key could not be parsed as valid PEM"
                );
            }

            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            PrivateKey privateKey;
            if (parsed instanceof PEMKeyPair keyPair) {
                privateKey = converter.getKeyPair(keyPair).getPrivate();
            } else if (parsed instanceof PrivateKeyInfo keyInfo) {
                privateKey = converter.getPrivateKey(keyInfo);
            } else {
                throw new ExternalIntegrationException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Unsupported GitHub App private key format"
                );
            }

            if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)) {
                throw new ExternalIntegrationException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "GitHub App private key must be an RSA key"
                );
            }
            return rsaPrivateKey;
        } catch (ExternalIntegrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ExternalIntegrationException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read GitHub App PEM private key"
            );
        }
    }

    private String loadPrivateKeyPem(AppProperties.GitHubApp config) {
        if (AppProperties.isNonPlaceholder(config.getPrivateKey())) {
            return config.getPrivateKey();
        }
        if (AppProperties.isNonPlaceholder(config.getPrivateKeyPath())) {
            try {
                return Files.readString(Path.of(config.getPrivateKeyPath().trim()), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new ExternalIntegrationException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Configured GitHub App private key file could not be read"
                );
            }
        }
        throw new ExternalIntegrationException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GitHub App private key is not configured"
        );
    }

    private record TokenCacheKey(long githubInstallationId, GitHubTokenScope scope) {
    }

    private record CachedInstallationToken(String token, Instant expiresAt) {
    }
}
