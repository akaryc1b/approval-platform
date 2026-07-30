package io.github.akaryc1b.approval.connector.credential;

import java.io.Serial;
import java.util.Objects;

public final class CredentialResolutionException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient CredentialResolutionEvidence evidence;

    public CredentialResolutionException(CredentialResolutionEvidence evidence) {
        super(message(Objects.requireNonNull(evidence, "evidence must not be null").status()));
        this.evidence = evidence;
    }

    public CredentialResolutionEvidence evidence() {
        return evidence;
    }

    private static String message(CredentialResolutionStatus status) {
        return "credential resolution failed: " + status.name();
    }
}
