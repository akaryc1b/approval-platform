package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Two-person review evidence that remains non-authorizing. */
public record AiProviderActivationReviewBundle(
    String bundleId,
    String bundleVersion,
    AiVersionReferences.ProviderVersion providerVersion,
    String deploymentSnapshotHash,
    String readinessReportHash,
    String faultDrillReportHash,
    String changeSetHash,
    String endpointTrustAssessmentHash,
    String secretReferenceEvidenceHash,
    String killSwitchEvidenceHash,
    List<ReviewerApproval> approvals,
    Status status,
    String bundleHash,
    boolean providerInvocationAuthorized,
    boolean secretResolutionAuthorized,
    boolean networkAccessAuthorized,
    boolean applyAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderActivationReviewBundle {
        bundleId = requireText(bundleId, "bundleId", 160);
        bundleVersion = requireText(bundleVersion, "bundleVersion", 120);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        deploymentSnapshotHash = requireSha256(
            deploymentSnapshotHash,
            "deploymentSnapshotHash"
        );
        readinessReportHash = requireSha256(readinessReportHash, "readinessReportHash");
        faultDrillReportHash = requireSha256(faultDrillReportHash, "faultDrillReportHash");
        changeSetHash = requireSha256(changeSetHash, "changeSetHash");
        endpointTrustAssessmentHash = requireSha256(
            endpointTrustAssessmentHash,
            "endpointTrustAssessmentHash"
        );
        secretReferenceEvidenceHash = requireSha256(
            secretReferenceEvidenceHash,
            "secretReferenceEvidenceHash"
        );
        killSwitchEvidenceHash = requireSha256(
            killSwitchEvidenceHash,
            "killSwitchEvidenceHash"
        );
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        if (approvals.size() > 10) {
            throw new IllegalArgumentException("approvals must be bounded");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        bundleHash = requireSha256(bundleHash, "bundleHash");
        if (providerInvocationAuthorized
            || secretResolutionAuthorized
            || networkAccessAuthorized
            || applyAuthorized) {
            throw new IllegalArgumentException(
                "activation review cannot authorize execution"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "activation review cannot authorize production enablement"
            );
        }
        validateStatus(status, approvals);
        String expectedHash = computeHash(
            bundleId,
            bundleVersion,
            providerVersion,
            deploymentSnapshotHash,
            readinessReportHash,
            faultDrillReportHash,
            changeSetHash,
            endpointTrustAssessmentHash,
            secretReferenceEvidenceHash,
            killSwitchEvidenceHash,
            approvals,
            status
        );
        if (!bundleHash.equals(expectedHash)) {
            throw new IllegalArgumentException("bundleHash must match canonical review evidence");
        }
    }

    public static AiProviderActivationReviewBundle create(
        String bundleId,
        String bundleVersion,
        AiVersionReferences.ProviderVersion providerVersion,
        String deploymentSnapshotHash,
        String readinessReportHash,
        String faultDrillReportHash,
        String changeSetHash,
        String endpointTrustAssessmentHash,
        String secretReferenceEvidenceHash,
        String killSwitchEvidenceHash,
        List<ReviewerApproval> approvals
    ) {
        List<ReviewerApproval> copy = approvals == null ? List.of() : List.copyOf(approvals);
        Status status = reviewComplete(copy) ? Status.REVIEW_COMPLETE : Status.BLOCKED;
        String hash = computeHash(
            bundleId,
            bundleVersion,
            providerVersion,
            deploymentSnapshotHash,
            readinessReportHash,
            faultDrillReportHash,
            changeSetHash,
            endpointTrustAssessmentHash,
            secretReferenceEvidenceHash,
            killSwitchEvidenceHash,
            copy,
            status
        );
        return new AiProviderActivationReviewBundle(
            bundleId,
            bundleVersion,
            providerVersion,
            deploymentSnapshotHash,
            readinessReportHash,
            faultDrillReportHash,
            changeSetHash,
            endpointTrustAssessmentHash,
            secretReferenceEvidenceHash,
            killSwitchEvidenceHash,
            copy,
            status,
            hash,
            false,
            false,
            false,
            false,
            false
        );
    }

    public enum Status {
        REVIEW_COMPLETE,
        BLOCKED
    }

    public record ReviewerApproval(
        String reviewerEvidenceHash,
        Role role,
        Decision decision,
        String evidenceHash
    ) {
        public ReviewerApproval {
            reviewerEvidenceHash = requireSha256(
                reviewerEvidenceHash,
                "reviewerEvidenceHash"
            );
            role = Objects.requireNonNull(role, "role must not be null");
            decision = Objects.requireNonNull(decision, "decision must not be null");
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        }

        public static ReviewerApproval create(
            String reviewerId,
            Role role,
            Decision decision,
            String evidenceHash
        ) {
            return new ReviewerApproval(
                hash("reviewer", requireText(reviewerId, "reviewerId", 160)),
                role,
                decision,
                evidenceHash
            );
        }
    }

    public enum Role {
        SECURITY,
        PLATFORM,
        OPERATIONS
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    private static void validateStatus(Status status, List<ReviewerApproval> approvals) {
        boolean complete = reviewComplete(approvals);
        if (status == Status.REVIEW_COMPLETE && !complete) {
            throw new IllegalArgumentException(
                "REVIEW_COMPLETE requires two distinct approved reviewers and roles"
            );
        }
        if (status == Status.BLOCKED && complete) {
            throw new IllegalArgumentException(
                "complete review evidence cannot be represented as BLOCKED"
            );
        }
    }

    private static boolean reviewComplete(List<ReviewerApproval> approvals) {
        Set<String> reviewers = new HashSet<>();
        Set<Role> roles = new HashSet<>();
        for (ReviewerApproval approval : approvals) {
            if (approval.decision() != Decision.APPROVED) {
                return false;
            }
            reviewers.add(approval.reviewerEvidenceHash());
            roles.add(approval.role());
        }
        return reviewers.size() >= 2 && roles.size() >= 2;
    }

    private static String computeHash(
        String bundleId,
        String bundleVersion,
        AiVersionReferences.ProviderVersion providerVersion,
        String deploymentSnapshotHash,
        String readinessReportHash,
        String faultDrillReportHash,
        String changeSetHash,
        String endpointTrustAssessmentHash,
        String secretReferenceEvidenceHash,
        String killSwitchEvidenceHash,
        List<ReviewerApproval> approvals,
        Status status
    ) {
        List<String> fields = new ArrayList<>();
        fields.add(requireText(bundleId, "bundleId", 160));
        fields.add(requireText(bundleVersion, "bundleVersion", 120));
        fields.add(providerVersion.providerId());
        fields.add(providerVersion.version());
        fields.add(requireSha256(deploymentSnapshotHash, "deploymentSnapshotHash"));
        fields.add(requireSha256(readinessReportHash, "readinessReportHash"));
        fields.add(requireSha256(faultDrillReportHash, "faultDrillReportHash"));
        fields.add(requireSha256(changeSetHash, "changeSetHash"));
        fields.add(requireSha256(endpointTrustAssessmentHash, "endpointTrustAssessmentHash"));
        fields.add(requireSha256(secretReferenceEvidenceHash, "secretReferenceEvidenceHash"));
        fields.add(requireSha256(killSwitchEvidenceHash, "killSwitchEvidenceHash"));
        fields.add(status.name());
        approvals.stream()
            .sorted(java.util.Comparator.comparing(ReviewerApproval::reviewerEvidenceHash))
            .map(value -> frame(
                value.reviewerEvidenceHash(),
                value.role().name(),
                value.decision().name(),
                value.evidenceHash()
            ))
            .forEach(fields::add);
        return hash(fields.toArray(String[]::new));
    }

    private static String hash(String... values) {
        String canonical = frame(values);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String frame(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            String normalized = value == null ? "" : value;
            canonical.append(normalized.length())
                .append(':')
                .append(normalized);
        }
        return canonical.toString();
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
