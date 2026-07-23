package io.github.akaryc1b.approval.connector.contract;

/**
 * Queries authoritative provider evidence after a timeout or unknown execution result.
 */
public interface ConnectorReconciliationPort<R> {

    ProviderDescriptor descriptor();

    ConnectorReconciliationResult<R> reconcile(
        TrustedConnectorExecutionContext context,
        ConnectorReconciliationRequest request
    );
}
