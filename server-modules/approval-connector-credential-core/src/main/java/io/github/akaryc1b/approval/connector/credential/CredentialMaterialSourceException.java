package io.github.akaryc1b.approval.connector.credential;

import java.io.Serial;
import java.util.Objects;

/**
 * Stable redacted source rejection. It never accepts arbitrary backend messages.
 */
public final class CredentialMaterialSourceException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CredentialMaterialFailure failure;

    public CredentialMaterialSourceException(CredentialMaterialFailure failure) {
        super("credential material source rejected request: " + requireFailure(failure).stableCode());
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
