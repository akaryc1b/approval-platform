package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic non-executable production activation review plan. */
public record AiProviderActivationPlan(
    String planId,
    String planVersion,
    AiVersionReferences.ProviderVersion providerVersion,
    String deploymentSnapshotHash,
    String reviewBundleHash,
    long killSwitchGeneration,
    Mode mode,
    Status status,
    List<String> issueCodes,
    String planHash,
    boolean leaseGranted,
    boolean secretResolutionAuthorized,
    boolean networkAccessAuthorized,
    boolean providerInvocationAuthorized,
    boolean applyAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderActivationPlan {
        planId = requireText(planId, "planId", 160);
        planVersion = requireText(planVersion, "planVersion", 120);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        deploymentSnapshotHash = requireSha256(
            deploymentSnapshotHash,
            "deploymentSnapshotHash"
        );
        reviewBundleHash = requireSha256(reviewBundleHash, "reviewBundleHash");
        if (killSwitchGeneration < 1) {
            throw new IllegalArgumentException("killSwitchGeneration must be positive");
        }
        mode = Objects.requireNonNull(mode, "mode must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        issueCodes = issueCodes == null
            ? List.of()
            : issueCodes.stream()
                .map(value -> requireText(value, "issueCode", 120))
                .distinct()
                .sorted()
                .toList();
        if (issueCodes.size() > 50) {
            throw new IllegalArgumentException("issueCodes must be bounded");
        }
        planHash = requireSha256(planHash, "planHash");
        if (leaseGranted
            || secretResolutionAuthorized
            || networkAccessAuthorized
            || providerInvocationAuthorized
            || applyAuthorized) {
            throw new IllegalArgumentException(
                "M6-D activation plans must remain non-executable"
            );
        }
        if (productionEnablementAuthorized || approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "activation plan cannot authorize production or approval automation"
            );
        }
        if (status == Status.REVIEW_READY && !issueCodes.isEmpty()) {
            throw new IllegalArgumentException("REVIEW_READY cannot contain issue codes");
        }
        if (status == Status.BLOCKED && issueCodes.isEmpty()) {
            throw new IllegalArgumentException("BLOCKED plan requires issue codes");
        }
    }

    public static AiProviderActivationPlan assemble(
        String planId,
        String planVersion,
        AiProviderActivationReviewBundle review,
        AiProviderKillSwitch killSwitch,
        AiProviderActivationLease lease,
        AiEndpointTrustAssessment endpointTrust
    ) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(killSwitch, "killSwitch must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(endpointTrust, "endpointTrust must not be null");
        List<String> issues = new ArrayList<>();
        if (review.status() != AiProviderActivationReviewBundle.Status.REVIEW_COMPLETE) {
            issues.add("AI_ACTIVATION_REVIEW_INCOMPLETE");
        }
        if (!killSwitch.providerVersion().equals(review.providerVersion())) {
            issues.add("AI_KILL_SWITCH_PROVIDER_MISMATCH");
        }
        if (!review.killSwitchEvidenceHash().equals(killSwitch.evidenceHash())) {
            issues.add("AI_KILL_SWITCH_EVIDENCE_MISMATCH");
        }
        if (!killSwitch.permitsReviewOnly()) {
            issues.add("AI_KILL_SWITCH_NOT_IN_FAULT_DRILL_MODE");
        }
        if (!lease.providerVersion().equals(review.providerVersion())) {
            issues.add("AI_ACTIVATION_LEASE_PROVIDER_MISMATCH");
        }
        if (!lease.deploymentSnapshotHash().equals(review.deploymentSnapshotHash())) {
            issues.add("AI_ACTIVATION_LEASE_DEPLOYMENT_MISMATCH");
        }
        if (lease.state() != AiProviderActivationLease.State.NOT_GRANTED) {
            issues.add("AI_ACTIVATION_LEASE_NOT_REVIEW_ONLY");
        }
        if (!review.endpointTrustAssessmentHash().equals(endpointTrust.assessmentHash())) {
            issues.add("AI_ENDPOINT_TRUST_EVIDENCE_MISMATCH");
        }
        if (endpointTrust.status() != AiEndpointTrustAssessment.Status.TRUSTED_FOR_REVIEW) {
            issues.add("AI_ENDPOINT_TRUST_BLOCKED");
        }
        List<String> normalized = issues.stream().distinct().sorted().toList();
        Status status = normalized.isEmpty() ? Status.REVIEW_READY : Status.BLOCKED;
        String hash = computeHash(
            planId,
            planVersion,
            review,
            killSwitch,
            lease,
            endpointTrust,
            status,
            normalized
        );
        return new AiProviderActivationPlan(
            planId,
            planVersion,
            review.providerVersion(),
            review.deploymentSnapshotHash(),
            review.bundleHash(),
            killSwitch.generation(),
            Mode.NON_EXECUTABLE_REVIEW_ONLY,
            status,
            normalized,
            hash,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }

    public enum Mode {
        NON_EXECUTABLE_REVIEW_ONLY
    }

    public enum Status {
        REVIEW_READY,
        BLOCKED
    }

    private static String computeHash(
        String planId,
        String planVersion,
        AiProviderActivationReviewBundle review,
        AiProviderKillSwitch killSwitch,
        AiProviderActivationLease lease,
        AiEndpointTrustAssessment endpointTrust,
        Status status,
        List<String> issues
    ) {
        String canonical = String.join(
            "|",
            requireText(planId, "planId", 160),
            requireText(planVersion, "planVersion", 120),
            review.providerVersion().providerId(),
            review.providerVersion().version(),
            review.deploymentSnapshotHash(),
            review.bundleHash(),
            Long.toString(killSwitch.generation()),
            killSwitch.evidenceHash(),
            lease.evidenceHash(),
            endpointTrust.assessmentHash(),
            status.name(),
            issues.toString()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
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
