package com.cloudforge.backend.secrets.crypto;

/**
 * Thrown when authenticated encryption, key unwrapping, or context AAD verification fails.
 * Never embeds key material or plaintext secrets in exception messages.
 */
public class SecretCryptoException extends RuntimeException {

    public SecretCryptoException(String message) {
        super(message);
    }

    public SecretCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
