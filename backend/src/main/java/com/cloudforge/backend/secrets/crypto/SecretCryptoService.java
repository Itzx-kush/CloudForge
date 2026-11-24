package com.cloudforge.backend.secrets.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Performs AES-256-GCM envelope encryption and decryption for secret values,
 * plus DEK-only key rewrapping for master key rotation.
 *
 * <p>Cryptographic parameters:
 * <ul>
 *   <li>Cipher: {@code AES/GCM/NoPadding} with fresh 256-bit random DEK per secret version</li>
 *   <li>Nonce: fresh 96-bit (12-byte) CSPRNG nonce per encryption and per DEK wrap</li>
 *   <li>Authentication tag: 128-bit GCM tag</li>
 *   <li>Context binding: deterministic length-prefixed AAD over workspace, project, environment,
 *       secret ID, version number, and encryption format version</li>
 * </ul>
 * </p>
 */
@Service
public class SecretCryptoService {

    public static final String ALGORITHM_AES_256_GCM = "AES-256-GCM";
    public static final int FORMAT_VERSION_V1 = 1;

    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int DEK_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeyProvider keyProvider;

    public SecretCryptoService(SecretKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Encrypts plaintext secret bytes using a freshly generated 256-bit DEK and fresh 96-bit nonce,
     * then wraps the DEK using the active KEK. Clears sensitive working byte buffers in {@code finally}.
     */
    public EncryptedSecretPayload encrypt(byte[] plaintextBytes, SecretContext context) {
        if (plaintextBytes == null || plaintextBytes.length == 0) {
            throw new IllegalArgumentException("Secret plaintext bytes must not be empty");
        }

        byte[] dek = new byte[DEK_BYTES];
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(dek);
        SECURE_RANDOM.nextBytes(nonce);

        byte[] secretAad = SecretAadBuilder.buildSecretValueAad(
                FORMAT_VERSION_V1,
                context.workspaceId(),
                context.projectId(),
                context.environmentId(),
                context.secretId(),
                context.versionNumber()
        );

        try {
            SecretKey dekKey = new SecretKeySpec(dek, "AES");
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, dekKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(secretAad);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            SecretKeyProvider.WrappedDek wrappedDek = keyProvider.wrapDataEncryptionKey(
                    dek,
                    new SecretKeyProvider.DekWrappingContext(
                            context.workspaceId(),
                            context.projectId(),
                            context.environmentId(),
                            context.secretId(),
                            context.versionNumber(),
                            FORMAT_VERSION_V1
                    )
            );

            return new EncryptedSecretPayload(
                    ciphertext,
                    nonce,
                    wrappedDek.wrappedKeyBytes(),
                    wrappedDek.keyId(),
                    ALGORITHM_AES_256_GCM,
                    FORMAT_VERSION_V1
            );
        } catch (GeneralSecurityException ex) {
            throw new SecretCryptoException("Failed to encrypt secret payload", ex);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Unwraps the version's DEK via {@link SecretKeyProvider} and decrypts the secret ciphertext,
     * verifying both DEK-wrap and secret-value GCM tags and security context AAD.
     * Callers must clear the returned {@code byte[]} immediately after use.
     */
    public byte[] decrypt(EncryptedSecretPayload payload, SecretContext context) {
        if (payload == null) {
            throw new IllegalArgumentException("Encrypted secret payload is required");
        }
        if (!ALGORITHM_AES_256_GCM.equals(payload.algorithm())) {
            throw new SecretCryptoException("Unsupported encryption algorithm: " + payload.algorithm());
        }
        if (payload.encryptionFormatVersion() != FORMAT_VERSION_V1) {
            throw new SecretCryptoException("Unsupported encryption format version: " + payload.encryptionFormatVersion());
        }
        if (payload.nonce() == null || payload.nonce().length != GCM_NONCE_BYTES) {
            throw new SecretCryptoException("Invalid GCM nonce length");
        }
        if (payload.ciphertext() == null || payload.ciphertext().length < 16) {
            throw new SecretCryptoException("Ciphertext payload is missing or truncated");
        }

        byte[] dek = null;
        try {
            dek = keyProvider.unwrapDataEncryptionKey(
                    payload.kekId(),
                    payload.wrappedDek(),
                    new SecretKeyProvider.DekWrappingContext(
                            context.workspaceId(),
                            context.projectId(),
                            context.environmentId(),
                            context.secretId(),
                            context.versionNumber(),
                            payload.encryptionFormatVersion()
                    )
            );

            byte[] secretAad = SecretAadBuilder.buildSecretValueAad(
                    payload.encryptionFormatVersion(),
                    context.workspaceId(),
                    context.projectId(),
                    context.environmentId(),
                    context.secretId(),
                    context.versionNumber()
            );

            SecretKey dekKey = new SecretKeySpec(dek, "AES");
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dekKey, new GCMParameterSpec(GCM_TAG_BITS, payload.nonce()));
            cipher.updateAAD(secretAad);
            return cipher.doFinal(payload.ciphertext());
        } catch (GeneralSecurityException ex) {
            throw new SecretCryptoException("Secret authentication or decryption failed (tampering or context mismatch)", ex);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    /**
     * Rewraps an existing version's DEK from {@code currentKekId} onto the provider's active KEK
     * without decrypting the underlying secret ciphertext.
     */
    public SecretKeyProvider.WrappedDek rewrapDataEncryptionKey(
            String currentKekId,
            byte[] existingWrappedDek,
            int encryptionFormatVersion,
            SecretContext context
    ) {
        byte[] dek = null;
        try {
            SecretKeyProvider.DekWrappingContext wrapCtx = new SecretKeyProvider.DekWrappingContext(
                    context.workspaceId(),
                    context.projectId(),
                    context.environmentId(),
                    context.secretId(),
                    context.versionNumber(),
                    encryptionFormatVersion
            );
            dek = keyProvider.unwrapDataEncryptionKey(currentKekId, existingWrappedDek, wrapCtx);
            return keyProvider.wrapDataEncryptionKeyWithActiveKey(dek, wrapCtx);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    public record SecretContext(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            int versionNumber
    ) {
    }

    public record EncryptedSecretPayload(
            byte[] ciphertext,
            byte[] nonce,
            byte[] wrappedDek,
            String kekId,
            String algorithm,
            int encryptionFormatVersion
    ) {
    }
}
