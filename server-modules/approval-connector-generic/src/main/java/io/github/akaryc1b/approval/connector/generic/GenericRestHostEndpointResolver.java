package io.github.akaryc1b.approval.connector.generic;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;

@FunctionalInterface
public interface GenericRestHostEndpointResolver {

    GenericRestHostEndpoint resolve(ConnectorContext context);
}
