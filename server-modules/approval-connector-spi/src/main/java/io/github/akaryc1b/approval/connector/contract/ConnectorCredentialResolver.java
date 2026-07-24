package io.github.akaryc1b.approval.connector.contract;

/**
 * Resolves server-owned credential references only inside a bounded callback scope.
 * Implementations must not return or log raw credential material.
 */
public interface ConnectorCredentialResolver {

    <T> T withCredential(
        TrustedConnectorExecutionContext context,
        CredentialUse<T> use
    );

    @FunctionalInterface
    interface CredentialUse<T> {

        T apply(ScopedCredential credential);
    }

    interface ScopedCredential {

        CredentialReference reference();

        String keyId();

        <T> T withSecretBytes(SecretUse<T> use);

        boolean active();
    }

    @FunctionalInterface
    interface SecretUse<T> {

        T apply(byte[] secretBytes);
    }
}
