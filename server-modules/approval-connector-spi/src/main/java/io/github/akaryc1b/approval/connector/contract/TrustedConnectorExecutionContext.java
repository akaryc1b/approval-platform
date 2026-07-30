package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Trusted server-side routing context. This object is intentionally separate from client requests.
 */
public record TrustedConnectorExecutionContext(
    String tenantId,
    String providerKey,
    CredentialReference credentialReference,
    Instant requestedAt
) {

    public TrustedConnectorExecutionContext {
        tenantId = ConnectorContractSupport.requireSafeIdentifier(tenantId, "tenantId");
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        credentialReference = Objects.requireNonNull(
            credentialReference,
            "credentialReference must not be null"
        );
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (!providerKey.equals(credentialReference.providerKey())) {
            throw new IllegalArgumentException("credential reference belongs to another provider");
        }
    }
}
