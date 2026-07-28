package io.github.akaryc1b.approval.connector.credential;

import java.time.Instant;
import java.util.Objects;

/**
 * Exact fail-closed binding and validity admission before a backend is opened.
 */
public final class CredentialMaterialAdmission {

    private CredentialMaterialAdmission() {
    }

    public static void requireAdmitted(
        CredentialMaterialRequest request,
        CredentialBindingDescriptor descriptor,
        Instant now
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(now, "now must not be null");

        reject(!request.providerKey().equals(descriptor.providerKey()),
            CredentialMaterialFailure.PROVIDER_DRIFT);
        reject(!request.credentialReferenceHash().equals(descriptor.referenceHash()),
            CredentialMaterialFailure.REFERENCE_DRIFT);
        reject(!request.tenantId().equals(descriptor.tenantId()),
            CredentialMaterialFailure.TENANT_DRIFT);
        reject(!request.credentialBindingHash().equals(descriptor.fingerprint()),
            CredentialMaterialFailure.BINDING_DRIFT);
        reject(!request.expectedVersion().versionReference().equals(descriptor.versionId()),
            CredentialMaterialFailure.VERSION_DRIFT);
        reject(request.materialType() != descriptor.credentialType(),
            CredentialMaterialFailure.MATERIAL_TYPE_DRIFT);
        reject(!request.policyRevision().equals(descriptor.policyVersion()),
            CredentialMaterialFailure.POLICY_DRIFT);
        reject(!descriptor.allowedOperations().contains(request.operation()),
            CredentialMaterialFailure.OPERATION_NOT_ALLOWED);

        switch (descriptor.state()) {
            case DISABLED -> reject(true, CredentialMaterialFailure.CREDENTIAL_DISABLED);
            case REVOKED -> reject(true, CredentialMaterialFailure.CREDENTIAL_REVOKED);
            case NOT_YET_VALID -> reject(
                true,
                CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
            );
            case EXPIRED -> reject(true, CredentialMaterialFailure.CREDENTIAL_EXPIRED);
            case ROTATION_PENDING -> reject(
                true,
                CredentialMaterialFailure.AMBIGUOUS_ACTIVE_VERSIONS
            );
            case ACTIVE -> {
                // Continue with exact bounded validity checks.
            }
        }
        reject(descriptor.notBefore() != null && now.isBefore(descriptor.notBefore()),
            CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID);
        reject(descriptor.expiresAt() != null && !now.isBefore(descriptor.expiresAt()),
            CredentialMaterialFailure.CREDENTIAL_EXPIRED);
        reject(!request.expectedVersion().effectiveAt(now),
            now.isBefore(request.expectedVersion().effectiveFrom())
                ? CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
                : CredentialMaterialFailure.CREDENTIAL_EXPIRED);
    }

    private static void reject(boolean rejected, CredentialMaterialFailure failure) {
        if (rejected) {
            throw new CredentialMaterialSourceException(failure);
        }
    }
}
