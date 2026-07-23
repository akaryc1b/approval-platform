package io.github.akaryc1b.approval.domain.definition;

import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped lifecycle state and append-only transition evidence for a release package. */
public record ApprovalProcessRelease(
    String tenantId,
    String definitionKey,
    int releaseVersion,
    String releasePackageHash,
    State lifecycleState,
    long revision,
    String publishedBy,
    Instant publishedAt,
    Instant activatedAt,
    Instant deprecatedAt,
    Instant retiredAt,
    String lastTransitionBy,
    Instant lastTransitionAt,
    String lastTransitionReason,
    String lastIdempotencyKey,
    String lastRequestId,
    String lastTraceId,
    String lastAuditChainReference
) {

    public ApprovalProcessRelease {
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        requirePositive(releaseVersion, "releaseVersion");
        releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState must not be null");
        if (lifecycleState == State.DRAFT) {
            throw new IllegalArgumentException("persisted process release cannot be DRAFT");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        publishedBy = requireText(publishedBy, "publishedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        lastTransitionBy = requireText(lastTransitionBy, "lastTransitionBy");
        lastTransitionAt = Objects.requireNonNull(
            lastTransitionAt,
            "lastTransitionAt must not be null"
        );
        lastTransitionReason = requireBoundedText(
            lastTransitionReason,
            "lastTransitionReason",
            1000
        );
        lastIdempotencyKey = requireBoundedText(
            lastIdempotencyKey,
            "lastIdempotencyKey",
            200
        );
        lastRequestId = requireBoundedText(lastRequestId, "lastRequestId", 256);
        lastTraceId = normalizeOptional(lastTraceId, 256, "lastTraceId");
        lastAuditChainReference = requireBoundedText(
            lastAuditChainReference,
            "lastAuditChainReference",
            256
        );
        validateTimeline(
            publishedAt,
            activatedAt,
            deprecatedAt,
            retiredAt,
            lastTransitionAt
        );
        validateStateEvidence(lifecycleState, activatedAt, deprecatedAt, retiredAt);
    }

    public static ApprovalProcessRelease published(
        ApprovalReleasePackage releasePackage,
        Transition transition
    ) {
        Objects.requireNonNull(releasePackage, "releasePackage must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        requireIdentity(
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            transition
        );
        if (transition.fromState() != State.DRAFT
            || transition.toState() != State.PUBLISHED
            || transition.revision() != 1) {
            throw new IllegalArgumentException(
                "published release requires DRAFT -> PUBLISHED revision 1 evidence"
            );
        }
        if (!releasePackage.publishedBy().equals(transition.operatorId())
            || !releasePackage.publishedAt().equals(transition.happenedAt())) {
            throw new IllegalArgumentException(
                "release package publish evidence must match lifecycle transition"
            );
        }
        return new ApprovalProcessRelease(
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.PUBLISHED,
            transition.revision(),
            releasePackage.publishedBy(),
            releasePackage.publishedAt(),
            null,
            null,
            null,
            transition.operatorId(),
            transition.happenedAt(),
            transition.reason(),
            transition.idempotencyKey(),
            transition.requestId(),
            transition.traceId(),
            transition.auditChainReference()
        );
    }

    public ApprovalProcessRelease transitioned(Transition transition) {
        Objects.requireNonNull(transition, "transition must not be null");
        requireIdentity(tenantId, definitionKey, releaseVersion, releasePackageHash, transition);
        if (transition.fromState() != lifecycleState) {
            throw new IllegalArgumentException("transition source does not match lifecycle state");
        }
        if (transition.revision() != revision + 1) {
            throw new IllegalArgumentException("transition revision must advance exactly once");
        }
        if (transition.happenedAt().isBefore(lastTransitionAt)) {
            throw new IllegalArgumentException("transition time must not move backwards");
        }
        Instant nextActivatedAt = activatedAt;
        Instant nextDeprecatedAt = deprecatedAt;
        Instant nextRetiredAt = retiredAt;
        if (transition.toState() == State.ACTIVE) {
            nextActivatedAt = activatedAt == null ? transition.happenedAt() : activatedAt;
        } else if (transition.toState() == State.DEPRECATED) {
            nextDeprecatedAt = transition.happenedAt();
        } else if (transition.toState() == State.RETIRED) {
            nextRetiredAt = transition.happenedAt();
        }
        return new ApprovalProcessRelease(
            tenantId,
            definitionKey,
            releaseVersion,
            releasePackageHash,
            transition.toState(),
            transition.revision(),
            publishedBy,
            publishedAt,
            nextActivatedAt,
            nextDeprecatedAt,
            nextRetiredAt,
            transition.operatorId(),
            transition.happenedAt(),
            transition.reason(),
            transition.idempotencyKey(),
            transition.requestId(),
            transition.traceId(),
            transition.auditChainReference()
        );
    }

    public record Transition(
        UUID transitionId,
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String releasePackageHash,
        State fromState,
        State toState,
        long revision,
        String reason,
        String idempotencyKey,
        String operatorId,
        String requestId,
        String traceId,
        String auditChainReference,
        Instant happenedAt
    ) {
        public Transition {
            transitionId = Objects.requireNonNull(
                transitionId,
                "transitionId must not be null"
            );
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            requirePositive(releaseVersion, "releaseVersion");
            releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
            fromState = Objects.requireNonNull(fromState, "fromState must not be null");
            toState = Objects.requireNonNull(toState, "toState must not be null");
            ApprovalReleaseLifecycle.requirePermitted(fromState, toState);
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            reason = requireBoundedText(reason, "reason", 1000);
            idempotencyKey = requireBoundedText(idempotencyKey, "idempotencyKey", 200);
            operatorId = requireBoundedText(operatorId, "operatorId", 256);
            requestId = requireBoundedText(requestId, "requestId", 256);
            traceId = normalizeOptional(traceId, 256, "traceId");
            auditChainReference = requireBoundedText(
                auditChainReference,
                "auditChainReference",
                256
            );
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        }
    }

    private static void requireIdentity(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String releasePackageHash,
        Transition transition
    ) {
        if (!tenantId.equals(transition.tenantId())
            || !definitionKey.equals(transition.definitionKey())
            || releaseVersion != transition.releaseVersion()
            || !releasePackageHash.equals(transition.releasePackageHash())) {
            throw new IllegalArgumentException("transition release identity does not match");
        }
    }

    private static void validateTimeline(
        Instant publishedAt,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant retiredAt,
        Instant lastTransitionAt
    ) {
        if (lastTransitionAt.isBefore(publishedAt)
            || activatedAt != null && activatedAt.isBefore(publishedAt)
            || deprecatedAt != null
                && (activatedAt == null || deprecatedAt.isBefore(activatedAt))
            || retiredAt != null && retiredAt.isBefore(publishedAt)
            || retiredAt != null && activatedAt != null && retiredAt.isBefore(activatedAt)
            || retiredAt != null && deprecatedAt != null && retiredAt.isBefore(deprecatedAt)) {
            throw new IllegalArgumentException("release lifecycle timestamps are inconsistent");
        }
    }

    private static void validateStateEvidence(
        State state,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant retiredAt
    ) {
        boolean valid = switch (state) {
            case PUBLISHED -> activatedAt == null && deprecatedAt == null && retiredAt == null;
            case ACTIVE -> activatedAt != null && retiredAt == null;
            case DEPRECATED -> activatedAt != null && deprecatedAt != null && retiredAt == null;
            case RETIRED -> retiredAt != null;
            case DRAFT -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("lifecycle state evidence is incomplete");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireBoundedText(String value, String name, int maximum) {
        String normalized = requireText(value, name);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximum);
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
