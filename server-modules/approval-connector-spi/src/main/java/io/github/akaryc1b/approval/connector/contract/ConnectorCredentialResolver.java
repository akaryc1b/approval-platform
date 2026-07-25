package io.github.akaryc1b.approval.connector.contract;

/**
 * Resolves server-owned credential references only inside a bounded callback scope.
 * Implementations must not return or log raw credential material.
 */
public interface ConnectorCredentialResolver {

    /**
     * Compatibility and deterministic-test API. A generic return value can be misused by trusted
     * callback code to return secret material, so production resolvers must reject this path and
     * expose an operation-bound non-returning scope instead.
     */
    @Deprecated(forRemoval = false)
    <T> T withCredential(
        TrustedConnectorExecutionContext context,
        CredentialUse<T> use
    );

    /**
     * Non-returning compatibility bridge. Production resolvers may require a stronger,
     * operation-bound request and reject this context-only method.
     */
    default void useCredential(
        TrustedConnectorExecutionContext context,
        ScopedCredentialUse use
    ) {
        withCredential(context, credential -> {
            use.accept(credential);
            return null;
        });
    }

    @FunctionalInterface
    interface CredentialUse<T> {

        T apply(ScopedCredential credential);
    }

    @FunctionalInterface
    interface ScopedCredentialUse {

        void accept(ScopedCredential credential);
    }

    interface ScopedCredential {

        CredentialReference reference();

        String keyId();

        /**
         * Compatibility and deterministic-test API. Production scopes must reject this generic
         * return path because trusted callback code could return the supplied array or a copy.
         */
        @Deprecated(forRemoval = false)
        <T> T withSecretBytes(SecretUse<T> use);

        default void useSecretBytes(SecretBytesUse use) {
            withSecretBytes(secretBytes -> {
                use.accept(secretBytes);
                return null;
            });
        }

        boolean active();
    }

    @FunctionalInterface
    interface SecretUse<T> {

        T apply(byte[] secretBytes);
    }

    @FunctionalInterface
    interface SecretBytesUse {

        void accept(byte[] secretBytes);
    }
}
