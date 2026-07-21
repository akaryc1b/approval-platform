package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Bounded decision cache with assignment-boundary expiry and explicit subject invalidation. */
public final class CachingApprovalResponsibilityResolver
    implements ApprovalResponsibilityResolver {

    private final ApprovalResponsibilityResolver delegate;
    private final Clock clock;
    private final Duration maximumTtl;
    private final int maximumEntries;
    private final ConcurrentMap<DecisionKey, CachedDecision> decisions =
        new ConcurrentHashMap<>();

    public CachingApprovalResponsibilityResolver(
        ApprovalResponsibilityResolver delegate,
        Clock clock,
        Duration maximumTtl,
        int maximumEntries
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maximumTtl = Objects.requireNonNull(maximumTtl, "maximumTtl must not be null");
        if (maximumTtl.isZero() || maximumTtl.isNegative()) {
            throw new IllegalArgumentException("maximumTtl must be positive");
        }
        if (maximumEntries < 1 || maximumEntries > 100_000) {
            throw new IllegalArgumentException(
                "maximumEntries must be between 1 and 100000"
            );
        }
        this.maximumEntries = maximumEntries;
    }

    @Override
    public ApprovalAuthorizationDecision resolve(
        ApprovalPrincipal principal,
        Requirement requirement,
        ApprovalResource resource
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        DecisionKey key = DecisionKey.from(principal, requirement, resource);
        Instant now = clock.instant();
        CachedDecision existing = decisions.get(key);
        if (existing != null && now.isBefore(existing.expiresAt())) {
            return existing.decision();
        }
        if (existing != null) {
            decisions.remove(key, existing);
        }
        if (decisions.size() >= maximumEntries && !decisions.containsKey(key)) {
            decisions.clear();
        }
        ApprovalAuthorizationDecision decision = delegate.resolve(
            principal,
            requirement,
            resource
        );
        decisions.put(
            key,
            new CachedDecision(decision, expiry(principal, now))
        );
        return decision;
    }

    @Override
    public void invalidate(String tenantId, String operatorId) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedOperator = requireText(operatorId, "operatorId");
        decisions.keySet().removeIf(key ->
            key.tenantId().equals(normalizedTenant)
                && key.operatorId().equals(normalizedOperator)
        );
        delegate.invalidate(normalizedTenant, normalizedOperator);
    }

    @Override
    public void clear() {
        decisions.clear();
        delegate.clear();
    }

    int cacheSize() {
        return decisions.size();
    }

    private Instant expiry(ApprovalPrincipal principal, Instant now) {
        Instant expiry = now.plus(maximumTtl);
        for (ApprovalResponsibilityAssignment assignment : principal.responsibilities()) {
            expiry = earlierFuture(expiry, assignment.effectiveFrom(), now);
            expiry = earlierFuture(expiry, assignment.effectiveUntil(), now);
        }
        return expiry;
    }

    private static Instant earlierFuture(Instant current, Instant candidate, Instant now) {
        return candidate != null && candidate.isAfter(now) && candidate.isBefore(current)
            ? candidate
            : current;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private record DecisionKey(
        String tenantId,
        String operatorId,
        int authorityFingerprint,
        int responsibilityFingerprint,
        Requirement requirement,
        ApprovalResource resource
    ) {
        private static DecisionKey from(
            ApprovalPrincipal principal,
            Requirement requirement,
            ApprovalResource resource
        ) {
            return new DecisionKey(
                principal.tenantId(),
                principal.operatorId(),
                principal.authorities().hashCode(),
                principal.responsibilities().hashCode(),
                Objects.requireNonNull(requirement, "requirement must not be null"),
                Objects.requireNonNull(resource, "resource must not be null")
            );
        }
    }

    private record CachedDecision(
        ApprovalAuthorizationDecision decision,
        Instant expiresAt
    ) {
        private CachedDecision {
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }
}
