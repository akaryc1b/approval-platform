package io.github.akaryc1b.approval.connector.credential;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class CredentialRotationEvaluator {

    private final Clock clock;

    public CredentialRotationEvaluator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CredentialRotationEvidence complete(
        CredentialBindingDescriptor pendingDescriptor,
        CredentialBindingDescriptor activeDescriptor,
        String sourceVersionId,
        String sourceEvidenceHash
    ) {
        Objects.requireNonNull(pendingDescriptor, "pendingDescriptor must not be null");
        Objects.requireNonNull(activeDescriptor, "activeDescriptor must not be null");
        String resolvedSourceVersion = CredentialContractSupport.requireIdentifier(
            sourceVersionId,
            "sourceVersionId"
        );
        String resolvedSourceEvidenceHash = CredentialContractSupport.requireSha256(
            sourceEvidenceHash,
            "sourceEvidenceHash"
        );
        Instant now = clock.instant();

        if (pendingDescriptor.state() != CredentialBindingState.ROTATION_PENDING) {
            throw new IllegalArgumentException("previous descriptor is not rotation pending");
        }
        if (activeDescriptor.state() != CredentialBindingState.ACTIVE) {
            throw new IllegalArgumentException("new descriptor is not active");
        }
        if (!pendingDescriptor.reference().equals(activeDescriptor.reference())
            || !pendingDescriptor.tenantId().equals(activeDescriptor.tenantId())
            || !pendingDescriptor.providerKey().equals(activeDescriptor.providerKey())
            || pendingDescriptor.credentialType() != activeDescriptor.credentialType()
            || !pendingDescriptor.keyId().equals(activeDescriptor.keyId())) {
            throw new IllegalArgumentException("rotation descriptors do not identify one binding");
        }
        if (pendingDescriptor.versionId().equals(activeDescriptor.versionId())) {
            throw new IllegalArgumentException("rotation must activate a different version");
        }
        if (!activeDescriptor.versionId().equals(resolvedSourceVersion)) {
            throw new IllegalArgumentException("material source version does not match active descriptor");
        }
        if (activeDescriptor.notBefore() != null && now.isBefore(activeDescriptor.notBefore())) {
            throw new IllegalArgumentException("active version is not yet valid");
        }
        if (activeDescriptor.expiresAt() != null && !now.isBefore(activeDescriptor.expiresAt())) {
            throw new IllegalArgumentException("active version is expired");
        }

        return new CredentialRotationEvidence(
            CredentialRotationStatus.COMPLETED,
            activeDescriptor.referenceHash(),
            activeDescriptor.providerKey(),
            activeDescriptor.keyId(),
            pendingDescriptor.versionId(),
            activeDescriptor.versionId(),
            pendingDescriptor.fingerprint(),
            activeDescriptor.fingerprint(),
            resolvedSourceEvidenceHash,
            activeDescriptor.policyVersion(),
            now,
            Map.of("automaticPreviousVersionFallback", "false")
        );
    }
}
