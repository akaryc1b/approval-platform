package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Detect-only consistency checks over platform-owned approval evidence. */
public interface ApprovalConsistencyStore {

    ConsistencyCheck run(ConsistencyCheckRequest request);

    ConsistencyCheckPage findChecks(ConsistencyCheckCriteria criteria);

    ConsistencyFindingPage findFindings(ConsistencyFindingCriteria criteria);

    enum CheckScope {
        TENANT
    }

    enum CheckStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }

    enum CheckType {
        INSTANCE_TASK_STATE,
        DELEGATION_EVIDENCE,
        HANDOVER_EVIDENCE,
        COLLABORATION_POLICY,
        NOTIFICATION_DELIVERY,
        COMMENT_REVISION,
        ATTACHMENT_REFERENCE,
        AUDIT_BUSINESS_EVIDENCE
    }

    enum Severity {
        WARNING,
        ERROR,
        CRITICAL
    }

    record ConsistencyCheckRequest(
        UUID checkId,
        String tenantId,
        String requestedBy,
        String requestId,
        String traceId,
        CheckScope scope,
        Instant startedAt
    ) {
        public ConsistencyCheckRequest {
            checkId = Objects.requireNonNull(checkId, "checkId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            requestedBy = requireText(requestedBy, "requestedBy");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            scope = Objects.requireNonNull(scope, "scope must not be null");
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }

    record ConsistencyCheck(
        UUID checkId,
        String tenantId,
        String requestedBy,
        String requestId,
        String traceId,
        CheckScope scope,
        CheckStatus status,
        Instant startedAt,
        Instant completedAt,
        int findingCount,
        String errorCode,
        String errorMessage,
        long version
    ) {
        public ConsistencyCheck {
            checkId = Objects.requireNonNull(checkId, "checkId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            requestedBy = requireText(requestedBy, "requestedBy");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            scope = Objects.requireNonNull(scope, "scope must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            errorCode = normalizeOptional(errorCode);
            errorMessage = normalizeOptional(errorMessage);
            if (findingCount < 0) {
                throw new IllegalArgumentException("findingCount must not be negative");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record ConsistencyFinding(
        UUID findingId,
        UUID checkId,
        CheckType checkType,
        Severity severity,
        String aggregateType,
        String aggregateId,
        Instant detectedAt,
        Map<String, String> details,
        String suggestedAction
    ) {
        public ConsistencyFinding {
            findingId = Objects.requireNonNull(findingId, "findingId must not be null");
            checkId = Objects.requireNonNull(checkId, "checkId must not be null");
            checkType = Objects.requireNonNull(checkType, "checkType must not be null");
            severity = Objects.requireNonNull(severity, "severity must not be null");
            aggregateType = requireText(aggregateType, "aggregateType");
            aggregateId = requireText(aggregateId, "aggregateId");
            detectedAt = Objects.requireNonNull(detectedAt, "detectedAt must not be null");
            details = details == null ? Map.of() : Map.copyOf(details);
            suggestedAction = requireText(suggestedAction, "suggestedAction");
        }
    }

    record ConsistencyCheckCriteria(
        String tenantId,
        CheckStatus status,
        int limit,
        int offset
    ) {
        public ConsistencyCheckCriteria {
            tenantId = requireText(tenantId, "tenantId");
            validatePage(limit, offset);
        }
    }

    record ConsistencyFindingCriteria(
        String tenantId,
        UUID checkId,
        CheckType checkType,
        Severity severity,
        int limit,
        int offset
    ) {
        public ConsistencyFindingCriteria {
            tenantId = requireText(tenantId, "tenantId");
            checkId = Objects.requireNonNull(checkId, "checkId must not be null");
            validatePage(limit, offset);
        }
    }

    record ConsistencyCheckPage(
        List<ConsistencyCheck> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ConsistencyCheckPage(
            List<ConsistencyCheck> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public ConsistencyCheckPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    record ConsistencyFindingPage(
        List<ConsistencyFinding> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ConsistencyFindingPage(
            List<ConsistencyFinding> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public ConsistencyFindingPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    final class ConsistencyCheckNotFoundException extends RuntimeException {
        public ConsistencyCheckNotFoundException(String message) {
            super(message);
        }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
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
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
