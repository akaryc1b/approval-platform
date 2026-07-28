package io.github.akaryc1b.approval.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped, bounded and read-only M5-E2 diagnostics over platform-owned evidence.
 * Implementations must not dispatch engine work or mutate migration state.
 */
public interface ApprovalMigrationDiagnosticsQuery {

    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE = 10_000;
    Duration MAX_TIME_RANGE = Duration.ofDays(31);

    Optional<PlanDiagnostics> findPlanDiagnostics(String tenantId, UUID planId);

    DiagnosticInstancePage findInstances(InstanceCriteria criteria);

    Optional<InstanceDiagnostics> findInstance(
        String tenantId,
        UUID planId,
        UUID approvalInstanceId
    );

    enum InstanceSort {
        SEQUENCE_ASC,
        LATEST_EVIDENCE_ASC,
        LATEST_EVIDENCE_DESC
    }

    enum AttemptStatusFilter {
        UNPROVISIONED,
        PENDING,
        CLAIMED,
        ENGINE_REQUESTED,
        VERIFYING,
        UNKNOWN,
        RECONCILING,
        SUCCEEDED,
        BLOCKED_STALE,
        FAILED_RETRYABLE,
        FAILED_TERMINAL,
        CANCELLED
    }

    enum FailureClass {
        NONE,
        PRE_DISPATCH_REJECTED,
        ENGINE_REJECTED,
        AMBIGUOUS_UNKNOWN,
        VERIFICATION_MISMATCH,
        BINDING_CONFLICT,
        STALE_AUTHORITY,
        TERMINAL_FAILURE,
        RETRYABLE_FAILURE,
        UNCLASSIFIED
    }

    enum ReconciliationState {
        NONE,
        OPEN,
        RESOLVED_SOURCE,
        RESOLVED_TERMINAL,
        MANUAL_REVIEW_REQUIRED
    }

    record InstanceCriteria(
        String tenantId,
        UUID planId,
        UUID approvalInstanceId,
        AttemptStatusFilter attemptStatus,
        FailureClass failureClass,
        ReconciliationState reconciliationState,
        Instant from,
        Instant to,
        InstanceSort sort,
        int page,
        int pageSize
    ) {
        public InstanceCriteria {
            tenantId = requireText(tenantId, "tenantId", 128);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            sort = sort == null ? InstanceSort.SEQUENCE_ASC : sort;
            if (page < 1 || page > MAX_PAGE) {
                throw new IllegalArgumentException("page is outside the bounded range");
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize is outside the bounded range");
            }
            if (from != null && to != null) {
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException("from must not be after to");
                }
                if (Duration.between(from, to).compareTo(MAX_TIME_RANGE) > 0) {
                    throw new IllegalArgumentException("time range exceeds 31 days");
                }
            }
        }

        public int offset() {
            return Math.multiplyExact(page - 1, pageSize);
        }
    }

    record PlanDiagnostics(
        UUID planId,
        String planStatus,
        UUID intentId,
        String intentStatus,
        int selectedCount,
        int provisionedAttemptCount,
        int pendingCount,
        int claimedCount,
        int engineRequestedCount,
        int verifyingCount,
        int reconcilingCount,
        int unknownCount,
        long ambiguousUnknownCount,
        int manualReviewCount,
        int bindingConflictCount,
        int blockedStaleCount,
        int terminalFailedCount,
        int exactSuccessCount,
        int unresolvedCount,
        Long aggregateRevision,
        String aggregateStatus,
        String canaryStatus,
        String orchestrationStatus,
        UUID canaryInstanceId,
        Instant canaryRecordedAt,
        Long orchestrationRunRevision,
        String orchestrationPhase,
        Integer orchestrationRequestedLimit,
        Integer orchestrationBatchAttemptCount,
        String latestOrchestrationEvent,
        String orchestrationPauseReason,
        Instant orchestrationStartedAt,
        Instant latestOrchestrationEventAt,
        String killSwitchStatus,
        Long killSwitchExpectedRevision,
        Long killSwitchObservedRevision,
        Boolean dispatchAllowed,
        Instant killSwitchObservedAt,
        String aggregateHash,
        Instant aggregatedAt,
        String completionStatus,
        String completionEvidenceHash,
        Instant completedAt,
        Instant observedAt
    ) {
        public PlanDiagnostics {
            planId = Objects.requireNonNull(planId, "planId must not be null");
            planStatus = requireText(planStatus, "planStatus", 64);
            requireNonNegative(selectedCount, "selectedCount");
            requireNonNegative(provisionedAttemptCount, "provisionedAttemptCount");
            requireNonNegative(pendingCount, "pendingCount");
            requireNonNegative(claimedCount, "claimedCount");
            requireNonNegative(engineRequestedCount, "engineRequestedCount");
            requireNonNegative(verifyingCount, "verifyingCount");
            requireNonNegative(reconcilingCount, "reconcilingCount");
            requireNonNegative(unknownCount, "unknownCount");
            if (ambiguousUnknownCount < 0) {
                throw new IllegalArgumentException("ambiguousUnknownCount must not be negative");
            }
            requireNonNegative(manualReviewCount, "manualReviewCount");
            requireNonNegative(bindingConflictCount, "bindingConflictCount");
            requireNonNegative(blockedStaleCount, "blockedStaleCount");
            requireNonNegative(terminalFailedCount, "terminalFailedCount");
            requireNonNegative(exactSuccessCount, "exactSuccessCount");
            requireNonNegative(unresolvedCount, "unresolvedCount");
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        }
    }

    record DiagnosticInstanceItem(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        UUID attemptId,
        Integer attemptNumber,
        String attemptStatus,
        Long attemptRevision,
        String engineDisposition,
        String engineStableCode,
        FailureClass failureClass,
        String verificationClassification,
        Boolean verificationReadSucceeded,
        Boolean verificationTruncated,
        String verificationEvidenceHash,
        Instant verificationAt,
        ReconciliationState reconciliationState,
        String reconciliationDisposition,
        String reconciliationEvidenceHash,
        Instant reconciliationAt,
        String ownershipStatus,
        Long ownershipRevision,
        String leaseOwnerReference,
        Instant leaseUntil,
        String fencingStatus,
        Long fencingRevision,
        String fencingOwnerReference,
        Instant fencingLeaseUntil,
        String bindingResult,
        Long bindingRevision,
        String bindingEvidenceHash,
        String completionEvidenceHash,
        String selectedInstanceEvidenceHash,
        String latestEvidenceHash,
        Instant latestEvidenceAt
    ) {
        public DiagnosticInstanceItem {
            if (sequenceNo < 1 || sequenceNo > 5_000) {
                throw new IllegalArgumentException("sequenceNo is outside the bounded range");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            attemptStatus = safeValue(attemptStatus);
            failureClass = failureClass == null ? FailureClass.UNCLASSIFIED : failureClass;
            reconciliationState = reconciliationState == null
                ? ReconciliationState.NONE
                : reconciliationState;
            ownershipStatus = safeValue(ownershipStatus);
            fencingStatus = safeValue(fencingStatus);
            bindingResult = safeValue(bindingResult);
            selectedInstanceEvidenceHash = requireHash(
                selectedInstanceEvidenceHash,
                "selectedInstanceEvidenceHash"
            );
            verificationEvidenceHash = optionalHash(
                verificationEvidenceHash,
                "verificationEvidenceHash"
            );
            reconciliationEvidenceHash = optionalHash(
                reconciliationEvidenceHash,
                "reconciliationEvidenceHash"
            );
            bindingEvidenceHash = optionalHash(bindingEvidenceHash, "bindingEvidenceHash");
            completionEvidenceHash = optionalHash(
                completionEvidenceHash,
                "completionEvidenceHash"
            );
            latestEvidenceHash = optionalHash(latestEvidenceHash, "latestEvidenceHash");
        }
    }

    record DiagnosticInstancePage(
        UUID planId,
        List<DiagnosticInstanceItem> items,
        long total,
        int page,
        int pageSize,
        int totalPages,
        boolean hasMore
    ) {
        public DiagnosticInstancePage {
            planId = Objects.requireNonNull(planId, "planId must not be null");
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0 || page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("diagnostic page metadata is invalid");
            }
            int expectedPages = total == 0 ? 0 : (int) ((total - 1) / pageSize) + 1;
            if (totalPages != expectedPages || items.size() > pageSize) {
                throw new IllegalArgumentException("diagnostic page metadata is inconsistent");
            }
            if (hasMore != (page < totalPages)) {
                throw new IllegalArgumentException("diagnostic page hasMore is inconsistent");
            }
        }
    }

    record TimelineEvent(
        int order,
        String stage,
        String state,
        String evidenceHash,
        Instant happenedAt
    ) {
        public TimelineEvent {
            if (order < 1 || order > 64) {
                throw new IllegalArgumentException("timeline order is outside the bounded range");
            }
            stage = requireText(stage, "stage", 64);
            state = safeValue(state);
            evidenceHash = optionalHash(evidenceHash, "evidenceHash");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        }
    }

    record InstanceDiagnostics(
        DiagnosticInstanceItem instance,
        List<TimelineEvent> timeline,
        Instant observedAt
    ) {
        public InstanceDiagnostics {
            instance = Objects.requireNonNull(instance, "instance must not be null");
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
            if (timeline.size() > 64) {
                throw new IllegalArgumentException("timeline exceeds the bounded range");
            }
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static String safeValue(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : requireText(value, "value", 96);
    }

    private static String optionalHash(String value, String name) {
        return value == null ? null : requireHash(value, name);
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                name + " is blank or exceeds maximum length " + maximum
            );
        }
        return normalized;
    }
}
