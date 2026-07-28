package io.github.akaryc1b.approval.connector.credential;

import java.util.Locale;

/**
 * Closed, low-sensitivity material failure classifications.
 */
public enum CredentialMaterialFailure {
    NONE,
    BACKEND_DISABLED,
    BACKEND_NOT_SELECTED,
    SOURCE_UNAVAILABLE,
    MATERIAL_MALFORMED,
    REFERENCE_DRIFT,
    VERSION_DRIFT,
    BINDING_DRIFT,
    ROUTE_DRIFT,
    PROVIDER_DRIFT,
    TENANT_DRIFT,
    MATERIAL_TYPE_DRIFT,
    OPERATION_NOT_ALLOWED,
    PROTOCOL_DRIFT,
    CAPABILITY_DRIFT,
    ENVIRONMENT_DRIFT,
    POLICY_DRIFT,
    CREDENTIAL_DISABLED,
    CREDENTIAL_REVOKED,
    CREDENTIAL_EXPIRED,
    CREDENTIAL_NOT_YET_VALID,
    AMBIGUOUS_ACTIVE_VERSIONS,
    LEASE_CLOSED,
    CONCURRENT_ACCESS_REJECTED,
    RELEASE_FAILED,
    CANCELLED,
    TIMEOUT,
    UNKNOWN;

    public String stableCode() {
        return name().toLowerCase(Locale.ROOT);
    }
}
