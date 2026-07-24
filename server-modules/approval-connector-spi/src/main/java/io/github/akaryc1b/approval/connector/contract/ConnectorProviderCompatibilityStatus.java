package io.github.akaryc1b.approval.connector.contract;

/**
 * Closed provider compatibility classification based only on immutable registry evidence.
 */
public enum ConnectorProviderCompatibilityStatus {
    COMPATIBLE,
    PROVIDER_UNKNOWN,
    PROVIDER_DISABLED,
    CAPABILITY_UNSUPPORTED,
    OPERATION_UNREGISTERED,
    CONTRACT_TYPE_MISMATCH,
    PROTOCOL_MISMATCH
}
