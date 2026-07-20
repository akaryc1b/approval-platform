package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified append-only audit storage, bounded administration queries and integrity verification.
 */
public interface ApprovalAuditStore extends AuditEventSink {

    AuditPage find(AuditCriteria criteria);

    AuditIntegrityResult verify(AuditIntegrityCriteria criteria);

    record AuditCriteria(
        String tenantId,
        String operatorId,
        String action,
        String aggregateType,
        String aggregateId,
        String requestId,
        String traceId,
        Instant occurredFrom,
        Instant occurredTo,
        int limit,
        int offset
    ) {
        public AuditCriteria {
            tenantId = requireText(tenantId, "tenantId");
            operatorId = normalizeOptional(operatorId);
            action = normalizeOptional(action);
            aggregateType = normalizeOptional(aggregateType);
            aggregateId = normalizeOptional(aggregateId);
            requestId = normalizeOptional(requestId);
            traceId = normalizeOptional(traceId);
            occurredFrom = Objects.requireNonNull(occurredFrom, "occurredFrom must not be null");
            occurredTo = Objects.requireNonNull(occurredTo, "occurredTo must not be null");
            if (!occurredFrom.isBefore(occurredTo)) {
                throw new IllegalArgumentException("occurredFrom must be before occurredTo");
            }
            if (limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    record AuditRecord(
        UUID eventId,
        String tenantId,
        long tenantSequence,
        String operatorId,
        String action,
        String aggregateType,
        String aggregateId,
        String schemaName,
        int schemaVersion,
        String requestId,
        String traceId,
        Instant occurredAt,
        Map<String, String> attributes,
        String previousHash,
        String payloadHash,
        String currentHash
    ) {
        public AuditRecord {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            if (tenantSequence < 1) {
                throw new IllegalArgumentException("tenantSequence must be positive");
            }
            operatorId = requireText(operatorId, "operatorId");
            action = requireText(action, "action");
            aggregateType = requireText(aggregateType, "aggregateType");
            aggregateId = requireText(aggregateId, "aggregateId");
            schemaName = requireText(schemaName, "schemaName");
            if (schemaVersion < 0) {
                throw new IllegalArgumentException("schemaVersion must not be negative");
            }
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            previousHash = requireHash(previousHash, "previousHash");
            payloadHash = requireHash(payloadHash, "payloadHash");
            currentHash = requireHash(currentHash, "currentHash");
        }
    }

    record AuditPage(
        List<AuditRecord> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public AuditPage(List<AuditRecord> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public AuditPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            if (limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    record AuditIntegrityCriteria(
        String tenantId,
        Instant occurredFrom,
        Instant occurredTo
    ) {
        public AuditIntegrityCriteria {
            tenantId = requireText(tenantId, "tenantId");
            occurredFrom = Objects.requireNonNull(occurredFrom, "occurredFrom must not be null");
            occurredTo = Objects.requireNonNull(occurredTo, "occurredTo must not be null");
            if (!occurredFrom.isBefore(occurredTo)) {
                throw new IllegalArgumentException("occurredFrom must be before occurredTo");
            }
        }
    }

    record AuditIntegrityResult(
        boolean valid,
        long checkedCount,
        UUID firstInvalidEventId,
        Long firstInvalidSequence,
        String failureCode,
        long chainStateSequence,
        String chainStateHash
    ) {
        public AuditIntegrityResult {
            if (checkedCount < 0) {
                throw new IllegalArgumentException("checkedCount must not be negative");
            }
            failureCode = normalizeOptional(failureCode);
            if (valid && (firstInvalidEventId != null
                || firstInvalidSequence != null
                || failureCode != null)) {
                throw new IllegalArgumentException("valid integrity result cannot contain a failure");
            }
            if (chainStateSequence < 0) {
                throw new IllegalArgumentException("chainStateSequence must not be negative");
            }
            chainStateHash = requireHash(chainStateHash, "chainStateHash");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
