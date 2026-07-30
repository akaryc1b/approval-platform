package io.github.akaryc1b.approval.connector.credential;

import java.io.Serial;
import java.util.Objects;

/**
 * Stable redacted lease failure with no backend or material text.
 */
public final class CredentialMaterialLeaseException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CredentialMaterialFailure failure;

    public CredentialMaterialLeaseException(CredentialMaterialFailure failure) {
        super("credential material lease failure: " + requireFailure(failure).stableCode());
        this.failure = failure;
    }

    public CredentialMaterialFailure failure() {
        return failure;
    }

    private static CredentialMaterialFailure requireFailure(CredentialMaterialFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        if (failure == CredentialMaterialFailure.NONE) {
            throw new IllegalArgumentException("failure must not be NONE");
        }
        return failure;
    }
}
