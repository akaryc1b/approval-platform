package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform-owned read model for approvals started or previously processed by a user.
 */
public interface ApprovalParticipationQuery {

    StartedInstancePage findStartedInstances(StartedInstanceCriteria criteria);

    ProcessedTaskPage findProcessedTasks(ProcessedTaskCriteria criteria);

    record StartedInstanceCriteria(
        String tenantId,
        String initiatorId,
        String keyword,
        int limit,
        int offset
    ) {
        public StartedInstanceCriteria {
            tenantId = requireText(tenantId, "tenantId");
            initiatorId = requireText(initiatorId, "initiatorId");
            keyword = normalizeOptional(keyword);
            validatePage(limit, offset);
        }
    }

    record ProcessedTaskCriteria(
        String tenantId,
        String operatorId,
        String keyword,
        int limit,
        int offset
    ) {
        public ProcessedTaskCriteria {
            tenantId = requireText(tenantId, "tenantId");
            operatorId = requireText(operatorId, "operatorId");
            keyword = normalizeOptional(keyword);
            validatePage(limit, offset);
        }
    }

    record StartedInstanceItem(
        UUID instanceId,
        String definitionKey,
        String businessKey,
        String initiatorId,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        List<String> attachmentIds,
        InstanceStatus status,
        String currentTaskDefinitionKey,
        String currentTaskName,
        boolean withdrawable,
        Instant createdAt,
        Instant updatedAt
    ) {
        public StartedInstanceItem {
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            status = Objects.requireNonNull(status, "status must not be null");
            currentTaskDefinitionKey = normalizeOptional(currentTaskDefinitionKey);
            currentTaskName = normalizeOptional(currentTaskName);
        }
    }

    record ProcessedTaskItem(
        UUID taskId,
        UUID instanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String businessKey,
        String initiatorId,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        InstanceStatus instanceStatus,
        Instant completedAt,
        boolean retrievable
    ) {
        public ProcessedTaskItem {
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            instanceStatus = Objects.requireNonNull(
                instanceStatus,
                "instanceStatus must not be null"
            );
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        }
    }

    record StartedInstancePage(
        List<StartedInstanceItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public StartedInstancePage(
            List<StartedInstanceItem> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, hasMore(items, total, offset));
        }

        public StartedInstancePage {
            items = items == null ? List.of() : List.copyOf(items);
            validatePageResult(total, limit, offset);
        }
    }

    record ProcessedTaskPage(
        List<ProcessedTaskItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ProcessedTaskPage(
            List<ProcessedTaskItem> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, hasMore(items, total, offset));
        }

        public ProcessedTaskPage {
            items = items == null ? List.of() : List.copyOf(items);
            validatePageResult(total, limit, offset);
        }
    }

    private static boolean hasMore(List<?> items, long total, int offset) {
        return offset + (items == null ? 0 : items.size()) < total;
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    private static void validatePageResult(long total, int limit, int offset) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        validatePage(limit, offset);
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
