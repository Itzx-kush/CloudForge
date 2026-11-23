package com.cloudforge.backend.github;

import com.cloudforge.backend.config.AppProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAppTokenServiceTest {

    @Test
    void parsesPkcs8PemSignsValidRs256JwtAndCachesShortLivedInstallationTokens() throws Exception {
        // Generate an ephemeral RSA keypair strictly in-memory for testing (no committed keys).
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        StringWriter pemWriter = new StringWriter();
        try (JcaPEMWriter jcaWriter = new JcaPEMWriter(pemWriter)) {
            jcaWriter.writeObject(new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        }

        AppProperties properties = new AppProperties();
        properties.getGithubApp().setAppId("123456");
        properties.getGithubApp().setSlug("cloudforge-test");
        properties.getGithubApp().setPrivateKey(pemWriter.toString());

        Instant fixedNow = Instant.parse("2026-11-23T10:00:00Z");
        GitHubAppTokenService service = new GitHubAppTokenService(properties, Clock.fixed(fixedNow, ZoneOffset.UTC));

        String jwtString = service.generateAppJwt();
        SignedJWT parsed = SignedJWT.parse(jwtString);

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("123456");
        assertThat(parsed.getJWTClaimsSet().getIssueTime().toInstant())
                .isEqualTo(fixedNow.minusSeconds(60));
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(fixedNow.plusSeconds(540));
        assertThat(parsed.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();

        // Also verify PKCS#1 PEM parsing (default format downloaded from GitHub Apps)
        RSAPrivateKey parsedKey = service.parseRsaPrivateKey(pemWriter.toString().replace("\n", "\\n"));
        assertThat(parsedKey).isNotNull();

        // Verify short-lived installation token caching & eviction
        AtomicInteger fetchCalls = new AtomicInteger();
        String first = service.getOrFetchInstallationToken(777L, id -> {
            fetchCalls.incrementAndGet();
            return new GitHubInstallationToken("ghs_short_lived_1", fixedNow.plusSeconds(3600));
        });
        String second = service.getOrFetchInstallationToken(777L, id -> {
            fetchCalls.incrementAndGet();
            return new GitHubInstallationToken("ghs_short_lived_2", fixedNow.plusSeconds(3600));
        });
        assertThat(first).isEqualTo("ghs_short_lived_1");
        assertThat(second).isEqualTo("ghs_short_lived_1");
        assertThat(fetchCalls.get()).isEqualTo(1);

        service.evictInstallationToken(777L);
        String afterEvict = service.getOrFetchInstallationToken(777L, id -> {
            fetchCalls.incrementAndGet();
            return new GitHubInstallationToken("ghs_short_lived_3", fixedNow.plusSeconds(3600));
        });
        assertThat(afterEvict).isEqualTo("ghs_short_lived_3");
        assertThat(fetchCalls.get()).isEqualTo(2);
    }
}
