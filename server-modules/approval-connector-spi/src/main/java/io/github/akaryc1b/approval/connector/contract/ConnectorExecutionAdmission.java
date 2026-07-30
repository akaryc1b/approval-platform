package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Fail-closed admission result. Even ADMITTED requires a separate explicit invocation gate.
 */
public record ConnectorExecutionAdmission(
    ConnectorExecutionAdmissionStatus status,
    ConnectorExecutionAdmissionEvidence evidence
) {

    public ConnectorExecutionAdmission {
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        if (status != evidence.status()) {
            throw new IllegalArgumentException("admission status does not match evidence");
        }
    }

    public boolean admitted() {
        return status == ConnectorExecutionAdmissionStatus.ADMITTED;
    }

    public boolean automaticExecutionAllowed() {
        return false;
    }

    public boolean automaticRetryAllowed() {
        return false;
    }

    public boolean requiresExplicitInvocation() {
        return true;
    }
}
