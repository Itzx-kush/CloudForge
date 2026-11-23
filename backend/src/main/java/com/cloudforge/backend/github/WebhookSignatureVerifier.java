package com.cloudforge.backend.github;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies GitHub X-Hub-Signature-256 HMAC-SHA256 webhook signatures using
 * constant-time comparison over the original raw request payload bytes.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    public boolean verify(byte[] rawPayload, String signatureHeader, String webhookSecret) {
        if (rawPayload == null
                || !StringUtils.hasText(signatureHeader)
                || !StringUtils.hasText(webhookSecret)) {
            return false;
        }

        String trimmedHeader = signatureHeader.trim();
        if (!trimmedHeader.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            return false;
        }

        String providedHex = trimmedHeader.substring(SIGNATURE_PREFIX.length()).toLowerCase(Locale.ROOT);
        if (providedHex.length() != 64) {
            return false;
        }

        String expectedHex = computeHmacSha256Hex(rawPayload, webhookSecret);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                providedHex.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String computeSignatureHeader(byte[] rawPayload, String webhookSecret) {
        return SIGNATURE_PREFIX + computeHmacSha256Hex(rawPayload, webhookSecret);
    }

    public String computeHmacSha256Hex(byte[] rawPayload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(rawPayload);
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 algorithm unavailable", ex);
        }
    }

    public String sha256Hex(byte[] rawPayload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(rawPayload == null ? new byte[0] : rawPayload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
