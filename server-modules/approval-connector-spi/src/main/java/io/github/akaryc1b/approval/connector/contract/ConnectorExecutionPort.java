package io.github.akaryc1b.approval.connector.contract;

/**
 * Provider-neutral execution port. Network transports are adapters behind this boundary.
 */
public interface ConnectorExecutionPort<P, R> {

    ProviderDescriptor descriptor();

    ConnectorResult<R> execute(
        TrustedConnectorExecutionContext context,
        ConnectorRequest<P> request
    );
}
