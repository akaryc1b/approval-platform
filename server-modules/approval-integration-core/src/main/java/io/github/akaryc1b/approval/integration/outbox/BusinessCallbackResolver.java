package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;

/**
 * Resolves the callback connector configured for an Outbox connector key.
 */
@FunctionalInterface
public interface BusinessCallbackResolver {

    BusinessCallbackConnector resolve(String connectorKey);
}
