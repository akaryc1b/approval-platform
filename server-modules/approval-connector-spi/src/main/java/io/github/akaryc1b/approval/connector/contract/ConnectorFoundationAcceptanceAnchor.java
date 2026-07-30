package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable governance anchor for the formally accepted M6-A contract foundation.
 */
public record ConnectorFoundationAcceptanceAnchor(
    String governanceStatus,
    String acceptedHead,
    long validationRunId,
    String artifactDigest,
    String acceptanceDocument
) {

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    public static final String CURRENT_GOVERNANCE_STATUS =
        "FORMALLY_ACCEPTED_CONTRACT_FOUNDATION";
    public static final String CURRENT_ACCEPTED_HEAD =
        "3bb9f28e3c72d29413bb494b814cd601ad7da3c7";
    public static final long CURRENT_VALIDATION_RUN_ID = 30060608341L;
    public static final String CURRENT_ARTIFACT_DIGEST =
        "ba46bfb1ac6b4c516af0f3aa2e3617bedf4c8c5c357306cbf8dcf96c99fa6f41";
    public static final String CURRENT_ACCEPTANCE_DOCUMENT =
        "docs/m6/M6_A_CONNECTOR_FOUNDATION_ACCEPTANCE.md";

    public ConnectorFoundationAcceptanceAnchor {
        governanceStatus = ConnectorContractSupport.requireCode(
            governanceStatus,
            "governanceStatus"
        );
        acceptedHead = requireCommitSha(acceptedHead);
        if (validationRunId <= 0) {
            throw new IllegalArgumentException("validationRunId must be positive");
        }
        artifactDigest = ConnectorContractSupport.requireSha256(
            artifactDigest,
            "artifactDigest"
        );
        acceptanceDocument = ConnectorContractSupport.requireSafeIdentifier(
            acceptanceDocument,
            "acceptanceDocument"
        );
    }

    public static ConnectorFoundationAcceptanceAnchor current() {
        return new ConnectorFoundationAcceptanceAnchor(
            CURRENT_GOVERNANCE_STATUS,
            CURRENT_ACCEPTED_HEAD,
            CURRENT_VALIDATION_RUN_ID,
            CURRENT_ARTIFACT_DIGEST,
            CURRENT_ACCEPTANCE_DOCUMENT
        );
    }

    public String canonicalEvidence() {
        return "governanceStatus=" + governanceStatus
            + "\nacceptedHead=" + acceptedHead
            + "\nvalidationRunId=" + validationRunId
            + "\nartifactDigest=" + artifactDigest
            + "\nacceptanceDocument=" + acceptanceDocument;
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    private static String requireCommitSha(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "acceptedHead must not be null"
        ).trim().toLowerCase();
        if (!COMMIT_SHA.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "acceptedHead must be a lower-case Git commit SHA"
            );
        }
        return normalized;
    }
}
