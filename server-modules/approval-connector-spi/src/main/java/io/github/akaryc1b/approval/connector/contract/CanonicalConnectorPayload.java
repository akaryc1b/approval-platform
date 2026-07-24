package io.github.akaryc1b.approval.connector.contract;

/**
 * Provider-neutral payload with deterministic canonical evidence.
 */
public interface CanonicalConnectorPayload {

    String canonicalPayload();

    default String canonicalPayloadHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalPayload());
    }
}
