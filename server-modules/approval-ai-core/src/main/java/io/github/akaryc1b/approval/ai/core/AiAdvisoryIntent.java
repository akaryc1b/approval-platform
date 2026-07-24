package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.Objects;

/**
 * Untrusted request intent.
 *
 * <p>Tenant, operator, authority, provider credentials and knowledge authorization are deliberately
 * absent and must be supplied by server-owned context and policy.</p>
 */
public record AiAdvisoryIntent(AiCapability capability, String resourceId) {

    public AiAdvisoryIntent {
        capability = Objects.requireNonNull(capability, "capability must not be null");
        if (resourceId == null || resourceId.isBlank() || resourceId.length() > 200) {
            throw new IllegalArgumentException("resourceId must be non-blank and bounded");
        }
        resourceId = resourceId.trim();
    }
}
