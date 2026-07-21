package io.github.akaryc1b.approval.security;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned responsibility evidence supplied by a trusted enterprise identity integration. */
public record ApprovalResponsibilityAssignment(
    ApprovalEnterpriseRole role,
    ApprovalResponsibilitySourceType sourceType,
    String sourceId,
    ApprovalResourceScope scope,
    Instant effectiveFrom,
    Instant effectiveUntil,
    long version
) {

    private static final Pattern SOURCE_ID = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}"
    );

    public ApprovalResponsibilityAssignment(
        ApprovalEnterpriseRole role,
        ApprovalResponsibilitySourceType sourceType,
        String sourceId,
        ApprovalResourceScope scope
    ) {
        this(role, sourceType, sourceId, scope, null, null, 1);
    }

    public ApprovalResponsibilityAssignment {
        role = Objects.requireNonNull(role, "role must not be null");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        sourceId = requireSourceId(sourceId);
        scope = Objects.requireNonNull(scope, "scope must not be null");
        if (effectiveFrom != null && effectiveUntil != null
            && !effectiveFrom.isBefore(effectiveUntil)) {
            throw new IllegalArgumentException(
                "effectiveFrom must be before effectiveUntil"
            );
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return (effectiveFrom == null || !instant.isBefore(effectiveFrom))
            && (effectiveUntil == null || instant.isBefore(effectiveUntil));
    }

    private static String requireSourceId(String value) {
        Objects.requireNonNull(value, "sourceId must not be null");
        String normalized = value.trim();
        if (!SOURCE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("sourceId is malformed");
        }
        return normalized;
    }
}
