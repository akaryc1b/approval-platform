package io.github.akaryc1b.approval.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read model for user-facing approval task centers. Implementations must query platform projections.
 */
public interface ApprovalTaskQuery {

    PendingTaskPage findPendingTasks(PendingTaskCriteria criteria);

    record PendingTaskCriteria(
        String tenantId,
        String assigneeId,
        String keyword,
        int limit,
        int offset
    ) {
        public PendingTaskCriteria {
            tenantId = requireText(tenantId, "tenantId");
            assigneeId = requireText(assigneeId, "assigneeId");
            keyword = normalizeOptional(keyword);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    record PendingTaskItem(
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
        Instant taskCreatedAt,
        Instant taskUpdatedAt
    ) {
    }

    record PendingTaskPage(
        List<PendingTaskItem> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public PendingTaskPage(
            List<PendingTaskItem> items,
            long total,
            int limit,
            int offset
        ) {
            this(
                items,
                total,
                limit,
                offset,
                offset + (items == null ? 0 : items.size()) < total
            );
        }

        public PendingTaskPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
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
