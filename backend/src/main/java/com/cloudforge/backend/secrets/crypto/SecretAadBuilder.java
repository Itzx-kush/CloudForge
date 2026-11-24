package com.cloudforge.backend.secrets.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds deterministic, length-prefixed binary Authenticated Additional Data (AAD)
 * binding encrypted secret values and wrapped data encryption keys to their exact
 * CloudForge security context.
 *
 * <p>Length-prefixed encoding prevents delimiter injection or ambiguity attacks and
 * causes AES-GCM authentication to fail closed if ciphertext or wrapped DEKs are
 * substituted across workspaces, projects, environments, secrets, versions, or KEKs.</p>
 */
public final class SecretAadBuilder {

    private static final String SECRET_DOMAIN_TAG = "CF-SECRET-VALUE-AAD-V1";
    private static final String DEK_WRAP_DOMAIN_TAG = "CF-DEK-WRAP-AAD-V1";

    private SecretAadBuilder() {
    }

    public static byte[] buildSecretValueAad(
            int encryptionFormatVersion,
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            int versionNumber
    ) {
        Objects.requireNonNull(workspaceId, "workspaceId is required for AAD");
        Objects.requireNonNull(projectId, "projectId is required for AAD");
        Objects.requireNonNull(environmentId, "environmentId is required for AAD");
        Objects.requireNonNull(secretId, "secretId is required for AAD");

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeUtf8Field(out, SECRET_DOMAIN_TAG);
            out.writeInt(encryptionFormatVersion);
            writeUuid(out, workspaceId);
            writeUuid(out, projectId);
            writeUuid(out, environmentId);
            writeUuid(out, secretId);
            out.writeInt(versionNumber);
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new SecretCryptoException("Failed to build secret value AAD", ex);
        }
    }

    public static byte[] buildDekWrapAad(
            int encryptionFormatVersion,
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId,
            int versionNumber,
            String kekId
    ) {
        Objects.requireNonNull(workspaceId, "workspaceId is required for DEK wrap AAD");
        Objects.requireNonNull(projectId, "projectId is required for DEK wrap AAD");
        Objects.requireNonNull(environmentId, "environmentId is required for DEK wrap AAD");
        Objects.requireNonNull(secretId, "secretId is required for DEK wrap AAD");
        if (kekId == null || kekId.isBlank()) {
            throw new IllegalArgumentException("kekId is required for DEK wrap AAD");
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeUtf8Field(out, DEK_WRAP_DOMAIN_TAG);
            out.writeInt(encryptionFormatVersion);
            writeUuid(out, workspaceId);
            writeUuid(out, projectId);
            writeUuid(out, environmentId);
            writeUuid(out, secretId);
            out.writeInt(versionNumber);
            writeUtf8Field(out, kekId);
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new SecretCryptoException("Failed to build DEK wrap AAD", ex);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void writeUtf8Field(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
