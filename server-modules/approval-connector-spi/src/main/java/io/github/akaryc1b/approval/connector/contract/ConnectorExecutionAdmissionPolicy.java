package io.github.akaryc1b.approval.connector.contract;

/**
 * Revalidates current server evidence immediately before a later explicit invocation gate.
 */
public interface ConnectorExecutionAdmissionPolicy {

    <P, R> ConnectorExecutionAdmission admit(ConnectorExecutionAdmissionRequest<P, R> request);
}
