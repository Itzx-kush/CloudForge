package com.cloudforge.backend.secrets.crypto;

import java.util.Set;
import java.util.UUID;

/**
 * Abstraction for CloudForge master Key-Encryption Key (KEK) providers.
 *
 * <p>Supports locally configured environment keyrings in Phase 3.4 while keeping
 * secret storage, envelope encryption, and rotation agnostic to future external
 * KMS providers (AWS KMS, Azure Key Vault, Google Cloud KMS, HashiCorp Vault).</p>
 */
public interface SecretKeyProvider {

    String getProviderType();

    boolean isEnabled();

    boolean isAvailable();

    String getActiveKeyId();

    /**
     * Sanitized operational status text containing zero key material or credentials.
     */
    String getStatusDiagnostic();

    /**
     * Wraps a freshly generated 256-bit Data Encryption Key (DEK) under the active KEK.
     */
    WrappedDek wrapDataEncryptionKey(byte[] plaintextDek, DekWrappingContext context);

    /**
     * Wraps an existing 256-bit DEK under the current active KEK during master key rotation.
     */
    WrappedDek wrapDataEncryptionKeyWithActiveKey(byte[] plaintextDek, DekWrappingContext context);

    /**
     * Unwraps a previously wrapped DEK using the historical KEK identified by {@code keyId}.
     * Fails closed if {@code keyId} is unavailable or authenticated wrapping verification fails.
     */
    byte[] unwrapDataEncryptionKey(String keyId, byte[] wrappedDek, DekWrappingContext context);

    boolean supportsKeyId(String keyId);

    Set<String> listConfiguredKeyIds();

    record DekWrappingContext(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            int versionNumber,
            int encryptionFormatVersion
    ) {
    }

    record WrappedDek(
            String keyId,
            byte[] wrappedKeyBytes
    ) {
    }
}
