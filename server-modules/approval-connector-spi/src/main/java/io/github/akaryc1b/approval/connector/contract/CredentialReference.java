package io.github.akaryc1b.approval.connector.contract;

/**
 * Opaque server-owned reference. It never contains or renders credential material.
 */
public record CredentialReference(String providerKey, String referenceId) {

    public CredentialReference {
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        referenceId = ConnectorContractSupport.requireSafeIdentifier(referenceId, "referenceId");
    }

    @Override
    public String toString() {
        return "CredentialReference[providerKey=" + providerKey + ", referenceId=<redacted>]";
    }
}
