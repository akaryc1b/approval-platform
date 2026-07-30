package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** Exact advisory policy metadata; authority escalation and post-call retry remain prohibited. */
public record AiPolicyDescriptor(
    AiVersionReferences.PolicyVersion version,
    Set<AiCapability> allowedCapabilities,
    boolean humanReviewRequired,
    boolean authoritativeDecisionAllowed,
    boolean postInvocationRetryAllowed
) {

    public AiPolicyDescriptor {
        version = Objects.requireNonNull(version, "version must not be null");
        allowedCapabilities = boundedCapabilities(allowedCapabilities);
        if (!humanReviewRequired) {
            throw new IllegalArgumentException("AI policy must require human review");
        }
        if (authoritativeDecisionAllowed) {
            throw new IllegalArgumentException("AI policy cannot authorize an authoritative decision");
        }
        if (postInvocationRetryAllowed) {
            throw new IllegalArgumentException("post-invocation provider retry is prohibited");
        }
    }

    public boolean supports(AiCapability capability) {
        return allowedCapabilities.contains(capability);
    }

    private static Set<AiCapability> boundedCapabilities(Set<AiCapability> values) {
        Set<AiCapability> copy = values == null ? Set.of() : Set.copyOf(values);
        if (copy.isEmpty() || copy.size() > AiCapability.values().length) {
            throw new IllegalArgumentException("allowedCapabilities must be non-empty and bounded");
        }
        return copy;
    }
}
