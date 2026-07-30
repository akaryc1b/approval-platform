package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped read-only M5-E1 operations visibility over immutable D1-D8 evidence. */
public interface ApprovalMigrationOperationsQuery {

    int MAX_PAGE_SIZE = 200;

    OperationsSummary summarize(String tenantId);

    PlanPage findPlans(PlanCriteria criteria);

    Optional<PlanDetail> findPlan(String tenantId, UUID planId);

    InstancePage findInstances(String tenantId, UUID planId, int limit, int offset);

    record PlanCriteria(
        String tenantId,
        String definitionKey,
        PlanStatus planStatus,
        AggregateStatus aggregateStatus,
        Boolean paused,
        int limit,
        int offset
    ) {
        public PlanCriteria {
            tenantId = requireText(tenantId, "tenantId", 128);
            definitionKey = normalizeOptional(definitionKey, "definitionKey", 64);
            boundedPage(limit, offset);
        }
    }

    record OperationsSummary(
        String tenantId,
        long totalPlans,
        long consumedPlans,
        long activePlans,
        long pausedPlans,
        long unresolvedPlans,
        long completedPlans,
        long killSwitchObservedPlans,
        Instant latestAggregatedAt,
        Instant observedAt
    ) {
        public OperationsSummary {
            tenantId = requireText(tenantId, "tenantId", 128);
            requireCount(totalPlans, "totalPlans");
            requireCount(consumedPlans, "consumedPlans");
            requireCount(activePlans, "activePlans");
            requireCount(pausedPlans, "pausedPlans");
            requireCount(unresolvedPlans, "unresolvedPlans");
            requireCount(completedPlans, "completedPlans");
            requireCount(killSwitchObservedPlans, "killSwitchObservedPlans");
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        }
    }

    record PlanItem(
        UUID planId,
        String planHash,
        String definitionKey,
        int sourceReleaseVersion,
        int targetReleaseVersion,
        int selectedInstanceCount,
        PlanStatus planStatus,
        UUID intentId,
        IntentStatus intentStatus,
        Long aggregateRevision,
        AggregateStatus aggregateStatus,
        TerminalOutcome terminalOutcome,
        int exactSuccessCount,
        int terminalFailedCount,
        int unresolvedCount,
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        boolean paused,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        Instant createdAt,
        Instant consumedAt,
        Instant latestAggregatedAt,
        AggregateStatus completionStatus,
        Instant completedAt
    ) {
        public PlanItem {
            planId = Objects.requireNonNull(planId, "planId must not be null");
            planHash = requireHash(planHash, "planHash");
            definitionKey = requireText(definitionKey, "definitionKey", 64);
            positive(sourceReleaseVersion, "sourceReleaseVersion");
            positive(targetReleaseVersion, "targetReleaseVersion");
            if (selectedInstanceCount < 1 || selectedInstanceCount > 5_000) {
                throw new IllegalArgumentException("selectedInstanceCount is outside bounded range");
            }
            planStatus = Objects.requireNonNull(planStatus, "planStatus must not be null");
            nonNegative(exactSuccessCount, "exactSuccessCount");
            nonNegative(terminalFailedCount, "terminalFailedCount");
            nonNegative(unresolvedCount, "unresolvedCount");
            if (aggregateRevision == null) {
                boolean unexpectedAggregateEvidence = aggregateStatus != null
                    || terminalOutcome != null
                    || exactSuccessCount != 0
                    || terminalFailedCount != 0
                    || canaryStatus != null
                    || orchestrationStatus != null
                    || paused
                    || (pauseReason != null && pauseReason != PauseReason.NONE)
                    || killSwitchObserved
                    || latestAggregatedAt != null
                    || completionStatus != null
                    || completedAt != null;
                if (unexpectedAggregateEvidence) {
                    throw new IllegalArgumentException(
                        "plan without aggregate revision cannot expose aggregate evidence"
                    );
                }
                unresolvedCount = selectedInstanceCount;
            }
            if (exactSuccessCount + terminalFailedCount + unresolvedCount > selectedInstanceCount) {
                throw new IllegalArgumentException("plan operation counts exceed selected instances");
            }
            pauseReason = pauseReason == null ? PauseReason.NONE : pauseReason;
            if (paused != (pauseReason != PauseReason.NONE)) {
                throw new IllegalArgumentException("paused does not match pauseReason");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    record PlanPage(
        List<PlanItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public PlanPage {
            items = items == null ? List.of() : List.copyOf(items);
            requireCount(total, "total");
            boundedPage(limit, offset);
            if (items.size() > limit || hasMore != ((long) offset + items.size() < total)) {
                throw new IllegalArgumentException("plan page metadata is inconsistent");
            }
        }
    }

    record PlanDetail(
        PlanItem plan,
        String sourcePackageHash,
        String targetPackageHash,
        String inputEvidenceHash,
        String predecessorHash,
        String aggregateHash,
        String completionEvidenceHash,
        String requestId,
        String traceId,
        String auditReference
    ) {
        public PlanDetail {
            plan = Objects.requireNonNull(plan, "plan must not be null");
            sourcePackageHash = requireHash(sourcePackageHash, "sourcePackageHash");
            targetPackageHash = requireHash(targetPackageHash, "targetPackageHash");
            inputEvidenceHash = normalizeOptionalHash(inputEvidenceHash, "inputEvidenceHash");
            predecessorHash = normalizeOptionalHash(predecessorHash, "predecessorHash");
            aggregateHash = normalizeOptionalHash(aggregateHash, "aggregateHash");
            completionEvidenceHash = normalizeOptionalHash(
                completionEvidenceHash,
                "completionEvidenceHash"
            );
            requestId = requireText(requestId, "requestId", 256);
            traceId = normalizeOptional(traceId, "traceId", 256);
            auditReference = requireText(auditReference, "auditReference", 256);
        }
    }

    record InstanceItem(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        UUID attemptId,
        Integer attemptNumber,
        String attemptStatus,
        Long attemptRevision,
        String engineOutcome,
        String verificationClassification,
        String reconciliationStatus,
        String reconciliationDisposition,
        boolean exactCompletion,
        boolean bindingConflict,
        String selectedInstanceEvidenceHash,
        String latestEvidenceHash,
        Instant latestEvidenceAt
    ) {
        public InstanceItem {
            if (sequenceNo < 1 || sequenceNo > 5_000) {
                throw new IllegalArgumentException("sequenceNo is outside bounded range");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            selectedInstanceEvidenceHash = requireHash(
                selectedInstanceEvidenceHash,
                "selectedInstanceEvidenceHash"
            );
            latestEvidenceHash = normalizeOptionalHash(latestEvidenceHash, "latestEvidenceHash");
            attemptStatus = normalizeOptional(attemptStatus, "attemptStatus", 64);
            engineOutcome = normalizeOptional(engineOutcome, "engineOutcome", 64);
            verificationClassification = normalizeOptional(
                verificationClassification,
                "verificationClassification",
                96
            );
            reconciliationStatus = normalizeOptional(
                reconciliationStatus,
                "reconciliationStatus",
                64
            );
            reconciliationDisposition = normalizeOptional(
                reconciliationDisposition,
                "reconciliationDisposition",
                96
            );
        }
    }

    record InstancePage(
        UUID planId,
        List<InstanceItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public InstancePage {
            planId = Objects.requireNonNull(planId, "planId must not be null");
            items = items == null ? List.of() : List.copyOf(items);
            requireCount(total, "total");
            boundedPage(limit, offset);
            if (items.size() > limit || hasMore != ((long) offset + items.size() < total)) {
                throw new IllegalArgumentException("instance page metadata is inconsistent");
            }
        }
    }

    private static void boundedPage(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("pagination is outside the bounded range");
        }
    }

    private static void positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireCount(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String normalizeOptionalHash(String value, String name) {
        return value == null ? null : requireHash(value, name);
    }

    private static String normalizeOptional(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
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
