package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned delegation policy store. Implementations must not read Flowable tables.
 */
public interface ApprovalDelegationStore {

    DelegationRule create(DelegationRule rule);

    Optional<DelegationRule> findById(String tenantId, UUID ruleId);

    List<DelegationRule> findByPrincipal(
        String tenantId,
        String principalId,
        boolean includeRevoked
    );

    DelegationRule revoke(
        String tenantId,
        UUID ruleId,
        String principalId,
        String revokedBy,
        String revokeReason,
        Instant revokedAt
    );

    Optional<DelegationRule> resolveEffective(
        String tenantId,
        String principalId,
        String definitionKey,
        Instant effectiveAt
    );

    record DelegationRule(
        UUID ruleId,
        String tenantId,
        String principalId,
        String delegateId,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil,
        DelegationStatus status,
        String reason,
        String createdBy,
        Instant createdAt,
        String revokedBy,
        Instant revokedAt,
        String revokeReason,
        long version
    ) {
        public DelegationRule {
            ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            principalId = requireText(principalId, "principalId");
            delegateId = requireText(delegateId, "delegateId");
            scope = Objects.requireNonNull(scope, "scope must not be null");
            definitionKey = normalizeOptional(definitionKey);
            validFrom = Objects.requireNonNull(validFrom, "validFrom must not be null");
            validUntil = Objects.requireNonNull(validUntil, "validUntil must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            reason = requireText(reason, "reason");
            createdBy = requireText(createdBy, "createdBy");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            revokedBy = normalizeOptional(revokedBy);
            revokeReason = normalizeOptional(revokeReason);
            if (principalId.equals(delegateId)) {
                throw new IllegalArgumentException("delegateId must differ from principalId");
            }
            if (!validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
            if (scope == DelegationScope.ALL && definitionKey != null) {
                throw new IllegalArgumentException(
                    "definitionKey must be absent for an ALL delegation"
                );
            }
            if (scope == DelegationScope.DEFINITION && definitionKey == null) {
                throw new IllegalArgumentException(
                    "definitionKey is required for a DEFINITION delegation"
                );
            }
            boolean anyRevocation = revokedBy != null || revokedAt != null || revokeReason != null;
            boolean completeRevocation = revokedBy != null
                && revokedAt != null
                && revokeReason != null;
            if (status == DelegationStatus.ACTIVE && anyRevocation) {
                throw new IllegalArgumentException(
                    "active delegation rules must not contain revocation metadata"
                );
            }
            if (status == DelegationStatus.REVOKED && !completeRevocation) {
                throw new IllegalArgumentException(
                    "revoked delegation rules require complete revocation metadata"
                );
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }

        public boolean effectiveAt(Instant instant) {
            Objects.requireNonNull(instant, "instant must not be null");
            return status == DelegationStatus.ACTIVE
                && !instant.isBefore(validFrom)
                && instant.isBefore(validUntil);
        }
    }

    enum DelegationScope {
        ALL,
        DEFINITION
    }

    enum DelegationStatus {
        ACTIVE,
        REVOKED
    }

    final class DelegationConflictException extends RuntimeException {

        public DelegationConflictException(String message) {
            super(message);
        }
    }

    final class DelegationNotFoundException extends RuntimeException {

        public DelegationNotFoundException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
