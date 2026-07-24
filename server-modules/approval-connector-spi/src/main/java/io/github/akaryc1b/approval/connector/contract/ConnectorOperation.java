package io.github.akaryc1b.approval.connector.contract;

import io.github.akaryc1b.approval.connector.ConnectorProvider;

/**
 * Closed operation set for the first M6-A connector contract slice.
 */
public enum ConnectorOperation {
    ORGANIZATION_READ(ConnectorProvider.Capability.ORGANIZATION),
    IDENTITY_RESOLVE(ConnectorProvider.Capability.AUTHENTICATION),
    NOTIFICATION_SEND(ConnectorProvider.Capability.NOTIFICATION),
    EXTERNAL_TODO_CREATE(ConnectorProvider.Capability.EXTERNAL_TODO),
    EXTERNAL_TODO_UPDATE(ConnectorProvider.Capability.EXTERNAL_TODO),
    BUSINESS_CALLBACK_DELIVER(ConnectorProvider.Capability.BUSINESS_CALLBACK);

    private final ConnectorProvider.Capability requiredCapability;

    ConnectorOperation(ConnectorProvider.Capability requiredCapability) {
        this.requiredCapability = requiredCapability;
    }

    public ConnectorProvider.Capability requiredCapability() {
        return requiredCapability;
    }
}
