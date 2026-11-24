package com.cloudforge.backend.secrets.crypto;

import com.cloudforge.backend.config.AppProperties;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Environment-configured AES-256-GCM keyring provider ({@code env-keyring}).
 *
 * <p>Validates that every configured Key-Encryption Key (KEK) decodes to exactly
 * 256 bits (32 non-zero bytes), supports multiple historical key versions for
 * decrypting older records and performing DEK rewrapping, never auto-generates
 * replacement master keys, never persists master keys in PostgreSQL, and fails
 * closed on disabled or invalid configurations.</p>
 */
@Component
public class EnvKeyringSecretKeyProvider implements SecretKeyProvider {

    public static final String PROVIDER_TYPE = "env-keyring";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_256_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte WRAP_FORMAT_VERSION_V1 = 0x01;
    private static final Pattern VALID_KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private static final Logger log = LoggerFactory.getLogger(EnvKeyringSecretKeyProvider.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretManagementProperties properties;
    private volatile ParsedKeyringSnapshot snapshot;

    public EnvKeyringSecretKeyProvider(SecretManagementProperties properties) {
        this.properties = properties;
        this.snapshot = evaluateConfiguration(properties);
        if (this.snapshot.available()) {
            log.info(
                    "Initialized secret key provider type={} activeKeyId={} configuredKeys={}",
                    PROVIDER_TYPE,
                    this.snapshot.activeKeyId(),
                    this.snapshot.keys().size()
            );
        } else if (properties.isEnabled()) {
            log.error(
                    "Secret management is enabled but key provider failed closed: {}",
                    this.snapshot.diagnostic()
            );
        } else {
            log.info("Encrypted secret management is disabled (CLOUDFORGE_SECRETS_ENABLED=false)");
        }
    }

    /**
     * Re-evaluates configuration dynamically (primarily for unit tests mutating properties).
     */
    public synchronized void reloadFromProperties() {
        this.snapshot = evaluateConfiguration(properties);
    }

    /**
     * Validates configuration and throws {@link SecretManagementUnavailableException} if disabled
     * or misconfigured. Useful for strict startup assertions or tests.
     */
    public void validateConfigurationOrThrow() {
        ParsedKeyringSnapshot current = this.snapshot;
        if (!current.available()) {
            throw new SecretManagementUnavailableException(current.diagnostic());
        }
    }

    @Override
    public String getProviderType() {
        return StringUtils.hasText(properties.getKeyProvider())
                ? properties.getKeyProvider().trim()
                : PROVIDER_TYPE;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public boolean isAvailable() {
        return snapshot.available();
    }

    @Override
    public String getActiveKeyId() {
        ParsedKeyringSnapshot current = requireAvailable();
        return current.activeKeyId();
    }

    @Override
    public String getStatusDiagnostic() {
        return snapshot.diagnostic();
    }

    @Override
    public WrappedDek wrapDataEncryptionKey(byte[] plaintextDek, DekWrappingContext context) {
        return wrapDataEncryptionKeyWithActiveKey(plaintextDek, context);
    }

    @Override
    public WrappedDek wrapDataEncryptionKeyWithActiveKey(byte[] plaintextDek, DekWrappingContext context) {
        ParsedKeyringSnapshot current = requireAvailable();
        validateDekLength(plaintextDek);
        String targetKeyId = current.activeKeyId();
        byte[] kekBytes = current.keys().get(targetKeyId);
        if (kekBytes == null) {
            throw new SecretManagementUnavailableException("Active key ID \"" + targetKeyId + "\" is unavailable");
        }

        byte[] wrapNonce = new byte[GCM_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(wrapNonce);
        byte[] wrapAad = SecretAadBuilder.buildDekWrapAad(
                context.encryptionFormatVersion(),
                context.workspaceId(),
                context.projectId(),
                context.environmentId(),
                context.secretId(),
                context.versionNumber(),
                targetKeyId
        );

        byte[] kekCopy = Arrays.copyOf(kekBytes, kekBytes.length);
        try {
            SecretKey kek = new SecretKeySpec(kekCopy, "AES");
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, wrapNonce));
            cipher.updateAAD(wrapAad);
            byte[] encryptedDek = cipher.doFinal(plaintextDek);

            ByteBuffer envelope = ByteBuffer.allocate(1 + GCM_NONCE_BYTES + encryptedDek.length);
            envelope.put(WRAP_FORMAT_VERSION_V1);
            envelope.put(wrapNonce);
            envelope.put(encryptedDek);
            return new WrappedDek(targetKeyId, envelope.array());
        } catch (GeneralSecurityException ex) {
            throw new SecretCryptoException("Failed to wrap data encryption key under keyId=" + targetKeyId, ex);
        } finally {
            Arrays.fill(kekCopy, (byte) 0);
        }
    }

    @Override
    public byte[] unwrapDataEncryptionKey(String keyId, byte[] wrappedDek, DekWrappingContext context) {
        ParsedKeyringSnapshot current = requireAvailable();
        if (!StringUtils.hasText(keyId)) {
            throw new SecretCryptoException("Key identifier is missing on wrapped DEK");
        }
        String normalizedKeyId = keyId.trim();
        byte[] kekBytes = current.keys().get(normalizedKeyId);
        if (kekBytes == null) {
            throw new SecretManagementUnavailableException(
                    "Historical key-encryption key \"" + normalizedKeyId + "\" is not present in configured keyring"
            );
        }
        if (wrappedDek == null || wrappedDek.length < 1 + GCM_NONCE_BYTES + AES_256_BYTES + 16) {
            throw new SecretCryptoException("Wrapped DEK payload is malformed or truncated");
        }

        byte formatVersion = wrappedDek[0];
        if (formatVersion != WRAP_FORMAT_VERSION_V1) {
            throw new SecretCryptoException("Unsupported wrapped DEK format version: " + formatVersion);
        }

        byte[] wrapNonce = Arrays.copyOfRange(wrappedDek, 1, 1 + GCM_NONCE_BYTES);
        byte[] encryptedDek = Arrays.copyOfRange(wrappedDek, 1 + GCM_NONCE_BYTES, wrappedDek.length);
        byte[] wrapAad = SecretAadBuilder.buildDekWrapAad(
                context.encryptionFormatVersion(),
                context.workspaceId(),
                context.projectId(),
                context.environmentId(),
                context.secretId(),
                context.versionNumber(),
                normalizedKeyId
        );

        byte[] kekCopy = Arrays.copyOf(kekBytes, kekBytes.length);
        try {
            SecretKey kek = new SecretKeySpec(kekCopy, "AES");
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, wrapNonce));
            cipher.updateAAD(wrapAad);
            byte[] dek = cipher.doFinal(encryptedDek);
            if (dek.length != AES_256_BYTES) {
                Arrays.fill(dek, (byte) 0);
                throw new SecretCryptoException("Unwrapped DEK does not match 256-bit key length");
            }
            return dek;
        } catch (GeneralSecurityException ex) {
            throw new SecretCryptoException("Wrapped DEK authentication or decryption failed for keyId=" + normalizedKeyId, ex);
        } finally {
            Arrays.fill(kekCopy, (byte) 0);
        }
    }

    @Override
    public boolean supportsKeyId(String keyId) {
        if (!snapshot.available() || !StringUtils.hasText(keyId)) {
            return false;
        }
        return snapshot.keys().containsKey(keyId.trim());
    }

    @Override
    public Set<String> listConfiguredKeyIds() {
        if (!snapshot.available()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(snapshot.keys().keySet());
    }

    private ParsedKeyringSnapshot requireAvailable() {
        ParsedKeyringSnapshot current = this.snapshot;
        if (!properties.isEnabled()) {
            throw new SecretManagementUnavailableException(
                    "Encrypted secret management is disabled on this CloudForge instance"
            );
        }
        if (!current.available()) {
            throw new SecretManagementUnavailableException(
                    "Secret key provider is unavailable: " + current.diagnostic()
            );
        }
        return current;
    }

    private static void validateDekLength(byte[] dek) {
        if (dek == null || dek.length != AES_256_BYTES) {
            throw new SecretCryptoException("Data encryption key must be exactly 256 bits (32 bytes)");
        }
    }

    /**
     * Parses and validates keyring entries strictly without ever logging raw key bytes.
     */
    public static ParsedKeyringSnapshot evaluateConfiguration(SecretManagementProperties props) {
        if (props == null || !props.isEnabled()) {
            return new ParsedKeyringSnapshot(
                    false,
                    null,
                    Map.of(),
                    "Secret management is disabled (CLOUDFORGE_SECRETS_ENABLED=false)"
            );
        }

        String provider = props.getKeyProvider() == null ? "" : props.getKeyProvider().trim();
        if (!PROVIDER_TYPE.equals(provider)) {
            return new ParsedKeyringSnapshot(
                    false,
                    null,
                    Map.of(),
                    "Unsupported secret key provider \"" + provider + "\"; only \"" + PROVIDER_TYPE + "\" is implemented"
            );
        }

        String activeKeyId = props.getActiveKeyId() == null ? "" : props.getActiveKeyId().trim();
        if (!StringUtils.hasText(activeKeyId) || !VALID_KEY_ID_PATTERN.matcher(activeKeyId).matches()) {
            return new ParsedKeyringSnapshot(
                    false,
                    null,
                    Map.of(),
                    "CLOUDFORGE_SECRETS_ACTIVE_KEY_ID is missing or malformed"
            );
        }

        String rawKeyring = props.getKeyring();
        if (!StringUtils.hasText(rawKeyring)) {
            return new ParsedKeyringSnapshot(
                    false,
                    null,
                    Map.of(),
                    "CLOUDFORGE_SECRETS_KEYRING is empty; external 256-bit key material is required"
            );
        }

        Map<String, byte[]> parsedKeys = new LinkedHashMap<>();
        String[] entries = rawKeyring.split(",");
        for (int i = 0; i < entries.length; i++) {
            String trimmed = entries[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf(':');
            if (sep <= 0 || sep >= trimmed.length() - 1) {
                return invalidSnapshot(parsedKeys, "Malformed keyring entry at index " + i + " (expected keyId:base64Key)");
            }
            String keyId = trimmed.substring(0, sep).trim();
            String encodedKey = trimmed.substring(sep + 1).trim();

            if (!VALID_KEY_ID_PATTERN.matcher(keyId).matches()) {
                return invalidSnapshot(parsedKeys, "Invalid keyId format in keyring at index " + i);
            }
            if (parsedKeys.containsKey(keyId)) {
                return invalidSnapshot(parsedKeys, "Duplicate keyId \"" + keyId + "\" in keyring");
            }
            if (!AppProperties.isNonPlaceholder(encodedKey)) {
                return invalidSnapshot(parsedKeys, "Placeholder key material rejected for keyId \"" + keyId + "\"");
            }

            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encodedKey);
            } catch (IllegalArgumentException ex) {
                return invalidSnapshot(parsedKeys, "Invalid Base64 encoding for keyId \"" + keyId + "\"");
            }

            if (decoded.length != AES_256_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                return invalidSnapshot(
                        parsedKeys,
                        "Key \"" + keyId + "\" must decode to exactly 256 bits (32 bytes), got " + (decoded.length * 8) + " bits"
                );
            }
            if (isAllZero(decoded)) {
                Arrays.fill(decoded, (byte) 0);
                return invalidSnapshot(parsedKeys, "All-zero key material rejected for keyId \"" + keyId + "\"");
            }
            parsedKeys.put(keyId, decoded);
        }

        if (parsedKeys.isEmpty()) {
            return new ParsedKeyringSnapshot(false, null, Map.of(), "CLOUDFORGE_SECRETS_KEYRING contains no valid keys");
        }
        if (!parsedKeys.containsKey(activeKeyId)) {
            return invalidSnapshot(
                    parsedKeys,
                    "Active keyId \"" + activeKeyId + "\" was not found in CLOUDFORGE_SECRETS_KEYRING"
            );
        }

        return new ParsedKeyringSnapshot(
                true,
                activeKeyId,
                Collections.unmodifiableMap(parsedKeys),
                "Key provider env-keyring active (activeKeyId=" + activeKeyId + ", keys=" + parsedKeys.size() + ")"
        );
    }

    private static ParsedKeyringSnapshot invalidSnapshot(Map<String, byte[]> partialKeys, String message) {
        partialKeys.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        return new ParsedKeyringSnapshot(false, null, Map.of(), message);
    }

    private static boolean isAllZero(byte[] bytes) {
        int acc = 0;
        for (byte b : bytes) {
            acc |= (b & 0xFF);
        }
        return acc == 0;
    }

    public record ParsedKeyringSnapshot(
            boolean available,
            String activeKeyId,
            Map<String, byte[]> keys,
            String diagnostic
    ) {
    }
}
