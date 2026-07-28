package io.github.akaryc1b.approval.connector.credential;

import java.util.Objects;

/**
 * Bounded synchronous helper that closes a lease on success and every failure path.
 */
public final class CredentialMaterialLeaseSupport {

    private CredentialMaterialLeaseSupport() {
    }

    public static void withLease(
        CredentialMaterialSource source,
        CredentialMaterialRequest request,
        CredentialMaterialLeaseUse use
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(use, "use must not be null");
        try (CredentialMaterialLease lease = source.openLease(request)) {
            use.accept(lease);
        }
    }

    @FunctionalInterface
    public interface CredentialMaterialLeaseUse {
        void accept(CredentialMaterialLease lease);
    }
}
