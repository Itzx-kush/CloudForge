package com.cloudforge.backend.secrets.crypto;

import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoAndKeyProviderUnitTest {

    private static final SecureRandom RNG = new SecureRandom();

    private static String randomBase64Key(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }

    @Test
    void envelopeEncryptionRoundTripSupportsMultilineUnicodeAndContextAadTamperRejection() {
        String k1 = randomBase64Key(32);
        SecretManagementProperties props = new SecretManagementProperties();
        props.setEnabled(true);
        props.setKeyProvider("env-keyring");
        props.setActiveKeyId("kek-v1");
        props.setKeyring("kek-v1:" + k1);

        EnvKeyringSecretKeyProvider provider = new EnvKeyringSecretKeyProvider(props);
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.getActiveKeyId()).isEqualTo("kek-v1");

        SecretCryptoService crypto = new SecretCryptoService(provider);
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        UUID envId = UUID.randomUUID();
        UUID secId = UUID.randomUUID();
        SecretCryptoService.SecretContext ctx = new SecretCryptoService.SecretContext(wsId, projId, envId, secId, 1);

        String multilineSecret = "-----BEGIN PRIVATE KEY-----\nline-alpha-✓-🔐\nline-beta\n-----END PRIVATE KEY-----";
        SecretCryptoService.EncryptedSecretPayload encrypted =
                crypto.encrypt(multilineSecret.getBytes(StandardCharsets.UTF_8), ctx);

        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.kekId()).isEqualTo("kek-v1");
        assertThat(encrypted.algorithm()).isEqualTo("AES-256-GCM");
        assertThat(encrypted.encryptionFormatVersion()).isEqualTo(1);
        assertThat(encrypted.ciphertext()).isNotEqualTo(multilineSecret.getBytes(StandardCharsets.UTF_8));

        // Encrypting the same secret again must use a fresh random DEK and fresh 96-bit nonce
        SecretCryptoService.EncryptedSecretPayload encryptedAgain =
                crypto.encrypt(multilineSecret.getBytes(StandardCharsets.UTF_8), ctx);
        assertThat(encryptedAgain.nonce()).isNotEqualTo(encrypted.nonce());
        assertThat(encryptedAgain.ciphertext()).isNotEqualTo(encrypted.ciphertext());
        assertThat(encryptedAgain.wrappedDek()).isNotEqualTo(encrypted.wrappedDek());

        byte[] decrypted = crypto.decrypt(encrypted, ctx);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(multilineSecret);

        // Tamper ciphertext fails closed
        byte[] badCiphertext = encrypted.ciphertext().clone();
        badCiphertext[badCiphertext.length - 1] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(
                new SecretCryptoService.EncryptedSecretPayload(
                        badCiphertext, encrypted.nonce(), encrypted.wrappedDek(),
                        encrypted.kekId(), encrypted.algorithm(), encrypted.encryptionFormatVersion()
                ),
                ctx
        )).isInstanceOf(SecretCryptoException.class);

        // Tamper wrapped DEK fails closed
        byte[] badWrapped = encrypted.wrappedDek().clone();
        badWrapped[badWrapped.length - 1] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(
                new SecretCryptoService.EncryptedSecretPayload(
                        encrypted.ciphertext(), encrypted.nonce(), badWrapped,
                        encrypted.kekId(), encrypted.algorithm(), encrypted.encryptionFormatVersion()
                ),
                ctx
        )).isInstanceOf(SecretCryptoException.class);

        // Cross-environment / cross-project / cross-workspace / cross-secret / cross-version AAD mismatch fails closed
        assertThatThrownBy(() -> crypto.decrypt(
                encrypted,
                new SecretCryptoService.SecretContext(UUID.randomUUID(), projId, envId, secId, 1)
        )).isInstanceOf(SecretCryptoException.class);
        assertThatThrownBy(() -> crypto.decrypt(
                encrypted,
                new SecretCryptoService.SecretContext(wsId, projId, UUID.randomUUID(), secId, 1)
        )).isInstanceOf(SecretCryptoException.class);
        assertThatThrownBy(() -> crypto.decrypt(
                encrypted,
                new SecretCryptoService.SecretContext(wsId, projId, envId, secId, 2)
        )).isInstanceOf(SecretCryptoException.class);
    }

    @Test
    void rejectsInvalidKeyLengthsMissingKeysPlaceholdersAndPreservesHistoricalDecryptionOnRotation() {
        // Short 128-bit key fails closed
        SecretManagementProperties badLen = new SecretManagementProperties();
        badLen.setEnabled(true);
        badLen.setActiveKeyId("kek-short");
        badLen.setKeyring("kek-short:" + randomBase64Key(16));
        EnvKeyringSecretKeyProvider badProvider = new EnvKeyringSecretKeyProvider(badLen);
        assertThat(badProvider.isAvailable()).isFalse();
        assertThat(badProvider.getStatusDiagnostic()).contains("256 bits");
        assertThatThrownBy(badProvider::validateConfigurationOrThrow)
                .isInstanceOf(SecretManagementUnavailableException.class);

        // All-zero key fails closed
        SecretManagementProperties zeroKey = new SecretManagementProperties();
        zeroKey.setEnabled(true);
        zeroKey.setActiveKeyId("kek-zero");
        zeroKey.setKeyring("kek-zero:" + Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(new EnvKeyringSecretKeyProvider(zeroKey).isAvailable()).isFalse();

        // Placeholder / activeKeyId missing from keyring fails closed
        SecretManagementProperties missingActive = new SecretManagementProperties();
        missingActive.setEnabled(true);
        missingActive.setActiveKeyId("kek-v2");
        missingActive.setKeyring("kek-v1:" + randomBase64Key(32));
        assertThat(new EnvKeyringSecretKeyProvider(missingActive).isAvailable()).isFalse();

        // Unsupported provider name fails closed
        SecretManagementProperties unknownProv = new SecretManagementProperties();
        unknownProv.setEnabled(true);
        unknownProv.setKeyProvider("aws-kms");
        unknownProv.setActiveKeyId("kek-v1");
        unknownProv.setKeyring("kek-v1:" + randomBase64Key(32));
        assertThat(new EnvKeyringSecretKeyProvider(unknownProv).isAvailable()).isFalse();

        // Multi-key rotation preserves historical decryption and supports DEK-only rewrap
        String k1 = randomBase64Key(32);
        String k2 = randomBase64Key(32);
        SecretManagementProperties rotProps = new SecretManagementProperties();
        rotProps.setEnabled(true);
        rotProps.setActiveKeyId("kek-v1");
        rotProps.setKeyring("kek-v1:" + k1 + ",kek-v2:" + k2);
        EnvKeyringSecretKeyProvider provider = new EnvKeyringSecretKeyProvider(rotProps);
        SecretCryptoService crypto = new SecretCryptoService(provider);

        SecretCryptoService.SecretContext ctx = new SecretCryptoService.SecretContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1
        );
        byte[] plaintext = "rotated-master-secret-987".getBytes(StandardCharsets.UTF_8);
        SecretCryptoService.EncryptedSecretPayload encryptedUnderV1 = crypto.encrypt(plaintext, ctx);

        // Switch active key to kek-v2 while keeping kek-v1 in keyring
        rotProps.setActiveKeyId("kek-v2");
        provider.reloadFromProperties();
        assertThat(provider.getActiveKeyId()).isEqualTo("kek-v2");

        // Can still decrypt under kek-v1
        assertThat(crypto.decrypt(encryptedUnderV1, ctx)).isEqualTo(plaintext);

        // DEK-only rewrap moves wrappedDek onto kek-v2 without modifying ciphertext/nonce
        SecretKeyProvider.WrappedDek rewrapped = crypto.rewrapDataEncryptionKey(
                encryptedUnderV1.kekId(),
                encryptedUnderV1.wrappedDek(),
                encryptedUnderV1.encryptionFormatVersion(),
                ctx
        );
        assertThat(rewrapped.keyId()).isEqualTo("kek-v2");

        // Removing kek-v1 from keyring still decrypts rewrapped record under kek-v2 cleanly
        rotProps.setKeyring("kek-v2:" + k2);
        provider.reloadFromProperties();
        SecretCryptoService.EncryptedSecretPayload afterRewrap = new SecretCryptoService.EncryptedSecretPayload(
                encryptedUnderV1.ciphertext(),
                encryptedUnderV1.nonce(),
                rewrapped.wrappedKeyBytes(),
                rewrapped.keyId(),
                encryptedUnderV1.algorithm(),
                encryptedUnderV1.encryptionFormatVersion()
        );
        assertThat(crypto.decrypt(afterRewrap, ctx)).isEqualTo(plaintext);
        // Original encryptedUnderV1 fails closed once kek-v1 is retired
        assertThatThrownBy(() -> crypto.decrypt(encryptedUnderV1, ctx))
                .isInstanceOf(SecretManagementUnavailableException.class);
    }
}
