package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only deterministic backend used by P5 and later non-production connector tests.
 */
final class DeterministicCredentialMaterialSource implements CredentialMaterialSource {

    private static final byte[] FIXTURE = "deterministic-test-material".getBytes();

    private final CredentialMaterialRequest expectedRequest;
    private final CredentialBindingDescriptor descriptor;
    private final Instant now;
    private final AtomicLong ordinal = new AtomicLong(10);
    private CredentialMaterialFailure scriptedFailure = CredentialMaterialFailure.NONE;
    private boolean releaseFailure;
    private int openCount;
    private int releaseCount;

    DeterministicCredentialMaterialSource(
        CredentialMaterialRequest expectedRequest,
        CredentialBindingDescriptor descriptor,
        Instant now
    ) {
        this.expectedRequest = Objects.requireNonNull(expectedRequest);
        this.descriptor = Objects.requireNonNull(descriptor);
        this.now = Objects.requireNonNull(now);
    }

    @Override
    public CredentialMaterialLease openLease(CredentialMaterialRequest request) {
        openCount++;
        if (scriptedFailure != CredentialMaterialFailure.NONE) {
            throw new CredentialMaterialSourceException(scriptedFailure);
        }
        requireExact(request);
        CredentialMaterialAdmission.requireAdmitted(request, descriptor, now);
        long openOrdinal = ordinal.getAndIncrement();
        CredentialMaterialDescriptor materialDescriptor = CredentialMaterialDescriptor.loaded(
            request,
            CanonicalPayloadHash.sha256Utf8("deterministic-fake-source\n" + request.evidenceHash()),
            openOrdinal
        );
        byte[] owned = FIXTURE.clone();
        return CredentialMaterialLease.takeOwnership(
            request,
            materialDescriptor,
            owned,
            ordinal::getAndIncrement,
            () -> {
                releaseCount++;
                if (releaseFailure) {
                    throw new IllegalStateException("test-only release text must be redacted");
                }
            }
        );
    }

    @Override
    public MaterialScope openMaterial(
        CredentialReference reference,
        String expectedKeyId,
        String expectedVersionId
    ) {
        throw new SourceUnavailableException();
    }

    void script(CredentialMaterialFailure failure) {
        scriptedFailure = failure;
    }

    void failRelease() {
        releaseFailure = true;
    }

    int openCount() {
        return openCount;
    }

    int releaseCount() {
        return releaseCount;
    }

    static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private void requireExact(CredentialMaterialRequest request) {
        reject(!request.providerKey().equals(expectedRequest.providerKey()),
            CredentialMaterialFailure.PROVIDER_DRIFT);
        reject(!request.credentialReferenceHash().equals(
                expectedRequest.credentialReferenceHash()),
            CredentialMaterialFailure.REFERENCE_DRIFT);
        reject(!request.tenantHash().equals(expectedRequest.tenantHash()),
            CredentialMaterialFailure.TENANT_DRIFT);
        reject(!request.routePlanHash().equals(expectedRequest.routePlanHash()),
            CredentialMaterialFailure.ROUTE_DRIFT);
        reject(!request.credentialBindingHash().equals(
                expectedRequest.credentialBindingHash()),
            CredentialMaterialFailure.BINDING_DRIFT);
        reject(!request.expectedVersion().equals(expectedRequest.expectedVersion()),
            CredentialMaterialFailure.VERSION_DRIFT);
        reject(request.materialType() != expectedRequest.materialType(),
            CredentialMaterialFailure.MATERIAL_TYPE_DRIFT);
        reject(request.operation() != expectedRequest.operation(),
            CredentialMaterialFailure.OPERATION_NOT_ALLOWED);
        reject(!request.protocolProfile().equals(expectedRequest.protocolProfile()),
            CredentialMaterialFailure.PROTOCOL_DRIFT);
        reject(!request.capability().equals(expectedRequest.capability()),
            CredentialMaterialFailure.CAPABILITY_DRIFT);
        reject(request.environment() != expectedRequest.environment(),
            CredentialMaterialFailure.ENVIRONMENT_DRIFT);
        reject(!request.policyRevision().equals(expectedRequest.policyRevision()),
            CredentialMaterialFailure.POLICY_DRIFT);
    }

    private static void reject(boolean rejected, CredentialMaterialFailure failure) {
        if (rejected) {
            throw new CredentialMaterialSourceException(failure);
        }
    }
}
