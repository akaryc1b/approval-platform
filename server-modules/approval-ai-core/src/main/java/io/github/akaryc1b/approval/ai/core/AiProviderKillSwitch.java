package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.regex.Pattern;

/** Server-owned operational stop metadata. No state authorizes Provider invocation. */
public record AiProviderKillSwitch(
    String switchId,
    AiVersionReferences.ProviderVersion providerVersion,
    long generation,
    State state,
    String reasonCode,
    String evidenceHash,
    boolean providerInvocationAuthorized,
    boolean networkAccessAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderKillSwitch {
        switchId = requireText(switchId, "switchId", 160);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        state = Objects.requireNonNull(state, "state must not be null");
        reasonCode = requireText(reasonCode, "reasonCode", 120);
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        if (providerInvocationAuthorized || networkAccessAuthorized) {
            throw new IllegalArgumentException(
                "M6-D kill switches cannot authorize Provider or network access"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "kill switch cannot authorize production enablement"
            );
        }
    }

    public boolean permitsReviewOnly() {
        return state == State.FAULT_DRILL_ONLY;
    }

    public enum State {
        DISABLED,
        FAULT_DRILL_ONLY
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
