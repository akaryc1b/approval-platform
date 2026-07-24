package io.github.akaryc1b.approval.connector.contract;

/**
 * Server-owned provider selection boundary. Implementations must fail closed on ambiguity.
 */
public interface ConnectorProviderSelectionPolicy {

    <P, R> ConnectorProviderSelection<P, R> select(
        ConnectorProviderRegistry registry,
        ConnectorProviderSelectionRequest<P, R> request
    );
}
