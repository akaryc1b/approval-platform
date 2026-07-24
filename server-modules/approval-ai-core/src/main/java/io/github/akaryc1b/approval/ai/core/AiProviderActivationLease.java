package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.regex.Pattern;

/** Non-granting activation lease evidence for review-only planning. */
public record AiProviderActivationLease(
    String leaseId,
    AiVersionReferences.ProviderVersion providerVersion,
    String deploymentSnapshotHash,
    State state,
    String reasonCode,
    String evidenceHash,
    boolean providerInvocationAuthorized,
    boolean secretResolutionAuthorized,
    boolean networkAccessAuthorized,
    boolean applyAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderActivationLease {
        leaseId = requireText(leaseId, "leaseId", 160);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        deploymentSnapshotHash = requireSha256(
            deploymentSnapshotHash,
            "deploymentSnapshotHash"
        );
        state = Objects.requireNonNull(state, "state must not be null");
        reasonCode = requireText(reasonCode, "reasonCode", 120);
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        if (providerInvocationAuthorized
            || secretResolutionAuthorized
            || networkAccessAuthorized
            || applyAuthorized) {
            throw new IllegalArgumentException(
                "M6-D activation leases cannot grant execution authority"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "activation lease cannot authorize production enablement"
            );
        }
    }

    public enum State {
        NOT_GRANTED,
        DENIED,
        EXPIRED
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
