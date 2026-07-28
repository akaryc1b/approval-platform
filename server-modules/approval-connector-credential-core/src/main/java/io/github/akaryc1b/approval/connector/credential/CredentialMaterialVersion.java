package io.github.akaryc1b.approval.connector.credential;

import java.time.Instant;

/**
 * Exact immutable version reference and bounded validity evidence.
 */
public record CredentialMaterialVersion(
    String versionReference,
    Instant effectiveFrom,
    Instant expiresAt,
    String versionEvidenceHash
) {

    public CredentialMaterialVersion {
        versionReference = CredentialContractSupport.requireIdentifier(
            versionReference,
            "versionReference"
        );
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("effectiveFrom must not be null");
        }
        if (expiresAt == null || !expiresAt.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("expiresAt must be after effectiveFrom");
        }
        versionEvidenceHash = CredentialContractSupport.requireSha256(
            versionEvidenceHash,
            "versionEvidenceHash"
        );
    }

    public boolean effectiveAt(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant must not be null");
        }
        return !instant.isBefore(effectiveFrom) && instant.isBefore(expiresAt);
    }
}
