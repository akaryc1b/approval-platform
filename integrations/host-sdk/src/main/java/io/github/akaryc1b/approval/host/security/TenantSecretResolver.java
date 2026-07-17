package io.github.akaryc1b.approval.host.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a tenant-scoped signing secret without exposing storage details.
 */
public interface TenantSecretResolver {

    Optional<SecretMaterial> resolve(String tenantKey, String keyId);

    record SecretMaterial(String keyId, byte[] secret) {

        public SecretMaterial {
            keyId = requireText(keyId, "keyId");
            Objects.requireNonNull(secret, "secret must not be null");
            if (secret.length < 32) {
                throw new IllegalArgumentException("secret must contain at least 32 bytes");
            }
            secret = Arrays.copyOf(secret, secret.length);
        }

        @Override
        public byte[] secret() {
            return Arrays.copyOf(secret, secret.length);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
