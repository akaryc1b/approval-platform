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

        failWhen(!request.providerKey().equals(descriptor.providerKey()),
            CredentialMaterialFailure.PROVIDER_DRIFT);
        failWhen(!request.credentialReferenceHash().equals(descriptor.referenceHash()),
            CredentialMaterialFailure.REFERENCE_DRIFT);
        failWhen(!request.tenantId().equals(descriptor.tenantId()),
            CredentialMaterialFailure.TENANT_DRIFT);
        failWhen(!request.credentialBindingHash().equals(descriptor.fingerprint()),
            CredentialMaterialFailure.BINDING_DRIFT);
        failWhen(!request.expectedVersion().versionReference().equals(descriptor.versionId()),
            CredentialMaterialFailure.VERSION_DRIFT);
        failWhen(request.materialType() != descriptor.credentialType(),
            CredentialMaterialFailure.MATERIAL_TYPE_DRIFT);
        failWhen(!request.policyRevision().equals(descriptor.policyVersion()),
            CredentialMaterialFailure.POLICY_DRIFT);
        failWhen(!descriptor.allowedOperations().contains(request.operation()),
            CredentialMaterialFailure.OPERATION_NOT_ALLOWED);

        switch (descriptor.state()) {
            case DISABLED -> failWhen(true, CredentialMaterialFailure.CREDENTIAL_DISABLED);
            case REVOKED -> failWhen(true, CredentialMaterialFailure.CREDENTIAL_REVOKED);
            case NOT_YET_VALID -> failWhen(
                true,
                CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
            );
            case EXPIRED -> failWhen(true, CredentialMaterialFailure.CREDENTIAL_EXPIRED);
            case ROTATION_PENDING -> failWhen(
                true,
                CredentialMaterialFailure.AMBIGUOUS_ACTIVE_VERSIONS
            );
            case ACTIVE -> {
                // Continue with exact bounded validity checks.
            }
        }
        failWhen(descriptor.notBefore() != null && now.isBefore(descriptor.notBefore()),
            CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID);
        failWhen(descriptor.expiresAt() != null && !now.isBefore(descriptor.expiresAt()),
            CredentialMaterialFailure.CREDENTIAL_EXPIRED);
        failWhen(!request.expectedVersion().effectiveAt(now),
            now.isBefore(request.expectedVersion().effectiveFrom())
                ? CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
                : CredentialMaterialFailure.CREDENTIAL_EXPIRED);
    }

    private static void failWhen(boolean rejected, CredentialMaterialFailure failure) {
        if (rejected) {
            throw new CredentialMaterialSourceException(failure);
        }
    }
}
